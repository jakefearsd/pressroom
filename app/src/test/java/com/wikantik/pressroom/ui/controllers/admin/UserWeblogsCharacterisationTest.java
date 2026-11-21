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
package com.wikantik.pressroom.ui.controllers.admin;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterisation tests (spec 2026-11-20-jsp-rules-and-view-models-design,
 * Task 2, criterion 1): written before the membership rules moved out of
 * {@link UserWeblogsController} into {@code Memberships}, and passing on the
 * code they replaced. Each pins the Weblogs section's whole answer to a
 * refused grant, change or removal -- the flash errors in order, where it
 * redirects -- and that nothing was written, so the move (criterion 3) is
 * seen to change none of them.
 */
class UserWeblogsCharacterisationTest {

    private static final String TO_PERSON = "redirect:/pressroom-ui/admin/modifyUser.rol";

    private MockWeblogger weblogger;
    private UserWeblogsController controller;
    private RedirectAttributesModelMap redirect;
    private User jake;
    private Weblog travel;
    private List<WeblogPermission> members;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new UserWeblogsController());
        redirect = new RedirectAttributesModelMap();
        jake = account("jake");
        travel = new Weblog();
        travel.setHandle("travel");
        members = new ArrayList<>();
        when(weblogger.weblogManager().getWeblogByHandle("travel", null)).thenReturn(travel);
        when(weblogger.userManager().getWeblogPermissions(travel)).thenReturn(members);
        when(weblogger.userManager().checkPermission(any(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    // --- grant ---

    @Test
    void grantingWhileGroupBloggingIsOffIsRefusedBeforeAnythingElse() throws Exception {
        when(weblogger.propertiesManager().getProperty("groupblogging.enabled"))
                .thenReturn(new RuntimeConfigProperty("groupblogging.enabled", "false"));

        assertRefused(controller.grant(asAdmin(), redirect, "jake", "nowhere", "bogus"),
                List.of("userWeblogs.groupBloggingOff"));
    }

    @Test
    void grantingOnAnUnknownWeblogOrTheSiteSpaceIsNoSuchWeblog() throws Exception {
        assertRefused(controller.grant(asAdmin(), redirect, "jake", "nowhere", "bogus"),
                List.of("userWeblogs.error.noSuchWeblog"));

        Weblog site = new Weblog();
        site.setHandle("site");
        site.setSiteSpace(true);
        when(weblogger.weblogManager().getWeblogByHandle("site", null)).thenReturn(site);
        redirect = new RedirectAttributesModelMap();
        assertRefused(controller.grant(asAdmin(), redirect, "jake", "site", "post"),
                List.of("userWeblogs.error.noSuchWeblog"));
    }

    @Test
    void grantingAnythingButOneLevelIsTheLevelError() throws Exception {
        for (String level : new String[] {"", "-1", "admin,post", "bogus"}) {
            redirect = new RedirectAttributesModelMap();
            assertRefused(controller.grant(asAdmin(), redirect, "jake", "travel", level),
                    List.of("userWeblogs.error.level"));
        }
    }

    /** A defect the characterisation found: a form posting no level at all fails as a 500. */
    @Test
    void grantingWithNoLevelFailsOnTheNull() {
        assertThrows(NullPointerException.class, () -> controller.grant(asAdmin(), redirect, "jake", "travel", null));
    }

    @Test
    void grantingToAReaderAccountIsRefused() throws Exception {
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(jake))).thenReturn(false);

        assertRefused(controller.grant(asAdmin(), redirect, "jake", "travel", "post"),
                List.of("inviteMember.error.readerAccount"));
    }

    @Test
    void aReaderCheckThatCannotRunRefusesTheGrant() throws Exception {
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(jake)))
                .thenThrow(new WebloggerException("down"));

        assertRefused(controller.grant(asAdmin(), redirect, "jake", "travel", "post"),
                List.of("memberPermissions.saveError"));
    }

    @Test
    void anUnknownAccountIsNoSuchUserBackOnTheAccountList() throws Exception {
        String view = controller.grant(asAdmin(), redirect, "ghost", "travel", "post");

        assertEquals("redirect:/pressroom-ui/admin/userAdmin.rol", view);
        assertEquals(List.of("userAdmin.error.userNotFound"), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.userManager(), never()).grantWeblogPermission(any(), any(), any());
    }

    // --- change ---

    @Test
    void changingToAnythingButOneLevelIsTheLevelErrorBeforeTheWeblogIsLookedUp() throws Exception {
        assertRefused(controller.change(asAdmin(), redirect, "jake", "nowhere", "-1"),
                List.of("userWeblogs.error.level"));
        verify(weblogger.weblogManager(), never()).getWeblogByHandle(any(), any());
    }

    /** A defect the characterisation found: a form posting no level at all fails as a 500. */
    @Test
    void changingWithNoLevelFailsOnTheNull() {
        assertThrows(NullPointerException.class, () -> controller.change(asAdmin(), redirect, "jake", "travel", null));
    }

    @Test
    void changingAMembershipThatDoesNotExistIsRefused() throws Exception {
        assertRefused(controller.change(asAdmin(), redirect, "jake", "travel", "post"),
                List.of("userWeblogs.error.notMember"));
    }

    @Test
    void demotingTheOnlyAdminIsRefused() throws Exception {
        WeblogPermission mine = member(jake, WeblogPermission.ADMIN);
        member(account("bob"), WeblogPermission.POST);
        when(weblogger.userManager().getWeblogPermission(travel, jake)).thenReturn(mine);

        assertRefused(controller.change(asAdmin(), redirect, "jake", "travel", "post"),
                List.of("memberPermissions.oneAdminRequired"));
    }

    @Test
    void demotingOneOfTwoAdminsIsAllowed() throws Exception {
        WeblogPermission mine = member(jake, WeblogPermission.ADMIN);
        member(account("bob"), WeblogPermission.ADMIN);
        when(weblogger.userManager().getWeblogPermission(travel, jake)).thenReturn(mine);

        assertEquals(TO_PERSON, controller.change(asAdmin(), redirect, "jake", "travel", "post"));

        assertEquals(List.of(), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.userManager()).grantWeblogPermission(travel, jake, List.of("post"));
    }

    /** No count is taken when the only admin keeps Admin, or when the member changed holds no Admin. */
    @Test
    void aChangeThatTakesAdminFromNobodyIsNotCounted() throws Exception {
        WeblogPermission mine = member(jake, WeblogPermission.ADMIN);
        when(weblogger.userManager().getWeblogPermission(travel, jake)).thenReturn(mine);
        assertEquals(TO_PERSON, controller.change(asAdmin(), redirect, "jake", "travel", "admin"));

        WeblogPermission authors = new WeblogPermission(travel, jake, List.of(WeblogPermission.POST));
        when(weblogger.userManager().getWeblogPermission(travel, jake)).thenReturn(authors);
        members.clear();
        assertEquals(TO_PERSON, controller.change(asAdmin(), redirect, "jake", "travel", "edit_draft"));

        assertEquals(List.of(), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.userManager(), never()).getWeblogPermissions(any(Weblog.class));
    }

    @Test
    void aCountThatCannotRunRefusesTheChange() throws Exception {
        WeblogPermission mine = member(jake, WeblogPermission.ADMIN);
        when(weblogger.userManager().getWeblogPermission(travel, jake)).thenReturn(mine);
        when(weblogger.userManager().getWeblogPermissions(travel)).thenThrow(new WebloggerException("down"));

        assertRefused(controller.change(asAdmin(), redirect, "jake", "travel", "post"),
                List.of("memberPermissions.saveError"));
    }

    // --- remove ---

    @Test
    void removingAMembershipThatDoesNotExistIsRefused() throws Exception {
        assertRefused(controller.remove(asAdmin(), redirect, "jake", "travel"),
                List.of("userWeblogs.error.notMember"));
    }

    @Test
    void removingTheOnlyAdminIsRefused() throws Exception {
        WeblogPermission mine = member(jake, WeblogPermission.ADMIN);
        when(weblogger.userManager().getWeblogPermission(travel, jake)).thenReturn(mine);

        assertRefused(controller.remove(asAdmin(), redirect, "jake", "travel"),
                List.of("memberPermissions.oneAdminRequired"));
    }

    /** A weblog left with no Admin some other way still lets a non-admin member go. */
    @Test
    void removingANonAdminFromAWeblogWithNoAdminIsAllowed() throws Exception {
        WeblogPermission mine = member(jake, WeblogPermission.POST);
        when(weblogger.userManager().getWeblogPermission(travel, jake)).thenReturn(mine);

        assertEquals(TO_PERSON, controller.remove(asAdmin(), redirect, "jake", "travel"));

        assertEquals(List.of(), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.userManager()).revokeWeblogPermission(travel, jake, WeblogPermission.ALL_ACTIONS);
    }

    @Test
    void aCountThatCannotRunRefusesTheRemoval() throws Exception {
        WeblogPermission mine = member(jake, WeblogPermission.ADMIN);
        when(weblogger.userManager().getWeblogPermission(travel, jake)).thenReturn(mine);
        when(weblogger.userManager().getWeblogPermissions(travel)).thenThrow(new WebloggerException("down"));

        assertRefused(controller.remove(asAdmin(), redirect, "jake", "travel"),
                List.of("memberPermissions.saveError"));
    }

    // --- helpers ---

    private void assertRefused(String view, List<String> errors) throws WebloggerException {
        assertEquals(TO_PERSON, view);
        assertEquals("jake", redirect.asMap().get("bean.userName"));
        assertEquals(errors, ControllerTestFixture.flashErrors(redirect));
        assertEquals(List.of(), ControllerTestFixture.flashMessages(redirect));
        verify(weblogger.userManager(), never()).grantWeblogPermission(any(), any(), any());
        verify(weblogger.userManager(), never()).revokeWeblogPermission(any(), any(), any());
    }

    private User account(String userName) throws WebloggerException {
        User user = new User();
        user.setUserName(userName);
        user.setKind(User.Kind.PERSON);
        when(weblogger.userManager().getUserByUserName(userName, null)).thenReturn(user);
        return user;
    }

    private WeblogPermission member(User user, String level) {
        WeblogPermission permission = new WeblogPermission(travel, user, List.of(level));
        members.add(permission);
        return permission;
    }

    private static HttpServletRequest asAdmin() {
        User admin = new User();
        admin.setUserName("admin");
        return ControllerTestFixture.requestFor(admin);
    }
}
