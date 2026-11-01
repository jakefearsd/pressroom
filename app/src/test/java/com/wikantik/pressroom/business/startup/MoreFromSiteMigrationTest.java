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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V042 adds weblog.show_more_from_site, default on, so every existing blog
 * keeps showing the "more from the site" block until its owner opts out.
 */
public class MoreFromSiteMigrationTest {

    @Test
    void columnDefaultsTrue() throws Exception {
        String db = "migrationtest_more_from_site";
        try (Connection con = ScratchDatabase.migratedThrough(db, 41)) {
            exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) "
                    + "VALUES ('w1', 'w1', 'w1', 'e@example.com', now())");
            applyV042(con);
            assertTrue(flag(con, "w1"), "an existing row reads true after the migration");
            exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) "
                    + "VALUES ('w2', 'w2', 'w2', 'e@example.com', now())");
            assertTrue(flag(con, "w2"), "a row inserted without the column reads true");
            applyV042(con);
            assertEquals(2, count(con), "applying it again changes nothing");
        } finally {
            drop(db);
        }
    }

    private static void applyV042(Connection con) throws Exception {
        for (Path migration : MigrationFiles.all()) {
            if (migration.getFileName().toString().startsWith("V042__")) {
                exec(con, Files.readString(migration, StandardCharsets.UTF_8));
                return;
            }
        }
        throw new AssertionError("no V042 migration");
    }

    private static boolean flag(Connection con, String id) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT show_more_from_site FROM weblog WHERE id = '" + id + "'")) {
            assertTrue(rs.next());
            return rs.getBoolean(1);
        }
    }

    private static int count(Connection con) throws Exception {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM weblog")) {
            rs.next();
            return rs.getInt(1);
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
