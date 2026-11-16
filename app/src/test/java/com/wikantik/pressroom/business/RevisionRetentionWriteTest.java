package com.wikantik.pressroom.business;

import java.lang.reflect.Field;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntryRevision;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPageRevision;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Revision retention at the write (spec 2026-11-15, Part 3): coalescing and
 * the per-item count, for posts and for pages alike. Every case runs once per
 * kind, through {@link Subject}.
 */
class RevisionRetentionWriteTest {

    private static final String COALESCE = "revisions.coalesce.minutes";

    /** What the cases need of a post or a page. */
    enum Subject {
        POST("entry.revisions.retention", "weblogentry_revision", "entryid"),
        PAGE("page.revisions.retention", "pressroom_weblogpage_revision", "pageid");

        final String retentionProperty;
        final String table;
        final String ownerColumn;

        Subject(String retentionProperty, String table, String ownerColumn) {
            this.retentionProperty = retentionProperty;
            this.table = table;
            this.ownerColumn = ownerColumn;
        }
    }

    private User alice;
    private User bob;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        alice = TestUtils.setupUser("retentionalice");
        bob = TestUtils.setupUser("retentionbob");
        weblog = TestUtils.setupWeblog("retentionblog", alice);
        TestUtils.endSession(true);
        set(COALESCE, "10");
    }

    @AfterEach
    void tearDown() throws Exception {
        set(COALESCE, "10");
        set(Subject.POST.retentionProperty, "-1");
        set(Subject.PAGE.retentionProperty, "50");
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(alice.getUserName());
        TestUtils.teardownUser(bob.getUserName());
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------ coalescing

    @ParameterizedTest
    @EnumSource(Subject.class)
    void twoSavesByOneUserInsideTheWindowLeaveOne(Subject s) throws Exception {
        String id = create(s, "v0");

        edit(s, id, "v1", alice, null);
        age(s, id, 3);
        edit(s, id, "v2", alice, null);

        assertEquals(List.of("v0"), contents(s, id), "the one revision holds the content from before the burst");
    }

    @ParameterizedTest
    @EnumSource(Subject.class)
    void aSaveAfterTheWindowLeavesAnother(Subject s) throws Exception {
        String id = create(s, "v0");

        edit(s, id, "v1", alice, null);
        age(s, id, 11);
        edit(s, id, "v2", alice, null);

        assertEquals(List.of("v1", "v0"), contents(s, id));
    }

    @ParameterizedTest
    @EnumSource(Subject.class)
    void aNoteIsNeverCoalesced(Subject s) throws Exception {
        String id = create(s, "v0");

        edit(s, id, "v1", alice, null);
        edit(s, id, "v2", alice, "worth its own revision");

        assertEquals(List.of("v1", "v0"), contents(s, id));
    }

    /** B's save in between ends A's burst: A's next save writes a revision again. */
    @ParameterizedTest
    @EnumSource(Subject.class)
    void anotherUsersSaveIsNotCoalescedAndEndsTheBurst(Subject s) throws Exception {
        String id = create(s, "v0");

        edit(s, id, "v1", alice, null);
        edit(s, id, "v2", bob, null);
        edit(s, id, "v3", alice, null);

        assertEquals(List.of("v2", "v1", "v0"), contents(s, id));
    }

    @ParameterizedTest
    @EnumSource(Subject.class)
    void aSystemSaveIsNeverCoalesced(Subject s) throws Exception {
        String id = create(s, "v0");

        edit(s, id, "v1", null, null);
        edit(s, id, "v2", null, null);

        assertEquals(List.of("v1", "v0"), contents(s, id));
    }

    /** Or a save followed by a restore would lose the content the restore replaced. */
    @ParameterizedTest
    @EnumSource(Subject.class)
    void aRestoreIsNeverCoalesced(Subject s) throws Exception {
        String id = create(s, "v0");
        edit(s, id, "v1", alice, null);
        age(s, id, 1);

        restoreNewestRevision(s, id, alice);

        assertEquals("v0", current(s, id));
        assertEquals(List.of("v1", "v0"), contents(s, id), "what the restore replaced is kept");
    }

    @ParameterizedTest
    @EnumSource(Subject.class)
    void windowZeroTurnsCoalescingOff(Subject s) throws Exception {
        set(COALESCE, "0");
        String id = create(s, "v0");

        edit(s, id, "v1", alice, null);
        edit(s, id, "v2", alice, null);

        assertEquals(List.of("v1", "v0"), contents(s, id));
    }

    /** A save that writes no revision must not push one out either. */
    @ParameterizedTest
    @EnumSource(Subject.class)
    void aCoalescedSaveRemovesNothing(Subject s) throws Exception {
        set(s.retentionProperty, "1");
        String id = create(s, "v0");

        edit(s, id, "v1", alice, null);
        edit(s, id, "v2", alice, null);

        assertEquals(List.of("v0"), contents(s, id));
    }

    // ------------------------------------------------------------- the count

    /** Kept revisions are extra to the count: the item holds its count plus its kept ones. */
    @ParameterizedTest
    @EnumSource(Subject.class)
    void theCountIgnoresKeptRevisions(Subject s) throws Exception {
        set(COALESCE, "0");
        set(s.retentionProperty, "3");
        String id = create(s, "v0");
        edit(s, id, "v1", alice, null);
        edit(s, id, "v2", alice, null);
        raw("UPDATE " + s.table + " SET kept = true WHERE " + s.ownerColumn + " = ?", id);

        for (int i = 3; i <= 7; i++) {
            edit(s, id, "v" + i, alice, null);
        }

        assertEquals(List.of("v6", "v5", "v4", "v1", "v0"), contents(s, id));
    }

    @ParameterizedTest
    @EnumSource(Subject.class)
    void restoringBackAndForthStaysWithinTheCount(Subject s) throws Exception {
        set(s.retentionProperty, "4");
        String id = create(s, "v0");
        edit(s, id, "v1", alice, null);

        for (int i = 0; i < 10; i++) {
            restoreNewestRevision(s, id, alice);
        }

        assertTrue(contents(s, id).size() <= 4, "got " + contents(s, id));
    }

    /** Installing retention removes nothing: rows from before it, with kept defaulted, all survive a save. */
    @ParameterizedTest
    @EnumSource(Subject.class)
    void existingRevisionsSurviveAnUpgrade(Subject s) throws Exception {
        set(COALESCE, "0");
        set(s.retentionProperty, "-1");
        String id = create(s, "v0");
        for (int i = 1; i <= 8; i++) {
            edit(s, id, "v" + i, alice, null);
        }

        set(COALESCE, "10");
        age(s, id, 60);
        edit(s, id, "v9", alice, null);

        assertEquals(9, contents(s, id).size());
    }

    // ------------------------------------------------------------------ kept

    @ParameterizedTest
    @EnumSource(Subject.class)
    void anEleventhKeptRevisionIsRefused(Subject s) throws Exception {
        set(COALESCE, "0");
        set(s.retentionProperty, "-1");
        String id = create(s, "v0");
        for (int i = 1; i <= 11; i++) {
            edit(s, id, "v" + i, alice, null);
        }
        for (int i = 0; i < 10; i++) {
            keep(s, id, i, true);
        }

        assertThrows(RevisionKeepLimitException.class, () -> keep(s, id, 10, true));
        TestUtils.endSession(false);
        assertEquals(10, keptCount(s, id));

        // releasing one makes room again
        keep(s, id, 0, false);
        keep(s, id, 10, true);
        assertEquals(10, keptCount(s, id));
    }

    @ParameterizedTest
    @EnumSource(Subject.class)
    void keepingIsIdempotent(Subject s) throws Exception {
        set(COALESCE, "0");
        String id = create(s, "v0");
        edit(s, id, "v1", alice, null);

        keep(s, id, 0, true);
        keep(s, id, 0, true);
        assertEquals(1, keptCount(s, id));

        keep(s, id, 0, false);
        keep(s, id, 0, false);
        assertEquals(0, keptCount(s, id));
    }

    /** Releasing a revision deletes nothing then and there: the next save, or the sweep, applies the count. */
    @ParameterizedTest
    @EnumSource(Subject.class)
    void unkeepingOnAnItemOverItsCountRemovesNothingUntilTheNextSave(Subject s) throws Exception {
        set(COALESCE, "0");
        set(s.retentionProperty, "2");
        String id = create(s, "v0");
        edit(s, id, "v1", alice, null);
        edit(s, id, "v2", alice, null);
        keep(s, id, 0, true);
        keep(s, id, 1, true);
        edit(s, id, "v3", alice, null);
        edit(s, id, "v4", alice, null);
        assertEquals(List.of("v3", "v2", "v1", "v0"), contents(s, id));

        keep(s, id, 2, false);
        keep(s, id, 3, false);
        assertEquals(List.of("v3", "v2", "v1", "v0"), contents(s, id), "nothing goes at the moment of release");

        edit(s, id, "v5", alice, null);
        assertEquals(List.of("v4", "v3"), contents(s, id), "the next save applies the count");
    }

    // ---------------------------------------------------------------- support

    private static WeblogEntryManager entries() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    private static WeblogPageManager pages() {
        return TestUtils.weblogger().getWeblogPageManager();
    }

    private static void commit() throws Exception {
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private static void set(String name, String value) throws Exception {
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        RuntimeConfigProperty property = pmgr.getProperties().get(name);
        assertNotNull(property, name + " is not a declared runtime property");
        property.setValue(value);
        pmgr.saveProperties(pmgr.getProperties());
        TestUtils.endSession(true);
    }

    private String create(Subject s, String content) throws Exception {
        if (s == Subject.POST) {
            WeblogEntry entry = TestUtils.setupWeblogEntry("retained", TestUtils.getManagedWebsite(weblog),
                    TestUtils.getManagedUser(alice));
            TestUtils.endSession(true);
            WeblogEntry managed = entries().getWeblogEntry(entry.getId());
            managed.setText(content);
            entries().saveWeblogEntry(managed);
            commit();
            raw("DELETE FROM weblogentry_revision WHERE entryid = ?", entry.getId());
            return entry.getId();
        }
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug("retained");
        page.setTitle("Retained");
        page.setContent(content);
        pages().savePage(page);
        commit();
        return page.getId();
    }

    private void edit(Subject s, String id, String content, User editor, String note) throws Exception {
        User actor = editor == null ? null : TestUtils.getManagedUser(editor);
        if (s == Subject.POST) {
            WeblogEntry entry = entries().getWeblogEntry(id);
            entry.setText(content);
            entries().saveWeblogEntry(entry, actor, note);
        } else {
            WeblogPage page = pages().getPage(id);
            page.setContent(content);
            pages().savePage(page, actor, note);
        }
        commit();
    }

    private void restoreNewestRevision(Subject s, String id, User editor) throws Exception {
        User actor = TestUtils.getManagedUser(editor);
        if (s == Subject.POST) {
            WeblogEntry entry = entries().getWeblogEntry(id);
            WeblogEntryRevision revision = entries().getRevisions(entry).get(0);
            entries().restoreRevision(entry, revision, actor);
        } else {
            WeblogPage page = pages().getPage(id);
            WeblogPageRevision revision = pages().getRevisions(page).get(0);
            pages().restoreRevision(page, revision, actor);
        }
        commit();
    }

    private String current(Subject s, String id) throws Exception {
        return s == Subject.POST ? entries().getWeblogEntry(id).getText() : pages().getPage(id).getContent();
    }

    /** The item's revisions' content, newest first. */
    private List<String> contents(Subject s, String id) throws Exception {
        TestUtils.endSession(true);
        if (s == Subject.POST) {
            return entries().getRevisions(entries().getWeblogEntry(id)).stream()
                    .map(WeblogEntryRevision::getText).toList();
        }
        return pages().getRevisions(pages().getPage(id)).stream().map(WeblogPageRevision::getContent).toList();
    }

    /** Moves every revision of the item back in time, as if that long had passed since each was written. */
    private void age(Subject s, String id, int minutes) throws Exception {
        raw("UPDATE " + s.table + " SET created = created - make_interval(mins => " + minutes + ") WHERE "
                + s.ownerColumn + " = ?", id);
    }

    /** Keeps or releases the item's index-th revision, newest first. */
    private void keep(Subject s, String id, int index, boolean kept) throws Exception {
        if (s == Subject.POST) {
            entries().setRevisionKept(entries().getRevisions(entries().getWeblogEntry(id)).get(index), kept);
        } else {
            pages().setRevisionKept(pages().getRevisions(pages().getPage(id)).get(index), kept);
        }
        commit();
    }

    private long keptCount(Subject s, String id) throws Exception {
        TestUtils.endSession(true);
        if (s == Subject.POST) {
            return entries().getRevisions(entries().getWeblogEntry(id)).stream()
                    .filter(WeblogEntryRevision::isKept).count();
        }
        return pages().getRevisions(pages().getPage(id)).stream().filter(WeblogPageRevision::isKept).count();
    }

    private static void raw(String sql, String id) throws Exception {
        Query update = strategy().getEntityManager(true).createNativeQuery(sql);
        update.setParameter(1, id);
        update.executeUpdate();
        commit();
        strategy().getEntityManager(false).getEntityManagerFactory().getCache().evictAll();
        TestUtils.endSession(true);
    }

    private static JPAPersistenceStrategy strategy() throws Exception {
        Object manager = entries();
        Field f = manager.getClass().getDeclaredField("strategy");
        f.setAccessible(true);
        return (JPAPersistenceStrategy) f.get(manager);
    }
}
