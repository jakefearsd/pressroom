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
package com.wikantik.pressroom.business.startup;

import com.wikantik.pressroom.testing.UmamiContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shipped {@code deploy/backup/umami-retention.sh} and its
 * {@code deploy/analytics/umami-retention.sql} against the real Umami 3.3.0
 * schema ({@link UmamiContainer}: the compose file's own digest, after
 * Umami's own migrations), the way the backup loop does after the nightly
 * backup. Rows are seeded straight into Umami's tables with ages relative to
 * the database's own clock.
 *
 * <p>Umami's schema has no foreign keys (Prisma's relation mode), so nothing
 * cascades: the SQL deletes each per-visit table itself, children first.
 * The instance is shared with other test classes, so every assertion is
 * scoped to this class's own websites, and every row seeded here is removed
 * afterwards.
 *
 * <p><strong>The deletes are not scoped.</strong> The job under test deletes
 * every back-dated row in the shared Umami database, whatever website it
 * belongs to, exactly as it does in production. Any other test class that
 * seeds Umami rows with a {@code created_at} older than a window used here
 * (30 days, 730 days) must expect them to vanish when this class runs, and
 * must not assert on them across that boundary.
 */
class UmamiRetentionScriptTest {

    private static final Path SCRIPT = Paths.get("../deploy/backup/umami-retention.sh");
    private static final Path SQL = Paths.get("../deploy/analytics/umami-retention.sql");
    private static final String SECRET_PATH = "/retention-test/never-logged-" + UUID.randomUUID();

    /** Every table the retention job trims. */
    private static final Set<String> TRIMMED = Set.of("event_data", "revenue", "website_event",
            "heatmap_event", "session_replay", "session", "session_data", "session_link");

    private static UmamiContainer umami;

    private final List<UUID> websites = new ArrayList<>();

    @BeforeAll
    static void start() {
        umami = UmamiContainer.get();
    }

    @BeforeEach
    void websites() throws SQLException {
        websites.add(website("retention-a"));
        websites.add(website("retention-b"));
    }

    @AfterEach
    void cleanUp() throws SQLException {
        try (Connection con = umami.jdbc()) {
            for (String table : List.of("event_data", "revenue", "website_event", "heatmap_event",
                    "session_replay", "session", "session_data", "session_link", "website")) {
                try (PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM " + table + " WHERE website_id = ANY (?)")) {
                    ps.setArray(1, con.createArrayOf("uuid", websites.toArray()));
                    ps.executeUpdate();
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // The job
    // ---------------------------------------------------------------------

    @Test
    void oldRowsGoRecentRowsStayAndAReRunIsANoOp() throws Exception {
        UUID site = websites.get(0);
        UUID other = websites.get(1);

        // A visit three years ago: every per-visit table has a row.
        UUID oldSession = session(site, 3 * 365);
        UUID oldEvent = event(site, oldSession, 3 * 365);
        UUID oldEventData = eventData(site, oldEvent, 3 * 365);
        UUID oldRevenue = revenue(site, oldSession, oldEvent, 3 * 365);
        UUID oldSessionData = sessionData(site, oldSession, 3 * 365);
        sessionLink(site, oldSession, 3 * 365);
        UUID oldHeatmap = heatmap(site, oldSession, 3 * 365);
        UUID oldReplay = replay(site, oldSession, 3 * 365);

        // Just past the default 730-day window, on the other website.
        UUID pastSession = session(other, 731);
        UUID pastEvent = event(other, pastSession, 731);

        // Just inside it.
        UUID edgeSession = session(other, 729);
        UUID edgeEvent = event(other, edgeSession, 729);

        // An old session still in use: its old event goes, the session stays.
        UUID spanning = session(site, 3 * 365);
        UUID spanningOld = event(site, spanning, 3 * 365);
        UUID spanningRecent = event(site, spanning, 1);
        UUID spanningData = sessionData(site, spanning, 3 * 365);

        // Recent visits, and a session whose first event has not landed yet.
        UUID recentSession = session(site, 10);
        UUID recentEvent = event(site, recentSession, 10);
        UUID recentEventData = eventData(site, recentEvent, 10);
        UUID recentHeatmap = heatmap(site, recentSession, 10);
        UUID brandNew = session(site, 0);

        Container.ExecResult first = run(Map.of());
        assertEquals(0, first.getExitCode(), output(first));

        assertGone("session", "session_id", oldSession);
        assertGone("website_event", "event_id", oldEvent);
        assertGone("event_data", "event_data_id", oldEventData);
        assertGone("revenue", "revenue_id", oldRevenue);
        assertGone("session_data", "session_data_id", oldSessionData);
        assertGone("session_link", "session_id", oldSession);
        assertGone("heatmap_event", "heatmap_event_id", oldHeatmap);
        assertGone("session_replay", "replay_id", oldReplay);
        assertGone("session", "session_id", pastSession);
        assertGone("website_event", "event_id", pastEvent);
        assertGone("website_event", "event_id", spanningOld);

        assertKept("session", "session_id", edgeSession);
        assertKept("website_event", "event_id", edgeEvent);
        assertKept("session", "session_id", spanning);
        assertKept("website_event", "event_id", spanningRecent);
        assertKept("session_data", "session_data_id", spanningData);
        assertKept("session", "session_id", recentSession);
        assertKept("website_event", "event_id", recentEvent);
        assertKept("event_data", "event_data_id", recentEventData);
        assertKept("heatmap_event", "heatmap_event_id", recentHeatmap);
        assertKept("session", "session_id", brandNew);
        for (UUID website : websites) {
            assertKept("website", "website_id", website);
        }

        Map<String, Long> counts = counts(output(first));
        assertEquals(TRIMMED, counts.keySet(), "one count per trimmed table: " + output(first));
        assertTrue(counts.get("website_event") >= 3, output(first));
        assertTrue(counts.get("session") >= 2, output(first));
        assertFalse(output(first).contains(SECRET_PATH), "only counts are logged, never row contents");

        Container.ExecResult second = run(Map.of());
        assertEquals(0, second.getExitCode(), output(second));
        Map<String, Long> again = counts(output(second));
        assertEquals(TRIMMED, again.keySet(), output(second));
        again.forEach((table, n) -> assertEquals(0L, n, "a re-run deletes nothing from " + table));
        assertKept("session", "session_id", spanning);
        assertKept("website_event", "event_id", recentEvent);
    }

    @Test
    void zeroDaysSwitchesItOff() throws Exception {
        UUID site = websites.get(0);
        UUID oldSession = session(site, 3 * 365);
        UUID oldEvent = event(site, oldSession, 3 * 365);

        Container.ExecResult result = run(Map.of("UMAMI_RETENTION_DAYS", "0"));
        assertEquals(0, result.getExitCode(), output(result));
        assertTrue(output(result).contains("Umami retention is off"), output(result));
        assertKept("session", "session_id", oldSession);
        assertKept("website_event", "event_id", oldEvent);

        // The SQL refuses to delete on its own at 0, whoever runs it.
        umami.psql(SQL, Map.of("retention_days", "0"));
        assertKept("session", "session_id", oldSession);
        assertKept("website_event", "event_id", oldEvent);
    }

    @Test
    void theWindowFollowsUmamiRetentionDays() throws Exception {
        UUID site = websites.get(0);
        UUID monthOld = session(site, 40);
        UUID monthOldEvent = event(site, monthOld, 40);
        UUID weekOld = session(site, 7);
        UUID weekOldEvent = event(site, weekOld, 7);

        Container.ExecResult result = run(Map.of("UMAMI_RETENTION_DAYS", "30"));
        assertEquals(0, result.getExitCode(), output(result));

        assertGone("website_event", "event_id", monthOldEvent);
        assertGone("session", "session_id", monthOld);
        assertKept("website_event", "event_id", weekOldEvent);
        assertKept("session", "session_id", weekOld);
    }

    @Test
    void aWindowThatIsNotAWholeNumberDeletesNothing() throws Exception {
        UUID site = websites.get(0);
        UUID oldSession = session(site, 3 * 365);

        Container.ExecResult result = run(Map.of("UMAMI_RETENTION_DAYS", "1; DROP TABLE website"));
        assertEquals(1, result.getExitCode(), output(result));
        assertTrue(output(result).contains("UMAMI_RETENTION_DAYS must be a whole number"), output(result));
        assertKept("session", "session_id", oldSession);
    }

    /** The first run on a large table is bounded: it deletes in batches, each committed. */
    @Test
    void deletesInBatches() throws Exception {
        UUID site = websites.get(0);
        UUID oldSession = session(site, 3 * 365);
        List<UUID> events = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            events.add(event(site, oldSession, 3 * 365));
        }

        umami.psql(SQL, Map.of("batch_size", "2"));

        for (UUID event : events) {
            assertGone("website_event", "event_id", event);
        }
        assertGone("session", "session_id", oldSession);
    }

    /** A stack whose Umami has not booted yet has nothing to trim; that is not a failure. */
    @Test
    void noUmamiDatabaseIsNotAFailure() {
        Container.ExecResult result = run(Map.of("UMAMI_DB", "no_such_umami_db"));
        assertEquals(0, result.getExitCode(), output(result));
        assertTrue(output(result).contains("no Umami database"), output(result));
    }

    /**
     * The table check is a query like any other: when it fails (here, a role
     * refused a connection to the Umami database), the run has failed. It is
     * not "Umami has not created its tables yet", which exits 0 and would let
     * a broken retention job report success every night.
     */
    @Test
    void aFailedTableCheckIsAFailure() throws Exception {
        String locked = "retention_locked";
        String role = "retention_noconnect";
        String password = "noconnect-" + UUID.randomUUID();
        try (Connection con = umami.jdbc(); Statement st = con.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + locked + " WITH (FORCE)");
            st.execute("DROP ROLE IF EXISTS " + role);
            st.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
            st.execute("CREATE DATABASE " + locked);
            st.execute("REVOKE CONNECT ON DATABASE " + locked + " FROM PUBLIC");
        }
        try {
            Container.ExecResult result = run(Map.of("UMAMI_DB", locked,
                    "POSTGRES_USER", role, "POSTGRES_PASSWORD", password));

            assertEquals(1, result.getExitCode(), output(result));
            assertTrue(result.getStderr().contains("Umami retention: could not check"), output(result));
            assertFalse(output(result).contains("has not created its tables yet"), output(result));
        } finally {
            try (Connection con = umami.jdbc(); Statement st = con.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + locked + " WITH (FORCE)");
                st.execute("DROP ROLE IF EXISTS " + role);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static Container.ExecResult run(Map<String, String> env) {
        Map<String, String> all = new HashMap<>(env);
        all.put("ANALYTICS_SQL_DIR", ".");
        return umami.runDeployScript(SCRIPT, all, SQL);
    }

    private static String output(Container.ExecResult result) {
        return result.getStdout() + result.getStderr();
    }

    /** {@code umami-retention: <table>: <n> row(s) deleted}, one per table. */
    private static Map<String, Long> counts(String output) {
        Map<String, Long> counts = new HashMap<>();
        Matcher m = Pattern.compile("umami-retention: (\\w+): (\\d+) row\\(s\\) deleted").matcher(output);
        while (m.find()) {
            counts.put(m.group(1), Long.parseLong(m.group(2)));
        }
        return counts;
    }

    private static UUID website(String name) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection con = umami.jdbc();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO website (website_id, name, domain, created_at)"
                             + " VALUES (?, ?, 'retention.example', now() - interval '5 years')")) {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID insert(String sql, Object... params) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection con = umami.jdbc(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, id);
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 2, params[i]);
            }
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID session(UUID website, int daysAgo) throws SQLException {
        return insert("INSERT INTO session (session_id, website_id, browser, city, created_at)"
                + " VALUES (?, ?, 'firefox', 'Testville', now() - make_interval(days => ?))", website, daysAgo);
    }

    private static UUID event(UUID website, UUID session, int daysAgo) throws SQLException {
        return insert("INSERT INTO website_event (event_id, website_id, session_id, visit_id, url_path,"
                + " event_type, created_at) VALUES (?, ?, ?, gen_random_uuid(), ?, 1,"
                + " now() - make_interval(days => ?))", website, session, SECRET_PATH, daysAgo);
    }

    private static UUID eventData(UUID website, UUID event, int daysAgo) throws SQLException {
        return insert("INSERT INTO event_data (event_data_id, website_id, website_event_id, data_key,"
                + " string_value, data_type, created_at) VALUES (?, ?, ?, 'dest', ?, 1,"
                + " now() - make_interval(days => ?))", website, event, SECRET_PATH, daysAgo);
    }

    private static UUID revenue(UUID website, UUID session, UUID event, int daysAgo) throws SQLException {
        return insert("INSERT INTO revenue (revenue_id, website_id, session_id, event_id, event_name,"
                + " currency, revenue, created_at) VALUES (?, ?, ?, ?, 'buy', 'USD', 1,"
                + " now() - make_interval(days => ?))", website, session, event, daysAgo);
    }

    private static UUID sessionData(UUID website, UUID session, int daysAgo) throws SQLException {
        return insert("INSERT INTO session_data (session_data_id, website_id, session_id, data_key,"
                + " string_value, data_type, created_at) VALUES (?, ?, ?, 'plan', 'free', 1,"
                + " now() - make_interval(days => ?))", website, session, daysAgo);
    }

    private static void sessionLink(UUID website, UUID session, int daysAgo) throws SQLException {
        try (Connection con = umami.jdbc();
             PreparedStatement ps = con.prepareStatement("INSERT INTO session_link (website_id, session_id,"
                     + " distinct_id, created_at) VALUES (?, ?, 'reader-1', now() - make_interval(days => ?))")) {
            ps.setObject(1, website);
            ps.setObject(2, session);
            ps.setInt(3, daysAgo);
            ps.executeUpdate();
        }
    }

    private static UUID heatmap(UUID website, UUID session, int daysAgo) throws SQLException {
        return insert("INSERT INTO heatmap_event (heatmap_event_id, website_id, session_id, visit_id,"
                + " url_path, event_type, created_at) VALUES (?, ?, ?, gen_random_uuid(), ?, 1,"
                + " now() - make_interval(days => ?))", website, session, SECRET_PATH, daysAgo);
    }

    private static UUID replay(UUID website, UUID session, int daysAgo) throws SQLException {
        return insert("INSERT INTO session_replay (replay_id, website_id, session_id, visit_id, chunk_index,"
                + " events, event_count, started_at, ended_at, created_at) VALUES (?, ?, ?, gen_random_uuid(),"
                + " 0, '\\x00'::bytea, 1, now(), now(), now() - make_interval(days => ?))",
                website, session, daysAgo);
    }

    private static boolean present(String table, String idColumn, UUID id) throws SQLException {
        try (Connection con = umami.jdbc();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT count(*) FROM " + table + " WHERE " + idColumn + " = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1) > 0;
            }
        }
    }

    private static void assertGone(String table, String idColumn, UUID id) throws SQLException {
        assertFalse(present(table, idColumn, id), table + " row " + id + " should have been deleted");
    }

    private static void assertKept(String table, String idColumn, UUID id) throws SQLException {
        assertTrue(present(table, idColumn, id), table + " row " + id + " should have been kept");
    }
}
