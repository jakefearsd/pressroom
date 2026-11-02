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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wikantik.pressroom.testing.UmamiContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the digest-pinned Umami 3.3.0 image actually does, before any
 * Pressroom code is built against it: the dedicated account made by
 * {@code deploy/analytics/umami-pressroom-account.sql}, website registration
 * (including whether a supplied ID is honoured), what the account may and may
 * not do, and the read endpoints and response shapes the Traffic tabs need.
 *
 * <p>Every expectation here was observed against the real image; a surprise
 * is pinned as observed and written up in {@code docs/dev/umami-api.md},
 * never loosened. If an image bump breaks this class, the doc is what has to
 * be re-checked. Spec: {@code docs/superpowers/specs/2026-11-01-analytics-
 * without-admin-steps-design.md}.
 */
class UmamiApiContractTest {

    private static final Path ACCOUNT_SQL = Paths.get("../deploy/analytics/umami-pressroom-account.sql");
    private static final String USERNAME = "pressroom";
    /** Generated per run and never printed. */
    private static final String PASSWORD = "contract-" + UUID.randomUUID();
    private static final String TIMEZONE = "America/Los_Angeles";
    private static final String BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";
    private static final String OTHER_BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15";
    /** MaxMind's documented test address in Great Britain. */
    private static final String GB_ADDRESS = "81.2.69.160";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** The metric types the seven Traffic tabs read from {@code /metrics}. */
    private static final List<String> TAB_METRIC_TYPES = List.of(
            "path", "entry", "title", "referrer", "channel", "country", "region", "city",
            "device", "browser", "os", "language", "event", "hostname", "query");

    private static UmamiContainer umami;
    private static String token;

    record Answer(int status, JsonNode body, String raw) {}

    @BeforeAll
    static void createAccount() {
        assertTrue(Files.isRegularFile(ACCOUNT_SQL), "missing " + ACCOUNT_SQL.toAbsolutePath());
        umami = UmamiContainer.get();
        applyAccountSql(USERNAME, PASSWORD);
        token = umami.login(USERNAME, PASSWORD);
    }

    // ------------------------------------------------------------ the account

    @Test
    void accountSqlCreatesALoginThatCanCreateWebsites() throws Exception {
        assertFalse(token.isBlank());

        Answer me = post("/api/auth/verify", token, null);
        assertEquals(200, me.status(), me.raw());
        assertEquals(USERNAME, me.body().get("username").asString());
        assertEquals("user", me.body().get("role").asString());
        assertFalse(me.body().get("isAdmin").asBoolean());
        Answer whoami = get("/api/me", token);
        assertEquals(200, whoami.status());
        assertFalse(whoami.body().get("token").asString().isBlank(),
                "observed: /api/me's body carries a fresh token, so it must never be logged");

        Answer created = createWebsite(token, Map.of("name", "contract-create", "domain", "create.example.com"));
        assertEquals(200, created.status(), created.raw());
        assertEquals(me.body().get("id").asString(), created.body().get("userId").asString(),
                "the website belongs to the pressroom user, not to a team");
        assertEquals(me.body().get("id").asString(), created.body().get("createdBy").asString());
        assertTrue(created.body().get("teamId").isNull());
    }

    @Test
    void accountSqlIsIdempotentAndRotatesPassword() throws Exception {
        String name = "pressroom-rotation";
        String first = "first-" + UUID.randomUUID();
        String second = "second-" + UUID.randomUUID();

        applyAccountSql(name, first);
        String heldToken = umami.login(name, first);
        assertEquals(200, post("/api/auth/verify", heldToken, null).status());

        // Someone promotes the account by hand; a re-run puts it back.
        try (Connection con = umami.jdbc();
             PreparedStatement ps = con.prepareStatement("UPDATE \"user\" SET role = 'admin' WHERE username = ?")) {
            ps.setString(1, name);
            assertEquals(1, ps.executeUpdate());
        }
        applyAccountSql(name, second);

        assertEquals(401, loginStatus(name, first), "the old password no longer works");
        assertEquals(200, loginStatus(name, second), "the new one does");
        assertEquals(401, post("/api/auth/verify", heldToken, null).status(),
                "observed: a password change invalidates tokens issued before it, so a "
                        + "client holding one must log in again on 401");
        try (Connection con = umami.jdbc();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT count(*), min(role), min(password) FROM \"user\" WHERE username = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "a re-run updates the one row, never adds another");
                assertEquals("user", rs.getString(2), "a re-run restores least privilege");
                assertTrue(rs.getString(3).startsWith("$2"), "stored as bcrypt, never plain text");
            }
        }
    }

    /**
     * psql substitutes {@code :'pw'} into the statement text before sending it,
     * and PostgreSQL's default {@code log_min_error_statement = error} copies a
     * failing statement into the server log, which in production is the shared
     * postgres service's log. Run before Umami's first boot (no "user" table),
     * the script must fail without the password reaching that log.
     */
    @Test
    void accountSqlNeverLogsThePasswordWhenUmamiHasNotBooted() throws Exception {
        String secret = "never-logged-" + UUID.randomUUID();
        String unbooted = "umami_unbooted_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (Connection con = umami.jdbc(); Statement st = con.createStatement()) {
            st.execute("CREATE DATABASE " + unbooted);
        }
        try {
            Pattern failure = Pattern.compile("ERROR:[^\\n]*\"user\"");
            int before = count(failure, umami.databaseLogs());
            assertThrows(IllegalStateException.class,
                    () -> umami.psql(ACCOUNT_SQL, Map.of("username", USERNAME, "pw", secret), unbooted));
            String logs = awaitLog(failure, before);
            assertFalse(logs.contains(secret), "the password reached PostgreSQL's server log");
        } finally {
            try (Connection con = umami.jdbc(); Statement st = con.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + unbooted);
            }
        }
    }

    /** The same, for a statement that fails to parse: {@code :username} left unset. */
    @Test
    void accountSqlNeverLogsThePasswordWhenAVariableIsMissing() throws Exception {
        String secret = "never-logged-" + UUID.randomUUID();
        Pattern failure = Pattern.compile("ERROR:\\s+syntax error at or near \":\"");
        int before = count(failure, umami.databaseLogs());
        assertThrows(IllegalStateException.class, () -> umami.psql(ACCOUNT_SQL, Map.of("pw", secret)));
        String logs = awaitLog(failure, before);
        assertFalse(logs.contains(secret), "the password reached PostgreSQL's server log");
    }

    @Test
    void accountSqlHashesAtBcryptCostTen() throws Exception {
        try (Connection con = umami.jdbc();
             PreparedStatement ps = con.prepareStatement("SELECT password FROM \"user\" WHERE username = ?")) {
            ps.setString(1, USERNAME);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getString(1).startsWith("$2a$10$"), "bcrypt cost 10, not pgcrypto's default 6");
            }
        }
    }

    @Test
    void nonAdminCannotCreateUsers() throws Exception {
        Answer answer = post("/api/users", token,
                Map.of("username", "intruder", "password", "intruder-password", "role", "admin"));
        assertEquals(401, answer.status(), answer.raw());
        try (Connection con = umami.jdbc();
             PreparedStatement ps = con.prepareStatement("SELECT count(*) FROM \"user\" WHERE username = 'intruder'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
        assertEquals(401, get("/api/admin/websites", token).status(), "nor read the admin listing");
    }

    // ---------------------------------------------------------- registration

    @Test
    void suppliedIdIsHonoured() throws Exception {
        String id = UUID.randomUUID().toString();
        Answer created = createWebsite(token, Map.of("id", id, "name", "contract-id", "domain", "id.example.com"));
        assertEquals(200, created.status(), created.raw());
        assertEquals(id, created.body().get("id").asString());

        Answer fetched = get("/api/websites/" + id, token);
        assertEquals(200, fetched.status(), fetched.raw());
        assertEquals(id, fetched.body().get("id").asString());
        assertEquals("id.example.com", fetched.body().get("domain").asString());
    }

    @Test
    void reRegisteringAnExistingIdIsAServerErrorNotAConflict() throws Exception {
        String id = UUID.randomUUID().toString();
        Map<String, String> site = Map.of("id", id, "name", "contract-dup", "domain", "dup.example.com");
        assertEquals(200, createWebsite(token, site).status());

        Answer again = createWebsite(token, site);
        assertEquals(500, again.status(),
                "observed: a duplicate id is an unhandled primary-key violation (500, empty body), "
                        + "so a reconciler must GET /api/websites/{id} first rather than read a conflict: "
                        + again.raw());
        assertTrue(again.raw().isBlank(), "with an empty body: " + again.raw());
        assertEquals(200, get("/api/websites/" + id, token).status(), "the original is untouched");
    }

    @Test
    void unknownWebsiteAnswers401NotFound() throws Exception {
        assertEquals(401, get("/api/websites/" + UUID.randomUUID(), token).status(),
                "observed: Umami answers 401 for a website that does not exist, the same as for "
                        + "one the caller may not see, so 401 alone cannot mean 'register it'");
        // Ruling R24 rests on the reads answering the same way, to a token that is good.
        String missing = UUID.randomUUID().toString();
        assertEquals(401, get(site(missing, "stats", window()), token).status(),
                "a read of a website that does not exist is a 401 too");
        assertEquals(200, post("/api/auth/verify", token, Map.of()).status(), "while the token itself is good");
    }

    @Test
    void adminSeesPressroomOwnedWebsites() throws Exception {
        String id = UUID.randomUUID().toString();
        assertEquals(200, createWebsite(token,
                Map.of("id", id, "name", "contract-admin-sees", "domain", "seen.example.com")).status());
        String admin = umami.loginAdmin();

        assertEquals(200, get("/api/websites/" + id, admin).status(), "admin opens any website by id");

        Answer adminListing = get("/api/admin/websites?pageSize=1000", admin);
        assertEquals(200, adminListing.status(), adminListing.raw());
        JsonNode row = findById(adminListing.body().get("data"), id);
        assertNotNull(row, "Settings > Websites (the admin listing) shows it: " + adminListing.raw());
        assertEquals(USERNAME, row.get("user").get("username").asString(), "with its owner named");

        Answer ownListing = get("/api/websites?pageSize=1000", admin);
        assertEquals(200, ownListing.status());
        assertNull(findById(ownListing.body().get("data"), id),
                "observed: admin's own /api/websites lists only admin's websites, not pressroom's");
    }

    @Test
    void localhostDomainIsRejectedOrAccepted() throws Exception {
        Map<String, Integer> observed = new LinkedHashMap<>();
        for (String domain : List.of("localhost", "localhost:8083", "blog.wikantik.com",
                "example.com:8443", "127.0.0.1", "Blog.Example.com")) {
            observed.put(domain, createWebsite(token, Map.of("name", "contract-domain", "domain", domain)).status());
        }
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("localhost", 200);
        expected.put("localhost:8083", 200);
        expected.put("blog.wikantik.com", 200);
        expected.put("example.com:8443", 400);   // a port is allowed only on localhost
        expected.put("127.0.0.1", 400);          // no IP addresses
        expected.put("Blog.Example.com", 400);   // lower case only: callers must lower-case
        assertEquals(expected, observed);

        Answer missing = createWebsite(token, Map.of("name", "contract-no-domain"));
        assertEquals(400, missing.status(), "domain is required: " + missing.raw());
    }

    // ------------------------------------------------------------- reading

    @Test
    void readEndpointsAnswer() throws Exception {
        String id = registerFreshWebsite("contract-empty", "empty.example.com");
        String window = window();

        Answer stats = get(site(id, "stats", window), token);
        assertEquals(200, stats.status(), stats.raw());
        assertSummaryFields(stats.body());
        assertSummaryFields(stats.body().get("comparison"));
        assertEquals(0, stats.body().get("pageviews").asInt());

        Answer series = get(site(id, "pageviews", window + "&unit=day&timezone=" + TIMEZONE), token);
        assertEquals(200, series.status(), series.raw());
        assertTrue(series.body().get("pageviews").isArray());
        assertTrue(series.body().get("sessions").isArray());

        for (String type : TAB_METRIC_TYPES) {
            Answer metric = get(site(id, "metrics", window + "&type=" + type), token);
            assertEquals(200, metric.status(), type + ": " + metric.raw());
            assertTrue(metric.body().isArray(), type);
        }
        for (String type : List.of("path", "referrer", "country", "browser", "event")) {
            Answer expanded = get(site(id, "metrics/expanded", window + "&type=" + type), token);
            assertEquals(200, expanded.status(), type + ": " + expanded.raw());
            assertTrue(expanded.body().isArray(), type);
        }
        for (String unsupported : List.of("url", "utm_source", "utm_campaign")) {
            assertEquals(400, get(site(id, "metrics", window + "&type=" + unsupported), token).status(),
                    unsupported + " is not a metric type in 3.3.0 (UTM comes from reports/utm)");
        }

        Answer events = get(site(id, "events/stats", window), token);
        assertEquals(200, events.status(), events.raw());
        assertTrue(events.body().get("data").has("comparison"));

        Answer performance = post("/api/reports/performance", token, report(id, "performance"));
        assertEquals(200, performance.status(), performance.raw());
        for (String vital : List.of("lcp", "inp", "cls", "fcp", "ttfb")) {
            assertTrue(performance.body().get("summary").has(vital), vital);
        }
        assertEquals(0, performance.body().get("summary").get("count").asInt(),
                "observed: no data is count 0 with zero percentiles, not nulls");
        for (String list : List.of("chart", "pages", "pageTitles", "devices", "browsers")) {
            assertTrue(performance.body().get(list).isArray(), list);
        }

        assertEquals(405, get("/api/reports/performance?websiteId=" + id + "&" + window, token).status(),
                "reports are POST only");

        Answer utm = post("/api/reports/utm", token, report(id, "utm"));
        assertEquals(200, utm.status(), utm.raw());
        for (String key : List.of("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content")) {
            assertTrue(utm.body().get(key).isArray(), key);
        }

        Map<String, Object> noFilters = report(id, "utm");
        noFilters.remove("filters");
        assertEquals(400, post("/api/reports/utm", token, noFilters).status(),
                "observed: reports require a filters object, even an empty one");
        assertEquals(400, get(site(id, "pageviews", window + "&unit=day&timezone=Not/AZone"), token).status(),
                "an unknown time zone is rejected, not silently treated as UTC");
    }

    @Test
    void seededTrafficShapes() throws Exception {
        String id = registerFreshWebsite("contract-traffic", "traffic.example.com");
        String host = "traffic.example.com";
        assertEquals(200, send(BROWSER_UA, "event", Map.of("website", id, "hostname", host,
                "url", "/blog/entry/hello?utm_source=newsletter&utm_medium=email&utm_campaign=nov",
                "referrer", "https://www.google.com/search?q=x", "title", "Hello",
                "screen", "1920x1080", "language", "en-US")));
        assertEquals(200, send(BROWSER_UA, "event", Map.of("website", id, "hostname", host,
                "url", "/blog/entry/hello", "title", "Hello", "screen", "1920x1080", "language", "en-US")));
        assertEquals(200, send(BROWSER_UA, "event", Map.of("website", id, "hostname", host,
                "url", "/blog/entry/hello", "title", "Hello", "name", "cta-click",
                "data", Map.of("dest", "https://book.example.com"))));
        assertEquals(200, send(OTHER_BROWSER_UA, "event", Map.of("website", id, "hostname", host,
                "url", "/blog/entry/hello", "title", "Hello", "screen", "1440x900", "language", "fr-FR")));
        assertEquals(200, send(OTHER_BROWSER_UA, "performance", Map.of("website", id, "hostname", host,
                "url", "/blog/entry/hello", "title", "Hello",
                "lcp", 2000, "inp", 200, "cls", 0.15, "fcp", 1500, "ttfb", 300)));
        // Split the first visitor's first pageview into a visit of its own, so
        // visitors (2), visits (3) and pageviews (3) all differ and each count
        // below can only be the one it claims to be.
        try (Connection con = umami.jdbc();
             PreparedStatement ps = con.prepareStatement("""
                     UPDATE website_event SET visit_id = gen_random_uuid()
                     WHERE event_id = (SELECT event_id FROM website_event
                                       WHERE website_id = ?::uuid AND event_type = 1
                                       ORDER BY created_at LIMIT 1)
                     """)) {
            ps.setString(1, id);
            assertEquals(1, ps.executeUpdate());
        }
        String window = window();

        JsonNode stats = get(site(id, "stats", window), token).body();
        assertEquals(3, stats.get("pageviews").asInt(), "pageviews count event_type 1 only: " + stats);
        assertEquals(2, stats.get("visitors").asInt(), stats.toString());
        assertEquals(3, stats.get("visits").asInt(), stats.toString());

        JsonNode paths = get(site(id, "metrics", window + "&type=path"), token).body();
        assertEquals("/blog/entry/hello", paths.get(0).get("x").asString());
        assertEquals(2, paths.get(0).get("y").asInt(),
                "observed: /metrics y counts distinct visitors (2), not visits (3) or pageviews (3): " + paths);

        JsonNode expanded = get(site(id, "metrics/expanded", window + "&type=path"), token).body();
        JsonNode hello = expanded.get(0);
        assertEquals("/blog/entry/hello", hello.get("name").asString());
        assertTrue(hello.get("pageviews").isString(), "observed: pageviews is a JSON string here: " + hello);
        assertEquals("3", hello.get("pageviews").asString());
        assertTrue(hello.get("visitors").isNumber());
        assertEquals(2, hello.get("visitors").asInt());
        assertTrue(hello.get("visits").isNumber(), hello.toString());
        assertEquals(3, hello.get("visits").asInt());
        assertTrue(hello.get("bounces").isNumber(), hello.toString());
        assertTrue(hello.get("totaltime").isString(), "observed: totaltime is a JSON string too: " + hello);

        JsonNode referrers = get(site(id, "metrics", window + "&type=referrer"), token).body();
        assertEquals("google.com", referrers.get(0).get("x").asString(), "referrers are bare domains");
        JsonNode countries = get(site(id, "metrics", window + "&type=country"), token).body();
        assertEquals("GB", countries.get(0).get("x").asString(), "countries are ISO 3166-1 alpha-2");
        JsonNode eventsMetric = get(site(id, "metrics", window + "&type=event"), token).body();
        assertEquals("cta-click", eventsMetric.get(0).get("x").asString());

        JsonNode daily = get(site(id, "pageviews", window + "&unit=day&timezone=" + TIMEZONE), token).body();
        assertTrue(Pattern.matches("\\d{4}-\\d{2}-\\d{2} 00:00:00", daily.get("pageviews").get(0).get("x").asString()),
                "observed: day buckets are local midnights without an offset: " + daily);
        Instant now = Instant.now();
        String week = "startAt=" + now.minus(7, ChronoUnit.DAYS).toEpochMilli()
                + "&endAt=" + now.plus(1, ChronoUnit.MINUTES).toEpochMilli();
        JsonNode weekly = get(site(id, "pageviews", week + "&unit=day&timezone=" + TIMEZONE), token).body();
        assertEquals(1, weekly.get("pageviews").size(),
                "days with no traffic are omitted, not zero-filled: " + weekly);
        assertEquals(3, weekly.get("pageviews").get(0).get("y").asInt());
        assertEquals(2, weekly.get("sessions").get(0).get("y").asInt(),
                "sessions.y is visitors (2), not visits (3): " + weekly);
        JsonNode compared = get(site(id, "pageviews",
                week + "&unit=day&timezone=" + TIMEZONE + "&compare=prev"), token).body();
        assertTrue(compared.get("startDate").isString(), compared.toString());
        assertTrue(compared.get("endDate").isString(), compared.toString());
        assertTrue(compared.get("compare").get("pageviews").isArray(), compared.toString());
        assertTrue(compared.get("compare").get("sessions").isArray(), compared.toString());
        assertTrue(compared.get("compare").get("startDate").isString(), compared.toString());

        JsonNode eventSeries = get(site(id, "events/series", window + "&unit=day&timezone=" + TIMEZONE), token).body();
        assertEquals("cta-click", eventSeries.get(0).get("x").asString(), eventSeries.toString());
        assertTrue(eventSeries.get(0).get("t").asString().endsWith(" 00:00:00"), eventSeries.toString());
        assertEquals(1, eventSeries.get(0).get("y").asInt());

        JsonNode utm = post("/api/reports/utm", token, report(id, "utm")).body();
        assertEquals("newsletter", utm.get("utm_source").get(0).get("utm").asString());
        assertTrue(utm.get("utm_source").get(0).has("views"));

        JsonNode performance = post("/api/reports/performance", token, report(id, "performance")).body();
        assertEquals(1, performance.get("summary").get("count").asInt());
        assertEquals(2000.0, performance.get("summary").get("lcp").get("p75").asDouble(), 0.001);
        assertEquals("/blog/entry/hello", performance.get("pages").get(0).get("name").asString());

        Instant later = Instant.now().plus(1, ChronoUnit.MINUTES);
        String nextWindow = "startAt=" + later.toEpochMilli() + "&endAt=" + later.plus(1, ChronoUnit.HOURS).toEpochMilli();
        JsonNode next = get(site(id, "stats", nextWindow), token).body();
        assertEquals(0, next.get("pageviews").asInt());
        assertEquals(3, next.get("comparison").get("pageviews").asInt(),
                "comparison is the equal-length period just before startAt, always present: " + next);
        assertEquals(2, next.get("comparison").get("visitors").asInt(), next.toString());
    }

    /**
     * Ruling R23: the read asks {@code metrics/expanded} for 1000 paths. The
     * limit is honoured (a smaller one cuts the list) and 1000 is accepted.
     * Whether the image caps a page below 1000 rows on its own is not tested
     * here (it would take a thousand seeded paths); docs/dev/umami-api.md says so.
     */
    @Test
    void expandedPathsHonourTheLimitAndAcceptAThousand() throws Exception {
        String id = registerFreshWebsite("contract-limit", "limit.example.com");
        for (String path : List.of("/blog/entry/one", "/blog/entry/two", "/blog/entry/three")) {
            assertEquals(200, send(BROWSER_UA, "event", Map.of("website", id, "hostname", "limit.example.com",
                    "url", path, "title", "T", "screen", "1920x1080", "language", "en-US")));
        }
        String window = window();

        Answer two = get(site(id, "metrics/expanded", window + "&type=path&limit=2"), token);
        assertEquals(200, two.status(), two.raw());
        assertEquals(2, two.body().size(), "the limit cuts the list: " + two.raw());
        Answer thousand = get(site(id, "metrics/expanded", window + "&type=path&limit="
                + UmamiAnalyticsService.PATH_LIMIT), token);
        assertEquals(200, thousand.status(), thousand.raw());
        assertEquals(3, thousand.body().size(), "1000 is accepted and returns every path: " + thousand.raw());
    }

    @Test
    void trackerPerformanceAttribute() throws Exception {
        String js = trackerScript();
        assertTrue(js.contains("\"data-\""), "attributes are data- prefixed");
        Matcher performance = Pattern.compile("[\\w$]+\\(\"performance\"\\)===([\\w$]+)").matcher(js);
        assertTrue(performance.find(), "the tracker reads a data-performance attribute");
        assertTrue(Pattern.compile("[^\\w$]" + Pattern.quote(performance.group(1)) + "=\"true\"").matcher(js).find(),
                "and turns collection on only for the exact value \"true\"");
        for (String attribute : List.of("website-id", "host-url", "domains", "do-not-track", "auto-track", "tag")) {
            assertTrue(js.contains("(\"" + attribute + "\")"), attribute);
        }
    }

    /**
     * Read from the pinned image's minified script, since no browser runs
     * here: {@code data-domains} is split on commas and compared with
     * {@code location.hostname}, which carries no port. So
     * {@code localhost:8083} can never match; {@code localhost} does.
     */
    @Test
    void trackerDomainsMatchTheHostnameWithoutPort() throws Exception {
        String js = trackerScript();
        Matcher location = Pattern.compile("location:([\\w$]+)").matcher(js);
        assertTrue(location.find(), "the tracker destructures window.location");
        Matcher hostname = Pattern.compile("\\{hostname:([\\w$]+)[^}]*\\}=" + Pattern.quote(location.group(1)) + "[^\\w$]")
                .matcher(js);
        assertTrue(hostname.find(), "and takes location.hostname from it");
        Matcher domains = Pattern.compile("([\\w$]+)=[\\w$]+\\(\"domains\"\\)").matcher(js);
        assertTrue(domains.find(), "the tracker reads data-domains");
        Matcher list = Pattern.compile("([\\w$]+)=" + Pattern.quote(domains.group(1)) + "\\.split\\(\",\"\\)").matcher(js);
        assertTrue(list.find(), "split on commas");
        assertTrue(js.contains(domains.group(1) + "&&!" + list.group(1) + ".includes(" + hostname.group(1) + ")"),
                "and beacons are dropped unless the list includes location.hostname exactly");
    }

    @Test
    void trackerStoresNothingInTheBrowser() throws Exception {
        String js = trackerScript();
        assertFalse(js.contains("setItem"), "no localStorage/sessionStorage writes");
        assertFalse(js.contains("cookie"), "no cookies");
        assertTrue(js.contains("getItem(\"umami.disabled\")"), "it only reads the opt-out flag");
    }

    // ------------------------------------------------------------ helpers

    private static String trackerScript() throws Exception {
        HttpResponse<String> script = raw(HttpRequest.newBuilder(URI.create(umami.baseUrl() + "/script.js")).GET());
        assertEquals(200, script.statusCode());
        return script.body();
    }

    private static void applyAccountSql(String username, String password) {
        umami.psql(ACCOUNT_SQL, Map.of("username", username, "pw", password));
    }

    private static int count(Pattern pattern, String text) {
        int n = 0;
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            n++;
        }
        return n;
    }

    /**
     * Waits until the database log shows one more {@code marker} than
     * {@code before} (proof the failure has been logged, so an absent
     * password is absent and not merely not yet flushed), then a short
     * settle for the lines PostgreSQL writes after it.
     */
    private static String awaitLog(Pattern marker, int before) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (count(marker, umami.databaseLogs()) <= before) {
            assertTrue(System.nanoTime() < deadline, "the failure never reached the database log");
            Thread.sleep(100);
        }
        Thread.sleep(500);
        return umami.databaseLogs();
    }

    private static int loginStatus(String username, String password) throws Exception {
        return post("/api/auth/login", null, Map.of("username", username, "password", password)).status();
    }

    private static String registerFreshWebsite(String name, String domain) throws Exception {
        String id = UUID.randomUUID().toString();
        Answer created = createWebsite(token, Map.of("id", id, "name", name, "domain", domain));
        assertEquals(200, created.status(), created.raw());
        return id;
    }

    private static Answer createWebsite(String bearer, Map<String, String> body) throws Exception {
        return post("/api/websites", bearer, body);
    }

    /** The last hour, plus a minute of slack for clock skew with the container. */
    private static String window() {
        Instant now = Instant.now();
        return "startAt=" + now.minus(1, ChronoUnit.HOURS).toEpochMilli()
                + "&endAt=" + now.plus(1, ChronoUnit.MINUTES).toEpochMilli();
    }

    private static String site(String id, String endpoint, String query) {
        return "/api/websites/" + id + "/" + endpoint + "?" + query;
    }

    private static Map<String, Object> report(String id, String type) {
        Instant now = Instant.now();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("startDate", now.minus(1, ChronoUnit.HOURS).toString());
        parameters.put("endDate", now.plus(1, ChronoUnit.MINUTES).toString());
        parameters.put("timezone", TIMEZONE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("websiteId", id);
        body.put("type", type);
        body.put("filters", Map.of());
        body.put("parameters", parameters);
        return body;
    }

    private static void assertSummaryFields(JsonNode node) {
        for (String field : List.of("pageviews", "visitors", "visits", "bounces", "totaltime")) {
            assertTrue(node.get(field).isNumber(), field + " in " + node);
        }
    }

    private static JsonNode findById(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (id.equals(row.get("id").asString())) {
                return row;
            }
        }
        return null;
    }

    private static int send(String userAgent, String type, Map<String, Object> payload) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(umami.baseUrl() + "/api/send"))
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent)
                .header("X-Forwarded-For", GB_ADDRESS)
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(Map.of("type", type, "payload", payload)), StandardCharsets.UTF_8));
        return raw(request).statusCode();
    }

    private static Answer get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(umami.baseUrl() + path)).GET();
        return answer(request, bearer);
    }

    private static Answer post(String path, String bearer, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(umami.baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        return answer(request, bearer);
    }

    private static Answer answer(HttpRequest.Builder request, String bearer) throws IOException, InterruptedException {
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> response = raw(request);
        String body = response.body();
        JsonNode json = body == null || body.isBlank() ? JSON.getNodeFactory().nullNode() : JSON.readTree(body);
        return new Answer(response.statusCode(), json, body);
    }

    private static HttpResponse<String> raw(HttpRequest.Builder request) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
