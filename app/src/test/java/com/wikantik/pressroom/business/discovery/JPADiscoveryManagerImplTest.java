/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.business.discovery;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.util.DiscoveryGeneration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The discovery picker against the real tier: candidates read from the
 * database, scores from a fake traffic source, and the front-page and
 * more-from-the-site selections built from them.
 *
 * <p>The suite shares one database, so a weblog another class left behind can
 * be a candidate too. Assertions about rank therefore use traffic on this
 * class's own weblogs only (nothing else has a score), and assertions about
 * random sections check shape and exclusions, never exact membership.
 */
class JPADiscoveryManagerImplTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private User user;
    private final List<Weblog> blogs = new ArrayList<>();
    private Weblog createdSiteSpace;
    private WeblogEntry siteSpaceEntry;
    private String originalShare;
    private FakeSource source;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("discoveryuser");
        originalShare = properties().getProperty(DiscoverySettings.SHARE_PERCENT).getValue();
        TestUtils.endSession(true);
        source = new FakeSource();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.endSession(false);
        setShare(originalShare);
        for (Weblog blog : blogs) {
            TestUtils.teardownWeblog(blog.getId());
        }
        if (createdSiteSpace == null && siteSpaceEntry != null) {
            TestUtils.teardownWeblogEntry(siteSpaceEntry.getId());
        }
        if (createdSiteSpace != null) {
            weblogs().removeWeblog(weblogs().getWeblog(createdSiteSpace.getId()));
            TestUtils.weblogger().flush();
        }
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- AC 1

    @Test
    void noTrafficStillFillsEverySection() throws Exception {
        Fixture f = threeBlogsOfSix();
        JPADiscoveryManagerImpl manager = manager();

        FrontPageSelection page = manager.selectFrontPage();

        assertNotNull(page.headline());
        assertEquals(f.newest.getId(), page.headline().getId(), "with no scores the headline is the newest post");
        assertEquals(4, page.popular().size());
        assertEquals(3, page.blogs().size());
        for (BlogCard card : page.blogs()) {
            assertFalse(card.topEntries().isEmpty(), "every card lists entries: " + card.weblog().getHandle());
        }
        assertEquals(3, page.different().size());
        assertTrue(handles(page.allBlogs()).containsAll(List.of("discoa", "discob", "discoc")));
        assertEquals(DiscoveryStatus.State.DISABLED, manager.getStatus().state());
    }

    // ---------------------------------------------------------------- AC 2

    @Test
    void headlineIsTopScored() throws Exception {
        Fixture f = threeBlogsOfSix();
        setShare("0");
        source.configured = true;
        source.rows = List.of(
                row(f, 1, 0, 50),
                row(f, 0, 2, 100),
                row(f, 2, 3, 70));

        FrontPageSelection page = manager().selectFrontPage();

        assertEquals(f.entries[0][2].getId(), page.headline().getId());
    }

    @Test
    void popularFollowsScoreOrder() throws Exception {
        Fixture f = threeBlogsOfSix();
        setShare("0");
        source.configured = true;
        source.rows = List.of(
                row(f, 0, 0, 100),
                row(f, 1, 1, 90),
                row(f, 2, 2, 80),
                row(f, 0, 3, 70),
                row(f, 1, 4, 60),
                row(f, 2, 0, 50));

        FrontPageSelection page = manager().selectFrontPage();

        assertEquals(f.entries[0][0].getId(), page.headline().getId());
        assertEquals(List.of(f.entries[1][1].getId(), f.entries[2][2].getId(),
                        f.entries[0][3].getId(), f.entries[1][4].getId()),
                ids(page.popular()));
    }

    @Test
    void blogCardsFollowBlogScore() throws Exception {
        Fixture f = threeBlogsOfSix();
        setShare("0");
        source.configured = true;
        // blog c sums to 300, a to 200, b to 100 -- not creation order
        source.rows = List.of(
                row(f, 2, 0, 150), row(f, 2, 1, 150),
                row(f, 0, 0, 120), row(f, 0, 1, 80),
                row(f, 1, 0, 100));

        FrontPageSelection page = manager().selectFrontPage();

        assertEquals(List.of(f.blogs[2].getId(), f.blogs[0].getId(), f.blogs[1].getId()),
                page.blogs().stream().map(card -> card.weblog().getId()).toList());
        for (BlogCard card : page.blogs()) {
            for (WeblogEntry entry : card.topEntries()) {
                assertEquals(card.weblog().getId(), entry.getWebsite().getId(),
                        "a card lists only its own blog's entries");
            }
        }
    }

    @Test
    void websiteIdMatchesIgnoringCase() throws Exception {
        Fixture f = threeBlogsOfSix();
        setShare("0");
        source.configured = true;
        source.rows = List.of(new TrafficRow(f.blogs[1].getAnalyticsSiteId().toUpperCase(Locale.ROOT),
                f.entries[1][3].getAnchor(), 10));

        JPADiscoveryManagerImpl manager = manager();
        FrontPageSelection page = manager.selectFrontPage();

        assertEquals(f.entries[1][3].getId(), page.headline().getId());
        assertEquals(1, manager.getStatus().scoredEntries());
    }

    @Test
    void refreshAsksForTheWindow() throws Exception {
        threeBlogsOfSix();
        source.configured = true;

        manager().refresh();

        // default window 30 days, today included: 2026-10-03 .. 2026-11-01
        assertEquals(LocalDate.of(2026, 10, 3), source.lastFirstDay);
    }

    @Test
    void statusReportsTheLastRefresh() throws Exception {
        Fixture f = threeBlogsOfSix();
        source.configured = true;
        source.rows = List.of(row(f, 0, 0, 5), row(f, 1, 1, 6));
        JPADiscoveryManagerImpl manager = manager();
        assertEquals(DiscoveryStatus.State.NEVER, manager.getStatus().state());

        manager.refresh();

        DiscoveryStatus status = manager.getStatus();
        assertEquals(DiscoveryStatus.State.OK, status.state());
        assertEquals(NOW, status.lastAttempt());
        assertEquals(NOW, status.lastSuccess());
        assertEquals(2, status.scoredEntries());
        assertTrue(status.candidates() >= 19, "every fixture entry is a candidate: " + status.candidates());
        assertNull(status.lastError());
    }

    // ---------------------------------------------------------------- AC 5

    @Test
    void noEntryAppearsTwiceOnTheFrontPage() throws Exception {
        Fixture f = threeBlogsOfSix();
        source.configured = true;
        source.rows = List.of(row(f, 0, 0, 30), row(f, 0, 1, 20), row(f, 1, 0, 10));
        JPADiscoveryManagerImpl manager = manager();

        for (int generation = 0; generation < 5; generation++) {
            manager.refresh();
            List<String> all = allEntryIds(manager.selectFrontPage());
            assertEquals(all.size(), new HashSet<>(all).size(), "duplicate on the front page: " + all);
        }
    }

    @Test
    void moreFromSiteExcludesCurrentBlog() throws Exception {
        Fixture f = threeBlogsOfSix();
        source.configured = true;
        source.rows = List.of(row(f, 0, 0, 30), row(f, 1, 0, 20));
        JPADiscoveryManagerImpl manager = manager();

        for (Weblog blog : f.blogs) {
            List<WeblogEntry> more = manager.selectMoreFromSite(blog, 3);
            assertEquals(3, more.size());
            assertEquals(3, new HashSet<>(ids(more)).size(), "no duplicates");
            for (WeblogEntry entry : more) {
                assertNotEquals(blog.getId(), entry.getWebsite().getId(), "the block never shows its own blog");
            }
        }
    }

    // ---------------------------------------------------------------- AC 7

    @Test
    void refreshFailureKeepsLastScores() throws Exception {
        Fixture f = threeBlogsOfSix();
        setShare("0");
        source.configured = true;
        source.rows = List.of(row(f, 0, 4, 100), row(f, 1, 3, 90), row(f, 2, 2, 80),
                row(f, 0, 1, 70), row(f, 1, 0, 60));
        JPADiscoveryManagerImpl manager = manager();
        manager.refresh();
        FrontPageSelection before = manager.selectFrontPage();
        long generationBefore = DiscoveryGeneration.current();

        source.failure = new WebloggerException("umami is down");
        manager.refresh();

        DiscoveryStatus status = manager.getStatus();
        assertEquals(DiscoveryStatus.State.FAILED, status.state());
        assertTrue(status.lastError().contains("umami is down"), status.lastError());
        assertEquals(NOW, status.lastSuccess(), "the last success is still the earlier read");
        assertEquals(5, status.scoredEntries(), "the earlier scores are kept");
        assertTrue(DiscoveryGeneration.current() > generationBefore, "picks still reshuffle while Umami is down");
        FrontPageSelection after = manager.selectFrontPage();
        assertEquals(before.headline().getId(), after.headline().getId());
        assertEquals(ids(before.popular()), ids(after.popular()));
    }

    // ---------------------------------------------------------------- AC 8

    @Test
    void ineligibleContentNeverAppears() throws Exception {
        Fixture f = threeBlogsOfSix();
        Weblog d = blog("discod");
        List<WeblogEntry> ineligible = new ArrayList<>();
        ineligible.add(TestUtils.setupWeblogEntry("draft", d, user, PubStatus.DRAFT));
        ineligible.add(TestUtils.setupWeblogEntry("scheduled", d, user, PubStatus.SCHEDULED));
        ineligible.add(TestUtils.setupWeblogEntry("pending", d, user, PubStatus.PENDING));
        WeblogEntry trashed = TestUtils.setupWeblogEntry("trashed", d, user);
        entries().trashWeblogEntry(entries().getWeblogEntry(trashed.getId()));
        TestUtils.weblogger().flush();
        ineligible.add(trashed);
        WeblogEntry noindex = TestUtils.setupWeblogEntry("noindexed", d, user);
        WeblogEntry managedNoindex = entries().getWeblogEntry(noindex.getId());
        managedNoindex.setNoindex(Boolean.TRUE);
        entries().saveWeblogEntry(managedNoindex);
        TestUtils.weblogger().flush();
        ineligible.add(noindex);

        Weblog inactive = blog("discoinactive");
        ineligible.add(TestUtils.setupWeblogEntry("inactivepost", inactive, user));
        Weblog managedInactive = weblogs().getWeblog(inactive.getId());
        managedInactive.setActive(Boolean.FALSE);
        weblogs().saveWeblog(managedInactive);
        TestUtils.weblogger().flush();

        Weblog hidden = blog("discohidden");
        ineligible.add(TestUtils.setupWeblogEntry("hiddenpost", hidden, user));
        Weblog managedHidden = weblogs().getWeblog(hidden.getId());
        managedHidden.setVisible(Boolean.FALSE);
        weblogs().saveWeblog(managedHidden);
        TestUtils.weblogger().flush();

        Weblog site = siteSpace();
        siteSpaceEntry = TestUtils.setupWeblogEntry("sitepost", site, user);
        ineligible.add(siteSpaceEntry);
        TestUtils.endSession(true);

        // every ineligible entry carries far more traffic than anything else,
        // so if one leaked into the candidates it would take the headline
        setShare("0");
        source.configured = true;
        List<TrafficRow> rows = new ArrayList<>();
        for (WeblogEntry entry : ineligible) {
            Weblog owner = weblogs().getWeblog(entry.getWebsite().getId());
            String siteId = owner.getAnalyticsSiteId();
            if (siteId == null) {
                siteId = UUID.randomUUID().toString();
                owner.setAnalyticsSiteId(siteId);
                weblogs().saveWeblog(owner);
                TestUtils.weblogger().flush();
            }
            rows.add(new TrafficRow(siteId, entry.getAnchor(), 1_000_000));
        }
        rows.add(row(f, 0, 0, 1));
        source.rows = rows;
        TestUtils.endSession(true);

        JPADiscoveryManagerImpl manager = manager();
        FrontPageSelection page = manager.selectFrontPage();
        Set<String> shown = new HashSet<>(allEntryIds(page));
        for (Weblog blog : f.blogs) {
            shown.addAll(ids(manager.selectMoreFromSite(blog, 10)));
        }

        assertEquals(f.entries[0][0].getId(), page.headline().getId());
        for (WeblogEntry entry : ineligible) {
            assertFalse(shown.contains(entry.getId()), "ineligible entry shown: " + entry.getAnchor());
        }
        Set<String> listed = new HashSet<>(handles(page.allBlogs()));
        assertFalse(listed.contains("discoinactive"));
        assertFalse(listed.contains("discohidden"));
        assertFalse(listed.contains(Weblog.SITE_SPACE_HANDLE));
        assertTrue(listed.contains("discod"));
        assertEquals(1, manager.getStatus().scoredEntries(), "traffic on ineligible entries scores nothing");
    }

    @Test
    void selectionDropsEntriesNoLongerEligible() throws Exception {
        Fixture f = threeBlogsOfSix();
        setShare("0");
        source.configured = true;
        source.rows = List.of(row(f, 0, 0, 100), row(f, 1, 1, 90), row(f, 2, 2, 80),
                row(f, 0, 3, 70), row(f, 1, 4, 60));
        JPADiscoveryManagerImpl manager = manager();
        FrontPageSelection before = manager.selectFrontPage();
        String headline = before.headline().getId();
        String popular = before.popular().get(0).getId();
        TestUtils.endSession(false);

        // trashed and noindexed between rebuilds: the memoized ids still name them
        entries().trashWeblogEntry(entries().getWeblogEntry(headline));
        WeblogEntry noindexed = entries().getWeblogEntry(popular);
        noindexed.setNoindex(Boolean.TRUE);
        entries().saveWeblogEntry(noindexed);
        TestUtils.endSession(true);

        FrontPageSelection after = manager.selectFrontPage();

        List<String> shown = allEntryIds(after);
        assertFalse(shown.contains(headline), "a trashed entry is dropped");
        assertFalse(shown.contains(popular), "a noindexed entry is dropped");
        assertNotNull(after.headline(), "the headline is never empty while anything is eligible");
        assertEquals(before.popular().get(1).getId(), after.headline().getId(),
                "the next-ranked surviving pick takes the headline");
    }

    // ---------------------------------------------------------------- AC 9

    @Test
    void optedOutBlogStillAppears() throws Exception {
        Fixture f = threeBlogsOfSix();
        Weblog optedOut = weblogs().getWeblog(f.blogs[0].getId());
        optedOut.setShowMoreFromSite(Boolean.FALSE);
        weblogs().saveWeblog(optedOut);
        TestUtils.endSession(true);
        setShare("0");
        source.configured = true;
        source.rows = List.of(row(f, 0, 0, 100), row(f, 0, 1, 90), row(f, 0, 2, 80), row(f, 0, 3, 70));
        JPADiscoveryManagerImpl manager = manager();

        FrontPageSelection page = manager.selectFrontPage();
        List<WeblogEntry> more = manager.selectMoreFromSite(f.blogs[1], 3);

        assertEquals(f.entries[0][0].getId(), page.headline().getId());
        assertTrue(handles(page.allBlogs()).contains("discoa"));
        assertEquals(List.of(f.entries[0][0].getId(), f.entries[0][1].getId(), f.entries[0][2].getId()),
                ids(more), "an opted-out blog's posts still fill other blogs' blocks");
    }

    // ---------------------------------------------------------------- reshuffle cadence

    @Test
    void samePicksWithinAGeneration() throws Exception {
        Fixture f = threeBlogsOfSix();
        setShare("100");
        JPADiscoveryManagerImpl manager = manager();

        FrontPageSelection first = manager.selectFrontPage();
        FrontPageSelection second = manager.selectFrontPage();

        assertEquals(allEntryIds(first), allEntryIds(second));
        assertEquals(ids(manager.selectMoreFromSite(f.blogs[0], 3)), ids(manager.selectMoreFromSite(f.blogs[0], 3)));
    }

    @Test
    void picksCanChangeAcrossGenerations() throws Exception {
        threeBlogsOfSix();
        setShare("100");
        JPADiscoveryManagerImpl manager = manager();
        manager.refresh();
        long generation = DiscoveryGeneration.current();
        List<String> first = allEntryIds(manager.selectFrontPage());

        boolean changed = false;
        for (int i = 0; i < 20 && !changed; i++) {
            manager.refresh();
            changed = !allEntryIds(manager.selectFrontPage()).equals(first);
        }

        assertTrue(DiscoveryGeneration.current() > generation, "every refresh is a new generation");
        assertTrue(changed, "random picks reshuffle when the snapshot rebuilds");
    }

    // ---------------------------------------------------------------- mapping

    @Test
    void unmappedTrafficIsIgnored() throws Exception {
        Fixture f = threeBlogsOfSix();
        source.configured = true;
        source.rows = List.of(
                new TrafficRow(UUID.randomUUID().toString(), f.entries[0][0].getAnchor(), 500),
                new TrafficRow(f.blogs[0].getAnalyticsSiteId(), "no-such-anchor", 400),
                new TrafficRow(null, null, 300));
        JPADiscoveryManagerImpl manager = manager();

        FrontPageSelection page = manager.selectFrontPage();

        assertEquals(DiscoveryStatus.State.OK, manager.getStatus().state());
        assertEquals(0, manager.getStatus().scoredEntries());
        assertEquals(f.newest.getId(), page.headline().getId(), "no score, so the newest post leads");
    }

    // ---------------------------------------------------------------- small pools

    @Test
    void smallPoolNeverDuplicates() throws Exception {
        Weblog only = blog("discoonly");
        entry(only, "first", 10);
        entry(only, "second", 0);
        TestUtils.endSession(true);
        JPADiscoveryManagerImpl manager = manager();

        FrontPageSelection page = manager.selectFrontPage();
        List<String> all = allEntryIds(page);
        List<WeblogEntry> more = manager.selectMoreFromSite(only, 3);

        assertEquals(all.size(), new HashSet<>(all).size(), "duplicate on the front page: " + all);
        assertTrue(all.size() <= manager.getStatus().candidates());
        for (WeblogEntry entry : more) {
            assertNotEquals(only.getId(), entry.getWebsite().getId());
        }
    }

    // ---------------------------------------------------------------- first build

    @Test
    void firstSelectBuildsTheSnapshotOnce() throws Exception {
        threeBlogsOfSix();
        source.configured = true;
        source.delayMillis = 200;
        JPADiscoveryManagerImpl manager = manager();
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<FrontPageSelection>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return manager.selectFrontPage();
                    } finally {
                        TestUtils.weblogger().release();
                    }
                }));
            }
            start.countDown();
            for (Future<FrontPageSelection> result : results) {
                assertNotNull(result.get(30, TimeUnit.SECONDS).headline());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, source.calls.get(), "concurrent first requests share one build");
    }

    @Test
    void theTierWiresADiscoveryManager() {
        assertNotNull(TestUtils.weblogger().getDiscoveryManager());
    }

    // ---------------------------------------------------------------- fixture

    /**
     * Three weblogs (discoa, discob, discoc) of six published entries each,
     * plus a newest one on discoc: 19, enough to fill every front-page
     * section (1 + 4 + 3 x 3 + 3 = 17) with none left short. The brief's
     * "3 x 5" is 15, two fewer than the page shows.
     */
    private Fixture threeBlogsOfSix() throws Exception {
        Fixture f = new Fixture();
        String[] handles = {"discoa", "discob", "discoc"};
        int minutesAgo = 100;
        for (int b = 0; b < handles.length; b++) {
            f.blogs[b] = blog(handles[b]);
            for (int e = 0; e < 6; e++) {
                minutesAgo -= 5;
                f.entries[b][e] = entry(f.blogs[b], handles[b] + "-post" + e, minutesAgo);
            }
        }
        f.newest = entry(f.blogs[2], "discoc-newest", 0);
        TestUtils.endSession(true);
        return f;
    }

    private Weblog blog(String handle) throws Exception {
        Weblog created = TestUtils.setupWeblog(handle, user);
        blogs.add(created);
        Weblog managed = weblogs().getWeblog(created.getId());
        managed.setAnalyticsSiteId(UUID.randomUUID().toString());
        weblogs().saveWeblog(managed);
        TestUtils.weblogger().flush();
        return weblogs().getWeblog(created.getId());
    }

    private WeblogEntry entry(Weblog blog, String anchor, int minutesAgo) throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry(anchor, blog, user);
        if (minutesAgo > 0) {
            WeblogEntry managed = entries().getWeblogEntry(created.getId());
            managed.setPubTime(new Timestamp(System.currentTimeMillis() - minutesAgo * 60_000L));
            entries().saveWeblogEntry(managed);
            TestUtils.weblogger().flush();
        }
        return entries().getWeblogEntry(created.getId());
    }

    private Weblog siteSpace() throws Exception {
        Weblog existing = weblogs().getSiteSpace();
        if (existing != null) {
            return existing;
        }
        createdSiteSpace = weblogs().getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(user.getUserName()));
        TestUtils.weblogger().flush();
        return createdSiteSpace;
    }

    private static TrafficRow row(Fixture f, int blog, int entry, long views) {
        return new TrafficRow(f.blogs[blog].getAnalyticsSiteId(), f.entries[blog][entry].getAnchor(), views);
    }

    private JPADiscoveryManagerImpl manager() {
        return new JPADiscoveryManagerImpl(TestUtils.weblogger(), source, CLOCK);
    }

    private void setShare(String value) throws Exception {
        RuntimeConfigProperty prop = properties().getProperty(DiscoverySettings.SHARE_PERCENT);
        prop.setValue(value);
        properties().saveProperty(prop);
        TestUtils.endSession(true);
    }

    private static PropertiesManager properties() {
        return TestUtils.weblogger().getPropertiesManager();
    }

    private static WeblogManager weblogs() {
        return TestUtils.weblogger().getWeblogManager();
    }

    private static WeblogEntryManager entries() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    private static List<String> ids(List<WeblogEntry> entries) {
        return entries.stream().map(WeblogEntry::getId).toList();
    }

    private static List<String> handles(List<Weblog> weblogs) {
        return weblogs.stream().map(Weblog::getHandle).toList();
    }

    private static List<String> allEntryIds(FrontPageSelection page) {
        List<String> all = new ArrayList<>();
        if (page.headline() != null) {
            all.add(page.headline().getId());
        }
        all.addAll(ids(page.popular()));
        for (BlogCard card : page.blogs()) {
            all.addAll(ids(card.topEntries()));
        }
        all.addAll(ids(page.different()));
        return all;
    }

    private static final class Fixture {
        final Weblog[] blogs = new Weblog[3];
        final WeblogEntry[][] entries = new WeblogEntry[3][6];
        WeblogEntry newest;
    }

    /** Traffic on demand: unconfigured until told, then the given rows or the given failure. */
    private static final class FakeSource implements TrafficSource {
        volatile boolean configured;
        volatile List<TrafficRow> rows = List.of();
        volatile WebloggerException failure;
        volatile LocalDate lastFirstDay;
        volatile long delayMillis;
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public List<TrafficRow> viewsSince(LocalDate firstDay) throws WebloggerException {
            calls.incrementAndGet();
            lastFirstDay = firstDay;
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw failure;
            }
            return rows;
        }
    }
}
