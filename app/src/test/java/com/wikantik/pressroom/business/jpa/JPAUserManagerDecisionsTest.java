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
package com.wikantik.pressroom.business.jpa;

import java.util.Date;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decisions {@link JPAUserManagerImpl} makes about who may be created,
 * found and given authority: what it refuses outright, how reader accounts
 * differ from editors, and how permission grants and revocations compose.
 * Listing and the tri-state enabled filter are in {@code UserQueryAndRoleTest}.
 */
class JPAUserManagerDecisionsTest {

    private User user;
    private Weblog weblog;
    private UserManager users;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        users = TestUtils.weblogger().getUserManager();
        user = TestUtils.setupUser("umdec");
        weblog = TestUtils.setupWeblog("umdecblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private User newAccount(String name) {
        User u = new User();
        u.setUserName(name);
        u.setPassword(TestUtils.TEST_PASSWORD_HASH);
        u.setScreenName("Screen");
        u.setFullName("Full");
        u.setEmailAddress("umdec@dev.null");
        u.setLocale("en_US");
        u.setTimeZone("America/Los_Angeles");
        u.setDateCreated(new Date());
        u.setEnabled(Boolean.TRUE);
        return u;
    }

    // ------------------------------------------------------------ account creation

    @Test
    void anAccountCannotBeAddedTwiceUnderTheSameNameOrADifferentCase() throws Exception {
        assertThrows(WebloggerException.class, () -> users.addUser(null));
        assertThrows(WebloggerException.class, () -> users.addUser(newAccount(user.getUserName())),
                "same name");
        assertThrows(WebloggerException.class,
                () -> users.addUser(newAccount(user.getUserName().toUpperCase(java.util.Locale.ROOT))),
                "a name differing only in case would be a second login for the same person");
    }

    @Test
    void aReaderAccountMustBeSsoBoundAndGetsOnlyTheReaderRole() throws Exception {
        assertThrows(WebloggerException.class, () -> users.addReader(null));
        assertThrows(WebloggerException.class, () -> users.addReader(newAccount("junit_umdecreader")),
                "a password account is not a reader");

        User reader = newAccount("junit_umdecreader");
        reader.setSsoIssuer("https://issuer.example");
        reader.setSsoSubject("subject-umdec");
        users.addReader(reader);
        TestUtils.endSession(true);
        try {
            User stored = users.getUserBySsoIdentity("https://issuer.example", "subject-umdec");
            assertNotNull(stored, "the SSO identity finds the account");
            assertEquals("junit_umdecreader", stored.getUserName());
            assertTrue(users.checkPermission(new GlobalPermission(List.of(GlobalPermission.COMMENT)), stored),
                    "a reader may comment");
            assertFalse(users.checkPermission(new GlobalPermission(List.of(GlobalPermission.WEBLOG)), stored),
                    "a reader must not get the editor role addUser would hand out");
            assertFalse(users.checkPermission(new GlobalPermission(List.of(GlobalPermission.ADMIN)), stored));

            User duplicate = newAccount("junit_umdecreader");
            duplicate.setSsoIssuer("https://issuer.example");
            duplicate.setSsoSubject("someone-else");
            assertThrows(WebloggerException.class, () -> users.addReader(duplicate),
                    "the name is taken");
        } finally {
            TestUtils.teardownUser("junit_umdecreader");
            TestUtils.endSession(true);
        }
    }

    @Test
    void aReaderNameIsTakenEvenWhenTheExistingAccountIsDisabled() throws Exception {
        User managed = TestUtils.getManagedUser(user);
        managed.setEnabled(Boolean.FALSE);
        users.saveUser(managed);
        TestUtils.endSession(true);

        User reader = newAccount(user.getUserName());
        reader.setSsoIssuer("https://issuer.example");
        reader.setSsoSubject("subject-clash");
        assertThrows(WebloggerException.class, () -> users.addReader(reader),
                "a disabled account still owns its name");
    }

    // ------------------------------------------------------------------- lookups

    @Test
    void identityLookupsRefuseMissingPartsAndFindNothingForUnknownOnes() throws Exception {
        assertThrows(WebloggerException.class, () -> users.getUserByEmail(null));
        assertThrows(WebloggerException.class, () -> users.getUserBySsoIdentity(null, "s"));
        assertThrows(WebloggerException.class, () -> users.getUserBySsoIdentity("i", null));
        assertThrows(WebloggerException.class, () -> users.getUserByUserName(null));
        assertNull(users.getUserBySsoIdentity("https://issuer.example", "no-such-subject"));
    }

    @Test
    void aRemovedAccountIsNotServedFromTheNameCache() throws Exception {
        User other = TestUtils.setupUser("umdecgone");
        TestUtils.endSession(true);
        assertNotNull(users.getUserByUserName(other.getUserName()), "primes the name cache");

        TestUtils.teardownUser(other.getUserName());
        TestUtils.endSession(true);

        assertNull(users.getUserByUserName(other.getUserName(), null), "a stale cache entry must not resurrect it");
    }

    @Test
    void aCachedAccountIsNotReturnedWhenItsEnabledStateDoesNotMatch() throws Exception {
        assertNotNull(users.getUserByUserName(user.getUserName(), Boolean.TRUE), "primes the name cache");

        assertNull(users.getUserByUserName(user.getUserName(), Boolean.FALSE),
                "an enabled account must not be served to a lookup for disabled ones");
    }

    @Test
    void usersStartingWithFiltersByEnabledStateWithOrWithoutAPrefix() throws Exception {
        User managed = TestUtils.getManagedUser(user);
        managed.setEnabled(Boolean.FALSE);
        users.saveUser(managed);
        TestUtils.endSession(true);
        String name = user.getUserName();

        assertTrue(users.getUsersStartingWith(null, Boolean.FALSE, 0, -1).stream()
                .anyMatch(u -> name.equals(u.getUserName())), "disabled, no prefix");
        assertTrue(users.getUsersStartingWith(null, Boolean.TRUE, 0, -1).stream()
                .noneMatch(u -> name.equals(u.getUserName())), "enabled-only excludes it");
        assertTrue(users.getUsersStartingWith("junit_umdec", Boolean.FALSE, 0, -1).stream()
                .anyMatch(u -> name.equals(u.getUserName())), "disabled, with prefix");
        assertTrue(users.getUsersStartingWith("junit_umdec", null, 0, -1).stream()
                .anyMatch(u -> name.equals(u.getUserName())), "either, with prefix");
        assertEquals(1, users.getUsersStartingWith("junit_umdec", null, 0, 1).size(), "length caps the page");
        assertTrue(users.getUsersStartingWith("junit_umdec", null, 5, -1).isEmpty(), "offset moves past the rows");
    }

    @Test
    void usersByLetterAndByCreationWindowPage() throws Exception {
        assertTrue(users.getUsersByLetter('J', 0, -1).stream()
                .anyMatch(u -> user.getUserName().equals(u.getUserName())), "letter match ignores case");
        assertEquals(1, users.getUsersByLetter('j', 0, 1).size());
        assertTrue(users.getUsersByLetter('j', 100_000, -1).isEmpty());

        Date past = new Date(System.currentTimeMillis() - 3_600_000L);
        Date future = new Date(System.currentTimeMillis() + 3_600_000L);
        assertTrue(users.getUsers(null, past, future, 0, -1).stream()
                .anyMatch(u -> user.getUserName().equals(u.getUserName())));
        assertTrue(users.getUsers(null, future, null, 0, -1).stream()
                .noneMatch(u -> user.getUserName().equals(u.getUserName())), "created before the window");
        assertTrue(users.getUsers(null, null, past, 0, -1).stream()
                .noneMatch(u -> user.getUserName().equals(u.getUserName())), "created after the window");
        assertEquals(1, users.getUsers(Boolean.TRUE, null, null, 0, 1).size());
    }

    // ------------------------------------------------------------- authority

    @Test
    void grantingAnActionTwiceMergesAndRevokingOneLeavesTheRest() throws Exception {
        User managed = TestUtils.getManagedUser(user);
        Weblog managedBlog = TestUtils.getManagedWebsite(weblog);
        users.grantWeblogPermission(managedBlog, managed, List.of(WeblogPermission.EDIT_DRAFT));
        users.grantWeblogPermission(managedBlog, managed, List.of(WeblogPermission.POST));
        TestUtils.endSession(true);

        managed = TestUtils.getManagedUser(user);
        managedBlog = TestUtils.getManagedWebsite(weblog);
        WeblogPermission merged = users.getWeblogPermission(managedBlog, managed);
        assertTrue(merged.hasAction(WeblogPermission.EDIT_DRAFT) && merged.hasAction(WeblogPermission.POST),
                "the second grant adds to the first rather than replacing it");

        users.revokeWeblogPermission(managedBlog, managed, List.of(WeblogPermission.POST));
        TestUtils.endSession(true);
        managed = TestUtils.getManagedUser(user);
        managedBlog = TestUtils.getManagedWebsite(weblog);
        WeblogPermission rest = users.getWeblogPermission(managedBlog, managed);
        assertNotNull(rest, "a partial revoke keeps the permission");
        assertTrue(rest.hasAction(WeblogPermission.EDIT_DRAFT));
        assertFalse(rest.hasAction(WeblogPermission.POST));
    }

    @Test
    void revokingAPermissionTheUserNeverHadOrARoleNeverHeldIsAnError() throws Exception {
        User stranger = TestUtils.setupUser("umdecstranger");
        TestUtils.endSession(true);
        try {
            User managedStranger = TestUtils.getManagedUser(stranger);
            Weblog managedBlog = TestUtils.getManagedWebsite(weblog);
            assertThrows(WebloggerException.class,
                    () -> users.revokeWeblogPermission(managedBlog, managedStranger, WeblogPermission.ALL_ACTIONS),
                    "no permission row to revoke");
            assertThrows(WebloggerException.class, () -> users.revokeRole("no-such-role", managedStranger));
        } finally {
            TestUtils.teardownUser(stranger.getUserName());
            TestUtils.endSession(true);
        }
    }
}
