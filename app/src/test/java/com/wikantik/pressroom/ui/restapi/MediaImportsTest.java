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
package com.wikantik.pressroom.ui.restapi;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import javax.imageio.ImageIO;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.CwebpEncoder;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.fetch.FetchedImage;
import com.wikantik.pressroom.business.fetch.ImageKind;
import com.wikantik.pressroom.business.fetch.RemoteImageFetcher;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Importing identical bytes twice keeps one file (criterion 13), against the real database. */
public class MediaImportsTest {

    private User user;
    private Weblog weblog;
    private MediaFileManager mfMgr;
    private MediaFileDirectory rootDirectory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setupWeblogger();
        // Force the deterministic ImageIO-only path, as MediaFileCropTest does.
        CwebpEncoder.setAvailableForTesting(false);

        Map<String, RuntimeConfigProperty> config = TestUtils.weblogger()
                .getPropertiesManager().getProperties();
        config.get("uploads.enabled").setValue("true");

        user = TestUtils.setupUser("mediaImportsTestUser");
        weblog = TestUtils.setupWeblog("mediaImportsTestWeblog", user);
        mfMgr = TestUtils.weblogger().getMediaFileManager();
        rootDirectory = mfMgr.getDefaultMediaFileDirectory(weblog);
        TestUtils.endSession(true);

        weblog = TestUtils.getManagedWebsite(weblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());
    }

    @AfterEach
    public void tearDown() throws Exception {
        CwebpEncoder.setAvailableForTesting(null);
        TestUtils.endSession(true);
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static byte[] generatedJpeg(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    @Test
    void theSameBytesImportedTwiceAre201Then200AndOneFile() throws Exception {
        byte[] bytes = generatedJpeg(40, 30);
        RemoteImageFetcher fetcher = mock(RemoteImageFetcher.class);
        when(fetcher.fetch(eq("https://img.example.com/a.jpg"), anyLong(), any()))
                .thenReturn(new FetchedImage("a.jpg", ImageKind.JPEG, bytes));
        Weblogger weblogger = TestUtils.weblogger();

        MediaImports.Imported first = MediaImports.importImage(weblogger, fetcher,
                TestUtils.getManagedWebsite(weblog), mfMgr.getMediaFileDirectory(rootDirectory.getId()),
                "https://img.example.com/a.jpg", null, null);
        TestUtils.endSession(true);
        MediaImports.Imported second = MediaImports.importImage(weblogger, fetcher,
                TestUtils.getManagedWebsite(weblog), mfMgr.getMediaFileDirectory(rootDirectory.getId()),
                "https://img.example.com/a.jpg", null, "Later alt");
        TestUtils.endSession(true);

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.file().getId(), second.file().getId());
        assertEquals(1, mfMgr.getMediaFileDirectory(rootDirectory.getId()).getMediaFiles().size());
        assertEquals("Later alt", mfMgr.getMediaFile(first.file().getId()).getAltText());
    }
}
