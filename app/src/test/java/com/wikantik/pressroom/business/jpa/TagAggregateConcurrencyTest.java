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
package com.wikantik.pressroom.business.jpa;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.TagStat;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogEntryTagAggregate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static com.wikantik.pressroom.business.jpa.TagAggregateRows.assertAggregateIsTrue;
import static com.wikantik.pressroom.business.jpa.TagAggregateRows.connection;
import static com.wikantik.pressroom.business.jpa.TagAggregateRows.storedTotals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The tag aggregate table ({@code pressroom_weblogentrytagagg}) under concurrent
 * entry saves, against the real PostgreSQL.
 *
 * <p>Each worker runs on its own thread, so it gets its own thread-local
 * EntityManager and its own database transaction -- exactly as concurrent
 * requests do. A {@link CyclicBarrier} releases the workers together so their
 * transactions overlap; several rounds make a collision certain rather than
 * likely. Before the fix (an unlocked select-then-insert-or-update), the
 * first test failed with {@code duplicate key value violates unique
 * constraint "weta_weblog_tag_uq"} and the second lost most of its
 * increments.
 *
 * <p>Every assertion compares the stored totals with the true count, read
 * straight from the entries and tags (published entries only), and requires
 * exactly one row per (weblog, tag) and per site-wide tag.
 */
class TagAggregateConcurrencyTest {

    private static final int WORKERS = 8;
    private static final int ROUNDS = 6;

    private User user;
    private Weblog weblog;
    private Weblog otherWeblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("tagrace");
        weblog = TestUtils.setupWeblog("tagraceblog", user);
        otherWeblog = TestUtils.setupWeblog("tagraceother", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownWeblog(otherWeblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------------ A

    @Test
    @Timeout(180)
    void concurrentFirstUsesOfANewTagAllSucceed() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String tag = "fresh" + round;
            List<Throwable> failures = inParallel(WORKERS,
                    worker -> publish(weblog.getId(), "fresh-" + worker, tag));
            assertNoFailures(failures, "round " + round);
            assertAggregateIsTrue(tag, weblog);
            assertEquals(WORKERS, storedTotal(weblog.getId(), tag));
        }
    }

    // ------------------------------------------------------------------ B

    @Test
    @Timeout(180)
    void concurrentIncrementsOfAnExistingTagAreNotLost() throws Exception {
        publish(weblog.getId(), "seed", "shared");
        for (int round = 0; round < ROUNDS; round++) {
            assertNoFailures(inParallel(WORKERS,
                    worker -> publish(weblog.getId(), "inc-" + worker, "shared")), "round " + round);
        }
        assertAggregateIsTrue("shared", weblog);
        assertEquals(1 + WORKERS * ROUNDS, storedTotal(weblog.getId(), "shared"));
        assertEquals(1 + WORKERS * ROUNDS, storedTotal(null, "shared"));
    }

    @Test
    @Timeout(180)
    void concurrentIncrementsAndDecrementsAcrossTwoWeblogsLandOnTheTrueCount() throws Exception {
        List<String> seeded = new ArrayList<>();
        for (int i = 0; i < WORKERS * ROUNDS; i++) {
            seeded.add(publish(i % 2 == 0 ? weblog.getId() : otherWeblog.getId(), "mixseed-" + i, "mix", "keep"));
        }

        for (int round = 0; round < ROUNDS; round++) {
            int base = round * WORKERS;
            List<Throwable> failures = inParallel(WORKERS, worker -> {
                String seededId = seeded.get(base + worker);
                switch (worker % 4) {
                    // a new entry on either weblog: +1
                    case 0 -> publish(weblog.getId(), "mixnew-" + worker, "mix");
                    case 1 -> publish(otherWeblog.getId(), "mixnew-" + worker, "mix");
                    // the tag taken off an existing entry: -1
                    case 2 -> retag(seededId, "keep");
                    // an existing entry trashed: -1 for every tag it carries
                    default -> trash(seededId);
                }
            });
            assertNoFailures(failures, "round " + round);
        }

        assertAggregateIsTrue("mix", weblog, otherWeblog);
        assertAggregateIsTrue("keep", weblog, otherWeblog);
    }

    /**
     * Saves of the SAME entry, each loaded before any of them committed (a
     * double-clicked publish, an API PATCH racing the editor, the scheduler
     * promoting an entry the author is publishing). Each saw the entry as a
     * draft, so a count derived from what each had loaded was added once per
     * save.
     */
    @Test
    @Timeout(120)
    void concurrentPublishesOfOneDraftCountItOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            String tag = "same" + round;
            String draft = create(weblog.getId(), PubStatus.DRAFT, "same", tag);
            CyclicBarrier loaded = new CyclicBarrier(4);
            assertNoFailures(inParallel(4, worker -> {
                WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
                WeblogEntry entry = mgr.getWeblogEntry(draft);
                loaded.await(30, TimeUnit.SECONDS);
                entry.setStatus(PubStatus.PUBLISHED);
                mgr.saveWeblogEntry(entry);
            }), "round " + round);
            assertAggregateIsTrue(tag, weblog);
            assertEquals(1, storedTotal(weblog.getId(), tag));
        }
    }

    /** Trash racing an unpublish of the same entry: uncounted once, not twice. */
    @Test
    @Timeout(120)
    void aTrashRacingAnUnpublishOfOneEntryUncountsItOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            String tag = "gone" + round;
            publish(weblog.getId(), "stays", tag);
            String target = publish(weblog.getId(), "goes", tag);
            CyclicBarrier loaded = new CyclicBarrier(2);
            assertNoFailures(inParallel(2, worker -> {
                WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
                WeblogEntry entry = mgr.getWeblogEntry(target);
                loaded.await(30, TimeUnit.SECONDS);
                if (worker == 0) {
                    mgr.trashWeblogEntry(entry);
                } else {
                    entry.setStatus(PubStatus.DRAFT);
                    mgr.saveWeblogEntry(entry);
                }
            }), "round " + round);
            assertAggregateIsTrue(tag, weblog);
            assertEquals(1, storedTotal(weblog.getId(), tag));
        }
    }

    /**
     * One transaction saving several entries (ScheduledEntriesTask, a bulk
     * publish) touches tag b, then tag a; meanwhile a single save touches a
     * and b. Taking each tag's row lock as each entry was saved made that a
     * lock cycle. The multi-entry transaction goes on only once the single
     * save is waiting on it (or after two seconds, if it never waits).
     *
     * <p>The single save edits an existing entry: a second CREATE in the
     * weblog would wait on the weblog's anchor lock (JPAWeblogEntryManagerImpl.
     * claimAnchor) before touching any tag, and never overlap at the tag rows.
     */
    @Test
    @Timeout(120)
    void aMultiEntryTransactionAndAnOverlappingSaveDoNotDeadlock() throws Exception {
        String existing = publish(weblog.getId(), "single");
        CountDownLatch firstSaved = new CountDownLatch(1);
        assertNoFailures(inParallel(2, worker -> {
            WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
            if (worker == 0) {
                mgr.saveWeblogEntry(newEntry(weblog.getId(), PubStatus.PUBLISHED, "multi1", "dlb"));
                firstSaved.countDown();
                awaitASessionWaitingOnALock(Duration.ofSeconds(2));
                mgr.saveWeblogEntry(newEntry(weblog.getId(), PubStatus.PUBLISHED, "multi2", "dla"));
            } else {
                firstSaved.await(30, TimeUnit.SECONDS);
                retag(existing, "dla dlb");
            }
        }), "multi-entry vs single save");
        assertAggregateIsTrue("dla", weblog);
        assertAggregateIsTrue("dlb", weblog);
        assertEquals(2, storedTotal(weblog.getId(), "dla"));
    }

    /**
     * The site-wide row is the sum of the per-weblog rows, so it is exact
     * only if whichever transaction writes it last sees every other weblog's
     * row as committed. Two saves on different weblogs each update their own
     * weblog row, then both queue on the site-wide row (held here by a third
     * session so the queue is certain, not likely). The first through sums
     * the other's row as last committed; the second, if it summed in the
     * statement that waited for the lock, would sum from a snapshot taken
     * before the first committed and lose its entry. It must sum in a later
     * statement, whose snapshot includes the first.
     *
     * <p>Characterisation: passes against the entry-rescanning recount too;
     * pins that the sum keeps the same lock-then-read shape.
     */
    @Test
    @Timeout(120)
    void savesOnTwoWeblogsQueuedOnTheSiteWideRowEachCountTheOther() throws Exception {
        publish(weblog.getId(), "queueseed", "queued");
        try (Connection holder = connection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT total FROM pressroom_weblogentrytagagg WHERE websiteid IS NULL AND name = 'queued' FOR UPDATE")) {
                lock.executeQuery().close();
            }
            List<Throwable> failures = inParallel(3, worker -> {
                switch (worker) {
                    case 0 -> publish(weblog.getId(), "queue-a", "queued");
                    case 1 -> publish(otherWeblog.getId(), "queue-b", "queued");
                    default -> {
                        awaitSessionsBlockedBy(holder, 2, Duration.ofSeconds(30));
                        holder.commit();
                    }
                }
            });
            assertNoFailures(failures, "two weblogs queued on the site-wide row");
        }
        assertAggregateIsTrue("queued", weblog, otherWeblog);
        assertEquals(3, storedTotal(null, "queued"));
    }

    /**
     * Deleting a weblog takes its rows out of every site-wide sum, including
     * a row a concurrent save committed after the deletion started. Here a
     * third session publishes one of the weblog's drafts, exactly as the
     * store would (entry, then each tag's weblog row and site-wide row), and
     * commits only once the deletion is waiting on it. The draft's
     * {@code late} tag had no row when the deletion began; a deletion that
     * took its tag names, or deleted the weblog's rows, before then would
     * leave {@code late}'s rows counting an entry that no longer exists.
     */
    @Test
    @Timeout(120)
    void aDraftPublishedWhileItsWeblogIsDeletedLeavesNoCount() throws Exception {
        Weblog doomed = TestUtils.setupWeblog("tagracedoomed", user);
        TestUtils.endSession(true);
        String doomedId = doomed.getId();
        publish(doomedId, "held", "held");
        String draft = create(doomedId, PubStatus.DRAFT, "late", "held", "late");

        try (Connection publisher = connection()) {
            publisher.setAutoCommit(false);
            publishBehindTheStoresBack(publisher, draft, doomedId);
            List<Throwable> failures = inParallel(2, worker -> {
                if (worker == 0) {
                    WeblogManager weblogs = TestUtils.weblogger().getWeblogManager();
                    weblogs.removeWeblog(weblogs.getWeblog(doomedId));
                } else {
                    awaitSessionsBlockedBy(publisher, 1, Duration.ofSeconds(30));
                    publisher.commit();
                }
            });
            assertNoFailures(failures, "weblog deletion racing a publish");
        }
        try {
            assertFalse(weblogExists(doomedId), "the deletion committed");
            assertEquals(List.of(), storedTotals(doomedId, "late"), "the deleted weblog's row");
            assertEquals(List.of(), storedTotals(doomedId, "held"), "the deleted weblog's row");
            assertAggregateIsTrue("late", weblog, otherWeblog);
            assertAggregateIsTrue("held", weblog, otherWeblog);
        } finally {
            // on failure, leave no rows behind for the suites that start from none
            try (Connection con = connection(); Statement st = con.createStatement()) {
                st.executeUpdate("DELETE FROM pressroom_weblogentrytagagg WHERE name IN ('late', 'held')");
            }
        }
    }

    /**
     * What a commit publishing {@code draft} (tagged held and late) writes,
     * in the store's order, left uncommitted on {@code publisher}.
     */
    private static void publishBehindTheStoresBack(Connection publisher, String draft, String weblogId)
            throws SQLException {
        try (PreparedStatement ps = publisher.prepareStatement(
                "UPDATE weblogentry SET status = 'PUBLISHED' WHERE id = ?")) {
            ps.setString(1, draft);
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps = publisher.prepareStatement(
                "UPDATE pressroom_weblogentrytagagg SET total = total + 1 "
                        + "WHERE websiteid IS NOT DISTINCT FROM ? AND name = 'held'")) {
            for (String id : new String[] {weblogId, null}) {
                ps.setString(1, id);
                assertEquals(1, ps.executeUpdate());
            }
        }
        try (PreparedStatement ps = publisher.prepareStatement(
                "INSERT INTO pressroom_weblogentrytagagg (id, websiteid, name, total, lastused) "
                        + "VALUES (?, ?, 'late', 1, now())")) {
            for (String id : new String[] {weblogId, null}) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, id);
                ps.executeUpdate();
            }
        }
    }

    private static boolean weblogExists(String id) throws SQLException {
        try (Connection con = connection();
             PreparedStatement ps = con.prepareStatement("SELECT 1 FROM weblog WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Waits, up to {@code atMost}, for some session to be waiting on a lock;
     * goes on regardless once the time is up (the caller's interleaving is
     * a best effort, not a precondition).
     */
    private static void awaitASessionWaitingOnALock(Duration atMost) throws Exception {
        long deadline = System.nanoTime() + atMost.toNanos();
        try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity "
                        + "WHERE datname = current_database() AND wait_event_type = 'Lock'")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        }
    }

    /**
     * Waits until {@code sessions} sessions are blocked behind {@code holder}'s
     * transaction -- directly, or queued behind a session that is (a second
     * waiter for a row waits on the first waiter's tuple lock) -- and fails
     * at {@code atMost}: a test whose interleaving never happened has not
     * tested it.
     */
    private static void awaitSessionsBlockedBy(Connection holder, int sessions, Duration atMost)
            throws Exception {
        int holderPid;
        try (Statement st = holder.createStatement(); ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            holderPid = rs.getInt(1);
        }
        long deadline = System.nanoTime() + atMost.toNanos();
        int blocked = 0;
        try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity w "
                        + "WHERE w.wait_event_type = 'Lock' AND (? = ANY(pg_blocking_pids(w.pid)) "
                        + "OR EXISTS (SELECT 1 FROM unnest(pg_blocking_pids(w.pid)) b(pid) "
                        + "WHERE ? = ANY(pg_blocking_pids(b.pid))))")) {
            ps.setInt(1, holderPid);
            ps.setInt(2, holderPid);
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    blocked = rs.getInt(1);
                    if (blocked >= sessions) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        }
        fail(blocked + " of " + sessions + " sessions blocked behind the holder after " + atMost);
    }

    // ------------------------------------------------------------------ C

    @Test
    void theDatabaseRefusesASecondSiteWideRowForTheSameTag() throws Exception {
        String insert = "INSERT INTO pressroom_weblogentrytagagg (id, websiteid, name, total, lastused) "
                + "VALUES (?, NULL, 'sitedup', 1, now())";
        try (Connection con = connection()) {
            try (PreparedStatement first = con.prepareStatement(insert)) {
                first.setString(1, UUID.randomUUID().toString());
                first.executeUpdate();
            }
            try (PreparedStatement second = con.prepareStatement(insert)) {
                second.setString(1, UUID.randomUUID().toString());
                SQLException refused = assertThrows(SQLException.class, second::executeUpdate);
                assertEquals("23505", refused.getSQLState(), refused.getMessage());
            } finally {
                try (Statement st = con.createStatement()) {
                    st.executeUpdate("DELETE FROM pressroom_weblogentrytagagg WHERE name = 'sitedup'");
                }
            }
        }
    }

    // ------------------------------------------------------------------ D

    @Test
    @Timeout(120)
    void theManagersTagReadsSeeEveryConcurrentChangeAtOnce() throws Exception {
        WeblogEntryManager entries = TestUtils.weblogger().getWeblogEntryManager();
        String seed = publish(weblog.getId(), "readseed", "readtag");

        // Read through the manager first, so anything a cache could hold is warm.
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        assertEquals(1, countOf(entries.getTags(managed, null, null, 0, -1), "readtag"));
        assertEquals(1, countOf(entries.getTags(null, null, null, 0, -1), "readtag"));
        assertEquals(1, countOf(entries.getPopularTags(managed, null, 0, -1), "readtag"));
        assertTrue(entries.getTagComboExists(List.of("readtag"), managed));
        TestUtils.endSession(false);

        assertNoFailures(inParallel(WORKERS, worker -> publish(weblog.getId(), "read-" + worker, "readtag")),
                "increments");

        managed = TestUtils.getManagedWebsite(weblog);
        assertEquals(1 + WORKERS, countOf(entries.getTags(managed, null, null, 0, -1), "readtag"));
        assertEquals(1 + WORKERS, countOf(entries.getTags(null, null, null, 0, -1), "readtag"));
        assertEquals(1 + WORKERS, countOf(entries.getPopularTags(managed, null, 0, -1), "readtag"));
        assertEquals(1 + WORKERS, countOf(entries.getPopularTags(null, null, 0, -1), "readtag"));
        TestUtils.endSession(false);

        // Take the tag off every entry, the seed included: the rows must go.
        List<String> ids = entryIdsTagged("readtag");
        assertNoFailures(inParallel(ids.size(), worker -> retag(ids.get(worker), "other")), "decrements");
        assertTrue(ids.contains(seed));

        managed = TestUtils.getManagedWebsite(weblog);
        assertEquals(0, countOf(entries.getTags(managed, null, null, 0, -1), "readtag"));
        assertEquals(0, countOf(entries.getTags(null, null, null, 0, -1), "readtag"));
        assertFalse(entries.getTagComboExists(List.of("readtag"), managed));
        assertFalse(entries.getTagComboExists(List.of("readtag"), null));
        assertEquals(List.of(), storedTotals(weblog.getId(), "readtag"), "the weblog row is deleted at zero");
        assertEquals(List.of(), storedTotals(null, "readtag"), "the site-wide row is deleted at zero");
        TestUtils.endSession(false);
    }

    /**
     * The store writes with native SQL, which EclipseLink's shared cache never
     * sees. Were the entity cached, the second read below would be answered
     * from the instance the first read cached, carrying the old total.
     */
    @Test
    @Timeout(120)
    void anAggregateReadAsAnEntityIsNeverServedStaleFromTheSharedCache() throws Exception {
        publish(weblog.getId(), "cacheseed", "cachetag");
        JPAPersistenceStrategy strategy = liveStrategy();

        WeblogEntryTagAggregate first = siteRow(strategy, "cachetag");
        assertEquals(1, first.getTotal());
        TestUtils.endSession(false);
        assertFalse(strategy.getEntityManager(false).getEntityManagerFactory().getCache()
                .contains(WeblogEntryTagAggregate.class, first.getId()));

        assertNoFailures(inParallel(2, worker -> publish(weblog.getId(), "cache-" + worker, "cachetag")),
                "increments");

        assertEquals(3, siteRow(strategy, "cachetag").getTotal());
        TestUtils.endSession(false);
    }

    private static WeblogEntryTagAggregate siteRow(JPAPersistenceStrategy strategy, String tag)
            throws Exception {
        return strategy.getDynamicQuery(
                "SELECT w FROM WeblogEntryTagAggregate w WHERE w.weblog IS NULL AND w.name = ?1",
                WeblogEntryTagAggregate.class).setParameter(1, tag).getSingleResult();
    }

    /** The bootstrapped tier's persistence strategy. */
    private static JPAPersistenceStrategy liveStrategy() throws ReflectiveOperationException {
        Field field = JPAWebloggerImpl.class.getDeclaredField("strategy");
        field.setAccessible(true);
        return (JPAPersistenceStrategy) field.get(TestUtils.weblogger());
    }

    // ------------------------------------------------------------- workers

    @FunctionalInterface
    private interface Worker {
        void run(int worker) throws Exception;
    }

    /**
     * Runs {@code count} workers on their own threads, released together, each
     * committing (or failing) its own transaction. Returns what they threw.
     */
    private static List<Throwable> inParallel(int count, Worker worker) throws Exception {
        CyclicBarrier start = new CyclicBarrier(count);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int n = i;
                done.add(pool.submit(() -> {
                    Weblogger tier = TestUtils.weblogger();
                    try {
                        start.await(30, TimeUnit.SECONDS);
                        worker.run(n);
                        tier.flush();
                    } catch (Exception | AssertionError e) {
                        failures.add(e);
                    } finally {
                        tier.release();
                    }
                }));
            }
            for (Future<?> f : done) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return failures;
    }

    /** Creates and saves a published entry carrying {@code tags}; returns its id. */
    private String publish(String weblogId, String anchorStem, String... tags) throws Exception {
        return create(weblogId, PubStatus.PUBLISHED, anchorStem, tags);
    }

    /** Creates, saves and commits an entry; returns its id. */
    private String create(String weblogId, PubStatus status, String anchorStem, String... tags)
            throws Exception {
        Weblogger tier = TestUtils.weblogger();
        WeblogEntry entry = newEntry(weblogId, status, anchorStem, tags);
        tier.getWeblogEntryManager().saveWeblogEntry(entry);
        tier.flush();
        return entry.getId();
    }

    private WeblogEntry newEntry(String weblogId, PubStatus status, String anchorStem, String... tags)
            throws Exception {
        Weblogger tier = TestUtils.weblogger();
        Weblog w = tier.getWeblogManager().getWeblog(weblogId);
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(w);
        entry.setTitle(anchorStem);
        entry.setText("text");
        entry.setAnchor(anchorStem + "-" + UUID.randomUUID());
        entry.setCreatorUserName(user.getUserName());
        Timestamp now = new Timestamp(System.currentTimeMillis());
        entry.setPubTime(now);
        entry.setUpdateTime(now);
        entry.setStatus(status);
        for (String tag : tags) {
            entry.addTag(tag);
        }
        return entry;
    }

    private static void retag(String entryId, String tags) throws Exception {
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry entry = mgr.getWeblogEntry(entryId);
        entry.setTagsAsString(tags);
        mgr.saveWeblogEntry(entry);
    }

    private static void trash(String entryId) throws Exception {
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        mgr.trashWeblogEntry(mgr.getWeblogEntry(entryId));
    }

    // ---------------------------------------------------------- assertions

    private static void assertNoFailures(List<Throwable> failures, String when) {
        if (!failures.isEmpty()) {
            Throwable first = failures.get(0);
            AssertionError error = new AssertionError(failures.size() + " of the concurrent saves failed ("
                    + when + "); first: " + rootMessage(first), first);
            throw error;
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    private static int storedTotal(String weblogId, String tag) throws SQLException {
        List<Integer> totals = storedTotals(weblogId, tag);
        if (totals.size() != 1) {
            fail("expected one aggregate row for " + tag + ", found " + totals);
        }
        return totals.get(0);
    }

    private static List<String> entryIdsTagged(String tag) throws SQLException {
        try (Connection con = connection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT DISTINCT entryid FROM pressroom_weblogentrytag WHERE name = ?")) {
            ps.setString(1, tag);
            List<String> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
            return ids;
        }
    }

    private static int countOf(List<TagStat> stats, String tag) {
        int count = 0;
        for (TagStat stat : stats) {
            if (stat.getName().equals(tag)) {
                count += stat.getCount();
            }
        }
        return count;
    }
}
