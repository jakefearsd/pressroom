package com.wikantik.pressroom.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.business.analytics.AnalyticsReport;
import com.wikantik.pressroom.business.analytics.BlogTraffic;

/**
 * A blog's traffic for one period, as {@code GET /v1/weblogs/{handle}/traffic} writes it: the Traffic
 * page's snapshot, read from the local tables only.
 *
 * <p>Three things differ from {@link BlogTraffic} on purpose. {@code lastError} is left out: it is
 * internal error text, and {@code STALE} says enough. A trend of {@link Double#NaN} (no longer period to
 * compare with) is null, since JSON has no NaN. And the model's -1 sentinels (visitors the service
 * cannot count, the hour of a day bucket) are null rather than a number a client could add up.
 */
public final class TrafficDtos {

    private TrafficDtos() {
    }

    /**
     * {@code asOf}, {@code current} and {@code previous} are absent when there is no snapshot
     * ({@code NOT_CONFIGURED}, or {@code NO_DATA} before the first read); the lists and map are then
     * empty.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TrafficView(
            @Schema(description = "NOT_CONFIGURED, NO_DATA, STALE (the last read failed: shown, not hidden) or OK.")
            String state,
            @Schema(description = "When the snapshot was read; the numbers are as fresh as this.")
            Instant asOf,
            String period,
            Summary current,
            @Schema(description = "The immediately preceding period of the same length.")
            Summary previous,
            List<Point> series,
            @Schema(description = "One row per recorded path, most viewed first.")
            List<Post> posts,
            @Schema(description = "Rows by tab: sources, places, devices, events, campaigns, speed.")
            Map<String, List<Row>> breakdowns,
            @Schema(description = "0 when posts holds every recorded path; else the number of top paths it "
                    + "was cut at, and a path outside them is unknown, not zero.")
            int pathsCappedAt) {
    }

    /** The headline numbers; visitors are distinct per period and never summed across periods. */
    public record Summary(long visitors, long visits, long views, long bounces, long totalTimeSeconds) {
    }

    /** One bucket of the series, in the site's time zone; {@code hour} only for a 24h period. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Point(LocalDate day, Integer hour, long visitors, long views) {
    }

    /**
     * One recorded path. {@code entryId} and {@code title} are null for a path that is not an entry;
     * {@code title} is the entry's title as stored (escaped). Every component is written, nulls included.
     */
    public record Post(
            String entryId,
            String title,
            String path,
            long views,
            @Schema(description = "Distinct visitors to this path; null when the service cannot say.")
            Long visitors,
            @Schema(description = "This period's daily view rate against the next longer period's, minus one "
                    + "(0 is steady, 1 is twice the usual rate); null when no longer period has the path.")
            Double trend) {
    }

    /** One row of a breakdown. */
    public record Row(String key, long value) {
    }

    public static TrafficView toView(BlogTraffic traffic, String period) {
        Map<String, List<Row>> breakdowns = new LinkedHashMap<>();
        traffic.breakdowns().forEach((tab, rows) ->
                breakdowns.put(tab, rows.stream().map(r -> new Row(r.key(), r.value())).toList()));
        return new TrafficView(traffic.state().name(), traffic.asOf(), period,
                summary(traffic.current()), summary(traffic.previous()),
                traffic.series().stream().map(TrafficDtos::point).toList(),
                traffic.posts().stream().map(TrafficDtos::post).toList(),
                breakdowns, traffic.pathsCappedAt());
    }

    private static Summary summary(AnalyticsReport.Summary s) {
        return s == null ? null : new Summary(s.visitors(), s.visits(), s.views(), s.bounces(), s.totalTimeSeconds());
    }

    private static Point point(AnalyticsReport.SeriesPoint p) {
        return new Point(p.day(), p.hour() == AnalyticsReport.SeriesPoint.NO_HOUR ? null : p.hour(),
                p.visitors(), p.views());
    }

    private static Post post(BlogTraffic.PostRow row) {
        return new Post(row.entryId(), row.title(), row.path(), row.views(),
                row.visitors() < 0 ? null : row.visitors(),
                Double.isFinite(row.trend()) ? row.trend() : null);
    }
}
