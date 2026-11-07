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

import com.wikantik.pressroom.testing.PressroomPostgresContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The back-office services' own database servers (spec
 * 2026-11-03-umami-database-server-design, and step 2 of
 * 2026-11-06-back-office-decoupling-design): the {@code umami-move} and
 * {@code listmonk-move} one-shots ({@code deploy/db-move.sh}) that copy an
 * existing host's database from Pressroom's server to the service's own,
 * and the nightly {@code deploy/backup/backup.sh}, which
 * dumps {@code umami} from {@code umami-db} and everything else from
 * {@code postgres}.
 *
 * <p>Two real PostgreSQL servers on one network, under the compose file's
 * service names as aliases, each with its own credentials. The scripts run
 * inside {@code umami-db}'s container, which has the same client tools
 * ({@code psql}, {@code pg_dump}, {@code pg_restore}) as the app image the
 * one-shots run from. The Umami schema here is a small stand-in: the move is
 * a whole-database copy and does not read Umami's tables beyond
 * {@code website_event}'s presence and the counts it reports.
 */
class UmamiDatabaseServerTest {

    private static final Path MOVE = Paths.get("../deploy/db-move.sh");
    private static final Path BACKUP = Paths.get("../deploy/backup/backup.sh");
    private static final Path BACKOFFICE = Paths.get("../deploy/backup/backoffice-backup.sh");

    private static final String SOURCE_PASSWORD = "source-pw-" + UUID.randomUUID();
    private static final String TARGET_PASSWORD = "target-pw-" + UUID.randomUUID();

    private static Network network;
    private static PostgreSQLContainer source;
    private static PostgreSQLContainer target;

    @BeforeAll
    static void start() {
        network = Network.newNetwork();
        source = new PostgreSQLContainer(PressroomPostgresContainer.IMAGE)
                .withNetwork(network).withNetworkAliases("postgres")
                .withDatabaseName("pressroom").withUsername("pressroom").withPassword(SOURCE_PASSWORD);
        target = new PostgreSQLContainer(PressroomPostgresContainer.IMAGE)
                .withNetwork(network).withNetworkAliases("umami-db", "listmonk-db")
                .withDatabaseName("umami").withUsername("umami").withPassword(TARGET_PASSWORD);
        source.start();
        target.start();
    }

    @AfterAll
    static void stop() {
        target.stop();
        source.stop();
        network.close();
    }

    /** Each test starts from no Umami database on Pressroom's server and an empty one on umami-db. */
    @BeforeEach
    void reset() throws SQLException {
        try (Connection con = connect(source, "pressroom"); Statement st = con.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS umami WITH (FORCE)");
            st.execute("DROP DATABASE IF EXISTS listmonk WITH (FORCE)");
        }
        try (Connection con = connect(target, "postgres"); Statement st = con.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS umami WITH (FORCE)");
            st.execute("CREATE DATABASE umami");
            st.execute("DROP DATABASE IF EXISTS listmonk WITH (FORCE)");
            st.execute("CREATE DATABASE listmonk");
        }
    }

    // ---------------------------------------------------------------------
    // The move
    // ---------------------------------------------------------------------

    /** AC 5: copies every row once, owned by umami-db's user, leaves the source alone; a re-run is a no-op. */
    @Test
    void anExistingUmamiIsCopiedOnceAndTheSourceIsLeftAsItWas() throws Exception {
        seedOldUmami();

        Container.ExecResult first = move(Map.of());

        assertEquals(0, first.getExitCode(), output(first));
        assertTrue(first.getStdout().contains("umami-move: website: 2 row(s)"), output(first));
        assertTrue(first.getStdout().contains("umami-move: session: 3 row(s)"), output(first));
        assertTrue(first.getStdout().contains("umami-move: website_event: 5 row(s)"), output(first));
        assertEquals(Map.of("website", 2L, "\"user\"", 1L, "session", 3L, "website_event", 5L), counts(target));
        assertEquals(Map.of("website", 2L, "\"user\"", 1L, "session", 3L, "website_event", 5L), counts(source),
                "the old copy is a rollback point: nothing in it changes");
        try (Connection con = connect(target, "umami"); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'public'")) {
            assertTrue(rs.next());
            assertEquals("umami", rs.getString(1), "objects belong to umami-db's own user");
            assertFalse(rs.next(), "and to no one else");
        }
        try (Connection con = connect(target, "umami"); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM analytics_traffic")) {
            assertTrue(rs.next() && rs.getLong(1) == 2, "views come across too");
        }

        try (Connection con = connect(source, "umami"); Statement st = con.createStatement()) {
            st.execute("INSERT INTO website_event (website_id, session_id) SELECT website_id, session_id"
                    + " FROM session LIMIT 1");
        }
        Container.ExecResult again = move(Map.of());

        assertEquals(0, again.getExitCode(), output(again));
        assertTrue(again.getStdout().contains("umami-db already holds umami; nothing to move"), output(again));
        assertEquals(5L, counts(target).get("website_event"), "a re-run never copies again");
        assertNoPassword(first, again);
    }

    /** AC 6: a fresh install has no Umami database on Pressroom's server. */
    @Test
    void aFreshInstallHasNothingToMove() throws Exception {
        Container.ExecResult result = move(Map.of());

        assertEquals(0, result.getExitCode(), output(result));
        assertTrue(result.getStdout().contains("nothing to move"), output(result));
        assertEquals(0, tableCount(target), "umami-db stays empty for Umami's own first boot");
        assertNoPassword(result);
    }

    /** AC 6: the old provision created the database, but Umami never booted on it. */
    @Test
    void anOldDatabaseWithoutUmamisTablesHasNothingToMove() throws Exception {
        try (Connection con = connect(source, "pressroom"); Statement st = con.createStatement()) {
            st.execute("CREATE DATABASE umami");
        }

        Container.ExecResult result = move(Map.of());

        assertEquals(0, result.getExitCode(), output(result));
        assertTrue(result.getStdout().contains("nothing to move"), output(result));
        assertEquals(0, tableCount(target));
    }

    /** AC 7: a source that cannot be asked is not a source with nothing in it. */
    @Test
    void anUnreachableSourceFailsAndCopiesNothing() throws Exception {
        seedOldUmami();

        Container.ExecResult result = move(Map.of("POSTGRES_PASSWORD", "wrong-" + UUID.randomUUID()));

        assertNotEquals(0, result.getExitCode(), output(result));
        assertTrue(result.getStderr().contains("cannot reach"), output(result));
        assertEquals(0, tableCount(target));
    }

    /** AC 7: likewise umami-db; and a wrong target never falls back to anything. */
    @Test
    void anUnreachableTargetFailsAndCopiesNothing() throws Exception {
        seedOldUmami();

        Container.ExecResult result = move(Map.of("MOVE_DB_PASSWORD", "wrong-" + UUID.randomUUID()));

        assertNotEquals(0, result.getExitCode(), output(result));
        assertTrue(result.getStderr().contains("cannot reach"), output(result));
        // The likely cause after a rollback that restored an older .env: the
        // server keeps the password it was first started with.
        assertTrue(result.getStderr().contains("UMAMI_DB_PASSWORD"), output(result));
        assertEquals(0, tableCount(target));
    }

    /**
     * AC 7: a restore that fails part-way leaves umami-db as it was, so the
     * next deploy tries again rather than finding a half-copy it would
     * mistake for a finished one. A type named like one of the tables makes
     * {@code CREATE TABLE website} fail after other objects were created.
     */
    @Test
    void aFailedRestoreLeavesUmamiDbEmpty() throws Exception {
        seedOldUmami();
        try (Connection con = connect(target, "umami"); Statement st = con.createStatement()) {
            st.execute("CREATE TYPE website AS (x integer)");
        }

        Container.ExecResult result = move(Map.of());

        assertNotEquals(0, result.getExitCode(), output(result));
        assertEquals(0, tableCount(target), "single transaction: all or nothing: " + output(result));
        assertNoPassword(result);
    }

    /**
     * The same script moves listmonk's database to listmonk-db (0.8.5), told
     * by compose which database, which server and which table marks a
     * listmonk that ran.
     */
    @Test
    void anExistingListmonkIsCopiedOnceToItsOwnServer() throws Exception {
        try (Connection con = connect(source, "pressroom"); Statement st = con.createStatement()) {
            st.execute("CREATE DATABASE listmonk");
        }
        try (Connection con = connect(source, "listmonk"); Statement st = con.createStatement()) {
            st.execute("CREATE TABLE subscribers (id serial PRIMARY KEY, email text)");
            st.execute("CREATE TABLE lists (id serial PRIMARY KEY, name text)");
            st.execute("CREATE TABLE campaigns (id serial PRIMARY KEY, name text)");
            st.execute("INSERT INTO subscribers (email) VALUES ('a@example.com'), ('b@example.com')");
            st.execute("INSERT INTO lists (name) VALUES ('weekly')");
        }

        Container.ExecResult first = run(MOVE, listmonkMoveEnv());
        Container.ExecResult again = run(MOVE, listmonkMoveEnv());

        assertEquals(0, first.getExitCode(), output(first));
        assertTrue(first.getStdout().contains("listmonk-move: subscribers: 2 row(s)"), output(first));
        assertTrue(first.getStdout().contains("listmonk-move: lists: 1 row(s)"), output(first));
        assertTrue(first.getStdout().contains("listmonk-move: campaigns: 0 row(s)"), output(first));
        try (Connection con = connect(target, "listmonk"); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM subscribers")) {
            assertTrue(rs.next() && rs.getLong(1) == 2, "subscribers come across");
        }
        assertEquals(0, again.getExitCode(), output(again));
        assertTrue(again.getStdout().contains("listmonk-db already holds listmonk; nothing to move"), output(again));
        assertNoPassword(first, again);
    }

    /** A host whose listmonk never ran (no subscribers table) has nothing to move. */
    @Test
    void aListmonkDatabaseWithoutItsTablesHasNothingToMove() throws Exception {
        try (Connection con = connect(source, "pressroom"); Statement st = con.createStatement()) {
            st.execute("CREATE DATABASE listmonk");
        }

        Container.ExecResult result = run(MOVE, listmonkMoveEnv());

        assertEquals(0, result.getExitCode(), output(result));
        assertTrue(result.getStdout().contains("nothing to move"), output(result));
    }

    /** AC 8: nothing that could put a password on a command line, or echo every command. */
    @Test
    void theMoveScriptKeepsPasswordsOffCommandLines() throws IOException {
        String script = Files.readString(MOVE);
        assertFalse(script.contains("postgresql://"), "a connection URI carries the password on the command line");
        assertFalse(script.matches("(?s).*\\bset\\s+-[a-z]*x.*"), "set -x would echo every expanded command");
        assertFalse(script.matches("(?s).*--password[= ].*"), "no password flags");
    }

    // ---------------------------------------------------------------------
    // The backup
    // ---------------------------------------------------------------------

    /**
     * AC 10, since step 4: the back-office backup dumps umami from umami-db and
     * listmonk from listmonk-db, never the old copies left on Pressroom's
     * server.
     */
    @Test
    void theBackOfficeBackupDumpsEachDatabaseFromItsOwnServer() throws Exception {
        seedOldUmami();
        try (Connection con = connect(source, "umami"); Statement st = con.createStatement()) {
            st.execute("CREATE TABLE only_on_the_old_copy (id integer)");
        }
        try (Connection con = connect(target, "umami"); Statement st = con.createStatement()) {
            st.execute("CREATE TABLE only_on_umami_db (id integer)");
        }
        try (Connection con = connect(target, "listmonk"); Statement st = con.createStatement()) {
            st.execute("CREATE TABLE only_on_listmonk_db (id integer)");
        }
        String dir = "/tmp/backups-" + UUID.randomUUID();

        Container.ExecResult result = run(BACKOFFICE, backOfficeEnv(dir));

        assertEquals(0, result.getExitCode(), output(result));
        String files = exec("ls", dir).getStdout();
        assertEquals(2, files.lines().count(), files);
        assertTrue(toc(dir, files, "umami-").contains("only_on_umami_db"), files);
        assertFalse(toc(dir, files, "umami-").contains("only_on_the_old_copy"), "the old copy is not backed up");
        assertTrue(toc(dir, files, "listmonk-").contains("only_on_listmonk_db"), files);
        assertNoPassword(result);
    }

    /** The core backup is Pressroom's alone: the old back-office copies on its server are not dumped. */
    @Test
    void theCoreBackupLeavesTheBackOfficeAlone() throws Exception {
        seedOldUmami();
        try (Connection con = connect(source, "pressroom"); Statement st = con.createStatement()) {
            st.execute("CREATE DATABASE listmonk");
        }
        String dir = "/tmp/backups-" + UUID.randomUUID();
        exec("mkdir", "-p", "/data/mediafiles", "/data/search-index", "/data/uploads");
        Map<String, String> env = new HashMap<>(Map.of("PGHOST", "postgres", "POSTGRES_USER", "pressroom",
                "POSTGRES_PASSWORD", SOURCE_PASSWORD, "POSTGRES_DB", "pressroom", "BACKUP_DIR", dir));

        Container.ExecResult result = run(BACKUP, env);

        assertEquals(0, result.getExitCode(), output(result));
        List<String> files = exec("ls", dir).getStdout().lines().toList();
        assertEquals(2, files.size(), files.toString());
        assertTrue(files.stream().allMatch(f -> f.startsWith("pressroom-") || f.startsWith("volumes-")), files.toString());
        assertNoPassword(result);
    }

    /**
     * AC 7 for the backup: a fault on one server costs that dump only. With
     * umami-db refusing the login, listmonk's dump is still written, and the
     * run still fails, so the loop skips retention and says so.
     */
    @Test
    void anUnreachableUmamiDbStillLeavesListmonksBackup() throws Exception {
        String dir = "/tmp/backups-" + UUID.randomUUID();
        Map<String, String> env = backOfficeEnv(dir);
        env.put("UMAMI_DB_PASSWORD", "wrong-" + UUID.randomUUID());

        Container.ExecResult result = run(BACKOFFICE, env);

        assertNotEquals(0, result.getExitCode(), "a missing analytics dump is a failed backup: " + output(result));
        String files = exec("ls", dir).getStdout();
        assertTrue(files.lines().anyMatch(f -> f.startsWith("listmonk-") && f.endsWith(".dump")), files + output(result));
        assertFalse(files.lines().anyMatch(f -> f.startsWith("umami-") && f.endsWith(".dump")), files);
        assertTrue(result.getStderr().contains("umami"), output(result));
        assertNoPassword(result);
    }

    private static String toc(String dir, String files, String prefix) throws IOException, InterruptedException {
        String dump = files.lines().filter(f -> f.startsWith(prefix)).findFirst().orElseThrow();
        return exec("pg_restore", "-l", dir + "/" + dump).getStdout();
    }

    /** What compose hands backoffice-backup: both back-office servers, each with its own login. */
    private static Map<String, String> backOfficeEnv(String dir) {
        Map<String, String> env = new HashMap<>(Map.of("UMAMI_DB", "umami", "UMAMI_PGHOST", "umami-db",
                "UMAMI_DB_USER", "umami", "UMAMI_DB_PASSWORD", TARGET_PASSWORD, "LISTMONK_DB", "listmonk",
                "LISTMONK_PGHOST", "listmonk-db", "LISTMONK_DB_USER", "umami",
                "LISTMONK_DB_PASSWORD", TARGET_PASSWORD, "PGCONNECT_TIMEOUT", "10"));
        env.put("BACKUP_DIR", dir);
        return env;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** What compose hands umami-move (docker-compose.backoffice.yml). */
    private static Map<String, String> umamiMoveEnv() {
        Map<String, String> env = new HashMap<>(Map.of("PGHOST", "postgres", "POSTGRES_USER", "pressroom",
                "POSTGRES_PASSWORD", SOURCE_PASSWORD, "PGCONNECT_TIMEOUT", "10"));
        env.putAll(Map.of("MOVE_NAME", "umami-move", "MOVE_DB", "umami", "MOVE_MARKER_TABLE", "website_event",
                "MOVE_COUNT_TABLES", "website session website_event", "MOVE_PGHOST", "umami-db",
                "MOVE_DB_USER", "umami", "MOVE_DB_PASSWORD", TARGET_PASSWORD,
                "MOVE_PASSWORD_SETTING", "UMAMI_DB_PASSWORD"));
        return env;
    }

    /** What compose hands listmonk-move. listmonk-db is the same test server, under a second alias. */
    private static Map<String, String> listmonkMoveEnv() {
        Map<String, String> env = umamiMoveEnv();
        env.putAll(Map.of("MOVE_NAME", "listmonk-move", "MOVE_DB", "listmonk", "MOVE_MARKER_TABLE", "subscribers",
                "MOVE_COUNT_TABLES", "subscribers lists campaigns", "MOVE_PGHOST", "listmonk-db",
                "MOVE_DB_USER", "umami", "MOVE_PASSWORD_SETTING", "LISTMONK_DB_PASSWORD"));
        return env;
    }

    private static Container.ExecResult move(Map<String, String> overrides) throws IOException, InterruptedException {
        Map<String, String> env = umamiMoveEnv();
        env.putAll(overrides);
        return run(MOVE, env);
    }

    private static Container.ExecResult run(Path script, Map<String, String> env)
            throws IOException, InterruptedException {
        String dir = "/tmp/script-" + UUID.randomUUID();
        target.copyFileToContainer(MountableFile.forHostPath(script), dir + "/" + script.getFileName());
        return target.execInContainer(ExecConfig.builder()
                .command(new String[] {"bash", dir + "/" + script.getFileName()})
                .workDir(dir)
                .envVars(env)
                .build());
    }

    private static Container.ExecResult exec(String... command) throws IOException, InterruptedException {
        return target.execInContainer(command);
    }

    /** An Umami as 0.7.1 left it on Pressroom's server: its tables, an extension, a view granted to grafana_ro. */
    private static void seedOldUmami() throws SQLException {
        try (Connection con = connect(source, "pressroom"); Statement st = con.createStatement()) {
            st.execute("CREATE DATABASE umami");
            st.execute("""
                    DO $$ BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grafana_ro') THEN
                            CREATE ROLE grafana_ro NOLOGIN;
                        END IF;
                    END $$""");
        }
        try (Connection con = connect(source, "umami"); Statement st = con.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
            st.execute("CREATE TABLE website (website_id uuid PRIMARY KEY, name text)");
            st.execute("CREATE TABLE \"user\" (user_id uuid PRIMARY KEY, username text)");
            st.execute("CREATE TABLE session (session_id uuid PRIMARY KEY, website_id uuid)");
            st.execute("CREATE TABLE website_event (event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),"
                    + " website_id uuid, session_id uuid, created_at timestamptz DEFAULT now())");
            st.execute("CREATE VIEW analytics_traffic AS SELECT website_id, count(*) AS views"
                    + " FROM website_event GROUP BY website_id");
            st.execute("GRANT SELECT ON analytics_traffic TO grafana_ro");
            st.execute("INSERT INTO website VALUES (gen_random_uuid(), 'a'), (gen_random_uuid(), 'b')");
            st.execute("INSERT INTO \"user\" VALUES (gen_random_uuid(), 'admin')");
            st.execute("INSERT INTO session SELECT gen_random_uuid(), website_id FROM website");
            st.execute("INSERT INTO session SELECT gen_random_uuid(), website_id FROM website LIMIT 1");
            st.execute("INSERT INTO website_event (website_id, session_id)"
                    + " SELECT website_id, session_id FROM session");
            st.execute("INSERT INTO website_event (website_id, session_id)"
                    + " SELECT website_id, session_id FROM session LIMIT 2");
        }
    }

    private static Map<String, Long> counts(PostgreSQLContainer server) throws SQLException {
        Map<String, Long> counts = new HashMap<>();
        try (Connection con = connect(server, "umami"); Statement st = con.createStatement()) {
            for (String table : List.of("website", "\"user\"", "session", "website_event")) {
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
                    rs.next();
                    counts.put(table, rs.getLong(1));
                }
            }
        }
        return counts;
    }

    private static int tableCount(PostgreSQLContainer server) throws SQLException {
        try (Connection con = connect(server, "umami"); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Connection connect(PostgreSQLContainer server, String database) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + server.getHost() + ":"
                + server.getMappedPort(5432) + "/" + database, server.getUsername(), server.getPassword());
    }

    private static String output(Container.ExecResult result) {
        return result.getStdout() + result.getStderr();
    }

    /** AC 8: neither server's password is ever printed. */
    private static void assertNoPassword(Container.ExecResult... results) {
        for (Container.ExecResult result : results) {
            assertFalse(output(result).contains(SOURCE_PASSWORD), output(result));
            assertFalse(output(result).contains(TARGET_PASSWORD), output(result));
        }
    }
}
