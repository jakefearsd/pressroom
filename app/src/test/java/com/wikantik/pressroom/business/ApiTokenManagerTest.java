package com.wikantik.pressroom.business;

import java.sql.Timestamp;
import java.util.List;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiTokenManagerTest {

    private User user;
    private ApiTokenManager mgr;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("apitokentestuser");
        TestUtils.endSession(true);
        mgr = TestUtils.weblogger().getApiTokenManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void anIssuedTokenAuthenticatesAndCarriesItsScope() throws Exception {
        ApiTokenManager.Issued issued = mgr.issueToken(user, "seo-agent", "testblog", ApiToken.Role.POST, null);
        TestUtils.endSession(true);

        String raw = issued.rawToken();
        assertTrue(raw.startsWith("rlr_"), "tokens are prefixed so they are recognisable in logs");
        assertEquals("testblog", issued.token().getScopeWeblog(),
                "the returned row is the same one that was persisted");

        ApiToken found = mgr.authenticate(raw);
        assertNotNull(found);
        assertEquals("testblog", found.getScopeWeblog());
        assertEquals(ApiToken.Role.POST, found.getScopeRole());
    }

    /** A database read must never yield a working credential. */
    @Test
    void theRawTokenIsNeverStored() throws Exception {
        String raw = mgr.issueToken(user, "label", null, ApiToken.Role.READ, null).rawToken();
        TestUtils.endSession(true);

        ApiToken stored = mgr.authenticate(raw);
        assertNotNull(stored);
        assertNotEquals(raw, stored.getTokenSha256());
        assertEquals(64, stored.getTokenSha256().length(), "SHA-256 hex is 64 chars");
    }

    @Test
    void anExpiredTokenDoesNotAuthenticate() throws Exception {
        Timestamp past = new Timestamp(System.currentTimeMillis() - 1000L);
        String raw = mgr.issueToken(user, "expired", null, ApiToken.Role.READ, past).rawToken();
        TestUtils.endSession(true);

        assertNull(mgr.authenticate(raw));
    }

    @Test
    void aRevokedTokenDoesNotAuthenticate() throws Exception {
        String raw = mgr.issueToken(user, "doomed", null, ApiToken.Role.ADMIN, null).rawToken();
        TestUtils.endSession(true);

        ApiToken issued = mgr.authenticate(raw);
        assertNotNull(issued);
        assertTrue(mgr.revoke(user, issued.getId()));
        TestUtils.endSession(true);

        assertNull(mgr.authenticate(raw));
    }

    /** Revocation is scoped to the owner: one user must not revoke another's. */
    @Test
    void revokeRefusesATokenOwnedBySomeoneElse() throws Exception {
        User other = TestUtils.setupUser("apitokenotheruser");
        TestUtils.endSession(true);
        String raw = mgr.issueToken(user, "mine", null, ApiToken.Role.READ, null).rawToken();
        TestUtils.endSession(true);
        ApiToken mine = mgr.authenticate(raw);
        assertNotNull(mine);

        assertFalse(mgr.revoke(other, mine.getId()));
        TestUtils.endSession(true);
        assertNotNull(mgr.authenticate(raw), "the token must still work");

        TestUtils.teardownUser(other.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void anUnknownTokenDoesNotAuthenticate() throws Exception {
        assertNull(mgr.authenticate("rlr_notarealtoken"));
        assertNull(mgr.authenticate(null));
        assertNull(mgr.authenticate("   "));
    }

    /**
     * lastUsedAt is read by an operator deciding whether a token is still in
     * daily use before revoking it -- it must be durable the moment
     * authenticate() stamps it, not depend on whatever request happened to
     * carry it also calling flush(). A read-only endpoint (GET /api/v1/me)
     * never flushes anything of its own, so before this test, a token used
     * only for reads kept lastUsedAt null forever: the write happened in a
     * transaction nobody ever committed, and {@code PersistenceSessionFilter}
     * / {@code TestUtils.endSession(false)}'s end-of-request release() rolls
     * back whatever is still open.
     *
     * <p>Simulates exactly that: authenticate() (which stamps lastUsedAt),
     * then release() with NO flush -- then re-reads the token in a FRESH
     * session (release() clears the thread-local EntityManager, so the next
     * call opens a new one) to prove the stamp is durable in the database,
     * not just visible in the persistence context that wrote it.
     */
    @Test
    void authenticatingATokenMakesLastUsedAtDurableEvenWithoutTheCallerFlushing() throws Exception {
        String raw = mgr.issueToken(user, "watched", null, ApiToken.Role.READ, null).rawToken();
        TestUtils.endSession(true);

        ApiToken found = mgr.authenticate(raw);
        assertNotNull(found);
        assertNotNull(found.getLastUsedAt(), "authenticate() should have stamped lastUsedAt");

        // No flush: this is what a read-only request actually does.
        TestUtils.endSession(false);

        ApiToken reloaded = mgr.getTokens(user).stream()
                .filter(t -> t.getId().equals(found.getId()))
                .findFirst().orElseThrow();
        assertNotNull(reloaded.getLastUsedAt(),
                "lastUsedAt must survive a release() with no flush, or an operator reading it "
                        + "would see null forever for a token used only for reads");
    }

    /**
     * A missing or blank id is client input, same as an unknown one -- it
     * must fail closed (false), not throw. EntityManager.find (behind
     * strategy.load) throws IllegalArgumentException on a null primary key,
     * which revoke must not let escape.
     */
    @Test
    void revokeRefusesAMissingOrBlankTokenId() throws Exception {
        assertFalse(mgr.revoke(user, null));
        assertFalse(mgr.revoke(user, "   "));
    }

    /** Spec 2026-10-13, section 3: a mint records who issued it. */
    @Test
    void aTokenRecordsWhoIssuedIt() throws Exception {
        User admin = TestUtils.setupUser("apitokenissuer" + System.nanoTime());
        TestUtils.endSession(true);
        try {
            ApiTokenManager.Issued issued = mgr.issueToken(user, "minted", null, ApiToken.Role.POST, null, admin);
            TestUtils.endSession(true);

            assertEquals(admin.getId(), mgr.getToken(issued.token().getId()).getIssuedBy());
        } finally {
            TestUtils.teardownUser(admin.getUserName());
            TestUtils.endSession(true);
        }
    }

    @Test
    void aSelfMintRecordsTheOwnerAsTheIssuer() throws Exception {
        ApiTokenManager.Issued issued = mgr.issueToken(user, "self", null, ApiToken.Role.READ, null);
        TestUtils.endSession(true);

        assertEquals(user.getId(), mgr.getToken(issued.token().getId()).getIssuedBy());
    }

    @Test
    void getTokenFindsATokenByIdAndNothingForABlankOrUnknownId() throws Exception {
        String id = mgr.issueToken(user, "findme", null, ApiToken.Role.READ, null).token().getId();
        TestUtils.endSession(true);

        assertEquals("findme", mgr.getToken(id).getLabel());
        assertNull(mgr.getToken("no-such-token"));
        assertNull(mgr.getToken("   "));
        assertNull(mgr.getToken(null));
    }

    /** Criterion 14: revoking twice succeeds and changes nothing. */
    @Test
    void revokingTwiceKeepsTheFirstRevocation() throws Exception {
        String raw = mgr.issueToken(user, "twice", null, ApiToken.Role.READ, null).rawToken();
        TestUtils.endSession(true);
        String id = mgr.authenticate(raw).getId();
        TestUtils.endSession(true);

        mgr.revoke(mgr.getToken(id));
        TestUtils.endSession(true);
        java.sql.Timestamp first = mgr.getToken(id).getRevokedAt();
        assertNotNull(first);

        mgr.revoke(mgr.getToken(id));
        TestUtils.endSession(true);

        assertEquals(first, mgr.getToken(id).getRevokedAt());
        assertNull(mgr.authenticate(raw), "a revoked token authenticates no one");
    }

    @Test
    void liveTokensLeaveOutRevokedAndExpiredOnes() throws Exception {
        String live = mgr.issueToken(user, "live", null, ApiToken.Role.READ, null).token().getId();
        String expired = mgr.issueToken(user, "expired", null, ApiToken.Role.READ,
                new Timestamp(System.currentTimeMillis() - 60_000L)).token().getId();
        String revoked = mgr.issueToken(user, "revoked", null, ApiToken.Role.READ, null).token().getId();
        TestUtils.endSession(true);
        mgr.revoke(mgr.getToken(revoked));
        TestUtils.endSession(true);

        List<String> ids = mgr.getLiveTokens(user).stream().map(ApiToken::getId).toList();

        assertEquals(List.of(live), ids);
        assertTrue(mgr.getTokens(user).stream().map(ApiToken::getId).toList().containsAll(
                List.of(live, expired, revoked)), "getTokens still lists every token");
    }

    /** Criterion 13: the site list shows live tokens; the toggle adds revoked and expired ones. */
    @Test
    void theSiteListAddsInactiveTokensOnlyWhenAsked() throws Exception {
        String live = mgr.issueToken(user, "site-live", null, ApiToken.Role.READ, null).token().getId();
        String revoked = mgr.issueToken(user, "site-revoked", null, ApiToken.Role.READ, null).token().getId();
        TestUtils.endSession(true);
        mgr.revoke(mgr.getToken(revoked));
        TestUtils.endSession(true);

        List<String> liveOnly = mgr.getAllTokens(false).stream().map(ApiToken::getId).toList();
        List<String> everything = mgr.getAllTokens(true).stream().map(ApiToken::getId).toList();

        assertTrue(liveOnly.contains(live));
        assertFalse(liveOnly.contains(revoked));
        assertTrue(everything.containsAll(List.of(live, revoked)));
    }
}
