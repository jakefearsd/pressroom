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
package com.wikantik.pressroom.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Starts the shared PostgreSQL container and builds the schema that every
 * test runs against.
 *
 * <p>The entry point is the static {@link #ensureSchema()}: once per JVM it
 * starts the container, points Roller's configuration at it, and builds the
 * schema by applying the real {@code bin/db/migrations} chain.
 * {@code PressroomTestBootstrap} and {@code TestUtils} call it directly. Tests
 * tear down their own fixtures rather than relying on any per-test reset.
 */
public class PressroomDatabaseExtension {

    private static volatile boolean schemaReady;

    private PressroomDatabaseExtension() {
        // static utility
    }

    /**
     * Start the container, publish its coordinates to Roller's config, and
     * apply the migration chain. Idempotent and safe under concurrent access.
     */
    public static void ensureSchema() {
        if (schemaReady) {
            return;
        }
        synchronized (PressroomDatabaseExtension.class) {
            if (schemaReady) {
                return;
            }
            publishDatabaseConfig();
            applyMigrations();
            schemaReady = true;
        }
    }

    /**
     * Roller reads {@code pressroom.custom.config} last, so writing the
     * container's JDBC coordinates there overrides the checked-in test
     * properties without them needing to know the randomly-assigned port.
     */
    private static void publishDatabaseConfig() {
        try {
            Path overrides = Files.createTempFile("roller-testdb", ".properties");
            overrides.toFile().deleteOnExit();
            String contents = """
                    database.configurationType=jdbc
                    database.jdbc.driverClass=org.postgresql.Driver
                    database.jdbc.connectionURL=%s
                    database.jdbc.username=%s
                    database.jdbc.password=%s
                    """.formatted(
                    PressroomPostgresContainer.getJdbcUrl(),
                    PressroomPostgresContainer.getUsername(),
                    PressroomPostgresContainer.getPassword());
            Files.writeString(overrides, contents, StandardCharsets.UTF_8);

            // WebloggerConfig loads this in a static initializer, so it must be
            // set before anything touches that class.
            System.setProperty("pressroom.custom.config", overrides.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not publish test database config", e);
        }
    }

    /**
     * Builds the schema from {@code bin/db/migrations} — the same files
     * {@code migrate.sh} and the install wizard use. There is deliberately no
     * separate test schema to drift.
     */
    private static void applyMigrations() {
        List<Path> migrations = MigrationFiles.all();
        try (Connection con = newConnection()) {
            con.setAutoCommit(false);
            for (Path migration : migrations) {
                String sql = Files.readString(migration, StandardCharsets.UTF_8)
                        .replace(":app_user", PressroomPostgresContainer.getUsername());
                try (Statement st = con.createStatement()) {
                    st.execute(sql);
                }
            }
            recordApplied(con, migrations);
            con.commit();
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Could not apply migrations to the test database", e);
        }
    }

    private static void recordApplied(Connection con, List<Path> migrations) throws SQLException {
        for (Path migration : migrations) {
            String version = migration.getFileName().toString().replace(".sql", "");
            try (var ps = con.prepareStatement(
                    "INSERT INTO schema_migrations (version) VALUES (?) ON CONFLICT DO NOTHING")) {
                ps.setString(1, version);
                ps.executeUpdate();
            }
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                PressroomPostgresContainer.getJdbcUrl(),
                PressroomPostgresContainer.getUsername(),
                PressroomPostgresContainer.getPassword());
    }
}
