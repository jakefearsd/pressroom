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
package org.apache.roller.weblogger.business.startup;

import org.apache.roller.testing.MigrationFiles;
import org.apache.roller.testing.RollerPostgresContainer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the schema migration system.
 *
 * <p>Roller has exactly one source of schema truth -- the {@code V*.sql} files
 * in {@code bin/db/migrations} -- reached three ways: {@code migrate.sh} at
 * deploy time, {@link DatabaseInstaller} through the web install wizard, and
 * the test harness. These tests check the properties all three depend on:
 * that the files are discoverable from the classpath the WAR ships, that the
 * chain builds the schema the JPA mappings expect, and that re-applying a
 * migration is a no-op (the rule stated in bin/db/migrations/README.md).
 */
public class SchemaMigrationTest {

    /**
     * Every table the ORM mappings expect. If a migration adds an entity table
     * without the mapping (or vice versa) the mismatch surfaces here rather
     * than as a runtime failure on a page nobody visits until production.
     */
    private static final List<String> EXPECTED_TABLES = List.of(
            "custom_template_rendition", "entryattribute", "newsfeed",
            "roller_audit_log",
            "roller_mediafile", "roller_mediafiledir", "roller_mediafiletag",
            "roller_permission", "roller_properties",
            "roller_tasklock",
            "roller_user", "roller_weblogentrytag", "roller_weblogentrytagagg",
            "userrole", "weblog", "weblog_custom_template", "weblogcategory",
            "weblogentry", "weblogentry_revision");

    /**
     * Tables belonging to features this fork has removed since diverging
     * from upstream. The baseline must not recreate them -- that was the
     * whole point of dropping the old 610-to-620 upgrade script in favour
     * of a clean baseline.
     */
    private static final List<String> REMOVED_FEATURE_TABLES = List.of(
            "rag_entry", "rag_group", "rag_group_subscription", "rag_planet",
            "rag_properties", "rag_subscription", "bookmark", "bookmark_folder",
            "autoping", "pingtarget", "pingqueueentry",
            // V017: hitcount subsystem replaced by Umami traffic counting.
            "roller_hitcounts",
            // V020: share-link feature removed entirely, not coming back.
            "roller_share_link",
            // V022: comment subsystem removed entirely (W1), not coming back.
            "roller_comment");

    @Test
    public void migrationsAreDiscoverableOnTheClasspath() {
        List<String> versions = MigrationCatalog.versions();

        assertFalse(versions.isEmpty(), "MigrationCatalog found no migrations");
        assertEquals("V001__schema_migrations", versions.get(0),
                "V001 must sort first -- it creates the tracking table the rest depend on");

        // The build copies bin/db/migrations to /dbmigrations. If that copy step
        // is dropped, the WAR ships with no schema and the installer silently
        // has nothing to apply.
        ClasspathDatabaseScriptProvider provider = new ClasspathDatabaseScriptProvider();
        for (String version : versions) {
            assertTrue(provider.getScriptURL(version + ".sql") != null,
                    version + " is listed by MigrationCatalog but not readable from the "
                            + "classpath; check the copy-db-migrations step in app/pom.xml");
        }
    }

    @Test
    public void getDatabaseScriptOpensTheSameMigrationsFromTheClasspath() throws Exception {
        // The applier-facing method (migrate.sh/DatabaseInstaller/the test
        // harness all read migrations through this), as opposed to
        // getScriptURL above, which exists only for logging which file
        // resolved.
        List<String> versions = MigrationCatalog.versions();
        assertFalse(versions.isEmpty(), "MigrationCatalog found no migrations");

        ClasspathDatabaseScriptProvider provider = new ClasspathDatabaseScriptProvider();
        String first = versions.get(0) + ".sql";
        try (InputStream in = provider.getDatabaseScript(first)) {
            assertTrue(in != null, first + " must be readable via getDatabaseScript, "
                    + "not just getScriptURL");
            byte[] content = in.readAllBytes();
            assertTrue(content.length > 0, first + " resolved to an empty stream");
        }

        assertTrue(provider.getDatabaseScript("V999999__does_not_exist.sql") == null,
                "an unknown migration name must resolve to no stream, not throw");
    }

    @Test
    public void classpathCopyMatchesTheCanonicalMigrations() throws Exception {
        List<String> onClasspath = MigrationCatalog.versions();
        List<String> canonical = new ArrayList<>();
        for (Path migration : MigrationFiles.all()) {
            canonical.add(migration.getFileName().toString().replace(".sql", ""));
        }

        assertEquals(canonical, onClasspath,
                "The migrations copied onto the classpath differ from those in "
                        + MigrationFiles.directory().toAbsolutePath()
                        + ". The build copy step is stale.");
    }

    @Test
    public void chainBuildsTheSchemaTheOrmExpects() throws Exception {
        String dbName = "migrationtest_schema_shape";
        try (Connection con = freshDatabase(dbName)) {
            List<String> tables = tableNames(con);

            for (String expected : EXPECTED_TABLES) {
                assertTrue(tables.contains(expected),
                        "Migration chain did not create table '" + expected + "'. Present: "
                                + tables);
            }
            assertTrue(tables.contains("schema_migrations"),
                    "V001 did not create the tracking table");

            for (String removed : REMOVED_FEATURE_TABLES) {
                assertFalse(tables.contains(removed),
                        "Table '" + removed + "' belongs to a feature this fork removed and "
                                + "must not be in the baseline schema");
            }
        } finally {
            dropDatabase(dbName);
        }
    }

    /**
     * README.md rule 1: applying a migration twice must be a no-op. Enforced
     * here so the rule is checked rather than merely documented.
     */
    @Test
    public void everyMigrationIsIdempotent() throws Exception {
        String dbName = "migrationtest_idempotency";
        try (Connection con = freshDatabase(dbName)) {
            List<String> before = tableNames(con);

            for (Path migration : MigrationFiles.all()) {
                String sql = readMigration(migration);
                try (Statement st = con.createStatement()) {
                    st.execute(sql);
                } catch (Exception e) {
                    fail("Re-applying " + migration.getFileName() + " failed, so it is not "
                            + "idempotent. See rule 1 in bin/db/migrations/README.md. Cause: "
                            + e.getMessage());
                }
            }

            assertEquals(before, tableNames(con),
                    "Re-applying the migration chain changed the set of tables");
        } finally {
            dropDatabase(dbName);
        }
    }

    /** Applies the full chain to a brand-new database (dropping any leftover of the same name first) and returns a connection to it. */
    private Connection freshDatabase(String dbName) throws Exception {
        try (Connection admin = adminConnection();
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName);
            st.execute("CREATE DATABASE " + dbName);
        }

        Connection con = DriverManager.getConnection(
                jdbcUrlFor(dbName),
                RollerPostgresContainer.getUsername(),
                RollerPostgresContainer.getPassword());
        for (Path migration : MigrationFiles.all()) {
            try (Statement st = con.createStatement()) {
                st.execute(readMigration(migration));
            }
        }
        return con;
    }

    /** Drops the scratch database. Callers must close their connection to it first. */
    private void dropDatabase(String dbName) throws Exception {
        try (Connection admin = adminConnection();
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName);
        }
    }

    private String readMigration(Path migration) throws Exception {
        return Files.readString(migration, StandardCharsets.UTF_8)
                .replace(":app_user", RollerPostgresContainer.getUsername());
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(),
                RollerPostgresContainer.getPassword());
    }

    /** Rewrites the container's JDBC URL to point at a different database. */
    private String jdbcUrlFor(String dbName) {
        String url = RollerPostgresContainer.getJdbcUrl();
        int dbStart = url.lastIndexOf('/') + 1;
        int queryStart = url.indexOf('?', dbStart);
        String tail = queryStart < 0 ? "" : url.substring(queryStart);
        return url.substring(0, dbStart) + dbName + tail;
    }

    private List<String> tableNames(Connection con) throws Exception {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename";
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }
}
