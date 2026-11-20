package com.wikantik.pressroom.business.jpa;

import java.lang.reflect.Field;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The purge must not delete a page that left the trash after the sweep read
 * it. The sweep holds each candidate as it was when listed; an author's
 * restore can commit before the delete. So each page is read again, locked,
 * immediately before it is removed, and removed only if it is still trash.
 */
class PageTrashPurgeRaceTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("purgeraceuser");
        weblog = TestUtils.setupWeblog("purgeraceblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static JPAWeblogPageManagerImpl manager() {
        return (JPAWeblogPageManagerImpl) TestUtils.weblogger().getWeblogPageManager();
    }

    private static JPAPersistenceStrategy strategy() throws Exception {
        Field f = JPAWeblogPageManagerImpl.class.getDeclaredField("strategy");
        f.setAccessible(true);
        return (JPAPersistenceStrategy) f.get(manager());
    }

    private String trashedPage(String slug) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle("Title for " + slug);
        page.setContent("Body");
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        manager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        manager().trashPage(manager().getPage(page.getId()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return page.getId();
    }

    /**
     * The candidate is in hand as TRASHED; the row is then changed to a
     * draft the way another transaction's restore would leave it. The purge
     * step must see the row, not the copy in hand.
     */
    @Test
    void aPageRestoredAfterTheSweepListedItIsNotPurged() throws Exception {
        String id = trashedPage("about");
        WeblogPage candidate = manager().getTrashedPages(TestUtils.getManagedWebsite(weblog)).get(0);
        assertEquals(WeblogPage.PubStatus.TRASHED, candidate.getStatus());

        Query restore = strategy().getEntityManager(true).createNativeQuery(
                "UPDATE pressroom_weblogpage SET status = 'DRAFT', trashed_at = NULL WHERE id = ?");
        restore.setParameter(1, id);
        assertEquals(1, restore.executeUpdate());

        assertFalse(manager().removeIfStillTrashed(candidate, cutoff()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        // The native update went round the shared cache; read the row itself.
        strategy().getEntityManager(false).getEntityManagerFactory().getCache().evictAll();
        TestUtils.endSession(true);

        WeblogPage kept = manager().getPage(id);
        assertNotNull(kept, "a page that left the trash must survive the purge");
        assertEquals(WeblogPage.PubStatus.DRAFT, kept.getStatus());
    }

    @Test
    void aPageStillInTheTrashIsRemoved() throws Exception {
        String id = trashedPage("about");
        WeblogPage candidate = manager().getTrashedPages(TestUtils.getManagedWebsite(weblog)).get(0);

        assertTrue(manager().removeIfStillTrashed(candidate, cutoff()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertNull(manager().getPage(id));
    }

    /** The purge step's cutoff: a moment from now, so a page trashed just now is past it. */
    private static java.sql.Timestamp cutoff() {
        return new java.sql.Timestamp(System.currentTimeMillis() + 60_000);
    }

    /** A page trashed after the cutoff (re-trashed since the sweep listed it) is kept, as an entry is. */
    @Test
    void aPageTrashedAfterTheCutoffIsNotPurged() throws Exception {
        String id = trashedPage("about");
        WeblogPage candidate = manager().getTrashedPages(TestUtils.getManagedWebsite(weblog)).get(0);
        java.sql.Timestamp yesterday = java.sql.Timestamp.from(
                java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS));

        assertFalse(manager().removeIfStillTrashed(candidate, yesterday));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertNotNull(manager().getPage(id));
    }

    @Test
    void aPageAlreadyInTheTrashCannotBeTrashedAgainNorADraftRestored() throws Exception {
        String id = trashedPage("about");
        WeblogPage trashed = manager().getPage(id);
        org.junit.jupiter.api.Assertions.assertThrows(com.wikantik.pressroom.WebloggerException.class,
                () -> manager().trashPage(trashed));
        manager().restorePage(trashed);
        org.junit.jupiter.api.Assertions.assertThrows(com.wikantik.pressroom.WebloggerException.class,
                () -> manager().restorePage(trashed));
    }
}
