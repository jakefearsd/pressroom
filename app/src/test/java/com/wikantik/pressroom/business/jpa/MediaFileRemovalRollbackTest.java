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

package com.wikantik.pressroom.business.jpa;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import com.wikantik.pressroom.testing.PressroomPostgresContainer;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.FileContentManager;
import com.wikantik.pressroom.business.FileNotFoundException;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A media file leaves the disk only once its removal has committed.
 *
 * <p>{@code removeMediaFile} and {@code removeMediaFileDirectory} used to
 * delete the original, thumbnail and renditions from disk before the commit,
 * so a removal that rolled back (or whose commit failed) left a row pointing
 * at files that were gone for good.
 */
class MediaFileRemovalRollbackTest {

    private static final String REFUSE_FN = "mfrr_refuse_delete";

    private User user;
    private Weblog weblog;
    private String fileId;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("mediaRollbackUser");
        weblog = TestUtils.setupWeblog("mediarollbackblog", user);
        TestUtils.endSession(true);
        fileId = TestUtils.setupImageMediaFile(TestUtils.getManagedWebsite(weblog),
                "rollback.jpg", "rollbackdir").getId();
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.weblogger().release();
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void aRemovalThatRollsBackKeepsItsFile() throws Exception {
        MediaFile file = media().getMediaFile(fileId);
        media().removeMediaFile(managedWeblog(), file);
        TestUtils.endSession(false);

        assertNotNull(media().getMediaFile(fileId), "the row stays");
        assertNotNull(content().getFileContent(weblog, fileId), "and so must the file it points at");
    }

    @Test
    void aCommittedRemovalDeletesItsFile() throws Exception {
        media().removeMediaFile(managedWeblog(), media().getMediaFile(fileId));
        TestUtils.endSession(true);

        assertNull(media().getMediaFile(fileId));
        assertThrows(FileNotFoundException.class, () -> content().getFileContent(weblog, fileId));
    }

    /**
     * {@code removeMediaFileDirectory} commits itself; when that commit is
     * refused (a real PostgreSQL error from a trigger on this one row), the
     * files must still be on disk.
     */
    @Test
    void aDirectoryRemovalWhoseCommitFailsKeepsItsFiles() throws Exception {
        sql("CREATE OR REPLACE FUNCTION " + REFUSE_FN + "() RETURNS trigger AS $$ BEGIN "
                + "RAISE EXCEPTION 'refused by MediaFileRemovalRollbackTest'; END $$ LANGUAGE plpgsql");
        sql("CREATE TRIGGER " + REFUSE_FN + " BEFORE DELETE ON roller_mediafile FOR EACH ROW "
                + "WHEN (OLD.id = '" + fileId + "') EXECUTE FUNCTION " + REFUSE_FN + "()");
        try {
            assertThrows(Exception.class, () -> media().removeMediaFileDirectory(
                    media().getMediaFileDirectoryByName(managedWeblog(), "rollbackdir")));
            TestUtils.weblogger().release();
        } finally {
            sql("DROP TRIGGER IF EXISTS " + REFUSE_FN + " ON roller_mediafile");
            sql("DROP FUNCTION IF EXISTS " + REFUSE_FN + "()");
        }

        assertNotNull(media().getMediaFile(fileId), "the row stays");
        assertNotNull(content().getFileContent(weblog, fileId), "and so must the file it points at");
    }

    @Test
    void aCommittedDirectoryRemovalDeletesItsFiles() throws Exception {
        media().removeMediaFileDirectory(media().getMediaFileDirectoryByName(managedWeblog(), "rollbackdir"));
        TestUtils.endSession(true);

        assertNull(media().getMediaFile(fileId));
        assertThrows(FileNotFoundException.class, () -> content().getFileContent(weblog, fileId));
    }

    // ------------------------------------------------------------- fixtures

    private Weblog managedWeblog() throws Exception {
        return TestUtils.getManagedWebsite(weblog);
    }

    private static MediaFileManager media() {
        return TestUtils.weblogger().getMediaFileManager();
    }

    private static FileContentManager content() {
        return TestUtils.weblogger().getFileContentManager();
    }

    private static void sql(String statement) throws SQLException {
        try (Connection con = DriverManager.getConnection(PressroomPostgresContainer.getJdbcUrl(),
                PressroomPostgresContainer.getUsername(), PressroomPostgresContainer.getPassword());
                Statement st = con.createStatement()) {
            st.execute(statement);
        }
    }
}
