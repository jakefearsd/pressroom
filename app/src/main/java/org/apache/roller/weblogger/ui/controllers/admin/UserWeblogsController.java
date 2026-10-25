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
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.AdminLog;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The Weblogs section of an account's page, person or agent (spec 2026-10-13,
 * section 4): grant, change and remove memberships by the weblog Members
 * page's own rules -- a weblog keeps at least one Admin, nothing is granted
 * while group blogging is off, and a reader-only account is never granted.
 * Each change writes a {@code roller.admin} line.
 *
 * <p>The weblog travels as {@code handle}, never {@code weblog}:
 * {@code RollerHandlerInterceptor} would read that as the action weblog.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class UserWeblogsController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(UserWeblogsController.class);

    /** The levels, as the Members page offers them: Admin, Author, Limited. */
    static final List<String> LEVELS =
            List.of(WeblogPermission.ADMIN, WeblogPermission.POST, WeblogPermission.EDIT_DRAFT);

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

    @Override
    public String getActionName() {
        return "userAdmin";
    }

    @PostMapping("/userWeblogs!grant.rol")
    public String grant(HttpServletRequest request, RedirectAttributes redirectAttributes,
                        @RequestParam(name = "userName", required = false) String userName,
                        @RequestParam(name = "handle", required = false) String handle,
                        @RequestParam(name = "level", required = false) String level) {
        User account = lookupAccount(userName);
        if (account == null) {
            return accountNotFound(request, redirectAttributes);
        }
        try {
            UserManager users = weblogger.getUserManager();
            Weblog weblog = weblogFor(handle);
            if (!WebloggerRuntimeConfig.getBooleanProperty("groupblogging.enabled")) {
                addFlashError(redirectAttributes, "userWeblogs.groupBloggingOff", request);
            } else if (weblog == null) {
                addFlashError(redirectAttributes, "userWeblogs.error.noSuchWeblog", request);
            } else if (!LEVELS.contains(level)) {
                addFlashError(redirectAttributes, "userWeblogs.error.level", request);
            } else if (!users.checkPermission(new GlobalPermission(List.of(GlobalPermission.LOGIN)), account)) {
                // A reader holds no login action: a weblog permission would be
                // dead weight and a promotion path (MembersController.grant).
                addFlashError(redirectAttributes, "inviteMember.error.readerAccount", request);
            } else {
                users.grantWeblogPermission(weblog, account, List.of(level));
                weblogger.flush();
                AdminLog.record("membership.granted", getAuthenticatedUser(request),
                        details(account, weblog) + " level=" + level);
                addFlashMessage(redirectAttributes, "userWeblogs.granted",
                        StringEscapeUtils.escapeHtml4(weblog.getHandle()), request);
            }
        } catch (WebloggerException e) {
            log.error("Could not add {} to weblog {}", userName, handle, e);
            addFlashError(redirectAttributes, "memberPermissions.saveError", request);
        }
        return backTo(account, redirectAttributes);
    }

    @PostMapping("/userWeblogs!change.rol")
    public String change(HttpServletRequest request, RedirectAttributes redirectAttributes,
                         @RequestParam(name = "userName", required = false) String userName,
                         @RequestParam(name = "handle", required = false) String handle,
                         @RequestParam(name = "level", required = false) String level) {
        User account = lookupAccount(userName);
        if (account == null) {
            return accountNotFound(request, redirectAttributes);
        }
        if (!LEVELS.contains(level)) {
            addFlashError(redirectAttributes, "userWeblogs.error.level", request);
            return backTo(account, redirectAttributes);
        }
        try {
            UserManager users = weblogger.getUserManager();
            Weblog weblog = weblogFor(handle);
            if (weblog == null) {
                addFlashError(redirectAttributes, "userWeblogs.error.noSuchWeblog", request);
                return backTo(account, redirectAttributes);
            }
            WeblogPermission current = users.getWeblogPermission(weblog, account);
            if (current == null) {
                addFlashError(redirectAttributes, "userWeblogs.error.notMember", request);
            } else if (!WeblogPermission.ADMIN.equals(level) && isLastAdmin(users, weblog, current)) {
                addFlashError(redirectAttributes, "memberPermissions.oneAdminRequired", request);
            } else {
                users.revokeWeblogPermission(weblog, account, WeblogPermission.ALL_ACTIONS);
                users.grantWeblogPermission(weblog, account, List.of(level));
                weblogger.flush();
                AdminLog.record("membership.changed", getAuthenticatedUser(request),
                        details(account, weblog) + " level=" + level);
                addFlashMessage(redirectAttributes, "userWeblogs.changed",
                        StringEscapeUtils.escapeHtml4(weblog.getHandle()), request);
            }
        } catch (WebloggerException e) {
            log.error("Could not change {} on weblog {}", userName, handle, e);
            addFlashError(redirectAttributes, "memberPermissions.saveError", request);
        }
        return backTo(account, redirectAttributes);
    }

    @PostMapping("/userWeblogs!remove.rol")
    public String remove(HttpServletRequest request, RedirectAttributes redirectAttributes,
                         @RequestParam(name = "userName", required = false) String userName,
                         @RequestParam(name = "handle", required = false) String handle) {
        User account = lookupAccount(userName);
        if (account == null) {
            return accountNotFound(request, redirectAttributes);
        }
        try {
            UserManager users = weblogger.getUserManager();
            Weblog weblog = weblogFor(handle);
            if (weblog == null) {
                addFlashError(redirectAttributes, "userWeblogs.error.noSuchWeblog", request);
                return backTo(account, redirectAttributes);
            }
            WeblogPermission current = users.getWeblogPermission(weblog, account);
            if (current == null) {
                addFlashError(redirectAttributes, "userWeblogs.error.notMember", request);
            } else if (isLastAdmin(users, weblog, current)) {
                addFlashError(redirectAttributes, "memberPermissions.oneAdminRequired", request);
            } else {
                users.revokeWeblogPermission(weblog, account, WeblogPermission.ALL_ACTIONS);
                weblogger.flush();
                AdminLog.record("membership.removed", getAuthenticatedUser(request), details(account, weblog));
                addFlashMessage(redirectAttributes, "userWeblogs.removed",
                        StringEscapeUtils.escapeHtml4(weblog.getHandle()), request);
            }
        } catch (WebloggerException e) {
            log.error("Could not remove {} from weblog {}", userName, handle, e);
            addFlashError(redirectAttributes, "memberPermissions.saveError", request);
        }
        return backTo(account, redirectAttributes);
    }

    /** Whether {@code current} is the weblog's only Admin membership -- which a weblog must keep. */
    private static boolean isLastAdmin(UserManager users, Weblog weblog, WeblogPermission current)
            throws WebloggerException {
        if (!current.hasAction(WeblogPermission.ADMIN)) {
            return false;
        }
        int admins = 0;
        for (WeblogPermission permission : users.getWeblogPermissions(weblog)) {
            if (permission.hasAction(WeblogPermission.ADMIN)) {
                admins++;
            }
        }
        return admins <= 1;
    }

    private static String details(User account, Weblog weblog) {
        return "account=" + account.getUserName() + " weblog=" + weblog.getHandle();
    }

    /**
     * The weblog a membership form names, or null for none. The site space
     * (the hidden weblog owning the site's pages) answers null too: it holds
     * no permission rows and must never gain one, which would list it among
     * that person's blogs. The handle arrives in the form, outside the
     * interceptor's withholding, so it is refused here.
     */
    private Weblog weblogFor(String handle) throws WebloggerException {
        Weblog weblog = StringUtils.isBlank(handle)
                ? null : weblogger.getWeblogManager().getWeblogByHandle(handle, null);
        return weblog == null || weblog.isSiteSpace() ? null : weblog;
    }

    /** A failed lookup reads as no account, and nothing proceeds. */
    private User lookupAccount(String userName) {
        if (StringUtils.isBlank(userName)) {
            return null;
        }
        try {
            return weblogger.getUserManager().getUserByUserName(userName, null);
        } catch (WebloggerException e) {
            log.error("Could not look up the account {}", userName, e);
            return null;
        }
    }

    private String accountNotFound(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        addFlashError(redirectAttributes, "userAdmin.error.userNotFound", request);
        return "redirect:/roller-ui/admin/userAdmin.rol";
    }

    /** Back to the account's own page: an agent's page, or the person editor. */
    private static String backTo(User account, RedirectAttributes redirectAttributes) {
        if (account.isAgent()) {
            redirectAttributes.addAttribute("userName", account.getUserName());
            return "redirect:/roller-ui/admin/agent.rol";
        }
        redirectAttributes.addAttribute("bean.userName", account.getUserName());
        return "redirect:/roller-ui/admin/modifyUser.rol";
    }
}
