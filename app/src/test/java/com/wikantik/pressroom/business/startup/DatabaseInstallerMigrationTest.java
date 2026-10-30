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
import com.wikantik.pressroom.business.DatabaseProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DatabaseInstaller} driving migrations into a throwaway database on
 * the shared PostgreSQL server: what it decides (create, upgrade, refuse),
 * what it records in {@code schema_migrations}, and what it reports when a
 * migration cannot be read or applied. Characterisation tests: written against
 * the existing behaviour and expected to pass immediately.
 *
 * <p>Each test gets its own freshly created database and drops it afterwards;
 * the suite's shared {@code pressroom} database is never touched.
 */
class DatabaseInstallerMigrationTest {

    private static final String V001 = "V001__schema_migrations";

    private String dbName;
    private DatabaseProvider provider;

    @BeforeEach
    void createThrowawayDatabase() throws Exception {
        dbName = "installer_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + dbName);
        }
        provider = mock(DatabaseProvider.class);
        when(provider.getConnection()).thenAnswer(invocation -> throwawayConnection());
    }

    @AfterEach
    void dropThrowawayDatabase() throws Exception {
        try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
        }
    }

    @Test
    void anEmptyDatabaseIsCreatedByApplyingTheWholeChainAndThenReportsUpToDate() throws Exception {
        DatabaseInstaller installer = new DatabaseInstaller(provider, new ClasspathDatabaseScriptProvider());
        List<String> versions = MigrationCatalog.versions();

        assertTrue(installer.isCreationRequired(), "no tables at all means the schema must be created");
        assertTrue(installer.isUpgradeRequired(), "no tracking table means every migration is pending");

        installer.createDatabase();

        assertEquals(versions, recordedVersions(), "every migration on the classpath is recorded, in order");
        assertTrue(tableExists("pressroom_user"), "the baseline schema really was created");
        List<String> messages = installer.getMessages();
        assertEquals("Bootstrapping schema_migrations from V001", messages.get(0));
        assertTrue(messages.contains(V001 + " applied"), messages.toString());
        assertTrue(messages.contains(versions.getLast() + " applied"), messages.toString());
        assertEquals("Applied " + versions.size() + " migration(s)", messages.getLast());

        assertFalse(installer.isCreationRequired());
        assertFalse(installer.isUpgradeRequired());

        DatabaseInstaller again = new DatabaseInstaller(provider, new ClasspathDatabaseScriptProvider());
        again.upgradeDatabase(true);
        assertEquals(List.of("Database is up to date (" + versions.size() + " migrations applied)"),
                again.getMessages());
    }

    @Test
    void anEmptyTrackingTableStillCountsAsNeedingCreation() throws Exception {
        execute("CREATE TABLE schema_migrations (version VARCHAR(64) PRIMARY KEY, "
                + "applied_at TIMESTAMP NOT NULL DEFAULT NOW())");

        DatabaseInstaller installer = new DatabaseInstaller(provider, new ClasspathDatabaseScriptProvider());

        assertTrue(installer.isCreationRequired());
        assertTrue(installer.isUpgradeRequired());
    }

    @Test
    void anUpgradeAppliesOnlyThePendingMigrationAndExpandsTheAppUserVariable() throws Exception {
        List<String> versions = MigrationCatalog.versions();
        String last = versions.getLast();
        execute("CREATE TABLE schema_migrations (version VARCHAR(64) PRIMARY KEY, "
                + "applied_at TIMESTAMP NOT NULL DEFAULT NOW())");
        for (String version : versions.subList(0, versions.size() - 1)) {
            execute("INSERT INTO schema_migrations (version) VALUES ('" + version + "')");
        }
        // Stand in for the newest migration with one whose effect is easy to
        // observe; ':app_user' inside the literal proves the substitution.
        DatabaseScriptProvider scripts = path -> path.equals(last + ".sql")
                ? sql("CREATE TABLE upgrade_probe (id INT);\n"
                        + "COMMENT ON TABLE upgrade_probe IS 'granted to :app_user';")
                : failIfRead(path);
        DatabaseInstaller installer = new DatabaseInstaller(provider, scripts);

        assertFalse(installer.isCreationRequired(), "migrations are recorded, so this is an upgrade");
        assertTrue(installer.isUpgradeRequired());

        installer.upgradeDatabase(false);

        assertEquals(versions, recordedVersions());
        assertEquals("granted to " + PressroomPostgresContainer.getUsername(),
                queryString("SELECT obj_description('upgrade_probe'::regclass, 'pg_class')"));
        // The messages reach the anonymous installer's pages, so no SQL is echoed
        // there: the expanded :app_user would name the database user.
        List<String> messages = installer.getMessages();
        assertTrue(messages.stream().noneMatch(m -> m.contains("upgrade_probe") || m.contains("granted to")),
                "no SQL, and so no database user, on the page: " + messages);
        assertTrue(messages.contains(last + " applied"), messages.toString());
        assertEquals("Applied 1 migration(s)", messages.getLast());
        assertFalse(installer.isUpgradeRequired());
    }

    @Test
    void aDatabaseWithPressroomTablesButNoTrackingTableIsRefused() throws Exception {
        // Upstream's table name: an Apache Roller schema. rebrand:keep
        execute("CREATE TABLE roller_user (id VARCHAR(48) PRIMARY KEY)"); // rebrand:keep
        DatabaseInstaller installer = new DatabaseInstaller(provider, new ClasspathDatabaseScriptProvider());

        IllegalStateException refused = assertThrows(IllegalStateException.class, installer::isCreationRequired);

        assertTrue(refused.getMessage().startsWith(
                "This database has Apache Roller tables but no schema_migrations table"), // rebrand:keep
                refused.getMessage());
        assertTrue(refused.getMessage().contains("Export your content and load it into a fresh database."),
                refused.getMessage());
    }

    @Test
    void aLegacyUserroleTableAloneIsAlsoRefused() throws Exception {
        execute("CREATE TABLE userrole (id VARCHAR(48) PRIMARY KEY)");
        DatabaseInstaller installer = new DatabaseInstaller(provider, new ClasspathDatabaseScriptProvider());

        assertThrows(IllegalStateException.class, installer::isCreationRequired);
    }

    @Test
    void aFailingMigrationStopsTheChainAndIsNotRecorded() throws Exception {
        String second = MigrationCatalog.versions().get(1);
        DatabaseScriptProvider scripts = onlyV001Then(sql("CREATE TABLE broken (;"));
        DatabaseInstaller installer = new DatabaseInstaller(provider, scripts);

        StartupException failure = assertThrows(StartupException.class, installer::createDatabase);

        assertEquals("Error applying migration " + second, failure.getMessage());
        assertInstanceOf(SQLException.class, failure.getCause());
        assertEquals(List.of(V001), recordedVersions(),
                "V001 committed before the failure; the failing migration is not recorded");
        assertFalse(tableExists("broken"));
        List<String> log = failure.getStartupLog();
        assertTrue(log.contains(V001 + " applied"), log.toString());
        assertTrue(log.contains("ERROR: statement 1 of 1 failed; the details are in the server log."),
                "the runner's error reaches the installer's log: " + log);
        assertTrue(log.stream().noneMatch(m -> m.contains("broken")),
                "by position only; the SQL goes to the server log: " + log);
        assertEquals("ERROR applying migration " + second, log.getLast());
        assertEquals(log, installer.getMessages());
    }

    @Test
    void aFailingMigrationLeavesNoPartialEffectsAndIsNotRecorded() throws Exception {
        String second = MigrationCatalog.versions().get(1);
        DatabaseScriptProvider scripts = onlyV001Then(sql(
                "CREATE TABLE half_applied (id INT);\n"
                + "CREATE TABLE broken (;"));
        DatabaseInstaller installer = new DatabaseInstaller(provider, scripts);

        StartupException failure = assertThrows(StartupException.class, installer::createDatabase);

        assertEquals("Error applying migration " + second, failure.getMessage());
        assertFalse(tableExists("half_applied"),
                "the whole migration must roll back, not just the failing statement");
        assertEquals(List.of(V001), recordedVersions(),
                "a partially-applied, non-atomic migration must not be recorded, "
                        + "or a retry of it fails forever");
    }

    /**
     * Mirrors {@code anUpgradeAppliesOnlyThePendingMigrationAndExpandsTheAppUserVariable}'s
     * setup: every migration but the last is pre-recorded, so the installer applies
     * exactly one, custom, multi-statement migration and there is no "next" migration
     * for the chain to (wrongly) continue into.
     */
    @Test
    void aSuccessfulMultiStatementMigrationCommitsEverythingAndRecordsTheVersion() throws Exception {
        List<String> versions = MigrationCatalog.versions();
        String last = versions.getLast();
        execute("CREATE TABLE schema_migrations (version VARCHAR(64) PRIMARY KEY, "
                + "applied_at TIMESTAMP NOT NULL DEFAULT NOW())");
        for (String version : versions.subList(0, versions.size() - 1)) {
            execute("INSERT INTO schema_migrations (version) VALUES ('" + version + "')");
        }
        DatabaseScriptProvider scripts = path -> path.equals(last + ".sql")
                ? sql("CREATE TABLE multi_a (id INT);\n"
                        + "CREATE TABLE multi_b (id INT);\n"
                        + "CREATE TABLE multi_c (id INT);")
                : failIfRead(path);
        DatabaseInstaller installer = new DatabaseInstaller(provider, scripts);

        installer.upgradeDatabase(false);

        assertTrue(tableExists("multi_a"));
        assertTrue(tableExists("multi_b"));
        assertTrue(tableExists("multi_c"));
        assertEquals(versions, recordedVersions());
    }

    @Test
    void aToleratedFailureInsideATransactionDoesNotAbortTheStatementsAfterIt() throws Exception {
        List<String> versions = MigrationCatalog.versions();
        String last = versions.getLast();
        execute("CREATE TABLE schema_migrations (version VARCHAR(64) PRIMARY KEY, "
                + "applied_at TIMESTAMP NOT NULL DEFAULT NOW())");
        for (String version : versions.subList(0, versions.size() - 1)) {
            execute("INSERT INTO schema_migrations (version) VALUES ('" + version + "')");
        }
        DatabaseScriptProvider scripts = path -> path.equals(last + ".sql")
                ? sql("CREATE TABLE tolerant_probe (id INT);\n"
                        + "alter table tolerant_probe drop index ix_ghost;\n"
                        + "CREATE TABLE tolerant_probe2 (id INT);")
                : failIfRead(path);
        DatabaseInstaller installer = new DatabaseInstaller(provider, scripts);

        installer.upgradeDatabase(false);

        assertTrue(tableExists("tolerant_probe"));
        assertTrue(tableExists("tolerant_probe2"),
                "a tolerated failure (drop index) must not abort the statements after it, "
                        + "even inside a single migration transaction");
        assertEquals(versions, recordedVersions());
        List<String> messages = installer.getMessages();
        assertTrue(messages.contains("INFO: statement 2 of 3 failed and was ignored."), messages.toString());
    }

    @Test
    void aMigrationMissingFromTheClasspathIsReportedByName() throws Exception {
        String second = MigrationCatalog.versions().get(1);
        DatabaseInstaller installer = new DatabaseInstaller(provider, onlyV001Then(null));

        StartupException failure = assertThrows(StartupException.class, () -> installer.upgradeDatabase(true));

        assertEquals("Error applying migration " + second, failure.getMessage());
        StartupException cause = assertInstanceOf(StartupException.class, failure.getCause());
        assertEquals("Migration " + second + " not found on the classpath", cause.getMessage());
        assertEquals(List.of(V001), recordedVersions());
    }

    @Test
    void aMigrationThatCannotBeReadIsReportedWithItsCause() throws Exception {
        String second = MigrationCatalog.versions().get(1);
        IOException diskError = new IOException("disk on fire");
        InputStream unreadable = new InputStream() {
            @Override
            public int read() throws IOException {
                throw diskError;
            }
        };
        DatabaseInstaller installer = new DatabaseInstaller(provider, onlyV001Then(unreadable));

        StartupException failure = assertThrows(StartupException.class, installer::createDatabase);

        StartupException cause = assertInstanceOf(StartupException.class, failure.getCause());
        assertEquals("Could not read migration " + second, cause.getMessage());
        assertEquals(diskError, cause.getCause());
    }

    /**
     * Serves the real V001 (which creates the tracking table) and answers
     * {@code next} for the second migration; reading any later one fails the
     * test, since the chain must stop at the second.
     */
    private DatabaseScriptProvider onlyV001Then(InputStream next) {
        ClasspathDatabaseScriptProvider real = new ClasspathDatabaseScriptProvider();
        String second = MigrationCatalog.versions().get(1) + ".sql";
        return path -> {
            if (path.equals(V001 + ".sql")) {
                return real.getDatabaseScript(path);
            }
            if (path.equals(second)) {
                return next;
            }
            return failIfRead(path);
        };
    }

    private static InputStream failIfRead(String path) {
        throw new AssertionError("the installer should not have read " + path);
    }

    private static InputStream sql(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> recordedVersions() throws SQLException {
        List<String> versions = new ArrayList<>();
        try (Connection con = throwawayConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM schema_migrations ORDER BY version")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }
        return versions;
    }

    private boolean tableExists(String table) throws SQLException {
        return "t".equals(queryString(
                "SELECT CASE WHEN to_regclass('public." + table + "') IS NULL THEN 'f' ELSE 't' END"));
    }

    private String queryString(String query) throws SQLException {
        try (Connection con = throwawayConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(query)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private void execute(String statement) throws SQLException {
        try (Connection con = throwawayConnection(); Statement st = con.createStatement()) {
            st.execute(statement);
        }
    }

    private Connection throwawayConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrlFor(dbName),
                PressroomPostgresContainer.getUsername(), PressroomPostgresContainer.getPassword());
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(PressroomPostgresContainer.getJdbcUrl(),
                PressroomPostgresContainer.getUsername(), PressroomPostgresContainer.getPassword());
    }

    /** Rewrites the container's JDBC URL to point at a different database. */
    private static String jdbcUrlFor(String name) {
        String url = PressroomPostgresContainer.getJdbcUrl();
        int dbStart = url.lastIndexOf('/') + 1;
        int queryStart = url.indexOf('?', dbStart);
        String tail = queryStart < 0 ? "" : url.substring(queryStart);
        return url.substring(0, dbStart) + name + tail;
    }
}
