/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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
 * limitations under the License.
 */
package com.wikantik.pressroom.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.wikantik.pressroom.business.DatabaseProvider;
import com.wikantik.pressroom.business.startup.ClasspathDatabaseScriptProvider;
import com.wikantik.pressroom.business.startup.DatabaseInstaller;
import com.wikantik.pressroom.testing.PressroomPostgresContainer;
import com.wikantik.pressroom.testing.ScratchDatabase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V041 renames the live production schema: every {@code roller_*} table,
 * sequence, index and constraint becomes {@code pressroom_*}, and stored
 * {@code /pressroom-ui/} links in pages and entries follow the UI's new path.
 * The data is what matters, so each test seeds rows at V040 and reads them
 * back through the new names.
 */
class PressroomRenameMigrationTest {

    private static final String DB = "pressroom_rename_probe";

    /** The old prefix and UI path, which V041 exists to retire. */
    private static final String OLD = "roller_"; // rebrand:keep
    private static final String OLD_LIKE = "roller\\_%"; // rebrand:keep
    private static final String OLD_UI = "/pressroom-ui/"; // rebrand:keep

    /** The 18 tables the Phase 0 production scan found, without their prefix. */
    private static final List<String> TABLES = List.of(
            "audit_log", "permission", "properties", "tasklock", "weblogentrytagagg",
            "mediafiledir", "event", "form_submission", "user", "api_token", "mediafile",
            "mediafiletag", "reader_comment", "user_token", "weblogpage", "weblog_redirect",
            "weblogentrytag", "business");

    private static final int V040 = 40;
    private static final int V041 = 41;

    @Test
    void renamesEveryOldRelationAndConstraint() throws Exception {
        try (Connection con = ScratchDatabase.migratedThrough(DB, V040)) {
            seed(con);
            int constraints = count(con, "SELECT count(*) FROM pg_constraint c "
                    + "JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname = 'public'");
            int indexes = count(con, "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'");

            ScratchDatabase.apply(con, ScratchDatabase.migration(V041));

            assertFullyRenamed(con);
            assertSeedReadable(con);
            assertEquals(constraints, count(con, "SELECT count(*) FROM pg_constraint c "
                    + "JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname = 'public'"),
                    "a rename neither adds nor drops a constraint");
            assertEquals(indexes, count(con, "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'"),
                    "a rename neither adds nor drops an index");
        } finally {
            ScratchDatabase.drop(DB);
        }
    }

    @Test
    void isIdempotent() throws Exception {
        try (Connection con = ScratchDatabase.migratedThrough(DB, V040)) {
            seed(con);
            ScratchDatabase.apply(con, ScratchDatabase.migration(V041));
            List<String> once = schemaAndContent(con);

            ScratchDatabase.apply(con, ScratchDatabase.migration(V041));

            assertEquals(once, schemaAndContent(con), "a second run changes nothing");
        } finally {
            ScratchDatabase.drop(DB);
        }
    }

    @Test
    void finishesAPartialRename() throws Exception {
        try (Connection con = ScratchDatabase.migratedThrough(DB, V040)) {
            seed(con);
            exec(con, "ALTER TABLE " + OLD + "user RENAME TO pressroom_user");

            ScratchDatabase.apply(con, ScratchDatabase.migration(V041));

            assertFullyRenamed(con);
            assertSeedReadable(con);
        } finally {
            ScratchDatabase.drop(DB);
        }
    }

    @Test
    void refusesToRunWhenBothTheOldAndTheNewTableExist() throws Exception {
        try (Connection con = ScratchDatabase.migratedThrough(DB, V040)) {
            seed(con);
            exec(con, "CREATE TABLE pressroom_user (id varchar(48))");

            SQLException refused = assertThrows(SQLException.class,
                    () -> ScratchDatabase.apply(con, ScratchDatabase.migration(V041)));

            assertTrue(refused.getMessage().contains("pressroom_user"), refused.getMessage());
            assertTrue(exists(con, OLD + "api_token"),
                    "the renames before the conflict are rolled back with it");
            assertEquals("olduser", string(con, "SELECT username FROM " + OLD + "user WHERE id = 'u1'"));
        } finally {
            ScratchDatabase.drop(DB);
        }
    }

    @Test
    void refusesANameThatWouldOutgrowPostgresIdentifierLimit() throws Exception {
        try (Connection con = ScratchDatabase.migratedThrough(DB, V040)) {
            String longest = OLD + "x".repeat(63 - OLD.length());
            exec(con, "CREATE TABLE " + longest + " (id int)");

            SQLException refused = assertThrows(SQLException.class,
                    () -> ScratchDatabase.apply(con, ScratchDatabase.migration(V041)));

            assertTrue(refused.getMessage().contains(longest), refused.getMessage());
            assertTrue(exists(con, OLD + "user"), "nothing was renamed");
        } finally {
            ScratchDatabase.drop(DB);
        }
    }

    @Test
    void rewritesStoredUiLinks() throws Exception {
        try (Connection con = ScratchDatabase.migratedThrough(DB, V040)) {
            seed(con);
            exec(con, "INSERT INTO " + OLD + "weblogpage (id, weblogid, slug, title, content, created, updated) "
                    + "VALUES ('p1', 'w1', 'privacy', 'Privacy', "
                    + "'<a href=\"" + OLD_UI + "account.rol\">account</a>', now(), now())");
            exec(con, "INSERT INTO weblogentry (id, anchor, creator, title, text, summary, updatetime, "
                    + "websiteid, categoryid, status) VALUES ('e2', 'b', 'olduser', 't', "
                    + "'<a href=\"" + OLD_UI + "login.rol\">in</a>', 'see " + OLD_UI + "x', now(), "
                    + "'w1', 'c1', 'PUBLISHED')");
            exec(con, "INSERT INTO weblogentry_revision (id, entryid, created, text) "
                    + "VALUES ('r1', 'e2', now(), 'was " + OLD_UI + "x')");

            ScratchDatabase.apply(con, ScratchDatabase.migration(V041));

            assertEquals("<a href=\"/pressroom-ui/account.rol\">account</a>",
                    string(con, "SELECT content FROM pressroom_weblogpage WHERE id = 'p1'"));
            assertEquals("<a href=\"/pressroom-ui/login.rol\">in</a>",
                    string(con, "SELECT text FROM weblogentry WHERE id = 'e2'"));
            assertEquals("see /pressroom-ui/x", string(con, "SELECT summary FROM weblogentry WHERE id = 'e2'"));
            assertEquals("a controller", string(con, "SELECT text FROM weblogentry WHERE id = 'e1'"),
                    "only the UI path is rewritten, never the substring");
            assertEquals("was " + OLD_UI + "x",
                    string(con, "SELECT text FROM weblogentry_revision WHERE id = 'r1'"),
                    "revisions are history and keep what was written");
        } finally {
            ScratchDatabase.drop(DB);
        }
    }

    @Test
    void analyticsViewsStillAnswer() throws Exception {
        try (Connection con = ScratchDatabase.migratedThrough(DB, V040)) {
            seed(con);
            exec(con, "INSERT INTO " + OLD + "event (id, weblogid, event_type, occurred_at) "
                    + "VALUES ('ev1', 'w1', 'cta_click', now())");

            ScratchDatabase.apply(con, ScratchDatabase.migration(V041));

            assertEquals(1, count(con, "SELECT count(*) FROM analytics_events"));
            assertTrue(string(con, "SELECT pg_get_viewdef('analytics_events'::regclass)")
                    .contains("pressroom_event"), "the view follows the table by oid");
        } finally {
            ScratchDatabase.drop(DB);
        }
    }

    @Test
    void freshInstallEndsRenamed() throws Exception {
        ScratchDatabase.empty(DB);
        try {
            DatabaseProvider provider = mock(DatabaseProvider.class);
            when(provider.getConnection()).thenAnswer(invocation -> DriverManager.getConnection(
                    ScratchDatabase.jdbcUrl(DB), PressroomPostgresContainer.getUsername(),
                    PressroomPostgresContainer.getPassword()));

            new DatabaseInstaller(provider, new ClasspathDatabaseScriptProvider()).createDatabase();

            try (Connection con = provider.getConnection()) {
                assertTrue(exists(con, "pressroom_user"));
                assertFalse(exists(con, OLD + "user"));
                assertFullyRenamed(con);
            }
        } finally {
            ScratchDatabase.drop(DB);
        }
    }

    private static void seed(Connection con) throws SQLException {
        exec(con, "INSERT INTO " + OLD + "user (id, username, passphrase, screenname, fullname, "
                + "emailaddress, datecreated, isenabled) "
                + "VALUES ('u1', 'olduser', 'x', 'Old', 'Old', 'old@example.com', now(), true)");
        exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) "
                + "VALUES ('w1', 'One', 'one', 'a@example.com', now())");
        exec(con, "INSERT INTO weblogcategory (id, name, websiteid) VALUES ('c1', 'General', 'w1')");
        exec(con, "INSERT INTO weblogentry (id, anchor, creator, title, text, updatetime, websiteid, "
                + "categoryid, status) VALUES ('e1', 'a', 'olduser', 't', 'a controller', now(), 'w1', 'c1', "
                + "'PUBLISHED')");
        exec(con, "INSERT INTO " + OLD + "api_token (id, userid, label, token_sha256, scope_role, created) "
                + "VALUES ('t1', 'u1', 'agent', 'digest-1', 'AUTHOR', now())");
    }

    private static void assertSeedReadable(Connection con) throws SQLException {
        assertEquals("olduser", string(con, "SELECT username FROM pressroom_user WHERE id = 'u1'"));
        assertEquals("digest-1", string(con, "SELECT t.token_sha256 FROM pressroom_api_token t "
                + "JOIN pressroom_user u ON u.id = t.userid WHERE u.username = 'olduser'"));
        assertEquals("a controller", string(con, "SELECT text FROM weblogentry WHERE id = 'e1'"));
        SQLException orphan = assertThrows(SQLException.class, () -> exec(con,
                "INSERT INTO pressroom_api_token (id, userid, label, token_sha256, scope_role, created) "
                        + "VALUES ('t2', 'nobody', 'x', 'digest-2', 'AUTHOR', now())"));
        assertEquals("23503", orphan.getSQLState(), "the token's foreign key survives the rename");
    }

    private static void assertFullyRenamed(Connection con) throws SQLException {
        assertEquals(List.of(), strings(con, "SELECT c.relname FROM pg_class c JOIN pg_namespace n "
                + "ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname LIKE '" + OLD_LIKE
                + "' ORDER BY 1"), "no relation keeps the old prefix");
        assertEquals(List.of(), strings(con, "SELECT conname FROM pg_constraint WHERE conname LIKE '"
                + OLD_LIKE + "' ORDER BY 1"), "no constraint keeps the old prefix");
        for (String table : TABLES) {
            assertTrue(exists(con, "pressroom_" + table), "pressroom_" + table + " exists");
        }
    }

    /** Every relation and constraint name in public, then the content V041 rewrites. */
    private static List<String> schemaAndContent(Connection con) throws SQLException {
        List<String> all = new ArrayList<>(strings(con, "SELECT c.relname FROM pg_class c JOIN pg_namespace n "
                + "ON n.oid = c.relnamespace WHERE n.nspname = 'public' ORDER BY 1"));
        all.addAll(strings(con, "SELECT conname FROM pg_constraint ORDER BY 1"));
        all.addAll(strings(con, "SELECT coalesce(text, '') || coalesce(summary, '') FROM weblogentry ORDER BY id"));
        return all;
    }

    private static boolean exists(Connection con, String table) throws SQLException {
        return string(con, "SELECT to_regclass('public." + table + "')::text") != null;
    }

    private static void exec(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    private static int count(Connection con, String sql) throws SQLException {
        return Integer.parseInt(string(con, sql));
    }

    private static String string(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static List<String> strings(Connection con, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
