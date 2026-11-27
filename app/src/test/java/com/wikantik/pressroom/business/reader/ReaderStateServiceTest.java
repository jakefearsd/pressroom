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
package com.wikantik.pressroom.business.reader;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.business.jpa.JPAWebloggerImpl;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.util.GenericThrottle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Saves, follows and reading history against the database: the rules of
 * spec Part E and Review Focus 2 (cascades) and 5 (nothing invisible in a
 * list).
 */
class ReaderStateServiceTest {

    private static final String ISSUER = "https://issuer.test/reader-state";

    private final List<String> userNames = new ArrayList<>();
    private final List<String> weblogIds = new ArrayList<>();
    private final AtomicLong ticks = new AtomicLong();
    private User owner;
    private User reader;
    private Weblog a;
    private Weblog b;
    private WeblogEntry a1;
    private WeblogEntry a2;
    private WeblogEntry b1;
    private ReaderStateService service;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        owner = TestUtils.setupUser("rsowner");
        userNames.add(owner.getUserName());
        reader = newReader("reader-rsreader1");
        a = newWeblog("rsbloga");
        b = newWeblog("rsblogb");
        a1 = newEntry(a, "rsa1");
        a2 = newEntry(a, "rsa2");
        b1 = newEntry(b, "rsb1");
        service = new ReaderStateService(TestUtils.weblogger(), ReaderStateServiceTest::strategy, ticking(),
                new GenericThrottle(1000, 60_000, 100), () -> null);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.endSession(false);
        for (String id : weblogIds) {
            TestUtils.teardownWeblog(id);
        }
        for (String name : userNames) {
            TestUtils.teardownUser(name);
        }
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------- save

    @Test
    void saveRefusesInvisibleEntry() throws Exception {
        WeblogEntry draft = newEntry(a, "rsdraft", PubStatus.DRAFT);
        WeblogEntry scheduled = managed(a1);
        scheduled.setPubTime(Timestamp.from(Instant.now().plusSeconds(86_400)));
        WeblogEntry trashed = managed(a2);
        trashed.moveToTrash(Timestamp.from(Instant.now()));
        Weblog hidden = newWeblog("rsblogh");
        WeblogEntry onHidden = newEntry(hidden, "rsh1");
        setVisible(hidden, false);

        for (WeblogEntry e : List.of(draft, scheduled, trashed, managed(onHidden))) {
            ReaderStateRefusal refusal = assertThrows(ReaderStateRefusal.class, () -> service.save(reader, e),
                    e.getAnchor());
            assertSame(ReaderStateRefusal.NOT_VISIBLE, refusal);
        }
        assertThrows(ReaderStateRefusal.class, () -> service.save(reader, null));
        TestUtils.endSession(false);
        assertEquals(0, count("pressroom_reader_save"), "nothing invisible is stored");
    }

    @Test
    void saveIsIdempotent() throws Exception {
        service.save(reader, managed(a1));
        service.save(reader, managed(a1));
        TestUtils.endSession(true);

        assertEquals(1, count("pressroom_reader_save"));
        assertTrue(service.isSaved(reader, a1.getId()));
        assertFalse(service.isSaved(reader, a2.getId()));
        assertEquals(List.of(a1.getId()), ids(lists(ReaderList.SAVED).saved()));

        service.unsave(reader, a1.getId());
        service.unsave(reader, a1.getId());
        service.unsave(reader, "no-such-entry");
        TestUtils.endSession(true);
        assertFalse(service.isSaved(reader, a1.getId()));
        assertEquals(0, count("pressroom_reader_save"));
    }

    @Test
    void savedIsNewestFirstAndLimited() throws Exception {
        service.save(reader, managed(a1));
        service.save(reader, managed(b1));
        service.save(reader, managed(a2));
        TestUtils.endSession(true);

        assertEquals(List.of(a2.getId(), b1.getId(), a1.getId()), ids(lists(ReaderList.SAVED).saved()));
        assertEquals(List.of(a2.getId(), b1.getId()),
                ids(service.lists(reader, EnumSet.of(ReaderList.SAVED), 2).saved()));
    }

    // ------------------------------------------------------------- follow

    @Test
    void followIsIdempotentAndRefusesAHiddenBlog() throws Exception {
        service.follow(reader, managed(a));
        service.follow(reader, managed(a));
        service.follow(reader, managed(b));
        TestUtils.endSession(true);

        assertEquals(2, count("pressroom_reader_follow"));
        assertTrue(service.isFollowing(reader, a.getId()));
        assertEquals(List.of(b.getId(), a.getId()), weblogIdsOf(lists(ReaderList.FOLLOWING).following()),
                "newest follow first");

        Weblog hidden = newWeblog("rsblogh");
        setVisible(hidden, false);
        assertSame(ReaderStateRefusal.NOT_VISIBLE,
                assertThrows(ReaderStateRefusal.class, () -> service.follow(reader, managed(hidden))));
        assertThrows(ReaderStateRefusal.class, () -> service.follow(reader, null));

        service.unfollow(reader, a.getId());
        service.unfollow(reader, a.getId());
        TestUtils.endSession(true);
        assertFalse(service.isFollowing(reader, a.getId()));
        assertEquals(List.of(b.getId()), weblogIdsOf(lists(ReaderList.FOLLOWING).following()));
    }

    @Test
    void followedPostsNewestFirstAcrossFollowedOnly() throws Exception {
        Weblog c = newWeblog("rsblogc");
        WeblogEntry c1 = newEntry(c, "rsc1");
        List<WeblogEntry> more = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            more.add(newEntry(i % 2 == 0 ? a : b, "rsmore" + i));
        }
        WeblogEntry draft = newEntry(a, "rsfdraft", PubStatus.DRAFT);
        service.follow(reader, managed(a));
        service.follow(reader, managed(b));
        TestUtils.endSession(true);

        List<String> expected = new ArrayList<>();
        for (int i = more.size() - 1; i >= 0; i--) {
            expected.add(more.get(i).getId());
        }
        List<String> posts = ids(lists(ReaderList.FOLLOWED_POSTS).followedPosts());
        assertEquals(expected, posts, "the six newest across followed blogs, newest first");
        assertFalse(posts.contains(c1.getId()), "an unfollowed blog's posts never appear");
        assertFalse(posts.contains(draft.getId()));
        assertEquals(ReaderStateService.FOLLOWED_POSTS, posts.size());
    }

    // ------------------------------------------------------------- history

    @Test
    void historyIsUpsertedNewestFirst() throws Exception {
        service.recordRead(reader, managed(a1));
        service.recordRead(reader, managed(b1));
        service.recordRead(reader, managed(a2));
        service.recordRead(reader, managed(a1));
        TestUtils.endSession(true);

        assertEquals(3, count("pressroom_reader_read"), "one row an entry: read again is an update");
        ReaderLists lists = service.lists(reader, EnumSet.of(ReaderList.HISTORY, ReaderList.CONTINUE,
                ReaderList.READ_IDS), 50);
        assertEquals(List.of(a1.getId(), a2.getId(), b1.getId()), ids(lists.history()));
        assertEquals(List.of(a1.getId(), a2.getId(), b1.getId()), ids(lists.continueReading()),
                "continue reading: the three most recently read");
        assertEquals(Set.of(a1.getId(), a2.getId(), b1.getId()), lists.readIds());
        assertEquals(List.of(), lists.saved(), "a list not asked for is empty");

        assertSame(ReaderStateRefusal.NOT_VISIBLE, assertThrows(ReaderStateRefusal.class,
                () -> service.recordRead(reader, managed(newEntry(a, "rsrdraft", PubStatus.DRAFT)))));

        service.clearHistory(reader);
        TestUtils.endSession(true);
        assertEquals(0, count("pressroom_reader_read"));
        assertEquals(List.of(), ids(lists(ReaderList.HISTORY).history()));
    }

    /**
     * "Keep my reading history" off (V057's {@code keep_history}): a read is
     * not recorded, and nothing already there is touched by the read; the
     * account page's switch clears the history itself.
     */
    @Test
    void historyNotRecordedWhenKeepHistoryOff() throws Exception {
        service.recordRead(reader, managed(a1));
        TestUtils.endSession(true);
        reader.setKeepHistory(false);

        service.recordRead(reader, managed(b1));
        service.recordRead(reader, managed(a1));
        TestUtils.endSession(true);

        assertEquals(List.of(a1.getId()), ids(lists(ReaderList.HISTORY).history()),
                "no new read, and the old one is not moved");
        assertSame(ReaderStateRefusal.NOT_VISIBLE, assertThrows(ReaderStateRefusal.class,
                () -> service.recordRead(reader, managed(newEntry(a, "rsrkhdraft", PubStatus.DRAFT)))),
                "visibility is still refused first: the switch is no way to probe a draft");
    }

    @Test
    void historyTrimsTo500() throws Exception {
        assertEquals(500, ReaderStateService.HISTORY_CAP);
        String prefix = "rt-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        try {
            // 500 older reads, of entries cloned in SQL from a1 (creating 500 through JPA is slow).
            strategy().getNativeQuery("INSERT INTO weblogentry SELECT (jsonb_populate_record(e, "
                    + "jsonb_build_object('id', ?1 || g, 'anchor', ?2 || g))).* "
                    + "FROM weblogentry e CROSS JOIN generate_series(1, 500) g WHERE e.id = ?3")
                    .setParameter(1, prefix).setParameter(2, prefix).setParameter(3, a1.getId()).executeUpdate();
            strategy().getNativeQuery("INSERT INTO pressroom_reader_read (user_id, entry_id, read_at) "
                    + "SELECT ?1, ?2 || g, now() - make_interval(mins => g) FROM generate_series(1, 500) g")
                    .setParameter(1, reader.getId()).setParameter(2, prefix).executeUpdate();
            TestUtils.weblogger().flush();
            TestUtils.endSession(true);
            assertEquals(500, count("pressroom_reader_read"));

            service.recordRead(reader, managed(a2));
            TestUtils.endSession(true);

            assertEquals(500, count("pressroom_reader_read"), "the newest 500 are kept");
            assertEquals(1, countWhere("entry_id = '" + a2.getId() + "'"), "the new read is kept");
            assertEquals(0, countWhere("entry_id = '" + prefix + "500'"), "the oldest goes");
            assertEquals(1, countWhere("entry_id = '" + prefix + "499'"));
        } finally {
            strategy().getNativeQuery("DELETE FROM weblogentry WHERE id LIKE ?1").setParameter(1, prefix + "%")
                    .executeUpdate();
            TestUtils.weblogger().flush();
            TestUtils.endSession(true);
        }
    }

    @Test
    void placesAreLastFiveDistinctWeblogs() throws Exception {
        List<Weblog> blogs = new ArrayList<>(List.of(a, b));
        List<WeblogEntry> firsts = new ArrayList<>(List.of(a1, b1));
        for (char c = 'c'; c <= 'f'; c++) {
            Weblog w = newWeblog("rsplace" + c);
            blogs.add(w);
            firsts.add(newEntry(w, "rsplace" + c + "1"));
        }
        for (WeblogEntry e : firsts) {
            service.recordRead(reader, managed(e));
        }
        service.recordRead(reader, managed(a2));
        TestUtils.endSession(true);

        // read order: a, b, c, d, e, f, then a again (another entry of a)
        List<String> places = weblogIdsOf(lists(ReaderList.PLACES).places());
        assertEquals(List.of(a.getId(), blogs.get(5).getId(), blogs.get(4).getId(), blogs.get(3).getId(),
                blogs.get(2).getId()), places, "distinct blogs, most recent read first, five of them");

        setVisible(blogs.get(5), false);
        assertFalse(weblogIdsOf(lists(ReaderList.PLACES).places()).contains(blogs.get(5).getId()),
                "a blog hidden since is no longer a place");
    }

    @Test
    void listsNeverShowInvisibleEntries() throws Exception {
        service.save(reader, managed(a1));
        service.recordRead(reader, managed(a1));
        service.follow(reader, managed(a));
        service.follow(reader, managed(b));
        TestUtils.endSession(true);
        Set<ReaderList> all = EnumSet.allOf(ReaderList.class);
        assertTrue(ids(service.lists(reader, all, 50).saved()).contains(a1.getId()), "fixture");

        WeblogEntry back = managed(a1);
        back.setStatus(PubStatus.DRAFT);
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(back);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        setVisible(b, false);

        ReaderLists lists = service.lists(reader, all, 50);
        for (List<WeblogEntry> list : List.of(lists.saved(), lists.history(), lists.continueReading(),
                lists.followedPosts())) {
            assertFalse(ids(list).contains(a1.getId()), "an unpublished entry never shows: " + ids(list));
        }
        assertFalse(lists.readIds().contains(a1.getId()));
        assertFalse(ids(lists.followedPosts()).contains(b1.getId()), "a hidden blog's posts never show");
        assertEquals(List.of(a.getId()), weblogIdsOf(lists.following()), "a hidden blog is not listed");
        assertEquals(List.of(), lists.places(), "the only read is invisible now");
        assertEquals(1, count("pressroom_reader_save"), "the row stays: it shows again once republished");
        assertFalse(service.isSaved(reader, a1.getId()), "but it is not answered as saved while invisible");
        assertFalse(service.isFollowing(reader, b.getId()), "nor a hidden blog as followed");
        assertEquals(2, count("pressroom_reader_follow"), "whose row stays too");
    }

    @Test
    void cascadesOnEntryWeblogAndUserDelete() throws Exception {
        User second = newReader("reader-rsreader2");
        for (User who : List.of(reader, second)) {
            service.save(who, managed(a1));
            service.save(who, managed(b1));
            service.recordRead(who, managed(a1));
            service.recordRead(who, managed(b1));
            service.follow(who, managed(a));
            service.follow(who, managed(b));
        }
        TestUtils.endSession(true);
        assertEquals(4, count("pressroom_reader_save"));
        assertEquals(4, count("pressroom_reader_read"));
        assertEquals(4, count("pressroom_reader_follow"));

        TestUtils.teardownWeblogEntry(a1.getId());
        TestUtils.endSession(true);
        assertEquals(2, count("pressroom_reader_save"), "deleting an entry removes its saves");
        assertEquals(2, count("pressroom_reader_read"), "and its reads");

        TestUtils.teardownWeblog(b.getId());
        weblogIds.remove(b.getId());
        TestUtils.endSession(true);
        assertEquals(0, count("pressroom_reader_save"), "deleting a weblog removes its entries' rows");
        assertEquals(0, count("pressroom_reader_read"));
        assertEquals(2, count("pressroom_reader_follow"), "and its follows");

        service.save(second, managed(a2));
        service.recordRead(second, managed(a2));
        TestUtils.endSession(true);
        TestUtils.teardownUser(second.getUserName());
        userNames.remove(second.getUserName());
        TestUtils.endSession(true);
        assertEquals(0, countFor("pressroom_reader_save", second), "deleting an account removes everything");
        assertEquals(0, countFor("pressroom_reader_read", second));
        assertEquals(0, countFor("pressroom_reader_follow", second));
        assertEquals(1, count("pressroom_reader_follow"), "another reader's rows stay");
    }

    // ------------------------------------------------------------- the account

    @Test
    void anEnabledAccountWithCommentMayUseIt() throws Exception {
        assertTrue(service.mayUse(reader));
        assertTrue(service.mayUse(owner), "an author holds comment too");
        assertFalse(service.mayUse(null));
        User ghost = new User();
        assertFalse(service.mayUse(ghost), "no username: no account");
        ghost.setUserName("reader-rs-no-such-account");
        assertFalse(service.mayUse(ghost), "an account removed since sign-in");
        assertFalse(service.isSaved(reader, " "), "a blank id is nothing saved");
        assertFalse(service.isFollowing(reader, null));

        UserManager users = TestUtils.weblogger().getUserManager();
        User current = users.getUserByUserName(reader.getUserName(), null);
        current.setEnabled(Boolean.FALSE);
        users.saveUser(current);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        assertFalse(service.mayUse(reader), "disabled since sign-in");
    }

    @Test
    void writesAreThrottledPerAccount() throws Exception {
        ReaderStateService limited = new ReaderStateService(TestUtils.weblogger(),
                ReaderStateServiceTest::strategy, ticking(), new GenericThrottle(2, 60_000, 100), () -> null);
        assertTrue(limited.admitWrite(reader));
        assertTrue(limited.admitWrite(reader));
        assertFalse(limited.admitWrite(reader), "a third write in the window is refused");
        assertTrue(limited.admitWrite(owner), "another account has its own count");
    }

    @Test
    void theConfiguredWriteLimitIsReadWithDefaults() {
        assertEquals(120, ReaderStateService.writeLimit(key -> null));
        assertEquals(600, ReaderStateService.writeWindowSeconds(key -> null));
        assertEquals(7, ReaderStateService.writeLimit(key -> "7"));
        assertEquals(120, ReaderStateService.writeLimit(key -> "0"), "an unusable value falls back");
        assertEquals(600, ReaderStateService.writeWindowSeconds(key -> "86401"));
        assertEquals(30, ReaderStateService.writeWindowSeconds(key -> "30"));

        GenericThrottle three = ReaderStateService.writeThrottle(key -> ReaderStateService.WRITE_LIMIT.equals(key)
                ? "3" : null);
        assertFalse(three.processHit("a"));
        assertFalse(three.processHit("a"));
        assertFalse(three.processHit("a"));
        assertTrue(three.processHit("a"), "at most the configured count: the fourth is over");
    }

    /**
     * The limit holds under concurrent writes from one account: the count is
     * taken under the account's lock, as CommentService's is (review minor 3).
     */
    @Test
    void concurrentWritesNeverOvershootTheLimit() throws Exception {
        ReaderStateService limited = new ReaderStateService(TestUtils.weblogger(),
                ReaderStateServiceTest::strategy, ticking(), new GenericThrottle(1000, 60_000, 100), () -> null);
        int threads = 8;
        int each = 500;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        AtomicLong admitted = new AtomicLong();
        List<java.util.concurrent.Future<?>> runs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            runs.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < each; i++) {
                    if (limited.admitWrite(reader)) {
                        admitted.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> run : runs) {
            run.get();
        }
        pool.shutdown();
        assertEquals(1000, admitted.get(), "exactly the configured count, however the writes interleave");
    }

    /** The overload without a limit serves only the fixed-size lists (review minor 4). */
    @Test
    void listsWithoutALimitRefuseTheCallerLimitedLists() throws Exception {
        service.recordRead(reader, managed(a1));
        TestUtils.endSession(true);

        assertEquals(List.of(a.getId()), weblogIdsOf(service.lists(reader, EnumSet.of(ReaderList.PLACES)).places()));
        for (ReaderList limited : List.of(ReaderList.SAVED, ReaderList.FOLLOWING, ReaderList.HISTORY)) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.lists(reader, EnumSet.of(ReaderList.PLACES, limited)), limited.name());
        }
        assertThrows(IllegalArgumentException.class,
                () -> service.lists(reader, EnumSet.of(ReaderList.SAVED), 0), "a caller-limited list needs a limit");
    }

    /**
     * The production listmonk client is built on first use, once, and kept.
     * Written after {@code memoized} (fix round 1's coverage gap), so it
     * passed the first time it ran: a characterisation of the helper, not a
     * test seen failing.
     */
    @Test
    void theListmonkClientIsBuiltOnceOnFirstUse() {
        AtomicLong builds = new AtomicLong();
        java.util.function.Supplier<Object> client = ReaderStateService.memoized(() -> {
            builds.incrementAndGet();
            return new Object();
        });
        assertEquals(0, builds.get(), "nothing is built at wiring");
        Object first = client.get();
        assertSame(first, client.get());
        assertEquals(1, builds.get());
    }

    /** A check that could not run is a failure, never "not saved" or an empty list. */
    @Test
    void aDatabaseFailureIsAWebloggerException() {
        Query broken = mock(Query.class);
        when(broken.setParameter(anyInt(), any())).thenReturn(broken);
        when(broken.getResultList()).thenThrow(new PersistenceException("down"));
        when(broken.getSingleResult()).thenThrow(new PersistenceException("down"));
        when(broken.executeUpdate()).thenThrow(new PersistenceException("down"));
        JPAPersistenceStrategy strategy = mock(JPAPersistenceStrategy.class);
        when(strategy.getNativeQuery(anyString())).thenReturn(broken);
        ReaderStateService failing = new ReaderStateService(TestUtils.weblogger(), () -> strategy, ticking(),
                new GenericThrottle(1000, 60_000, 100), () -> null);

        assertThrows(WebloggerException.class, () -> failing.isSaved(reader, a1.getId()));
        assertThrows(WebloggerException.class, () -> failing.unsave(reader, a1.getId()));
        assertThrows(WebloggerException.class,
                () -> failing.lists(reader, EnumSet.of(ReaderList.READ_IDS), 50));
    }

    // ------------------------------------------------------------- helpers

    private ReaderLists lists(ReaderList wanted) throws Exception {
        return service.lists(reader, EnumSet.of(wanted), 50);
    }

    /**
     * The real time, a millisecond further on at every read: entries created a
     * moment ago are due, and two writes never tie at the columns' precision.
     */
    private Clock ticking() {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.now().plusMillis(ticks.incrementAndGet());
            }
        };
    }

    private static List<String> ids(List<WeblogEntry> entries) {
        return entries.stream().map(WeblogEntry::getId).toList();
    }

    private static List<String> weblogIdsOf(List<Weblog> weblogs) {
        return weblogs.stream().map(Weblog::getId).toList();
    }

    private long count(String table) throws Exception {
        return ((Number) strategy().getNativeQuery("SELECT count(*) FROM " + table + " WHERE user_id IN (?1, ?2)")
                .setParameter(1, reader.getId()).setParameter(2, otherId()).getSingleResult()).longValue();
    }

    private long countWhere(String condition) throws Exception {
        return ((Number) strategy().getNativeQuery("SELECT count(*) FROM pressroom_reader_read WHERE user_id = ?1 AND "
                + condition).setParameter(1, reader.getId()).getSingleResult()).longValue();
    }

    private static long countFor(String table, User who) throws Exception {
        return ((Number) strategy().getNativeQuery("SELECT count(*) FROM " + table + " WHERE user_id = ?1")
                .setParameter(1, who.getId()).getSingleResult()).longValue();
    }

    /** The second reader's id while it exists, so counts cover both readers of the cascade test. */
    private String otherId() throws Exception {
        User second = TestUtils.weblogger().getUserManager().getUserByUserName("reader-rsreader2", null);
        return second == null ? "none" : second.getId();
    }

    static JPAPersistenceStrategy strategy() {
        try {
            Weblogger real = TestUtils.weblogger();
            Field field = JPAWebloggerImpl.class.getDeclaredField("strategy");
            field.setAccessible(true);
            return (JPAPersistenceStrategy) field.get(real);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static WeblogEntry managed(WeblogEntry e) throws Exception {
        return TestUtils.getManagedWeblogEntry(e);
    }

    private static Weblog managed(Weblog w) throws Exception {
        return TestUtils.getManagedWebsite(w);
    }

    private Weblog newWeblog(String handle) throws Exception {
        Weblog w = TestUtils.setupWeblog(handle, owner);
        weblogIds.add(w.getId());
        TestUtils.endSession(true);
        return w;
    }

    private WeblogEntry newEntry(Weblog weblog, String anchor) throws Exception {
        return newEntry(weblog, anchor, PubStatus.PUBLISHED);
    }

    private WeblogEntry newEntry(Weblog weblog, String anchor, PubStatus status) throws Exception {
        WeblogEntry e = TestUtils.setupWeblogEntry(anchor, TestUtils.getManagedWebsite(weblog),
                TestUtils.getManagedUser(owner), status);
        TestUtils.endSession(true);
        return e;
    }

    private static void setVisible(Weblog weblog, boolean visible) throws Exception {
        Weblog w = TestUtils.getManagedWebsite(weblog);
        w.setVisible(visible);
        TestUtils.weblogger().getWeblogManager().saveWeblog(w);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private User newReader(String userName) throws Exception {
        User u = new User();
        u.setUserName(userName);
        u.setScreenName("Ann Reader");
        u.setFullName("Ann Reader");
        u.setEmailAddress(userName + "@example.com");
        u.setPassword(User.SSO_PASSWORD_MARKER);
        u.setDateCreated(new Date());
        u.setEnabled(Boolean.TRUE);
        u.setSsoIssuer(ISSUER);
        u.setSsoSubject("sub-" + UUID.randomUUID());
        TestUtils.weblogger().getUserManager().addReader(u);
        TestUtils.weblogger().flush();
        userNames.add(userName);
        TestUtils.endSession(true);
        return u;
    }
}
