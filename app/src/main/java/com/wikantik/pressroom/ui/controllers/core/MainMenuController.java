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

package com.wikantik.pressroom.ui.controllers.core;

import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import com.wikantik.pressroom.ui.controllers.WeblogPermissionView;


/**
 * Allows user to view and pick from list of his/her websites.
 */
@Controller
@RequestMapping("/roller-ui")
public class MainMenuController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(MainMenuController.class);

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public String getPageTitle() {
        return "yourWebsites.title";
    }

    @GetMapping("/menu.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        populateMenuData(request, model);
        return ".MainMenu";
    }

    private void populateMenuData(HttpServletRequest request, Model model) {
        User user = getAuthenticatedUser(request);
        boolean admin = isUserIsAdmin(user);
        model.addAttribute("existingPermissions", getExistingPermissions(user));
        model.addAttribute("userIsAdmin", admin);
        model.addAttribute("siteSpace", admin ? siteSpace(user) : null);
    }

    /**
     * The site space, for a global admin's Site section (Pages, Media, Theme
     * acting on the site's own pages). Created on a global admin's first
     * visit, so the section is there from the start; nobody else ever sees
     * it here, and it is never one of the blogs above, which come from
     * permission rows the site space does not have.
     */
    private Weblog siteSpace(User user) {
        try {
            return weblogger.getWeblogManager().getOrCreateSiteSpace(user);
        } catch (Exception e) {
            // Display only: the Site section is left out; the interceptor decides who reaches it.
            log.warn("Could not load the site space for the Main Menu", e);
            return null;
        }
    }

    private List<WeblogPermissionView> getExistingPermissions(User user) {
        try {
            UserManager mgr = weblogger.getUserManager();
            return WeblogPermissionView.resolve(mgr.getWeblogPermissions(user), weblogger);
        } catch (Exception e) {
            // Display only: a failure shows a smaller menu and grants nothing; authorization is
            // the interceptor's. Logged so an empty menu has a cause.
            log.warn("Could not load the Main Menu permissions", e);
            return Collections.emptyList();
        }
    }

    private boolean isUserIsAdmin(User user) {
        try {
            GlobalPermission adminPerm = new GlobalPermission(
                    Collections.singletonList(GlobalPermission.ADMIN));
            UserManager umgr = weblogger.getUserManager();
            return umgr.checkPermission(adminPerm, user);
        } catch (Exception e) {
            // Display only (hides the admin links); the interceptor still enforces.
            log.warn("Could not check the Main Menu admin permission", e);
            return false;
        }
    }
}
