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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates {@code deploy/analytics/umami-views.sql} -- the Umami-side half
 * of the analytics contract (see {@code V017__analytics_contract.sql} for the
 * rollerdb half). That file is never exercised by {@link SchemaMigrationTest}
 * because it does not live in {@code bin/db/migrations}: it is applied to a
 * separate {@code umami} database by the one-shot {@code analytics-views}
 * compose service ({@code deploy/analytics-views.sh}, baked into the app
 * image), not by {@code migrate.sh} (PostgreSQL has no cross-database
 * queries). Runs after Umami's own first boot, on purpose -- see
 * {@code deploy/analytics-views.sh}'s header for why it cannot run from
 * {@code provision.sh} alongside the rest of provisioning.
 *
 * <p>This test builds a scratch database on the shared test container,
 * mirrors the slice of Umami v2's {@code website_event} table the view reads
 * (its own {@code CREATE TABLE}, kept minimal and deliberately not shared
 * with production code -- the repo's only other knowledge of Umami's schema
 * is the view file itself), applies the real file unmodified, and checks:
 * the view is created, re-applying it is a no-op (same idempotency contract
 * as {@code SchemaMigrationTest#everyMigrationIsIdempotent}), and it answers
 * a query correctly.
 *
 * <p>The view file's {@code GRANT ... TO grafana_ro} statements reference a
 * cluster-global role. This test creates that role itself with the same
 * idempotent guard V017 uses, rather than relying on some other test class
 * (e.g. {@link SchemaMigrationTest}, which applies V017 to its own scratch
 * databases) having already created it on this shared container -- test
 * order across classes is not guaranteed.
 */
public class UmamiViewScriptTest {

    /** Relative to the app module, which is the working directory for tests. */
    private static final Path VIEW_SCRIPT = Paths.get("../deploy/analytics/umami-views.sql");

    @Test
    public void viewCreatesReappliesAndAnswersAQuery() throws Exception {
        String dbName = "umami_view_test";

        try (Connection admin = adminConnection();
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName);
            st.execute("CREATE DATABASE " + dbName);
            // Cluster-global; guarded exactly like V017 so this test does not
            // depend on another test class having created the role first.
            st.execute("""
                    DO $$ BEGIN
                        CREATE ROLE grafana_ro NOLOGIN;
                    EXCEPTION WHEN duplicate_object THEN
                        NULL;
                    END $$;
                    """);
        }

        String viewSql = readViewScript();

        try (Connection con = DriverManager.getConnection(
                jdbcUrlFor(dbName), PressroomPostgresContainer.getUsername(), PressroomPostgresContainer.getPassword())) {

            // Umami v2's postgresql schema, minimal shape: only the columns
            // analytics_traffic reads.
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

            try (Statement st = con.createStatement()) {
                st.execute(viewSql);
            } catch (Exception e) {
                fail("Applying umami-views.sql to a fresh database failed: " + e.getMessage());
            }

            assertTrue(viewExists(con), "analytics_traffic was not created");

            try (Statement st = con.createStatement()) {
                st.execute(viewSql);
            } catch (Exception e) {
                fail("Re-applying umami-views.sql failed, so it is not idempotent: " + e.getMessage());
            }

            assertTrue(viewExists(con), "analytics_traffic did not survive re-application");

            UUID websiteId = UUID.randomUUID();
            UUID sharedSessionId = UUID.randomUUID();
            try (var ps = con.prepareStatement("""
                    INSERT INTO website_event (website_id, session_id, created_at, url_path, event_type)
                    VALUES (?, ?, now(), ?, ?)
                    """)) {
                // Two pageviews (event_type = 1) in the SAME session on the same
                // path, plus one non-pageview event in a different session, all on
                // one day. Reusing sharedSessionId across the two pageview rows is
                // what makes `sessions` (count(DISTINCT session_id) = 2) actually
                // differ from `views` (count(*) FILTER (event_type = 1) = 2) here
                // -- distinct rows but the same set of session ids -- so a
                // regression from DISTINCT to a plain count(*) would fail this
                // assertion instead of passing by coincidence.
                ps.setObject(1, websiteId);
                ps.setObject(2, sharedSessionId);
                ps.setString(3, "/weblog/handle/entry/my-post");
                ps.setInt(4, 1);
                ps.addBatch();

                ps.setObject(1, websiteId);
                ps.setObject(2, sharedSessionId);
                ps.setString(3, "/weblog/handle/entry/my-post");
                ps.setInt(4, 1);
                ps.addBatch();

                ps.setObject(1, websiteId);
                ps.setObject(2, UUID.randomUUID());
                ps.setString(3, "/weblog/handle/entry/my-post");
                ps.setInt(4, 2);
                ps.addBatch();

                ps.executeBatch();
            }

            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT path, entry_anchor, sessions, views
                         FROM analytics_traffic
                         WHERE website_id = '""" + websiteId + "'")) {
                assertTrue(rs.next(), "analytics_traffic returned no rows for the seeded event");
                assertEquals("/weblog/handle/entry/my-post", rs.getString("path"));
                assertEquals("my-post", rs.getString("entry_anchor"));
                assertEquals(2, rs.getInt("sessions"),
                        "sessions must be DISTINCT session_id (2 sessions across 3 rows), not row count");
                assertEquals(2, rs.getInt("views"), "only event_type = 1 rows count as views");
            }
        } finally {
            try (Connection admin = adminConnection();
                 Statement st = admin.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + dbName);
            }
        }
    }

    private String readViewScript() throws Exception {
        if (!Files.isRegularFile(VIEW_SCRIPT)) {
            fail("View script not found at " + VIEW_SCRIPT.toAbsolutePath()
                    + ". Tests expect to run with the app module as the working directory.");
        }
        return Files.readString(VIEW_SCRIPT, StandardCharsets.UTF_8);
    }

    private boolean viewExists(Connection con) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT to_regclass('public.analytics_traffic') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private Connection adminConnection() throws Exception {
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
