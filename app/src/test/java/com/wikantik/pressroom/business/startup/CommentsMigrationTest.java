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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V031 adds roller_reader_comment and three settings columns.
 */
public class CommentsMigrationTest {

    @Test
    void tableAndColumnDefaults() throws Exception {
        String db = "migrationtest_comments_defaults";
        try (Connection con = freshDatabase(db)) {
            assertEquals(9, scalar(con, "SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_name = 'roller_reader_comment'"));
            assertDefault(con, "weblog", "comments_enabled", "false");
            assertDefault(con, "weblog", "comment_notify", "true");
            assertDefault(con, "weblogentry", "comments_closed", "false");
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void applyingItAgainChangesNothing() throws Exception {
        String db = "migrationtest_comments_twice";
        try (Connection con = freshDatabase(db)) {
            seed(con);
            insertComment(con, "c1", "PUBLISHED", null);
            for (Path migration : MigrationFiles.all()) {
                if (migration.getFileName().toString().startsWith("V031")) {
                    exec(con, Files.readString(migration, StandardCharsets.UTF_8));
                }
            }
            assertEquals(1, scalar(con, "SELECT count(*) FROM roller_reader_comment"));
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void statusIsConstrainedAndDeletesCascade() throws Exception {
        String db = "migrationtest_comments_cascade";
        try (Connection con = freshDatabase(db)) {
            seed(con);
            insertComment(con, "c1", "PUBLISHED", null);
            SQLException bad = assertThrows(SQLException.class,
                    () -> insertComment(con, "c2", "BOGUS", null));
            assertEquals("23514", bad.getSQLState());

            insertComment(con, "c3", "PUBLISHED", "c1");
            exec(con, "DELETE FROM roller_reader_comment WHERE id = 'c1'");
            assertEquals(0, scalar(con, "SELECT count(*) FROM roller_reader_comment"));

            insertComment(con, "c4", "PUBLISHED", null);
            exec(con, "DELETE FROM weblogentry WHERE id = 'e1'");
            assertEquals(0, scalar(con, "SELECT count(*) FROM roller_reader_comment"));
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void aCommentHasAtMostOneReply() throws Exception {
        String db = "migrationtest_comments_one_reply";
        try (Connection con = freshDatabase(db)) {
            seed(con);
            insertComment(con, "c1", "PUBLISHED", null);
            insertComment(con, "r1", "PUBLISHED", "c1");
            SQLException second = assertThrows(SQLException.class,
                    () -> insertComment(con, "r2", "PUBLISHED", "c1"));
            assertEquals("23505", second.getSQLState());
            insertComment(con, "c2", "PUBLISHED", null);   // top-level rows (null reply_to) are unconstrained
            insertComment(con, "c3", "PUBLISHED", null);
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void deletingTheUserDeletesTheirComments() throws Exception {
        String db = "migrationtest_comments_user";
        try (Connection con = freshDatabase(db)) {
            seed(con);
            insertComment(con, "c1", "PENDING", null);
            exec(con, "DELETE FROM roller_user WHERE id = 'u1'");
            assertEquals(0, scalar(con, "SELECT count(*) FROM roller_reader_comment"));
        } finally {
            dropDatabase(db);
        }
    }

    private void assertDefault(Connection con, String table, String column, String expected)
            throws SQLException {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT column_default FROM information_schema.columns "
                     + "WHERE table_name = '" + table + "' AND column_name = '" + column + "'")) {
            assertTrue(rs.next());
            assertEquals(expected, rs.getString(1));
        }
    }

    private int scalar(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private void exec(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private void seed(Connection con) throws SQLException {
        exec(con, "INSERT INTO roller_user (id, username, passphrase, screenname, fullname, "
                + "emailaddress, datecreated, isenabled) "
                + "VALUES ('u1', 'u1', 'x', 'u1', 'u1', 'e@example.com', now(), true)");
        exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) "
                + "VALUES ('w1', 'w1', 'w1', 'e@example.com', now())");
        exec(con, "INSERT INTO weblogcategory (id, name, websiteid) VALUES ('cat1', 'c', 'w1')");
        exec(con, "INSERT INTO weblogentry (id, anchor, creator, title, text, updatetime, websiteid, "
                + "categoryid, status) "
                + "VALUES ('e1', 'a', 'u1', 't', 'x', now(), 'w1', 'cat1', 'PUBLISHED')");
    }

    private void insertComment(Connection con, String id, String status, String replyTo)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO roller_reader_comment (id, entry_id, author_id, reply_to_id, body, status, "
                + "created, status_changed) VALUES (?, 'e1', 'u1', ?, 'b', ?, now(), now())")) {
            ps.setString(1, id);
            ps.setString(2, replyTo);
            ps.setString(3, status);
            ps.executeUpdate();
        }
    }

    private Connection freshDatabase(String dbName) throws Exception {
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
            try (Statement st = con.createStatement()) {
                st.execute(Files.readString(migration, StandardCharsets.UTF_8)
                        .replace(":app_user", PressroomPostgresContainer.getUsername()));
            }
        }
        return con;
    }

    private void dropDatabase(String dbName) throws Exception {
        try (Connection admin = adminConnection();
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName);
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                PressroomPostgresContainer.getJdbcUrl(),
                PressroomPostgresContainer.getUsername(),
                PressroomPostgresContainer.getPassword());
    }

    private String jdbcUrlFor(String dbName) {
        String url = PressroomPostgresContainer.getJdbcUrl();
        int dbStart = url.lastIndexOf('/') + 1;
        int queryStart = url.indexOf('?', dbStart);
        String tail = queryStart < 0 ? "" : url.substring(queryStart);
        return url.substring(0, dbStart) + dbName + tail;
    }
}
