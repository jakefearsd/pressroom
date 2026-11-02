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
package com.wikantik.pressroom.business.analytics;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.KeyCount;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.PathCount;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.SeriesPoint;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.Summary;
import com.wikantik.pressroom.business.analytics.BlogTraffic.PostRow;
import com.wikantik.pressroom.business.analytics.SiteTraffic.BlogRow;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * The batch read, against the real database and a fake service: what a sync
 * writes into the local snapshots, what a failure leaves behind, and what the
 * readers answer from them.
 *
 * <p>The suite shares one database, so other classes' weblogs are registered
 * and read too (the fake answers them an empty report); assertions name this
 * class's own weblogs, and site totals count only views those blogs have.
 */
class AnalyticsReadTest {

    private AnalyticsTier tier;
    private User user;
    private String previousContext;
    private final List<String> createdSiteSpace = new ArrayList<>();
    /** The site space's zone before {@link #siteZone} changed it, when the suite already had one. */
    private String restoreZone;

    @BeforeEach
    void setUp() throws Exception {
        previousContext = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("");
        tier = new AnalyticsTier();
        user = TestUtils.setupUser("analyticsread");
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (restoreZone != null) {
            Weblog site = TestUtils.weblogger().getWeblogManager().getSiteSpace();
            site.setTimeZone(restoreZone);
            TestUtils.weblogger().getWeblogManager().saveWeblog(site);
            TestUtils.endSession(true);
        }
        tier.tearDown();
        for (String id : createdSiteSpace) {
            TestUtils.teardownWeblog(id);
        }
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
        WebloggerRuntimeConfig.setRelativeContextURL(previousContext);
    }

    @Test
    void readReplacesSnapshotWhole() throws Exception {
        Weblog blog = tier.addWeblogUnseen("readwhole", user);
        WeblogEntry entry = entry("tide-pools", blog);
        tier.service.reports.put(blog.getId(), report(10,
                List.of(new PathCount("/readwhole/entry/tide-pools", 7, 4),
                        new PathCount("/readwhole/category/beaches", 3, 2)),
                List.of(new KeyCount("google.com", 3))));

        sync();
        BlogTraffic first = tier.manager.getBlogTraffic(blog.getId(), "7d");

        assertEquals(TrafficState.OK, first.state());
        assertEquals(10, first.current().views());
        assertEquals(List.of(new PostRow(entry.getId(), "tide-pools", "/readwhole/entry/tide-pools", 7, 4, first
                        .posts().get(0).trend()),
                new PostRow(null, null, "/readwhole/category/beaches", 3, 2, first.posts().get(1).trend())),
                first.posts(), "a non-entry path keeps no entry and still counts");
        assertEquals(List.of(new KeyCount("google.com", 3)), first.breakdowns().get("sources"));
        assertEquals(30.0 / 7 - 1, first.posts().get(0).trend(), 1e-9,
                "the 7-day daily rate against the 30-day one: the same 7 views in both is 30/7 the usual rate");
        assertTrue(Double.isNaN(tier.manager.getBlogTraffic(blog.getId(), "90d").posts().get(0).trend()),
                "the longest period has nothing longer to compare with");

        tier.service.reports.put(blog.getId(), report(9,
                List.of(new PathCount("/readwhole/entry/tide-pools", 9, 5)),
                List.of(new KeyCount("bing.com", 1))));
        sync();
        BlogTraffic second = tier.manager.getBlogTraffic(blog.getId(), "7d");

        assertEquals(List.of("/readwhole/entry/tide-pools"), second.posts().stream().map(PostRow::path).toList(),
                "a path absent from the new read is gone");
        assertEquals(List.of(new KeyCount("bing.com", 1)), second.breakdowns().get("sources"));
        assertEquals(List.of(), second.breakdowns().get("places"), "every tab is present, empty when empty");
        assertEquals(9, second.current().views());
        assertFalse(second.asOf().isBefore(first.asOf()), "stamped with the new read");
        assertEquals(1L, rowCount("AnalyticsPathViews", blog.getId(), "7d"), "no stale rows behind the reader");
        assertEquals(Map.of(entry.getId(), 9L), tier.manager.entryViews(blog.getId(), "7d"));
    }

    @Test
    void failedReadKeepsPreviousSnapshotAndMarksStale() throws Exception {
        Weblog failing = tier.addWeblogUnseen("readstale", user);
        Weblog fine = tier.addWeblogUnseen("readfine", user);
        tier.service.reports.put(failing.getId(), report(10, List.of(new PathCount("/readstale/", 10, 6)), List.of()));
        tier.service.reports.put(fine.getId(), report(20, List.of(), List.of()));
        sync();
        Instant firstAsOf = tier.manager.getBlogTraffic(failing.getId(), "7d").asOf();

        tier.service.readFailIds.add(failing.getId());
        tier.service.reports.put(failing.getId(), report(99, List.of(), List.of()));
        tier.service.reports.put(fine.getId(), report(25, List.of(), List.of()));
        sync();

        BlogTraffic stale = tier.manager.getBlogTraffic(failing.getId(), "7d");
        assertEquals(TrafficState.STALE, stale.state());
        assertEquals(10, stale.current().views(), "the previous snapshot is kept");
        assertEquals(firstAsOf, stale.asOf(), "with its own age");
        assertEquals(List.of("/readstale/"), stale.posts().stream().map(PostRow::path).toList());
        assertTrue(stale.lastError().contains("HTTP 503"), stale.lastError());
        BlogTraffic other = tier.manager.getBlogTraffic(fine.getId(), "7d");
        assertEquals(TrafficState.OK, other.state(), "one blog's failure does not stop the others");
        assertEquals(25, other.current().views());
        AnalyticsStatus status = tier.manager.getStatus();
        assertTrue(status.lastReadError() != null && status.lastReadError().contains("HTTP 503"), status.toString());
        assertEquals(Boolean.TRUE, status.reachable(), "the service answered the other reads");
        assertEquals(TrafficState.STALE, tier.manager.getSiteTraffic("7d").state());

        tier.service.readFailIds.clear();
        sync();
        BlogTraffic recovered = tier.manager.getBlogTraffic(failing.getId(), "7d");
        assertEquals(TrafficState.OK, recovered.state());
        assertEquals(99, recovered.current().views());
        assertNull(recovered.lastError());
        assertNull(tier.manager.getStatus().lastReadError(), "a clean read clears it");
        assertNotNull(tier.manager.getStatus().lastRead());
    }

    @Test
    void aCacheThatCannotBeWrittenAbortsTheReadAndKeepsTheSnapshot() throws Exception {
        Weblog blog = tier.addWeblogUnseen("readdbfail", user);
        tier.service.reports.put(blog.getId(), report(10, List.of(new PathCount("/readdbfail/", 10, 6)), List.of()));
        sync();

        // PostgreSQL refuses a NUL in text, so this snapshot's commit fails
        tier.service.reports.put(blog.getId(), report(50, List.of(new PathCount("/readdbfail/\u0000", 50, 9)),
                List.of()));
        sync();

        BlogTraffic kept = tier.manager.getBlogTraffic(blog.getId(), "7d");
        assertEquals(10, kept.current().views(), "the failed transaction wrote nothing");
        assertEquals(List.of("/readdbfail/"), kept.posts().stream().map(PostRow::path).toList());
        assertEquals(TrafficState.STALE, kept.state());
        assertEquals(JPAAnalyticsManagerImpl.CACHE_FAILED, tier.manager.getStatus().lastReadError(),
                "a sync aborted by the database says so, in a short message of its own");
    }

    @Test
    void aServiceThatIsDownCostsOneReadAndOnePing() throws Exception {
        Weblog a = tier.addWeblogUnseen("downa", user);
        Weblog b = tier.addWeblogUnseen("downb", user);
        sync();
        tier.service.down = true;
        tier.service.reads.clear();
        tier.service.pings.set(0);

        sync();

        assertEquals(1, tier.service.reads.size(), "the first failure, then nothing more: " + tier.service.reads);
        assertEquals(1, tier.service.pings.get(), "one ping decided the service is down");
        assertEquals(Boolean.FALSE, tier.manager.getStatus().reachable(), "a run that reads refreshes reachable");
        for (Weblog weblog : List.of(a, b)) {
            BlogTraffic traffic = tier.manager.getBlogTraffic(weblog.getId(), "7d");
            assertEquals(TrafficState.STALE, traffic.state(), weblog.getHandle());
            assertNotNull(traffic.lastError(), weblog.getHandle());
        }
        assertTrue(tier.manager.getStatus().lastReadError().contains("more"),
                "every blog failed, the first one named: " + tier.manager.getStatus().lastReadError());
    }

    @Test
    void aReconcileThatStopsLeavesNothingForTheReadToCommit() throws Exception {
        Weblog blog = tier.addWeblogUnseen("halfmade", user);
        WeblogManager failing = spy(tier.weblogs);
        doThrow(new WebloggerException("the bump could not be saved"))
                .when(failing).saveWeblog(argThat(w -> w != null && blog.getId().equals(w.getId())));
        doReturn(failing).when(tier.weblogger).getWeblogManager();
        try {
            sync();
        } finally {
            doReturn(tier.weblogs).when(tier.weblogger).getWeblogManager();
        }

        assertTrue(count("SELECT COUNT(s) FROM AnalyticsSummary s") > 0, "the read went on and committed snapshots");
        assertEquals(0L, count("SELECT COUNT(s) FROM AnalyticsSite s WHERE s.weblogId = '" + blog.getId() + "'"),
                "the stopped reconcile's half-made registration row was not committed along with them");
    }

    @Test
    void entryPathsMapUnderAContextPathBeforeTheFirstRequest() throws Exception {
        Weblog blog = tier.addWeblogUnseen("ctxblog", user);
        WeblogEntry entry = entry("tide-pools", blog);
        WebloggerRuntimeConfig.setRelativeContextURL(null);
        tier.siteUrl = "http://host/pressroom/";
        tier.service.reports.put(blog.getId(), report(5,
                List.of(new PathCount("/pressroom/ctxblog/entry/tide-pools", 5, 3)), List.of()));

        sync();

        assertEquals(Map.of(entry.getId(), 5L), tier.manager.entryViews(blog.getId(), "7d"),
                "the context path comes from the site URL until a request latches one");
    }

    @Test
    void entryPathsMapAtTheRootBeforeTheFirstRequest() throws Exception {
        Weblog blog = tier.addWeblogUnseen("ctxroot", user);
        WeblogEntry entry = entry("tide-pools", blog);
        WebloggerRuntimeConfig.setRelativeContextURL(null);
        tier.siteUrl = "http://host/";
        tier.service.reports.put(blog.getId(), report(4,
                List.of(new PathCount("/ctxroot/entry/tide-pools", 4, 2)), List.of()));

        sync();

        assertEquals(Map.of(entry.getId(), 4L), tier.manager.entryViews(blog.getId(), "7d"),
                "a site URL with no path is the root");
    }

    @Test
    void visitorsAreNeverSummed() throws Exception {
        Weblog a = tier.addWeblogUnseen("visitsa", user);
        Weblog b = tier.addWeblogUnseen("visitsb", user);
        List<SeriesPoint> series = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 10, 26);
        for (int i = 0; i < 7; i++) {
            series.add(new SeriesPoint(day.plusDays(i), 2, 1));
        }
        tier.service.reports.put(a.getId(), new AnalyticsReport(Instant.now(), new Summary(5, 7, 10, 1, 60),
                new Summary(4, 5, 8, 1, 50), series, List.of(),
                breakdowns(List.of(new KeyCount("google.com", 4)))));
        tier.service.reports.put(b.getId(), new AnalyticsReport(Instant.now(), new Summary(6, 8, 20, 2, 70),
                new Summary(6, 8, 10, 2, 70), List.of(), List.of(),
                breakdowns(List.of(new KeyCount("google.com", 3), new KeyCount("bing.com", 1)))));

        sync();

        BlogTraffic blog = tier.manager.getBlogTraffic(a.getId(), "7d");
        assertEquals(5, blog.current().visitors(), "the period's visitors are the service's number");
        assertEquals(14, blog.series().stream().mapToLong(SeriesPoint::visitors).sum(),
                "even though the daily series sums higher");
        assertEquals(series, blog.series());

        SiteTraffic site = tier.manager.getSiteTraffic("7d");
        assertEquals(30, site.totalViews());
        assertEquals(15, site.totalVisits());
        Set<String> components = Arrays.stream(SiteTraffic.class.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet());
        assertTrue(components.stream().noneMatch(n -> n.toLowerCase(java.util.Locale.ROOT).contains("visitor")),
                "the site has no visitor total: " + components);
        BlogRow rowA = site.blogs().stream().filter(r -> a.getId().equals(r.weblogId())).findFirst().orElseThrow();
        assertEquals(5, rowA.visitors());
        assertEquals(10, rowA.views());
        assertEquals(0.25, rowA.change(), 1e-9, "views against the previous period");
        assertEquals("visitsa", rowA.handle());
        assertEquals(Map.of("google.com", 4L, "bing.com", 1L), site.topSources().stream()
                        .collect(Collectors.toMap(KeyCount::key, KeyCount::value)),
                "a source two blogs share shows the larger blog's visitors, never their sum");
    }

    @Test
    void siteTrafficListsTopPostsAndEachBlogsTopPost() throws Exception {
        Weblog a = tier.addWeblogUnseen("topa", user);
        Weblog b = tier.addWeblogUnseen("topb", user);
        WeblogEntry sunrise = entry("sunrise", a);
        WeblogEntry gone = entry("gone-soon", a);
        WeblogEntry sunset = entry("sunset", b);
        String tooLong = "/topa/" + "x".repeat(1100);
        tier.service.reports.put(a.getId(), report(30, List.of(new PathCount("/topa/entry/gone-soon", 15, 9),
                new PathCount("/topa/entry/sunrise", 8, 5), new PathCount("/topa/", 6, 4),
                new PathCount(tooLong, 1, 1)), List.of()));
        tier.service.reports.put(b.getId(), report(12, List.of(new PathCount("/topb/entry/sunset", 12, 7)),
                List.of()));
        sync();
        TestUtils.teardownWeblogEntry(gone.getId());
        TestUtils.endSession(true);

        SiteTraffic site = tier.manager.getSiteTraffic("7d");
        assertEquals(List.of(sunset.getId(), sunrise.getId()), site.topPosts().stream().map(PostRow::entryId).toList(),
                "entries only, most viewed first; an entry deleted since the read is no post");
        BlogRow rowA = site.blogs().stream().filter(r -> a.getId().equals(r.weblogId())).findFirst().orElseThrow();
        assertEquals("sunrise", rowA.topPostTitle());
        assertEquals(Double.NaN, rowA.change(), "no views before: no change to state");
        assertEquals(List.of("topa", "topb"), site.blogs().stream().map(BlogRow::handle)
                .filter(h -> h.startsWith("top")).toList(), "most viewed blog first");

        BlogTraffic blog = tier.manager.getBlogTraffic(a.getId(), "7d");
        assertEquals(List.of("/topa/entry/gone-soon", "/topa/entry/sunrise", "/topa/"),
                blog.posts().stream().map(PostRow::path).toList(), "a path too long to keep is left out");
        assertNull(blog.posts().get(0).entryId(), "the deleted entry's path is a plain path now");
        assertEquals(30, blog.current().views(), "and every view still counts");
    }

    @Test
    void stateDistinguishesNoDataStaleNotConfiguredAndZero() throws Exception {
        Weblog blog = tier.addWeblogUnseen("readstates", user);

        tier.manager.reconcile();
        TestUtils.endSession(false);
        BlogTraffic registeredOnly = tier.manager.getBlogTraffic(blog.getId(), "7d");
        assertEquals(TrafficState.NO_DATA, registeredOnly.state(), "registered, nothing read yet");
        assertNull(registeredOnly.current());
        assertNull(registeredOnly.asOf());
        assertEquals(TrafficState.NO_DATA, tier.manager.getSiteTraffic("7d").state(), "no blog read yet");
        assertThrows(IllegalArgumentException.class, () -> tier.manager.getBlogTraffic(blog.getId(), "week"));

        sync();
        assertEquals(TrafficState.NO_DATA, tier.manager.getBlogTraffic(blog.getId(), "7d").state(),
                "a zero-view snapshot within a day of registering is not yet data");

        registeredAt(blog.getId(), Instant.now().minus(2, ChronoUnit.DAYS));
        BlogTraffic zero = tier.manager.getBlogTraffic(blog.getId(), "7d");
        assertEquals(TrafficState.OK, zero.state(), "after a day, zero views is a number");
        assertEquals(0, zero.current().views());

        tier.service.readFailIds.add(blog.getId());
        sync();
        assertEquals(TrafficState.STALE, tier.manager.getBlogTraffic(blog.getId(), "7d").state());

        tier.service.configured = false;
        assertEquals(TrafficState.NOT_CONFIGURED, tier.manager.getBlogTraffic(blog.getId(), "7d").state());
        assertEquals(TrafficState.NOT_CONFIGURED, tier.manager.getSiteTraffic("7d").state());
        assertEquals(Map.of(), tier.manager.entryViews(blog.getId(), "7d"));
        assertEquals(Map.of(), tier.manager.entryViewsAllBlogs(30));
    }

    @Test
    void discoveryWindowIsReadWhenNotAStandardPeriod() throws Exception {
        Weblog a = tier.addWeblogUnseen("windowa", user);
        Weblog b = tier.addWeblogUnseen("windowb", user);
        WeblogEntry entryA = entry("first-light", a);
        WeblogEntry entryB = entry("low-tide", b);
        tier.service.reports.put(a.getId(), report(5, List.of(new PathCount("/windowa/entry/first-light", 3, 2),
                new PathCount("/windowa/entry/first-light/", 2, 2)), List.of()));
        tier.service.reports.put(b.getId(), report(4, List.of(new PathCount("/windowb/entry/low-tide", 4, 3)),
                List.of()));
        ZoneId zone = siteZone("Pacific/Auckland");

        assertEquals(List.of("24h", "7d", "30d", "90d"), tier.manager.periodKeys(), "30 days is a standard period");
        tier.windowDays = 45;
        assertEquals(List.of("24h", "7d", "30d", "90d", "45d"), tier.manager.periodKeys());

        sync();

        assertEquals(Set.of("24h", "7d", "30d", "90d", "45d"), tier.service.reads.stream()
                .filter(r -> a.getId().equals(r.websiteId())).map(FakeAnalyticsService.Read::period)
                .collect(Collectors.toSet()));
        assertTrue(tier.service.reads.stream().allMatch(r -> zone.equals(r.zone())),
                "every read is in the site space's time zone (R12)");
        assertEquals(Map.of(entryA.getId(), 5L, entryB.getId(), 4L), tier.manager.entryViewsAllBlogs(45),
                "views per entry, across blogs and across a path's two spellings");
        assertEquals(Map.of(entryA.getId(), 5L, entryB.getId(), 4L), tier.manager.entryViewsAllBlogs(30));
    }

    // ------------------------------------------------------------ helpers

    private void sync() throws Exception {
        tier.manager.sync();
        TestUtils.endSession(false);
    }

    private WeblogEntry entry(String anchor, Weblog weblog) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, TestUtils.getManagedWebsite(weblog), user);
        TestUtils.endSession(true);
        return entry;
    }

    private static AnalyticsReport report(long views, List<PathCount> paths, List<KeyCount> sources) {
        return new AnalyticsReport(Instant.now(), new Summary(views / 2, views / 2, views, 0, 0),
                new Summary(0, 0, 0, 0, 0), List.of(), paths, breakdowns(sources));
    }

    private static Map<String, List<KeyCount>> breakdowns(List<KeyCount> sources) {
        Map<String, List<KeyCount>> breakdowns = FakeAnalyticsService.emptyBreakdowns();
        breakdowns.put("sources", sources);
        return breakdowns;
    }

    private long rowCount(String entity, String weblogId, String period) {
        return tier.strategy.getEntityManager(false)
                .createQuery("SELECT COUNT(x) FROM " + entity + " x WHERE x.weblogId = :w AND x.period = :p",
                        Long.class)
                .setParameter("w", weblogId).setParameter("p", period).getSingleResult();
    }

    private long count(String jpql) {
        return tier.strategy.getEntityManager(false).createQuery(jpql, Long.class).getSingleResult();
    }

    private void registeredAt(String weblogId, Instant at) throws Exception {
        tier.strategy.getEntityManager(true)
                .createQuery("UPDATE AnalyticsSite s SET s.registeredAt = :t WHERE s.weblogId = :w")
                .setParameter("t", at).setParameter("w", weblogId).executeUpdate();
        tier.strategy.flush();
        TestUtils.endSession(false);
    }

    /** Gives the site space this zone (creating it if the suite has none) and returns it. */
    private ZoneId siteZone(String zone) throws Exception {
        Weblog site = TestUtils.weblogger().getWeblogManager().getSiteSpace();
        if (site == null) {
            site = TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(user);
            TestUtils.endSession(true);
            createdSiteSpace.add(site.getId());
            site = TestUtils.weblogger().getWeblogManager().getSiteSpace();
        }
        String previous = site.getTimeZone();
        site.setTimeZone(zone);
        TestUtils.weblogger().getWeblogManager().saveWeblog(site);
        TestUtils.endSession(true);
        if (createdSiteSpace.isEmpty()) {
            restoreZone = previous;
        }
        return ZoneId.of(zone);
    }

}
