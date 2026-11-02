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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.wikantik.pressroom.business.analytics.AnalyticsReport.KeyCount;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.PathCount;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.SeriesPoint;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.Summary;
import com.wikantik.pressroom.config.WebloggerConfig;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import tools.jackson.databind.JsonNode;

/**
 * {@link AnalyticsService} over Umami 3.3.0's HTTP API, as recorded in
 * {@code docs/dev/umami-api.md}. Background threads only.
 *
 * <p>Breakdown mapping (keys exactly {@code sources, places, devices, events,
 * campaigns, speed}):
 * <ul>
 * <li>sources: the {@code referrer} metric, then the {@code channel} metric
 *     with keys prefixed {@code channel:} (Umami's own channel class, e.g.
 *     {@code channel:organicSearch}); blank referrers (direct) are dropped;</li>
 * <li>places: {@code country};</li>
 * <li>devices: {@code device}, {@code browser}, {@code os}, keys prefixed
 *     {@code device:}, {@code browser:}, {@code os:};</li>
 * <li>events: {@code event};</li>
 * <li>campaigns: the {@code reports/utm} report, keys {@code utm_source:x},
 *     {@code utm_medium:x}, and so on, valued by views (the report has no
 *     visitor counts);</li>
 * <li>speed: the {@code reports/performance} p75 per metric, keys {@code lcp,
 *     inp, fcp, ttfb} in milliseconds and {@code cls} multiplied by
 *     {@value #CLS_SCALE} so it fits a whole number. Empty when the report's
 *     sample count is zero.</li>
 * </ul>
 */
public final class UmamiAnalyticsService implements AnalyticsService {

    /** {@code cls} is a small fraction (0.1); it is reported times this so a long can hold it. */
    public static final int CLS_SCALE = 1000;

    private static final int NAME_MAX = 100;
    private static final int PATH_LIMIT = 100;
    private static final int METRIC_LIMIT = 50;
    private static final String[] SPEED_KEYS = {"lcp", "inp", "fcp", "ttfb", "cls"};
    private static final String[] UTM_KEYS = {"utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content"};
    private static final String NOT_CONFIGURED = "analytics not configured";

    private final UmamiClient client;
    private final Clock clock;

    private UmamiAnalyticsService(UmamiClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    /** Reads {@code analytics.umami.apiUrl}, {@code .username} and {@code .password}; a blank URL is an inert service. */
    public static AnalyticsService fromConfig() {
        String url = StringUtils.trimToNull(WebloggerConfig.getProperty("analytics.umami.apiUrl"));
        if (url == null) {
            return new UmamiAnalyticsService(null, Clock.systemUTC());
        }
        return new UmamiAnalyticsService(new UmamiClient(url,
                WebloggerConfig.getProperty("analytics.umami.username", "pressroom"),
                WebloggerConfig.getProperty("analytics.umami.password", "")), Clock.systemUTC());
    }

    static UmamiAnalyticsService of(UmamiClient client, Clock clock) {
        return new UmamiAnalyticsService(client, clock);
    }

    @Override
    public boolean isConfigured() {
        return client != null;
    }

    @Override
    public boolean ping() {
        if (client == null) {
            return false;
        }
        try {
            return client.verify();
        } catch (AnalyticsException e) {
            return false;
        }
    }

    // ------------------------------------------------------------ registering

    @Override
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Lower-cases a blog's own host name for Umami's lower-case-only domain pattern; "
                    + "it is sent to Umami, never used to decide access, and Umami validates it again.")
    public String ensureSite(SiteSpec spec) throws AnalyticsException {
        UmamiClient c = configured();
        String name = StringUtils.left(spec.name(), NAME_MAX);
        String domain = spec.domain() == null ? null : spec.domain().toLowerCase(Locale.ROOT);
        String id = spec.websiteId();
        if (id == null) {
            return create(c, null, name, domain, null);
        }
        String path = "/api/websites/" + encode(id);
        UmamiClient.Reply found = c.get(path);
        if (found.ok()) {
            JsonNode site = found.json();
            if (!name.equals(site.path("name").asString()) || !domain.equals(site.path("domain").asString())) {
                requireOk(c.post(path, Map.of("name", name, "domain", domain)), "updating the website");
            }
            return id;
        }
        if (found.status() == 401 || found.status() == 404) {
            // Umami answers 401 for a website that does not exist, and the client
            // has already logged in afresh if the token was stale: so this is "missing".
            return create(c, id, name, domain, path);
        }
        throw new AnalyticsException("Umami answered HTTP " + found.status() + " looking up the website");
    }

    private String create(UmamiClient c, String id, String name, String domain, String lookupPath)
            throws AnalyticsException {
        Map<String, Object> body = new LinkedHashMap<>();
        if (id != null) {
            body.put("id", id);
        }
        body.put("name", name);
        body.put("domain", domain);
        UmamiClient.Reply created = c.post("/api/websites", body);
        if (created.ok()) {
            JsonNode returned = created.json().get("id");
            return returned == null ? id : returned.asString();
        }
        if (created.status() == 500 && lookupPath != null && c.get(lookupPath).ok()) {
            // Registering an ID that exists is an unhandled key violation (500, no body).
            return id;
        }
        throw new AnalyticsException("Umami answered HTTP " + created.status() + " creating the website");
    }

    // ------------------------------------------------------------ reading

    @Override
    public AnalyticsReport read(String websiteId, Period period, ZoneId zone) throws AnalyticsException {
        UmamiClient c = configured();
        Instant end = clock.instant();
        Instant start = period.startBefore(end);
        boolean hourly = "24h".equals(period.key());
        String base = "/api/websites/" + encode(websiteId);
        String window = "startAt=" + start.toEpochMilli() + "&endAt=" + end.toEpochMilli();
        String tz = "&timezone=" + encode(zone.getId());

        JsonNode stats = getJson(c, base + "/stats?" + window, "reading stats");
        Summary current = summary(stats);
        Summary previous = summary(stats.path("comparison"));

        JsonNode pageviews = getJson(c, base + "/pageviews?" + window + "&unit=" + (hourly ? "hour" : "day") + tz,
                "reading the series");
        List<SeriesPoint> series = series(pageviews, start, end, zone, hourly);

        JsonNode expanded = getJson(c, base + "/metrics/expanded?" + window + "&type=path&limit=" + PATH_LIMIT,
                "reading pages");
        List<PathCount> paths = new ArrayList<>();
        for (JsonNode row : expanded) {
            paths.add(new PathCount(row.path("name").asString(), number(row.path("pageviews")),
                    number(row.path("visitors"))));
        }

        Map<String, List<KeyCount>> breakdowns = new LinkedHashMap<>();
        List<KeyCount> sources = metric(c, base, window, "referrer", "");
        sources.addAll(metric(c, base, window, "channel", "channel:"));
        breakdowns.put("sources", sources);
        breakdowns.put("places", metric(c, base, window, "country", ""));
        List<KeyCount> devices = metric(c, base, window, "device", "device:");
        devices.addAll(metric(c, base, window, "browser", "browser:"));
        devices.addAll(metric(c, base, window, "os", "os:"));
        breakdowns.put("devices", devices);
        breakdowns.put("events", metric(c, base, window, "event", ""));
        breakdowns.put("campaigns", campaigns(report(c, websiteId, "utm", start, end, zone)));
        breakdowns.put("speed", speed(report(c, websiteId, "performance", start, end, zone)));
        return new AnalyticsReport(end, current, previous, series, paths, breakdowns);
    }

    private static Summary summary(JsonNode node) {
        return new Summary(number(node.path("visitors")), number(node.path("visits")),
                number(node.path("pageviews")), number(node.path("bounces")), number(node.path("totaltime")));
    }

    /** Umami omits empty buckets; this lays every day (or hour) of the window down, in the site zone. */
    private static List<SeriesPoint> series(JsonNode pageviews, Instant start, Instant end, ZoneId zone,
                                            boolean hourly) {
        Map<LocalDateTime, long[]> seen = new HashMap<>();
        for (JsonNode p : pageviews.path("pageviews")) {
            seen.computeIfAbsent(bucket(p.path("x").asString()), k -> new long[2])[1] = number(p.path("y"));
        }
        for (JsonNode p : pageviews.path("sessions")) {
            seen.computeIfAbsent(bucket(p.path("x").asString()), k -> new long[2])[0] = number(p.path("y"));
        }
        List<SeriesPoint> points = new ArrayList<>();
        if (hourly) {
            // Wall-clock hours in the site zone: the repeated fall-back hour is one bucket, the
            // skipped spring-forward hour has none, and an end exactly on the hour adds no empty bucket.
            LocalDateTime t = start.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.HOURS);
            LocalDateTime endLocal = end.atZone(zone).toLocalDateTime();
            LocalDateTime last = endLocal.truncatedTo(ChronoUnit.HOURS);
            if (last.equals(endLocal)) {
                last = last.minusHours(1);
            }
            for (; !t.isAfter(last); t = t.plusHours(1)) {
                if (!ZonedDateTime.ofLocal(t, zone, null).toLocalDateTime().equals(t)) {
                    continue;
                }
                long[] v = seen.getOrDefault(t, new long[2]);
                points.add(new SeriesPoint(t.toLocalDate(), t.getHour(), v[0], v[1]));
            }
        } else {
            LocalDate last = end.atZone(zone).toLocalDate();
            for (LocalDate d = start.atZone(zone).toLocalDate(); !d.isAfter(last); d = d.plusDays(1)) {
                long[] v = seen.getOrDefault(d.atStartOfDay(), new long[2]);
                points.add(new SeriesPoint(d, v[0], v[1]));
            }
        }
        return points;
    }

    /** {@code "2026-11-02 00:00:00"} to a local date-time (no offset). */
    private static LocalDateTime bucket(String x) {
        return LocalDateTime.parse(x.replace(' ', 'T'));
    }

    private List<KeyCount> metric(UmamiClient c, String base, String window, String type, String prefix)
            throws AnalyticsException {
        JsonNode rows = getJson(c, base + "/metrics?" + window + "&type=" + type + "&limit=" + METRIC_LIMIT,
                "reading " + type);
        List<KeyCount> out = new ArrayList<>();
        for (JsonNode row : rows) {
            String key = row.path("x").asString();
            if (!key.isBlank()) {
                out.add(new KeyCount(prefix + key, number(row.path("y"))));
            }
        }
        return out;
    }

    private JsonNode report(UmamiClient c, String websiteId, String type, Instant start, Instant end, ZoneId zone)
            throws AnalyticsException {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("startDate", start.toString());
        parameters.put("endDate", end.toString());
        parameters.put("timezone", zone.getId());
        parameters.put("unit", "day");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("websiteId", websiteId);
        body.put("type", type);
        body.put("filters", Map.of());
        body.put("parameters", parameters);
        UmamiClient.Reply reply = c.post("/api/reports/" + type, body);
        requireOk(reply, "reading the " + type + " report");
        return reply.json();
    }

    private static List<KeyCount> campaigns(JsonNode utm) {
        List<KeyCount> out = new ArrayList<>();
        for (String key : UTM_KEYS) {
            for (JsonNode row : utm.path(key)) {
                out.add(new KeyCount(key + ":" + row.path("utm").asString(), number(row.path("views"))));
            }
        }
        return out;
    }

    private static List<KeyCount> speed(JsonNode performance) {
        JsonNode summary = performance.path("summary");
        List<KeyCount> out = new ArrayList<>();
        if (number(summary.path("count")) == 0) {
            return out;
        }
        for (String key : SPEED_KEYS) {
            double p75 = summary.path(key).path("p75").asDouble();
            out.add(new KeyCount(key, Math.round("cls".equals(key) ? p75 * CLS_SCALE : p75)));
        }
        return out;
    }

    // ------------------------------------------------------------ plumbing

    private UmamiClient configured() throws AnalyticsException {
        if (client == null) {
            throw new AnalyticsException(NOT_CONFIGURED);
        }
        return client;
    }

    private static JsonNode getJson(UmamiClient c, String path, String step) throws AnalyticsException {
        UmamiClient.Reply reply = c.get(path);
        requireOk(reply, step);
        return reply.json();
    }

    private static void requireOk(UmamiClient.Reply reply, String step) throws AnalyticsException {
        if (!reply.ok()) {
            throw new AnalyticsException("Umami answered HTTP " + reply.status() + " while " + step);
        }
    }

    /** Umami sends bigint columns as JSON strings and the rest as numbers. */
    private static long number(JsonNode node) {
        if (node.isNumber()) {
            return node.asLong();
        }
        String text = node.asString("");
        try {
            return text.isEmpty() ? 0 : Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
