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

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.KeyCount;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.PathCount;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.SeriesPoint;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.Summary;
import com.wikantik.pressroom.business.analytics.BlogTraffic.PostRow;
import com.wikantik.pressroom.business.analytics.SiteTraffic.BlogRow;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The analytics manager's local snapshot tables: writes one (blog, period)
 * snapshot whole, and answers the traffic readers from them. Owned by
 * {@link JPAAnalyticsManagerImpl}; the JSON a breakdown or series is kept as
 * is private to this class, and callers see only the records.
 *
 * <p>Writing runs on the sync's thread, reading on any thread; neither ever
 * calls the analytics service.
 */
final class TrafficCache {

    private static final Logger log = LoggerFactory.getLogger(TrafficCache.class);

    /** The tabs a snapshot keeps, in the order a page shows them. */
    static final List<String> TABS = List.of("sources", "places", "devices", "events", "campaigns", "speed");

    /** The breakdown kind the series is kept under. */
    static final String SERIES = "series";

    /** {@code analytics_path_views.path}'s width; a longer path is not kept (the totals still count it). */
    private static final int MAX_PATH = 1024;

    /** How many top posts and top sources the site page gets. */
    private static final int TOP = 10;

    /** How long after registering a zero-view snapshot still reads as no data yet. */
    static final Duration NO_DATA_GRACE = Duration.ofHours(24);

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** One breakdown row as stored. */
    record Row(String k, long n) {
    }

    /** One series bucket as stored: day, hour (-1 for a day bucket), visitors, views. */
    record Point(String d, int h, long u, long v) {
    }

    private final JPAPersistenceStrategy strategy;

    TrafficCache(JPAPersistenceStrategy strategy) {
        this.strategy = strategy;
    }

    // ------------------------------------------------------------ writing

    /**
     * Replaces one blog's snapshot for one period with {@code report}: the
     * summary, every path and every breakdown, in one transaction. Rows the
     * report no longer has are removed. Nothing is written if the commit
     * fails; the caller rolls back and stops.
     *
     * @param entryIds recorded path to entry ID, for the paths that name an entry
     */
    // The EntityManager is the thread's own, which JPAPersistenceStrategy.release()
    // closes at the end of the unit of work; closing it here would end the sync's.
    @SuppressWarnings("PMD.CloseResource")
    void replace(String weblogId, String period, AnalyticsReport report, Instant asOf, Map<String, String> entryIds)
            throws WebloggerException {
        EntityManager em = strategy.getEntityManager(true);

        AnalyticsSummary summary = em.find(AnalyticsSummary.class, new AnalyticsSummary.Key(weblogId, period));
        if (summary == null) {
            summary = new AnalyticsSummary();
            summary.setWeblogId(weblogId);
            summary.setPeriod(period);
            em.persist(summary);
        }
        summary.setAsOf(asOf);
        Summary current = orZero(report.current());
        Summary previous = orZero(report.previous());
        summary.setVisitors(current.visitors());
        summary.setVisits(current.visits());
        summary.setViews(current.views());
        summary.setBounces(current.bounces());
        summary.setTotalTime(current.totalTimeSeconds());
        summary.setPrevVisitors(previous.visitors());
        summary.setPrevVisits(previous.visits());
        summary.setPrevViews(previous.views());
        summary.setPrevBounces(previous.bounces());
        summary.setPrevTotalTime(previous.totalTimeSeconds());

        replacePaths(em, weblogId, period, report.paths(), entryIds);

        Map<String, String> payloads = new LinkedHashMap<>();
        Map<String, List<KeyCount>> breakdowns = report.breakdowns() == null ? Map.of() : report.breakdowns();
        for (String tab : TABS) {
            payloads.put(tab, rowsJson(breakdowns.getOrDefault(tab, List.of())));
        }
        payloads.put(SERIES, seriesJson(report.series()));
        replaceBreakdowns(em, weblogId, period, payloads);

        strategy.flush();
    }

    private static void replacePaths(EntityManager em, String weblogId, String period, List<PathCount> paths,
                                     Map<String, String> entryIds) {
        Map<String, AnalyticsPathViews> old = new HashMap<>();
        for (AnalyticsPathViews row : em.createQuery(
                        "SELECT p FROM AnalyticsPathViews p WHERE p.weblogId = :w AND p.period = :p",
                        AnalyticsPathViews.class)
                .setParameter("w", weblogId).setParameter("p", period).getResultList()) {
            old.put(row.getPath(), row);
        }
        Set<String> written = new HashSet<>();
        for (PathCount count : paths == null ? List.<PathCount>of() : paths) {
            String path = count.path();
            if (path == null || path.isEmpty() || path.length() > MAX_PATH || !written.add(path)) {
                continue;
            }
            AnalyticsPathViews row = old.remove(path);
            if (row == null) {
                row = new AnalyticsPathViews();
                row.setWeblogId(weblogId);
                row.setPeriod(period);
                row.setPath(path);
                em.persist(row);
            }
            row.setEntryId(entryIds.get(path));
            row.setViews(count.views());
            row.setVisitors(count.visitors());
        }
        old.values().forEach(em::remove);
    }

    private static void replaceBreakdowns(EntityManager em, String weblogId, String period,
                                          Map<String, String> payloads) {
        Map<String, AnalyticsBreakdown> old = new HashMap<>();
        for (AnalyticsBreakdown row : em.createQuery(
                        "SELECT b FROM AnalyticsBreakdown b WHERE b.weblogId = :w AND b.period = :p",
                        AnalyticsBreakdown.class)
                .setParameter("w", weblogId).setParameter("p", period).getResultList()) {
            old.put(row.getKind(), row);
        }
        payloads.forEach((kind, payload) -> {
            AnalyticsBreakdown row = old.remove(kind);
            if (row == null) {
                row = new AnalyticsBreakdown();
                row.setWeblogId(weblogId);
                row.setPeriod(period);
                row.setKind(kind);
                em.persist(row);
            }
            row.setPayload(payload);
        });
        old.values().forEach(em::remove);
    }

    /** Anchors of one blog to its entries' IDs, in one query. */
    Map<String, String> entryIdsByAnchor(String weblogId, Set<String> anchors) {
        Map<String, String> ids = new HashMap<>();
        for (Object[] row : strategy.getEntityManager(false).createQuery(
                        "SELECT e.anchor, e.id FROM WeblogEntry e WHERE e.website.id = :w AND e.anchor IN :a",
                        Object[].class)
                .setParameter("w", weblogId).setParameter("a", anchors).getResultList()) {
            ids.put((String) row[0], (String) row[1]);
        }
        return ids;
    }

    // ------------------------------------------------------------ reading

    /** The stored summary of one blog and period, or null. */
    AnalyticsSummary summary(String weblogId, String period) {
        return strategy.getEntityManager(false).find(AnalyticsSummary.class,
                new AnalyticsSummary.Key(weblogId, period));
    }

    /** The registration row of one blog, or null. */
    AnalyticsSite site(String weblogId) {
        return strategy.getEntityManager(false).find(AnalyticsSite.class, weblogId);
    }

    static Summary current(AnalyticsSummary s) {
        return new Summary(s.getVisitors(), s.getVisits(), s.getViews(), s.getBounces(), s.getTotalTime());
    }

    static Summary previous(AnalyticsSummary s) {
        return new Summary(s.getPrevVisitors(), s.getPrevVisits(), s.getPrevViews(), s.getPrevBounces(),
                s.getPrevTotalTime());
    }

    /** The tabs of one snapshot (every tab present, in order) and its series. */
    Map<String, List<KeyCount>> breakdowns(String weblogId, String period) {
        Map<String, List<KeyCount>> tabs = new LinkedHashMap<>();
        for (String tab : TABS) {
            tabs.put(tab, List.of());
        }
        for (AnalyticsBreakdown row : breakdownRows(List.of(weblogId), period)) {
            if (tabs.containsKey(row.getKind())) {
                tabs.put(row.getKind(), rows(row.getPayload()));
            }
        }
        return tabs;
    }

    List<SeriesPoint> series(String weblogId, String period) {
        AnalyticsBreakdown row = strategy.getEntityManager(false).find(AnalyticsBreakdown.class,
                new AnalyticsBreakdown.Key(weblogId, period, SERIES));
        return row == null ? List.of() : points(row.getPayload());
    }

    /**
     * Every recorded path of one blog and period, most viewed first.
     *
     * @param longer the next longer period, for the trend, or null
     */
    List<PostRow> posts(String weblogId, Period period, Period longer) {
        List<AnalyticsPathViews> rows = strategy.getEntityManager(false).createQuery(
                        "SELECT p FROM AnalyticsPathViews p WHERE p.weblogId = :w AND p.period = :p "
                                + "ORDER BY p.views DESC, p.path", AnalyticsPathViews.class)
                .setParameter("w", weblogId).setParameter("p", period.key()).getResultList();
        return postRows(rows, period, longer, List.of(weblogId));
    }

    /** One blog's line per summary, the site's top posts and sources, for one period. */
    SiteTraffic siteTraffic(List<AnalyticsSummary> summaries, Period period, Period longer, TrafficStateOf stateOf) {
        List<String> weblogIds = summaries.stream().map(AnalyticsSummary::getWeblogId).toList();
        Map<String, String[]> names = names(weblogIds);
        List<AnalyticsPathViews> entryRows = strategy.getEntityManager(false).createQuery(
                        "SELECT p FROM AnalyticsPathViews p WHERE p.period = :p AND p.entryId IS NOT NULL "
                                + "ORDER BY p.views DESC, p.path", AnalyticsPathViews.class)
                .setParameter("p", period.key()).getResultList();
        List<PostRow> posts = postRows(entryRows, period, longer, weblogIds);
        Map<String, String> topTitle = new HashMap<>();
        List<PostRow> topPosts = new ArrayList<>();
        for (int i = 0; i < entryRows.size(); i++) {
            PostRow post = posts.get(i);
            if (post.entryId() == null) {
                continue;
            }
            topTitle.putIfAbsent(entryRows.get(i).getWeblogId(), post.title());
            if (topPosts.size() < TOP) {
                topPosts.add(post);
            }
        }

        List<BlogRow> blogs = new ArrayList<>();
        long views = 0;
        long visits = 0;
        Instant asOf = null;
        for (AnalyticsSummary s : summaries) {
            String[] name = names.getOrDefault(s.getWeblogId(), new String[2]);
            blogs.add(new BlogRow(s.getWeblogId(), name[0], name[1], s.getVisitors(), s.getViews(), s.getVisits(),
                    change(s.getViews(), s.getPrevViews()), topTitle.get(s.getWeblogId())));
            views += s.getViews();
            visits += s.getVisits();
            asOf = asOf == null || s.getAsOf().isBefore(asOf) ? s.getAsOf() : asOf;
        }
        blogs.sort(Comparator.comparingLong(BlogRow::views).reversed()
                .thenComparing(BlogRow::handle, Comparator.nullsLast(Comparator.naturalOrder())));
        return new SiteTraffic(stateOf.of(weblogIds), asOf, blogs, views, visits, topPosts,
                topSources(weblogIds, period.key()));
    }

    /** What a site page's state is, given the blogs that have a snapshot. */
    @FunctionalInterface
    interface TrafficStateOf {
        TrafficState of(Collection<String> weblogIds);
    }

    List<AnalyticsSummary> summaries(String period) {
        return strategy.getEntityManager(false).createQuery(
                        "SELECT s FROM AnalyticsSummary s WHERE s.period = :p", AnalyticsSummary.class)
                .setParameter("p", period).getResultList();
    }

    /** Views per entry for one period, of one blog or (weblogId null) of every blog. */
    Map<String, Long> entryViews(String weblogId, String period) {
        String query = "SELECT p.entryId, SUM(p.views) FROM AnalyticsPathViews p WHERE p.period = :p "
                + "AND p.entryId IS NOT NULL" + (weblogId == null ? "" : " AND p.weblogId = :w")
                + " GROUP BY p.entryId";
        var q = strategy.getEntityManager(false).createQuery(query, Object[].class).setParameter("p", period);
        if (weblogId != null) {
            q.setParameter("w", weblogId);
        }
        Map<String, Long> views = new HashMap<>();
        for (Object[] row : q.getResultList()) {
            views.put((String) row[0], ((Number) row[1]).longValue());
        }
        return views;
    }

    // ------------------------------------------------------------ helpers

    /**
     * Rows to post rows: titles in one query, and the trend against the
     * longer period's rows of the same blogs.
     */
    private List<PostRow> postRows(List<AnalyticsPathViews> rows, Period period, Period longer,
                                   Collection<String> weblogIds) {
        Set<String> ids = new HashSet<>();
        for (AnalyticsPathViews row : rows) {
            if (row.getEntryId() != null) {
                ids.add(row.getEntryId());
            }
        }
        Map<String, String> titles = titles(ids);
        Map<String, Long> longerViews = longer == null || rows.isEmpty() ? Map.of() : longerViews(weblogIds, longer);
        List<PostRow> posts = new ArrayList<>(rows.size());
        for (AnalyticsPathViews row : rows) {
            // an entry deleted since the read is no entry any more
            String title = row.getEntryId() == null ? null : titles.get(row.getEntryId());
            String entryId = title == null ? null : row.getEntryId();
            Long before = longerViews.get(row.getWeblogId() + '\n' + row.getPath());
            posts.add(new PostRow(entryId, title, row.getPath(), row.getViews(), row.getVisitors(),
                    trend(row.getViews(), period, before, longer)));
        }
        return posts;
    }

    private Map<String, Long> longerViews(Collection<String> weblogIds, Period longer) {
        Map<String, Long> views = new HashMap<>();
        for (AnalyticsPathViews row : strategy.getEntityManager(false).createQuery(
                        "SELECT p FROM AnalyticsPathViews p WHERE p.period = :p AND p.weblogId IN :w",
                        AnalyticsPathViews.class)
                .setParameter("p", longer.key()).setParameter("w", weblogIds).getResultList()) {
            views.put(row.getWeblogId() + '\n' + row.getPath(), row.getViews());
        }
        return views;
    }

    /** This period's daily rate against the longer period's, minus one; NaN with nothing to compare. */
    static double trend(long views, Period period, Long longerViews, Period longer) {
        if (longer == null || longerViews == null || longerViews <= 0) {
            return Double.NaN;
        }
        double rate = views / (double) period.length().toMinutes();
        double usual = longerViews / (double) longer.length().toMinutes();
        return rate / usual - 1;
    }

    /** Views against the previous period as a fraction; NaN when the previous period had none. */
    static double change(long views, long previousViews) {
        return previousViews <= 0 ? Double.NaN : (views - previousViews) / (double) previousViews;
    }

    private Map<String, String> titles(Set<String> entryIds) {
        if (entryIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> titles = new HashMap<>();
        for (Object[] row : strategy.getEntityManager(false).createQuery(
                        "SELECT e.id, e.title FROM WeblogEntry e WHERE e.id IN :ids", Object[].class)
                .setParameter("ids", entryIds).getResultList()) {
            titles.put((String) row[0], (String) row[1]);
        }
        return titles;
    }

    private Map<String, String[]> names(List<String> weblogIds) {
        if (weblogIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String[]> names = new HashMap<>();
        for (Object[] row : strategy.getEntityManager(false).createQuery(
                        "SELECT w.id, w.handle, w.name FROM Weblog w WHERE w.id IN :ids", Object[].class)
                .setParameter("ids", weblogIds).getResultList()) {
            names.put((String) row[0], new String[] {(String) row[1], (String) row[2]});
        }
        return names;
    }

    /**
     * The site's top sources. Source counts are visitors, which do not add
     * across blogs: a source several blogs share shows the largest single
     * blog's count.
     */
    private List<KeyCount> topSources(List<String> weblogIds, String period) {
        Map<String, Long> largest = new HashMap<>();
        for (AnalyticsBreakdown row : breakdownRows(weblogIds, period)) {
            if ("sources".equals(row.getKind())) {
                for (KeyCount count : rows(row.getPayload())) {
                    largest.merge(count.key(), count.value(), Math::max);
                }
            }
        }
        return largest.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP)
                .map(e -> new KeyCount(e.getKey(), e.getValue()))
                .toList();
    }

    private List<AnalyticsBreakdown> breakdownRows(List<String> weblogIds, String period) {
        if (weblogIds.isEmpty()) {
            return List.of();
        }
        return strategy.getEntityManager(false).createQuery(
                        "SELECT b FROM AnalyticsBreakdown b WHERE b.period = :p AND b.weblogId IN :w",
                        AnalyticsBreakdown.class)
                .setParameter("p", period).setParameter("w", weblogIds).getResultList();
    }

    private static Summary orZero(Summary summary) {
        return summary == null ? new Summary(0, 0, 0, 0, 0) : summary;
    }

    // ------------------------------------------------------------ the JSON payload

    private static String rowsJson(List<KeyCount> counts) {
        List<Row> rows = new ArrayList<>(counts.size());
        for (KeyCount count : counts) {
            rows.add(new Row(count.key(), count.value()));
        }
        return JSON.writeValueAsString(rows);
    }

    private static String seriesJson(List<SeriesPoint> series) {
        List<Point> points = new ArrayList<>();
        for (SeriesPoint p : series == null ? List.<SeriesPoint>of() : series) {
            points.add(new Point(p.day().toString(), p.hour(), p.visitors(), p.views()));
        }
        return JSON.writeValueAsString(points);
    }

    /** Display path: a payload that cannot be read shows as an empty tab, and says so in the log. */
    private static List<KeyCount> rows(String payload) {
        try {
            List<KeyCount> counts = new ArrayList<>();
            for (Row row : JSON.readValue(payload, Row[].class)) {
                counts.add(new KeyCount(row.k(), row.n()));
            }
            return counts;
        } catch (JacksonException e) {
            log.warn("An analytics breakdown could not be read; it shows empty", e);
            return List.of();
        }
    }

    private static List<SeriesPoint> points(String payload) {
        try {
            List<SeriesPoint> points = new ArrayList<>();
            for (Point p : JSON.readValue(payload, Point[].class)) {
                points.add(new SeriesPoint(LocalDate.parse(p.d()), p.h(), p.u(), p.v()));
            }
            return points;
        } catch (JacksonException | DateTimeParseException e) {
            log.warn("An analytics series could not be read; it shows empty", e);
            return List.of();
        }
    }
}
