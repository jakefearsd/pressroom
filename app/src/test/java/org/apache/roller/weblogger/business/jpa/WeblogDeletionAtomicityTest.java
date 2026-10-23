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

package org.apache.roller.weblogger.business.jpa;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.roller.testing.RollerPostgresContainer;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.FileContentManager;
import org.apache.roller.weblogger.business.FileNotFoundException;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Deleting a weblog is one transaction: it either removes everything or
 * nothing, and the media files on disk go only once the deletion has
 * committed.
 *
 * <p>Until 2026-10-22 {@code removeWeblogContents} committed three times on
 * the way (each {@code strategy.flush()} is a COMMIT), and deleted media
 * files from disk before the first. A failure after the first commit left a
 * weblog with no templates, pages or media; after the second, one with no
 * entries; and no rollback could bring the files back.
 *
 * <p>The failure here is a real PostgreSQL refusal late in the deletion: an
 * entry of ANOTHER weblog is pointed at this weblog's category, so the
 * category delete, which runs after the entries are gone, violates
 * {@code we_categoryid_fk}.
 */
class WeblogDeletionAtomicityTest {

    private User user;
    private Weblog doomed;
    private Weblog bystander;
    private String doomedEntryId;
    private String bystanderEntryId;
    private String mediaFileId;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("delAtomUser");
        doomed = TestUtils.setupWeblog("delatomdoomed", user);
        bystander = TestUtils.setupWeblog("delatombystander", user);
        TestUtils.endSession(true);

        WeblogEntry entry = TestUtils.setupWeblogEntry("delatom-entry",
                TestUtils.getManagedWebsite(doomed), TestUtils.getManagedUser(user));
        entry.setTagsAsString("delatomtag");
        entries().saveWeblogEntry(entry);
        doomedEntryId = entry.getId();
        bystanderEntryId = TestUtils.setupWeblogEntry("delatom-bystander-entry",
                TestUtils.getManagedWebsite(bystander), TestUtils.getManagedUser(user)).getId();
        TestUtils.endSession(true);

        MediaFile file = TestUtils.setupImageMediaFile(TestUtils.getManagedWebsite(doomed), "delatom.jpg");
        mediaFileId = file.getId();
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.weblogger().release();
        if (bystander == null) {
            if (doomed != null) {
                TestUtils.teardownWeblog(doomed.getId());
            }
            TestUtils.teardownUser(user.getUserName());
            TestUtils.endSession(true);
            return;
        }
        // put the bystander's entry back in its own weblog's category first
        update("UPDATE weblogentry SET categoryid = (SELECT id FROM weblogcategory WHERE websiteid = ? LIMIT 1)"
                + " WHERE id = ?", bystander.getId(), bystanderEntryId);
        for (Weblog weblog : new Weblog[] {doomed, bystander}) {
            if (weblogs().getWeblog(weblog.getId()) != null) {
                TestUtils.teardownWeblog(weblog.getId());
            }
        }
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void aDeletionThatFailsPartWayLeavesTheWeblogWhole() throws Exception {
        update("UPDATE weblogentry SET categoryid = (SELECT id FROM weblogcategory WHERE websiteid = ? LIMIT 1)"
                + " WHERE id = ?", doomed.getId(), bystanderEntryId);
        assertEquals(1, tagAggregateRows(doomed.getId()));

        assertThrows(Exception.class, () -> {
            weblogs().removeWeblog(weblogs().getWeblog(doomed.getId()));
            TestUtils.weblogger().flush();
        }, "the category delete must be refused by we_categoryid_fk");
        TestUtils.weblogger().release();

        Weblog survivor = weblogs().getWeblog(doomed.getId());
        assertNotNull(survivor, "the weblog");
        assertNotNull(entries().getWeblogEntry(doomedEntryId), "its entry");
        assertNotNull(TestUtils.weblogger().getMediaFileManager().getMediaFile(mediaFileId), "its media row");
        assertNotNull(content().getFileContent(survivor, mediaFileId), "its media file on disk");
        assertEquals(1, tagAggregateRows(doomed.getId()), "its tag counts");
        assertEquals(1, count("SELECT count(*) FROM roller_permission WHERE objectid = ?", doomed.getHandle()),
                "its member");
    }

    @Test
    void theMediaFilesLeaveTheDiskOnlyOnceTheDeletionCommits() throws Exception {
        Weblog weblog = weblogs().getWeblog(doomed.getId());
        weblogs().removeWeblog(weblog);

        assertNotNull(content().getFileContent(weblog, mediaFileId),
                "deleted from disk before the commit, where no rollback can bring it back");

        TestUtils.weblogger().flush();
        TestUtils.weblogger().release();

        assertNull(weblogs().getWeblog(doomed.getId()));
        assertThrows(FileNotFoundException.class, () -> content().getFileContent(weblog, mediaFileId));
        assertEquals(0, tagAggregateRows(doomed.getId()));
        assertEquals(0, count("SELECT count(*) FROM roller_mediafile WHERE id = ?", mediaFileId));
    }

    // ------------------------------------------------------------- fixtures

    private static WeblogManager weblogs() {
        return TestUtils.weblogger().getWeblogManager();
    }

    private static WeblogEntryManager entries() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    private static FileContentManager content() {
        return TestUtils.weblogger().getFileContentManager();
    }

    private static int tagAggregateRows(String weblogId) throws SQLException {
        return count("SELECT count(*) FROM roller_weblogentrytagagg WHERE websiteid = ?", weblogId);
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(RollerPostgresContainer.getJdbcUrl(),
                RollerPostgresContainer.getUsername(), RollerPostgresContainer.getPassword());
    }

    private static int count(String sql, String parameter) throws SQLException {
        try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, parameter);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void update(String sql, String first, String second) throws SQLException {
        try (Connection con = connection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, first);
            ps.setString(2, second);
            ps.executeUpdate();
        }
    }
}
