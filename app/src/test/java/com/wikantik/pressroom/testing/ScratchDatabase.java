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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Throwaway databases in the suite's PostgreSQL container, for tests that
 * must not share the business tier's database (an empty schema, a
 * migration-only schema, a count that other test classes could disturb).
 * Extracted from DevSeedTest and AnalyticsContractTest, which carried
 * identical private copies; their tests passing unchanged is the
 * characterisation that the move altered nothing.
 */
public final class ScratchDatabase {

    private ScratchDatabase() {
    }

    /**
     * Drops and recreates {@code name}, applies the full migration chain, and
     * returns an open connection to it. The caller closes it, then calls
     * {@link #drop(String)}.
     */
    public static Connection migrated(String name) throws Exception {
        empty(name);
        Connection con = DriverManager.getConnection(jdbcUrl(name),
                PressroomPostgresContainer.getUsername(), PressroomPostgresContainer.getPassword());
        for (Path migration : MigrationFiles.all()) {
            try (Statement st = con.createStatement()) {
                st.execute(Files.readString(migration, StandardCharsets.UTF_8)
                        .replace(":app_user", PressroomPostgresContainer.getUsername()));
            }
        }
        return con;
    }

    /** Drops and recreates {@code name} with no schema at all. */
    public static void empty(String name) throws Exception {
        try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name);
            st.execute("CREATE DATABASE " + name);
        }
    }

    /** Drops {@code name}. Callers must close their connections to it first. */
    public static void drop(String name) throws Exception {
        try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name);
        }
    }

    /** Rewrites the container's JDBC URL to point at a different database. */
    public static String jdbcUrl(String name) {
        String url = PressroomPostgresContainer.getJdbcUrl();
        int dbStart = url.lastIndexOf('/') + 1;
        int queryStart = url.indexOf('?', dbStart);
        String tail = queryStart < 0 ? "" : url.substring(queryStart);
        return url.substring(0, dbStart) + name + tail;
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(PressroomPostgresContainer.getJdbcUrl(),
                PressroomPostgresContainer.getUsername(), PressroomPostgresContainer.getPassword());
    }
}
