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

package com.wikantik.pressroom.ui.view;

import java.util.Collections;
import java.util.List;

import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.WeblogPermissionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the main menu shows a signed-in user, as a value; the controller puts
 * it on its model as {@code existingPermissions}, {@code userIsAdmin} and
 * {@code siteSpace}. Display only: a failed lookup shows less and is logged;
 * authorization is the interceptor's.
 *
 * @param weblogs the blogs the user holds permissions on, resolved for the page
 * @param siteAdmin whether the admin section is shown
 * @param siteSpace the site space for an admin's Site section (created on a
 *        site admin's first visit); null for anyone else or when it cannot be loaded
 */
public record MainMenuView(List<WeblogPermissionView> weblogs, boolean siteAdmin, Weblog siteSpace) {

    private static final Logger log = LoggerFactory.getLogger(MainMenuView.class);

    public static MainMenuView of(User user, Weblogger weblogger) {
        boolean admin = isSiteAdmin(user, weblogger);
        return new MainMenuView(weblogsOf(user, weblogger), admin, admin ? siteSpace(user, weblogger) : null);
    }

    private static Weblog siteSpace(User user, Weblogger weblogger) {
        try {
            return weblogger.getWeblogManager().getOrCreateSiteSpace(user);
        } catch (Exception e) {
            // Display only: the Site section is left out; the interceptor decides who reaches it.
            log.warn("Could not load the site space for the Main Menu", e);
            return null;
        }
    }

    private static List<WeblogPermissionView> weblogsOf(User user, Weblogger weblogger) {
        try {
            UserManager mgr = weblogger.getUserManager();
            return WeblogPermissionView.resolve(mgr.getWeblogPermissions(user), weblogger);
        } catch (Exception e) {
            // Display only: a failure shows a smaller menu and grants nothing. Logged so an empty
            // menu has a cause.
            log.warn("Could not load the Main Menu permissions", e);
            return Collections.emptyList();
        }
    }

    private static boolean isSiteAdmin(User user, Weblogger weblogger) {
        try {
            GlobalPermission adminPerm = new GlobalPermission(
                    Collections.singletonList(GlobalPermission.ADMIN));
            return weblogger.getUserManager().checkPermission(adminPerm, user);
        } catch (Exception e) {
            // Display only (hides the admin links); the interceptor still enforces.
            log.warn("Could not check the Main Menu admin permission", e);
            return false;
        }
    }
}
