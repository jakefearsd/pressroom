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
import com.wikantik.pressroom.testing.PressroomPostgresContainer;
import org.junit.jupiter.api.Test;

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
 * V030 binds an account to an OpenID Connect identity: the (issuer, subject)
 * pair is unique, and the two columns are both set or both null.
 */
public class UserSsoIdentityMigrationTest {

    @Test
    void identityColumnsExist() throws Exception {
        String db = "migrationtest_sso_columns";
        try (Connection con = freshDatabase(db);
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM information_schema.columns "
                     + "WHERE table_name = 'roller_user' AND column_name IN ('sso_issuer','sso_subject')")) { // rebrand:keep
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void identityPairIsUniqueAndAllOrNothing() throws Exception {
        String db = "migrationtest_sso_identity";
        try (Connection con = freshDatabase(db)) {
            insertUser(con, "u1", "a", null, null);
            insertUser(con, "u2", "b", null, null);                       // two local accounts: fine
            insertUser(con, "u3", "c", "https://issuer", "sub-1");
            SQLException dup = assertThrows(SQLException.class,
                    () -> insertUser(con, "u4", "d", "https://issuer", "sub-1"));
            assertEquals("23505", dup.getSQLState());
            SQLException half = assertThrows(SQLException.class,
                    () -> insertUser(con, "u5", "e", null, "sub-2"));
            assertEquals("23514", half.getSQLState());
        } finally {
            dropDatabase(db);
        }
    }

    private void insertUser(Connection con, String id, String name, String issuer, String subject)
            throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO roller_user (id, username, passphrase, screenname, fullname, " // rebrand:keep
                + "emailaddress, datecreated, isenabled, sso_issuer, sso_subject) "
                + "VALUES (?, ?, 'x', ?, ?, 'e@example.com', now(), true, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, name);
            ps.setString(4, name);
            ps.setString(5, issuer);
            ps.setString(6, subject);
            ps.executeUpdate();
        }
    }

    /**
     * The schema as V040 left it: V041 renamed the table this test names.
     */
    private Connection freshDatabase(String dbName) throws Exception {
        return ScratchDatabase.migratedThrough(dbName, 40);
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
}
