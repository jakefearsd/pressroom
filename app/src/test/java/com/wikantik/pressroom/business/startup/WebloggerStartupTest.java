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
import com.wikantik.pressroom.business.MailProvider;
import com.wikantik.pressroom.config.WebloggerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link WebloggerStartup}'s preparation sequence and the install wizard's
 * create/upgrade entry points: when the application counts as prepared, what
 * each installation type does to a database, and what a failure carries back.
 * Characterisation tests: written against the existing behaviour and expected
 * to pass immediately.
 *
 * <p>{@code WebloggerStartup} keeps its state in private statics shared with
 * the rest of the suite (the real tier's {@code DatabaseProvider} lives
 * there), so every test saves all four and restores them afterwards, as
 * {@code InstallControllerTest} does. Databases are throwaway ones created on
 * the shared server and dropped afterwards; the suite's {@code pressroom} database is
 * never touched.
 */
class WebloggerStartupTest {

    private Object savedPrepared;
    private Object savedDbProvider;
    private Object savedDbProviderException;
    private MailProvider savedMailProvider;

    private final List<String> throwawayDatabases = new ArrayList<>();

    @BeforeEach
    void saveStartupState() {
        savedPrepared = getField("prepared");
        savedDbProvider = getField("dbProvider");
        savedDbProviderException = getField("dbProviderException");
        savedMailProvider = WebloggerStartup.currentMailProvider();
        setField("prepared", false);
    }

    @AfterEach
    void restoreStartupStateAndDropDatabases() throws Exception {
        setField("prepared", savedPrepared);
        setField("dbProvider", savedDbProvider);
        setField("dbProviderException", savedDbProviderException);
        WebloggerStartup.installMailProvider(savedMailProvider);
        try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
            for (String name : throwawayDatabases) {
                st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            }
        }
    }

    @Test
    void theDatabaseProviderIsRefusedBeforePreparation() {
        setField("dbProvider", null);

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, WebloggerStartup::getDatabaseProvider);
        assertEquals("Roller Weblogger has not been prepared yet", refused.getMessage());
        assertThrows(IllegalStateException.class, WebloggerStartup::isDatabaseCreationRequired,
                "nor can the installer be asked anything without one");
    }

    @Test
    void aDatabaseProviderFailureIsKeptForTheInstallPage() {
        StartupException failure = assertThrows(StartupException.class, () -> prepareWith(Map.of(
                "database.configurationType", "jdbc",
                "database.jdbc.driverClass", "com.example.NoSuchDriver")));

        assertSame(failure, WebloggerStartup.getDatabaseProviderException());
        assertTrue(failure.getMessage().startsWith("ERROR: cannot load JDBC driver class"),
                failure.getMessage());
        assertFalse(WebloggerStartup.isPrepared());
    }

    @Test
    void autoInstallOnAnEmptyDatabaseWaitsForTheWizardToCreateTheSchema() throws Exception {
        String url = throwawayDatabaseUrl();
        int migrations = MigrationCatalog.versions().size();

        prepareWith(jdbcTo(url, "auto"));

        assertFalse(WebloggerStartup.isPrepared(), "auto mode never migrates on its own");
        assertEquals(url, WebloggerStartup.getDatabaseProvider().getJdbcConnectionURL());
        assertTrue(WebloggerStartup.isDatabaseCreationRequired());
        assertTrue(WebloggerStartup.isDatabaseUpgradeRequired());

        List<String> created = WebloggerStartup.createDatabase();

        assertTrue(WebloggerStartup.isPrepared(), "a successful creation prepares the application");
        assertEquals("Applied " + migrations + " migration(s)", created.getLast());
        assertEquals(MigrationCatalog.versions(), recordedVersions(url));
        assertFalse(WebloggerStartup.isDatabaseCreationRequired());
        assertFalse(WebloggerStartup.isDatabaseUpgradeRequired());
        assertEquals(List.of("Database is up to date (" + migrations + " migrations applied)"),
                WebloggerStartup.upgradeDatabase(false));
    }

    @Test
    void autoInstallOnAnUpToDateDatabaseIsPreparedAtOnce() throws Exception {
        String url = throwawayDatabaseUrl();
        new DatabaseInstaller(connectingTo(url), new ClasspathDatabaseScriptProvider()).createDatabase();

        prepareWith(jdbcTo(url, "auto"));

        assertTrue(WebloggerStartup.isPrepared());
    }

    @Test
    void manualInstallAppliesPendingMigrationsItselfAndIsPrepared() throws Exception {
        String url = throwawayDatabaseUrl();

        prepareWith(jdbcTo(url, "manual"));

        assertTrue(WebloggerStartup.isPrepared());
        assertEquals(MigrationCatalog.versions(), recordedVersions(url));
    }

    @Test
    void aFailedCreationCarriesTheInstallersLogAndLeavesTheApplicationUnprepared() throws Exception {
        setField("dbProvider", unreachable());

        StartupException failure = assertThrows(StartupException.class, WebloggerStartup::createDatabase);

        assertEquals("Error applying database migrations", failure.getMessage());
        assertInstanceOf(StartupException.class, failure.getCause());
        assertEquals(List.of("ERROR connecting to database to apply migrations"), failure.getStartupLog());
        assertFalse(WebloggerStartup.isPrepared());
    }

    @Test
    void aFailedUpgradeCarriesTheInstallersLogAndLeavesTheApplicationUnprepared() throws Exception {
        setField("dbProvider", unreachable());

        StartupException failure = assertThrows(StartupException.class,
                () -> WebloggerStartup.upgradeDatabase(true));

        assertEquals("Error applying database migrations", failure.getMessage());
        assertEquals(List.of("ERROR connecting to database to apply migrations"), failure.getStartupLog());
        assertFalse(WebloggerStartup.isPrepared());
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, String> jdbcTo(String url, String installationType) {
        return Map.of(
                "installation.type", installationType,
                "database.configurationType", "jdbc",
                "database.jdbc.driverClass", "org.postgresql.Driver",
                "database.jdbc.connectionURL", url,
                "database.jdbc.username", PressroomPostgresContainer.getUsername(),
                "database.jdbc.password", PressroomPostgresContainer.getPassword(),
                // an unbindable mail session: prepare() must carry on without one
                "mail.jndi.name", "mail/NoSuchSession");
    }

    private static void prepareWith(Map<String, String> settings) throws StartupException {
        Map<String, String> config = new HashMap<>(settings);
        try (MockedStatic<WebloggerConfig> mocked = mockStatic(WebloggerConfig.class)) {
            mocked.when(() -> WebloggerConfig.getProperty(anyString()))
                    .thenAnswer(invocation -> config.get(invocation.<String>getArgument(0)));
            WebloggerStartup.prepare();
        }
    }

    private static DatabaseProvider unreachable() throws SQLException {
        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(provider.getConnection()).thenThrow(new SQLException("connection refused"));
        return provider;
    }

    private static DatabaseProvider connectingTo(String url) throws SQLException {
        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(provider.getConnection()).thenAnswer(invocation -> connect(url));
        return provider;
    }

    private String throwawayDatabaseUrl() throws SQLException {
        String name = "startup_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        throwawayDatabases.add(name);
        String url = PressroomPostgresContainer.getJdbcUrl();
        int dbStart = url.lastIndexOf('/') + 1;
        int queryStart = url.indexOf('?', dbStart);
        String tail = queryStart < 0 ? "" : url.substring(queryStart);
        return url.substring(0, dbStart) + name + tail;
    }

    private static List<String> recordedVersions(String url) throws SQLException {
        List<String> versions = new ArrayList<>();
        try (Connection con = connect(url);
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM schema_migrations ORDER BY version")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }
        return versions;
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url,
                PressroomPostgresContainer.getUsername(), PressroomPostgresContainer.getPassword());
    }

    private static Connection adminConnection() throws SQLException {
        return connect(PressroomPostgresContainer.getJdbcUrl());
    }

    private static void setField(String name, Object value) {
        try {
            field(name).set(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not set WebloggerStartup." + name, e);
        }
    }

    private static Object getField(String name) {
        try {
            return field(name).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read WebloggerStartup." + name, e);
        }
    }

    private static Field field(String name) {
        try {
            Field field = WebloggerStartup.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("WebloggerStartup no longer has a '" + name + "' field", e);
        }
    }
}
