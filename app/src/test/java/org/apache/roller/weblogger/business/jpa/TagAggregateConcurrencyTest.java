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

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.TagStat;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogEntryTagAggregate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.apache.roller.weblogger.business.jpa.TagAggregateRows.assertAggregateIsTrue;
import static org.apache.roller.weblogger.business.jpa.TagAggregateRows.connection;
import static org.apache.roller.weblogger.business.jpa.TagAggregateRows.storedTotals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The tag aggregate table ({@code roller_weblogentrytagagg}) under concurrent
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

    // ------------------------------------------------------------------ C

    @Test
    void theDatabaseRefusesASecondSiteWideRowForTheSameTag() throws Exception {
        String insert = "INSERT INTO roller_weblogentrytagagg (id, websiteid, name, total, lastused) "
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
                    st.executeUpdate("DELETE FROM roller_weblogentrytagagg WHERE name = 'sitedup'");
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
        entry.setStatus(PubStatus.PUBLISHED);
        for (String tag : tags) {
            entry.addTag(tag);
        }
        tier.getWeblogEntryManager().saveWeblogEntry(entry);
        tier.flush();
        return entry.getId();
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
                     "SELECT DISTINCT entryid FROM roller_weblogentrytag WHERE name = ?")) {
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
