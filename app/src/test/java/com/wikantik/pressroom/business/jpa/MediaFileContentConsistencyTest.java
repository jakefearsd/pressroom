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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import javax.imageio.ImageIO;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.CwebpEncoder;
import com.wikantik.pressroom.business.FileContentManager;
import com.wikantik.pressroom.business.FileIOException;
import com.wikantik.pressroom.business.FileNotFoundException;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.util.PressroomMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The media row and the files on disk must describe the same bytes: after a
 * replacement (or a refused one), the stored type, length, dimensions,
 * blurhash and EXIF, and every derived sibling ({@code _sm}, the rendition
 * ladder) belong to the file actually stored, never to the one it replaced.
 */
class MediaFileContentConsistencyTest {

    private User testUser;
    private Weblog testWeblog;
    private MediaFileManager mfMgr;
    private FileContentManager cmgr;
    private String dirMaxSizeBefore;

    @BeforeEach
    void setUp() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        TestUtils.setupWeblogger();
        testUser = TestUtils.setupUser("mfConsistencyUser");
        testWeblog = TestUtils.setupWeblog("mfConsistencyWeblog", testUser);
        TestUtils.endSession(true);
        mfMgr = TestUtils.weblogger().getMediaFileManager();
        cmgr = TestUtils.weblogger().getFileContentManager();
        config().get("uploads.enabled").setValue("true");
        dirMaxSizeBefore = config().get("uploads.dir.maxsize").getValue();
    }

    @AfterEach
    void tearDown() throws Exception {
        CwebpEncoder.setAvailableForTesting(null);
        TestUtils.endSession(false);
        // the properties are entities: a changed value commits with the next flush
        config().get("uploads.dir.maxsize").setValue(dirMaxSizeBefore);
        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
        TestUtils.endSession(true);
    }

    private static Map<String, RuntimeConfigProperty> config() throws Exception {
        return TestUtils.weblogger().getPropertiesManager().getProperties();
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = MediaFileContentConsistencyTest.class.getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }

    private String upload(String name, String contentType, byte[] bytes) throws Exception {
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFile mf = new MediaFile();
        mf.setName(name);
        mf.setDirectory(mfMgr.getDefaultMediaFileDirectory(testWeblog));
        mf.setWeblog(testWeblog);
        mf.setContentType(contentType);
        mf.setLength(bytes.length);
        mf.setInputStream(new ByteArrayInputStream(bytes));
        PressroomMessages errors = new PressroomMessages();
        mfMgr.createMediaFile(testWeblog, mf, errors);
        assertEquals(0, errors.getErrorCount(), "fixture upload refused: " + errors);
        TestUtils.endSession(true);
        assertNotNull(mf.getId());
        return mf.getId();
    }

    /** Replaces the content the way MediaFileEditController does, then commits. */
    private void replace(String id, String contentType, byte[] bytes) throws Exception {
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFile mf = mfMgr.getMediaFile(id);
        mf.setContentType(contentType);
        mf.setLength(bytes.length);
        mfMgr.updateMediaFile(testWeblog, mf, new ByteArrayInputStream(bytes));
        TestUtils.endSession(true);
    }

    private byte[] stored(String fileId) throws Exception {
        try (InputStream in = cmgr.getFileContent(testWeblog, fileId).getInputStream()) {
            return in.readAllBytes();
        }
    }

    private void assertGone(String fileId, String why) {
        final Weblog weblog = testWeblog;
        assertThrows(FileNotFoundException.class, () -> cmgr.getFileContent(weblog, fileId), why);
    }

    private MediaFile reloaded(String id) throws Exception {
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        return mfMgr.getMediaFile(id);
    }

    // ------------------------------------------------------ refused replacement

    @Test
    void aRefusedReplacementCommitsNothing() throws Exception {
        byte[] original = "original bytes".getBytes(StandardCharsets.UTF_8);
        String id = upload("notes.txt", "text/plain", original);
        String hashBefore = reloaded(id).getContentHash();
        TestUtils.endSession(false);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFile mf = mfMgr.getMediaFile(id);
        // What the edit controller sets before the manager checks the upload;
        // uploads.file.maxsize defaults to 2 MB, so ten is refused.
        mf.setContentType("image/png");
        mf.setLength(10L * 1024 * 1024);
        final Weblog weblog = testWeblog;
        assertThrows(FileIOException.class,
                () -> mfMgr.updateMediaFile(weblog, mf, new ByteArrayInputStream(new byte[] {1, 2, 3})));
        // the request ends without a commit, as PersistenceSessionFilter ends it
        TestUtils.endSession(false);

        MediaFile after = reloaded(id);
        assertEquals("text/plain", after.getContentType(),
                "a refused replacement must not commit the new type over the old bytes");
        assertEquals(original.length, after.getLength());
        assertEquals(hashBefore, after.getContentHash(), "the stored bytes did not change, nor may their hash");
        assertArrayEquals(original, stored(id));
    }

    @Test
    void aReplacementIsNotRefusedForCountingTheFileItReplaces() throws Exception {
        byte[] original = new byte[6000];
        Arrays.fill(original, (byte) 'a');
        String id = upload("big.txt", "text/plain", original);
        TestUtils.endSession(false);

        // ~10 KB: room for one 6000-byte file, not for two
        config().get("uploads.dir.maxsize").setValue("0.01");
        byte[] replacement = new byte[6000];
        Arrays.fill(replacement, (byte) 'b');
        replace(id, "text/plain", replacement);

        assertArrayEquals(replacement, stored(id));
    }

    // ------------------------------------------------------- derived siblings

    @Test
    void replacingAnImageRemovesTheRenditionsOfTheOldPixels() throws Exception {
        String id = upload("wide.jpg", "image/jpeg", jpeg(1000, 500));
        assertTrue(stored(id + "_960").length > 0, "fixture: a 1000px original has a 960 rung");

        replace(id, "image/jpeg", jpeg(300, 600));

        assertGone(id + "_480", "?w=480 must not keep serving the replaced image");
        assertGone(id + "_960", "?w=960 must not keep serving the replaced image");
        MediaFile after = reloaded(id);
        assertEquals(300, after.getWidth());
        assertEquals(600, after.getHeight());
        BufferedImage thumbnail = ImageIO.read(new ByteArrayInputStream(stored(id + "_sm")));
        assertEquals(60, thumbnail.getWidth(), "the thumbnail is the new, portrait image");
        assertEquals(120, thumbnail.getHeight());
    }

    @Test
    void replacingAnImageWithAnotherKindOfFileDropsEverythingDerivedFromThePicture() throws Exception {
        String id = upload("photo.jpg", "image/jpeg", resource("/hawk-exif.jpg"));
        MediaFile before = reloaded(id);
        assertNotNull(before.getBlurhash(), "fixture: the photo has a placeholder");
        assertNotNull(before.getExifCamera(), "fixture: the photo carries EXIF");
        TestUtils.endSession(false);

        replace(id, "application/pdf", "%PDF-1.4 not a picture".getBytes(StandardCharsets.UTF_8));

        assertGone(id + "_sm", "the admin thumbnail of the old picture must go");
        assertGone(id + "_480", "the renditions of the old picture must go");
        MediaFile after = reloaded(id);
        assertEquals(0, after.getWidth());
        assertEquals(0, after.getHeight());
        assertNull(after.getBlurhash(), "a placeholder of the old picture must not stand in for a PDF");
        assertNull(after.getExifCamera(), "the old picture's EXIF does not describe the new file");
        assertNull(after.getExifTaken());
    }
}
