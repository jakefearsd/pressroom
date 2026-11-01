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
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Scratch stand-in for Umami's database on the shared test container: a
 * minimal {@code website_event} (only the columns {@code analytics_traffic}
 * reads), the real {@code deploy/analytics/umami-views.sql} applied
 * unmodified, and helpers to insert events and drop the database.
 *
 * <p>Extracted from {@code UmamiViewScriptTest} as a characterisation
 * refactor: that test's assertions are unchanged and passed before and after
 * the move, so this class changes no behaviour. Callers pick a distinct
 * database name each so test classes cannot collide.
 */
public final class UmamiTestDatabase {

    /** Relative to the app module, which is the working directory for tests. */
    private static final Path VIEW_SCRIPT = Paths.get("../deploy/analytics/umami-views.sql");

    private UmamiTestDatabase() {
    }

    /** Drops and recreates the database and the cluster-global grafana_ro role guard. */
    public static void recreate(String dbName) throws Exception {
        try (Connection admin = adminConnection();
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName);
            st.execute("CREATE DATABASE " + dbName);
            // Cluster-global; guarded exactly like V017 so this does not
            // depend on another test class having created the role first.
            st.execute("""
                    DO $$ BEGIN
                        CREATE ROLE grafana_ro NOLOGIN;
                    EXCEPTION WHEN duplicate_object THEN
                        NULL;
                    END $$;
                    """);
        }
    }

    /** Creates the minimal Umami v2 {@code website_event} table. */
    public static void createEventTable(Connection con) throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("""
                    CREATE TABLE website_event (
                        website_id uuid,
                        session_id uuid,
                        created_at timestamptz,
                        url_path varchar,
                        event_type int
                    )
                    """);
        }
    }

    /** Recreates the database with the event table and the real view applied. */
    public static Connection create(String dbName) throws Exception {
        recreate(dbName);
        Connection con = connect(dbName);
        createEventTable(con);
        try (Statement st = con.createStatement()) {
            st.execute(readViewScript());
        }
        return con;
    }

    /** Inserts one pageview (event_type 1) at the given instant. */
    public static void insertPageview(Connection con, UUID websiteId, UUID sessionId,
                                      Instant at, String path) throws Exception {
        insertEvent(con, websiteId, sessionId, at, path, 1);
    }

    public static void insertEvent(Connection con, UUID websiteId, UUID sessionId,
                                   Instant at, String path, int eventType) throws Exception {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO website_event (website_id, session_id, created_at, url_path, event_type)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            ps.setObject(1, websiteId);
            ps.setObject(2, sessionId);
            ps.setTimestamp(3, Timestamp.from(at));
            ps.setString(4, path);
            ps.setInt(5, eventType);
            ps.executeUpdate();
        }
    }

    public static void drop(String dbName) throws Exception {
        try (Connection admin = adminConnection();
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName);
        }
    }

    public static String readViewScript() throws Exception {
        if (!Files.isRegularFile(VIEW_SCRIPT)) {
            fail("View script not found at " + VIEW_SCRIPT.toAbsolutePath()
                    + ". Tests expect to run with the app module as the working directory.");
        }
        return Files.readString(VIEW_SCRIPT, StandardCharsets.UTF_8);
    }

    public static boolean viewExists(Connection con) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT to_regclass('public.analytics_traffic') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    public static Connection connect(String dbName) throws Exception {
        return DriverManager.getConnection(
                jdbcUrlFor(dbName), PressroomPostgresContainer.getUsername(), PressroomPostgresContainer.getPassword());
    }

    public static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                PressroomPostgresContainer.getJdbcUrl(),
                PressroomPostgresContainer.getUsername(),
                PressroomPostgresContainer.getPassword());
    }

    /** Rewrites the container's JDBC URL to point at a different database. */
    public static String jdbcUrlFor(String dbName) {
        String url = PressroomPostgresContainer.getJdbcUrl();
        int dbStart = url.lastIndexOf('/') + 1;
        int queryStart = url.indexOf('?', dbStart);
        String tail = queryStart < 0 ? "" : url.substring(queryStart);
        return url.substring(0, dbStart) + dbName + tail;
    }
}
