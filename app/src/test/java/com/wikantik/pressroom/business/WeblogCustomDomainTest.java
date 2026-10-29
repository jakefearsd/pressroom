package com.wikantik.pressroom.business;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hostname-to-weblog lookup virtual hosting resolves every request through. */
class WeblogCustomDomainTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("vhostuser");
        weblog = TestUtils.setupWeblog("vhostblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void aWeblogIsFoundByItsCustomDomain() throws Exception {
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        Weblog stored = mgr.getWeblogByHandle("vhostblog");
        stored.setCustomDomain("vhost.example.com");
        mgr.saveWeblog(stored);
        TestUtils.endSession(true);

        Weblog found = mgr.getWeblogByCustomDomain("vhost.example.com");
        assertEquals("vhostblog", found.getHandle());
    }

    @Test
    void anUnclaimedHostFindsNothing() throws Exception {
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        assertNull(mgr.getWeblogByCustomDomain("nobody.example.com"));
    }

    /**
     * A null host must not become a query that matches the many weblogs whose
     * custom_domain is NULL -- that would make every unclaimed hostname resolve
     * to an arbitrary weblog.
     */
    @Test
    void aNullHostFindsNothing() throws Exception {
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        assertNull(mgr.getWeblogByCustomDomain(null));
    }

    /**
     * {@code VirtualHostRegistry.map()} builds its hostname-to-handle cache by
     * calling {@code getWeblogs(null, null, null, null, 0, -1)} -- every
     * filter left unset, because it wants every weblog regardless of enabled/
     * active status. With all four filters null the WHERE clause has no
     * conditions in it, and {@code JPAWeblogManagerImpl.getWeblogs} used to
     * build the query as {@code "SELECT w FROM Weblog w WHERE " + whereClause}
     * with " ORDER BY ..." appended to whereClause UNCONDITIONALLY -- so an
     * empty whereClause produced the invalid JPQL
     * "SELECT w FROM Weblog w WHERE  ORDER BY w.dateCreated DESC", which
     * EclipseLink rejects at query-creation time with an
     * IllegalArgumentException. VirtualHostRegistry.map() catches that
     * exception and (deliberately, to tolerate pre-bootstrap calls) returns an
     * empty map WITHOUT caching it -- so every virtual-host lookup silently
     * failed, forever, both in tests and in production.
     */
    @Test
    void getWeblogsWithNoFiltersDoesNotThrow() throws Exception {
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        assertTrue(mgr.getWeblogs(null, null, null, null, 0, -1).stream()
                .anyMatch(w -> "vhostblog".equals(w.getHandle())));
    }

    /**
     * I6: {@code getWeblogs} orders by {@code dateCreated} with no tiebreak,
     * so {@code GET /api/v1/weblogs} (which paginates over it) can repeat or
     * skip a row across pages whenever two weblogs share a
     * {@code dateCreated} -- the same defect fixed for {@code User.getAll}.
     * Unlike the user queries (named queries in {@code User.orm.xml},
     * pinnable by reading that file as text), {@code getWeblogs} builds its
     * JPQL inline in Java, so there is no {@code .orm.xml} to regex here --
     * this reads {@code JPAWeblogManagerImpl.java} itself, the same
     * plain-text-source idiom {@code ProductionComposeTest} uses on the
     * Dockerfile.
     */
    @Test
    void getWeblogsCarriesAHandleTiebreakOnItsOrdering() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/wikantik/pressroom/business/jpa/JPAWeblogManagerImpl.java"));
        assertTrue(source.contains("ORDER BY w.dateCreated DESC, w.handle"),
                "getWeblogs has no tiebreak on its ORDER BY -- two LIMIT/OFFSET pages "
                        + "against it can return the same weblog twice, or skip one, "
                        + "whenever two weblogs share a dateCreated");
    }

    /**
     * CHARACTERISATION: saveWeblog already bumps lastModified unconditionally,
     * which is the ONLY thing that expires a page from WeblogPageCache -- it
     * has no CacheHandler, so CacheManager.invalidate never reaches it.
     * Without the bump, every cached page would keep serving handle-form urls
     * after a domain is set. Expected to pass on arrival; pinned so it is not
     * turned into a conditional bump later.
     */
    @Test
    void settingACustomDomainBumpsLastModified() throws Exception {
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        Weblog stored = mgr.getWeblogByHandle("vhostblog");
        java.util.Date before = stored.getLastModified();

        Thread.sleep(10);
        stored.setCustomDomain("bump.example.com");
        mgr.saveWeblog(stored);
        TestUtils.endSession(true);

        Weblog reloaded = mgr.getWeblogByHandle("vhostblog");
        assertTrue(reloaded.getLastModified().after(before),
                "lastModified must advance, or WeblogPageCache keeps serving handle-form urls");
    }
}
