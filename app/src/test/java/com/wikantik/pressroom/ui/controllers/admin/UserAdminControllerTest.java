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

package com.wikantik.pressroom.ui.controllers.admin;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Admin → Users (spec 2026-10-13, section 4; criterion 19): every account, tabs and a filter. */
class UserAdminControllerTest {

    private MockWeblogger weblogger;
    private UserAdminController controller;
    private ExtendedModelMap model;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new UserAdminController());
        model = new ExtendedModelMap();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    @Test
    void theUsersPageIsAdminOnlyAndNeedsNoWeblog() {
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
        assertEquals("userAdmin", controller.getActionName());
        assertEquals("userAdmin.title.searchUser", controller.getPageTitle());
    }

    @Test
    void theListShowsEveryAccountWithKindRoleStatusWeblogsAndLastActive() throws Exception {
        User person = account("jake", User.Kind.PERSON);
        person.setLastLogin(new Date(1_000_000L));
        User agent = account("bot", User.Kind.AGENT);
        agent.setEnabled(Boolean.FALSE);
        when(weblogger.userManager().getAccounts(null, null)).thenReturn(List.of(person, agent));
        when(weblogger.userManager().getRoles(person)).thenReturn(List.of("editor", "admin"));
        when(weblogger.userManager().getRoles(agent)).thenReturn(List.of("editor"));
        when(weblogger.userManager().getWeblogPermissions(person))
                .thenReturn(List.of(new WeblogPermission(), new WeblogPermission()));
        when(weblogger.apiTokenManager().getTokens(agent))
                .thenReturn(List.of(usedAt(1_000L), usedAt(5_000L), usedAt(null)));

        String view = controller.execute(ControllerTestFixture.requestFor(admin()), model, null, null);

        assertEquals(".UserAdmin", view);
        List<AccountRow> rows = rows();
        assertEquals(2, rows.size());

        AccountRow jake = rows.get(0);
        assertEquals("jake", jake.getUserName());
        assertFalse(jake.isAgent());
        assertEquals(AccountRow.Role.SITE_ADMIN, jake.getRole());
        assertTrue(jake.isEnabled());
        assertEquals(2, jake.getWeblogCount());
        assertEquals(1_000_000L, jake.getLastActive().getTime(), "a person's last active is the last sign-in");

        AccountRow bot = rows.get(1);
        assertTrue(bot.isAgent());
        assertEquals(AccountRow.Role.AUTHOR, bot.getRole());
        assertFalse(bot.isEnabled());
        assertEquals(0, bot.getWeblogCount());
        assertEquals(5_000L, bot.getLastActive().getTime(), "an agent's last active is its newest token use");
        assertNotNull(model.getAttribute("menu"), "the admin menu is what makes the rest of the section reachable");
    }

    @Test
    void anAccountWithNoLoginRoleIsAReaderAndOneNeverActiveShowsNothing() throws Exception {
        User reader = account("reader-abc", User.Kind.PERSON);
        when(weblogger.userManager().getAccounts(null, null)).thenReturn(List.of(reader));
        when(weblogger.userManager().getRoles(reader)).thenReturn(List.of("reader"));

        controller.execute(ControllerTestFixture.requestFor(admin()), model, null, null);

        AccountRow row = rows().get(0);
        assertEquals(AccountRow.Role.READER, row.getRole());
        assertNull(row.getLastActive());
    }

    /** Criterion 19: the tabs and the text filter narrow the list on the server. */
    @Test
    void thePeopleAndAgentsTabsAndTheFilterNarrowTheListServerSide() throws Exception {
        controller.execute(ControllerTestFixture.requestFor(admin()), model, "agents", "  bo ");

        verify(weblogger.userManager()).getAccounts(User.Kind.AGENT, "bo");
        assertEquals("agents", model.getAttribute("kindFilter"));
        assertEquals("bo", model.getAttribute("textFilter"));

        controller.execute(ControllerTestFixture.requestFor(admin()), new ExtendedModelMap(), "people", null);
        verify(weblogger.userManager()).getAccounts(User.Kind.PERSON, null);

        ExtendedModelMap anything = new ExtendedModelMap();
        controller.execute(ControllerTestFixture.requestFor(admin()), anything, "nonsense", "");
        verify(weblogger.userManager()).getAccounts(null, null);
        assertEquals("", anything.getAttribute("kindFilter"));
        assertEquals("", anything.getAttribute("textFilter"));
    }

    /** A display path: a store that cannot list shows an empty list, not an error page. */
    @Test
    void aStoreThatCannotListShowsAnEmptyList() throws Exception {
        when(weblogger.userManager().getAccounts(null, null)).thenThrow(new WebloggerException("db down"));

        String view = controller.execute(ControllerTestFixture.requestFor(admin()), model, null, null);

        assertEquals(".UserAdmin", view);
        assertTrue(rows().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private List<AccountRow> rows() {
        return (List<AccountRow>) model.getAttribute("accounts");
    }

    private static User account(String userName, User.Kind kind) {
        User user = new User();
        user.setUserName(userName);
        user.setScreenName("Name of " + userName);
        user.setKind(kind);
        return user;
    }

    private static ApiToken usedAt(Long millis) {
        ApiToken token = new ApiToken();
        token.setLastUsedAt(millis == null ? null : new Timestamp(millis));
        return token;
    }

    private static User admin() {
        User user = new User();
        user.setUserName("admin");
        return user;
    }
}
