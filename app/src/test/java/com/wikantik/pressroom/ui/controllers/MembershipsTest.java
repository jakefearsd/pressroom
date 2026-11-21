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
package com.wikantik.pressroom.ui.controllers;

import java.util.ArrayList;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The membership decisions {@link Memberships} makes for both membership
 * screens, with no controller behind them (spec
 * 2026-11-20-jsp-rules-and-view-models-design, Task 2, criterion 2).
 */
class MembershipsTest {

    private UserManager users;
    private Weblog travel;
    private List<WeblogPermission> stored;

    @BeforeEach
    void setUp() throws Exception {
        users = mock(UserManager.class);
        travel = new Weblog();
        travel.setHandle("travel");
        stored = new ArrayList<>();
        when(users.getWeblogPermissions(travel)).thenReturn(stored);
    }

    // --- levels ---

    @Test
    void aLevelIsOneOfTheThree() {
        for (String level : Memberships.LEVELS) {
            assertNull(Memberships.levelProblem(level, false), level);
        }
        for (String value : new String[] {null, "", " ", "bogus", "admin,post", "ADMIN", Memberships.REMOVE}) {
            assertEquals("userWeblogs.error.level", Memberships.levelProblem(value, false), value);
        }
    }

    @Test
    void removalIsAValueOnlyWhereTheCallerAllowsIt() {
        assertNull(Memberships.levelProblem(Memberships.REMOVE, true));
        assertNull(Memberships.levelProblem(WeblogPermission.POST, true));
        assertEquals("userWeblogs.error.level", Memberships.levelProblem(null, true));
        assertEquals("userWeblogs.error.level", Memberships.levelProblem("-2", true));
    }

    // --- who may hold one ---

    @Test
    void anAccountWithTheLoginActionMayHoldAMembershipAndAReaderMayNot() throws Exception {
        User editor = new User();
        editor.setUserName("editor");
        User reader = new User();
        reader.setUserName("reader");
        when(users.checkPermission(argThat(p -> p instanceof GlobalPermission g
                && g.hasAction(GlobalPermission.LOGIN)), eq(editor))).thenReturn(true);

        assertTrue(Memberships.mayHold(users, editor));
        assertFalse(Memberships.mayHold(users, reader));
    }

    @Test
    void aReaderCheckThatCannotRunThrows() throws Exception {
        when(users.checkPermission(any(), any())).thenThrow(new WebloggerException("down"));

        assertThrows(WebloggerException.class, () -> Memberships.mayHold(users, new User()));
    }

    // --- group blogging ---

    @Test
    void groupBloggingIsOffOnlyWhenTheSettingSaysSo() throws Exception {
        MockWeblogger weblogger = MockWeblogger.attached();
        try {
            when(weblogger.propertiesManager().getProperty("groupblogging.enabled"))
                    .thenReturn(new RuntimeConfigProperty("groupblogging.enabled", "false"));
            assertTrue(Memberships.groupBloggingOff());

            when(weblogger.propertiesManager().getProperty("groupblogging.enabled"))
                    .thenReturn(new RuntimeConfigProperty("groupblogging.enabled", "true"));
            assertFalse(Memberships.groupBloggingOff());
        } finally {
            weblogger.detach();
        }
    }

    // --- your own membership ---

    @Test
    void onlyAnotherAdminMayChangeYourOwnMembership() {
        assertEquals("memberPermissions.noSelfModifications",
                Memberships.selfChangeProblem("jake", "jake", WeblogPermission.POST));
        assertEquals("memberPermissions.noSelfModifications",
                Memberships.selfChangeProblem("jake", "jake", Memberships.REMOVE));
        assertEquals("memberPermissions.noSelfModifications", Memberships.selfChangeProblem("jake", "jake", "bogus"));
        assertNull(Memberships.selfChangeProblem("jake", "jake", WeblogPermission.ADMIN), "keeping Admin");
        assertNull(Memberships.selfChangeProblem("jake", "bob", WeblogPermission.POST), "another member");
    }

    // --- no admin left: the Members page's whole table ---

    @Test
    void aTableWithNoAdminPostedLeavesNone() {
        assertTrue(Memberships.leavesNoAdmin(List.of(WeblogPermission.POST, Memberships.REMOVE)));
        assertTrue(Memberships.leavesNoAdmin(List.of()), "nothing posted counts no admin");
        assertFalse(Memberships.leavesNoAdmin(List.of(WeblogPermission.POST, WeblogPermission.ADMIN)));
    }

    // --- no admin left: one change on a person's page ---

    @Test
    void demotingOrRemovingTheOnlyAdminLeavesNone() throws Exception {
        WeblogPermission jake = member("jake", WeblogPermission.ADMIN);
        member("bob", WeblogPermission.POST);

        assertTrue(Memberships.leavesNoAdmin(users, travel, jake, WeblogPermission.POST));
        assertTrue(Memberships.leavesNoAdmin(users, travel, jake, Memberships.REMOVE));
    }

    @Test
    void demotingOneOfTwoAdminsLeavesOne() throws Exception {
        WeblogPermission jake = member("jake", WeblogPermission.ADMIN);
        member("bob", WeblogPermission.ADMIN);

        assertFalse(Memberships.leavesNoAdmin(users, travel, jake, WeblogPermission.EDIT_DRAFT));
    }

    @Test
    void aChangeThatTakesAdminFromNobodyCountsNothing() throws Exception {
        WeblogPermission jake = member("jake", WeblogPermission.ADMIN);
        WeblogPermission bob = member("bob", WeblogPermission.POST);

        assertFalse(Memberships.leavesNoAdmin(users, travel, jake, WeblogPermission.ADMIN));
        assertFalse(Memberships.leavesNoAdmin(users, travel, bob, Memberships.REMOVE));
        verify(users, never()).getWeblogPermissions(any(Weblog.class));
    }

    @Test
    void aCountThatCannotRunRefusesTheChangeByThrowing() throws Exception {
        WeblogPermission jake = member("jake", WeblogPermission.ADMIN);
        when(users.getWeblogPermissions(travel)).thenThrow(new WebloggerException("down"));

        assertThrows(WebloggerException.class,
                () -> Memberships.leavesNoAdmin(users, travel, jake, WeblogPermission.POST));
    }

    private WeblogPermission member(String userName, String level) {
        User user = new User();
        user.setUserName(userName);
        WeblogPermission permission = new WeblogPermission(travel, user, List.of(level));
        stored.add(permission);
        return permission;
    }
}
