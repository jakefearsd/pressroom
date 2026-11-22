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

package com.wikantik.pressroom.ui.controllers.admin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.comments.CommentService;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.UserToken;
import com.wikantik.pressroom.ui.controllers.AccountRules;
import com.wikantik.pressroom.ui.controllers.AdminLog;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.controllers.RuleViolation;
import com.wikantik.pressroom.ui.controllers.core.PasswordLinkMailer;
import com.wikantik.pressroom.ui.controllers.util.UIUtils;
import com.wikantik.pressroom.ui.core.PressroomLoginSessionManager;
import com.wikantik.pressroom.ui.core.security.CredentialStampFilter;
import com.wikantik.pressroom.ui.view.WeblogPermissionView;
import com.wikantik.pressroom.util.TokenGenerator;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;


/**
 * Controller that allows an admin to create or modify a user profile.
 */
@Controller
@RequestMapping("/pressroom-ui/admin")
public class UserEditController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(UserEditController.class);

    private final CommentService comments;

    /** @param comments expires the weblogs showing an account's comments when it is renamed */
    public UserEditController(CommentService comments) {
        this.comments = comments;
    }


    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @ModelAttribute("bean")
    public CreateUserBean getBean() {
        return new CreateUserBean();
    }

    // --- Create User ---

    @GetMapping("/createUser.rol")
    public String createUserExecute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model, new Screen("createUser", "userAdmin.title.createNewUser"));

        CreateUserBean bean = new CreateUserBean();
        bean.setLocale(java.util.Locale.getDefault().toString());
        bean.setTimeZone(java.util.TimeZone.getDefault().getID());
        bean.setEnabled(Boolean.TRUE);

        model.addAttribute("bean", bean);
        model.addAttribute("mailConfigured", PasswordLinkMailer.isReady());
        addLocalesAndTimezones(model);

        return ".UserEdit";
    }

    @PostMapping("/createUser!save.rol")
    public String createUserSave(HttpServletRequest request, Model model,
                                 @ModelAttribute("bean") CreateUserBean bean) {
        populateCommonModel(request, model, new Screen("createUser", "userAdmin.title.createNewUser"));
        model.addAttribute("bean", bean);
        model.addAttribute("mailConfigured", PasswordLinkMailer.isReady());
        addLocalesAndTimezones(model);

        User user = new User();

        // validate for create
        validateNew(bean, model, request);

        if (!hasErrors(model)) {
            bean.copyTo(user);

            // "Email a set-password link" (spec 2026-10-13, section 4): the
            // account still needs SOME password, so it gets a random one
            // nobody is ever told.
            boolean emailSetPasswordLink = sendsSetPasswordLink(bean);
            user.resetPassword(emailSetPasswordLink ? TokenGenerator.newToken() : bean.getPassword());

            try {
                UserManager mgr = weblogger.getUserManager();
                // fields not copied over from above copyTo():
                user.setUserName(bean.getUserName());
                user.setDateCreated(new java.util.Date());
                // save new user
                mgr.addUser(user);

                // grant admin role if needed
                if (bean.isAdministrator()) {
                    mgr.grantRole("admin", user);
                }

                weblogger.flush();
                if (bean.isAdministrator()) {
                    AdminLog.record("siteAdmin.granted", getAuthenticatedUser(request),
                            "account=" + user.getUserName());
                }

                if (emailSetPasswordLink) {
                    issueAndMailPasswordSetLink(user, model, request, "userAdmin.userCreatedLinkSent");
                } else {
                    addMessage(model, "createUser.add.success",
                            StringEscapeUtils.escapeHtml4(bean.getUserName()), request);
                }
                model.addAttribute("bean", new CreateUserBean());
                return showAccounts(model, null, null);

            } catch (WebloggerException ex) {
                log.error("ERROR in action", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }
        return ".UserEdit";
    }

    @GetMapping("/createUser!cancel.rol")
    public String createUserCancel() {
        return "redirect:/pressroom-ui/admin/userAdmin.rol";
    }

    // --- Modify User ---

    @GetMapping("/modifyUser.rol")
    public String modifyUserExecute(HttpServletRequest request, Model model,
                                    @ModelAttribute("bean") CreateUserBean bean) {
        populateCommonModel(request, model, new Screen("modifyUser", "userAdmin.title.editUser"));

        User user = lookupUser(bean);
        if (user == null) {
            addError(model, "userAdmin.error.userNotFound", request);
            model.addAttribute("bean", bean);
            model.addAttribute("mailConfigured", PasswordLinkMailer.isReady());
            addLocalesAndTimezones(model);
            return showAccounts(model, null, null);
        }

        if (user.isAgent()) {
            // An agent has its own page, with no password and no set-password
            // link (spec 2026-10-13, criterion 6). The name is restricted to
            // username.allowedChars, so it needs no encoding in the query.
            return "redirect:/pressroom-ui/admin/agent.rol?userName=" + user.getUserName();
        }

        // populate form data from user profile data
        bean.copyFrom(user);
        bean.setAdministrator(isAdministrator(user));
        bean.setWriter(hasEditorRole(user));
        model.addAttribute("bean", bean);
        model.addAttribute("ssoBound", user.isSsoBound());
        model.addAttribute("mailConfigured", PasswordLinkMailer.isReady());
        model.addAttribute("permissions", getPermissions(user));
        UserWeblogsModel.populate(weblogger, model, user);
        addLocalesAndTimezones(model);

        return ".UserEdit";
    }

    @GetMapping("/modifyUser!firstSave.rol")
    public String modifyUserFirstSave(HttpServletRequest request, Model model,
                                      @ModelAttribute("bean") CreateUserBean bean) {
        addMessage(model, "createUser.add.success",
                            StringEscapeUtils.escapeHtml4(bean.getUserName()), request);
        return modifyUserExecute(request, model, bean);
    }

    @PostMapping("/modifyUser!save.rol")
    public String modifyUserSave(HttpServletRequest request, Model model,
                                 @ModelAttribute("bean") CreateUserBean bean) {
        populateCommonModel(request, model, new Screen("modifyUser", "userAdmin.title.editUser"));
        model.addAttribute("bean", bean);
        model.addAttribute("mailConfigured", PasswordLinkMailer.isReady());
        addLocalesAndTimezones(model);

        User user = lookupUser(bean);
        if (user == null) {
            addError(model, "userAdmin.error.userNotFound", request);
            return showAccounts(model, null, null);
        }
        if (user.isAgent()) {
            addError(model, "agent.error.notAPerson", request);
            return showAccounts(model, null, null);
        }
        model.addAttribute("permissions", getPermissions(user));
        UserWeblogsModel.populate(weblogger, model, user);
        model.addAttribute("ssoBound", user.isSsoBound());

        // A bound account signs in through Google and must never hold admin
        // (a compromised Google account would otherwise be an admin account).
        if (user.isSsoBound() && bean.isAdministrator()) {
            addError(model, "userAdmin.error.ssoAdmin", request);
            return ".UserEdit";
        }

        // validate for modify
        validateEdit(bean, user, model, request);
        refuseSelfLockout(bean, user, getAuthenticatedUser(request), model, request);
        if (hasErrors(model)) {
            return ".UserEdit";
        }

        String screenNameBefore = user.getScreenName();
        boolean enabledBefore = Boolean.TRUE.equals(user.getEnabled());
        bean.copyTo(user);

        // reset password if set
        boolean passwordChanged = !StringUtils.isEmpty(bean.getPassword());
        if (passwordChanged) {
            user.resetPassword(bean.getPassword());
        }

        // A new password or a disabled account ends the user's session,
        // unless it is the user executing this action.
        boolean disabled = !user.getEnabled();
        User authUser = getAuthenticatedUser(request);
        boolean actingOnSelf = authUser != null && authUser.getUserName().equals(user.getUserName());
        if (authUser != null && !actingOnSelf && (passwordChanged || disabled)) {
            PressroomLoginSessionManager.getInstance().invalidate(user.getUserName());
        }

        try {
            UserManager mgr = weblogger.getUserManager();
            mgr.saveUser(user);

            String roleEvent = null;
            if (user.isSsoBound()) {
                // a bound account never holds admin; only the editor role is editable
                syncRole(mgr, user, "editor", bean.isWriter(), mgr.hasRole("editor", user));
            } else {
                // grant/revoke admin role if needed (refuseSelfLockout has
                // already refused an admin unticking their own role)
                boolean hasAdmin = mgr.checkPermission(
                        new GlobalPermission(Collections.singletonList(GlobalPermission.ADMIN)), user);
                roleEvent = syncRole(mgr, user, "admin", bean.isAdministrator(), hasAdmin);
            }
            weblogger.flush();
            if (passwordChanged && actingOnSelf) {
                // The account's other sessions end on their next request
                // (CredentialStampFilter); the admin's own stays signed in.
                CredentialStampFilter.restamp(request, user);
            }
            logEnabledChange(enabledBefore, user, authUser);
            if (roleEvent != null) {
                AdminLog.record(roleEvent, authUser, "account=" + user.getUserName());
            }
            expireIfRenamed(user, screenNameBefore);

            // successful edit: send user back to user admin page
            model.addAttribute("bean", new CreateUserBean());
            addMessage(model, "userAdmin.userSaved", request);
            return showAccounts(model, null, null);

        } catch (WebloggerException ex) {
            log.error("ERROR in action", ex);
            addError(model, "generic.error.check.logs", request);
        }
        return ".UserEdit";
    }

    /**
     * Grants or revokes one role so that holding it matches the form.
     *
     * @return the admin-log event for a change to the admin role, else null
     */
    private static String syncRole(UserManager mgr, User user, String role, boolean wanted, boolean held)
            throws WebloggerException {
        if (wanted == held) {
            return null;
        }
        if (wanted) {
            mgr.grantRole(role, user);
        } else {
            mgr.revokeRole(role, user);
        }
        if (!"admin".equals(role)) {
            return null;
        }
        return wanted ? "siteAdmin.granted" : "siteAdmin.removed";
    }

    @GetMapping("/modifyUser!cancel.rol")
    public String modifyUserCancel() {
        return "redirect:/pressroom-ui/admin/userAdmin.rol";
    }

    // --- Send set-password link ---

    /**
     * Lets an admin email an existing user a set-password link instead of
     * inventing (and having to relay) a password themselves. Synchronous,
     * unlike the public forgot-password flow's off-thread send
     * ({@code PasswordResetController.deferIssueAndMail}): this action is
     * authenticated admin-only traffic, one request at a time, so there is no
     * enumeration risk to hide behind a background thread and no volume to
     * worry about -- and a synchronous send lets the admin see immediately
     * whether it actually went out.
     */
    @PostMapping("/userEdit!sendPasswordLink.rol")
    public String sendPasswordLink(HttpServletRequest request, Model model,
                                   @RequestParam(name = "bean.userName") String userName) {
        populateCommonModel(request, model, new Screen("modifyUser", "userAdmin.title.editUser"));
        model.addAttribute("mailConfigured", PasswordLinkMailer.isReady());
        addLocalesAndTimezones(model);

        CreateUserBean bean = new CreateUserBean();
        bean.setUserName(userName);
        User user = lookupUser(bean);
        if (user == null) {
            addError(model, "userAdmin.error.userNotFound", request);
            return showAccounts(model, null, null);
        }
        if (user.isAgent()) {
            addError(model, "agent.error.notAPerson", request);
            return showAccounts(model, null, null);
        }

        bean.copyFrom(user);
        bean.setAdministrator(isAdministrator(user));
        model.addAttribute("bean", bean);
        model.addAttribute("permissions", getPermissions(user));
        UserWeblogsModel.populate(weblogger, model, user);
        model.addAttribute("ssoBound", user.isSsoBound());

        if (user.isSsoBound()) {
            addError(model, "userAdmin.error.ssoPassword", request);
            return ".UserEdit";
        }

        if (!PasswordLinkMailer.isReady()) {
            addError(model, "userAdmin.mailNotConfigured", request);
            return ".UserEdit";
        }

        // The stored address is echoed into a banner messages.jsp renders bare.
        issueAndMailPasswordSetLink(user, model, request, "userAdmin.passwordLinkSent",
                StringEscapeUtils.escapeHtml4(user.getEmailAddress()));
        return ".UserEdit";
    }

    /**
     * Issues a {@code PASSWORD_SET} token for {@code user} and mails it via
     * {@link PasswordLinkMailer}, reporting {@code successKey} on success (with
     * whatever {@code successArg}, if any, it takes) or
     * {@code generic.error.check.logs} if either step throws. Shared by the
     * create-with-a-blank-password path and {@link #sendPasswordLink}.
     */
    private void issueAndMailPasswordSetLink(User user, Model model, HttpServletRequest request,
                                             String successKey, String... successArg) {
        try {
            String raw = weblogger.getUserTokenManager().issueToken(user, UserToken.Purpose.PASSWORD_SET);
            weblogger.flush();
            String subject = getText("userAdmin.setPassword.email.subject", request);
            PasswordLinkMailer.sendLink(user, raw, subject);
            if (successArg.length > 0) {
                addMessage(model, successKey, successArg[0], request);
            } else {
                addMessage(model, successKey, request);
            }
        } catch (Exception ex) {
            log.error("Error sending set-password link for {}", user.getUserName(), ex);
            addError(model, "generic.error.check.logs", request);
        }
    }

    // --- Helpers ---

    private User lookupUser(CreateUserBean bean) {
        try {
            UserManager mgr = weblogger.getUserManager();
            if (!StringUtils.isEmpty(bean.getId())) {
                return mgr.getUser(bean.getId());
            } else if (!StringUtils.isEmpty(bean.getUserName())) {
                return mgr.getUserByUserName(bean.getUserName(), null);
            }
        } catch (Exception e) {
            // A failed lookup reads as not found to the admin and the edit does not proceed; the cause is
            // in this log line.
            log.error("Error looking up user (id/username):{}/{}", bean.getId(), bean.getUserName(), e);
        }
        return null;
    }

    private void validateNew(CreateUserBean bean, Model model, HttpServletRequest request) {
        try {
            RuleViolation userName = AccountRules.newUserNameProblem(bean.getUserName(), weblogger.getUserManager());
            if (userName != null) {
                addFieldError(model, "bean_userName", userName, request);
            }
        } catch (WebloggerException e) {
            // A taken-check that cannot run refuses the create: it did not pass.
            log.error("Could not check whether the username {} is taken", bean.getUserName(), e);
            addError(model, "generic.error.check.logs", request);
        }
        // Spec 2026-10-13, section 4: required on the server too, not
        // only by the page's script.
        RuleViolation screenName = AccountRules.screenNameProblem(bean.getScreenName());
        if (screenName != null) {
            addFieldError(model, "bean_screenName", screenName, request);
        }
        if (StringUtils.isBlank(bean.getFullName())) {
            addFieldError(model, "bean_fullName", "personForm.error.fullName", request);
        }
        RuleViolation email = AccountRules.emailProblem(bean.getEmailAddress(), AccountRules.EmailUse.REQUIRED);
        if (email != null) {
            addFieldError(model, "bean_email", email, request);
        }
        if (sendsSetPasswordLink(bean)) {
            return;
        }
        String password = AccountRules.passwordProblem(bean.getPassword(), true);
        if (password != null) {
            addFieldError(model, "bean_password", password, request);
        }
    }

    private void validateEdit(CreateUserBean bean, User user, Model model, HttpServletRequest request) {
        if (user.getUserName() == null) {
            addError(model, "userAdmin.error.userNotFound", request);
        }
        if (user.isSsoBound() && !StringUtils.isEmpty(bean.getPassword())) {
            addFieldError(model, "bean_password", "userAdmin.error.ssoPassword", request);
            return;
        }
        String password = AccountRules.passwordProblem(bean.getPassword(), false);
        if (password != null) {
            addFieldError(model, "bean_password", password, request);
        }
    }

    /**
     * An admin editing their own account may neither untick their admin role
     * nor disable the account: either locks them out, and since only an
     * enabled admin reaches this page, refusing it for oneself is also what
     * keeps the site from losing its last enabled admin. Checked before
     * anything on the form is applied, so a refused save changes nothing --
     * the role check used to run after {@code saveUser}, so the form's other
     * fields committed and "saved" showed beside the error. A role lookup
     * that fails refuses too.
     */
    private void refuseSelfLockout(CreateUserBean bean, User user, User actor,
                                   Model model, HttpServletRequest request) {
        if (actor == null || user.getUserName() == null || !user.getUserName().equals(actor.getUserName())) {
            return;
        }
        if (!Boolean.TRUE.equals(bean.getEnabled())) {
            addError(model, "userAdmin.error.cantDisableSelf", request);
        }
        if (!bean.isAdministrator()) {
            try {
                if (weblogger.getUserManager().checkPermission(
                        new GlobalPermission(Collections.singletonList(GlobalPermission.ADMIN)), user)) {
                    addError(model, "userAdmin.cantChangeOwnRole", request);
                }
            } catch (WebloggerException ex) {
                log.error("Could not check the admin role of {}", user.getUserName(), ex);
                addError(model, "generic.error.check.logs", request);
            }
        }
    }

    /**
     * Whether a new person gets an emailed set-password link instead of a
     * password now: chosen on the form, and possible only while mail can
     * deliver one -- otherwise the account could never be handed over.
     */
    private static boolean sendsSetPasswordLink(CreateUserBean bean) {
        return CreateUserBean.PASSWORD_LINK.equals(bean.getPasswordMode()) && PasswordLinkMailer.isReady();
    }

    /** Spec 2026-10-13, section 6: an account's Enabled flag changing is a pressroom.admin event. */
    private static void logEnabledChange(boolean enabledBefore, User user, User actor) {
        boolean enabledNow = Boolean.TRUE.equals(user.getEnabled());
        if (enabledBefore != enabledNow) {
            AdminLog.record(enabledNow ? "account.enabled" : "account.disabled", actor,
                    "account=" + user.getUserName());
        }
    }

    /**
     * After a committed save: when the screen name changed, expires every weblog
     * showing the account's published comments, which its screen name signs.
     */
    private void expireIfRenamed(User user, String screenNameBefore) {
        if (!Objects.equals(screenNameBefore, user.getScreenName())) {
            comments.expireWhereShown(user);
        }
    }

    private List<WeblogPermissionView> getPermissions(User user) {
        try {
            return WeblogPermissionView.resolve(
                    weblogger.getUserManager().getWeblogPermissions(user), weblogger);
        } catch (WebloggerException ex) {
            log.error("ERROR getting permissions for user {}", user.getUserName(), ex);
        }
        return new ArrayList<>();
    }

    /**
     * Whether the user holds the {@code editor} role, for a provider-bound
     * account's "Can write" checkbox (the only role User Admin can grant one).
     * A lookup that fails reads as "cannot write" (a display default, logged),
     * the same default {@link #isAdministrator} takes for the admin box.
     */
    private boolean hasEditorRole(User user) {
        try {
            return weblogger.getUserManager().hasRole("editor", user);
        } catch (WebloggerException ex) {
            log.warn("Could not determine editor role for user {}", user.getUserName(), ex);
            return false;
        }
    }

    /**
     * Whether the user holds the global admin role, for the edit form's
     * checkbox. This used to live in {@code CreateUserBean.copyFrom}; it moved
     * here because the bean is pure and the controller holds the user manager.
     * A lookup that fails reads as "not an admin" (a display default, logged)
     * rather than offering to make somebody an admin on the strength of a
     * database error.
     */
    private boolean isAdministrator(User user) {
        try {
            GlobalPermission adminPerm =
                    new GlobalPermission(Collections.singletonList(GlobalPermission.ADMIN));
            return weblogger.getUserManager().checkPermission(adminPerm, user);
        } catch (WebloggerException ex) {
            log.warn("Could not determine administrator status for user {}", user.getUserName(), ex);
            return false;
        }
    }

    private void addLocalesAndTimezones(Model model) {
        model.addAttribute("localesList", UIUtils.getLocales());
        model.addAttribute("timeZonesList", UIUtils.getTimeZones());
    }
}
