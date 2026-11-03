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
package com.wikantik.pressroom.business.jpa;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.wikantik.pressroom.business.jpa.TagAggregateRows.connection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Creating a weblog is one transaction, as deleting one is
 * ({@code WeblogDeletionAtomicityTest}): it stores the weblog with its
 * owner's permission, its categories and its media directory, or nothing.
 *
 * <p>{@code addWeblog} used to commit the weblog row on its own and then add
 * the rest in a second commit, so a failure in the second part left a weblog
 * nobody had a permission on, with no category to file an entry under. The
 * failure here is a real PostgreSQL refusal at the last insert: a trigger
 * refuses the new weblog's media directory.
 */
class WeblogCreationAtomicityTest {

    private static final String HANDLE = "creatomblog";

    private User user;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("creatomuser");
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.weblogger().release();
        sql("DROP TRIGGER IF EXISTS creation_atomicity_refuse ON pressroom_mediafiledir");
        sql("DROP FUNCTION IF EXISTS creation_atomicity_refuse()");
        Weblog left = weblogs().getWeblogByHandle(HANDLE, null);
        if (left != null) {
            TestUtils.teardownWeblog(left.getId());
        }
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void aCreationThatFailsPartWayLeavesNoWeblog() throws Exception {
        Weblog weblog = new Weblog();
        weblog.setName("Creation atomicity");
        weblog.setTagline("");
        weblog.setHandle(HANDLE);
        weblog.setEmailAddress("creatom@dev.null");
        weblog.setEditorTheme("journal");
        weblog.setLocale("en_US");
        weblog.setTimeZone("America/Los_Angeles");
        weblog.setDateCreated(new java.util.Date());
        weblog.setCreatorUserName(user.getUserName());
        sql("CREATE OR REPLACE FUNCTION creation_atomicity_refuse() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.websiteid = '" + weblog.getId() + "' THEN "
                + "RAISE EXCEPTION 'refused by WeblogCreationAtomicityTest'; END IF; RETURN NEW; END $$");
        sql("CREATE TRIGGER creation_atomicity_refuse BEFORE INSERT ON pressroom_mediafiledir "
                + "FOR EACH ROW EXECUTE FUNCTION creation_atomicity_refuse()");

        assertThrows(Exception.class, () -> {
            weblogs().addWeblog(weblog);
            TestUtils.weblogger().flush();
        }, "the media directory insert must be refused by the trigger");
        TestUtils.weblogger().release();

        assertEquals(0, count("SELECT count(*) FROM weblog WHERE handle = ?", HANDLE), "no weblog row");
        assertEquals(0, count("SELECT count(*) FROM pressroom_permission WHERE objectid = ?", HANDLE),
                "no permission row");
        assertEquals(0, count("SELECT count(*) FROM weblogcategory WHERE websiteid = ?", weblog.getId()),
                "no category row");
    }

    private static WeblogManager weblogs() {
        return TestUtils.weblogger().getWeblogManager();
    }

    private static void sql(String statement) throws Exception {
        try (Connection con = connection(); Statement st = con.createStatement()) {
            st.execute(statement);
        }
    }

    private static int count(String sql, String param) throws Exception {
        try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
