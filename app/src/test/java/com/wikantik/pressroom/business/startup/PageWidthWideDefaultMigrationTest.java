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
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V054 makes WIDE the page-width default and moves every existing blog to it,
 * stamping lastmodified so its cached pages expire.
 */
class PageWidthWideDefaultMigrationTest {

    @Test
    void everyBlogBecomesWideAndItsCacheExpires() throws Exception {
        String db = "migrationtest_page_width_wide";
        try (Connection con = ScratchDatabase.migratedThrough(db, 53)) {
            exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated, lastmodified) "
                    + "VALUES ('w1', 'w1', 'w1', 'e@example.com', now(), '2020-01-01')");
            assertEquals("STANDARD", width(con, "w1"));
            applyV054(con);
            assertEquals("WIDE", width(con, "w1"), "an existing blog is moved to WIDE");
            assertTrue(lastModified(con, "w1").after(Timestamp.valueOf("2021-01-01 00:00:00")),
                    "its lastmodified moves, so its cached pages expire");
            exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) "
                    + "VALUES ('w2', 'w2', 'w2', 'e@example.com', now())");
            assertEquals("WIDE", width(con, "w2"), "a blog inserted without the column reads WIDE");
            exec(con, "UPDATE weblog SET lastmodified = '2020-01-01' WHERE id = 'w1'");
            Timestamp before = lastModified(con, "w1");
            applyV054(con);
            assertEquals(before, lastModified(con, "w1"),
                    "applying it again touches no blog that is already WIDE");
        } finally {
            drop(db);
        }
    }

    private static void applyV054(Connection con) throws Exception {
        for (Path migration : MigrationFiles.all()) {
            if (migration.getFileName().toString().startsWith("V054__")) {
                exec(con, Files.readString(migration, StandardCharsets.UTF_8));
                return;
            }
        }
        throw new AssertionError("no V054 migration");
    }

    private static String width(Connection con, String id) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT page_width FROM weblog WHERE id = '" + id + "'")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static Timestamp lastModified(Connection con, String id) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT lastmodified FROM weblog WHERE id = '" + id + "'")) {
            assertTrue(rs.next());
            return rs.getTimestamp(1);
        }
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
