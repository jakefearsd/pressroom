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

package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.comments.CommentService;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.UserToken;
import org.apache.roller.weblogger.ui.controllers.AdminLog;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.controllers.core.PasswordLinkMailer;
import org.apache.roller.weblogger.ui.core.RollerLoginSessionManager;
import org.apache.roller.weblogger.ui.controllers.util.UIUtils;
import org.apache.roller.weblogger.util.TokenGenerator;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.apache.roller.weblogger.ui.controllers.WeblogPermissionView;
import org.apache.commons.text.StringEscapeUtils;


/**
 * Controller that allows an admin to create or modify a user profile.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class UserEditController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(UserEditController.class);

    private static final int MIN_PASSWORD_LENGTH = 8;

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
        populateCommonModel(request, model);
        model.addAttribute("actionName", "createUser");
        model.addAttribute("pageTitle", "userAdmin.title.createNewUser");

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
        populateCommonModel(request, model);
        model.addAttribute("actionName", "createUser");
        model.addAttribute("pageTitle", "userAdmin.title.createNewUser");
        model.addAttribute("bean", bean);
        model.addAttribute("mailConfigured", PasswordLinkMailer.isReady());
        addLocalesAndTimezones(model);

        User user = new User();

        // validate for create
        myValidate(bean, user, true, model, request);

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

                if (emailSetPasswordLink) {
                    issueAndMailPasswordSetLink(user, model, request, "userAdmin.userCreatedLinkSent");
                } else {
                    addMessage(model, "createUser.add.success",
                            StringEscapeUtils.escapeHtml4(bean.getUserName()), request);
                }
                model.addAttribute("bean", new CreateUserBean());
                return AccountRows.showList(weblogger, model, null, null);

            } catch (WebloggerException ex) {
                log.error("ERROR in action", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }
        return ".UserEdit";
    }

    @GetMapping("/createUser!cancel.rol")
    public String createUserCancel() {
        return "redirect:/roller-ui/admin/userAdmin.rol";
    }

    // --- Modify User ---

    @GetMapping("/modifyUser.rol")
    public String modifyUserExecute(HttpServletRequest request, Model model,
                                    @ModelAttribute("bean") CreateUserBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "modifyUser");
        model.addAttribute("pageTitle", "userAdmin.title.editUser");

        User user = lookupUser(bean);
        if (user == null) {
            addError(model, "userAdmin.error.userNotFound", request);
            model.addAttribute("bean", bean);
            model.addAttribute("mailConfigured", PasswordLinkMailer.isReady());
            addLocalesAndTimezones(model);
            return AccountRows.showList(weblogger, model, null, null);
        }

        // populate form data from user profile data
        bean.copyFrom(user);
        bean.setAdministrator(isAdministrator(user));
        bean.setWriter(hasEditorRole(user));
        model.addAttribute("bean", bean);
        model.addAttribute("ssoBound", user.isSsoBound());
        model.addAttribute("mailConfigured", PasswordLinkMailer.isReady());
        model.addAttribute("permissions", getPermissions(user));
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
        populateCommonModel(request, model);
        model.addAttribute("actionName", "modifyUser");
        model.addAttribute("pageTitle", "userAdmin.title.editUser");
        model.addAttribute("bean", bean);
        model.addAttribute("mailConfigured", PasswordLinkMailer.isReady());
        addLocalesAndTimezones(model);

        User user = lookupUser(bean);
        if (user == null) {
            addError(model, "userAdmin.error.userNotFound", request);
            return AccountRows.showList(weblogger, model, null, null);
        }
        model.addAttribute("permissions", getPermissions(user));
        model.addAttribute("ssoBound", user.isSsoBound());

        // A bound account signs in through Google and must never hold admin
        // (a compromised Google account would otherwise be an admin account).
        if (user.isSsoBound() && bean.isAdministrator()) {
            addError(model, "userAdmin.error.ssoAdmin", request);
            return ".UserEdit";
        }

        // validate for modify
        myValidate(bean, user, false, model, request);

        if (!hasErrors(model)) {
            String screenNameBefore = user.getScreenName();
            boolean enabledBefore = Boolean.TRUE.equals(user.getEnabled());
            bean.copyTo(user);

            // reset password if set
            if (!StringUtils.isEmpty(bean.getPassword())) {
                user.resetPassword(bean.getPassword());

                // invalidate user's session if it's not user executing this action
                User authUser = getAuthenticatedUser(request);
                if (authUser != null && !authUser.getUserName().equals(user.getUserName())) {
                    RollerLoginSessionManager sessionManager = RollerLoginSessionManager.getInstance();
                    sessionManager.invalidate(user.getUserName());
                }
            }

            // if user is disabled and not the same as the user executing this action, then invalidate session
            User authUser = getAuthenticatedUser(request);
            if (!user.getEnabled() && authUser != null && !authUser.getUserName().equals(user.getUserName())) {
                RollerLoginSessionManager sessionManager = RollerLoginSessionManager.getInstance();
                sessionManager.invalidate(user.getUserName());
            }

            try {
                UserManager mgr = weblogger.getUserManager();
                mgr.saveUser(user);

                if (user.isSsoBound()) {
                    // a bound account never holds admin; only the editor role is editable
                    boolean hasEditor = mgr.hasRole("editor", user);
                    if (bean.isWriter() && !hasEditor) {
                        mgr.grantRole("editor", user);
                    } else if (!bean.isWriter() && hasEditor) {
                        mgr.revokeRole("editor", user);
                    }
                    weblogger.flush();
                    logEnabledChange(enabledBefore, user, authUser);
                    expireIfRenamed(user, screenNameBefore);
                    model.addAttribute("bean", new CreateUserBean());
                    addMessage(model, "userAdmin.userSaved", request);
                    return AccountRows.showList(weblogger, model, null, null);
                }

                // update Admin role as appropriate
                boolean hasAdmin = false;
                GlobalPermission adminPerm =
                        new GlobalPermission(Collections.singletonList(GlobalPermission.ADMIN));
                if (mgr.checkPermission(adminPerm, user)) {
                    hasAdmin = true;
                }
                // grant/revoke admin role if needed
                boolean userEditingSelf = user.equals(authUser);
                String roleEvent = null;
                if (hasAdmin && !bean.isAdministrator()) {
                    if (!userEditingSelf) {
                        // revoke role
                        mgr.revokeRole("admin", user);
                        roleEvent = "siteAdmin.removed";
                    } else {
                        addError(model, "userAdmin.cantChangeOwnRole", request);
                    }
                } else if (!hasAdmin && bean.isAdministrator()) {
                    mgr.grantRole("admin", user);
                    roleEvent = "siteAdmin.granted";
                }
                weblogger.flush();
                logEnabledChange(enabledBefore, user, authUser);
                if (roleEvent != null) {
                    AdminLog.record(roleEvent, authUser, "account=" + user.getUserName());
                }
                expireIfRenamed(user, screenNameBefore);

                // successful edit: send user back to user admin page
                model.addAttribute("bean", new CreateUserBean());
                addMessage(model, "userAdmin.userSaved", request);
                return AccountRows.showList(weblogger, model, null, null);

            } catch (WebloggerException ex) {
                log.error("ERROR in action", ex);
                addError(model, "generic.error.check.logs", request);
            }
        }
        return ".UserEdit";
    }

    @GetMapping("/modifyUser!cancel.rol")
    public String modifyUserCancel() {
        return "redirect:/roller-ui/admin/userAdmin.rol";
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
        populateCommonModel(request, model);
        model.addAttribute("actionName", "modifyUser");
        model.addAttribute("pageTitle", "userAdmin.title.editUser");
        model.addAttribute("mailConfigured", PasswordLinkMailer.isReady());
        addLocalesAndTimezones(model);

        CreateUserBean bean = new CreateUserBean();
        bean.setUserName(userName);
        User user = lookupUser(bean);
        if (user == null) {
            addError(model, "userAdmin.error.userNotFound", request);
            return AccountRows.showList(weblogger, model, null, null);
        }

        bean.copyFrom(user);
        bean.setAdministrator(isAdministrator(user));
        model.addAttribute("bean", bean);
        model.addAttribute("permissions", getPermissions(user));
        model.addAttribute("ssoBound", user.isSsoBound());

        if (user.isSsoBound()) {
            addError(model, "userAdmin.error.ssoPassword", request);
            return ".UserEdit";
        }

        if (!PasswordLinkMailer.isReady()) {
            addError(model, "userAdmin.mailNotConfigured", request);
            return ".UserEdit";
        }

        issueAndMailPasswordSetLink(user, model, request, "userAdmin.passwordLinkSent", user.getEmailAddress());
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

    private void myValidate(CreateUserBean bean, User user, boolean isAdd,
                            Model model, HttpServletRequest request) {
        if (isAdd) {
            String userNameProblem = UserNames.problem(bean.getUserName(), weblogger.getUserManager());
            if (userNameProblem != null) {
                addFieldError(model, "bean_userName", userNameProblem, request);
            }
            // Spec 2026-10-13, section 4: required on the server too, not
            // only by the page's script.
            if (StringUtils.isBlank(bean.getScreenName())) {
                addFieldError(model, "bean_screenName", "personForm.error.screenName", request);
            }
            if (StringUtils.isBlank(bean.getFullName())) {
                addFieldError(model, "bean_fullName", "personForm.error.fullName", request);
            }
            if (!EmailValidator.getInstance().isValid(StringUtils.trimToEmpty(bean.getEmailAddress()))) {
                addFieldError(model, "bean_email", "personForm.error.email", request);
            }
            if (!sendsSetPasswordLink(bean)) {
                if (StringUtils.isEmpty(bean.getPassword())) {
                    addFieldError(model, "bean_password", "error.add.user.missingPassword", request);
                } else if (bean.getPassword().length() < MIN_PASSWORD_LENGTH) {
                    addFieldError(model, "bean_password", "personForm.error.passwordTooShort", request);
                }
            }
        } else {
            if (user.getUserName() == null) {
                addError(model, "userAdmin.error.userNotFound", request);
            }
            if (user.isSsoBound() && !StringUtils.isEmpty(bean.getPassword())) {
                addFieldError(model, "bean_password", "userAdmin.error.ssoPassword", request);
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

    /** Spec 2026-10-13, section 6: an account's Enabled flag changing is a roller.admin event. */
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
