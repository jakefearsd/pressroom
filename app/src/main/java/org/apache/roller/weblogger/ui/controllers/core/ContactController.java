/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.FormSubmissionManager;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.FormSubmission;
import org.apache.roller.weblogger.pojos.RollerEvent;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.GenericThrottle;
import org.apache.roller.weblogger.util.MailUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves the public contact endpoint at
 * {@code POST /roller-ui/rendering/contact.rol}: the JSON counterpart of the
 * inline {@code [contact]} shortcode, posted by the JS
 * {@code #showAudienceAssets} injects onto every rendered page.
 *
 * <p><b>Routing.</b> {@code *.rol} is already dispatcher-mapped as a suffix
 * pattern, so the full path is the lookup path. {@code /roller-ui/rendering/*}
 * is where the public comment servlet already lives, and reaches this
 * endpoint {@code permitAll} via {@code SecurityConfig}'s catch-all.
 *
 * <p><b>Layered defences, in order.</b> A per-IP throttle refuses abusive
 * clients outright (429); an unknown weblog handle 404s; a filled honeypot
 * field or a submission faster than a human could type answers exactly like
 * success (204) but stores nothing, so automation learns nothing from being
 * detected; a failed field-level validation 400s; only then is anything
 * persisted.
 *
 * <p><b>Persist-first.</b> The submission is saved and flushed BEFORE any
 * notification email is attempted. If SMTP is down the inquiry survives --
 * for a business running on leads that is the failure that matters -- and
 * both the event record and the notification email are best-effort from
 * there: neither failure changes the response the caller sees.
 */
@Controller
@RequestMapping("/roller-ui/rendering")
public class ContactController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(ContactController.class);

    /** Not in the servlet API's constant list, which stops at 505. */
    private static final int TOO_MANY_REQUESTS = 429;

    /**
     * Throttle for contact submissions, keyed by client address.
     *
     * <p>Built lazily on first use rather than in a constructor:
     * {@code WebloggerConfig} is not necessarily loaded when Spring
     * instantiates controllers.
     */
    private volatile GenericThrottle throttle;

    // ------------------------------------------------------------- security
    //
    // The contact form is for anonymous readers; the interceptor must not
    // send them to the login page. requiredGlobalPermissionActions() is
    // overridden too, belt and braces.

    @Override
    public boolean isUserRequired() {
        return false;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return Collections.emptyList();
    }

    // ------------------------------------------------------------ the form

    @PostMapping(value = "/contact.rol", consumes = "application/json")
    public ResponseEntity<Void> submit(@RequestBody ContactPayload payload,
            HttpServletRequest request) {

        if (throttlingEnabled() && throttle().isAbusive(request.getRemoteAddr())) {
            return ResponseEntity.status(TOO_MANY_REQUESTS).build();
        }

        Weblog weblog = lookupWeblogByHandle(payload.weblog());
        if (weblog == null) {
            return ResponseEntity.notFound().build();
        }

        // Honeypot and timing: answer exactly like success so automation
        // cannot tell it was detected. Nothing is stored.
        if (StringUtils.isNotBlank(payload.website())
                || payload.elapsedMs() < minElapsedMs()) {
            if (throttlingEnabled()) {
                throttle().processHit(request.getRemoteAddr());
            }
            return ResponseEntity.noContent().build();
        }

        String error = validate(payload);
        if (error != null) {
            return ResponseEntity.badRequest().build();
        }

        // Persist FIRST. If SMTP is down the inquiry survives; for a
        // business running on leads that is the failure that matters.
        FormSubmission submission = toSubmission(payload, weblog, request.getRemoteAddr());
        try {
            weblogger.getFormSubmissionManager().save(submission);
            weblogger.flush();
        } catch (WebloggerException ex) {
            log.error("Could not persist contact submission", ex);
            return ResponseEntity.internalServerError().build();
        }

        recordEventBestEffort(weblog, submission);
        notifyBestEffort(weblog, submission);

        if (throttlingEnabled()) {
            throttle().processHit(request.getRemoteAddr());
        }
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------ helpers

    /** The weblog for this handle, or null when unknown -- never throws. */
    private Weblog lookupWeblogByHandle(String handle) {
        if (StringUtils.isBlank(handle)) {
            return null;
        }
        try {
            return weblogger.getWeblogManager().getWeblogByHandle(handle);
        } catch (WebloggerException ex) {
            // An outage reads as an unknown weblog (404) to the visitor: the endpoint is public, so it must not
            // reveal state or throw. The ERROR line is how an operator learns the lookups are failing.
            log.error("Error looking up weblog by handle - {}", handle, ex);
            return null;
        }
    }

    /**
     * Field-level validation, enforced here BEFORE anything reaches
     * {@code FormSubmissionManager} -- whose own {@code MAX_*} checks are the
     * last line of defence, not the first. Returns the name of the first
     * field that failed, or null when the payload is acceptable.
     */
    private static String validate(ContactPayload payload) {
        if (StringUtils.isBlank(payload.name())
                || payload.name().length() > FormSubmissionManager.MAX_NAME) {
            return "name";
        }
        if (StringUtils.isBlank(payload.email())
                || payload.email().length() > FormSubmissionManager.MAX_EMAIL
                || !payload.email().contains("@")) {
            return "email";
        }
        if (StringUtils.isBlank(payload.message())
                || payload.message().length() > FormSubmissionManager.MAX_MESSAGE) {
            return "message";
        }
        if (payload.subject() != null
                && payload.subject().length() > FormSubmissionManager.MAX_SUBJECT) {
            return "subject";
        }
        return null;
    }

    /**
     * Builds the persisted submission. {@code pageSlug}/{@code entryAnchor}
     * are filled from {@code source}'s last path segment on a best-effort
     * basis -- for display in the admin submissions list only, never trusted
     * for anything else, since the client fully controls {@code source}.
     */
    private static FormSubmission toSubmission(ContactPayload payload, Weblog weblog, String clientIp) {
        FormSubmission submission = new FormSubmission();
        submission.setWeblog(weblog);
        submission.setName(payload.name());
        submission.setEmail(payload.email());
        submission.setSubject(payload.subject());
        submission.setMessage(payload.message());
        submission.setClientIp(clientIp);
        applySourceLabel(submission, payload.source());
        return submission;
    }

    /**
     * Labels the submission with the page or entry it came from, parsed from
     * {@code source}'s URL path: the last segment of a {@code /../entry/..}
     * path is an entry anchor, the last segment of anything else is treated
     * as a page slug. Best-effort only -- an unrecognised or blank source
     * leaves both fields null.
     */
    private static void applySourceLabel(FormSubmission submission, String source) {
        if (StringUtils.isBlank(source)) {
            return;
        }
        String path = source;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int lastSlash = path.lastIndexOf('/');
        String lastSegment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        if (StringUtils.isBlank(lastSegment)) {
            return;
        }
        if (path.contains("/entry/")) {
            submission.setEntryAnchor(lastSegment);
        } else {
            submission.setPageSlug(lastSegment);
        }
    }

    /**
     * Records a {@code FORM_SUBMITTED} event. Best-effort per
     * {@code EventManager}'s own contract: an event insert must never fail
     * the request that produced it, so any failure here is logged and
     * swallowed.
     */
    private void recordEventBestEffort(Weblog weblog, FormSubmission submission) {
        try {
            RollerEvent event = new RollerEvent();
            event.setWeblog(weblog);
            event.setEventType(RollerEvent.EventType.FORM_SUBMITTED);
            event.setEntryAnchor(submission.getEntryAnchor());
            event.setPageSlug(submission.getPageSlug());
            weblogger.getEventManager().record(event);
            weblogger.flush();
        } catch (Exception ex) {
            log.error("Could not record contact form-submitted event", ex);
        }
    }

    /**
     * Attempts the owner notification email. Best-effort: SMTP being down
     * must never turn an already-persisted submission into a failed request.
     */
    private void notifyBestEffort(Weblog weblog, FormSubmission s) {
        if (!MailUtil.isMailConfigured()
                || StringUtils.isBlank(weblog.getEmailAddress())) {
            return;
        }
        try {
            String subject = "[" + weblog.getHandle() + "] contact: "
                    + StringUtils.defaultIfBlank(s.getSubject(), "(no subject)");
            String body = "From: " + s.getName() + " <" + s.getEmail() + ">\n\n"
                    + s.getMessage();
            sendNotification(weblog, s, subject, body);
        } catch (Exception ex) {
            log.error("Contact notification email failed; the submission is stored", ex);
        }
    }

    /**
     * The only call into {@link MailUtil} from this controller -- package-
     * private so a test can override it to observe or fail the notification
     * without static-mocking {@code MailUtil} or standing up a mail
     * transport, matching how other controller tests in this package stub
     * their collaborators.
     */
    void sendNotification(Weblog weblog, FormSubmission s, String subject, String body) throws Exception {
        MailUtil.sendTextMessage(weblog.getEmailAddress(), s.getEmail(),
                new String[] { weblog.getEmailAddress() }, subject, body);
    }

    // ------------------------------------------------------------ throttle

    /**
     * Whether the throttle applies. A startup property, not a runtime one:
     * sizing and the on/off switch both need a restart to change.
     */
    private static boolean throttlingEnabled() {
        return WebloggerConfig.getBooleanProperty("contact.throttle.enabled", true);
    }

    /** The minimum elapsed time, in milliseconds, below which a submit is treated as automation. */
    private static long minElapsedMs() {
        return intProperty("contact.form.min.seconds", 3) * (long) RollerConstants.SEC_IN_MS;
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
        int threshold = intProperty("contact.throttle.threshold", 10);
        int interval = intProperty("contact.throttle.interval", 60);
        int maxEntries = intProperty("contact.throttle.maxentries", 250);
        log.info("Contact throttle sized at {} submissions per {}s", threshold, interval);
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

    // -------------------------------------------------------------- payload

    /** The JSON body {@code #showAudienceAssets}'s contact form posts. */
    record ContactPayload(String weblog, String name, String email, String subject,
            String message, String website, long elapsedMs, String source) {
    }
}
