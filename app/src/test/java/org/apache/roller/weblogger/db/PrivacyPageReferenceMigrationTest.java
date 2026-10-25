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
package org.apache.roller.weblogger.db;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.apache.roller.testing.MigrationFiles;
import org.apache.roller.testing.ScratchDatabase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * V040 turns the privacy setting from a slug on the front-page blog into a
 * page reference: {@code privacy} + front-page blog {@code wikantik} becomes
 * {@code wikantik/privacy}, so the setting keeps pointing at the same page
 * (AC8). Anything already qualified, blank, or with no front-page blog to
 * qualify it by is left alone, and a second run changes nothing.
 */
class PrivacyPageReferenceMigrationTest {

    private static final String DB = "privacyref_probe";

    /** The privacy value after V040 ran over these two stored values (null = row absent). */
    private String migrate(String privacy, String frontHandle) throws Exception {
        try (Connection con = ScratchDatabase.migrated(DB)) {
            exec(con, "DELETE FROM roller_properties");
            if (privacy != null) {
                exec(con, "INSERT INTO roller_properties (name, value) VALUES ('site.privacy.page', '"
                        + privacy + "')");
            }
            if (frontHandle != null) {
                exec(con, "INSERT INTO roller_properties (name, value) VALUES "
                        + "('site.frontpage.weblog.handle', '" + frontHandle + "')");
            }
            exec(con, migration());
            String once = privacy(con);
            exec(con, migration());
            assertEquals(once, privacy(con), "a second run must change nothing (AC8)");
            return once;
        } finally {
            ScratchDatabase.drop(DB);
        }
    }

    private static String migration() throws Exception {
        for (Path file : MigrationFiles.all()) {
            if (file.getFileName().toString().startsWith("V040__")) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("no V040 migration");
    }

    private static void exec(Connection con, String sql) throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    private static String privacy(Connection con) throws Exception {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT value FROM roller_properties WHERE name = 'site.privacy.page'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void aBareSlugIsQualifiedByTheFrontPageBlog() throws Exception {
        assertEquals("wikantik/privacy", migrate("privacy", "wikantik"));
    }

    @Test
    void anAlreadyQualifiedReferenceIsLeftAlone() throws Exception {
        assertEquals("other/privacy", migrate("other/privacy", "wikantik"));
    }

    @Test
    void aBlankPrivacySettingStaysBlank() throws Exception {
        assertEquals("", migrate("", "wikantik"));
    }

    @Test
    void withNoFrontPageBlogTheSlugStaysBareAndNowMeansASitePage() throws Exception {
        assertEquals("privacy", migrate("privacy", ""));
        assertEquals("privacy", migrate("privacy", null));
    }

    @Test
    void anAbsentPrivacyRowStaysAbsent() throws Exception {
        assertEquals(null, migrate(null, "wikantik"));
    }
}
