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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V049 adds weblog.front_page_id (null), so every existing blog keeps listing
 * its posts at the root, and deleting the page row backstops the application
 * by nulling the column.
 */
public class FrontPageMigrationTest {

    @Test
    void existingBlogsHaveNoFrontPageAndTheMigrationIsRepeatable() throws Exception {
        String db = "migrationtest_frontpage_defaults";
        try (Connection con = ScratchDatabase.migratedThrough(db, 48)) {
            seedBlogAndPage(con);
            applyV049(con);
            assertNull(frontPage(con));
            applyV049(con);
            assertNull(frontPage(con));
        } finally {
            drop(db);
        }
    }

    @Test
    void deletingTheFrontPageRowNullsTheColumn() throws Exception {
        String db = "migrationtest_frontpage_fk";
        try (Connection con = ScratchDatabase.migrated(db)) {
            seedBlogAndPage(con);
            exec(con, "UPDATE weblog SET front_page_id = 'p1' WHERE id = 'w1'");
            assertEquals("p1", frontPage(con));
            exec(con, "DELETE FROM pressroom_weblogpage WHERE id = 'p1'");
            assertNull(frontPage(con));
            applyV049(con);
        } finally {
            drop(db);
        }
    }

    private static String frontPage(Connection con) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT front_page_id FROM weblog WHERE id = 'w1'")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static void seedBlogAndPage(Connection con) throws Exception {
        exec(con, "INSERT INTO weblog (id, name, handle, emailaddress, datecreated) "
                + "VALUES ('w1', 'w1', 'w1', 'e@example.com', now())");
        exec(con, "INSERT INTO pressroom_weblogpage (id, weblogid, slug, title, created, updated) "
                + "VALUES ('p1', 'w1', 's', 't', now(), now())");
    }

    private static void applyV049(Connection con) throws Exception {
        for (Path migration : MigrationFiles.all()) {
            if (migration.getFileName().toString().startsWith("V049__")) {
                exec(con, Files.readString(migration, StandardCharsets.UTF_8));
                return;
            }
        }
        throw new AssertionError("no V049 migration");
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
