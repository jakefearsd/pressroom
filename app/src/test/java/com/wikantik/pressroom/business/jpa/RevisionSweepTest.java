package com.wikantik.pressroom.business.jpa;

import java.lang.reflect.Field;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.RevisionSweep;
import com.wikantik.pressroom.business.jpa.RevisionSweeper.Limits;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
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
 * The revision sweep (spec 2026-11-15, Part 3): the per-item count, the age
 * limit and the site-wide ceiling, with the two protections neither age nor
 * the ceiling overrides: an item's newest five unkept revisions, and any
 * kept one. Rows are counted natively, so the assertions do not depend on
 * what else the test database holds: every limit is scoped by this test's
 * own rows, and the ceiling cases measure the change in the site's total.
 */
class RevisionSweepTest {

    private static final String ENTRY_RETENTION = "entry.revisions.retention";
    private static final String PAGE_RETENTION = "page.revisions.retention";
    private static final String MAX_AGE = "revisions.maxAgeDays";
    private static final String TOTAL_MAX = "revisions.total.max";

    private User user;
    private Weblog weblog;
    private Weblog otherWeblog;
    private String entryId;
    private String pageId;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("sweepuser");
        weblog = TestUtils.setupWeblog("sweepblog", user);
        otherWeblog = TestUtils.setupWeblog("sweepotherblog", user);
        TestUtils.endSession(true);
        set("revisions.coalesce.minutes", "0");
        set(ENTRY_RETENTION, "-1");
        set(PAGE_RETENTION, "-1");
        set(MAX_AGE, "-1");
        set(TOTAL_MAX, "-1");

        WeblogEntry entry = TestUtils.setupWeblogEntry("swept", TestUtils.getManagedWebsite(weblog),
                TestUtils.getManagedUser(user));
        entryId = entry.getId();
        TestUtils.endSession(true);
        pageId = newPage(weblog, "swept");
    }

    @AfterEach
    void tearDown() throws Exception {
        set("revisions.coalesce.minutes", "10");
        set(ENTRY_RETENTION, "-1");
        set(PAGE_RETENTION, "50");
        set(MAX_AGE, "-1");
        set(TOTAL_MAX, "-1");
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownWeblog(otherWeblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------- the count

    @Test
    void theSweepAppliesTheCountToItemsNotSavedSince() throws Exception {
        editEntry(5);
        editPage(pageId, 5);
        raw("UPDATE weblogentry_revision SET kept = true WHERE entryid = ? AND text = 'entry v0'", entryId);
        set(ENTRY_RETENTION, "2");
        set(PAGE_RETENTION, "2");

        RevisionSweep.Report report = sweeper().sweep(false);

        assertEquals(List.of("entry v4", "entry v3", "entry v0"), entryTexts(), "two unkept and the kept one");
        assertEquals(List.of("page v4", "page v3"), pageContents(pageId));
        assertTrue(report.byCount() >= 5, "two entry rows and three page rows went by count: " + report);
        assertFalse(report.dryRun());
    }

    // --------------------------------------------------------------- the age

    @Test
    void ageRemovesOldRevisionsButNeverAnItemsNewestFive() throws Exception {
        editPage(pageId, 8);
        ageDays("pressroom_weblogpage_revision", "pageid", pageId, 60);
        set(MAX_AGE, "30");

        sweeper().sweep(false);

        assertEquals(List.of("page v7", "page v6", "page v5", "page v4", "page v3"), pageContents(pageId));
    }

    @Test
    void ageKeepsEveryRevisionOfAnItemWithFewerThanFive() throws Exception {
        editEntry(3);
        ageDays("weblogentry_revision", "entryid", entryId, 400);
        set(MAX_AGE, "30");

        sweeper().sweep(false);

        assertEquals(3, entryTexts().size());
    }

    @Test
    void ageNeverRemovesAKeptRevision() throws Exception {
        editPage(pageId, 8);
        raw("UPDATE pressroom_weblogpage_revision SET kept = true WHERE pageid = ? AND content = 'page v0'", pageId);
        ageDays("pressroom_weblogpage_revision", "pageid", pageId, 60);
        set(MAX_AGE, "30");

        sweeper().sweep(false);

        assertEquals(List.of("page v7", "page v6", "page v5", "page v4", "page v3", "page v0"), pageContents(pageId));
    }

    @Test
    void aRevisionYoungerThanTheLimitStays() throws Exception {
        editPage(pageId, 8);
        set(MAX_AGE, "30");

        sweeper().sweep(false);

        assertEquals(8, pageContents(pageId).size());
    }

    // ----------------------------------------------------------- the ceiling

    @Test
    void theCeilingRemovesOldestFirstAcrossPostsAndPages() throws Exception {
        editEntry(9);
        editPage(pageId, 9);
        // This test's rows are the oldest on the site, so they are what goes.
        ageDays("weblogentry_revision", "entryid", entryId, 900);
        ageDays("pressroom_weblogpage_revision", "pageid", pageId, 900);
        long total = total();
        set(TOTAL_MAX, Long.toString(total - 6));

        RevisionSweep.Report report = sweeper().sweep(false);

        assertEquals(total - 6, total());
        assertEquals(6, report.byCeiling());
        assertFalse(report.ceilingUnmeetable());
        // 18 rows, 8 unprotected (4 each); the 6 oldest went, alternating by time.
        assertTrue(entryTexts().size() >= 5 && pageContents(pageId).size() >= 5);
        assertEquals(12, entryTexts().size() + pageContents(pageId).size());
        assertEquals("entry v8", entryTexts().get(0));
        assertEquals("page v8", pageContents(pageId).get(0));
    }

    /** Floors and kept revisions outrank the ceiling: it takes what it may, says so, and returns. */
    @Test
    void anUnmeetableCeilingRemovesWhatItMayAndSaysSo() throws Exception {
        editEntry(7);
        raw("UPDATE weblogentry_revision SET kept = true WHERE entryid = ? AND text = 'entry v0'", entryId);
        set(TOTAL_MAX, "1");

        RevisionSweep.Report report = sweeper().sweep(false);

        assertTrue(report.ceilingUnmeetable());
        assertEquals(List.of("entry v6", "entry v5", "entry v4", "entry v3", "entry v2", "entry v0"), entryTexts(),
                "the newest five and the kept one survive; only v1 was removable");
    }

    // --------------------------------------------------------------- dry run

    @Test
    void aDryRunRemovesNothingAndPredictsTheSweep() throws Exception {
        editEntry(9);
        editPage(pageId, 9);
        ageDays("pressroom_weblogpage_revision", "pageid", pageId, 60);
        set(ENTRY_RETENTION, "6");
        set(MAX_AGE, "30");
        long before = total();

        RevisionSweep.Report predicted = sweeper().sweep(true);

        assertTrue(predicted.dryRun());
        assertEquals(before, total(), "a dry run removes nothing");

        RevisionSweep.Report done = sweeper().sweep(false);

        assertEquals(predicted.byCount(), done.byCount());
        assertEquals(predicted.byAge(), done.byAge());
        assertEquals(predicted.byCeiling(), done.byCeiling());
        assertEquals(before - done.total(), total());
        assertEquals(6, entryTexts().size());
        assertEquals(5, pageContents(pageId).size());
    }

    @Test
    void aDryRunPredictsTheCeilingAfterTheOtherRules() throws Exception {
        editEntry(9);
        ageDays("weblogentry_revision", "entryid", entryId, 900);
        long total = total();
        set(TOTAL_MAX, Long.toString(total - 3));

        RevisionSweep.Report predicted = sweeper().sweep(true);

        assertEquals(3, predicted.byCeiling());
        assertFalse(predicted.ceilingUnmeetable());
        assertEquals(total, total());
    }

    /** As shipped for posts (no count, no age, no ceiling) the sweep removes nothing: an upgrade keeps all history. */
    @Test
    void withEveryLimitOffTheSweepRemovesNothing() throws Exception {
        editEntry(9);
        editPage(pageId, 9);
        ageDays("weblogentry_revision", "entryid", entryId, 4000);

        RevisionSweep.Report report = sweeper().sweep(false);

        assertEquals(0, report.total());
        assertEquals(9, entryTexts().size());
        assertEquals(9, pageContents(pageId).size());
    }

    // ------------------------------------------------------------ fail closed

    @Test
    void aFailureInOneWeblogLeavesItUntouchedAndSweepsTheOthers() throws Exception {
        String otherPage = newPage(otherWeblog, "other-swept");
        editPage(pageId, 5);
        editPage(otherPage, 5);
        set(PAGE_RETENTION, "2");
        String failing = weblog.getId();
        RevisionSweeper sweeper = new RevisionSweeper(emf()) {
            @Override
            long[] sweepWeblog(EntityManager em, String weblogId, boolean dryRun, Limits limits) {
                if (failing.equals(weblogId)) {
                    em.createNativeQuery("DELETE FROM pressroom_weblogpage_revision WHERE pageid = ?1")
                            .setParameter(1, pageId).executeUpdate();
                    throw new IllegalStateException("boom, after removing rows in this weblog's transaction");
                }
                return super.sweepWeblog(em, weblogId, dryRun, limits);
            }
        };

        RevisionSweep.Report report = sweeper.sweep(false);

        assertEquals(1, report.weblogsFailed());
        assertEquals(5, pageContents(pageId).size(), "the failed weblog's transaction rolled back whole");
        assertEquals(2, pageContents(otherPage).size(), "another weblog was still swept");
    }

    /** The batch that throws rolls back whole; here it is the only one, so nothing goes. */
    @Test
    void aFailingCeilingBatchRollsBack() throws Exception {
        editEntry(9);
        ageDays("weblogentry_revision", "entryid", entryId, 900);
        long total = total();
        set(TOTAL_MAX, Long.toString(total - 3));
        RevisionSweeper sweeper = new RevisionSweeper(emf()) {
            @Override
            long sweepCeilingBatch(EntityManager em, long excess) {
                super.sweepCeilingBatch(em, excess);
                throw new IllegalStateException("boom, after removing a batch in its transaction");
            }
        };

        RevisionSweep.Report report = sweeper.sweep(false);

        assertEquals(total, total());
        assertEquals(0, report.byCeiling());
        assertTrue(report.ceilingFailed());
    }

    /**
     * Each ceiling batch is its own transaction, so a later batch failing
     * leaves the earlier ones removed. They must be in the report, and the
     * removed rows must not be served from the shared cache afterwards.
     */
    @Test
    void aFailureInALaterCeilingBatchKeepsEarlierBatchesAndReportsThem() throws Exception {
        editEntry(9);
        ageDays("weblogentry_revision", "entryid", entryId, 900);
        List<String> ids = entries().getRevisions(entries().getWeblogEntry(entryId)).stream()
                .map(r -> r.getId()).toList();
        String oldest = ids.get(ids.size() - 1);
        TestUtils.endSession(true);
        long total = total();
        set(TOTAL_MAX, Long.toString(total - 4));
        int[] calls = {0};
        RevisionSweeper sweeper = new RevisionSweeper(emf()) {
            @Override
            int batchSize() {
                return 2;
            }

            @Override
            long sweepCeilingBatch(EntityManager em, long excess) {
                long removed = super.sweepCeilingBatch(em, excess);
                if (++calls[0] == 2) {
                    throw new IllegalStateException("boom, in the second batch");
                }
                return removed;
            }
        };

        RevisionSweep.Report report = sweeper.sweep(false);

        assertTrue(report.ceilingFailed());
        assertEquals(2, report.byCeiling(), "the committed first batch is reported");
        assertEquals(total - 2, total(), "the second batch rolled back; the first stays removed");
        assertNull(entries().getRevision(oldest), "a removed revision is not served from the cache");
    }

    /** The delete itself refuses a kept row, whatever the select before it saw. */
    @Test
    void theCeilingsDeleteNeverTakesAKeptRevisionEvenIfItsIdWasSelected() throws Exception {
        editEntry(3);
        List<String> ids = entries().getRevisions(entries().getWeblogEntry(entryId)).stream()
                .map(r -> r.getId()).toList();
        TestUtils.endSession(true);
        raw("UPDATE weblogentry_revision SET kept = true WHERE id = ?", ids.get(2));
        RevisionSweeper sweeper = sweeper();
        EntityManager em = emf().createEntityManager();
        em.getTransaction().begin();
        long removed;
        try {
            removed = sweeper.deleteUnkept(em, RevisionRetention.Kind.ENTRY, ids);
            em.getTransaction().commit();
        } finally {
            em.close();
        }
        emf().getCache().evictAll();

        assertEquals(2, removed);
        assertEquals(List.of("entry v0"), entryTexts(), "only the kept one is left");
    }

    /** A dry run that could not evaluate a weblog says so; it is not a prediction of zero. */
    @Test
    void aDryRunThatFailsForAWeblogSaysSoInItsReport() throws Exception {
        RevisionSweeper sweeper = new RevisionSweeper(emf()) {
            @Override
            long[] sweepWeblog(EntityManager em, String weblogId, boolean dryRun, Limits limits) {
                throw new IllegalStateException("boom");
            }
        };

        RevisionSweep.Report report = sweeper.sweep(true);

        assertTrue(report.weblogsFailed() > 0);
        assertTrue(report.incomplete());
    }

    // ----------------------------------------------------------------- stats

    @Test
    void statsEqualTheTablesRowCounts() throws Exception {
        editEntry(12);
        editPage(pageId, 4);
        raw("UPDATE weblogentry_revision SET kept = true WHERE entryid = ? AND text = 'entry v0'", entryId);

        RevisionSweep.Stats stats = sweeper().stats();

        assertEquals(count("SELECT count(*) FROM weblogentry_revision"), stats.entryRevisions());
        assertEquals(count("SELECT count(*) FROM pressroom_weblogpage_revision"), stats.pageRevisions());
        assertEquals(count("SELECT (SELECT count(*) FROM weblogentry_revision WHERE kept) "
                + "+ (SELECT count(*) FROM pressroom_weblogpage_revision WHERE kept)"), stats.kept());
        assertTrue(stats.approximateBytes() > 0);
        assertNotNull(stats.oldest());
        assertTrue(stats.largest().size() <= 10);
        for (int i = 1; i < stats.largest().size(); i++) {
            assertTrue(stats.largest().get(i - 1).revisions() >= stats.largest().get(i).revisions(), "most first");
        }
        assertTrue(stats.largest().stream().anyMatch(item -> entryId.equals(item.id()) && item.revisions() == 12
                && item.kind() == RevisionSweep.ItemKind.ENTRY && item.title() != null),
                "the entry with twelve revisions is listed: " + stats.largest());
        assertNotNull(stats.nextSweep());
        assertTrue(stats.nextSweep().dryRun());
    }

    @Test
    void theLastSweepIsRememberedAndADryRunDoesNotReplaceIt() throws Exception {
        RevisionSweeper sweeper = sweeper();
        assertNull(sweeper.stats().lastSweep(), "nothing swept by this sweeper yet");
        editPage(pageId, 5);
        set(PAGE_RETENTION, "2");

        RevisionSweep.Report done = sweeper.sweep(false);
        sweeper.sweep(true);

        assertEquals(done, sweeper.stats().lastSweep());
        assertNotNull(done.at());
    }

    // ---------------------------------------------------------------- support

    private static WeblogEntryManager entries() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    private static WeblogPageManager pages() {
        return TestUtils.weblogger().getWeblogPageManager();
    }

    private static JPAPersistenceStrategy strategy() throws Exception {
        Field f = JPAWeblogEntryManagerImpl.class.getDeclaredField("strategy");
        f.setAccessible(true);
        return (JPAPersistenceStrategy) f.get(entries());
    }

    private static EntityManagerFactory emf() throws Exception {
        return strategy().getEntityManager(false).getEntityManagerFactory();
    }

    private static RevisionSweeper sweeper() throws Exception {
        TestUtils.endSession(true);
        return new RevisionSweeper(emf());
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

    private String newPage(Weblog owner, String slug) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(owner));
        page.setSlug(slug);
        page.setTitle("Title of " + slug);
        page.setContent("page v0");
        pages().savePage(page);
        commit();
        return page.getId();
    }

    /** Leaves {@code n} revisions on the entry, holding "entry v0" .. "entry v(n-1)". */
    private void editEntry(int n) throws Exception {
        WeblogEntry first = entries().getWeblogEntry(entryId);
        first.setText("entry v0");
        entries().saveWeblogEntry(first);
        commit();
        raw("DELETE FROM weblogentry_revision WHERE entryid = ?", entryId);
        for (int i = 1; i <= n; i++) {
            WeblogEntry entry = entries().getWeblogEntry(entryId);
            entry.setText("entry v" + i);
            entries().saveWeblogEntry(entry);
            commit();
        }
    }

    /** Leaves {@code n} revisions on the page, holding "page v0" .. "page v(n-1)". */
    private void editPage(String id, int n) throws Exception {
        for (int i = 1; i <= n; i++) {
            WeblogPage page = pages().getPage(id);
            page.setContent("page v" + i);
            pages().savePage(page);
            commit();
        }
    }

    private List<String> entryTexts() throws Exception {
        TestUtils.endSession(true);
        return entries().getRevisions(entries().getWeblogEntry(entryId)).stream().map(r -> r.getText()).toList();
    }

    private List<String> pageContents(String id) throws Exception {
        TestUtils.endSession(true);
        return pages().getRevisions(pages().getPage(id)).stream().map(r -> r.getContent()).toList();
    }

    private static long total() throws Exception {
        return count("SELECT (SELECT count(*) FROM weblogentry_revision) "
                + "+ (SELECT count(*) FROM pressroom_weblogpage_revision)");
    }

    private static long count(String sql) throws Exception {
        TestUtils.endSession(true);
        Number n = (Number) strategy().getEntityManager(false).createNativeQuery(sql).getSingleResult();
        TestUtils.endSession(true);
        return n.longValue();
    }

    private static void ageDays(String table, String ownerColumn, String id, int days) throws Exception {
        raw("UPDATE " + table + " SET created = created - make_interval(days => " + days + ") WHERE "
                + ownerColumn + " = ?", id);
    }

    private static void raw(String sql, String id) throws Exception {
        Query update = strategy().getEntityManager(true).createNativeQuery(sql);
        update.setParameter(1, id);
        update.executeUpdate();
        commit();
        strategy().getEntityManager(false).getEntityManagerFactory().getCache().evictAll();
        TestUtils.endSession(true);
    }
}
