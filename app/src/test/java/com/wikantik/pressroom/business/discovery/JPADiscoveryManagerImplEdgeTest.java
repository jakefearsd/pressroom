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
package com.wikantik.pressroom.business.discovery;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.util.DiscoveryGeneration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The picker's failure and edge paths, on a mocked tier: what happens when
 * Pressroom itself cannot be read, when an entity cannot be loaded at
 * display time, and when a whole section's picks stop being eligible.
 */
class JPADiscoveryManagerImplEdgeTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private MockWeblogger tier;
    private final Map<String, WeblogEntry> entries = new HashMap<>();
    private final Weblog blog = weblog("blog-1");

    @BeforeEach
    void setUp() throws Exception {
        tier = MockWeblogger.attached();
        when(tier.weblogEntryManager().getWeblogEntry(anyString()))
                .thenAnswer(call -> entries.get(call.<String>getArgument(0)));
        when(tier.weblogManager().getWeblog(blog.getId())).thenReturn(blog);
        when(tier.weblogManager().getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(blog));
    }

    @AfterEach
    void tearDown() {
        tier.detach();
    }

    /** Work the manager handed to its background pool, not yet run. */
    private final List<Runnable> background = new ArrayList<>();

    private JPADiscoveryManagerImpl manager(TrafficSource source) {
        return new JPADiscoveryManagerImpl(tier.weblogger(), source, Clock.fixed(NOW, ZoneOffset.UTC),
                background::add);
    }

    private static Weblog weblog(String id) {
        Weblog weblog = new Weblog();
        weblog.setId(id);
        weblog.setHandle(id.replace("-", ""));
        weblog.setName(id);
        return weblog;
    }

    /** {@code count} published entries on the blog, the first one newest. */
    private List<DiscoveryCandidate> candidates(int count) throws WebloggerException {
        List<DiscoveryCandidate> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            WeblogEntry entry = new WeblogEntry();
            entry.setId("entry-" + i);
            entry.setAnchor("post-" + i);
            entry.setStatus(PubStatus.PUBLISHED);
            entry.setWebsite(blog);
            entries.put(entry.getId(), entry);
            list.add(new DiscoveryCandidate(entry.getId(), blog.getId(), entry.getAnchor(),
                    NOW.minusSeconds(60L * i)));
        }
        when(tier.weblogEntryManager().getDiscoveryCandidates()).thenReturn(list);
        return list;
    }

    /**
     * The generation is what dates a rendering: a page rendered while it
     * names a new generation must already be reading that generation's
     * snapshot, or a generation-keyed cache keeps the old picks for a whole
     * refresh interval. A watcher sees each new generation and checks the
     * manager already carries it.
     */
    @Test
    void aGenerationIsPublishedOnlyAfterItsSnapshot() throws Exception {
        candidates(20_000);
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger early = new AtomicInteger();
        Thread watcher = new Thread(() -> {
            long seen = DiscoveryGeneration.current();
            while (!stop.get()) {
                long generation = DiscoveryGeneration.current();
                if (generation != seen) {
                    seen = generation;
                    if (manager.snapshotGeneration() < generation) {
                        early.incrementAndGet();
                    }
                }
            }
        });
        watcher.start();
        try {
            for (int i = 0; i < 15; i++) {
                manager.refresh();
            }
        } finally {
            stop.set(true);
            watcher.join(10_000);
        }

        assertEquals(0, early.get(), "a generation was visible before its snapshot");
        assertEquals(DiscoveryGeneration.current(), manager.snapshotGeneration());
    }

    /**
     * A page rendered from the old snapshot while a slow refresh reads is
     * dated by the old generation; stamped with the refresh's start time, the
     * new generation could be older than such a page, which would then
     * survive (and answer 304) a whole extra interval.
     */
    @Test
    void theGenerationIsStampedAfterTheReads() throws Exception {
        candidates(3);
        MovableClock clock = new MovableClock(Instant.now().minusSeconds(3600));
        AtomicLong afterRead = new AtomicLong();
        JPADiscoveryManagerImpl manager = new JPADiscoveryManagerImpl(tier.weblogger(),
                new Configured(firstDay -> {
                    // the read takes "an hour": the clock moves on while it runs
                    Instant later = Instant.now();
                    clock.set(later);
                    afterRead.set(later.toEpochMilli());
                    return List.of();
                }), clock, background::add);

        manager.refresh();

        assertTrue(manager.snapshotGeneration() >= afterRead.get(),
                "generation " + manager.snapshotGeneration() + " predates the end of the read " + afterRead.get());
        assertEquals(DiscoveryGeneration.current(), manager.snapshotGeneration());
    }

    /**
     * After a restart the snapshot is empty and the scheduled refresh may be
     * an interval away, so the first page builds one. It reads candidates
     * only (one local query) and leaves Umami to the background pool: no
     * page waits on traffic.
     */
    @Test
    void theFirstFrontPageNeverReadsTrafficOnTheCallingThread() throws Exception {
        candidates(3);
        blog.setAnalyticsSiteId("site-1");
        Configured source = new Configured(firstDay -> List.of(new TrafficRow("site-1", "post-2", 10)));
        JPADiscoveryManagerImpl manager = manager(source);

        FrontPageSelection first = manager.selectFrontPage();

        assertTrue(source.callers.isEmpty(), "traffic was read on " + source.callers);
        assertEquals("entry-0", first.headline().getId(), "no scores yet: the newest post leads");
        assertEquals(1, background.size(), "the scored refresh is handed to the background pool");
        assertEquals(DiscoveryStatus.State.NEVER, manager.getStatus().state(), "Umami has not been read yet");
        assertEquals(3, manager.getStatus().candidates());

        Thread worker = new Thread(background.get(0), "discovery-background");
        worker.start();
        worker.join(10_000);

        assertEquals(List.of("discovery-background"), source.callers);
        verify(tier.weblogger()).release();
        assertEquals(DiscoveryStatus.State.OK, manager.getStatus().state());
        assertEquals("entry-2", manager.selectFrontPage().headline().getId(), "the scores arrive with it");
        assertEquals(1, background.size(), "only the first build schedules one");
    }

    @Test
    void theFirstMoreFromSiteNeverReadsTrafficOnTheCallingThread() throws Exception {
        candidates(3);
        Configured source = new Configured(firstDay -> List.of());
        JPADiscoveryManagerImpl manager = manager(source);

        assertEquals(3, manager.selectMoreFromSite(weblog("other"), 3).size());

        assertTrue(source.callers.isEmpty(), "traffic was read on " + source.callers);
        assertEquals(1, background.size());
    }

    @Test
    void anUnconfiguredSourceBuildsInFullAndSchedulesNothing() throws Exception {
        candidates(3);
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());

        manager.selectFrontPage();

        assertEquals(DiscoveryStatus.State.DISABLED, manager.getStatus().state());
        assertTrue(background.isEmpty(), "nothing to score, so nothing to schedule");
    }

    @Test
    void theTierPoolRunsTheFirstScoredRefresh() throws Exception {
        candidates(3);
        JPADiscoveryManagerImpl manager = new JPADiscoveryManagerImpl(tier.weblogger(),
                new Configured(firstDay -> List.of()));

        assertNotNull(manager.selectFrontPage().headline());

        verify(tier.threadManager()).executeInBackground(any(Runnable.class));
    }

    @Test
    void aScoredRefreshThatCannotBeScheduledLeavesThePageAlone() throws Exception {
        candidates(3);
        doThrow(new InterruptedException()).when(tier.threadManager()).executeInBackground(any(Runnable.class));
        JPADiscoveryManagerImpl manager = new JPADiscoveryManagerImpl(tier.weblogger(),
                new Configured(firstDay -> List.of()));

        FrontPageSelection page = manager.selectFrontPage();

        assertTrue(Thread.interrupted(), "the interrupt is kept (and cleared here)");
        assertEquals("entry-0", page.headline().getId());
    }

    @Test
    void aCandidateReadFailureIsRecordedAndShowsNothing() throws Exception {
        when(tier.weblogEntryManager().getDiscoveryCandidates()).thenThrow(new WebloggerException("db down"));
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());

        FrontPageSelection page = manager.selectFrontPage();

        assertNull(page.headline());
        assertTrue(page.popular().isEmpty());
        assertTrue(page.allBlogs().isEmpty());
        assertTrue(manager.selectMoreFromSite(weblog("other"), 3).isEmpty());
        assertEquals(DiscoveryStatus.State.FAILED, manager.getStatus().state());
        assertEquals("db down", manager.getStatus().lastError());
    }

    @Test
    void aCandidateReadFailureKeepsThePreviousSnapshot() throws Exception {
        candidates(3);
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());
        manager.refresh();
        when(tier.weblogEntryManager().getDiscoveryCandidates()).thenThrow(new IllegalStateException());

        manager.refresh();

        assertEquals(DiscoveryStatus.State.FAILED, manager.getStatus().state());
        assertEquals("IllegalStateException", manager.getStatus().lastError(), "no message: the type stands in");
        assertEquals(3, manager.getStatus().candidates());
        assertEquals("entry-0", manager.selectFrontPage().headline().getId());
    }

    @Test
    void aRuntimeFailureReadingTrafficIsAFailedRefresh() throws Exception {
        candidates(3);
        JPADiscoveryManagerImpl manager = manager(new TrafficSource() {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public List<TrafficRow> viewsSince(LocalDate firstDay) {
                throw new IllegalStateException("driver blew up");
            }
        });

        manager.refresh();

        assertEquals(DiscoveryStatus.State.FAILED, manager.getStatus().state());
        assertNull(manager.getStatus().lastSuccess());
        assertEquals("entry-0", manager.selectFrontPage().headline().getId(), "no scores: newest leads");
    }

    @Test
    void nothingEligibleMeansNoHeadline() throws Exception {
        candidates(0);
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());

        FrontPageSelection page = manager.selectFrontPage();

        assertNull(page.headline());
        assertTrue(page.blogs().isEmpty());
        assertEquals(List.of(blog), page.allBlogs(), "a blog with no posts is still listed");
    }

    @Test
    void anEntryThatCannotBeLoadedIsLeftOut() throws Exception {
        candidates(2);
        when(tier.weblogEntryManager().getWeblogEntry(anyString())).thenThrow(new WebloggerException("gone"));
        when(tier.weblogManager().getWeblog(blog.getId())).thenThrow(new WebloggerException("gone"));
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());

        FrontPageSelection page = manager.selectFrontPage();

        assertNull(page.headline());
        assertTrue(page.allBlogs().isEmpty());
        assertTrue(manager.selectMoreFromSite(null, 3).isEmpty());
    }

    @Test
    void whenEverySurvivorIsOnThePageTheNewestOneLeads() throws Exception {
        // 9 on one blog, newest first: headline 1, popular 4, the one card 3, different 1
        candidates(9);
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());
        FrontPageSelection before = manager.selectFrontPage();
        assertEquals(1, before.different().size());
        entries.get(before.headline().getId()).setStatus(PubStatus.DRAFT);
        for (WeblogEntry entry : before.popular()) {
            entry.setStatus(PubStatus.DRAFT);
        }
        String newestSurvivor = null;
        for (int i = 0; i < 9 && newestSurvivor == null; i++) {
            if (entries.get("entry-" + i).getStatus() == PubStatus.PUBLISHED) {
                newestSurvivor = "entry-" + i;
            }
        }

        FrontPageSelection after = manager.selectFrontPage();

        assertNotNull(after.headline(), "null only when nothing eligible survives");
        assertEquals(newestSurvivor, after.headline().getId(), "no scores: the newest survivor, never a random pick");
        assertTrue(after.popular().isEmpty());
        List<String> all = allEntryIds(after);
        assertEquals(4, all.size(), "the four survivors, each once: " + all);
        assertEquals(4, new HashSet<>(all).size(), "moved to the headline, not copied: " + all);
    }

    @Test
    void aDroppedHeadlineFallsToTheBestRankedPostNotOnThePage() throws Exception {
        candidates(30);
        blog.setAnalyticsSiteId("site-1");
        when(tier.propertiesManager().getProperty(DiscoverySettings.SHARE_PERCENT))
                .thenReturn(new RuntimeConfigProperty(DiscoverySettings.SHARE_PERCENT, "100"));
        List<TrafficRow> rows = List.of(new TrafficRow("site-1", "post-5", 100),
                new TrafficRow("site-1", "post-7", 50), new TrafficRow("site-1", "post-8", 40),
                new TrafficRow("site-1", "post-9", 30));
        JPADiscoveryManagerImpl manager = manager(new TrafficSource() {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public List<TrafficRow> viewsSince(LocalDate firstDay) {
                return rows;
            }
        });
        manager.refresh();
        FrontPageSelection before = manager.selectFrontPage();
        assertEquals("entry-5", before.headline().getId());
        entries.get("entry-5").setStatus(PubStatus.DRAFT);

        FrontPageSelection after = manager.selectFrontPage();

        // every other section is all random at 100%; the headline must still be
        // the best-ranked survivor not already shown, then the newest one
        List<String> rest = allEntryIds(after).subList(1, allEntryIds(after).size());
        List<String> order = new ArrayList<>(List.of("entry-7", "entry-8", "entry-9"));
        for (int i = 0; i < 30; i++) {
            order.add("entry-" + i);
        }
        String expected = order.stream().filter(id -> !"entry-5".equals(id) && !rest.contains(id))
                .findFirst().orElseThrow();
        assertEquals(expected, after.headline().getId());
        assertEquals(ids(before.popular()), ids(after.popular()), "the random picks keep their places");
    }

    @Test
    void moreFromSiteForNoCountIsEmptyAndForNoBlogDrawsFromAll() throws Exception {
        candidates(4);
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());

        assertTrue(manager.selectMoreFromSite(blog, 0).isEmpty());
        assertEquals(3, manager.selectMoreFromSite(null, 3).size());
        assertTrue(manager.selectMoreFromSite(blog, 3).isEmpty(), "the only blog has no other blogs to show");
    }

    @Test
    void anIneligibleWeblogDropsItsCardAndListing() throws Exception {
        candidates(9);
        JPADiscoveryManagerImpl manager = manager(new Unconfigured());
        assertEquals(1, manager.selectFrontPage().blogs().size());
        blog.setActive(Boolean.FALSE);

        FrontPageSelection page = manager.selectFrontPage();

        assertTrue(page.blogs().isEmpty());
        assertTrue(page.allBlogs().isEmpty());
        assertNull(page.headline(), "its entries are no longer eligible either");
    }

    private static List<String> ids(List<WeblogEntry> list) {
        return list.stream().map(WeblogEntry::getId).toList();
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

    private static final class Unconfigured implements TrafficSource {
        @Override
        public boolean isConfigured() {
            return false;
        }

        @Override
        public List<TrafficRow> viewsSince(LocalDate firstDay) {
            return List.of();
        }
    }

    /** What a configured source answers. */
    @FunctionalInterface
    private interface Reads {
        List<TrafficRow> viewsSince(LocalDate firstDay);
    }

    /** A configured source that records the thread of every read. */
    private static final class Configured implements TrafficSource {
        final List<String> callers = new CopyOnWriteArrayList<>();
        private final Reads reads;

        Configured(Reads reads) {
            this.reads = reads;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public List<TrafficRow> viewsSince(LocalDate firstDay) {
            callers.add(Thread.currentThread().getName());
            return reads.viewsSince(firstDay);
        }
    }

    /** A clock a test moves by hand. */
    private static final class MovableClock extends Clock {
        private volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
