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
package org.apache.roller.weblogger.business;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * User lookup, listing and roles.
 *
 * <p>The listing side feeds the admin user table and the search endpoint behind
 * the invite picker, and the two of them are how an administrator finds an
 * account to act on. The enabled filter is the part worth pinning: it is
 * tri-state everywhere it appears, and collapsing "not specified" into "only
 * enabled" would silently hide every disabled account from the one screen that
 * exists to re-enable them.
 *
 * <p>Roles are the coarse authority Spring Security reads at sign-in --
 * {@code admin} and {@code editor} -- so granting and revoking them has to
 * round-trip exactly.
 */
class UserQueryAndRoleTest {

    private User alice;
    private User bob;
    private User disabled;

    /**
     * {@code TestUtils.setupUser} prefixes what it is given, so the stored
     * names are junit_uq*, not uq*. Every lookup below goes through the created
     * objects rather than a literal for that reason -- and the letter indexes
     * key on the prefix's initial, not the caller's.
     */
    private String aliceName;
    private String bobName;
    private String disabledName;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();

        alice = TestUtils.setupUser("uqAlice");
        bob = TestUtils.setupUser("uqBob");
        disabled = TestUtils.setupUser("uqCharlie");
        aliceName = alice.getUserName();
        bobName = bob.getUserName();
        disabledName = disabled.getUserName();
        TestUtils.endSession(true);

        User managed = users().getUserByUserName(disabled.getUserName());
        managed.setEnabled(Boolean.FALSE);
        users().saveUser(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        // Normalise the role state rather than assume it. addUser grants admin
        // to whichever user it creates while the table is empty
        // (users.firstUserAdmin), so whether these fixtures start out as
        // administrators depends on what else in the suite has just torn its
        // own users down -- which made the "role not held" assertions below
        // pass alone and fail in a full run.
        for (String name : List.of(aliceName, bobName)) {
            User account = users().getUserByUserName(name);
            if (users().hasRole("admin", account)) {
                users().revokeRole("admin", account);
            }
        }
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownUser(alice.getUserName());
        TestUtils.teardownUser(bob.getUserName());
        TestUtils.teardownUser(disabled.getUserName());
        TestUtils.endSession(true);
    }

    // --------------------------------------------------------------- lookup

    /**
     * By default the by-name lookup returns only enabled accounts -- it is what
     * sign-in uses, and a disabled account must not authenticate.
     */
    @Test
    void lookupByUserNameFindsAnEnabledAccount() throws Exception {
        assertNotNull(users().getUserByUserName(aliceName));
    }

    @Test
    void lookupByUserNameHidesADisabledAccountByDefault() throws Exception {
        assertNull(users().getUserByUserName(disabledName),
                "the default lookup is the one sign-in uses; a disabled account "
                        + "must not be found by it");
    }

    /**
     * Administration needs the other two states: the disabled account
     * explicitly, and "either" for a screen that lists both.
     */
    @Test
    void lookupByUserNameIsTriStateOnEnabled() throws Exception {
        assertNotNull(users().getUserByUserName(disabledName, Boolean.FALSE),
                "asking for a disabled account must find it");
        assertNotNull(users().getUserByUserName(disabledName, null),
                "asking for either must find it too");
        assertNull(users().getUserByUserName(disabledName, Boolean.TRUE),
                "and asking for enabled must not");
    }

    @Test
    void lookupOfAnAccountThatDoesNotExistReturnsNothing() throws Exception {
        assertNull(users().getUserByUserName("no-such-account"));
        assertNull(users().getUser("no-such-id"));
    }

    /**
     * The forgot-password identifier lookup. {@code TestUtils.setupUser}
     * gives every fixture the same email address, so this gives alice a
     * distinct one first -- otherwise the query could not tell her apart
     * from bob or the disabled account.
     */
    @Test
    void lookupByEmailFindsAnEnabledAccount() throws Exception {
        String email = aliceName + "@example.invalid";
        User managed = users().getUserByUserName(aliceName);
        managed.setEmailAddress(email);
        users().saveUser(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        User found = users().getUserByEmail(email);

        assertNotNull(found);
        assertEquals(aliceName, found.getUserName());
    }

    @Test
    void lookupByEmailHidesADisabledAccount() throws Exception {
        String email = disabledName + "@example.invalid";
        User managed = users().getUserByUserName(disabledName, Boolean.FALSE);
        managed.setEmailAddress(email);
        users().saveUser(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertNull(users().getUserByEmail(email),
                "the forgot-password lookup must not find a disabled account, "
                        + "the same as the by-username lookup it backstops");
    }

    @Test
    void lookupByEmailOfAnUnknownAddressReturnsNothing() throws Exception {
        assertNull(users().getUserByEmail("nobody@example.invalid"));
    }

    /**
     * {@code emailaddress} carries no unique constraint at the database level
     * (see V002 baseline schema), so two enabled accounts really can share an
     * address. The lookup must not throw {@code NonUniqueResultException} --
     * it caps to one row, ordered deterministically by username.
     */
    @Test
    void lookupByEmailToleratesTwoAccountsSharingAnAddress() throws Exception {
        String sharedEmail = "shared@example.invalid";
        User managedAlice = users().getUserByUserName(aliceName);
        managedAlice.setEmailAddress(sharedEmail);
        users().saveUser(managedAlice);
        User managedBob = users().getUserByUserName(bobName);
        managedBob.setEmailAddress(sharedEmail);
        users().saveUser(managedBob);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        User found = users().getUserByEmail(sharedEmail);

        assertNotNull(found, "a duplicate address must not make the lookup throw or find nothing");
        assertEquals(aliceName, found.getUserName(),
                "the deterministic order must return the same one every time, "
                        + "not whichever the database happens to return first");
    }

    // -------------------------------------------------------------- listing

    @Test
    void listingIsTriStateOnEnabled() throws Exception {
        List<String> enabled = namesOf(users().getUsers(Boolean.TRUE, null, null, 0, -1));
        assertTrue(enabled.contains(aliceName), "got: " + enabled);
        assertFalse(enabled.contains(disabledName), "got: " + enabled);

        List<String> onlyDisabled = namesOf(users().getUsers(Boolean.FALSE, null, null, 0, -1));
        assertTrue(onlyDisabled.contains(disabledName), "got: " + onlyDisabled);
        assertFalse(onlyDisabled.contains(aliceName), "got: " + onlyDisabled);

        List<String> either = namesOf(users().getUsers(null, null, null, 0, -1));
        assertTrue(either.containsAll(List.of(aliceName, disabledName)),
                "the admin listing shows both, or a disabled account can never be "
                        + "re-enabled: " + either);
    }

    @Test
    void listingCanBeBoundedByDate() throws Exception {
        Date future = Date.from(Instant.now().plus(1, ChronoUnit.DAYS));
        Date past = Date.from(Instant.now().minus(1, ChronoUnit.DAYS));

        List<String> withinWindow = namesOf(users().getUsers(null, past, future, 0, -1));
        assertTrue(withinWindow.contains(aliceName), "got: " + withinWindow);

        List<String> beforeEveryone = namesOf(
                users().getUsers(null, null, Date.from(Instant.now().minus(365, ChronoUnit.DAYS)),
                        0, -1));
        assertFalse(beforeEveryone.contains(aliceName),
                "an end date before the account existed must exclude it: " + beforeEveryone);
    }

    /**
     * No end date means no end date, as {@code UserManager.getUsers} documents
     * ("or null for all"). The implementation used to substitute "now" for a
     * null end date and compare strictly ({@code dateCreated < now}), so an
     * account stamped in the same millisecond as the listing was missing from
     * it. That is what made {@link #listingIsTriStateOnEnabled} and
     * {@link #listingIsPageable} flaky: warm, {@link #setUp} runs in about a
     * millisecond, so the newest fixture account often shared the query's
     * millisecond -- invisible to one query, visible to the next, which also
     * shifted the page window. A {@code dateCreated} just ahead of the clock
     * stands on that boundary deterministically.
     */
    @Test
    void aListingWithNoEndDateIncludesAnAccountStampedAtOrAfterNow() throws Exception {
        Date justAhead = Date.from(Instant.now().plus(1, ChronoUnit.MINUTES));
        for (User account : List.of(users().getUserByUserName(aliceName),
                users().getUserByUserName(disabledName, Boolean.FALSE))) {
            account.setDateCreated(justAhead);
            users().saveUser(account);
        }
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        Date past = Date.from(Instant.now().minus(1, ChronoUnit.DAYS));
        assertTrue(namesOf(users().getUsers(Boolean.TRUE, null, null, 0, -1)).contains(aliceName),
                "enabled, no dates");
        assertTrue(namesOf(users().getUsers(Boolean.FALSE, null, null, 0, -1)).contains(disabledName),
                "disabled, no dates");
        assertTrue(namesOf(users().getUsers(null, null, null, 0, -1))
                        .containsAll(List.of(aliceName, disabledName)), "either, no dates");
        assertTrue(namesOf(users().getUsers(Boolean.TRUE, past, null, 0, -1)).contains(aliceName),
                "enabled, start date only");
        assertTrue(namesOf(users().getUsers(null, past, null, 0, -1)).contains(disabledName),
                "either, start date only");
    }

    @Test
    void listingIsPageable() throws Exception {
        List<User> firstPage = users().getUsers(null, null, null, 0, 1);
        assertEquals(1, firstPage.size(), "maxResults bounds the page");

        List<User> secondPage = users().getUsers(null, null, null, 1, 1);
        assertEquals(1, secondPage.size());
        assertFalse(namesOf(firstPage).equals(namesOf(secondPage)),
                "an offset must move the window, not repeat it");
    }

    /**
     * {@link #listingIsPageable} above is correct but flaky -- it depends on
     * the ambient user population and can pass by luck. This walks a whole
     * page of colliding-timestamp fixture users one row at a time and proves
     * every offset returns a row no earlier offset already returned.
     *
     * <p>This deliberately calls {@code getUsersStartingWith(null, null, ...)},
     * not {@code getUsers(...)} -- {@code getUsers} builds a query that already
     * carries {@code ORDER BY u.dateCreated DESC, u.id}. {@code getUsersStartingWith}
     * is what falls through to {@code User.getAll} when both {@code startsWith}
     * and {@code enabled} are null (see {@code JPAUserManagerImpl}), and
     * {@code User.getAll} is the one named query in {@code User.orm.xml} with
     * no {@code ORDER BY} at all. This is also a real, reachable path: it is
     * what {@code UserDataServlet} (the collaborator picker) calls when
     * loaded with no filter typed in yet.
     *
     * <p>With no {@code ORDER BY}, two same-{@code dateCreated} rows have no
     * defined relative order and a {@code LIMIT 1 OFFSET n} walk can hand
     * back the same row twice while never handing back another at all.
     */
    @Test
    void listingWalksEveryUserExactlyOnceAcrossPages() throws Exception {
        List<User> extra = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                extra.add(TestUtils.setupUser("uqPage" + i));
            }
            TestUtils.endSession(true);

            int total = 3 + extra.size();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int offset = 0; offset < total; offset++) {
                List<User> page = users().getUsersStartingWith(null, null, offset, 1);
                assertEquals(1, page.size(), "each page must return exactly one row at offset " + offset);
                String name = page.get(0).getUserName();
                assertTrue(seen.add(name),
                        "offset " + offset + " returned a user already seen at an earlier "
                                + "offset -- the walk repeated a row instead of moving the "
                                + "window: " + name);
            }
        } finally {
            for (User u : extra) {
                TestUtils.teardownUser(u.getUserName());
            }
            TestUtils.endSession(true);
        }
    }

    /**
     * A source-level proof for {@code User.getAll}, because the runtime walk
     * above could not demonstrate this defect: it did not reproduce a repeat
     * or a skip at the scale one test can afford to create, for the same
     * reason recorded on {@link #dateCreatedOrderedUserQueriesCarryTheIdTiebreak}
     * -- a moderate fixture gets a stable page order from Postgres "by luck"
     * (an unchanged table is scanned in stable physical order within one
     * short-lived test process), and the sibling defect that test pins only
     * surfaced under the full suite's accumulated churn of creates and
     * deletes. Asserting the query text directly is the deterministic pin,
     * exactly as that test already does for its four siblings; this is the
     * fifth dateCreated-ordered query, the one that was missing entirely
     * rather than missing only its id tiebreak.
     */
    @Test
    void getAllCarriesTheSameOrderingAsItsDateCreatedOrderedSiblings() throws Exception {
        String orm = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/org/apache/roller/weblogger/pojos/User.orm.xml"));
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("name=\"User\\.getAll\">\\s*<query>([^<]*)</query>").matcher(orm);
        assertTrue(m.find(), "User.getAll named query not found in User.orm.xml");
        assertTrue(m.group(1).contains("ORDER BY u.dateCreated DESC, u.id"),
                "User.getAll has no ORDER BY at all, unlike every sibling query in "
                        + "this file -- two LIMIT 1 OFFSET n calls against it can return "
                        + "the same row twice, or skip one entirely, because SQL makes no "
                        + "ordering guarantee without ORDER BY. Query text: "
                        + m.group(1).trim());
    }

    /**
     * Pins the id tiebreak on every dateCreated-ordered user query at the
     * SOURCE level. A runtime version of this pin (forced same-timestamp
     * users walked one page at a time) was tried first and passed with the
     * tiebreak reverted -- a small fixture gets a stable order from the
     * database by luck, so the runtime shape cannot discriminate. The
     * original defect only surfaced under the full suite's accumulated
     * users: ORDER BY dateCreated alone leaves same-timestamp rows in
     * undefined order, and adjacent one-row pages repeated a row roughly
     * every other run. Asserting the query text is the deterministic pin.
     *
     * <p>{@code User.getAll} joined this count once it was given the same
     * ordering as its siblings (it previously had no {@code ORDER BY} at
     * all, and so did not match this regex and was not counted here --
     * {@link #getAllCarriesTheSameOrderingAsItsDateCreatedOrderedSiblings}
     * is what pinned that gap).
     *
     * <p>The four date-bounded named queries behind {@code getUsers} were
     * replaced by one query that {@code JPAUserManagerImpl} builds (see
     * {@link #aListingWithNoEndDateIncludesAnAccountStampedAtOrAfterNow}),
     * so its ORDER BY is pinned in the Java source alongside {@code getAll}'s.
     */
    @Test
    void dateCreatedOrderedUserQueriesCarryTheIdTiebreak() throws Exception {
        String orm = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/org/apache/roller/weblogger/pojos/User.orm.xml"));
        String impl = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/org/apache/roller/weblogger/business/jpa/JPAUserManagerImpl.java"));
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("ORDER BY u\\.dateCreated DESC([^<\"]*)").matcher(orm + impl);
        int found = 0;
        while (m.find()) {
            found++;
            assertTrue(m.group(1).contains(", u.id"),
                    "every dateCreated-ordered user query needs the id "
                            + "tiebreak -- without it, same-timestamp rows "
                            + "have no defined order and pagination can "
                            + "repeat or skip a row. Offender: ORDER BY "
                            + "u.dateCreated DESC" + m.group(1));
        }
        assertEquals(2, found,
                "the two dateCreated-ordered queries (User.getAll, getUsers) moved "
                        + "or changed count -- update this pin alongside them");
    }

    // ------------------------------------------------------ prefix searching

    @Test
    void searchingByPrefixMatchesUserNames() throws Exception {
        List<String> found = namesOf(users().getUsersStartingWith(aliceName, null, 0, 10));

        assertTrue(found.contains(aliceName), "got: " + found);
        assertFalse(found.contains(bobName), "got: " + found);
    }

    /**
     * A null prefix means "everyone", which is what the picker asks for before
     * anything has been typed.
     */
    @Test
    void searchingWithNoPrefixReturnsEveryone() throws Exception {
        List<String> found = namesOf(users().getUsersStartingWith(null, null, 0, 50));

        assertTrue(found.containsAll(List.of(aliceName, bobName)), "got: " + found);
    }

    @Test
    void searchingByPrefixHonoursTheEnabledFilter() throws Exception {
        List<String> enabledOnly = namesOf(users().getUsersStartingWith(prefix(), Boolean.TRUE, 0, 50));
        assertFalse(enabledOnly.contains(disabledName), "got: " + enabledOnly);

        List<String> either = namesOf(users().getUsersStartingWith(prefix(), null, 0, 50));
        assertTrue(either.contains(disabledName), "got: " + either);
    }

    // -------------------------------------------------------- letter indexes

    @Test
    void theLetterMapCountsUsersUnderTheirInitial() throws Exception {
        Map<String, Long> letters = users().getUserNameLetterMap();

        assertEquals(26, letters.size(), "one entry per letter of the alphabet");
        String initial = aliceName.substring(0, 1).toUpperCase();
        assertTrue(letters.get(initial) >= 3,
                "the three fixture accounts must be counted under " + initial
                        + ": " + letters.get(initial));
    }

    @Test
    void usersCanBeFetchedByInitialLetter() throws Exception {
        List<String> found = namesOf(
                users().getUsersByLetter(Character.toUpperCase(aliceName.charAt(0)), 0, 50));

        assertTrue(found.containsAll(List.of(aliceName, bobName)), "got: " + found);
    }

    /**
     * The count is of <em>enabled</em> accounts -- its named query filters on
     * it -- which is what the admin dashboard means by "users".
     */
    @Test
    void theUserCountIsOfEnabledAccounts() throws Exception {
        long before = users().getUserCount();

        User managed = users().getUserByUserName(disabledName, Boolean.FALSE);
        managed.setEnabled(Boolean.TRUE);
        users().saveUser(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertEquals(before + 1, users().getUserCount(),
                "re-enabling an account must add it to the count");
    }

    // ----------------------------------------------------------------- roles

    @Test
    void aGrantedRoleIsReadBack() throws Exception {
        User managed = users().getUserByUserName(aliceName);
        users().grantRole("admin", managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        managed = users().getUserByUserName(aliceName);
        assertTrue(users().hasRole("admin", managed));
        assertTrue(users().getRoles(managed).contains("admin"), "got: " + users().getRoles(managed));
    }

    @Test
    void aRevokedRoleIsGone() throws Exception {
        User managed = users().getUserByUserName(aliceName);
        users().grantRole("editor", managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        managed = users().getUserByUserName(aliceName);
        users().revokeRole("editor", managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        managed = users().getUserByUserName(aliceName);
        assertFalse(users().hasRole("editor", managed),
                "a revoked role must not survive, or an account keeps authority "
                        + "an administrator believed they removed");
    }

    /**
     * Roles are per user. Granting one to somebody must not grant it to
     * everybody, which a query missing its username predicate would do.
     */
    @Test
    void aRoleGrantedToOneUserIsNotHeldByAnother() throws Exception {
        User managedAlice = users().getUserByUserName(aliceName);
        users().grantRole("admin", managedAlice);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        User managedBob = users().getUserByUserName(bobName);
        assertFalse(users().hasRole("admin", managedBob),
                "bob was granted nothing and must hold nothing");
    }

    @Test
    void aRoleThatWasNeverGrantedIsNotHeld() throws Exception {
        assertFalse(users().hasRole("admin", users().getUserByUserName(bobName)));
    }

    /**
     * Roles are keyed by username with no foreign key to the account, so they
     * survive the account unless removal deletes them. A later account given
     * the same name (a reader re-signing up, an administrator reusing a name)
     * would otherwise inherit the deleted account's authority.
     */
    @Test
    void removingAUserRemovesItsRoles() throws Exception {
        User leaving = TestUtils.setupUser("uqLeaving");
        users().grantRole("admin", leaving);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        TestUtils.teardownUser(leaving.getUserName());
        TestUtils.endSession(true);

        User sameName = new User();
        sameName.setUserName(leaving.getUserName());
        assertEquals(List.of(), users().getRoles(sameName),
                "a removed account's roles must go with it");
    }

    // ------------------------------------------------------- weblog members

    /**
     * The account page refuses a display name that impersonates a weblog
     * member, so it needs every member's screen name, from one query: an
     * account holding any weblog permission is a member; one holding none is
     * not, whatever its roles.
     */
    @Test
    void screenNamesOfWeblogMembersAreThoseOfAccountsHoldingAWeblogPermission() throws Exception {
        User member = users().getUserByUserName(aliceName);
        member.setScreenName("Alice Member " + aliceName);
        users().saveUser(member);
        User outsider = users().getUserByUserName(bobName);
        outsider.setScreenName("Bob Outsider " + bobName);
        users().saveUser(outsider);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        Weblog blog = TestUtils.setupWeblog("uqmembers", users().getUserByUserName(aliceName));
        TestUtils.endSession(true);
        try {
            List<String> names = users().getScreenNamesOfWeblogMembers();

            assertTrue(names.contains("Alice Member " + aliceName), "the weblog's creator is a member: " + names);
            assertFalse(names.contains("Bob Outsider " + bobName), "bob holds no weblog permission: " + names);
            assertEquals(names.size(), names.stream().distinct().count(), "each name once: " + names);
        } finally {
            TestUtils.teardownWeblog(blog.getId());
            TestUtils.endSession(true);
        }
    }

    // --------------------------------------------------------------- helpers

    private static UserManager users() {
        return TestUtils.weblogger().getUserManager();
    }

    /** The shared prefix of this test's fixture accounts. */
    private String prefix() {
        return aliceName.substring(0, aliceName.length() - "Alice".length());
    }

    private static List<String> namesOf(List<User> users) {
        return users.stream().map(User::getUserName).toList();
    }
}
