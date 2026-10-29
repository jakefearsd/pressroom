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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.FileContentManager;
import com.wikantik.pressroom.business.FileIOException;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.MediaFileFilter;
import com.wikantik.pressroom.pojos.MediaFileTag;
import com.wikantik.pressroom.pojos.MediaFileType;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.util.PressroomMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JPAMediaFileManagerImpl}'s moves, path lookups, theme-file creation,
 * content loading, tag replacement, multi-tag / "others" search and the
 * replacement-upload refusal, exercised against the real tier and the real
 * uploads directory.
 *
 * <p>Characterisation tests: written against the existing behaviour and
 * expected to pass immediately, pinning it so a later change is noticed.
 */
class MediaFileLookupMoveAndTagsTest {

    private User testUser;
    private Weblog testWeblog;
    private MediaFileManager mfMgr;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        testUser = TestUtils.setupUser("mfLookupMoveTagsUser");
        testWeblog = TestUtils.setupWeblog("mfLookupMoveTagsWeblog", testUser);
        TestUtils.endSession(true);
        mfMgr = TestUtils.weblogger().getMediaFileManager();
        uploadsEnabled("true");
    }

    @AfterEach
    void tearDown() throws Exception {
        uploadsEnabled("true");
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
        TestUtils.endSession(true);
    }

    private static void uploadsEnabled(String value) throws Exception {
        Map<String, RuntimeConfigProperty> config =
                TestUtils.weblogger().getPropertiesManager().getProperties();
        config.get("uploads.enabled").setValue(value);
    }

    private MediaFileDirectory defaultDirectory() throws Exception {
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        return mfMgr.getDefaultMediaFileDirectory(testWeblog);
    }

    private static MediaFile newFile(Weblog weblog, MediaFileDirectory dir, String name,
            String contentType, byte[] bytes) {
        MediaFile mf = new MediaFile();
        mf.setName(name);
        mf.setDirectory(dir);
        mf.setWeblog(weblog);
        mf.setContentType(contentType);
        mf.setLength(bytes.length);
        mf.setInputStream(new ByteArrayInputStream(bytes));
        return mf;
    }

    /** Uploads through the normal, checked path and returns the new id. */
    private String upload(MediaFileDirectory dir, String name, String contentType, String body)
            throws Exception {
        MediaFile mf = newFile(testWeblog, dir, name, contentType, body.getBytes(StandardCharsets.UTF_8));
        PressroomMessages errors = new PressroomMessages();
        mfMgr.createMediaFile(testWeblog, mf, errors);
        assertEquals(0, errors.getErrorCount(), "fixture upload refused: " + errors);
        TestUtils.endSession(true);
        assertNotNull(mf.getId());
        return mf.getId();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (in) {
            return in.readAllBytes();
        }
    }

    private static Set<String> tagNames(MediaFile mf) {
        return mf.getTags().stream().map(MediaFileTag::getName).collect(Collectors.toSet());
    }

    private static List<String> names(List<MediaFile> files) {
        return files.stream().map(MediaFile::getName).toList();
    }

    @Test
    void movingOneFileRehomesItsRowAndPathLookupsFollowIt() throws Exception {
        MediaFileDirectory root = defaultDirectory();
        mfMgr.createMediaFileDirectory(testWeblog, "trips");
        TestUtils.endSession(true);

        String movedId = upload(defaultDirectory(), "itinerary.txt", "text/plain", "day one");
        String stayingId = upload(defaultDirectory(), "readme.txt", "text/plain", "stays put");

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFileDirectory trips = mfMgr.getMediaFileDirectoryByName(testWeblog, "trips");
        mfMgr.moveMediaFile(mfMgr.getMediaFile(movedId), trips);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        assertEquals("trips", mfMgr.getMediaFile(movedId).getDirectory().getName());
        assertEquals(movedId, mfMgr.getMediaFileByPath(testWeblog, "trips/itinerary.txt").getId());
        assertEquals(movedId, mfMgr.getMediaFileByPath(testWeblog, "/trips/itinerary.txt").getId(),
                "a leading slash on the directory part is ignored");
        assertNull(mfMgr.getMediaFileByPath(testWeblog, "itinerary.txt"),
                "the default directory must no longer list the moved file");

        // A bare name, and a name with only a leading slash, both resolve in
        // the default directory.
        assertEquals(stayingId, mfMgr.getMediaFileByPath(testWeblog, "readme.txt").getId());
        assertEquals(stayingId, mfMgr.getMediaFileByPath(testWeblog, "/readme.txt").getId());
        assertEquals(root.getId(), mfMgr.getMediaFile(stayingId).getDirectory().getId());

        // Storage is keyed by id, not path: the moved file's bytes are untouched.
        MediaFile withContent = mfMgr.getMediaFile(movedId, true);
        assertEquals("day one", new String(readAll(withContent.getInputStream()), StandardCharsets.UTF_8));
    }

    @Test
    void loadingContentForAFileWithoutAThumbnailLeavesTheThumbnailEmpty() throws Exception {
        String id = upload(defaultDirectory(), "notes.txt", "text/plain", "plain words");

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFile withContent = mfMgr.getMediaFile(id, true);

        assertEquals("plain words", new String(readAll(withContent.getInputStream()), StandardCharsets.UTF_8));
        assertNull(withContent.getThumbnailInputStream(),
                "a non-image has no _sm sibling; the missing thumbnail must not fail the load");
        assertNull(mfMgr.getMediaFileByOriginalPath(testWeblog, null));
    }

    @Test
    void themeMediaFilesBypassTheUploadChecksThatRefuseAnOrdinaryUpload() throws Exception {
        uploadsEnabled("false");

        MediaFileDirectory root = defaultDirectory();
        MediaFile refused = newFile(testWeblog, root, "refused.txt", "text/plain",
                "no".getBytes(StandardCharsets.UTF_8));
        PressroomMessages errors = new PressroomMessages();
        mfMgr.createMediaFile(testWeblog, refused, errors);
        assertEquals(1, errors.getErrorCount());
        assertTrue(errors.toString().contains("error.upload.disabled"), errors.toString());

        MediaFile themeFile = newFile(testWeblog, root, "theme.css", "text/css",
                "body{}".getBytes(StandardCharsets.UTF_8));
        PressroomMessages themeErrors = new PressroomMessages();
        mfMgr.createThemeMediaFile(testWeblog, themeFile, themeErrors);
        TestUtils.endSession(true);

        assertEquals(0, themeErrors.getErrorCount());
        String id = themeFile.getId();
        assertNotNull(id);
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        assertEquals(id, mfMgr.getMediaFileByPath(testWeblog, "theme.css").getId());
        assertNull(mfMgr.getMediaFile(refused.getId()), "a refused upload persists nothing");
        assertNull(mfMgr.getMediaFileByPath(testWeblog, "refused.txt"));
        FileContentManager cmgr = TestUtils.weblogger().getFileContentManager();
        assertEquals("body{}", new String(readAll(cmgr.getFileContent(testWeblog, id).getInputStream()),
                StandardCharsets.UTF_8));
    }

    @Test
    void aReplacementUploadOverTheSizeLimitIsRefusedAndTheStoredBytesAreKept() throws Exception {
        String id = upload(defaultDirectory(), "draft.txt", "text/plain", "original bytes");

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFile mf = mfMgr.getMediaFile(id);
        // uploads.file.maxsize defaults to 2 MB; claim ten.
        mf.setLength(10L * 1024 * 1024);
        final Weblog weblog = testWeblog;
        FileIOException refused = assertThrows(FileIOException.class,
                () -> mfMgr.updateMediaFile(weblog, mf,
                        new ByteArrayInputStream("replacement".getBytes(StandardCharsets.UTF_8))));
        assertTrue(refused.getMessage().contains("error.upload.filemax"), refused.getMessage());
        TestUtils.endSession(true);

        FileContentManager cmgr = TestUtils.weblogger().getFileContentManager();
        assertEquals("original bytes", new String(
                readAll(cmgr.getFileContent(testWeblog, id).getInputStream()), StandardCharsets.UTF_8));
    }

    @Test
    void updateTagsReplacesTheSetAndANullListLeavesItAlone() throws Exception {
        String id = upload(defaultDirectory(), "tagged.txt", "text/plain", "x");

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFile mf = mfMgr.getMediaFile(id);
        mfMgr.updateTags(mf, List.of("Alpha", "beta"));
        mfMgr.updateMediaFile(testWeblog, mf);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        mf = mfMgr.getMediaFile(id);
        assertEquals(Set.of("alpha", "beta"), tagNames(mf), "names are normalised (lower-cased)");

        mfMgr.updateTags(mf, null);
        assertEquals(Set.of("alpha", "beta"), tagNames(mf));

        mfMgr.updateTags(mf, List.of("beta", "gamma"));
        assertEquals(Set.of("alpha"), mf.getRemovedTags());
        assertEquals(Set.of("gamma"), mf.getAddedTags(),
                "a tag already present is kept, not re-added");
        mfMgr.updateMediaFile(testWeblog, mf);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        assertEquals(Set.of("beta", "gamma"), tagNames(mfMgr.getMediaFile(id)),
                "the dropped tag's row must be gone after a reload");
    }

    @Test
    void searchMatchesAnyOfSeveralTagsAndOthersExcludesEveryKnownMediaType() throws Exception {
        String notesId = upload(defaultDirectory(), "notes.txt", "text/plain", "a");
        String songId = upload(defaultDirectory(), "song.mp3", "audio/mpeg", "b");
        upload(defaultDirectory(), "clip.txt", "text/plain", "c");
        upload(defaultDirectory(), "film.mp4", "video/mp4", "d");

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFile notes = mfMgr.getMediaFile(notesId);
        mfMgr.updateTags(notes, List.of("alpha"));
        mfMgr.updateMediaFile(testWeblog, notes);
        MediaFile song = mfMgr.getMediaFile(songId);
        mfMgr.updateTags(song, List.of("beta"));
        mfMgr.updateMediaFile(testWeblog, song);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFileFilter byTags = new MediaFileFilter();
        byTags.setTags(List.of("alpha", "beta"));
        assertEquals(List.of("notes.txt", "song.mp3"), names(mfMgr.searchMediaFiles(testWeblog, byTags)));

        MediaFileFilter others = new MediaFileFilter();
        others.setType(MediaFileType.OTHERS);
        assertEquals(List.of("clip.txt", "notes.txt"), names(mfMgr.searchMediaFiles(testWeblog, others)),
                "OTHERS excludes audio, video and image content types");
    }
}
