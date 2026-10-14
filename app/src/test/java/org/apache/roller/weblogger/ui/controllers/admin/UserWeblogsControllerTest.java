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

import org.apache.roller.testing.AdminLogCapture;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Weblogs section on an account's page (spec 2026-10-13, section 4; criterion 18; review focus 4). */
class UserWeblogsControllerTest {

    private MockWeblogger weblogger;
    private UserWeblogsController controller;
    private RedirectAttributesModelMap redirect;
    private User person;
    private User agent;
    private Weblog travel;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new UserWeblogsController());
        redirect = new RedirectAttributesModelMap();
        person = account("jake", User.Kind.PERSON);
        agent = account("bot", User.Kind.AGENT);
        travel = new Weblog();
        travel.setHandle("travel");
        when(weblogger.userManager().getUserByUserName("jake", null)).thenReturn(person);
        when(weblogger.userManager().getUserByUserName("bot", null)).thenReturn(agent);
        when(weblogger.weblogManager().getWeblogByHandle("travel", null)).thenReturn(travel);
        // Both accounts hold the global login action unless a test says otherwise.
        when(weblogger.userManager().checkPermission(any(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    private static User account(String userName, User.Kind kind) {
        User user = new User();
        user.setUserName(userName);
        user.setKind(kind);
        return user;
    }

    private static HttpServletRequest asAdmin() {
        return ControllerTestFixture.requestFor(account("admin", User.Kind.PERSON));
    }

    private WeblogPermission member(User user, String level) {
        return new WeblogPermission(travel, user, List.of(level));
    }

    @Test
    void theSectionIsAdminOnlyAndNeedsNoWeblog() {
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
    }

    /** Criterion 18: granting adds the membership, logs it, and returns to the person's page. */
    @Test
    void grantingAddsTheMembershipLogsItAndReturnsToThePersonsPage() throws Exception {
        try (AdminLogCapture log = AdminLogCapture.start()) {
            String view = controller.grant(asAdmin(), redirect, "jake", "travel", "post");

            assertEquals("redirect:/roller-ui/admin/modifyUser.rol", view);
            assertEquals("jake", redirect.asMap().get("bean.userName"));
            verify(weblogger.userManager()).grantWeblogPermission(travel, person, List.of("post"));
            assertEquals(1, weblogger.flushCount());
            assertEquals(List.of("userWeblogs.granted[travel]"), ControllerTestFixture.flashMessages(redirect));
            assertEquals(List.of("membership.granted account=jake weblog=travel level=post by=admin"), log.lines());
        }
    }

    @Test
    void anAgentReturnsToItsOwnPage() throws Exception {
        String view = controller.grant(asAdmin(), redirect, "bot", "travel", "post");

        assertEquals("redirect:/roller-ui/admin/agent.rol", view);
        assertEquals("bot", redirect.asMap().get("userName"));
    }

    /** Criterion 18: with group blogging off, granting is refused, and the message says why. */
    @Test
    void grantingWhileGroupBloggingIsOffIsRefused() throws Exception {
        when(weblogger.propertiesManager().getProperty("groupblogging.enabled"))
                .thenReturn(new RuntimeConfigProperty("groupblogging.enabled", "false"));

        controller.grant(asAdmin(), redirect, "jake", "travel", "post");

        assertEquals(List.of("userWeblogs.groupBloggingOff"), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.userManager(), never()).grantWeblogPermission(any(), any(), any());
    }

    /** Criterion 18: a reader-only account cannot be granted. */
    @Test
    void aReaderOnlyAccountCannotBeGranted() throws Exception {
        when(weblogger.userManager().checkPermission(any(), eq(person))).thenReturn(false);

        controller.grant(asAdmin(), redirect, "jake", "travel", "post");

        assertEquals(List.of("inviteMember.error.readerAccount"), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.userManager(), never()).grantWeblogPermission(any(), any(), any());
    }

    @Test
    void anUnknownWeblogOrLevelIsRefused() throws Exception {
        controller.grant(asAdmin(), redirect, "jake", "nosuch", "post");
        assertEquals(List.of("userWeblogs.error.noSuchWeblog"), ControllerTestFixture.flashErrors(redirect));

        RedirectAttributesModelMap badLevel = new RedirectAttributesModelMap();
        controller.grant(asAdmin(), badLevel, "jake", "travel", "owner");
        assertEquals(List.of("userWeblogs.error.level"), ControllerTestFixture.flashErrors(badLevel));
        verify(weblogger.userManager(), never()).grantWeblogPermission(any(), any(), any());
    }

    @Test
    void anUnknownAccountGoesBackToTheList() throws Exception {
        String view = controller.grant(asAdmin(), redirect, "nobody", "travel", "post");

        assertEquals("redirect:/roller-ui/admin/userAdmin.rol", view);
        assertEquals(List.of("userAdmin.error.userNotFound"), ControllerTestFixture.flashErrors(redirect));
    }

    /** Criterion 18: changing the level replaces the actions, and is logged. */
    @Test
    void changingALevelReplacesTheActions() throws Exception {
        when(weblogger.userManager().getWeblogPermission(travel, person)).thenReturn(member(person, "post"));

        try (AdminLogCapture log = AdminLogCapture.start()) {
            controller.change(asAdmin(), redirect, "jake", "travel", "admin");

            InOrder order = inOrder(weblogger.userManager());
            order.verify(weblogger.userManager()).revokeWeblogPermission(travel, person, WeblogPermission.ALL_ACTIONS);
            order.verify(weblogger.userManager()).grantWeblogPermission(travel, person, List.of("admin"));
            assertEquals(List.of("userWeblogs.changed[travel]"), ControllerTestFixture.flashMessages(redirect));
            assertEquals(List.of("membership.changed account=jake weblog=travel level=admin by=admin"), log.lines());
        }
    }

    /** Review focus 4: a weblog keeps at least one Admin -- the last one cannot be demoted. */
    @Test
    void demotingTheLastAdminIsRefused() throws Exception {
        WeblogPermission current = member(person, "admin");
        when(weblogger.userManager().getWeblogPermission(travel, person)).thenReturn(current);
        when(weblogger.userManager().getWeblogPermissions(travel)).thenReturn(List.of(current, member(agent, "post")));

        controller.change(asAdmin(), redirect, "jake", "travel", "post");

        assertEquals(List.of("memberPermissions.oneAdminRequired"), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.userManager(), never()).revokeWeblogPermission(any(), any(), any());
    }

    /** Review focus 4: nor removed. */
    @Test
    void removingTheLastAdminIsRefused() throws Exception {
        WeblogPermission current = member(person, "admin");
        when(weblogger.userManager().getWeblogPermission(travel, person)).thenReturn(current);
        when(weblogger.userManager().getWeblogPermissions(travel)).thenReturn(List.of(current));

        controller.remove(asAdmin(), redirect, "jake", "travel");

        assertEquals(List.of("memberPermissions.oneAdminRequired"), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.userManager(), never()).revokeWeblogPermission(any(), any(), any());
    }

    /** Review focus 4: with another Admin left, removing one works, and is logged. */
    @Test
    void removingAnAdminWhileAnotherRemainsWorks() throws Exception {
        WeblogPermission current = member(person, "admin");
        when(weblogger.userManager().getWeblogPermission(travel, person)).thenReturn(current);
        when(weblogger.userManager().getWeblogPermissions(travel)).thenReturn(List.of(current, member(agent, "admin")));

        try (AdminLogCapture log = AdminLogCapture.start()) {
            controller.remove(asAdmin(), redirect, "jake", "travel");

            verify(weblogger.userManager()).revokeWeblogPermission(travel, person, WeblogPermission.ALL_ACTIONS);
            assertEquals(1, weblogger.flushCount());
            assertEquals(List.of("userWeblogs.removed[travel]"), ControllerTestFixture.flashMessages(redirect));
            assertEquals(List.of("membership.removed account=jake weblog=travel by=admin"), log.lines());
        }
    }

    @Test
    void changingOrRemovingANonMemberSaysSo() throws Exception {
        controller.change(asAdmin(), redirect, "jake", "travel", "post");
        assertEquals(List.of("userWeblogs.error.notMember"), ControllerTestFixture.flashErrors(redirect));

        RedirectAttributesModelMap again = new RedirectAttributesModelMap();
        controller.remove(asAdmin(), again, "jake", "travel");
        assertEquals(List.of("userWeblogs.error.notMember"), ControllerTestFixture.flashErrors(again));
    }

    @Test
    void changingToAnUnknownLevelIsRefusedBeforeAnythingIsLookedUp() throws Exception {
        controller.change(asAdmin(), redirect, "jake", "travel", "owner");

        assertEquals(List.of("userWeblogs.error.level"), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.userManager(), never()).revokeWeblogPermission(any(), any(), any());
    }

    /** A failed write is reported, never swallowed into a success banner. */
    @Test
    void aStoreFailureIsReportedNotSwallowed() throws Exception {
        doThrow(new WebloggerException("db down"))
                .when(weblogger.userManager()).grantWeblogPermission(travel, person, List.of("post"));

        controller.grant(asAdmin(), redirect, "jake", "travel", "post");

        assertEquals(List.of("memberPermissions.saveError"), ControllerTestFixture.flashErrors(redirect));
        assertEquals(0, weblogger.flushCount());
    }
}
