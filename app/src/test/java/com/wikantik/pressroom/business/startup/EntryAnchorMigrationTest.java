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
 * V035 makes an entry's anchor unique within its weblog: duplicates left by
 * the create race are resolved, then {@code we_website_anchor_uq} is added.
 *
 * <p>Which duplicate keeps the anchor is chosen for readers: the
 * permalink lookup ({@code getSingleResult}) threw {@code
 * NonUniqueResultException} on a duplicate, so no duplicate was reachable,
 * and the URL should now serve the PUBLISHED one. Then the newest pubtime
 * (NULL last), then the lower id. Every other duplicate takes the first free
 * {@code <anchor>N}, N = 1, 2, ..., the suffix scheme {@code createAnchor}
 * uses.
 */
public class EntryAnchorMigrationTest {

    private static final String V035 = "V035";

    /** The longest anchor the column holds (varchar(255)). */
    private static final String LONG = "l".repeat(255);

    @Test
    void thePublishedThenNewestDuplicateKeepsTheAnchorAndTheOthersTakeFreeSuffixes() throws Exception {
        String db = "migrationtest_anchor_dups";
        try (Connection con = databaseBefore(V035, db)) {
            seedDuplicates(con);

            applyMigration(con, V035);

            assertEquals(expectedAnchors(), anchors(con));
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void afterwardsTheDatabaseRefusesASecondEntryWithAnAnchorInOneWeblog() throws Exception {
        String db = "migrationtest_anchor_unique";
        try (Connection con = databaseBefore(V035, db)) {
            applyMigration(con, V035);
            exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) VALUES "
                    + "('w1', 'One', 'one', 'a@example.com', now()), ('w2', 'Two', 'two', 'a@example.com', now())");
            exec(con, "INSERT INTO weblogcategory (id, name, websiteid) VALUES "
                    + "('c1', 'General', 'w1'), ('c2', 'General', 'w2')");
            exec(con, entry("a", "w1", "c1", "x", "NULL"));
            exec(con, entry("b", "w2", "c2", "x", "NULL"));

            SQLException refused = assertThrows(SQLException.class, () -> exec(con, entry("c", "w1", "c1", "x", "NULL")));
            assertEquals("23505", refused.getSQLState(), refused.getMessage());
            assertTrue(refused.getMessage().contains("we_website_anchor_uq"), refused.getMessage());
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void applyingItAgainChangesNothing() throws Exception {
        String db = "migrationtest_anchor_twice";
        try (Connection con = databaseBefore(V035, db)) {
            seedDuplicates(con);
            applyMigration(con, V035);
            Map<String, String> afterFirst = anchors(con);

            applyMigration(con, V035);

            assertEquals(afterFirst, anchors(con));
            assertEquals(1, scalarInt(con, "SELECT count(*) FROM pg_indexes WHERE indexname = 'we_website_anchor_uq'"));
        } finally {
            dropDatabase(db);
        }
    }

    // ------------------------------------------------------------- fixture

    /**
     * Weblog w1: "dup" three times (pubtimes Feb, mid-Jan, Jan) while "dup1"
     * is already taken; "nul" twice, one with no pubtime (NULL); "pub" as one
     * published entry and two drafts, one of them newer and one never dated;
     * "tie" twice with the same pubtime; a 255-character anchor twice. Weblog
     * w2 holds "dup" once, which is no clash.
     */
    private void seedDuplicates(Connection con) throws SQLException {
        exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) VALUES "
                + "('w1', 'One', 'one', 'a@example.com', now()), ('w2', 'Two', 'two', 'a@example.com', now())");
        exec(con, "INSERT INTO weblogcategory (id, name, websiteid) VALUES "
                + "('c1', 'General', 'w1'), ('c2', 'General', 'w2')");
        exec(con, entry("dup-jan", "w1", "c1", "dup", "'2026-01-01T00:00:00Z'"));
        exec(con, entry("dup-feb", "w1", "c1", "dup", "'2026-02-01T00:00:00Z'"));
        exec(con, entry("dup-midjan", "w1", "c1", "dup", "'2026-01-15T00:00:00Z'"));
        exec(con, entry("dup-one", "w1", "c1", "dup1", "'2025-12-01T00:00:00Z'"));
        exec(con, entry("nul-set", "w1", "c1", "nul", "'2026-03-01T00:00:00Z'"));
        exec(con, entry("nul-unset", "w1", "c1", "nul", "NULL"));
        exec(con, entry("pub-draft", "w1", "c1", "pub", "NULL", "DRAFT"));
        exec(con, entry("pub-later-draft", "w1", "c1", "pub", "'2026-06-02T00:00:00Z'", "DRAFT"));
        exec(con, entry("pub-published", "w1", "c1", "pub", "'2026-06-01T00:00:00Z'"));
        exec(con, entry("tie-b", "w1", "c1", "tie", "'2026-04-01T00:00:00Z'"));
        exec(con, entry("tie-a", "w1", "c1", "tie", "'2026-04-01T00:00:00Z'"));
        exec(con, entry("long-new", "w1", "c1", LONG, "'2026-05-02T00:00:00Z'"));
        exec(con, entry("long-old", "w1", "c1", LONG, "'2026-05-01T00:00:00Z'"));
        exec(con, entry("other-blog", "w2", "c2", "dup", "'2026-01-01T00:00:00Z'"));
    }

    private static Map<String, String> expectedAnchors() {
        Map<String, String> expected = new TreeMap<>();
        expected.put("dup-feb", "dup");
        expected.put("dup-one", "dup1");
        expected.put("dup-midjan", "dup2");
        expected.put("dup-jan", "dup3");
        expected.put("nul-set", "nul");
        expected.put("nul-unset", "nul1");
        expected.put("pub-published", "pub");
        expected.put("pub-later-draft", "pub1");
        expected.put("pub-draft", "pub2");
        expected.put("tie-a", "tie");
        expected.put("tie-b", "tie1");
        expected.put("long-new", LONG);
        expected.put("long-old", "l".repeat(254) + "1");
        expected.put("other-blog", "dup");
        return expected;
    }

    private static String entry(String id, String weblog, String category, String anchor, String pubtime) {
        return entry(id, weblog, category, anchor, pubtime, "PUBLISHED");
    }

    private static String entry(String id, String weblog, String category, String anchor, String pubtime,
                                String status) {
        return "INSERT INTO weblogentry (id, anchor, creator, title, text, updatetime, pubtime, websiteid, "
                + "categoryid, status) VALUES ('" + id + "', '" + anchor + "', 'u', 't', 'x', now(), " + pubtime
                + ", '" + weblog + "', '" + category + "', '" + status + "')";
    }

    /** Entry id to anchor. */
    private static Map<String, String> anchors(Connection con) throws SQLException {
        Map<String, String> anchors = new TreeMap<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, anchor FROM weblogentry")) {
            while (rs.next()) {
                anchors.put(rs.getString(1), rs.getString(2));
            }
        }
        return anchors;
    }

    // ------------------------------------------------------------- plumbing

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
                PressroomPostgresContainer.getUsername(),
                PressroomPostgresContainer.getPassword());
        for (Path migration : MigrationFiles.all()) {
            if (migration.getFileName().toString().compareTo(version) >= 0) {
                break;
            }
            exec(con, Files.readString(migration, StandardCharsets.UTF_8)
                    .replace(":app_user", PressroomPostgresContainer.getUsername()));
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
                PressroomPostgresContainer.getJdbcUrl(),
                PressroomPostgresContainer.getUsername(),
                PressroomPostgresContainer.getPassword());
    }

    private static String jdbcUrlFor(String dbName) {
        String url = PressroomPostgresContainer.getJdbcUrl();
        int dbStart = url.lastIndexOf('/') + 1;
        int queryStart = url.indexOf('?', dbStart);
        String tail = queryStart < 0 ? "" : url.substring(queryStart);
        return url.substring(0, dbStart) + dbName + tail;
    }
}
