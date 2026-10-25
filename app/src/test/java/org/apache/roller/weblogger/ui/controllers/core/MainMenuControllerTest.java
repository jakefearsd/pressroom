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

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import org.apache.roller.weblogger.ui.controllers.WeblogPermissionView;

/**
 * Tests for {@link MainMenuController}, the page a user lands on after signing
 * in and picks a weblog to work in. There is no invitation/acceptance
 * ceremony reachable from here any more -- sharing a weblog happens on
 * {@code MembersController}'s grant-by-username screen instead.
 */
class MainMenuControllerTest {

    private MockWeblogger weblogger;
    private MainMenuController controller;
    private ExtendedModelMap model;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new MainMenuController());
        model = new ExtendedModelMap();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    @Test
    void theMenuListsTheBlogsYouHave() throws Exception {
        User user = user("jake");
        List<WeblogPermission> existing = List.of(new WeblogPermission());
        when(weblogger.userManager().getWeblogPermissions(user)).thenReturn(existing);
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(user))).thenReturn(true);

        String view = controller.execute(ControllerTestFixture.requestFor(user), model);

        assertEquals(".MainMenu", view);
        assertEquals(existing, permissionsOf(model.getAttribute("existingPermissions")),
                "the menu iterates resolved rows (weblog + user) built from exactly these permissions");
        assertEquals(true, model.getAttribute("userIsAdmin"),
                "the admin section of the menu is shown from this flag");
        assertEquals("yourWebsites.title", model.getAttribute("pageTitle"));
        assertSame(user, model.getAttribute("authenticatedUser"));
    }

    @Test
    void anOrdinaryUserIsNotOfferedTheAdminSection() throws Exception {
        User user = user("jake");
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(user))).thenReturn(false);

        controller.execute(ControllerTestFixture.requestFor(user), model);

        assertEquals(false, model.getAttribute("userIsAdmin"));
    }

    @Test
    void aFailedPermissionLookupShowsAnEmptyMenuRatherThanAnError() throws Exception {
        // The menu is the only page a signed-in user always has; it must render.
        User user = user("jake");
        when(weblogger.userManager().getWeblogPermissions(user)).thenThrow(new WebloggerException("database down"));
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(user)))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(ControllerTestFixture.requestFor(user), model);

        assertEquals(".MainMenu", view);
        assertTrue(((List<?>) model.getAttribute("existingPermissions")).isEmpty());
        assertEquals(false, model.getAttribute("userIsAdmin"));
    }

    @Test
    void aSiteSpaceThatCannotBeLoadedLeavesOutTheSiteSectionNotTheMenu() throws Exception {
        // Display only: who reaches the site space is the interceptor's call, not this one's.
        User user = user("jake");
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(user))).thenReturn(true);
        when(weblogger.weblogManager().getOrCreateSiteSpace(user)).thenThrow(new WebloggerException("database down"));

        String view = controller.execute(ControllerTestFixture.requestFor(user), model);

        assertEquals(".MainMenu", view);
        assertEquals(true, model.getAttribute("userIsAdmin"));
        assertTrue(model.containsAttribute("siteSpace"));
        assertNull(model.getAttribute("siteSpace"));
    }

    @Test
    void theMenuNeedsNoWeblogOfItsOwn() {
        // It is the page you reach before choosing one.
        assertFalse(controller.isWeblogRequired());
        assertEquals("yourWebsites.title", controller.getPageTitle());
    }

    private static User user(String userName) {
        User user = new User();
        user.setUserName(userName);
        user.setEnabled(Boolean.TRUE);
        return user;
    }

    /** The permissions behind the {@code WeblogPermissionView} rows a controller now hands the JSP. */
    private static List<WeblogPermission> permissionsOf(Object rows) {
        return ((List<?>) rows).stream()
                .map(row -> ((WeblogPermissionView) row).getPermission())
                .toList();
    }
}
