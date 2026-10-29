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

import com.wikantik.pressroom.testing.MigrationFiles;
import com.wikantik.pressroom.testing.PressroomPostgresContainer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * {@link SchemaMigrationTest} proves the chain through psql-style whole-string
 * execution. The install wizard parses the SAME files through
 * {@link SQLScriptRunner}'s own splitter -- a third applier with its own
 * grammar, previously untested against the real chain. V017's DO-block role
 * guard is exactly the construct the old splitter corrupted.
 */
class SqlScriptRunnerMigrationTest {

    @Test
    void everyMigrationParsesAndAppliesThroughTheInstallWizardsSplitter() throws Exception {
        try (Connection con = freshDatabase("sqlscriptrunner")) {
            for (Path migration : MigrationFiles.all()) {
                apply(con, migration);

                // README.md rule 1: re-applying a migration must be a no-op,
                // through this applier too. Twice in a row, at its own place
                // in the chain (see SchemaMigrationTest.everyMigrationIsIdempotent
                // for why not the whole chain again: V041 renames tables).
                apply(con, migration);
            }
        }
    }

    /**
     * Applies one migration through {@link SQLScriptRunner}, matching the
     * exact call shape {@code DatabaseInstaller.applyMigration} uses (see
     * DatabaseInstaller.java:189-199): a fresh runner per migration, built
     * from the migration's bytes, run with {@code failonerror=true} so any
     * parsing/execution error throws instead of being swallowed.
     */
    private void apply(Connection con, Path migration) throws Exception {
        String sql = readMigration(migration);
        SQLScriptRunner runner = new SQLScriptRunner(
                new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
        runner.runScript(con, true);
    }

    private String readMigration(Path migration) throws Exception {
        return Files.readString(migration, StandardCharsets.UTF_8)
                .replace(":app_user", PressroomPostgresContainer.getUsername());
    }

    /** Applies the full chain to a brand-new database and returns a connection to it. */
    private Connection freshDatabase(String suffix) throws Exception {
        String dbName = "migrationtest_" + suffix;
        try (Connection admin = adminConnection();
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName);
            st.execute("CREATE DATABASE " + dbName);
        }

        return DriverManager.getConnection(
                jdbcUrlFor(dbName),
                PressroomPostgresContainer.getUsername(),
                PressroomPostgresContainer.getPassword());
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                PressroomPostgresContainer.getJdbcUrl(),
                PressroomPostgresContainer.getUsername(),
                PressroomPostgresContainer.getPassword());
    }

    /** Rewrites the container's JDBC URL to point at a different database. */
    private String jdbcUrlFor(String dbName) {
        String url = PressroomPostgresContainer.getJdbcUrl();
        int dbStart = url.lastIndexOf('/') + 1;
        int queryStart = url.indexOf('?', dbStart);
        String tail = queryStart < 0 ? "" : url.substring(queryStart);
        return url.substring(0, dbStart) + dbName + tail;
    }
}
