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
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V037 adds weblogentry_revision.note, the revisionNote an API update labels
 * the revision it leaves with (spec 2026-10-24, section 5).
 */
public class EntryRevisionNoteMigrationTest {

    private static final String NOTE_COLUMN = "SELECT count(*) FROM information_schema.columns "
            + "WHERE table_name = 'weblogentry_revision' AND column_name = 'note'";

    @Test
    void theNoteIsANullableColumnOfAtMost255Characters() throws Exception {
        String db = "migrationtest_revision_note";
        try (Connection con = freshDatabase(db)) {
            assertEquals(1, scalar(con, NOTE_COLUMN
                    + " AND is_nullable = 'YES' AND character_maximum_length = 255"));
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    void applyingItAgainChangesNothing() throws Exception {
        String db = "migrationtest_revision_note_twice";
        try (Connection con = freshDatabase(db)) {
            for (Path migration : MigrationFiles.all()) {
                if (migration.getFileName().toString().startsWith("V037")) {
                    exec(con, Files.readString(migration, StandardCharsets.UTF_8));
                }
            }
            assertEquals(1, scalar(con, NOTE_COLUMN));
        } finally {
            dropDatabase(db);
        }
    }

    private int scalar(Connection con, String sql) throws Exception {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private void exec(Connection con, String sql) throws Exception {
        try (Statement st = con.createStatement()) {
            st.executeUpdate(sql);
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
                RollerPostgresContainer.getUsername(),
                RollerPostgresContainer.getPassword());
        for (Path migration : MigrationFiles.all()) {
            try (Statement st = con.createStatement()) {
                st.execute(Files.readString(migration, StandardCharsets.UTF_8)
                        .replace(":app_user", RollerPostgresContainer.getUsername()));
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
                RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(),
                RollerPostgresContainer.getPassword());
    }

    private String jdbcUrlFor(String dbName) {
        String url = RollerPostgresContainer.getJdbcUrl();
        int dbStart = url.lastIndexOf('/') + 1;
        int queryStart = url.indexOf('?', dbStart);
        String tail = queryStart < 0 ? "" : url.substring(queryStart);
        return url.substring(0, dbStart) + dbName + tail;
    }
}
