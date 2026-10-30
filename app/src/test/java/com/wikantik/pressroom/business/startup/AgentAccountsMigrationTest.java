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
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package com.wikantik.pressroom.business.startup;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.wikantik.pressroom.testing.MigrationFiles;
import com.wikantik.pressroom.testing.PressroomPostgresContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V033 (spec 2026-10-13, section 1; criterion 1): an account kind, agent notes
 * and the last sign-in on roller_user, a nullable password, and the issuer on // rebrand:keep
 * roller_api_token. Rows from before V033 read back as people with no issuer. // rebrand:keep
 */
public class AgentAccountsMigrationTest {

    @Test
    void rowsFromBeforeV033ReadBackAsPeopleWithNoIssuerAndV033IsRepeatable() throws Exception {
        String db = "migrationtest_agent_accounts_existing";
        try (Connection con = databaseBefore(db, "V033")) {
            exec(con, "INSERT INTO roller_user (id, username, passphrase, screenname, fullname, " // rebrand:keep
                    + "emailaddress, datecreated, isenabled) "
                    + "VALUES ('u1', 'olduser', 'x', 'Old', 'Old', 'old@example.com', now(), true)");
            exec(con, "INSERT INTO roller_api_token (id, userid, label, token_sha256, scope_role, created) " // rebrand:keep
                    + "VALUES ('t1', 'u1', 'old', 'digest-1', 'READ', now())");

            String v033 = Files.readString(migration("V033"), StandardCharsets.UTF_8);
            exec(con, v033);
            exec(con, v033);

            assertEquals("PERSON", string(con, "SELECT kind FROM roller_user WHERE id = 'u1'")); // rebrand:keep
            assertNull(string(con, "SELECT notes FROM roller_user WHERE id = 'u1'")); // rebrand:keep
            assertNull(string(con, "SELECT last_login FROM roller_user WHERE id = 'u1'")); // rebrand:keep
            assertNull(string(con, "SELECT issued_by FROM roller_api_token WHERE id = 't1'")); // rebrand:keep
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void kindIsPersonOrAgentAndAnAgentNeedsNoPassword() throws Exception {
        String db = "migrationtest_agent_accounts_kind";
        try (Connection con = freshDatabase(db)) {
            exec(con, "INSERT INTO pressroom_user (id, username, passphrase, screenname, fullname, "
                    + "emailaddress, datecreated, isenabled, kind) "
                    + "VALUES ('a1', 'bot', NULL, 'Bot', 'Bot', 'bot@agents.invalid', now(), true, 'AGENT')");
            assertEquals("AGENT", string(con, "SELECT kind FROM pressroom_user WHERE id = 'a1'"));

            SQLException refused = assertThrows(SQLException.class, () -> exec(con,
                    "INSERT INTO pressroom_user (id, username, passphrase, screenname, fullname, "
                    + "emailaddress, datecreated, isenabled, kind) "
                    + "VALUES ('r1', 'robot', 'x', 'R', 'R', 'r@example.com', now(), true, 'ROBOT')"));
            assertEquals("23514", refused.getSQLState(), "the check constraint refuses any other kind");
        } finally {
            dropDatabase(db);
        }
    }

    private static Path migration(String version) {
        return MigrationFiles.all().stream()
                .filter(p -> p.getFileName().toString().startsWith(version + "__"))
                .findFirst().orElseThrow(() -> new AssertionError(version + " is missing"));
    }

    private String string(Connection con, String sql) throws Exception {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private void exec(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    private Connection freshDatabase(String dbName) throws Exception {
        return databaseBefore(dbName, "V999");
    }

    /** A new database with every migration whose version sorts before {@code version} applied. */
    private Connection databaseBefore(String dbName, String version) throws Exception {
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
                continue;
            }
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
