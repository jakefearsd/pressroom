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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.wikantik.pressroom.business.analytics.AnalyticsReport.KeyCount;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.SeriesPoint;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.Summary;
import com.wikantik.pressroom.business.analytics.BlogTraffic;
import com.wikantik.pressroom.business.analytics.BlogTraffic.PostRow;
import com.wikantik.pressroom.business.analytics.TrafficState;
import com.wikantik.pressroom.business.analytics.UmamiAnalyticsService;

/**
 * Everything the Traffic page shows, worked out from one {@link BlogTraffic}
 * snapshot so that the JSP has only to print it: formatted headline numbers
 * and their change, the server-drawn chart, the merged and sorted post rows,
 * and one tab's breakdown rows.
 *
 * <p>The values here are display strings or plain numbers. {@code title} on a
 * {@link PostLine} is the entry title as stored, which is already escaped: the
 * JSP emits it bare. Every other free text ({@code path}, {@code label}) is raw
 * and the JSP escapes it.
 *
 * @param period     the period shown, one of {@link #PERIODS}
 * @param periods    the periods the switch offers
 * @param tab        the tab shown, one of {@link #TABS}
 * @param tabs       every tab, in order
 * @param sort       the Posts sort column
 * @param noticeKey  the bundle key of the state message, or empty when the numbers speak for themselves
 * @param ageMinutes for a stale snapshot, how long ago it was read
 * @param asOfText   when the snapshot was read, as HH:mm in the blog's zone, or null
 * @param showBody   whether there are numbers to show at all
 * @param tiles      the five headline numbers, or none
 * @param chart      the trend chart
 * @param posts      the Posts tab's rows
 * @param rows       the selected breakdown tab's rows
 * @param umamiUrl   where "Open in Umami" goes, or null
 */
public record TrafficView(String period, List<String> periods, String tab, List<String> tabs, String sort,
                          String noticeKey, long ageMinutes, String asOfText, boolean showBody,
                          List<Tile> tiles, Chart chart, List<PostLine> posts, List<Row> rows, String umamiUrl) {

    /** The periods the switch offers; the discovery window is read but not offered. */
    public static final List<String> PERIODS = List.of("24h", "7d", "30d", "90d");

    /** The tabs, in the order shown; also the accepted {@code tab} values. */
    public static final List<String> TABS =
            List.of("posts", "sources", "places", "devices", "events", "campaigns", "speed");

    /** The accepted {@code sort} values; the first is the default. */
    public static final List<String> SORTS = List.of("views", "visitors", "trend");

    /**
     * The bundle keys TrafficBody.jsp builds as a prefix plus a value ({@code traffic.tab.} plus the tab,
     * and so on), written out whole so that a rename or a deletion finds them, as it does for any key a
     * page names outright. {@code TrafficJspTest} checks the list against the bundle and the JSP.
     */
    public static final List<String> DYNAMIC_MESSAGE_KEYS = List.of(
            "traffic.column.name.campaigns",
            "traffic.column.name.devices",
            "traffic.column.name.events",
            "traffic.column.name.places",
            "traffic.column.name.sources",
            "traffic.column.name.speed",
            "traffic.column.trend",
            "traffic.column.value.campaigns",
            "traffic.column.value.devices",
            "traffic.column.value.events",
            "traffic.column.value.places",
            "traffic.column.value.sources",
            "traffic.column.value.speed",
            "traffic.column.views",
            "traffic.column.visitors",
            "traffic.group.browser",
            "traffic.group.channel",
            "traffic.group.device",
            "traffic.group.os",
            "traffic.group.utm_campaign",
            "traffic.group.utm_content",
            "traffic.group.utm_medium",
            "traffic.group.utm_source",
            "traffic.group.utm_term",
            "traffic.period.24h",
            "traffic.period.30d",
            "traffic.period.7d",
            "traffic.period.90d",
            "traffic.speed.cls",
            "traffic.speed.fcp",
            "traffic.speed.inp",
            "traffic.speed.lcp",
            "traffic.speed.ttfb",
            "traffic.tab.campaigns",
            "traffic.tab.devices",
            "traffic.tab.events",
            "traffic.tab.places",
            "traffic.tab.posts",
            "traffic.tab.sources",
            "traffic.tab.speed");

    /** An em dash: "no figure", never NaN or an infinite change. */
    static final String DASH = "—";

    private static final int CHART_WIDTH = 600;
    private static final int CHART_HEIGHT = 140;
    private static final int PLOT_HEIGHT = 110;
    private static final int MAX_LABELS = 8;

    private static final Map<String, Set<String>> GROUPS = Map.of(
            "sources", Set.of("channel"),
            "devices", Set.of("device", "browser", "os"),
            "campaigns", Set.of("utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term"));
    private static final List<String> DEVICE_ORDER = List.of("device", "browser", "os");
    private static final Set<String> MILLISECOND_METRICS = Set.of("lcp", "inp", "fcp", "ttfb");

    /** One headline number. {@code trend} is "up" (good), "dn" (bad) or empty. */
    public record Tile(String labelKey, String value, String change, String trend) {
    }

    /** The chart's frame: an SVG of {@code width} by {@code height} whose bars fill {@code plotHeight}. */
    public record Chart(int width, int height, int plotHeight, List<Bar> bars) {
    }

    /** One bar; {@code label} is null for a bar whose label is thinned out. */
    public record Bar(int x, int y, int width, int height, String title, String label) {
    }

    /** One Posts row; {@code entryId} and {@code title} are null for a path that is not an entry. */
    public record PostLine(String entryId, String title, String path, long views, long visitors,
                           String trendText, String trendClass) {
    }

    /** One breakdown row, under {@code group} (empty when the tab has none). */
    public record Row(String group, String label, String value) {
    }

    /** Builds the view; {@code umamiUrl} is decided by the caller. */
    static TrafficView of(BlogTraffic traffic, String period, String tab, String sort, String umamiUrl,
                          Locale locale, ZoneId zone, Instant now) {
        TrafficState state = traffic.state();
        Summary current = traffic.current();
        boolean showBody = current != null;
        Instant asOf = traffic.asOf();
        long age = state == TrafficState.STALE && asOf != null
                ? Math.max(0, Duration.between(asOf, now).toMinutes()) : 0;
        List<Tile> tiles = showBody ? tiles(current, traffic.previous(), locale) : List.of();
        Chart chart = showBody ? chart(traffic.series(), locale) : new Chart(CHART_WIDTH, CHART_HEIGHT, PLOT_HEIGHT, List.of());
        List<KeyCount> breakdown = traffic.breakdowns().getOrDefault(tab, List.of());
        return new TrafficView(period, PERIODS, tab, TABS, sort, noticeKey(state, current), age,
                asOf == null ? null : DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(asOf),
                showBody, tiles, chart,
                "posts".equals(tab) ? posts(traffic.posts(), sort) : List.of(),
                "posts".equals(tab) ? List.of() : rows(tab, breakdown, locale), umamiUrl);
    }

    /** The accepted period for a request value, else 30d. */
    static String periodOf(String requested) {
        return requested != null && PERIODS.contains(requested) ? requested : "30d";
    }

    /** The accepted tab for a request value, else posts. */
    static String tabOf(String requested) {
        return requested != null && TABS.contains(requested) ? requested : "posts";
    }

    /** The accepted sort for a request value, else views. */
    static String sortOf(String requested) {
        return requested != null && SORTS.contains(requested) ? requested : "views";
    }

    /** Where "Open in Umami" goes, or null when it should not be offered. */
    static String umamiUrl(String dashboardUrl, String websiteId) {
        if (dashboardUrl == null || websiteId == null || websiteId.isBlank()) {
            return null;
        }
        String base = dashboardUrl.strip();
        String lower = base.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/websites/" + URLEncoder.encode(websiteId, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String noticeKey(TrafficState state, Summary current) {
        return switch (state) {
            case NOT_CONFIGURED -> "traffic.state.notConfigured";
            case NO_DATA -> "traffic.state.noData";
            case STALE -> "traffic.state.stale";
            case OK -> current != null && current.visitors() == 0 && current.visits() == 0 && current.views() == 0
                    ? "traffic.state.zero" : "";
        };
    }

    // ------------------------------------------------------------- headlines

    private static List<Tile> tiles(Summary now, Summary before, Locale locale) {
        boolean hasBefore = before != null;
        List<Tile> tiles = new ArrayList<>();
        tiles.add(countTile("traffic.tile.visitors", now.visitors(), hasBefore ? before.visitors() : 0, hasBefore, locale));
        tiles.add(countTile("traffic.tile.visits", now.visits(), hasBefore ? before.visits() : 0, hasBefore, locale));
        tiles.add(countTile("traffic.tile.views", now.views(), hasBefore ? before.views() : 0, hasBefore, locale));

        boolean bounceKnown = now.visits() > 0;
        boolean bounceChange = bounceKnown && hasBefore && before.visits() > 0;
        double bounce = bounceKnown ? 100.0 * now.bounces() / now.visits() : 0;
        long bounceDelta = bounceChange ? Math.round(bounce - 100.0 * before.bounces() / before.visits()) : 0;
        tiles.add(new Tile("traffic.tile.bounce", bounceKnown ? Math.round(bounce) + "%" : DASH,
                bounceChange ? signed(bounceDelta) + " pts" : DASH,
                bounceChange ? trend(-bounceDelta) : ""));

        boolean timeKnown = now.visits() > 0;
        boolean timeChange = timeKnown && hasBefore && before.visits() > 0;
        double seconds = timeKnown ? (double) now.totalTimeSeconds() / now.visits() : 0;
        long timeDelta = timeChange ? Math.round(seconds - (double) before.totalTimeSeconds() / before.visits()) : 0;
        tiles.add(new Tile("traffic.tile.time", timeKnown ? minutes(Math.round(seconds)) : DASH,
                timeChange ? signed(timeDelta) + "s" : DASH, timeChange ? trend(timeDelta) : ""));
        return tiles;
    }

    private static Tile countTile(String key, long now, long before, boolean hasBefore, Locale locale) {
        String value = number(now, locale);
        if (!hasBefore || before <= 0) {
            return new Tile(key, value, DASH, "");
        }
        long pct = Math.round(100.0 * (now - before) / before);
        return new Tile(key, value, signed(pct) + "%", trend(pct));
    }

    private static String signed(long delta) {
        return delta > 0 ? "+" + delta : Long.toString(delta);
    }

    private static String trend(long delta) {
        if (delta == 0) {
            return "";
        }
        return delta > 0 ? "up" : "dn";
    }

    private static String minutes(long seconds) {
        return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
    }

    private static String number(long value, Locale locale) {
        return java.text.NumberFormat.getIntegerInstance(locale).format(value);
    }

    // ----------------------------------------------------------------- chart

    private static Chart chart(List<SeriesPoint> series, Locale locale) {
        int n = series.size();
        if (n == 0) {
            return new Chart(CHART_WIDTH, CHART_HEIGHT, PLOT_HEIGHT, List.of());
        }
        long max = series.stream().mapToLong(SeriesPoint::views).max().orElse(0);
        int slot = Math.max(1, CHART_WIDTH / n);
        int width = Math.max(1, slot * 8 / 10);
        int step = (n + MAX_LABELS - 1) / MAX_LABELS;
        DateTimeFormatter dayFormat = DateTimeFormatter.ofPattern("MMM d", locale);
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            SeriesPoint point = series.get(i);
            boolean hourly = point.hour() != SeriesPoint.NO_HOUR;
            String title;
            String label;
            if (hourly) {
                label = String.format(Locale.ROOT, "%02d", point.hour());
                title = label + ":00: " + point.views();
            } else {
                label = dayFormat.format(point.day());
                title = label + ": " + point.views();
            }
            int height = max <= 0 ? 0 : (int) Math.round((double) PLOT_HEIGHT * point.views() / max);
            bars.add(new Bar(i * slot + (slot - width) / 2, PLOT_HEIGHT - height, width, height, title,
                    i % step == 0 ? label : null));
        }
        return new Chart(CHART_WIDTH, CHART_HEIGHT, PLOT_HEIGHT, bars);
    }

    // ----------------------------------------------------------------- posts

    /** A path's numbers, with the raw trend kept for sorting. */
    private record Merged(PostRow best, long views, long visitors) {
    }

    private static List<PostLine> posts(List<PostRow> rows, String sort) {
        List<Merged> merged = new ArrayList<>();
        Map<String, Integer> byEntry = new LinkedHashMap<>();
        for (PostRow row : rows) {
            Integer at = row.entryId() == null ? null : byEntry.get(row.entryId());
            if (at == null) {
                if (row.entryId() != null) {
                    byEntry.put(row.entryId(), merged.size());
                }
                merged.add(new Merged(row, row.views(), row.visitors()));
            } else {
                // The same entry under two spellings of its path: views add up,
                // visitors may overlap so only the larger is a safe figure, and
                // the better-viewed spelling stands for the row (path and trend).
                Merged old = merged.get(at);
                PostRow best = row.views() > old.best().views() ? row : old.best();
                merged.set(at, new Merged(best, old.views() + row.views(), Math.max(old.visitors(), row.visitors())));
            }
        }
        Comparator<Merged> byViews = Comparator.comparingLong(Merged::views).reversed();
        Comparator<Merged> order = switch (sort) {
            case "visitors" -> Comparator.comparingLong(Merged::visitors).reversed().thenComparing(byViews);
            case "trend" -> Comparator.comparingDouble((Merged m) -> trendKey(m.best().trend())).reversed()
                    .thenComparing(byViews);
            default -> byViews;
        };
        merged.sort(order.thenComparing(m -> m.best().path()));
        return merged.stream().map(TrafficView::line).toList();
    }

    private static double trendKey(double trend) {
        return Double.isNaN(trend) ? Double.NEGATIVE_INFINITY : trend;
    }

    private static PostLine line(Merged m) {
        PostRow best = m.best();
        double trend = best.trend();
        boolean known = !Double.isNaN(trend) && !Double.isInfinite(trend);
        long pct = known ? Math.round(trend * 100) : 0;
        return new PostLine(best.entryId(), best.title(), best.path(), m.views(), m.visitors(),
                known ? signed(pct) + "%" : DASH, known ? trend(pct) : "");
    }

    // ------------------------------------------------------------ breakdowns

    private static List<Row> rows(String tab, List<KeyCount> breakdown, Locale locale) {
        Set<String> groups = GROUPS.getOrDefault(tab, Set.of());
        List<Row> rows = new ArrayList<>();
        for (KeyCount kc : breakdown) {
            String key = kc.key();
            String group = "";
            String label = key;
            int colon = key.indexOf(':');
            if (colon > 0 && groups.contains(key.substring(0, colon))) {
                group = key.substring(0, colon);
                label = key.substring(colon + 1);
            }
            rows.add(new Row(group, label, "speed".equals(tab) ? speed(key, kc.value()) : number(kc.value(), locale)));
        }
        if ("devices".equals(tab)) {
            rows.sort(Comparator.comparingInt(r -> DEVICE_ORDER.indexOf(r.group())));
        }
        return rows;
    }

    private static String speed(String metric, long value) {
        if ("cls".equals(metric)) {
            return String.format(Locale.ROOT, "%.2f", value / (double) UmamiAnalyticsService.CLS_SCALE);
        }
        if (MILLISECOND_METRICS.contains(metric) && value >= 1000) {
            return String.format(Locale.ROOT, "%.1f s", value / 1000.0);
        }
        return value + " ms";
    }
}
