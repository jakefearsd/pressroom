package com.wikantik.pressroom.business;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPageRevision;
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
 * Page revisions in the business tier (spec 2026-11-15, Part 2): a save that
 * changes a page's title or content keeps the version it replaced, and a
 * revision that cannot be stored never costs the save.
 */
class WeblogPageRevisionTest {

    private static final String RETENTION = "page.revisions.retention";

    private User user;
    private Weblog weblog;
    private boolean weblogRemoved;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("pagerevuser");
        weblog = TestUtils.setupWeblog("pagerevblog", user);
        TestUtils.endSession(true);
        // These cases are about what a save records. One user's quick saves
        // would otherwise be coalesced into one revision
        // (RevisionRetentionWriteTest covers that), so it is off here.
        set("revisions.coalesce.minutes", "0");
    }

    @AfterEach
    void tearDown() throws Exception {
        setRetention("50");
        set("revisions.coalesce.minutes", "10");
        if (!weblogRemoved) {
            TestUtils.teardownWeblog(weblog.getId());
        }
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static WeblogPageManager manager() {
        return TestUtils.weblogger().getWeblogPageManager();
    }

    private static void commit() throws Exception {
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private void setRetention(String value) throws Exception {
        set(RETENTION, value);
    }

    private static void set(String name, String value) throws Exception {
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        RuntimeConfigProperty property = pmgr.getProperties().get(name);
        assertNotNull(property, name + " is not a declared runtime property");
        property.setValue(value);
        pmgr.saveProperties(pmgr.getProperties());
        TestUtils.endSession(true);
    }

    private String newPage(String slug, String title, String content) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent(content);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        manager().savePage(page, TestUtils.getManagedUser(user), null);
        commit();
        return page.getId();
    }

    /** One content-changing save in its own session, by the test user. */
    private void edit(String id, String title, String content, String note) throws Exception {
        WeblogPage page = manager().getPage(id);
        page.setTitle(title);
        page.setContent(content);
        manager().savePage(page, TestUtils.getManagedUser(user), note);
        commit();
    }

    private List<WeblogPageRevision> revisions(String id) throws Exception {
        return manager().getRevisions(manager().getPage(id));
    }

    @Test
    void aContentSaveLeavesTheDisplacedTitleAndContent() throws Exception {
        String id = newPage("about", "About", "First body");

        edit(id, "About us", "Second body", null);

        List<WeblogPageRevision> found = revisions(id);
        assertEquals(1, found.size());
        WeblogPageRevision revision = found.get(0);
        assertEquals("About", revision.getTitle());
        assertEquals("First body", revision.getContent());
        assertEquals(user.getUserName(), revision.getCreator());
        assertNull(revision.getNote());
        assertFalse(revision.isKept());
        assertNotNull(revision.getCreated());
        assertEquals(id, revision.getPage().getId());
    }

    @Test
    void aNoteLabelsOnlyTheRevisionItsSaveLeft() throws Exception {
        String id = newPage("about", "About", "v1");

        edit(id, "About", "v2", "why v2");
        edit(id, "About", "v3", null);

        List<WeblogPageRevision> found = revisions(id);
        assertEquals(2, found.size());
        assertNull(found.get(0).getNote(), "the newest revision came from a save with no note");
        assertEquals("why v2", found.get(1).getNote());
    }

    /** Pages have no creator column to fall back to: a system save credits nobody. */
    @Test
    void aSystemSaveRecordsANullCreator() throws Exception {
        String id = newPage("about", "About", "v1");

        WeblogPage page = manager().getPage(id);
        page.setContent("v2");
        manager().savePage(page);
        commit();

        assertNull(revisions(id).get(0).getCreator());
    }

    @Test
    void aSettingsOnlySaveLeavesNone() throws Exception {
        String id = newPage("about", "About", "v1");

        WeblogPage page = manager().getPage(id);
        page.setSlug("about-us");
        page.setStatus(WeblogPage.PubStatus.DRAFT);
        page.setShowInNav(Boolean.FALSE);
        page.setMetaTitle("Meta");
        page.setSearchDescription("Described");
        page.setNoindex(Boolean.TRUE);
        manager().savePage(page, TestUtils.getManagedUser(user), "settings only");
        commit();

        assertTrue(revisions(id).isEmpty());
    }

    @Test
    void aReorderATrashAndARestoreLeaveNone() throws Exception {
        String id = newPage("about", "About", "v1");
        newPage("contact", "Contact", "c1");

        manager().setNavOrder(TestUtils.getManagedWebsite(weblog), List.of(id));
        commit();
        manager().trashPage(manager().getPage(id));
        commit();
        manager().restorePage(manager().getPage(id));
        commit();

        assertTrue(revisions(id).isEmpty());
    }

    @Test
    void aNewPageLeavesNone() throws Exception {
        String id = newPage("about", "About", "v1");

        assertTrue(revisions(id).isEmpty());
    }

    /** The second save displaces what the first one stored, not what was loaded. */
    @Test
    void twoSavesInOneSessionChainTheirRevisions() throws Exception {
        String id = newPage("about", "About", "A");

        WeblogPage page = manager().getPage(id);
        page.setContent("B");
        manager().savePage(page, TestUtils.getManagedUser(user), null);
        TestUtils.weblogger().flush();
        page.setContent("C");
        manager().savePage(page, TestUtils.getManagedUser(user), null);
        commit();

        List<WeblogPageRevision> found = revisions(id);
        assertEquals(List.of("B", "A"), found.stream().map(WeblogPageRevision::getContent).toList());
        assertEquals("C", manager().getPage(id).getContent());
    }

    @Test
    void revisionsAreNewestFirst() throws Exception {
        String id = newPage("about", "About", "v1");

        edit(id, "About", "v2", null);
        edit(id, "About", "v3", null);
        edit(id, "About", "v4", null);

        assertEquals(List.of("v3", "v2", "v1"),
                revisions(id).stream().map(WeblogPageRevision::getContent).toList());
    }

    /**
     * The revision row is refused by the database (its note is longer than
     * the column). The page save has already committed and must stand.
     */
    @Test
    void aRefusedRevisionRowDoesNotFailTheSave() throws Exception {
        String id = newPage("about", "About", "v1");

        edit(id, "About", "v2", "n".repeat(300));

        assertEquals("v2", manager().getPage(id).getContent());
        assertTrue(revisions(id).isEmpty());

        // and the page still records revisions afterwards
        edit(id, "About", "v3", null);
        assertEquals(List.of("v2"), revisions(id).stream().map(WeblogPageRevision::getContent).toList());
    }

    @Test
    void aRolledBackSaveRecordsNone() throws Exception {
        String id = newPage("about", "About", "v1");

        WeblogPage page = manager().getPage(id);
        page.setContent("never committed");
        manager().savePage(page, TestUtils.getManagedUser(user), null);
        TestUtils.endSession(false);

        assertEquals("v1", manager().getPage(id).getContent());
        assertTrue(revisions(id).isEmpty());
    }

    @Test
    void retentionKeepsTheNewestN() throws Exception {
        setRetention("3");
        String id = newPage("about", "About", "v0");

        for (int i = 1; i <= 5; i++) {
            edit(id, "About", "v" + i, null);
        }

        assertEquals(List.of("v4", "v3", "v2"),
                revisions(id).stream().map(WeblogPageRevision::getContent).toList());
    }

    @Test
    void retentionZeroRecordsNone() throws Exception {
        setRetention("0");
        String id = newPage("about", "About", "v0");

        edit(id, "About", "v1", null);

        assertTrue(revisions(id).isEmpty());
    }

    @Test
    void negativeRetentionKeepsAll() throws Exception {
        setRetention("-1");
        String id = newPage("about", "About", "v0");

        for (int i = 1; i <= 5; i++) {
            edit(id, "About", "v" + i, null);
        }

        assertEquals(5, revisions(id).size());
    }

    /** As shipped: 50 a page, so page history is bounded from the first release. */
    @Test
    void theShippedRetentionIsFifty() throws Exception {
        assertEquals("50", TestUtils.weblogger().getPropertiesManager().getProperties().get(RETENTION).getValue());
    }

    @Test
    void deletingAPageForeverRemovesItsRevisions() throws Exception {
        String id = newPage("about", "About", "v1");
        edit(id, "About", "v2", null);
        String revisionId = revisions(id).get(0).getId();
        TestUtils.endSession(true);

        manager().removePage(manager().getPage(id));
        commit();

        assertNull(manager().getRevision(revisionId));
        assertNull(manager().getPage(id));
    }

    @Test
    void purgingATrashedPageRemovesItsRevisions() throws Exception {
        String id = newPage("about", "About", "v1");
        edit(id, "About", "v2", null);
        String revisionId = revisions(id).get(0).getId();
        TestUtils.endSession(true);
        manager().trashPage(manager().getPage(id));
        commit();
        Query age = strategy().getEntityManager(true).createNativeQuery(
                "UPDATE pressroom_weblogpage SET trashed_at = ? WHERE id = ?");
        age.setParameter(1, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        age.setParameter(2, id);
        assertEquals(1, age.executeUpdate());
        commit();
        strategy().getEntityManager(false).getEntityManagerFactory().getCache().evictAll();
        TestUtils.endSession(true);

        assertEquals(1, manager().purgeTrash(TestUtils.getManagedWebsite(weblog), 7));
        commit();

        assertNull(manager().getRevision(revisionId));
    }

    @Test
    void removingAWeblogRemovesItsPagesRevisions() throws Exception {
        String id = newPage("about", "About", "v1");
        edit(id, "About", "v2", null);
        String revisionId = revisions(id).get(0).getId();
        TestUtils.endSession(true);

        TestUtils.teardownWeblog(weblog.getId());
        weblogRemoved = true;
        TestUtils.endSession(true);

        assertNull(manager().getRevision(revisionId));
    }

    @Test
    void lockPageContentAnswersTheCommittedContent() throws Exception {
        String id = newPage("about", "About", "stored body");

        assertEquals("stored body", manager().lockPageContent(manager().getPage(id)));
        TestUtils.endSession(true);

        WeblogPage ghost = new WeblogPage();
        ghost.setId("no-such-page");
        assertNull(manager().lockPageContent(ghost));
        TestUtils.endSession(true);
    }

    @Test
    void readingRevisionsOfNothingIsEmptyNotAnError() throws Exception {
        assertTrue(manager().getRevisions(null).isEmpty());
        assertNull(manager().getRevision(null));
    }

    private static JPAPersistenceStrategy strategy() throws Exception {
        Object entries = TestUtils.weblogger().getWeblogEntryManager();
        Field f = entries.getClass().getDeclaredField("strategy");
        f.setAccessible(true);
        return (JPAPersistenceStrategy) f.get(entries);
    }
}
