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

import com.wikantik.pressroom.testing.ScratchDatabase;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V045 makes a user's role, a user's permission on a weblog and a category
 * name within a weblog unique. The managers checked and then inserted, so a
 * double-submitted form stored two rows, and every later lookup
 * ({@code getSingleResult}) failed on them with {@code
 * NonUniqueResultException}: a 500 for that user or category, and a role
 * that could never be revoked.
 *
 * <p>What it does with the duplicates already stored: a role is the same
 * row twice, so the extra rows go. A permission's duplicates are merged into
 * the oldest row, which keeps the union of their actions -- what granting
 * them one after the other ({@code grantWeblogPermission} adds actions) would
 * have left. A category's duplicates each hold entries, so none is deleted:
 * the one with the most entries keeps the name and the others are renamed
 * {@code "<name> 2"}, {@code "<name> 3"}, ... (the first free one).
 */
public class UniqueGrantsMigrationTest {

    private static final int BEFORE = 44;
    private static final int V045 = 45;

    @Test
    void duplicateRolesAreReducedToOne() throws Exception {
        String db = "migrationtest_unique_roles";
        try (Connection con = ScratchDatabase.migratedThrough(db, BEFORE)) {
            exec(con, "INSERT INTO userrole (id, username, rolename) VALUES "
                    + "('r1', 'bob', 'editor'), ('r2', 'bob', 'editor'), ('r3', 'bob', 'admin'), "
                    + "('r4', 'amy', 'editor')");

            ScratchDatabase.apply(con, ScratchDatabase.migration(V045));

            assertEquals(Map.of("r1", "bob/editor", "r3", "bob/admin", "r4", "amy/editor"),
                    rows(con, "SELECT id, username || '/' || rolename FROM userrole"));
            SQLException refused = assertThrows(SQLException.class, () -> exec(con,
                    "INSERT INTO userrole (id, username, rolename) VALUES ('r5', 'bob', 'admin')"));
            assertEquals("23505", refused.getSQLState(), refused.getMessage());
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    @Test
    void duplicatePermissionsAreMergedIntoTheOldestWithEveryAction() throws Exception {
        String db = "migrationtest_unique_perms";
        try (Connection con = ScratchDatabase.migratedThrough(db, BEFORE)) {
            exec(con, "INSERT INTO pressroom_permission (id, username, actions, objectid, objecttype, datecreated) "
                    + "VALUES ('p2', 'bob', 'post', 'blog', 'Weblog', '2026-02-01'), "
                    + "('p1', 'bob', 'edit_draft', 'blog', 'Weblog', '2026-01-01'), "
                    + "('p3', 'bob', 'admin,post', 'blog', 'Weblog', '2026-03-01'), "
                    + "('p4', 'bob', 'post', 'other', 'Weblog', '2026-01-01'), "
                    + "('p5', 'amy', 'admin', 'blog', 'Weblog', '2026-01-01')");

            ScratchDatabase.apply(con, ScratchDatabase.migration(V045));

            assertEquals(Map.of("p1", "bob/blog/edit_draft,post,admin",
                            "p4", "bob/other/post",
                            "p5", "amy/blog/admin"),
                    rows(con, "SELECT id, username || '/' || objectid || '/' || actions FROM pressroom_permission"));
            SQLException refused = assertThrows(SQLException.class, () -> exec(con,
                    "INSERT INTO pressroom_permission (id, username, actions, objectid, objecttype, datecreated) "
                            + "VALUES ('p6', 'amy', 'post', 'blog', 'Weblog', now())"));
            assertEquals("23505", refused.getSQLState(), refused.getMessage());
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    @Test
    void duplicateCategoriesKeepTheirEntriesAndAllButTheFullestAreRenamed() throws Exception {
        String db = "migrationtest_unique_cats";
        try (Connection con = ScratchDatabase.migratedThrough(db, BEFORE)) {
            seedCategories(con);

            ScratchDatabase.apply(con, ScratchDatabase.migration(V045));

            Map<String, String> expected = new TreeMap<>();
            expected.put("c1", "w1/Travel 3");
            expected.put("c2", "w1/Travel");
            expected.put("c3", "w1/Travel 5");
            expected.put("c4", "w1/Travel 4");
            expected.put("c5", "w2/Travel");
            expected.put("c9", "w1/Travel 2");
            expected.put("c6", "w1/" + "t".repeat(255));
            expected.put("c7", "w1/" + "t".repeat(253) + " 2");
            assertEquals(expected, rows(con, "SELECT id, websiteid || '/' || name FROM weblogcategory"));
            assertEquals(Map.of("e1", "c2", "e2", "c2", "e3", "c1"),
                    rows(con, "SELECT id, categoryid FROM weblogentry"), "no entry changes category");
            SQLException refused = assertThrows(SQLException.class, () -> exec(con,
                    "INSERT INTO weblogcategory (id, name, websiteid) VALUES ('c8', 'Travel', 'w2')"));
            assertEquals("23505", refused.getSQLState(), refused.getMessage());
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    @Test
    void applyingItAgainChangesNothing() throws Exception {
        String db = "migrationtest_unique_twice";
        try (Connection con = ScratchDatabase.migratedThrough(db, BEFORE)) {
            seedCategories(con);
            ScratchDatabase.apply(con, ScratchDatabase.migration(V045));
            Map<String, String> afterFirst = rows(con, "SELECT id, name FROM weblogcategory");

            ScratchDatabase.apply(con, ScratchDatabase.migration(V045));

            assertEquals(afterFirst, rows(con, "SELECT id, name FROM weblogcategory"));
            assertEquals(Map.of("1", "3"), rows(con, "SELECT 1, count(*) FROM pg_indexes WHERE indexname IN "
                    + "('ur_username_rolename_uq', 'perm_username_object_uq', 'wc_weblog_name_uq')"));
        } finally {
            ScratchDatabase.drop(db);
        }
    }

    /**
     * Weblog w1 has "Travel" four times: c2 with two entries, c1 with one, c3
     * and c4 empty (c4 placed first), and "Travel 2" is already taken; a
     * 255-character name twice. Weblog w2 has "Travel" once, which is no clash.
     */
    private static void seedCategories(Connection con) throws SQLException {
        exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) VALUES "
                + "('w1', 'One', 'one', 'a@example.com', now()), ('w2', 'Two', 'two', 'a@example.com', now())");
        exec(con, "INSERT INTO weblogcategory (id, name, websiteid, position) VALUES "
                + "('c1', 'Travel', 'w1', 1), ('c2', 'Travel', 'w1', 2), ('c3', 'Travel', 'w1', 3), "
                + "('c4', 'Travel', 'w1', 0), ('c5', 'Travel', 'w2', 0), "
                + "('c6', '" + "t".repeat(255) + "', 'w1', 0), ('c7', '" + "t".repeat(255) + "', 'w1', 1), "
                + "('c9', 'Travel 2', 'w1', 5)");
        for (String[] entry : new String[][] {{"e1", "c2"}, {"e2", "c2"}, {"e3", "c1"}}) {
            exec(con, "INSERT INTO weblogentry (id, anchor, creator, title, text, updatetime, websiteid, "
                    + "categoryid, status) VALUES ('" + entry[0] + "', '" + entry[0] + "', 'u', 't', 'x', now(), "
                    + "'w1', '" + entry[1] + "', 'PUBLISHED')");
        }
    }

    private static Map<String, String> rows(Connection con, String sql) throws SQLException {
        Map<String, String> rows = new TreeMap<>();
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.put(rs.getString(1), rs.getString(2));
            }
        }
        return rows;
    }

    private static void exec(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }
}
