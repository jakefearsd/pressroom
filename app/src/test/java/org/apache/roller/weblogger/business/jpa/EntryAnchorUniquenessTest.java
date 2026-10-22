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
package org.apache.roller.weblogger.business.jpa;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.AnchorTakenException;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.config.StaticConfigOverride;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.apache.roller.weblogger.business.jpa.TagAggregateRows.connection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An entry's anchor (its permalink slug) is unique within its weblog, under
 * concurrent creates, against the real PostgreSQL.
 *
 * <p>Each worker runs on its own thread, so it gets its own thread-local
 * EntityManager and database transaction, as concurrent requests do; a
 * {@link CyclicBarrier} releases them together. Before the fix,
 * {@code createAnchor}'s check-then-insert let every worker of a round see the
 * same anchor free, so a round of eight same-titled creates committed several
 * entries with one anchor, and only one of them was reachable at it.
 */
class EntryAnchorUniquenessTest {

    private static final int WORKERS = 8;
    private static final int ROUNDS = 6;

    /** Startup property bounding the wait for a weblog's anchor lock, in seconds. */
    private static final String ANCHOR_LOCK_TIMEOUT = "weblogentry.anchor.lockTimeoutSeconds";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("anchorrace");
        weblog = TestUtils.setupWeblog("anchorraceblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------- generated anchors

    @Test
    @Timeout(180)
    void concurrentCreatesWithOneTitleAllSucceedWithDistinctAnchors() throws Exception {
        for (int r = 0; r < ROUNDS; r++) {
            String round = "round" + (char) ('a' + r);
            String base = "load-festival-" + round;
            List<Throwable> failures = inParallel(WORKERS, worker -> save(newEntry("Load festival " + round, null)));
            assertNoFailures(failures, round);

            List<String> expected = new ArrayList<>();
            expected.add(base);
            for (int n = 1; n < WORKERS; n++) {
                expected.add(base + n);
            }
            Collections.sort(expected);
            assertEquals(expected, anchorsLike(base),
                    round + ": every create gets its own anchor, in createAnchor's format");
        }
    }

    /**
     * The deterministic form of the race: the second create starts while the
     * first has allocated its anchor but not yet committed. It must wait for
     * that commit and then take the next suffix -- not see the anchor free.
     */
    @Test
    @Timeout(60)
    void aCreateWaitsForAnUncommittedCreateOfTheSameAnchorAndTakesTheNextSuffix() throws Exception {
        CountDownLatch firstSaved = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        assertNoFailures(inParallel(2, worker -> {
            if (worker == 0) {
                save(newEntry("Slow commit", null));
                firstSaved.countDown();
                secondStarted.await(30, TimeUnit.SECONDS);
                awaitASessionWaitingOnALock(Duration.ofSeconds(3));
            } else {
                firstSaved.await(30, TimeUnit.SECONDS);
                secondStarted.countDown();
                save(newEntry("Slow commit", null));
            }
        }), "an overlapping pair");

        assertEquals(List.of("slow-commit", "slow-commit1"), anchorsLike("slow-commit"));
    }

    /**
     * One transaction creating two entries claims the weblog's anchor lock
     * twice. Its commit must still end the database transaction: beginning it
     * a second time nested it in EclipseLink, the commit closed only the
     * inner level, and the connection stayed idle in transaction holding the
     * lock, stalling every later create of the weblog.
     */
    @Test
    @Timeout(60)
    void aTransactionCreatingTwoEntriesReleasesTheAnchorLockAtCommit() throws Exception {
        assertNoFailures(inParallel(1, worker -> {
            save(newEntry("First of two", null));
            save(newEntry("Second of two", "second-of-two"));
        }), "a two-entry transaction");

        try (Connection con = connection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "no advisory lock outlives its transaction");
        }
        assertEquals(List.of("first-of-two"), anchorsLike("first-of-two"));
    }

    /**
     * Two creates of one title in ONE transaction: the second's check cannot
     * see the first entry's row, which is not flushed until commit, so the
     * claims this transaction already made must be consulted too -- or both
     * take the same anchor and the unique index fails the commit.
     */
    @Test
    @Timeout(60)
    void oneTransactionCreatingTwoEntriesWithOneTitleGivesThemTwoAnchors() throws Exception {
        assertNoFailures(inParallel(1, worker -> {
            save(newEntry("Twice over", null));
            save(newEntry("Twice over", null));
        }), "one transaction, one title twice");

        assertEquals(List.of("twice-over", "twice-over1"), anchorsLike("twice-over"));
    }

    /** A chosen anchor an entry saved earlier in the same transaction claimed is refused at the save. */
    @Test
    @Timeout(60)
    void oneTransactionChoosingOneAnchorTwiceIsRefusedAtTheSecondSave() throws Exception {
        List<Throwable> failures = inParallel(1, worker -> {
            save(newEntry("Pair one", "pair"));
            save(newEntry("Pair two", "pair"));
        });

        assertEquals(1, failures.size());
        assertInstanceOf(AnchorTakenException.class, failures.get(0), "not a commit-time 500: " + failures.get(0));
        assertEquals(List.of(), anchorsLike("pair"), "the transaction rolled back");
    }

    /**
     * The wait for the weblog's anchor lock is bounded: a holder that never
     * lets go (a stuck request, a session idle in transaction) makes the next
     * create fail, and roll back, within the bound -- not hang its thread.
     */
    @Test
    @Timeout(60)
    void aCreateGivesUpWhenTheAnchorLockIsHeldBeyondTheBound() throws Exception {
        String handle = weblog.getHandle();
        StaticConfigOverride bound = StaticConfigOverride.set(ANCHOR_LOCK_TIMEOUT, "1");
        try (Connection holder = connection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                lock.setString(1, "weblogentry.anchor:" + weblog.getId());
                lock.executeQuery().close();
            }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                long started = System.nanoTime();
                Future<Throwable> outcome = pool.submit(() -> {
                    Weblogger tier = TestUtils.weblogger();
                    try {
                        save(newEntry("Stuck behind", null));
                        tier.flush();
                        return null;
                    } catch (Exception e) {
                        return e;
                    } finally {
                        tier.release();
                    }
                });
                Throwable failure = outcome.get(10, TimeUnit.SECONDS);
                long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

                assertInstanceOf(WebloggerException.class, failure, "the create must fail, not succeed");
                assertTrue(failure.getMessage().contains(handle), failure.getMessage());
                assertTrue(waited < 8000, "gave up after " + waited + " ms");
            } finally {
                holder.rollback();
                pool.shutdownNow();
            }
        } finally {
            bound.close();
        }
        assertEquals(List.of(), anchorsLike("stuck-behind"), "the failed create rolled back");
    }

    /**
     * A one-word title as long as the column (title is varchar(255) too) gives
     * an anchor that fits, and so does the suffixed anchor of its second use;
     * an untitled entry's anchor comes from its text, which has no limit.
     */
    @Test
    void anAnchorFromALongTitleOrTextFitsTheColumnSuffixIncluded() throws Exception {
        for (int i = 0; i < 2; i++) {
            save(newEntry("x".repeat(255), null));
            TestUtils.weblogger().flush();
            TestUtils.endSession(false);
        }

        assertEquals(List.of("x".repeat(254) + "1", "x".repeat(255)), anchorsLike("xxx"));

        WeblogEntry untitled = newEntry("", null);
        untitled.setText("y".repeat(300));
        save(untitled);
        TestUtils.weblogger().flush();
        TestUtils.endSession(false);
        assertEquals(List.of("y".repeat(255)), anchorsLike("yyy"));
    }

    /** A base cut to fit the column is never cut between the halves of a surrogate pair. */
    @Test
    void aCutBaseNeverSplitsASurrogatePair() {
        String base = "a".repeat(254) + "\uD83D\uDE00";

        assertEquals("a".repeat(254), JPAWeblogEntryManagerImpl.fitAnchor(base, ""));
        assertEquals("a".repeat(254) + "1", JPAWeblogEntryManagerImpl.fitAnchor(base, "1"));
    }

    // ---------------------------------------------------------- chosen anchors

    @Test
    @Timeout(180)
    void concurrentCreatesChoosingOneAnchorLeaveOneWinnerAndRefuseTheRest() throws Exception {
        for (int round = 0; round < 3; round++) {
            String anchor = "chosen-" + round;
            List<Throwable> failures = inParallel(WORKERS, worker -> save(newEntry("Chosen", anchor)));

            assertEquals(WORKERS - 1, failures.size(), "round " + round + ": exactly one create wins");
            for (Throwable failure : failures) {
                assertInstanceOf(AnchorTakenException.class, failure,
                        "round " + round + ": a lost race is the documented refusal, never a constraint "
                                + "violation: " + failure);
            }
            assertEquals(List.of(anchor), anchorsLike(anchor));
        }
    }

    @Test
    void aChosenAnchorAnotherEntryHoldsIsRefusedWithItsHoldersStatus() throws Exception {
        save(newEntry("Held", "held"));
        TestUtils.weblogger().flush();
        TestUtils.endSession(false);

        AnchorTakenException taken = assertThrows(AnchorTakenException.class,
                () -> save(newEntry("Held again", "held")));
        assertEquals(PubStatus.PUBLISHED, taken.getHolderStatus());
        TestUtils.endSession(false);

        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        mgr.trashWeblogEntry(mgr.getWeblogEntryByAnchor(TestUtils.getManagedWebsite(weblog), "held"));
        TestUtils.weblogger().flush();
        TestUtils.endSession(false);

        taken = assertThrows(AnchorTakenException.class, () -> save(newEntry("Held again", "held")));
        assertEquals(PubStatus.TRASHED, taken.getHolderStatus(), "a trashed entry still holds its anchor");
        TestUtils.endSession(false);
    }

    @Test
    void reSavingAnEntryWithItsOwnAnchorIsNotAClash() throws Exception {
        WeblogEntry entry = newEntry("Mine", "mine");
        save(entry);
        TestUtils.weblogger().flush();
        TestUtils.endSession(false);

        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry loaded = mgr.getWeblogEntry(entry.getId());
        loaded.setSummary("edited");
        mgr.saveWeblogEntry(loaded);
        TestUtils.weblogger().flush();
        TestUtils.endSession(false);

        assertEquals(List.of("mine"), anchorsLike("mine"));
    }

    // ------------------------------------------------------------ the database

    @Test
    void theDatabaseRefusesASecondEntryWithTheSameAnchorInOneWeblog() throws Exception {
        String category = TestUtils.getManagedWebsite(weblog).getWeblogCategories().iterator().next().getId();
        TestUtils.endSession(false);
        String insert = "INSERT INTO weblogentry (id, anchor, creator, title, text, updatetime, websiteid, "
                + "categoryid, status) VALUES (?, 'dbdup', 'anchorrace', 't', 'x', now(), ?, ?, 'DRAFT')";
        try (Connection con = connection()) {
            try (PreparedStatement first = con.prepareStatement(insert)) {
                first.setString(1, "dbdup-1");
                first.setString(2, weblog.getId());
                first.setString(3, category);
                first.executeUpdate();
            }
            try (PreparedStatement second = con.prepareStatement(insert)) {
                second.setString(1, "dbdup-2");
                second.setString(2, weblog.getId());
                second.setString(3, category);
                SQLException refused = assertThrows(SQLException.class, second::executeUpdate);
                assertEquals("23505", refused.getSQLState(), refused.getMessage());
            } finally {
                try (Statement st = con.createStatement()) {
                    st.executeUpdate("DELETE FROM weblogentry WHERE anchor = 'dbdup'");
                }
            }
        }
    }

    /**
     * The permalink lookup ({@code getByWebsite&AnchorOrderByPubTimeDesc}) and
     * createAnchor's check both ask for one (weblog, anchor). Before V035 that
     * was a scan of the weblog's entries ("Rows Removed by Filter: 3131" in
     * the 2026-10-22 profile); now the unique index answers it. Planned over
     * 3,200 entries in one weblog, inserted and rolled back in one
     * transaction, so the planner sees a realistic table.
     */
    @Test
    void anAnchorLookupIsServedByTheUniqueIndex() throws Exception {
        String category = TestUtils.getManagedWebsite(weblog).getWeblogCategories().iterator().next().getId();
        TestUtils.endSession(false);
        try (Connection con = connection()) {
            con.setAutoCommit(false);
            try (PreparedStatement fill = con.prepareStatement(
                    "INSERT INTO weblogentry (id, anchor, creator, title, text, updatetime, pubtime, websiteid, "
                            + "categoryid, status) SELECT 'plan-' || g, 'plan-' || g, 'anchorrace', 't', "
                            + "repeat('x', 500), now(), now() - g * interval '1 minute', ?, ?, 'PUBLISHED' "
                            + "FROM generate_series(1, 3200) g");
                 Statement st = con.createStatement()) {
                fill.setString(1, weblog.getId());
                fill.setString(2, category);
                fill.executeUpdate();
                st.execute("ANALYZE weblogentry");
                StringBuilder plan = new StringBuilder();
                try (ResultSet rs = st.executeQuery("EXPLAIN SELECT * FROM weblogentry WHERE websiteid = '"
                        + weblog.getId() + "' AND anchor = 'plan-1792' ORDER BY pubtime DESC")) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                    }
                }
                assertTrue(plan.toString().contains("Index Scan using we_website_anchor_uq"), plan.toString());
            } finally {
                con.rollback();
            }
        }
    }

    // ------------------------------------------------------------- workers

    @FunctionalInterface
    private interface Worker {
        void run(int worker) throws Exception;
    }

    /** Runs {@code count} workers on their own threads, released together; returns what they threw. */
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

    private static void save(WeblogEntry entry) throws Exception {
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(entry);
    }

    /** A published entry with this title; {@code anchor} null lets the manager generate one. */
    private WeblogEntry newEntry(String title, String anchor) throws Exception {
        Weblog w = TestUtils.weblogger().getWeblogManager().getWeblog(weblog.getId());
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(w);
        entry.setTitle(title);
        entry.setText("text");
        entry.setAnchor(anchor);
        entry.setCreatorUserName(user.getUserName());
        Timestamp now = new Timestamp(System.currentTimeMillis());
        entry.setPubTime(now);
        entry.setUpdateTime(now);
        entry.setStatus(PubStatus.PUBLISHED);
        return entry;
    }

    /** Every stored anchor of the weblog that is {@code base} or starts with it, sorted, duplicates included. */
    private List<String> anchorsLike(String base) throws SQLException {
        try (Connection con = connection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT anchor FROM weblogentry WHERE websiteid = ? AND anchor LIKE ?")) {
            ps.setString(1, weblog.getId());
            ps.setString(2, base + "%");
            List<String> anchors = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    anchors.add(rs.getString(1));
                }
            }
            Collections.sort(anchors);
            return anchors;
        }
    }

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

    private static void assertNoFailures(List<Throwable> failures, String when) {
        if (!failures.isEmpty()) {
            Throwable root = failures.get(0);
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            throw new AssertionError(failures.size() + " of the concurrent creates failed (" + when
                    + "); first: " + root.getMessage(), failures.get(0));
        }
    }
}
