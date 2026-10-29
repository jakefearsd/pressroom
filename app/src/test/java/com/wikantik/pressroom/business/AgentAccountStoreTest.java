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
package com.wikantik.pressroom.business;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DB-backed: agent accounts, the last sign-in and the account list (spec 2026-10-13, sections 1, 2, 4). */
class AgentAccountStoreTest {

    private final List<String> created = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.endSession(false);
        for (String name : created) {
            TestUtils.teardownUser(name);
        }
        TestUtils.endSession(true);
    }

    private static UserManager mgr() {
        return TestUtils.weblogger().getUserManager();
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private User addAgent(String userName, String screenName, String email, boolean siteAdmin) throws Exception {
        User agent = new User();
        agent.setUserName(userName);
        agent.setScreenName(screenName);
        agent.setFullName(screenName);
        agent.setEmailAddress(email);
        agent.setDateCreated(new Date());
        mgr().addAgent(agent, siteAdmin);
        TestUtils.endSession(true);
        created.add(userName);
        return mgr().getUserByUserName(userName, null);
    }

    private User addPerson(String name) throws Exception {
        User person = TestUtils.setupUser(name);
        TestUtils.endSession(true);
        created.add(person.getUserName());
        return person;
    }

    /** Criterion 2. */
    @Test
    void anAgentIsStoredWithNoPasswordAsAnEnabledEditor() throws Exception {
        String name = "junit_agent" + suffix();
        User agent = addAgent(name, "Agent One", null, false);

        assertEquals(User.Kind.AGENT, agent.getKind());
        assertTrue(agent.isAgent());
        assertNull(agent.getPassword());
        assertEquals(Boolean.TRUE, agent.getEnabled());
        assertEquals(name + "@agents.invalid", agent.getEmailAddress());
        assertEquals(Set.of("editor"), Set.copyOf(mgr().getRoles(agent)));
        assertFalse(mgr().checkPermission(new GlobalPermission(List.of(GlobalPermission.ADMIN)), agent));
    }

    /** Criteria 2 and 23: the site-admin switch is the admin role, and the admin role is the admin permission. */
    @Test
    void theSiteAdminSwitchAddsTheAdminRole() throws Exception {
        User agent = addAgent("junit_agentadm" + suffix(), "Agent Admin", null, true);

        assertEquals(Set.of("editor", "admin"), Set.copyOf(mgr().getRoles(agent)));
        assertTrue(mgr().checkPermission(new GlobalPermission(List.of(GlobalPermission.ADMIN)), agent));
    }

    @Test
    void aGivenEmailIsKept() throws Exception {
        User agent = addAgent("junit_agentmail" + suffix(), "Agent Mail", "ops@example.com", false);

        assertEquals("ops@example.com", agent.getEmailAddress());
    }

    @Test
    void aTakenUserNameIsRefused() throws Exception {
        User person = addPerson("agenttaken" + suffix());
        User clash = new User();
        clash.setUserName(person.getUserName());
        clash.setScreenName("Clash");
        clash.setFullName("Clash");
        clash.setDateCreated(new Date());

        WebloggerException refused = assertThrows(WebloggerException.class, () -> mgr().addAgent(clash, false));

        assertEquals("error.add.user.userNameInUse", refused.getMessage());
    }

    @Test
    void anAgentWithAPasswordIsRefused() {
        User agent = new User();
        agent.setUserName("junit_agentpw" + suffix());
        agent.setScreenName("Agent Pw");
        agent.setFullName("Agent Pw");
        agent.setPassword(TestUtils.TEST_PASSWORD_HASH);
        agent.setDateCreated(new Date());

        assertThrows(WebloggerException.class, () -> mgr().addAgent(agent, false));
    }

    /** Criterion 20, the store half. */
    @Test
    void recordingASignInStampsTheLastLogin() throws Exception {
        User person = addPerson("agentlogin" + suffix());
        assertNull(mgr().getUserByUserName(person.getUserName(), null).getLastLogin());

        long before = System.currentTimeMillis();
        mgr().recordLogin(person.getUserName());
        TestUtils.endSession(true);

        Date stamped = mgr().getUserByUserName(person.getUserName(), null).getLastLogin();
        assertNotNull(stamped);
        assertTrue(stamped.getTime() >= before - 1_000L && stamped.getTime() <= System.currentTimeMillis() + 1_000L);
    }

    /**
     * Characterisation (final review, ledger T3): written to pass at once against the existing code. An
     * unknown name creates no account and stamps nobody else.
     */
    @Test
    void recordingASignInForAnUnknownNameDoesNothing() throws Exception {
        User bystander = addPerson("agentbystander" + suffix());
        String unknown = "junit_nosuchaccount" + suffix();

        mgr().recordLogin(unknown);
        TestUtils.endSession(true);

        assertNull(mgr().getUserByUserName(unknown, null), "recording a sign-in must not create an account");
        assertNull(mgr().getUserByUserName(bystander.getUserName(), null).getLastLogin(),
                "nobody else's last sign-in may change");
    }

    /** Criterion 19, the store half: kind and text narrow the list, server-side. */
    @Test
    void theAccountListNarrowsByKindAndByText() throws Exception {
        String sfx = suffix();
        User agent = addAgent("junit_agentlist" + sfx, "Agent Zebra" + sfx, null, false);
        User person = addPerson("agentlistp" + sfx);
        person = mgr().getUserByUserName(person.getUserName(), null);
        person.setEmailAddress("quokka" + sfx + "@example.com");
        mgr().saveUser(person);
        TestUtils.endSession(true);

        List<String> agents = names(mgr().getAccounts(User.Kind.AGENT, null));
        List<String> people = names(mgr().getAccounts(User.Kind.PERSON, null));
        List<String> everyone = names(mgr().getAccounts(null, "  "));

        assertTrue(agents.contains(agent.getUserName()));
        assertFalse(agents.contains(person.getUserName()));
        assertTrue(people.contains(person.getUserName()));
        assertFalse(people.contains(agent.getUserName()));
        assertTrue(everyone.containsAll(List.of(agent.getUserName(), person.getUserName())));
        assertEquals(List.of(agent.getUserName()), names(mgr().getAccounts(null, "zebra" + sfx)),
                "the name matches, case-insensitively");
        assertEquals(List.of(person.getUserName()), names(mgr().getAccounts(null, "QUOKKA" + sfx)),
                "the email matches");
        assertEquals(List.of(), names(mgr().getAccounts(User.Kind.AGENT, "quokka" + sfx)),
                "kind and text combine");
    }

    /** Criterion 5, the store half: a reset by email never reaches an agent, even one sorting first. */
    @Test
    void anEmailLookupNeverFindsAnAgent() throws Exception {
        String sfx = suffix();
        String shared = "shared" + sfx + "@example.com";
        addAgent("junit_a0" + sfx, "Agent Shared", shared, false);
        User person = addPerson("zz" + sfx);
        person = mgr().getUserByUserName(person.getUserName(), null);
        person.setEmailAddress(shared);
        mgr().saveUser(person);
        String agentOnly = "agentonly" + sfx + "@example.com";
        addAgent("junit_a1" + sfx, "Agent Only", agentOnly, false);
        TestUtils.endSession(true);

        assertEquals(person.getUserName(), mgr().getUserByEmail(shared).getUserName());
        assertNull(mgr().getUserByEmail(agentOnly));
    }

    private static List<String> names(List<User> users) {
        return users.stream().map(User::getUserName).toList();
    }
}
