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
import com.wikantik.pressroom.testing.ScratchDatabase;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V048 adds pressroom_weblogpage.is_strand (default false) and
 * weblogentry.strand_page_id / strand_position (null), so every existing page
 * and post reads exactly as before, and deleting a page detaches its posts.
 */
public class StrandsMigrationTest {

    @Test
    void existingRowsAreNotStrandsAndNotParts() throws Exception {
        String db = "migrationtest_strands_defaults";
        try (Connection con = ScratchDatabase.migratedThrough(db, 47)) {
            seedBlogPageAndEntry(con);
            applyV048(con);
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT is_strand FROM pressroom_weblogpage WHERE id = 'p1'")) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1), "an existing page is not a strand");
            }
            assertNullStrand(con, "e1");
            assertNullPosition(con, "e1");
            applyV048(con);
            assertNullStrand(con, "e1");
            assertNullPosition(con, "e1");
        } finally {
            drop(db);
        }
    }

    @Test
    void deletingAPageDetachesItsPosts() throws Exception {
        String db = "migrationtest_strands_fk";
        try (Connection con = ScratchDatabase.migrated(db)) {
            seedBlogPageAndEntry(con);
            exec(con, "UPDATE weblogentry SET strand_page_id = 'p1', strand_position = 4 WHERE id = 'e1'");
            exec(con, "DELETE FROM pressroom_weblogpage WHERE id = 'p1'");
            assertNullStrand(con, "e1");
            applyV048(con);
        } finally {
            drop(db);
        }
    }

    private static void seedBlogPageAndEntry(Connection con) throws Exception {
        exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) "
                + "VALUES ('w1', 'w1', 'w1', 'e@example.com', now())");
        exec(con, "INSERT INTO pressroom_weblogpage (id, weblogid, slug, title, created, updated) "
                + "VALUES ('p1', 'w1', 's', 't', now(), now())");
        exec(con, "INSERT INTO weblogcategory (id, name, websiteid) VALUES ('c1', 'c1', 'w1')");
        exec(con, "INSERT INTO weblogentry (id, anchor, creator, title, text, updatetime, websiteid, "
                + "categoryid, status) VALUES ('e1', 'a', 'u', 't', 'x', now(), 'w1', 'c1', 'PUBLISHED')");
    }

    private static void assertNullPosition(Connection con, String id) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT strand_position FROM weblogentry WHERE id = '" + id + "'")) {
            assertTrue(rs.next());
            rs.getInt(1);
            assertTrue(rs.wasNull(), "no position");
        }
    }

    private static void assertNullStrand(Connection con, String id) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT strand_page_id FROM weblogentry WHERE id = '" + id + "'")) {
            assertTrue(rs.next());
            assertNull(rs.getString(1), "no strand");
        }
    }

    private static void applyV048(Connection con) throws Exception {
        for (Path migration : MigrationFiles.all()) {
            if (migration.getFileName().toString().startsWith("V048__")) {
                exec(con, Files.readString(migration, StandardCharsets.UTF_8));
                return;
            }
        }
        throw new AssertionError("no V048 migration");
    }

    private static void exec(Connection con, String sql) throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    private static void drop(String db) throws Exception {
        try (Connection admin = DriverManager.getConnection(PressroomPostgresContainer.getJdbcUrl(),
                PressroomPostgresContainer.getUsername(), PressroomPostgresContainer.getPassword());
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + db);
        }
    }
}
