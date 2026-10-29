package com.wikantik.pressroom.ui.core.security.sso;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.util.HTMLSanitizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DB-backed: the sign-in decision table of {@link ReaderAccounts}. */
class ReaderAccountsTest {

    private static final String ISSUER = "https://issuer.test";
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final List<String> created = new ArrayList<>();
    private final List<String> weblogIds = new ArrayList<>();
    private String sub;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        sub = "sub-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.endSession(false);
        for (String id : weblogIds) {
            TestUtils.teardownWeblog(id);
        }
        for (String name : created) {
            TestUtils.teardownUser(name);
        }
        TestUtils.endSession(true);
    }

    private ReaderClaims claims(String email, String name) {
        return new ReaderClaims(ISSUER, sub, email, true, name);
    }

    private ReaderAccounts.SignIn signIn(ReaderAccounts accounts, ReaderClaims c) throws Exception {
        ReaderAccounts.SignIn s = accounts.signIn(c);
        if (s.created()) {
            created.add(s.user().getUserName());
        }
        return s;
    }

    @Test
    void firstSignInCreatesAReaderAndTheSecondReusesIt() throws Exception {
        ReaderAccounts accounts = new ReaderAccounts(TestUtils.weblogger());
        ReaderClaims c = claims("ada@example.com", "Ada Lovelace");
        ReaderAccounts.SignIn first = signIn(accounts, c);
        TestUtils.endSession(true);
        assertTrue(first.created());
        assertTrue(first.user().getUserName().matches("reader-[a-z0-9]{10}"));
        assertEquals("Ada Lovelace", first.user().getScreenName());
        assertEquals("Ada Lovelace", first.user().getFullName());
        assertEquals(User.SSO_PASSWORD_MARKER, first.user().getPassword());
        assertEquals(List.of("reader"), first.roles());
        long before = TestUtils.weblogger().getUserManager().getUserCount();
        ReaderAccounts.SignIn second = signIn(accounts, c);
        assertFalse(second.created());
        assertEquals(first.user().getUserName(), second.user().getUserName());
        assertEquals(before, TestUtils.weblogger().getUserManager().getUserCount());
    }

    @Test
    void aLocalAccountWithTheSameEmailIsNotAdopted() throws Exception {
        String email = "shared-" + UUID.randomUUID() + "@example.com";
        UserManager mgr = TestUtils.weblogger().getUserManager();
        User local = TestUtils.setupUser("local" + System.nanoTime());
        created.add(local.getUserName());
        local.setEmailAddress(email);
        mgr.saveUser(local);
        TestUtils.endSession(true);
        String passwordBefore = mgr.getUserByUserName(local.getUserName()).getPassword();
        List<String> rolesBefore = mgr.getRoles(mgr.getUserByUserName(local.getUserName()));

        ReaderAccounts.SignIn s = signIn(new ReaderAccounts(TestUtils.weblogger()), claims(email, "Local"));
        TestUtils.endSession(true);

        assertTrue(s.created());
        assertFalse(local.getUserName().equals(s.user().getUserName()));
        User after = mgr.getUserByUserName(local.getUserName());
        assertEquals(passwordBefore, after.getPassword());
        assertEquals(rolesBefore, mgr.getRoles(after));
        assertFalse(after.isSsoBound());
    }

    @Test
    void aDisabledBoundAccountIsRefusedAndNotReplaced() throws Exception {
        ReaderAccounts accounts = new ReaderAccounts(TestUtils.weblogger());
        ReaderClaims c = claims("d@example.com", "D");
        ReaderAccounts.SignIn first = signIn(accounts, c);
        TestUtils.endSession(true);
        UserManager mgr = TestUtils.weblogger().getUserManager();
        User u = mgr.getUserBySsoIdentity(ISSUER, sub);
        u.setEnabled(Boolean.FALSE);
        mgr.saveUser(u);
        TestUtils.endSession(true);
        long before = mgr.getUserCount();

        assertThrows(ReaderSignInRefused.class, () -> accounts.signIn(c));
        TestUtils.endSession(false);
        assertEquals(before, mgr.getUserCount());
        assertEquals(first.user().getUserName(), mgr.getUserBySsoIdentity(ISSUER, sub).getUserName());
    }

    /** Spec 2026-10-13, section 2: SSO never signs in as an agent. */
    @Test
    void aBoundAccountThatIsAnAgentIsRefused() throws Exception {
        ReaderAccounts accounts = new ReaderAccounts(TestUtils.weblogger());
        ReaderClaims c = claims("agent-sso@example.com", "A");
        signIn(accounts, c);
        TestUtils.endSession(true);
        UserManager mgr = TestUtils.weblogger().getUserManager();
        User bound = mgr.getUserBySsoIdentity(ISSUER, sub);
        bound.setKind(User.Kind.AGENT);
        mgr.saveUser(bound);
        TestUtils.endSession(true);

        assertThrows(ReaderSignInRefused.class, () -> accounts.signIn(c));
    }

    @Test
    void laterSignInRefreshesEmailButKeepsAnEditedScreenName() throws Exception {
        ReaderAccounts accounts = new ReaderAccounts(TestUtils.weblogger());
        signIn(accounts, claims("old@example.com", "Orig"));
        TestUtils.endSession(true);
        UserManager mgr = TestUtils.weblogger().getUserManager();
        User u = mgr.getUserBySsoIdentity(ISSUER, sub);
        u.setScreenName("Edited");
        mgr.saveUser(u);
        TestUtils.endSession(true);

        signIn(accounts, claims("new@example.com", "Orig"));
        TestUtils.endSession(true);

        User after = mgr.getUserBySsoIdentity(ISSUER, sub);
        assertEquals("new@example.com", after.getEmailAddress());
        assertEquals("Edited", after.getScreenName());
    }

    @Test
    void aUsernameCollisionIsRedrawn() throws Exception {
        User existing = bound("reader-aaaaaaaaaa", "other-" + UUID.randomUUID());
        UserManager mgr = TestUtils.weblogger().getUserManager();
        mgr.addReader(existing);
        created.add(existing.getUserName());
        TestUtils.endSession(true);

        RandomGenerator fixed = sequence("aaaaaaaaaa", "bbbbbbbbbb");
        ReaderAccounts.SignIn s = signIn(new ReaderAccounts(TestUtils.weblogger(), fixed),
                claims("x@example.com", "X"));
        assertEquals("reader-bbbbbbbbbb", s.user().getUserName());
    }

    @Test
    void aConcurrentFirstSignInEndsWithOneAccount() throws Exception {
        ReaderAccounts.SignIn winner = signIn(new ReaderAccounts(TestUtils.weblogger()),
                claims("w@example.com", "W"));
        TestUtils.endSession(true);

        UserManager realUsers = TestUtils.weblogger().getUserManager();
        UserManager users = Mockito.spy(realUsers);
        Mockito.doReturn(null).doCallRealMethod().when(users).getUserBySsoIdentity(ISSUER, sub);
        Weblogger spied = Mockito.spy(TestUtils.weblogger());
        Mockito.doReturn(users).when(spied).getUserManager();

        ReaderAccounts.SignIn loser = new ReaderAccounts(spied).signIn(claims("w@example.com", "W"));
        assertEquals(winner.user().getUserName(), loser.user().getUserName());
        assertFalse(loser.created());
    }

    // ------------------------------------------------------------- the display name (ruling R8)

    /**
     * The account page's rule applies at first sign-in too: a provider's name
     * claim that is a weblog member's screen name would let a reader pass as
     * the author from their very first comment.
     */
    @Test
    void aClaimNameThatIsAMembersNameFallsBackToTheEmailLocalPart() throws Exception {
        String ownerName = member("Owner " + sub.substring(4, 12));

        ReaderAccounts.SignIn s = signIn(new ReaderAccounts(TestUtils.weblogger()),
                claims("ann.reader@example.com", ownerName.toUpperCase(java.util.Locale.ROOT)));

        assertEquals("ann.reader", s.user().getScreenName());
        assertEquals("ann.reader", s.user().getFullName());
    }

    @Test
    void whenTheEmailLocalPartIsTakenTooTheReaderIsCalledReader() throws Exception {
        String local = "taken" + sub.substring(4, 12);
        member(local);

        ReaderAccounts.SignIn s = signIn(new ReaderAccounts(TestUtils.weblogger()),
                claims(local + "@example.com", local));

        assertEquals("Reader", s.user().getScreenName());
    }

    @Test
    void whenEvenReaderIsTakenTheGeneratedUserNameIsTheName() throws Exception {
        String local = "taken" + sub.substring(4, 12);
        member(local);
        member("Reader");

        ReaderAccounts.SignIn s = signIn(new ReaderAccounts(TestUtils.weblogger()),
                claims(local + "@example.com", local));

        assertEquals(s.user().getUserName(), s.user().getScreenName());
    }

    /** The review's input, at sign-up: stored, this claim is the owner's exact name. */
    @Test
    void aClaimNameIsJudgedAsItWouldBeStored() throws Exception {
        String ownerName = member("Owner " + sub.substring(4, 12));
        Boolean sanitizing = HTMLSanitizer.xssEnabled;
        ReaderAccounts.SignIn s;
        try {
            HTMLSanitizer.xssEnabled = Boolean.TRUE;
            s = signIn(new ReaderAccounts(TestUtils.weblogger()),
                    claims("ann.reader@example.com", "<i>" + ownerName + "&lt;b&gt;&lt;/b&gt;</i>"));
        } finally {
            HTMLSanitizer.xssEnabled = sanitizing;
        }

        assertEquals("ann.reader", s.user().getScreenName());
    }

    @Test
    void aClaimNameThatStoresEmptyFallsBack() throws Exception {
        Boolean sanitizing = HTMLSanitizer.xssEnabled;
        ReaderAccounts.SignIn s;
        try {
            HTMLSanitizer.xssEnabled = Boolean.TRUE;
            s = signIn(new ReaderAccounts(TestUtils.weblogger()),
                    claims("ann.reader@example.com", "<i>&lt;b&gt;&lt;/b&gt;</i>"));
        } finally {
            HTMLSanitizer.xssEnabled = sanitizing;
        }

        assertEquals("ann.reader", s.user().getScreenName());
    }

    @Test
    void aLongClaimNameIsCutToFiftyCodePoints() throws Exception {
        String sixty = "Ann " + "x".repeat(56);

        ReaderAccounts.SignIn s = signIn(new ReaderAccounts(TestUtils.weblogger()),
                claims("ann.reader@example.com", sixty));

        assertEquals(sixty.substring(0, 50), s.user().getScreenName());
    }

    @Test
    void refusedClaimsCreateNothing() throws Exception {
        long before = TestUtils.weblogger().getUserManager().getUserCount();
        ReaderAccounts accounts = new ReaderAccounts(TestUtils.weblogger());
        assertThrows(ReaderSignInRefused.class,
                () -> accounts.signIn(new ReaderClaims(ISSUER, sub, "u@example.com", false, "U")));
        assertEquals(before, TestUtils.weblogger().getUserManager().getUserCount());
    }

    /** A password account holding a weblog, renamed to {@code screenName}; returns the name. */
    private String member(String screenName) throws Exception {
        User owner = TestUtils.setupUser("ram" + UUID.randomUUID().toString().substring(0, 8));
        created.add(owner.getUserName());
        owner.setScreenName(screenName);
        TestUtils.weblogger().getUserManager().saveUser(owner);
        TestUtils.endSession(true);
        Weblog blog = TestUtils.setupWeblog("ram" + UUID.randomUUID().toString().substring(0, 8),
                TestUtils.getManagedUser(owner));
        weblogIds.add(blog.getId());
        TestUtils.endSession(true);
        return screenName;
    }

    private static User bound(String userName, String subject) {
        User u = new User();
        u.setUserName(userName);
        u.setScreenName("Reader");
        u.setFullName("Reader");
        u.setEmailAddress(userName + "@example.com");
        u.setPassword(User.SSO_PASSWORD_MARKER);
        u.setDateCreated(new Date());
        u.setEnabled(Boolean.TRUE);
        u.setSsoIssuer(ISSUER);
        u.setSsoSubject(subject);
        return u;
    }

    /** A generator whose nextInt(36) walks the alphabet indices of the given strings. */
    private static RandomGenerator sequence(String... draws) {
        List<Integer> indices = new ArrayList<>();
        for (String draw : draws) {
            draw.chars().forEach(ch -> indices.add(ALPHABET.indexOf(ch)));
        }
        return new RandomGenerator() {
            private int next;

            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int nextInt(int bound) {
                return indices.get(next++);
            }
        };
    }
}
