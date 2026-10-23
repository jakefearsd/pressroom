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
package org.apache.roller.weblogger.business.jpa;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.util.RollerMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link JPAMediaFileManagerImpl#removeMediaFile} deletes the original file
 * plus its admin thumbnail (`_sm`) and every responsive rendition, via a
 * private {@code deleteFileQuietly} helper that swallows a missing-file
 * exception on purpose -- a MediaFile that was never an image (or whose
 * renditions were never generated) has no thumbnail/rendition files to
 * delete in the first place, and that must not fail the whole remove.
 *
 * <p>Regression test for the swallow being TRACELESS rather than merely
 * quiet: {@code deleteFileQuietly} used to log
 * {@code "File to be deleted already unavailable..."} with only the file id,
 * dropping the caught exception entirely, so a debug-enabled operator had no
 * stack trace to look at even when they went looking. "Quiet" (the method's
 * own name) is not the same as "traceless".
 */
class JPAMediaFileManagerImplTest {

    private User testUser;
    private Weblog testWeblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        testUser = TestUtils.setupUser("deleteFileQuietlyTestUser");
        testWeblog = TestUtils.setupWeblog("deleteFileQuietlyTestWeblog", testUser);
        TestUtils.endSession(true);

        // allow media uploads for this test (default is off)
        Map<String, RuntimeConfigProperty> config =
                TestUtils.weblogger().getPropertiesManager().getProperties();
        config.get("uploads.enabled").setValue("true");
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void removingAMediaFileWithNoThumbnailLogsTheMissingFileExceptionAtDebug() throws Exception {
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        // A non-image content type: persistNewMediaFile only calls
        // updateThumbnail() -- the thing that writes the `_sm` file -- when
        // MediaFile.isImageFile() is true, so this file's `_sm` sibling (and
        // every rendition id) never exists on disk. removeMediaFile's
        // deleteFileQuietly calls for them are therefore guaranteed to hit
        // the missing-file catch branch under test, deterministically.
        MediaFile mediaFile = new MediaFile();
        mediaFile.setName("notice.txt");
        mediaFile.setDirectory(rootDirectory);
        mediaFile.setWeblog(testWeblog);
        mediaFile.setContentType("text/plain");
        mediaFile.setLength(11);
        mediaFile.setInputStream(new ByteArrayInputStream("hello world".getBytes()));

        mfMgr.createMediaFile(testWeblog, mediaFile, new RollerMessages());
        String id = mediaFile.getId();
        TestUtils.endSession(true);
        assertNotNull(id, "createMediaFile must have persisted the file (uploads are enabled).");

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFile saved = mfMgr.getMediaFile(id);

        List<LogEvent> captured = new ArrayList<>();
        Appender appender = new AbstractAppender("JPAMediaFileManagerImplTest-capture", null, null, false,
                Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                captured.add(event.toImmutable());
            }
        };
        appender.start();

        LoggerContext context = LoggerContext.getContext(false);
        LoggerConfig loggerConfig = context.getConfiguration()
                .getLoggerConfig(JPAMediaFileManagerImpl.class.getName());
        loggerConfig.addAppender(appender, null, null);
        try {
            mfMgr.removeMediaFile(testWeblog, saved);
            // the files leave the disk once the removal commits
            TestUtils.endSession(true);
        } finally {
            loggerConfig.removeAppender("JPAMediaFileManagerImplTest-capture");
            appender.stop();
        }

        List<LogEvent> quietDeletes = captured.stream()
                .filter(event -> event.getMessage().getFormattedMessage()
                        .startsWith("File to be deleted already unavailable"))
                .toList();
        assertFalse(quietDeletes.isEmpty(),
                "Expected the missing _sm thumbnail (and rendition ids) to hit "
                        + "deleteFileQuietly's catch branch.");
        for (LogEvent event : quietDeletes) {
            assertEquals(Level.DEBUG, event.getLevel());
            assertNotNull(event.getThrown(),
                    "The caught exception must be attached to the log record, not discarded -- "
                            + "quiet is not the same as traceless.");
        }
    }
}
