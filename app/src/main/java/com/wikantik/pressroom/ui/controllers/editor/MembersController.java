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

package com.wikantik.pressroom.ui.controllers.editor;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.AdminLog;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.controllers.Memberships;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.wikantik.pressroom.ui.controllers.WeblogPermissionView;
import com.wikantik.pressroom.pojos.Weblog;

/**
 * Allows weblog admin to list/modify member permissions, and to grant a
 * fresh permission to an existing account by username. Granting is
 * immediate -- no invitation, no acceptance step -- via the same
 * {@link UserManager#grantWeblogPermission} the table below uses to change
 * an existing member's role.
 */
@Controller
@RequestMapping("/pressroom-ui/authoring")
public class MembersController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(MembersController.class);

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "members";
    }

    @Override
    public String getPageTitle() {
        return "memberPermissions.title";
    }

    @GetMapping("/members.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        model.addAttribute("weblogPermissions", getWeblogPermissions(request));
        return ".Members";
    }

    /** The value a member's row posts to remove them. */
    private static final String REMOVE = "-1";

    /** A member the page can show (its account resolves) and what the form posted for them. */
    private record Submitted(WeblogPermission permission, User member, String value) {
    }

    @PostMapping("/members!save.rol")
    public String save(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        int removed = 0;
        int changed = 0;

        try {
            UserManager userMgr = weblogger.getUserManager();
            Weblog weblog = getActionWeblog(request);
            User user = getAuthenticatedUser(request);

            List<Submitted> rows = new ArrayList<>();
            for (WeblogPermission perms : userMgr.getWeblogPermissions(weblog)) {
                User permUser = userMgr.getUserByUserName(perms.getUserName());
                if (permUser == null) {
                    // The page shows no control for an account that no longer
                    // resolves (deleted or disabled), so nothing was posted for
                    // it; one such row used to abort every other change.
                    log.warn("Skipping the membership of {} on weblog {}: the account does not resolve",
                            perms.getUserName(), weblog.getHandle());
                    continue;
                }
                rows.add(new Submitted(perms, permUser, request.getParameter("perm-" + permUser.getId())));
            }

            boolean error = false;
            boolean badLevel = false;
            int numAdmins = 0;
            for (Submitted row : rows) {
                String sval = row.value();
                if (sval == null) {
                    continue;
                }
                if (!REMOVE.equals(sval) && !Memberships.LEVELS.contains(sval)) {
                    // One of the three levels or removal, as the person page
                    // allows (UserWeblogsController), never an action list.
                    badLevel = true;
                }
                if (WeblogPermission.ADMIN.equals(sval)) {
                    numAdmins++;
                }
                if (row.permission().getUserName().equals(user.getUserName())
                        && !WeblogPermission.ADMIN.equals(sval)) {
                    error = true;
                    addError(model, "memberPermissions.noSelfModifications", request);
                }
            }
            if (badLevel) {
                addError(model, "userWeblogs.error.level", request);
                error = true;
            }
            if (numAdmins == 0) {
                addError(model, "memberPermissions.oneAdminRequired", request);
                error = true;
            }

            for (Submitted row : rows) {
                String sval = row.value();
                if (sval == null || error || row.permission().hasAction(sval)) {
                    continue;
                }
                User permUser = row.member();
                userMgr.revokeWeblogPermission(weblog, permUser, WeblogPermission.ALL_ACTIONS);
                if (REMOVE.equals(sval)) {
                    AdminLog.record("membership.removed", user, Memberships.details(permUser, weblog));
                    removed++;
                } else {
                    userMgr.grantWeblogPermission(weblog, permUser, List.of(sval));
                    AdminLog.record("membership.changed", user,
                            Memberships.details(permUser, weblog) + " level=" + sval);
                    changed++;
                }
            }

            if (removed > 0 || changed > 0) {
                weblogger.flush();
            }

        } catch (Exception ex) {
            log.error("Error saving permissions on weblog - {}", getActionWeblog(request).getHandle(), ex);
            addError(model, "memberPermissions.saveError", request);
            // Counted as made, but only written by the flush: a failure saved none.
            removed = 0;
            changed = 0;
        }

        if (removed > 0) {
            addMessage(model, "memberPermissions.membersRemoved", Integer.toString(removed), request);
        }
        if (changed > 0) {
            addMessage(model, "memberPermissions.membersChanged", Integer.toString(changed), request);
        }
        if (removed == 0 && changed == 0 && !hasErrors(model)) {
            // A submission that asked for nothing used to answer with a blank
            // page, which is exactly what a save that silently failed looks
            // like. Only for a save that was not refused -- pairing this with
            // an error would read as though the refusal were routine.
            addMessage(model, "memberPermissions.noneChanged", request);
        }

        model.addAttribute("weblogPermissions", getWeblogPermissions(request));
        return ".Members";
    }

    /**
     * Grants an existing account access to this weblog, immediately -- no
     * invitation, no acceptance step. A username that does not resolve to an
     * enabled account is a field error, not an exception. Granting to a user
     * who already has a permission on this weblog updates that row rather
     * than creating a duplicate ({@link UserManager#grantWeblogPermission}
     * already merges into an existing row), and since this only ever adds an
     * action it can never trip the "at least one admin" invariant the way
     * revoking can.
     *
     * <p>Refused outright when {@code groupblogging.enabled} is off. Adding a
     * second person to a weblog <em>is</em> group blogging, so the menu gate
     * in {@code editor-menu.xml} is aimed correctly -- but a hidden menu entry
     * stops nobody who posts here directly, the same gap
     * {@code themes.customtheme.allowed} had before it was enforced in
     * {@link ThemeEditController}. Only granting is gated: {@link #save} keeps
     * working so an operator who turns the setting off can still revoke the
     * members it left behind, rather than being locked out of undoing them.
     */
    @PostMapping("/members!grant.rol")
    public String grant(HttpServletRequest request, Model model,
                         @RequestParam(value = "userName", required = false) String userName,
                         @RequestParam(value = "permissionString", required = false) String permissionString) {
        populateCommonModel(request, model);

        if (!WebloggerRuntimeConfig.getBooleanProperty("groupblogging.enabled")) {
            addError(model, "memberPermissions.error.groupBloggingDisabled", request);
            model.addAttribute("weblogPermissions", getWeblogPermissions(request));
            return ".Members";
        }

        UserManager userMgr = weblogger.getUserManager();
        User user = null;
        if (StringUtils.isBlank(userName)) {
            addFieldError(model, "grantUserName", "inviteMember.error.userNotFound", request);
        } else {
            try {
                user = userMgr.getUserByUserName(userName);
                if (user == null) {
                    addFieldError(model, "grantUserName", "inviteMember.error.userNotFound", request);
                } else if (!userMgr.checkPermission(
                        new GlobalPermission(List.of(GlobalPermission.LOGIN)), user)) {
                    // A reader holds no global login action; a weblog
                    // permission on it would be dead weight and a promotion
                    // path. A check that throws lands in the catch below.
                    addFieldError(model, "grantUserName", "inviteMember.error.readerAccount", request);
                }
            } catch (WebloggerException ex) {
                // userName is a raw request parameter: never logged (a CR/LF could forge a line).
                log.error("Error looking up a user to add to weblog {}", getActionWeblog(request).getHandle(), ex);
                addError(model, "memberPermissions.saveError", request);
            }
        }

        // The form's <select> always submits one of the three levels, so a
        // blank or other permissionString only happens via a hand-crafted
        // POST. Refused here as malformed input, never granted as posted.
        if (StringUtils.isBlank(permissionString)) {
            addError(model, "memberPermissions.saveError", request);
        } else if (!Memberships.LEVELS.contains(permissionString)) {
            // One level, as the select offers and the person page allows, never
            // a comma-separated action list.
            addError(model, "userWeblogs.error.level", request);
        }

        if (!hasErrors(model)) {
            try {
                userMgr.grantWeblogPermission(getActionWeblog(request), user, List.of(permissionString));
                weblogger.flush();
                AdminLog.record("membership.granted", getAuthenticatedUser(request),
                        Memberships.details(user, getActionWeblog(request)) + " level=" + permissionString);
                addMessage(model, "memberPermissions.memberChanged", StringEscapeUtils.escapeHtml4(user.getUserName()), request);
            } catch (Exception ex) {
                log.error("Error granting permission on weblog - {}", getActionWeblog(request).getHandle(), ex);
                addError(model, "memberPermissions.saveError", request);
            }
        }

        model.addAttribute("weblogPermissions", getWeblogPermissions(request));
        return ".Members";
    }

    private List<WeblogPermissionView> getWeblogPermissions(HttpServletRequest request) {
        try {
            return WeblogPermissionView.resolve(weblogger.getUserManager()
                    .getWeblogPermissions(getActionWeblog(request)), weblogger);
        } catch (WebloggerException ex) {
            log.error("ERROR getting weblog permissions", ex);
        }
        return new ArrayList<>();
    }
}
