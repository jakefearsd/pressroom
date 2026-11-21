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

package com.wikantik.pressroom.ui.controllers.editor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterisation tests (spec 2026-11-20-jsp-rules-and-view-models-design,
 * Task 2, criterion 1): written before the membership rules moved out of
 * {@link MembersController} into {@code Memberships}, and passing on the code
 * they replaced. Each pins the Members page's whole answer to a refused save
 * or grant -- every error in order, the control marked, the view, the
 * messages -- and that nothing was written, so the move (criterion 3) is seen
 * to change none of them.
 */
class MembersCharacterisationTest extends EditorControllerTestSupport {

    private MembersController controller;
    private Model model;
    private List<WeblogPermission> stored;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new MembersController());
        model = newModel();
        stored = new ArrayList<>();
        when(weblogger.getUserManager().getWeblogPermissions(weblog)).thenReturn(stored);
        when(weblogger.getWeblogManager().getWeblogByHandle(WEBLOG_HANDLE, null)).thenReturn(weblog);
        resolves(user);
        when(weblogger.getUserManager().checkPermission(any(GlobalPermission.class), any(User.class)))
                .thenReturn(true);
    }

    // --- save: the whole table ---

    @Test
    void aPostedValueThatIsNoLevelRefusesTheSaveOnce() throws Exception {
        member(user, WeblogPermission.ADMIN);
        User bob = member(other("bob"), WeblogPermission.POST);
        User carol = member(other("carol"), WeblogPermission.POST);
        post(user, WeblogPermission.ADMIN);
        post(bob, "bogus");
        post(carol, "admin,post");

        assertSaveRefused(List.of("userWeblogs.error.level"));
    }

    @Test
    void aChangeToYourOwnMembershipIsRefused() throws Exception {
        member(user, WeblogPermission.ADMIN);
        User bob = member(other("bob"), WeblogPermission.ADMIN);
        post(user, WeblogPermission.POST);
        post(bob, WeblogPermission.ADMIN);

        assertSaveRefused(List.of("memberPermissions.noSelfModifications"));
    }

    @Test
    void removingYourselfIsAChangeToYourOwnMembership() throws Exception {
        member(user, WeblogPermission.ADMIN);
        User bob = member(other("bob"), WeblogPermission.ADMIN);
        post(user, REMOVE);
        post(bob, WeblogPermission.ADMIN);

        assertSaveRefused(List.of("memberPermissions.noSelfModifications"));
    }

    @Test
    void demotingTheOnlyAdminLeavesNone() throws Exception {
        User bob = member(other("bob"), WeblogPermission.ADMIN);
        User carol = member(other("carol"), WeblogPermission.POST);
        post(bob, WeblogPermission.POST);
        post(carol, WeblogPermission.POST);

        assertSaveRefused(List.of("memberPermissions.oneAdminRequired"));
    }

    @Test
    void removingTheOnlyAdminLeavesNone() throws Exception {
        User bob = member(other("bob"), WeblogPermission.ADMIN);
        post(bob, REMOVE);

        assertSaveRefused(List.of("memberPermissions.oneAdminRequired"));
    }

    @Test
    void anotherMemberPostedAsAdminKeepsOne() throws Exception {
        User bob = member(other("bob"), WeblogPermission.ADMIN);
        User carol = member(other("carol"), WeblogPermission.POST);
        post(bob, WeblogPermission.POST);
        post(carol, WeblogPermission.ADMIN);

        assertEquals(".Members", controller.save(request, model));

        assertEquals(List.of(), errors(model));
        assertEquals(List.of("memberPermissions.membersChanged"), messages(model));
    }

    /** Only posted rows are counted: a submission with none counts no admin, whatever is stored. */
    @Test
    void aSubmissionPostingNothingCountsNoAdmin() throws Exception {
        member(user, WeblogPermission.ADMIN);
        member(other("bob"), WeblogPermission.ADMIN);

        assertSaveRefused(List.of("memberPermissions.oneAdminRequired"));
    }

    /** An admin row the form did not post is not counted, though the save would leave it alone. */
    @Test
    void anAdminRowNotPostedIsNotCounted() throws Exception {
        member(other("bob"), WeblogPermission.ADMIN);
        User carol = member(other("carol"), WeblogPermission.POST);
        post(carol, WeblogPermission.EDIT_DRAFT);

        assertSaveRefused(List.of("memberPermissions.oneAdminRequired"));
    }

    /** A membership whose account no longer resolves has no control on the page and is not counted. */
    @Test
    void aRowWhoseAccountDoesNotResolveIsNotCounted() throws Exception {
        User ghost = other("ghost");
        member(ghost, WeblogPermission.ADMIN);
        when(weblogger.getUserManager().getUserByUserName("ghost")).thenReturn(null);
        when(request.getParameter("perm-" + ghost.getId())).thenReturn(WeblogPermission.ADMIN);
        User carol = member(other("carol"), WeblogPermission.POST);
        post(carol, WeblogPermission.POST);

        assertSaveRefused(List.of("memberPermissions.oneAdminRequired"));
    }

    @Test
    void everyReasonIsReportedSelfThenLevelThenAdmins() throws Exception {
        member(user, WeblogPermission.ADMIN);
        post(user, "bogus");

        assertSaveRefused(List.of("memberPermissions.noSelfModifications", "userWeblogs.error.level",
                "memberPermissions.oneAdminRequired"));
    }

    @Test
    void aStoreThatCannotListTheMembersRefusesTheSave() throws Exception {
        when(weblogger.getUserManager().getWeblogPermissions(weblog)).thenThrow(new WebloggerException("down"));

        assertSaveRefused(List.of("memberPermissions.saveError"));
    }

    // --- grant ---

    @Test
    void grantingWhileGroupBloggingIsOffIsRefusedFirst() throws Exception {
        givenRuntimeProperty("groupblogging.enabled", "false");

        assertGrantRefused("ghost", "bogus", List.of("memberPermissions.error.groupBloggingDisabled"), List.of());
        verify(weblogger.getUserManager(), never()).getUserByUserName(any());
    }

    @Test
    void grantingToAnUnknownOrBlankUserNameMarksTheField() throws Exception {
        assertGrantRefused("ghost", WeblogPermission.POST, List.of("inviteMember.error.userNotFound"),
                List.of("grantUserName"));
        model = newModel();
        assertGrantRefused("  ", WeblogPermission.POST, List.of("inviteMember.error.userNotFound"),
                List.of("grantUserName"));
    }

    @Test
    void grantingToAReaderAccountIsRefused() throws Exception {
        User reader = other("reader");
        when(weblogger.getUserManager().checkPermission(any(GlobalPermission.class), eq(reader))).thenReturn(false);

        assertGrantRefused("reader", WeblogPermission.POST, List.of("inviteMember.error.readerAccount"),
                List.of("grantUserName"));
    }

    @Test
    void aReaderCheckThatCannotRunRefusesTheGrant() throws Exception {
        User bob = other("bob");
        when(weblogger.getUserManager().checkPermission(any(GlobalPermission.class), eq(bob)))
                .thenThrow(new WebloggerException("down"));

        assertGrantRefused("bob", WeblogPermission.POST, List.of("memberPermissions.saveError"), List.of());
    }

    @Test
    void aBlankLevelIsMalformedAndAnyOtherNonLevelIsTheLevelError() throws Exception {
        other("bob");
        assertGrantRefused("bob", " ", List.of("memberPermissions.saveError"), List.of());
        model = newModel();
        assertGrantRefused("bob", "admin,post", List.of("userWeblogs.error.level"), List.of());
        model = newModel();
        assertGrantRefused("bob", REMOVE, List.of("userWeblogs.error.level"), List.of());
    }

    @Test
    void anUnknownUserAndABadLevelAreBothReported() throws Exception {
        assertGrantRefused("ghost", "bogus", List.of("inviteMember.error.userNotFound", "userWeblogs.error.level"),
                List.of("grantUserName"));
    }

    // --- helpers ---

    /** The Members page's removal value, written out: the move gives it a home. */
    private static final String REMOVE = "-1";

    private void assertSaveRefused(List<String> errors) throws WebloggerException {
        assertEquals(".Members", controller.save(request, model));
        assertEquals(errors, errors(model));
        assertEquals(List.of(), invalidFields(model));
        assertEquals(List.of(), messages(model));
        verify(weblogger.getUserManager(), never()).revokeWeblogPermission(any(), any(), any());
        verify(weblogger.getUserManager(), never()).grantWeblogPermission(any(), any(), any());
    }

    private void assertGrantRefused(String userName, String level, List<String> errors, List<String> fields)
            throws WebloggerException {
        assertEquals(".Members", controller.grant(request, model, userName, level));
        assertEquals(errors, errors(model));
        assertEquals(fields, invalidFields(model));
        assertEquals(List.of(), messages(model));
        verify(weblogger.getUserManager(), never()).grantWeblogPermission(any(), any(), any());
    }

    private User other(String userName) {
        User other = new User();
        other.setId("user-" + userName);
        other.setUserName(userName);
        other.setDateCreated(new Date());
        other.setEnabled(Boolean.TRUE);
        resolves(other);
        return other;
    }

    private void resolves(User member) {
        try {
            when(weblogger.getUserManager().getUserByUserName(member.getUserName())).thenReturn(member);
        } catch (WebloggerException e) {
            throw new IllegalStateException(e);
        }
    }

    private User member(User member, String level) {
        stored.add(new WeblogPermission(weblog, member, List.of(level)));
        return member;
    }

    private void post(User member, String value) {
        when(request.getParameter("perm-" + member.getId())).thenReturn(value);
    }
}
