/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package org.apache.roller.weblogger.ui.controllers.core;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.UserToken;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.core.RollerLoginSessionManager;
import org.apache.roller.weblogger.util.GenericThrottle;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Serves the public forgot-password and reset-password flows.
 *
 * <p><b>Enumeration-proof by construction.</b> {@code forgotSend} returns the
 * exact same confirmation message whether the submitted identifier names an
 * existing enabled account, an unknown one, a disabled one, or was refused by
 * the throttle -- see the single {@code return} at the bottom of that method.
 * Anything that could make the response differ (issuing a token, sending
 * mail) happens strictly before that message is chosen and never changes it.
 *
 * <p><b>Tokens.</b> {@code UserTokenManager} stores only a token's SHA-256
 * digest; the raw value handed back by {@code issueToken} exists only in this
 * request and in the URL emailed to the account holder. {@code resetForm} and
 * {@code resetSave} mark their responses {@code Cache-Control: private,
 * no-store} -- a shared cache or browser history entry must never replay a
 * reset-password page for someone else.
 *
 * <p><b>Throttle.</b> This one counts every submission, keyed both by remote
 * address and by the lowercased identifier -- so hammering many identifiers
 * from one address, or one identifier from many addresses, both trip it. A
 * throttled submission still returns the generic confirmation: a distinct
 * "you are being throttled" response would itself leak which identifiers are
 * real.
 *
 * <p><b>No in-request timing tell.</b> An existing identifier used to cost a
 * synchronous SMTP send before the response, an unknown one only two
 * SELECTs -- a difference an attacker can literally time. {@code forgotSend}
 * now hands the whole lookup-issue-mail sequence to
 * {@code ThreadManager.executeInBackground}, so both paths return as soon as
 * the throttle bookkeeping is done and neither does any user-dependent work
 * in-request.
 */
@Controller
@RequestMapping("/roller-ui")
public class PasswordResetController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetController.class);

    /**
     * No existing validation in this codebase sets a password-length floor
     * (see {@code ProfileController}, which only checks the confirmation
     * matches); this is the rule for the reset flow specifically.
     */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /**
     * Built lazily on first use rather than in a constructor, exactly as
     * {@code ContactController.throttle} is: {@code WebloggerConfig} is not
     * necessarily loaded when Spring instantiates controllers.
     */
    private volatile GenericThrottle throttle;

    // ------------------------------------------------------------- security
    //
    // Both flows are for anonymous (usually locked-out) visitors; the
    // interceptor must not send them to the login page.

    @Override
    public boolean isUserRequired() {
        return false;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.emptyList();
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return Collections.emptyList();
    }

    // ---------------------------------------------------------- forgot form

    @GetMapping("/forgotPassword.rol")
    public String forgotForm(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        model.addAttribute("pageTitle", "forgotPassword.title");
        if (!PasswordLinkMailer.isReady()) {
            // A notice, not an error: the form still renders (a submission
            // would just say the same thing back), but there is no point
            // hiding from an administrator that reset links cannot go out.
            addMessage(model, "forgotPassword.mailNotConfigured", request);
        }
        return ".ForgotPassword";
    }

    @PostMapping("/forgotPassword!send.rol")
    public String forgotSend(HttpServletRequest request, Model model,
            @RequestParam(name = "identifier") String identifier) {
        populateCommonModel(request, model);
        model.addAttribute("pageTitle", "forgotPassword.title");

        if (!PasswordLinkMailer.isReady()) {
            addMessage(model, "forgotPassword.mailNotConfigured", request);
            return ".ForgotPassword";
        }
        boolean throttled = throttlingEnabled()
                && (throttle().isAbusive(request.getRemoteAddr())
                    || throttle().isAbusive(idKey(identifier)));
        if (!throttled) {
            // Off-thread: an existing identifier costs a token insert and an
            // SMTP send, an unknown one costs two SELECTs. Doing either
            // in-request makes the response time itself an enumeration
            // oracle, so neither happens before the response below.
            deferIssueAndMail(identifier, request);
            if (throttlingEnabled()) {
                throttle().processHit(request.getRemoteAddr());
                throttle().processHit(idKey(identifier));
            }
        }
        // One message for every outcome: an existing address, an unknown
        // one, and a throttled request all read identically, so the form
        // cannot enumerate accounts or reveal that throttling engaged.
        addMessage(model, "forgotPassword.confirmation", request);
        return ".ForgotPassword";
    }

    // ----------------------------------------------------------- reset form

    @GetMapping("/resetPassword.rol")
    public String resetForm(HttpServletRequest request, HttpServletResponse response, Model model,
            @RequestParam(name = "token") String token) throws WebloggerException {
        populateCommonModel(request, model);
        model.addAttribute("pageTitle", "resetPassword.title");
        response.setHeader("Cache-Control", "private, no-store");

        UserToken userToken = weblogger.getUserTokenManager().validate(token);
        if (userToken == null) {
            model.addAttribute("resetTokenValid", false);
            return ".ResetPassword";
        }
        model.addAttribute("resetTokenValid", true);
        model.addAttribute("resetToken", token);
        return ".ResetPassword";
    }

    @PostMapping("/resetPassword!save.rol")
    public String resetSave(HttpServletRequest request, HttpServletResponse response, Model model,
            RedirectAttributes redirectAttributes,
            @RequestParam(name = "token") String token,
            @RequestParam(name = "passwordText") String passwordText,
            @RequestParam(name = "passwordConfirm") String passwordConfirm) throws WebloggerException {
        populateCommonModel(request, model);
        model.addAttribute("pageTitle", "resetPassword.title");
        response.setHeader("Cache-Control", "private, no-store");

        // Re-validate: the token must still be usable at save time, not just
        // when the form was first shown.
        UserToken userToken = weblogger.getUserTokenManager().validate(token);
        if (userToken == null) {
            model.addAttribute("resetTokenValid", false);
            return ".ResetPassword";
        }

        if (!Strings.CS.equals(passwordText, passwordConfirm)) {
            addError(model, "resetPassword.mismatch", request);
        }
        if (passwordText == null || passwordText.length() < MIN_PASSWORD_LENGTH) {
            addError(model, "resetPassword.tooShort", request);
        }
        if (hasErrors(model)) {
            // The token is still unconsumed -- re-render the form with it
            // re-echoed rather than sending the visitor back to square one.
            model.addAttribute("resetTokenValid", true);
            model.addAttribute("resetToken", token);
            return ".ResetPassword";
        }

        User user = weblogger.getUserTokenManager().consume(token);
        if (user == null) {
            // Raced or expired between validate() above and consume() here.
            model.addAttribute("resetTokenValid", false);
            return ".ResetPassword";
        }

        if (user.isSsoBound() || user.isAgent()) {
            // Defence in depth: no token should exist for a bound account nor
            // for an agent, which has no password at all, but resetPassword
            // would throw for either.
            model.addAttribute("resetTokenValid", false);
            return ".ResetPassword";
        }

        user.resetPassword(passwordText);
        weblogger.getUserManager().saveUser(user);
        weblogger.flush();
        // Any session the account is currently signed into was authenticated
        // with the old password; force it to re-authenticate.
        RollerLoginSessionManager.getInstance().invalidate(user.getUserName());

        addFlashMessage(redirectAttributes, "resetPassword.done", request);
        return "redirect:/roller-ui/login.rol";
    }

    // -------------------------------------------------------------- helpers

    /**
     * Hands the lookup-issue-mail sequence to the background thread pool so
     * the controller's response does not depend on whether the identifier
     * named anyone. Resolves the (locale-dependent) email subject
     * synchronously first -- cheap, no I/O -- so the background runnable
     * never needs to hold onto the request.
     */
    private void deferIssueAndMail(String identifier, HttpServletRequest request) {
        String subject = getText("forgotPassword.email.subject", request);
        try {
            weblogger.getThreadManager().executeInBackground(
                    () -> issueAndMailBestEffort(identifier, subject));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("Could not schedule forgot-password background work", ex);
        }
    }

    /**
     * Issues a token and attempts the notification email for whichever
     * account (if any) the identifier names, swallowing every failure --
     * this runs off the request thread, so nothing in here can change the
     * response the caller already received.
     *
     * <p>This is the runnable's body: it executes on a pooled background
     * thread that never passes through {@code PersistenceSessionFilter}, so
     * nothing else releases this thread's EntityManager/connection when the
     * work is done -- {@code flush()} only commits, it does not close.
     * Every other off-request-thread JPA site in this codebase releases in a
     * {@code finally} for the same reason ({@code AddEntryOperation},
     * {@code ReIndexEntryOperation}, {@code ScheduledEntriesTask}); this
     * mirrors that, and it must stay in this method rather than move up into
     * {@code deferIssueAndMail} -- that method runs on the request thread,
     * before the background work has even started.
     */
    private void issueAndMailBestEffort(String identifier, String subject) {
        try {
            User user = findUser(identifier);
            if (user == null) {
                return;
            }
            String raw = weblogger.getUserTokenManager().issueToken(user, UserToken.Purpose.PASSWORD_RESET);
            weblogger.flush();
            PasswordLinkMailer.sendLink(user, raw, subject);
        } catch (Exception ex) {
            log.error("Could not process forgot-password request", ex);
        } finally {
            weblogger.release();
        }
    }

    /** The enabled user named by a username or an email address, or null. */
    private User findUser(String identifier) throws WebloggerException {
        if (StringUtils.isBlank(identifier)) {
            return null;
        }
        UserManager userManager = weblogger.getUserManager();
        User user = userManager.getUserByUserName(identifier, Boolean.TRUE);
        if (user == null) {
            user = userManager.getUserByEmail(identifier);
        }
        // An SSO-bound account has no password to reset, and an agent has none
        // at all (spec 2026-10-13, section 2): both read as unknown.
        return user == null || user.isSsoBound() || user.isAgent() ? null : user;
    }

    /** The throttle key for an identifier: case-insensitive, distinct from a remote address. */
    private static String idKey(String identifier) {
        return "id:" + (identifier == null ? "" : identifier.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------ throttle

    /**
     * Whether the throttle applies. Startup-scoped, like the contact
     * throttle: sizing and the on/off switch both need a restart.
     */
    private static boolean throttlingEnabled() {
        return WebloggerConfig.getBooleanProperty("passwordreset.throttle.enabled", true);
    }

    /**
     * The throttle itself, built on first use and cached -- its
     * threshold/interval/maxentries size a fixed tracking cache and are read
     * once, so the object is built once and never rebuilt.
     */
    private GenericThrottle throttle() {
        GenericThrottle t = throttle;
        if (t == null) {
            synchronized (this) {
                t = throttle;
                if (t == null) {
                    t = buildThrottle();
                    throttle = t;
                }
            }
        }
        return t;
    }

    private GenericThrottle buildThrottle() {
        int threshold = intProperty("passwordreset.throttle.threshold", 5);
        int interval = intProperty("passwordreset.throttle.interval", 300);
        int maxEntries = intProperty("passwordreset.throttle.maxentries", 250);
        log.info("Password-reset throttle sized at {} requests per {}s", threshold, interval);
        return new GenericThrottle(threshold, interval * RollerConstants.SEC_IN_MS, maxEntries);
    }

    private static int intProperty(String name, int fallback) {
        try {
            return Integer.parseInt(WebloggerConfig.getProperty(name));
        } catch (NumberFormatException e) {
            log.warn("bad input for config property {}; using {}", name, fallback, e);
            return fallback;
        }
    }
}
