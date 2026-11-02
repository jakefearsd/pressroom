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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wikantik.pressroom.business.analytics.AnalyticsReport.KeyCount;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Umami client and service against a stub HTTP server. Fixture bodies are
 * copied from the real responses recorded in {@code docs/dev/umami-api.md}.
 */
class UmamiClientTest {

    private static final String PASSWORD = "p4ssw0rd-never-logged";
    private static final String SITE = "798f2c87-5e32-4870-803d-db9fc87e4be0";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    /** 2026-11-02 12:00 in Los Angeles (PST). */
    private static final Instant NOW = Instant.parse("2026-11-02T20:00:00Z");

    record Req(String method, String path, String query, String auth, String body) { }

    record Resp(int status, String body) { }

    private HttpServer server;
    private final List<Req> seen = new CopyOnWriteArrayList<>();
    private volatile Function<Req, Resp> route = r -> new Resp(404, "{}");
    private final CountDownLatch release = new CountDownLatch(1);
    private int logins;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Req req = new Req(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body);
            seen.add(req);
            Resp resp;
            if (req.path().equals("/api/auth/login")) {
                synchronized (this) {
                    logins++;
                    resp = new Resp(200, "{\"token\":\"tok" + logins + "\",\"user\":{}}");
                }
            } else {
                resp = route.apply(req);
            }
            byte[] out = resp.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(resp.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private UmamiClient client() {
        return new UmamiClient(url(), "pressroom", PASSWORD);
    }

    private UmamiAnalyticsService service() {
        return UmamiAnalyticsService.of(client(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private long count(String method, String path) {
        return seen.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).count();
    }

    // ------------------------------------------------------------ auth

    @Test
    void logsInOnceAndReusesToken() throws Exception {
        route = r -> new Resp(200, "{}");
        UmamiClient c = client();
        assertTrue(c.get("/api/websites/x").ok());
        assertTrue(c.get("/api/websites/y").ok());

        assertEquals(1, count("POST", "/api/auth/login"));
        assertEquals("Bearer tok1", seen.get(1).auth());
        assertEquals("Bearer tok1", seen.get(2).auth());
        String login = seen.get(0).body();
        assertEquals("pressroom", JSON.readTree(login).get("username").asString());
    }

    @Test
    void reloginsOnceOn401() throws Exception {
        UmamiClient c = client();
        // A token just fetched for a call is not retried on 401; a held one is.
        route = r -> new Resp(200, "{}");
        assertTrue(c.get("/api/websites/x").ok());
        route = r -> "Bearer tok1".equals(r.auth()) ? new Resp(401, "{}") : new Resp(200, "{}");

        assertTrue(c.get("/api/websites/x").ok());

        assertEquals(2, count("POST", "/api/auth/login"));
        assertEquals("Bearer tok2", seen.get(seen.size() - 1).auth());
    }

    @Test
    void aSecond401ThrowsRatherThanLooping() throws Exception {
        UmamiAnalyticsService svc = service();
        route = r -> new Resp(200, statsBody());
        svc.ping();
        route = r -> new Resp(401, "{\"error\":{}}");

        AnalyticsException e = assertThrows(AnalyticsException.class,
                () -> svc.read(SITE, Period.of("7d"), LA));

        assertTrue(e.getMessage().contains("401"), e.getMessage());
        assertEquals(2, count("POST", "/api/auth/login"), "one re-login, no more");
    }

    @Test
    void aFailedLoginThrowsWithoutThePassword() {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        AnalyticsException e = assertThrows(AnalyticsException.class, () -> client().get("/api/me"));
        assertTrue(e.getMessage().contains("login failed"), e.getMessage());
        assertFalse(e.getMessage().contains(PASSWORD));
    }

    @Test
    void pingReflectsTheVerifyEndpoint() {
        route = r -> new Resp(200, "{}");
        assertTrue(service().ping());
        route = r -> new Resp(401, "{}");
        assertFalse(service().ping());
        server.stop(0);
        assertFalse(service().ping(), "an unreachable server is false, not an exception");
    }

    // ------------------------------------------------------------ ensureSite

    private static final String SITE_JSON = "{\"id\":\"" + SITE + "\",\"name\":\"Blog\",\"domain\":\"blog.example.com\","
            + "\"resetAt\":null,\"userId\":\"u\",\"teamId\":null}";

    @Test
    void ensureSiteCreatesThenUpdates() throws Exception {
        // Unknown website: Umami answers 401 (even for a fresh token), which means "missing".
        route = r -> {
            if (r.method().equals("GET")) {
                return new Resp(401, "{\"error\":{\"code\":\"unauthorized\"}}");
            }
            return new Resp(200, SITE_JSON);
        };
        assertEquals(SITE, service().ensureSite(new SiteSpec(SITE, "Blog", "Blog.Example.COM")));
        Req create = seen.stream().filter(r -> r.method().equals("POST") && r.path().equals("/api/websites"))
                .findFirst().orElseThrow();
        JsonNode body = JSON.readTree(create.body());
        assertEquals(SITE, body.get("id").asString());
        assertEquals("Blog", body.get("name").asString());
        assertEquals("blog.example.com", body.get("domain").asString(), "domain is lower-cased");

        // A 404 is missing too.
        seen.clear();
        route = r -> r.method().equals("GET") ? new Resp(404, "{}") : new Resp(200, SITE_JSON);
        assertEquals(SITE, service().ensureSite(new SiteSpec(SITE, "Blog", "blog.example.com")));
        assertEquals(1, count("POST", "/api/websites"));

        // Existing, same name and domain: nothing written.
        seen.clear();
        route = r -> new Resp(200, SITE_JSON);
        assertEquals(SITE, service().ensureSite(new SiteSpec(SITE, "Blog", "blog.example.com")));
        assertTrue(seen.stream().noneMatch(r -> r.method().equals("POST") && r.path().startsWith("/api/websites")));

        // Existing with a different name: updated in place.
        seen.clear();
        assertEquals(SITE, service().ensureSite(new SiteSpec(SITE, "Renamed", "blog.example.com")));
        Req update = seen.stream().filter(r -> r.method().equals("POST") && r.path().equals("/api/websites/" + SITE))
                .findFirst().orElseThrow();
        assertEquals("Renamed", JSON.readTree(update.body()).get("name").asString());
        assertEquals(0, count("POST", "/api/websites"));
    }

    @Test
    void ensureSiteWithoutAnIdReturnsTheOneUmamiChose() throws Exception {
        route = r -> new Resp(200, SITE_JSON);
        assertEquals(SITE, service().ensureSite(new SiteSpec(null, "Blog", "blog.example.com")));
        assertFalse(JSON.readTree(seen.get(1).body()).has("id"));
    }

    @Test
    void ensureSiteTreatsAlreadyExistsAsSuccess() throws Exception {
        // GET: 401 first (looks missing), create: 500 (duplicate key); the follow-up GET finds it.
        int[] gets = {0};
        route = r -> {
            if (r.method().equals("GET")) {
                return ++gets[0] <= 1 ? new Resp(401, "{}") : new Resp(200, SITE_JSON);
            }
            return new Resp(500, "");
        };
        assertEquals(SITE, service().ensureSite(new SiteSpec(SITE, "Blog", "blog.example.com")));

        // A 500 with the website still not there is a real failure.
        route = r -> r.method().equals("GET") ? new Resp(401, "{}") : new Resp(500, "");
        AnalyticsException e = assertThrows(AnalyticsException.class,
                () -> service().ensureSite(new SiteSpec(SITE, "Blog", "blog.example.com")));
        assertTrue(e.getMessage().contains("500"), e.getMessage());
    }

    @Test
    void ensureSiteFailsOnAServerError() {
        route = r -> new Resp(503, "");
        assertThrows(AnalyticsException.class,
                () -> service().ensureSite(new SiteSpec(SITE, "Blog", "blog.example.com")));
    }

    // ------------------------------------------------------------ timeouts and secrets

    @Test
    void timeoutsAreSet() {
        UmamiClient c = client();
        assertEquals(Duration.ofSeconds(5), c.connectTimeout());
        assertEquals(Duration.ofSeconds(20), c.requestTimeout());
    }

    @Test
    void hangingServerTimesOut() {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        UmamiClient c = new UmamiClient(url(), "pressroom", PASSWORD, Duration.ofMillis(300), Duration.ofMillis(300));
        long began = System.nanoTime();
        AnalyticsException e = assertThrows(AnalyticsException.class, () -> c.get("/api/me"));
        assertTrue(Duration.ofNanos(System.nanoTime() - began).toSeconds() < 5);
        assertFalse(e.getMessage().contains(PASSWORD));
    }

    @Test
    void passwordNeverInExceptionOrLog() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        AbstractAppender appender = new AbstractAppender("umami-capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                lines.add(event.getMessage().getFormattedMessage()
                        + (event.getThrown() == null ? "" : " " + event.getThrown()));
            }
        };
        appender.start();
        String name = UmamiClient.class.getName();
        org.apache.logging.log4j.core.config.LoggerConfig capture =
                new org.apache.logging.log4j.core.config.LoggerConfig(name, org.apache.logging.log4j.Level.DEBUG, false);
        capture.addAppender(appender, org.apache.logging.log4j.Level.DEBUG, null);
        configuration.addLogger(name, capture);
        context.updateLoggers();
        try {
            // A server that echoes the request back in a 500, login body included.
            route = r -> new Resp(500, "echo " + r.body() + " " + PASSWORD);
            UmamiClient c = client();
            AnalyticsException e = assertThrows(AnalyticsException.class, () ->
                    UmamiAnalyticsService.of(c, Clock.fixed(NOW, ZoneOffset.UTC)).read(SITE, Period.of("7d"), LA));
            assertFalse(e.getMessage().contains(PASSWORD), e.getMessage());
            assertFalse(c.toString().contains(PASSWORD));
            assertFalse(lines.isEmpty(), "the 500 was logged");
            assertTrue(lines.stream().noneMatch(l -> l.contains(PASSWORD) || l.contains("tok1")), lines.toString());
        } finally {
            configuration.removeLogger(name);
            appender.stop();
            context.updateLoggers();
        }
    }

    @Test
    void unconfiguredServiceIsInert() throws Exception {
        AnalyticsService svc = UmamiAnalyticsService.fromConfig();
        assertFalse(svc.isConfigured());
        assertFalse(svc.ping());
        AnalyticsException e1 = assertThrows(AnalyticsException.class,
                () -> svc.ensureSite(new SiteSpec(SITE, "n", "example.com")));
        assertEquals("analytics not configured", e1.getMessage());
        AnalyticsException e2 = assertThrows(AnalyticsException.class,
                () -> svc.read(SITE, Period.of("7d"), LA));
        assertEquals("analytics not configured", e2.getMessage());
    }

    @Test
    void periodParsing() {
        assertEquals(Duration.ofHours(24), Period.of("24h").length());
        assertEquals(Duration.ofDays(7), Period.of("7d").length());
        assertEquals(Duration.ofDays(365), Period.of("365d").length());
        assertEquals(NOW.minus(Duration.ofDays(30)), Period.of("30d").startBefore(NOW));
        for (String bad : new String[] {"0d", "366d", "1w", "", " ", null, "07d", "-1d", "24H"}) {
            assertThrows(IllegalArgumentException.class, () -> Period.of(bad), String.valueOf(bad));
        }
    }

    // ------------------------------------------------------------ read

    private static String statsBody() {
        return "{\"pageviews\":4,\"visitors\":2,\"visits\":3,\"bounces\":2,\"totaltime\":29,"
                + "\"comparison\":{\"pageviews\":1,\"visitors\":1,\"visits\":1,\"bounces\":0,\"totaltime\":5}}";
    }

    private Resp readRoute(Req r) {
        String type = r.query() == null ? "" : r.query().replaceAll(".*type=([a-z_]+).*", "$1");
        if (r.path().startsWith("/api/reports/")) {
            return reportRoute(r);
        }
        String path = r.path().replace("/api/websites/" + SITE, "");
        return switch (path) {
            case "/stats" -> new Resp(200, statsBody());
            case "/pageviews" -> new Resp(200, "{\"pageviews\":[{\"x\":\"2026-11-02 00:00:00\",\"y\":2}],"
                    + "\"sessions\":[{\"x\":\"2026-11-02 00:00:00\",\"y\":1}]}");
            case "/metrics/expanded" -> new Resp(200, "[{\"name\":\"/blog/entry/hello\",\"pageviews\":\"3\","
                    + "\"visitors\":2,\"visits\":3,\"bounces\":2,\"totaltime\":\"0\"},"
                    + "{\"name\":\"/blog/entry/second\",\"pageviews\":\"1\",\"visitors\":1,\"visits\":1,"
                    + "\"bounces\":0,\"totaltime\":\"0\"}]");
            case "/metrics" -> new Resp(200, switch (type) {
                case "referrer" -> "[{\"x\":\"google.com\",\"y\":1},{\"x\":\"\",\"y\":4}]";
                case "channel" -> "[{\"x\":\"organicSearch\",\"y\":1}]";
                case "country" -> "[{\"x\":\"GB\",\"y\":2}]";
                case "device" -> "[{\"x\":\"laptop\",\"y\":1}]";
                case "browser" -> "[{\"x\":\"chrome\",\"y\":1},{\"x\":\"safari\",\"y\":1}]";
                case "os" -> "[{\"x\":\"Linux\",\"y\":1}]";
                case "event" -> "[{\"x\":\"cta-click\",\"y\":1}]";
                default -> "[]";
            });
            default -> new Resp(404, "{}");
        };
    }

    private Resp reportRoute(Req r) {
        return r.path().equals("/api/reports/utm")
                    ? new Resp(200, "{\"utm_source\":[{\"utm\":\"newsletter\",\"views\":2}],"
                            + "\"utm_medium\":[{\"utm\":\"email\",\"views\":1}],"
                            + "\"utm_campaign\":[{\"utm\":\"nov\",\"views\":2}],\"utm_term\":[],\"utm_content\":[]}")
                    : new Resp(200, "{\"chart\":[],\"summary\":{\"lcp\":{\"p50\":1617.25,\"p75\":1808.625,\"p95\":1961.725},"
                            + "\"inp\":{\"p50\":140,\"p75\":170,\"p95\":194},\"cls\":{\"p50\":0.1,\"p75\":0.125,\"p95\":0.145},"
                            + "\"fcp\":{\"p50\":1200,\"p75\":1350,\"p95\":1470},\"ttfb\":{\"p50\":210,\"p75\":255,\"p95\":291},"
                            + "\"count\":2},\"pages\":[]}");
    }

    @Test
    void readMapsEveryTabFromTheDocumentedEndpoints() throws Exception {
        route = this::readRoute;

        AnalyticsReport report = service().read(SITE, Period.of("7d"), LA);

        assertEquals(NOW, report.asOf());
        assertEquals(new AnalyticsReport.Summary(2, 3, 4, 2, 29), report.current());
        assertEquals(new AnalyticsReport.Summary(1, 1, 1, 0, 5), report.previous(),
                "the previous period is stats' own comparison, with no second call");
        assertEquals(1, seen.stream().filter(r -> r.path().endsWith("/stats")).count());

        // 7d ending 2026-11-02 12:00 PST starts 2026-10-26: eight local days, zero-filled.
        assertEquals(8, report.series().size());
        assertEquals(LocalDate.of(2026, 10, 26), report.series().get(0).day());
        assertEquals(0, report.series().get(0).views());
        AnalyticsReport.SeriesPoint last = report.series().get(7);
        assertEquals(new AnalyticsReport.SeriesPoint(LocalDate.of(2026, 11, 2), 1, 2), last);
        Req series = seen.stream().filter(r -> r.path().endsWith("/pageviews")).findFirst().orElseThrow();
        assertTrue(series.query().contains("unit=day"), series.query());
        assertTrue(series.query().contains("timezone=America%2FLos_Angeles"), series.query());
        assertTrue(series.query().contains("startAt=" + NOW.minus(Duration.ofDays(7)).toEpochMilli()));
        assertTrue(series.query().contains("endAt=" + NOW.toEpochMilli()));
        assertEquals("Bearer tok1", series.auth());

        assertEquals(List.of(new AnalyticsReport.PathCount("/blog/entry/hello", 3, 2),
                new AnalyticsReport.PathCount("/blog/entry/second", 1, 1)), report.paths());

        Map<String, List<KeyCount>> b = report.breakdowns();
        assertEquals(List.of("sources", "places", "devices", "events", "campaigns", "speed"),
                new ArrayList<>(b.keySet()));
        assertEquals(List.of(new KeyCount("google.com", 1), new KeyCount("channel:organicSearch", 1)),
                b.get("sources"));
        assertEquals(List.of(new KeyCount("GB", 2)), b.get("places"));
        assertEquals(List.of(new KeyCount("device:laptop", 1), new KeyCount("browser:chrome", 1),
                new KeyCount("browser:safari", 1), new KeyCount("os:Linux", 1)), b.get("devices"));
        assertEquals(List.of(new KeyCount("cta-click", 1)), b.get("events"));
        assertEquals(List.of(new KeyCount("utm_source:newsletter", 2), new KeyCount("utm_medium:email", 1),
                new KeyCount("utm_campaign:nov", 2)), b.get("campaigns"));
        assertEquals(List.of(new KeyCount("lcp", 1809), new KeyCount("inp", 170), new KeyCount("fcp", 1350),
                new KeyCount("ttfb", 255), new KeyCount("cls", 125)), b.get("speed"));

        Req utm = seen.stream().filter(r -> r.path().equals("/api/reports/utm")).findFirst().orElseThrow();
        JsonNode sent = JSON.readTree(utm.body());
        assertTrue(sent.get("filters").isObject(), "filters is always present");
        assertEquals(SITE, sent.get("websiteId").asString());
        assertEquals(NOW.toString(), sent.get("parameters").get("endDate").asString());
        assertEquals(1, seen.stream().filter(r -> r.path().equals("/api/reports/performance")).count());
        // The metric types that do not exist in 3.3.0 are never asked for.
        String queries = seen.stream().map(Req::query).filter(q -> q != null).collect(Collectors.joining(" "));
        assertFalse(queries.contains("type=url") || queries.contains("type=utm_"), queries);
    }

    @Test
    void speedIsEmptyWhenNothingWasMeasured() throws Exception {
        route = r -> r.path().equals("/api/reports/performance")
                ? new Resp(200, "{\"chart\":[],\"summary\":{\"lcp\":{\"p50\":0,\"p75\":0,\"p95\":0},"
                        + "\"inp\":{\"p50\":0,\"p75\":0,\"p95\":0},\"cls\":{\"p50\":0,\"p75\":0,\"p95\":0},"
                        + "\"fcp\":{\"p50\":0,\"p75\":0,\"p95\":0},\"ttfb\":{\"p50\":0,\"p75\":0,\"p95\":0},\"count\":0}}")
                : readRoute(r);

        assertEquals(List.of(), service().read(SITE, Period.of("30d"), LA).breakdowns().get("speed"));
    }

    @Test
    void aDayPeriodIsZeroFilledHourlyFor24h() throws Exception {
        route = r -> r.path().endsWith("/pageviews")
                ? new Resp(200, "{\"pageviews\":[{\"x\":\"2026-11-02 09:00:00\",\"y\":5}],"
                        + "\"sessions\":[{\"x\":\"2026-11-02 09:00:00\",\"y\":3}]}")
                : readRoute(r);

        List<AnalyticsReport.SeriesPoint> series = service().read(SITE, Period.of("24h"), LA).series();

        // Local hours 13:00 on Nov 1 through 12:00 on Nov 2, both partial, inclusive. The clocks went back in between;
        // buckets are local wall-clock hours (as Umami labels them), so the repeated 01:00 is one bucket.
        assertEquals(25, series.size());
        assertEquals(new AnalyticsReport.SeriesPoint(LocalDate.of(2026, 11, 2), 9, 3, 5),
                series.stream().filter(p -> p.day().equals(LocalDate.of(2026, 11, 2)) && p.hour() == 9)
                        .findFirst().orElseThrow());
        assertTrue(seen.stream().anyMatch(r -> r.query() != null && r.query().contains("unit=hour")));
    }
}
