package com.wikantik.pressroom.business;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import jakarta.persistence.Query;
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
 * The page trash in the business tier (spec 2026-11-15, Part 1): a deleted
 * page is kept, out of every list readers and authors see, until it is
 * restored, deleted for good, or purged.
 */
class WeblogPageTrashTest {

    private User user;
    private Weblog weblog;
    private boolean weblogRemoved;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("pagetrashuser");
        weblog = TestUtils.setupWeblog("pagetrashblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (!weblogRemoved) {
            TestUtils.teardownWeblog(weblog.getId());
        }
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static WeblogPageManager manager() {
        return TestUtils.weblogger().getWeblogPageManager();
    }

    private static WeblogEntryManager entries() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    private Weblog managed() throws Exception {
        return TestUtils.getManagedWebsite(weblog);
    }

    private WeblogPage save(String slug, WeblogPage.PubStatus status, boolean strand) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(managed());
        page.setSlug(slug);
        page.setTitle("Title for " + slug);
        page.setContent("Body of " + slug);
        page.setStatus(status);
        page.setStrand(strand);
        manager().savePage(page);
        commit();
        return page;
    }

    private WeblogPage published(String slug) throws Exception {
        return save(slug, WeblogPage.PubStatus.PUBLISHED, false);
    }

    private static void commit() throws Exception {
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private void trash(String pageId) throws Exception {
        manager().trashPage(manager().getPage(pageId));
        commit();
    }

    private static JPAPersistenceStrategy strategy() throws Exception {
        Field f = entries().getClass().getDeclaredField("strategy");
        f.setAccessible(true);
        return (JPAPersistenceStrategy) f.get(entries());
    }

    /** Writes one column of one page row behind the manager's back. */
    private static void rawUpdate(String sql, Object... parameters) throws Exception {
        Query update = strategy().getEntityManager(true).createNativeQuery(sql);
        for (int i = 0; i < parameters.length; i++) {
            update.setParameter(i + 1, parameters[i]);
        }
        assertEquals(1, update.executeUpdate());
        commit();
        // The shared cache still holds the row as the manager last wrote it.
        strategy().getEntityManager(false).getEntityManagerFactory().getCache().evictAll();
        TestUtils.endSession(true);
    }

    private static void trashedDaysAgo(String pageId, int days) throws Exception {
        rawUpdate("UPDATE pressroom_weblogpage SET trashed_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(days))), pageId);
    }

    @Test
    void trashingSetsStatusAndStamp() throws Exception {
        String id = published("about").getId();

        trash(id);

        WeblogPage found = manager().getPage(id);
        assertEquals(WeblogPage.PubStatus.TRASHED, found.getStatus());
        assertNotNull(found.getTrashedAt());
        long age = System.currentTimeMillis() - found.getTrashedAt().getTime();
        assertTrue(age >= 0 && age < 5000, "trashedAt is now, was " + age + " ms ago");
    }

    @Test
    void trashedPageLeavesGetPagesAndJoinsTrashList() throws Exception {
        String id = published("about").getId();
        String kept = published("contact").getId();

        trash(id);

        assertEquals(List.of(kept), manager().getPages(managed()).stream().map(WeblogPage::getId).toList());
        assertEquals(List.of(kept),
                manager().getPublishedPages(managed()).stream().map(WeblogPage::getId).toList());
        assertEquals(List.of(id),
                manager().getTrashedPages(managed()).stream().map(WeblogPage::getId).toList());
    }

    @Test
    void trashedPageIsStillFoundBySlugAndId() throws Exception {
        String id = published("about").getId();

        trash(id);

        assertEquals(id, manager().getPageBySlug(managed(), "about").getId());
        assertEquals(id, manager().getPage(id).getId());
    }

    @Test
    void trashingTheFrontPageClearsTheFrontDoor() throws Exception {
        String id = published("home").getId();
        Weblog w = managed();
        w.setFrontPageId(id);
        TestUtils.weblogger().getWeblogManager().saveWeblog(w);
        commit();
        assertEquals(id, managed().getFrontPageId());

        trash(id);

        assertNull(managed().getFrontPageId());
    }

    @Test
    void trashingMovesTheWeblogStamp() throws Exception {
        String id = published("about").getId();
        long before = managed().getLastModified().getTime();
        Thread.sleep(5);

        trash(id);

        assertTrue(managed().getLastModified().getTime() > before);
    }

    @Test
    void trashingAStrandKeepsItsParts() throws Exception {
        String strandId = save("series", WeblogPage.PubStatus.PUBLISHED, true).getId();
        String first = part("part-one");
        String second = part("part-two");
        setParts(strandId, first, second);

        trash(strandId);

        assertEquals(strandId, entries().getWeblogEntry(first).getStrandPageId());
        assertEquals(strandId, entries().getWeblogEntry(second).getStrandPageId());
        assertEquals(List.of(first, second),
                entries().getStrandParts(manager().getPage(strandId), false).stream()
                        .map(WeblogEntry::getId).toList());
    }

    @Test
    void trashingMintsNoRedirect() throws Exception {
        String id = published("about").getId();

        trash(id);

        assertTrue(TestUtils.weblogger().getWeblogRedirectManager().getRedirects(managed()).isEmpty());
    }

    @Test
    void trashingTwiceIsRefused() throws Exception {
        String id = published("about").getId();
        trash(id);
        Timestamp first = manager().getPage(id).getTrashedAt();
        TestUtils.endSession(true);

        assertThrows(WebloggerException.class, () -> manager().trashPage(manager().getPage(id)));
        TestUtils.endSession(true);

        assertEquals(first, manager().getPage(id).getTrashedAt());
    }

    @Test
    void restoreYieldsDraftAndNoFrontDoor() throws Exception {
        WeblogPage home = published("home");
        String id = home.getId();
        Weblog w = managed();
        w.setFrontPageId(id);
        TestUtils.weblogger().getWeblogManager().saveWeblog(w);
        commit();
        int navOrder = manager().getPage(id).getNavOrder();
        trash(id);

        manager().restorePage(manager().getPage(id));
        commit();

        WeblogPage restored = manager().getPage(id);
        assertEquals(WeblogPage.PubStatus.DRAFT, restored.getStatus());
        assertNull(restored.getTrashedAt());
        assertEquals(navOrder, restored.getNavOrder());
        assertNull(managed().getFrontPageId());
        assertTrue(manager().getTrashedPages(managed()).isEmpty());
    }

    @Test
    void restoringALivePageIsRefused() throws Exception {
        String id = save("about", WeblogPage.PubStatus.DRAFT, false).getId();

        assertThrows(WebloggerException.class, () -> manager().restorePage(manager().getPage(id)));
    }

    /**
     * A slug saved before a rule existed must not strand its page: trash and
     * restore change no address, so they run no slug check.
     */
    @Test
    void aSlugThatNoLongerPassesTheRulesStillTrashesAndRestores() throws Exception {
        String id = published("legacy").getId();
        rawUpdate("UPDATE pressroom_weblogpage SET slug = ? WHERE id = ?", "feed", id);
        assertThrows(WebloggerException.class, () -> {
            WeblogPage page = manager().getPage(id);
            manager().savePage(page);
        }, "the slug really is one savePage refuses");
        TestUtils.endSession(true);

        trash(id);
        assertEquals(WeblogPage.PubStatus.TRASHED, manager().getPage(id).getStatus());

        manager().restorePage(manager().getPage(id));
        commit();
        assertEquals(WeblogPage.PubStatus.DRAFT, manager().getPage(id).getStatus());
        assertEquals("feed", manager().getPage(id).getSlug());
    }

    @Test
    void purgeRemovesOnlyPagesPastRetention() throws Exception {
        String old = published("old").getId();
        String recent = published("recent").getId();
        trash(old);
        trash(recent);
        trashedDaysAgo(old, 8);
        trashedDaysAgo(recent, 6);

        int purged = manager().purgeTrash(managed(), 7);
        commit();

        assertEquals(1, purged);
        assertNull(manager().getPage(old));
        assertNotNull(manager().getPage(recent));
    }

    @Test
    void purgeAtZeroRemovesAllTrash() throws Exception {
        String a = published("a-page").getId();
        String b = published("b-page").getId();
        String live = published("live").getId();
        trash(a);
        trash(b);
        trashedDaysAgo(a, 1);
        trashedDaysAgo(b, 1);

        int purged = manager().purgeTrash(managed(), 0);
        commit();

        assertEquals(2, purged);
        assertNotNull(manager().getPage(live));
        assertTrue(manager().getTrashedPages(managed()).isEmpty());
    }

    @Test
    void purgeAtAnyNegativeRemovesNothing() throws Exception {
        String id = published("about").getId();
        trash(id);
        trashedDaysAgo(id, 400);

        assertEquals(0, manager().purgeTrash(managed(), -1));
        assertEquals(0, manager().purgeTrash(managed(), -5));
        commit();

        assertNotNull(manager().getPage(id));
    }

    @Test
    void purgeSkipsATrashedRowWithNoStamp() throws Exception {
        String id = published("about").getId();
        trash(id);
        rawUpdate("UPDATE pressroom_weblogpage SET trashed_at = NULL WHERE id = ?", id);

        assertEquals(0, manager().purgeTrash(managed(), 0));
        commit();

        assertNotNull(manager().getPage(id));
    }

    @Test
    void purgedStrandDetachesItsParts() throws Exception {
        String strandId = save("series", WeblogPage.PubStatus.PUBLISHED, true).getId();
        String first = part("part-one");
        setParts(strandId, first);
        trash(strandId);
        trashedDaysAgo(strandId, 8);

        assertEquals(1, manager().purgeTrash(managed(), 7));
        commit();

        WeblogEntry entry = entries().getWeblogEntry(first);
        assertNull(entry.getStrandPageId());
        assertEquals(WeblogEntry.PubStatus.PUBLISHED, entry.getStatus());
    }

    @Test
    void afterDeleteForeverTheSlugIsFree() throws Exception {
        String id = published("about").getId();
        trash(id);

        manager().removePage(manager().getPage(id));
        commit();

        assertFalse(published("about").getId().equals(id));
    }

    @Test
    void removingAWeblogTakesItsTrashedPages() throws Exception {
        String id = published("about").getId();
        trash(id);

        TestUtils.teardownWeblog(weblog.getId());
        weblogRemoved = true;
        TestUtils.endSession(true);

        assertNull(manager().getPage(id));
    }

    private String part(String anchor) throws Exception {
        String id = TestUtils.setupWeblogEntry(anchor, weblog, user, WeblogEntry.PubStatus.PUBLISHED).getId();
        TestUtils.endSession(true);
        return id;
    }

    private void setParts(String strandId, String... entryIds) throws Exception {
        List<WeblogEntry> parts = new java.util.ArrayList<>();
        for (String entryId : entryIds) {
            parts.add(entries().getWeblogEntry(entryId));
        }
        entries().setStrandParts(manager().getPage(strandId), parts);
        commit();
    }
}
