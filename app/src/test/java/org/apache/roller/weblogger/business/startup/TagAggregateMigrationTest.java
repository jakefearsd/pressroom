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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V034 repairs {@code roller_weblogentrytagagg}: duplicate site-wide rows are
 * merged, {@code weta_weblog_tag_uq} becomes {@code NULLS NOT DISTINCT}, and
 * every total is recomputed as the number of PUBLISHED entries carrying the
 * tag -- the same count {@code TagAggregateStore} maintains.
 */
public class TagAggregateMigrationTest {

    private static final String V034 = "V034";

    @Test
    void driftedAndDuplicatedAggregatesAreRebuiltFromThePublishedEntries() throws Exception {
        String db = "migrationtest_tag_agg";
        try (Connection con = databaseBefore(V034, db)) {
            seedDriftedData(con);

            applyMigration(con, V034);

            assertEquals(expectedTotals(), totals(con));
            assertEquals("2026-10-20 00:00:00+00",
                    scalarString(con, "SELECT to_char(lastused AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') || '+00' "
                            + "FROM roller_weblogentrytagagg WHERE websiteid IS NULL AND name = 'a'"),
                    "the most recently used of the duplicate site-wide rows survives");
            assertEquals("2026-10-01 00:00:00+00",
                    scalarString(con, "SELECT to_char(lastused AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') || '+00' "
                            + "FROM roller_weblogentrytagagg WHERE websiteid = 'w2' AND name = 'a'"),
                    "a missing row is added with its newest tag's time");
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void afterwardsTheDatabaseRefusesASecondSiteWideRow() throws Exception {
        String db = "migrationtest_tag_agg_unique";
        try (Connection con = databaseBefore(V034, db)) {
            applyMigration(con, V034);
            exec(con, "INSERT INTO roller_weblogentrytagagg (id, websiteid, name, total, lastused) "
                    + "VALUES ('s1', NULL, 'x', 1, now())");
            SQLException refused = assertThrows(SQLException.class, () -> exec(con,
                    "INSERT INTO roller_weblogentrytagagg (id, websiteid, name, total, lastused) "
                            + "VALUES ('s2', NULL, 'x', 1, now())"));
            assertEquals("23505", refused.getSQLState(), refused.getMessage());
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void applyingItAgainChangesNothing() throws Exception {
        String db = "migrationtest_tag_agg_twice";
        try (Connection con = databaseBefore(V034, db)) {
            seedDriftedData(con);
            applyMigration(con, V034);
            Map<String, String> rowsAfterFirst = rowsWithIds(con);

            applyMigration(con, V034);

            assertEquals(rowsAfterFirst, rowsWithIds(con));
            assertEquals(1, scalarInt(con, "SELECT count(*) FROM pg_constraint "
                    + "WHERE conname = 'weta_weblog_tag_uq'"));
        } finally {
            dropDatabase(db);
        }
    }

    // ------------------------------------------------------------- fixture

    /**
     * Two weblogs. Published: e1 (w1: a, b), e2 (w1: a), e4 (w2: a).
     * Not counted: e3 DRAFT (w1: a), e5 TRASHED (w2: b), e6 SCHEDULED (w2: c).
     * The aggregates as stored disagree with that in every way the race and
     * the old bookkeeping could leave them.
     */
    private void seedDriftedData(Connection con) throws SQLException {
        exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) VALUES "
                + "('w1', 'One', 'one', 'a@example.com', now()), "
                + "('w2', 'Two', 'two', 'a@example.com', now())");
        exec(con, "INSERT INTO weblogcategory (id, name, websiteid) VALUES "
                + "('c1', 'General', 'w1'), ('c2', 'General', 'w2')");
        exec(con, "INSERT INTO weblogentry (id, anchor, creator, title, text, updatetime, websiteid, "
                + "categoryid, status) VALUES "
                + "('e1', 'e1', 'u', 't', 'x', now(), 'w1', 'c1', 'PUBLISHED'), "
                + "('e2', 'e2', 'u', 't', 'x', now(), 'w1', 'c1', 'PUBLISHED'), "
                + "('e3', 'e3', 'u', 't', 'x', now(), 'w1', 'c1', 'DRAFT'), "
                + "('e4', 'e4', 'u', 't', 'x', now(), 'w2', 'c2', 'PUBLISHED'), "
                + "('e5', 'e5', 'u', 't', 'x', now(), 'w2', 'c2', 'TRASHED'), "
                + "('e6', 'e6', 'u', 't', 'x', now(), 'w2', 'c2', 'SCHEDULED')");
        exec(con, "INSERT INTO roller_weblogentrytag (id, entryid, websiteid, creator, name, time) VALUES "
                + "('t1', 'e1', 'w1', 'u', 'a', '2026-09-01T00:00:00Z'), "
                + "('t2', 'e1', 'w1', 'u', 'b', '2026-09-01T00:00:00Z'), "
                + "('t3', 'e2', 'w1', 'u', 'a', '2026-09-02T00:00:00Z'), "
                + "('t4', 'e3', 'w1', 'u', 'a', '2026-09-03T00:00:00Z'), "
                + "('t5', 'e4', 'w2', 'u', 'a', '2026-10-01T00:00:00Z'), "
                + "('t6', 'e5', 'w2', 'u', 'b', '2026-09-05T00:00:00Z'), "
                + "('t7', 'e6', 'w2', 'u', 'c', '2026-09-06T00:00:00Z')");
        exec(con, "INSERT INTO roller_weblogentrytagagg (id, websiteid, name, total, lastused) VALUES "
                // a lost increment
                + "('g1', 'w1', 'a', 1, '2026-10-10T00:00:00Z'), "
                // already right
                + "('g2', 'w1', 'b', 1, '2026-10-10T00:00:00Z'), "
                // counts a trashed and a scheduled entry
                + "('g3', 'w2', 'b', 1, '2026-10-10T00:00:00Z'), "
                + "('g4', 'w2', 'c', 2, '2026-10-10T00:00:00Z'), "
                // (w2, a) is missing; two site-wide rows for a, both wrong
                + "('g5', NULL, 'a', 2, '2026-10-01T00:00:00Z'), "
                + "('g6', NULL, 'a', 1, '2026-10-20T00:00:00Z'), "
                // a zero row, and a tag no entry carries any more
                + "('g7', NULL, 'b', 0, '2026-10-10T00:00:00Z'), "
                + "('g8', NULL, 'gone', 5, '2026-10-10T00:00:00Z')");
    }

    private static Map<String, Integer> expectedTotals() {
        Map<String, Integer> expected = new TreeMap<>();
        expected.put("w1/a", 2);
        expected.put("w1/b", 1);
        expected.put("w2/a", 1);
        expected.put("site/a", 3);
        expected.put("site/b", 1);
        return expected;
    }

    /** Every row as "weblog/tag" to total; a duplicate key fails the test. */
    private static Map<String, Integer> totals(Connection con) throws SQLException {
        Map<String, Integer> totals = new TreeMap<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT coalesce(websiteid, 'site') || '/' || name, total "
                     + "FROM roller_weblogentrytagagg")) {
            while (rs.next()) {
                Integer previous = totals.put(rs.getString(1), rs.getInt(2));
                assertEquals(null, previous, "two rows for " + rs.getString(1));
            }
        }
        return totals;
    }

    private static Map<String, String> rowsWithIds(Connection con) throws SQLException {
        Map<String, String> rows = new TreeMap<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, coalesce(websiteid, 'site') || '/' || name "
                     + "|| '=' || total || '@' || lastused FROM roller_weblogentrytagagg")) {
            while (rs.next()) {
                rows.put(rs.getString(1), rs.getString(2));
            }
        }
        return rows;
    }

    // ------------------------------------------------------------- plumbing

    private static String scalarString(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static int scalarInt(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static void exec(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    private static void applyMigration(Connection con, String version) throws Exception {
        for (Path migration : MigrationFiles.all()) {
            if (migration.getFileName().toString().startsWith(version)) {
                exec(con, Files.readString(migration, StandardCharsets.UTF_8));
            }
        }
    }

    /** A fresh database with every migration before {@code version} applied. */
    private static Connection databaseBefore(String version, String dbName) throws Exception {
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
            if (migration.getFileName().toString().compareTo(version) >= 0) {
                break;
            }
            exec(con, Files.readString(migration, StandardCharsets.UTF_8)
                    .replace(":app_user", RollerPostgresContainer.getUsername()));
        }
        return con;
    }

    private static void dropDatabase(String dbName) throws Exception {
        try (Connection admin = adminConnection();
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName);
        }
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(),
                RollerPostgresContainer.getPassword());
    }

    private static String jdbcUrlFor(String dbName) {
        String url = RollerPostgresContainer.getJdbcUrl();
        int dbStart = url.lastIndexOf('/') + 1;
        int queryStart = url.indexOf('?', dbStart);
        String tail = queryStart < 0 ? "" : url.substring(queryStart);
        return url.substring(0, dbStart) + dbName + tail;
    }
}
