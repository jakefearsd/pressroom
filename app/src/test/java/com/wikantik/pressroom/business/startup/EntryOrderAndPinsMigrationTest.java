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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V047 adds weblog.entry_order (default NEWEST) and weblogentry.pinned
 * (default false), so every existing blog lists exactly as it did.
 */
public class EntryOrderAndPinsMigrationTest {

    @Test
    void defaultsKeepEveryBlogAsItWas() throws Exception {
        String db = "migrationtest_order_pins";
        try (Connection con = ScratchDatabase.migratedThrough(db, 46)) {
            exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) "
                    + "VALUES ('w1', 'w1', 'w1', 'e@example.com', now())");
            applyV047(con);
            assertEquals("NEWEST", order(con, "w1"), "an existing blog reads NEWEST");
            exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) "
                    + "VALUES ('w2', 'w2', 'w2', 'e@example.com', now())");
            assertEquals("NEWEST", order(con, "w2"), "a blog inserted without the column reads NEWEST");
            applyV047(con);
            assertEquals("NEWEST", order(con, "w1"), "applying it again changes nothing");
        } finally {
            drop(db);
        }
    }

    @Test
    void entriesAreNotPinned() throws Exception {
        String db = "migrationtest_order_pins_entry";
        try (Connection con = ScratchDatabase.migrated(db)) {
            exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) "
                    + "VALUES ('w1', 'w1', 'w1', 'e@example.com', now())");
            exec(con, "INSERT INTO weblogcategory (id, name, websiteid) VALUES ('c1', 'c1', 'w1')");
            exec(con, "INSERT INTO weblogentry (id, anchor, creator, title, text, updatetime, websiteid, "
                    + "categoryid, status) VALUES ('e1', 'a', 'u', 't', 'x', now(), 'w1', 'c1', 'PUBLISHED')");
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT pinned FROM weblogentry WHERE id = 'e1'")) {
                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1), "an entry inserted without the column is not pinned");
            }
            applyV047(con);
        } finally {
            drop(db);
        }
    }

    private static void applyV047(Connection con) throws Exception {
        for (Path migration : MigrationFiles.all()) {
            if (migration.getFileName().toString().startsWith("V047__")) {
                exec(con, Files.readString(migration, StandardCharsets.UTF_8));
                return;
            }
        }
        throw new AssertionError("no V047 migration");
    }

    private static String order(Connection con, String id) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT entry_order FROM weblog WHERE id = '" + id + "'")) {
            assertTrue(rs.next());
            return rs.getString(1);
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
