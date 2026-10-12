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
package org.apache.roller.weblogger.ui.controllers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.util.RollerMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DB-backed tests for {@link MediaUploads}, the per-file upload logic moved
 * out of {@code MediaApi} (Task A6) so the editor's session upload endpoint
 * ({@code MediaFileAddController#upload}) can share it.
 */
class MediaUploadsTest {

    private User testUser;
    private Weblog weblog;
    private User otherUser;
    private Weblog otherWeblog;
    private Weblogger weblogger;
    private MediaFileManager mfm;
    private MediaFileDirectory dir;
    private MediaFileDirectory otherWeblogsDirectory;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        weblogger = TestUtils.weblogger();
        mfm = weblogger.getMediaFileManager();

        testUser = TestUtils.setupUser("mediaUploadsTestUser");
        weblog = TestUtils.setupWeblog("mediaUploadsTestWeblog", testUser);
        otherUser = TestUtils.setupUser("mediaUploadsOtherUser");
        otherWeblog = TestUtils.setupWeblog("mediaUploadsOtherWeblog", otherUser);

        // Uploads live behind a runtime switch that defaults to off; every
        // test here needs it on, and needs the forbidden-extension list
        // empty by default (one test below overrides it).
        Map<String, RuntimeConfigProperty> config = weblogger.getPropertiesManager().getProperties();
        config.get("uploads.enabled").setValue("true");
        config.get("uploads.types.forbid").setValue("");
        weblogger.getPropertiesManager().saveProperties(config);

        dir = mfm.getDefaultMediaFileDirectory(weblog);
        if (dir == null) {
            dir = mfm.createDefaultMediaFileDirectory(weblog);
        }
        otherWeblogsDirectory = mfm.getDefaultMediaFileDirectory(otherWeblog);
        if (otherWeblogsDirectory == null) {
            otherWeblogsDirectory = mfm.createDefaultMediaFileDirectory(otherWeblog);
        }
        weblogger.flush();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(otherWeblog.getId());
        TestUtils.teardownUser(otherUser.getUserName());
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
        TestUtils.endSession(true);
    }

    /** A 1x1 PNG, the smallest input {@code canSave}'s image-shape checks accept. */
    private static byte[] tinyPng() {
        return Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    }

    @Test
    void aFileLandsInTheGivenDirectoryAndIsReturnedCreated() throws Exception {
        MockMultipartFile png = new MockMultipartFile("file", "photo.png", "image/png", tinyPng());
        RollerMessages messages = new RollerMessages();

        MediaUploads.Result r = MediaUploads.store(png, weblog, dir, mfm, messages);

        assertEquals("created", r.status());
        assertNotNull(r.file().getId());
        assertEquals(0, messages.getErrorCount());
    }

    @Test
    void aForbiddenExtensionIsReportedNotThrown() throws Exception {
        Map<String, RuntimeConfigProperty> config = weblogger.getPropertiesManager().getProperties();
        config.get("uploads.types.forbid").setValue("exe");
        weblogger.getPropertiesManager().saveProperties(config);

        MockMultipartFile exe = new MockMultipartFile("file", "x.exe", "application/octet-stream", new byte[]{1});
        MediaUploads.Result r = MediaUploads.store(exe, weblog, dir, mfm, new RollerMessages());

        // Not thrown, and not the generic "error" bucket either: canSave's
        // error.upload.forbiddenFile maps to the specific "forbidden_extension"
        // status (MediaUploads.refusal, moved from MediaDtos.refusal) -- the
        // same three-way split MediaUploadTest's API-level tests already pin,
        // so this per-file helper cannot regress that mapping.
        assertEquals("forbidden_extension", r.status());
        assertNotNull(r.detail());
    }

    @Test
    void aForeignDirectoryIdResolvesToNull() throws Exception {
        assertNull(MediaUploads.directoryFor(weblogger, weblog, otherWeblogsDirectory.getId()));
        assertNull(MediaUploads.directoryFor(weblogger, weblog, "   "));
    }

    @Test
    void theApiNoLongerCarriesItsOwnCopy() throws IOException {
        String api = Files.readString(
                Path.of("src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApi.java"));
        assertFalse(api.contains("private String effectiveContentType("), "MediaApi must delegate to MediaUploads");
        assertTrue(api.contains("MediaUploads."), "MediaApi must delegate to MediaUploads");
    }

    @Test
    void aByteArraySourceReachesCreateMediaFileWithItsNameTypeLengthAndBytes() throws Exception {
        byte[] bytes = tinyPng();
        MediaFileManager manager = mock(MediaFileManager.class);

        MediaUploads.Result r = MediaUploads.store(
                MediaUploads.Source.of("a.jpg", "image/jpeg", bytes), weblog, dir, manager, new RollerMessages());

        assertEquals("created", r.status());
        ArgumentCaptor<MediaFile> captor = ArgumentCaptor.forClass(MediaFile.class);
        verify(manager).createMediaFile(org.mockito.ArgumentMatchers.eq(weblog), captor.capture(), any());
        MediaFile stored = captor.getValue();
        assertEquals("a.jpg", stored.getName());
        assertEquals("image/jpeg", stored.getContentType());
        assertEquals(bytes.length, stored.getLength());
        assertEquals(dir, stored.getDirectory());
        assertTrue(java.util.Arrays.equals(bytes, stored.getInputStream().readAllBytes()));
    }

    @Test
    void anEmptyByteArraySourceIsRefusedBeforeTheManager() throws Exception {
        MediaFileManager manager = mock(MediaFileManager.class);

        MediaUploads.Result r = MediaUploads.store(
                MediaUploads.Source.of("a.jpg", "image/jpeg", new byte[0]), weblog, dir, manager,
                new RollerMessages());

        assertEquals("error", r.status());
        assertEquals("The file is empty.", r.detail());
        verify(manager, never()).createMediaFile(any(), any(), any());
    }

    @Test
    void theFileLimitIsReadFromTheRuntimeConfigInBytes() throws Exception {
        Map<String, RuntimeConfigProperty> config = weblogger.getPropertiesManager().getProperties();
        config.get("uploads.file.maxsize").setValue("2.00");
        weblogger.getPropertiesManager().saveProperties(config);

        assertEquals(2L * 1024 * 1024, MediaUploads.maxFileBytes());
    }
}
