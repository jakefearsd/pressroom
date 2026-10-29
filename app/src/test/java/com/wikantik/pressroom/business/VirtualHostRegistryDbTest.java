package com.wikantik.pressroom.business;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code VirtualHostRegistry} against a live database. Unlike
 * {@code WeblogRequestMapperTest}, which calls {@code invalidate()} by hand
 * after every domain change, these tests never do -- the point is to prove
 * that {@code JPAWeblogManagerImpl.saveWeblog}/{@code removeWeblog}
 * themselves keep the registry in sync. A test that calls
 * {@code invalidate()} manually proves nothing about whether those call
 * sites exist or are correctly placed.
 */
class VirtualHostRegistryDbTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("vhostdbuser");
        weblog = TestUtils.setupWeblog("vhostdbblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
        // The tier's VirtualHostRegistry is a JVM-wide cache -- a custom domain
        // set by one test must not leak into the next.
        registry().invalidate();
    }

    /** The bootstrapped tier's registry -- the instance the managers invalidate. */
    private static VirtualHostRegistry registry() {
        return TestUtils.weblogger().getVirtualHostRegistry();
    }

    /**
     * This is the test that would have failed against the JPQL bug fixed in
     * 8d019a23c: {@code map()}'s {@code getWeblogs(null, null, null, null, 0,
     * -1)} call threw, was swallowed, and every custom domain silently never
     * resolved. No manual {@code invalidate()} between the save and the
     * lookup -- {@code saveWeblog} must trigger the rebuild itself.
     */
    @Test
    void savingACustomDomainMakesItResolveWithNoManualInvalidate() throws Exception {
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        Weblog stored = mgr.getWeblogByHandle("vhostdbblog");
        stored.setCustomDomain("dbtest.example.com");
        mgr.saveWeblog(stored);
        TestUtils.endSession(true);

        assertEquals("vhostdbblog", registry().handleFor("dbtest.example.com"));
    }

    @Test
    void hostForAgreesWithHandleFor() throws Exception {
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        Weblog stored = mgr.getWeblogByHandle("vhostdbblog");
        stored.setCustomDomain("dbtest2.example.com");
        mgr.saveWeblog(stored);
        TestUtils.endSession(true);

        assertEquals("dbtest2.example.com", registry().hostFor("vhostdbblog"));
    }

    /**
     * Proves {@code saveWeblog}'s {@code invalidate()} call, not just
     * {@code invalidate()} itself: the first lookup forces the map to cache
     * under the OLD domain, then the domain changes and the OLD host must
     * stop resolving while the NEW one starts -- with no manual
     * {@code invalidate()} between the second save and the second lookup. A
     * misplaced or removed {@code invalidate()} call in {@code saveWeblog}
     * leaves the OLD mapping cached and the NEW one invisible.
     */
    @Test
    void changingTheDomainMovesTheMappingWithoutManualInvalidate() throws Exception {
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();

        Weblog stored = mgr.getWeblogByHandle("vhostdbblog");
        stored.setCustomDomain("old.example.com");
        mgr.saveWeblog(stored);
        TestUtils.endSession(true);
        // Force the map to build and cache under the OLD domain.
        assertEquals("vhostdbblog", registry().handleFor("old.example.com"));

        stored = mgr.getWeblogByHandle("vhostdbblog");
        stored.setCustomDomain("new.example.com");
        mgr.saveWeblog(stored);
        TestUtils.endSession(true);

        assertNull(registry().handleFor("old.example.com"));
        assertEquals("vhostdbblog", registry().handleFor("new.example.com"));
    }

    /**
     * I5: {@code saveWeblog}'s {@code invalidate()} runs right after {@code
     * strategy.store(weblog)}, BEFORE the surrounding transaction actually
     * commits -- the commit happens later, in {@code weblogger.flush()}
     * (called by the controller, or by {@code TestUtils.endSession(true)}
     * here). In the window between them, a genuinely concurrent request on a
     * DIFFERENT connection (a different thread here -- {@code
     * JPAPersistenceStrategy}'s EntityManager is thread-local, so a new
     * thread gets its own, and under Postgres's default READ_COMMITTED
     * isolation it cannot see this thread's still-uncommitted write) rebuilds
     * the map from the PRE-COMMIT row and caches it. If nothing invalidates
     * the registry again once the commit actually lands, that stale
     * (no-domain) map stays cached for the life of the JVM, or until some
     * UNRELATED weblog save happens to invalidate it -- meanwhile the author
     * who just saved sees "Saved changes" for a domain that never resolves.
     *
     * <p>An earlier version of this test rebuilt the map on the SAME thread
     * instead of a background one, expecting {@code getWeblogs}'s {@code
     * FlushModeType.COMMIT} query to skip the pending write. It does skip
     * the auto-flush, but JPA's identity map still hands back the SAME
     * managed {@code Weblog} instance already sitting in this thread's
     * persistence context -- carrying the in-memory, not-yet-flushed
     * {@code customDomain} regardless of the query's flush mode -- so that
     * version proved nothing about cross-request staleness. A real second
     * connection is what a real concurrent request is.
     */
    @Test
    void aReaderOnAnotherThreadThatCachesTheMapBeforeCommitSeesItInvalidatedAfterCommit() throws Exception {
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        Weblog stored = mgr.getWeblogByHandle("vhostdbblog");
        stored.setCustomDomain("race.example.com");
        mgr.saveWeblog(stored); // pre-commit invalidate() runs here; not yet committed on THIS thread

        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                assertNull(registry().handleFor("race.example.com"),
                        "sanity: a genuinely concurrent reader on its own connection must not "
                                + "observe the still-uncommitted domain");
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                // Closes this thread's own EntityManager/connection so the
                // test does not leak one from the pool per run.
                TestUtils.weblogger().release();
            }
        });
        reader.start();
        reader.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }

        // The write actually commits now, on the original thread.
        TestUtils.endSession(true);

        assertEquals("vhostdbblog", registry().handleFor("race.example.com"),
                "the registry must be invalidated again after commit -- otherwise the stale "
                        + "pre-commit map stays cached until an unrelated save invalidates it");
    }

    /**
     * Covers the {@code removeWeblog} call site: a removed weblog's domain
     * must stop resolving. Uses its own weblog/user, torn down inside the
     * test, rather than the shared fixture -- the class-level
     * {@code tearDown} already removes the shared weblog and must not be
     * asked to remove an already-removed row.
     */
    @Test
    void removingAWeblogStopsItsDomainFromResolving() throws Exception {
        User removalUser = TestUtils.setupUser("vhostdbremoveuser");
        Weblog removalWeblog = TestUtils.setupWeblog("vhostdbremoveblog", removalUser);
        TestUtils.endSession(true);

        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        Weblog stored = mgr.getWeblogByHandle("vhostdbremoveblog");
        stored.setCustomDomain("removeme.example.com");
        mgr.saveWeblog(stored);
        TestUtils.endSession(true);
        // Force the map to build and cache the mapping before removal.
        assertEquals("vhostdbremoveblog", registry().handleFor("removeme.example.com"));

        mgr.removeWeblog(mgr.getWeblog(removalWeblog.getId()));
        TestUtils.endSession(true);

        assertNull(registry().handleFor("removeme.example.com"));

        TestUtils.teardownUser(removalUser.getUserName());
        TestUtils.endSession(true);
    }
}
