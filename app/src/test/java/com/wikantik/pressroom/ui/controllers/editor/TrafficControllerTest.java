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

package com.wikantik.pressroom.ui.controllers.editor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.KeyCount;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.SeriesPoint;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.Summary;
import com.wikantik.pressroom.business.analytics.BlogTraffic;
import com.wikantik.pressroom.business.analytics.BlogTraffic.PostRow;
import com.wikantik.pressroom.business.analytics.TrafficState;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TrafficController}: the per-blog Traffic page, read from
 * the local snapshot only.
 */
class TrafficControllerTest extends EditorControllerTestSupport {

    private static final Instant AS_OF = Instant.parse("2026-11-01T19:05:00Z");
    private static final List<String> TABS =
            List.of("posts", "sources", "places", "devices", "events", "campaigns", "speed");

    private TrafficController controller;
    private AnalyticsManager analytics;
    private Model model;
    private String previousDashboard;

    @BeforeEach
    void setUp() {
        controller = prepare(new TrafficController());
        controller.setClock(java.time.Clock.fixed(Instant.parse("2026-11-01T19:35:00Z"), java.time.ZoneOffset.UTC));
        model = newModel();
        analytics = mock(AnalyticsManager.class);
        when(weblogger.weblogger().getAnalyticsManager()).thenReturn(analytics);
        when(analytics.periodKeys()).thenReturn(List.of("24h", "7d", "30d", "90d", "45d"));
        previousDashboard = overrideConfigProperty("analytics.umami.dashboardUrl", "");
    }

    @AfterEach
    void restoreConfig() {
        overrideConfigProperty("analytics.umami.dashboardUrl", previousDashboard);
    }

    // ------------------------------------------------------------ fixtures

    private static Map<String, List<KeyCount>> breakdowns() {
        Map<String, List<KeyCount>> map = new LinkedHashMap<>();
        for (String key : List.of("sources", "places", "devices", "events", "campaigns", "speed")) {
            map.put(key, List.of());
        }
        return map;
    }

    private static BlogTraffic data(TrafficState state, Summary current, Summary previous,
                                    List<PostRow> posts, Map<String, List<KeyCount>> breakdowns) {
        return new BlogTraffic(state, AS_OF, current, previous,
                List.of(new SeriesPoint(LocalDate.of(2026, 10, 30), 4, 9),
                        new SeriesPoint(LocalDate.of(2026, 10, 31), 6, 18),
                        new SeriesPoint(LocalDate.of(2026, 11, 1), 0, 0)),
                posts, breakdowns, state == TrafficState.STALE ? "boom" : null);
    }

    private static BlogTraffic ok() {
        return data(TrafficState.OK, new Summary(1284, 1602, 3977, 769, 160_000),
                new Summary(1088, 1393, 3286, 640, 150_000), List.of(), breakdowns());
    }

    private void serve(String period, BlogTraffic traffic) {
        when(analytics.getBlogTraffic("weblog-1", period)).thenReturn(traffic);
    }

    private TrafficView view(String period, String tab, String sort) {
        controller.execute(request, model, period, tab, sort);
        return (TrafficView) model.getAttribute("traffic");
    }

    private TrafficView view(String period, String tab) {
        return view(period, tab, "views");
    }

    private static String text(TrafficView.Tile tile) {
        return tile.value() + " " + tile.change();
    }

    // ------------------------------------------------------------ metadata

    @Test
    void declaresItsMenuAndNeedsWeblogAdmin() {
        assertEquals("traffic", controller.getActionName());
        assertEquals("editor", controller.getDesiredMenu());
        assertEquals(List.of(WeblogPermission.ADMIN), controller.requiredWeblogPermissionActions());
    }

    @Test
    void requiresWeblogAdmin() {
        // a post-only member holds POST only; the interceptor refuses on exactly this list
        assertFalse(controller.requiredWeblogPermissionActions().contains(WeblogPermission.POST));
    }

    @Test
    void theViewIsTheTrafficTile() {
        serve("30d", ok());
        assertEquals(".Traffic", controller.execute(request, model, "30d", "posts", "views"));
    }

    // ----------------------------------------------------------- headlines

    @Test
    void showsFiveHeadlinesWithChange() {
        serve("30d", ok());
        List<TrafficView.Tile> tiles = view("30d", "posts").tiles();

        assertEquals(5, tiles.size());
        assertEquals(List.of("traffic.tile.visitors", "traffic.tile.visits", "traffic.tile.views",
                "traffic.tile.bounce", "traffic.tile.time"),
                tiles.stream().map(TrafficView.Tile::labelKey).toList());
        assertEquals("1,284 +18%", text(tiles.get(0)));
        assertEquals("1,602 +15%", text(tiles.get(1)));
        assertEquals("3,977 +21%", text(tiles.get(2)));
        // 769/1602 = 48.0%; previous 640/1393 = 45.9% -> +2 points; a higher bounce is worse
        assertEquals("48% +2 pts", text(tiles.get(3)));
        assertEquals("dn", tiles.get(3).trend());
        assertEquals("up", tiles.get(0).trend());
        // 160000/1602 = 99.9s -> 1:40 ; previous 150000/1393 = 107.7s -> down 8s
        assertEquals("1:40 -8s", text(tiles.get(4)));
    }

    @Test
    void aChangeAgainstNothingIsADashNotInfinityOrNaN() {
        serve("30d", data(TrafficState.OK, new Summary(10, 12, 30, 6, 600),
                new Summary(0, 0, 0, 0, 0), List.of(), breakdowns()));
        for (TrafficView.Tile tile : view("30d", "posts").tiles()) {
            assertEquals("—", tile.change(), tile.labelKey());
            assertEquals("", tile.trend());
        }
    }

    @Test
    void anAbsentPreviousIsAlsoADash() {
        serve("30d", data(TrafficState.OK, new Summary(10, 12, 30, 6, 600), null, List.of(), breakdowns()));
        TrafficView.Tile visitors = view("30d", "posts").tiles().get(0);
        assertEquals("10", visitors.value());
        assertEquals("—", visitors.change());
    }

    @Test
    void noVisitsMeansNoBounceRateOrVisitTimeNotNaN() {
        serve("30d", data(TrafficState.OK, new Summary(0, 0, 0, 0, 0), new Summary(5, 6, 9, 3, 60),
                List.of(), breakdowns()));
        List<TrafficView.Tile> tiles = view("30d", "posts").tiles();
        assertEquals("—", tiles.get(3).value());
        assertEquals("—", tiles.get(4).value());
        assertFalse(text(tiles.get(3)).contains("NaN"));
    }

    // -------------------------------------------------------- tabs, period

    @Test
    void rendersEachTab() {
        serve("30d", ok());
        for (String tab : TABS) {
            assertEquals(tab, view("30d", tab).tab());
        }
        assertEquals(TABS, view("30d", "posts").tabs());
    }

    @Test
    void unknownTabFallsBackToPosts() {
        serve("30d", ok());
        assertEquals("posts", view("30d", "nonsense").tab());
        assertEquals("posts", view("30d", null).tab());
    }

    @Test
    void unknownPeriodFallsBackTo30d() {
        serve("30d", ok());
        assertEquals("30d", view("12h", "posts").period());
        // the discovery window is read, but the switch offers only four
        assertEquals("30d", view("45d", "posts").period());
        assertEquals(List.of("24h", "7d", "30d", "90d"), view("30d", "posts").periods());
        verify(analytics, org.mockito.Mockito.never()).getBlogTraffic("weblog-1", "45d");
    }

    @Test
    void eachOfTheFourPeriodsIsServed() {
        for (String period : List.of("24h", "7d", "30d", "90d")) {
            serve(period, ok());
            assertEquals(period, view(period, "posts").period());
        }
    }

    // --------------------------------------------------------------- posts

    @Test
    void postsTabLinksEntriesAndShowsRawPaths() {
        serve("30d", data(TrafficState.OK, new Summary(5, 5, 50, 1, 10), new Summary(5, 5, 50, 1, 10),
                List.of(new PostRow("e1", "Tide &amp; pools", "/blog/tide", 30, 20, 0.5),
                        new PostRow(null, null, "/about?x=<b>", 20, 15, Double.NaN)),
                breakdowns()));
        List<TrafficView.PostLine> posts = view("30d", "posts").posts();

        assertEquals(2, posts.size());
        assertEquals("e1", posts.get(0).entryId());
        assertEquals("Tide &amp; pools", posts.get(0).title(), "stored escaped, passed through untouched");
        assertNull(posts.get(1).entryId());
        assertNull(posts.get(1).title());
        assertEquals("/about?x=<b>", posts.get(1).path(), "raw; the JSP escapes it");
        assertEquals("+50%", posts.get(0).trendText());
        assertEquals("—", posts.get(1).trendText(), "NaN is a dash, never the word");
    }

    /** Ruling R23: a snapshot that holds only the top N paths says so on the Posts tab. */
    @Test
    void aCappedPathListIsNotedOnThePostsTab() {
        BlogTraffic full = ok();
        serve("30d", new BlogTraffic(full.state(), full.asOf(), full.current(), full.previous(), full.series(),
                full.posts(), full.breakdowns(), full.lastError(), 1000));

        assertEquals(1000, view("30d", "posts").pathsCappedAt());

        serve("30d", ok());
        assertEquals(0, view("30d", "posts").pathsCappedAt(), "a complete list has no note");
    }

    @Test
    void rowsForOneEntryUnderTwoPathSpellingsMergeWithoutSummingVisitors() {
        serve("30d", data(TrafficState.OK, new Summary(5, 5, 50, 1, 10), new Summary(5, 5, 50, 1, 10),
                List.of(new PostRow("e1", "Tide", "/blog/tide", 30, 20, 0.5),
                        new PostRow("e1", "Tide", "/blog/tide/", 10, 25, 1.5),
                        new PostRow(null, null, "/a", 5, 4, 0.0),
                        new PostRow(null, null, "/b", 4, 3, 0.0)),
                breakdowns()));
        List<TrafficView.PostLine> posts = view("30d", "posts").posts();

        assertEquals(3, posts.size(), "the two entry rows merge; the two entry-less rows stay apart");
        TrafficView.PostLine merged = posts.get(0);
        assertEquals(40, merged.views());
        assertEquals(25, merged.visitors(), "visitors are never summed; the larger is kept");
        assertEquals("/blog/tide", merged.path());
        assertEquals("+50%", merged.trendText(), "the merged row takes the better-viewed spelling's trend");
    }

    @Test
    void postsSortByTheRequestedColumnAndFallBackToViews() {
        serve("30d", data(TrafficState.OK, new Summary(5, 5, 50, 1, 10), new Summary(5, 5, 50, 1, 10),
                List.of(new PostRow("a", "A", "/a", 30, 5, 0.1),
                        new PostRow("b", "B", "/b", 20, 15, Double.NaN),
                        new PostRow("c", "C", "/c", 10, 9, 0.9)),
                breakdowns()));
        assertEquals(List.of("a", "b", "c"), ids(view("30d", "posts", "views")));
        assertEquals(List.of("b", "c", "a"), ids(view("30d", "posts", "visitors")));
        assertEquals(List.of("c", "a", "b"), ids(view("30d", "posts", "trend")), "NaN sorts last");
        assertEquals(List.of("a", "b", "c"), ids(view("30d", "posts", "bogus")));
        assertEquals("views", view("30d", "posts", "bogus").sort());
        assertEquals("trend", view("30d", "posts", "trend").sort());
    }

    private static List<String> ids(TrafficView view) {
        return view.posts().stream().map(TrafficView.PostLine::entryId).toList();
    }

    // ----------------------------------------------------------- breakdowns

    @Test
    void breakdownTabsCarryTheirRowsWithGroupsAndFormatting() {
        Map<String, List<KeyCount>> b = breakdowns();
        b.put("sources", List.of(new KeyCount("google.com", 9), new KeyCount("channel:organic", 4)));
        b.put("devices", List.of(new KeyCount("device:mobile", 7), new KeyCount("browser:Chrome", 5),
                new KeyCount("os:Android", 3)));
        b.put("campaigns", List.of(new KeyCount("utm_source:newsletter", 12)));
        b.put("speed", List.of(new KeyCount("lcp", 1900), new KeyCount("cls", 120), new KeyCount("ttfb", 85)));
        serve("30d", data(TrafficState.OK, new Summary(5, 5, 50, 1, 10), new Summary(5, 5, 50, 1, 10),
                List.of(), b));

        List<TrafficView.Row> sources = view("30d", "sources").rows();
        assertEquals("", sources.get(0).group());
        assertEquals("google.com", sources.get(0).label());
        assertEquals("channel", sources.get(1).group());
        assertEquals("organic", sources.get(1).label());

        List<TrafficView.Row> devices = view("30d", "devices").rows();
        assertEquals(List.of("device", "browser", "os"),
                devices.stream().map(TrafficView.Row::group).toList());
        assertEquals("mobile", devices.get(0).label());

        TrafficView.Row campaign = view("30d", "campaigns").rows().get(0);
        assertEquals("utm_source", campaign.group());
        assertEquals("newsletter", campaign.label());

        List<TrafficView.Row> speed = view("30d", "speed").rows();
        assertEquals("1.9 s", speed.get(0).value());
        assertEquals("0.12", speed.get(1).value(), "CLS is stored times 1000");
        assertEquals("85 ms", speed.get(2).value());
        assertEquals("lcp", speed.get(0).label());
    }

    @Test
    void anEmptyTabHasNoRowsNotZeroRows() {
        serve("30d", ok());
        for (String tab : List.of("sources", "places", "devices", "events", "campaigns", "speed")) {
            assertTrue(view("30d", tab).rows().isEmpty(), tab);
        }
    }

    // --------------------------------------------------------------- states

    @Test
    void stateMessagesAreDistinct() {
        List<String> keys = new ArrayList<>();
        serve("30d", data(TrafficState.NOT_CONFIGURED, null, null, List.of(), breakdowns()));
        keys.add(view("30d", "posts").noticeKey());
        serve("30d", new BlogTraffic(TrafficState.NO_DATA, null, null, null, null, null, null, null));
        keys.add(view("30d", "posts").noticeKey());
        serve("30d", data(TrafficState.STALE, new Summary(5, 5, 50, 1, 10), null, List.of(), breakdowns()));
        keys.add(view("30d", "posts").noticeKey());
        serve("30d", data(TrafficState.OK, new Summary(0, 0, 0, 0, 0), new Summary(0, 0, 0, 0, 0),
                List.of(), breakdowns()));
        keys.add(view("30d", "posts").noticeKey());
        serve("30d", ok());
        keys.add(view("30d", "posts").noticeKey());

        assertEquals(List.of("traffic.state.notConfigured", "traffic.state.noData", "traffic.state.stale",
                "traffic.state.zero", ""), keys);
    }

    @Test
    void notConfiguredAndNoDataShowNoNumbers() {
        serve("30d", new BlogTraffic(TrafficState.NOT_CONFIGURED, null, null, null, null, null, null, null));
        TrafficView v = view("30d", "posts");
        assertTrue(v.tiles().isEmpty());
        assertTrue(v.chart().bars().isEmpty());
        assertFalse(v.showBody());
        assertNull(v.asOfText());
    }

    @Test
    void noDataWithAZeroSummaryStillShowsNoNumbers() {
        serve("30d", new BlogTraffic(TrafficState.NO_DATA, AS_OF, new Summary(0, 0, 0, 0, 0),
                new Summary(0, 0, 0, 0, 0), List.of(), List.of(), breakdowns(), null));
        TrafficView v = view("30d", "posts");
        assertEquals("traffic.state.noData", v.noticeKey());
        assertTrue(v.tiles().isEmpty());
        assertFalse(v.showBody());
        assertTrue(v.chart().bars().isEmpty());
    }

    @Test
    void aGenuineZeroStillShowsZeroTiles() {
        serve("30d", data(TrafficState.OK, new Summary(0, 0, 0, 0, 0), new Summary(0, 0, 0, 0, 0),
                List.of(), breakdowns()));
        TrafficView v = view("30d", "posts");
        assertEquals(5, v.tiles().size());
        assertEquals("0", v.tiles().get(0).value());
        assertTrue(v.showBody());
    }

    @Test
    void staleShowsItsAgeAndKeepsTheOldNumbers() {
        serve("30d", data(TrafficState.STALE, new Summary(5, 5, 50, 1, 10), null, List.of(), breakdowns()));
        TrafficView v = view("30d", "posts");
        assertEquals(30, v.ageMinutes());
        assertEquals(5, v.tiles().size());
        assertTrue(v.showBody());
    }

    @Test
    void asOfLineShown() {
        serve("30d", ok());
        // 19:05Z is 14:05 in the blog's America/New_York zone
        assertEquals("14:05", view("30d", "posts").asOfText());
    }

    // ---------------------------------------------------------------- chart

    @Test
    void theChartHasABarPerBucketScaledToTheLargest() {
        serve("30d", ok());
        TrafficView.Chart chart = view("30d", "posts").chart();
        assertEquals(3, chart.bars().size());
        TrafficView.Bar tallest = chart.bars().get(1);
        TrafficView.Bar half = chart.bars().get(0);
        TrafficView.Bar none = chart.bars().get(2);
        assertEquals(chart.plotHeight(), tallest.height());
        assertEquals(chart.plotHeight() / 2, half.height());
        assertEquals(0, none.height());
        assertEquals(chart.plotHeight() - tallest.height(), tallest.y());
        assertEquals("Oct 31: 18", tallest.title());
    }

    @Test
    void theTwentyFourHourChartLabelsHours() {
        List<SeriesPoint> hours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            hours.add(new SeriesPoint(LocalDate.of(2026, 11, 1), h, 1, h));
        }
        serve("24h", new BlogTraffic(TrafficState.OK, AS_OF, new Summary(1, 1, 1, 0, 0),
                new Summary(1, 1, 1, 0, 0), hours, List.of(), breakdowns(), null));
        TrafficView.Chart chart = view("24h", "posts").chart();
        assertEquals(24, chart.bars().size());
        assertEquals("05:00: 5", chart.bars().get(5).title());
        assertEquals("00", chart.bars().get(0).label());
        assertTrue(chart.bars().stream().filter(b -> b.label() != null).count() < 24, "labels are thinned");
    }

    // ---------------------------------------------------------- Open in Umami

    @Test
    void umamiLinkOnlyWhenConfigured() {
        serve("30d", ok());
        when(analytics.websiteIdFor("weblog-1")).thenReturn("weblog-1");

        overrideConfigProperty("analytics.umami.dashboardUrl", "");
        assertNull(view("30d", "posts").umamiUrl());

        overrideConfigProperty("analytics.umami.dashboardUrl", "https://stats.example.com/");
        assertEquals("https://stats.example.com/websites/weblog-1", view("30d", "posts").umamiUrl());

        overrideConfigProperty("analytics.umami.dashboardUrl", "javascript:alert(1)");
        assertNull(view("30d", "posts").umamiUrl(), "only http(s) is linked");

        overrideConfigProperty("analytics.umami.dashboardUrl", "https://stats.example.com");
        when(analytics.websiteIdFor("weblog-1")).thenReturn(null);
        assertNull(view("30d", "posts").umamiUrl(), "an unregistered blog has no Umami website to open");
    }

    @Test
    void theUmamiWebsiteIdIsPathEncoded() {
        serve("30d", ok());
        overrideConfigProperty("analytics.umami.dashboardUrl", "https://stats.example.com");
        when(analytics.websiteIdFor("weblog-1")).thenReturn("a b/c");
        assertEquals("https://stats.example.com/websites/a%20b%2Fc", view("30d", "posts").umamiUrl());
    }

    // ------------------------------------------------------------------ AC 8

    @Test
    void pageNeverCallsAnalyticsService() {
        serve("30d", ok());
        when(analytics.websiteIdFor("weblog-1")).thenReturn("weblog-1");
        overrideConfigProperty("analytics.umami.dashboardUrl", "https://stats.example.com");

        view("30d", "posts");

        // the local cache and memory only: never sync, reconcile or anything else
        verify(analytics).getBlogTraffic("weblog-1", "30d");
        verify(analytics).websiteIdFor("weblog-1");
        verifyNoMoreInteractions(analytics);
    }

    @Test
    void theControllerReachesNoUmamiClass() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/wikantik/pressroom/ui/controllers/editor/TrafficController.java"));
        assertFalse(source.contains("UmamiAnalyticsService"));
        assertFalse(source.contains("java.net.http"));
    }

    // ------------------------------------------------------------ escaping

    @Test
    void blogNameIsEscapedEverywhere() {
        weblog.setName("<img src=x onerror=alert(1)>");
        serve("30d", ok());
        view("30d", "posts");
        // the controller adds no message argument at all; the heading's argument is
        // escaped in the JSP (see TrafficJspTest). Nothing it adds may carry the name.
        assertTrue(errors(model).isEmpty());
        assertTrue(messages(model).isEmpty());
        for (Object value : model.asMap().values()) {
            if (value instanceof String s) {
                assertFalse(s.contains("<img"), s);
            }
        }
    }
}
