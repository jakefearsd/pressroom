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
package com.wikantik.pressroom.business;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import javax.imageio.ImageIO;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.util.PressroomMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A new media file records the SHA-256 of its bytes; a content change clears
 * it, so a file that no longer holds those bytes never matches an import.
 * Fixtures follow {@link MediaFileCropTest}.
 */
public class MediaFileContentHashTest {

    private User user;
    private Weblog weblog;
    private MediaFileManager mfMgr;
    private MediaFileDirectory rootDirectory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setupWeblogger();
        Map<String, RuntimeConfigProperty> config = TestUtils.weblogger()
                .getPropertiesManager().getProperties();
        config.get("uploads.enabled").setValue("true");

        user = TestUtils.setupUser("contentHashTestUser");
        weblog = TestUtils.setupWeblog("contentHashTestWeblog", user);
        mfMgr = TestUtils.weblogger().getMediaFileManager();
        rootDirectory = mfMgr.getDefaultMediaFileDirectory(weblog);
        TestUtils.endSession(true);

        weblog = TestUtils.getManagedWebsite(weblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.endSession(true);
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------- fixtures

    private MediaFile upload(String name, String contentType, java.io.InputStream is)
            throws Exception {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setName(name);
        mediaFile.setDirectory(rootDirectory);
        mediaFile.setWeblog(weblog);
        mediaFile.setContentType(contentType);
        mediaFile.setInputStream(is);
        mfMgr.createMediaFile(weblog, mediaFile, new PressroomMessages());
        TestUtils.endSession(true);
        return mediaFile;
    }

    private static byte[] generatedJpeg(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    // ----------------------------------------------------------------- tests

    @Test
    void aNewFileRecordsTheHashOfItsBytes() throws Exception {
        byte[] bytes = generatedJpeg(40, 30);
        MediaFile file = upload("hash.jpg", "image/jpeg", new ByteArrayInputStream(bytes));

        MediaFile reread = mfMgr.getMediaFile(file.getId());
        assertEquals(ContentHashes.sha256Hex(bytes), reread.getContentHash());
        assertEquals(64, reread.getContentHash().length());
    }

    @Test
    void replacingTheContentClearsTheHash() throws Exception {
        MediaFile file = upload("replace.jpg", "image/jpeg", new ByteArrayInputStream(generatedJpeg(40, 30)));
        MediaFile managed = mfMgr.getMediaFile(file.getId());

        mfMgr.updateMediaFile(TestUtils.getManagedWebsite(weblog), managed,
                new ByteArrayInputStream(generatedJpeg(50, 50)));
        TestUtils.endSession(true);

        assertNull(mfMgr.getMediaFile(file.getId()).getContentHash());
    }

    @Test
    void croppingClearsTheHash() throws Exception {
        MediaFile file = upload("crop.jpg", "image/jpeg", new ByteArrayInputStream(generatedJpeg(80, 60)));
        MediaFile managed = mfMgr.getMediaFile(file.getId());

        mfMgr.cropMediaFile(TestUtils.getManagedWebsite(weblog), managed, 0, 0, 40, 30);
        TestUtils.endSession(true);

        assertNull(mfMgr.getMediaFile(file.getId()).getContentHash());
    }

    @Test
    void theLookupFindsAMatchOnlyInTheSameDirectory() throws Exception {
        byte[] bytes = generatedJpeg(40, 30);
        MediaFile file = upload("match.jpg", "image/jpeg", new ByteArrayInputStream(bytes));
        String hash = ContentHashes.sha256Hex(bytes);
        MediaFileDirectory other = mfMgr.createMediaFileDirectory(TestUtils.getManagedWebsite(weblog), "elsewhere");
        TestUtils.endSession(true);

        MediaFileDirectory root = mfMgr.getMediaFileDirectory(rootDirectory.getId());
        assertEquals(file.getId(), mfMgr.getMediaFileByContentHash(root, hash).getId());
        assertNull(mfMgr.getMediaFileByContentHash(mfMgr.getMediaFileDirectory(other.getId()), hash));
        assertNull(mfMgr.getMediaFileByContentHash(root, ContentHashes.sha256Hex(new byte[] {1})));
        assertNull(mfMgr.getMediaFileByContentHash(root, null));
    }
}
