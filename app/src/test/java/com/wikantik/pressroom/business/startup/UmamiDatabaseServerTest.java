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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
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
 * The back-office databases' backups (spec
 * 2026-11-03-umami-database-server-design, and step 4 of
 * 2026-11-06-back-office-decoupling-design): {@code deploy/backup/backoffice-backup.sh}
 * dumps {@code umami} from {@code umami-db} and {@code listmonk} from
 * {@code listmonk-db}, and the core {@code deploy/backup/backup.sh} dumps
 * Pressroom's server alone. The one-shots that once moved those databases
 * off Pressroom's server are retired.
 *
 * <p>Two real PostgreSQL servers on one network, under the compose file's
 * service names as aliases, each with its own credentials. The scripts run
 * inside {@code umami-db}'s container, which has the same client tools
 * ({@code psql}, {@code pg_dump}, {@code pg_restore}) as the app image the
 * backups run from. The Umami schema here is a small stand-in, seeded on
 * Pressroom's server as an old copy the backups must leave alone.
 */
class UmamiDatabaseServerTest {

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
