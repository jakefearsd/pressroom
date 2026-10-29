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
import java.util.Map;

import javax.imageio.ImageIO;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.CwebpEncoder;
import com.wikantik.pressroom.business.FileContentManager;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JPAMediaFileManagerImpl#regenerateRenditions} walking a weblog whose
 * files are not all healthy images, and {@link JPAMediaFileManagerImpl#cropMediaFile}
 * refusing a file that claims to be an image but is not one.
 *
 * <p>Characterisation tests: written against the existing behaviour and
 * expected to pass immediately, pinning it so a later change is noticed.
 */
class MediaRenditionRegenerationFailureTest {

    private static final byte[] NOT_AN_IMAGE = "definitely not a jpeg".getBytes(StandardCharsets.UTF_8);

    private User testUser;
    private Weblog testWeblog;
    private MediaFileManager mfMgr;
    private FileContentManager cmgr;

    @BeforeEach
    void setUp() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        TestUtils.setupWeblogger();
        testUser = TestUtils.setupUser("mfRegenFailureUser");
        testWeblog = TestUtils.setupWeblog("mfRegenFailureWeblog", testUser);
        TestUtils.endSession(true);
        mfMgr = TestUtils.weblogger().getMediaFileManager();
        cmgr = TestUtils.weblogger().getFileContentManager();
        Map<String, RuntimeConfigProperty> config =
                TestUtils.weblogger().getPropertiesManager().getProperties();
        config.get("uploads.enabled").setValue("true");
    }

    @AfterEach
    void tearDown() throws Exception {
        CwebpEncoder.setAvailableForTesting(null);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
        TestUtils.endSession(true);
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
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

    private byte[] stored(String fileId) throws Exception {
        try (InputStream in = cmgr.getFileContent(testWeblog, fileId).getInputStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    void regenerationCountsOnlyTheImagesItCouldRebuildAndSurvivesTheRest() throws Exception {
        upload("notes.txt", "text/plain", "not an image at all".getBytes(StandardCharsets.UTF_8));
        String bogusId = upload("bogus.jpg", "image/jpeg", NOT_AN_IMAGE);
        String lostId = upload("lost.jpg", "image/jpeg", jpeg(600, 400));
        String goodId = upload("good.jpg", "image/jpeg", jpeg(600, 400));

        // An image whose original has vanished from disk: the walk must log
        // and move on rather than abort.
        cmgr.deleteFile(testWeblog, lostId);
        // Drop the good image's rung so its regeneration is observable.
        cmgr.deleteFile(testWeblog, goodId + "_480");

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        int processed = mfMgr.regenerateRenditions(testWeblog);
        TestUtils.endSession(true);

        assertEquals(1, processed,
                "only good.jpg is rebuilt: the text file is skipped, bogus.jpg is not a "
                        + "readable image, and lost.jpg has no original to read");
        assertTrue(stored(goodId + "_480").length > 0, "the healthy image's rung is regenerated");
        assertArrayEquals(NOT_AN_IMAGE, stored(bogusId), "an unreadable original is left as it was");
        final Weblog weblog = testWeblog;
        assertThrows(FileNotFoundException.class, () -> cmgr.getFileContent(weblog, bogusId + "_480"),
                "no rendition is invented for an unreadable original");
    }

    @Test
    void croppingAFileThatIsNotReallyAnImageIsRefusedAndLeavesItUntouched() throws Exception {
        String id = upload("fake.jpg", "image/jpeg", NOT_AN_IMAGE);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFile mf = mfMgr.getMediaFile(id);
        final Weblog weblog = testWeblog;
        WebloggerException refused = assertThrows(WebloggerException.class,
                () -> mfMgr.cropMediaFile(weblog, mf, 0, 0, 10, 10));
        assertEquals("Media file " + id + " is not a readable image", refused.getMessage());
        TestUtils.endSession(true);

        assertArrayEquals(NOT_AN_IMAGE, stored(id));
    }
}
