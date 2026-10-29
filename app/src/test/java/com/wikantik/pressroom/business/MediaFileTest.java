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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.*;
import com.wikantik.pressroom.pojos.MediaFileFilter.MediaFileOrder;
import com.wikantik.pressroom.pojos.MediaFileFilter.SizeFilterType;
import com.wikantik.pressroom.util.PressroomMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test media file related business operations.
 */
public class MediaFileTest  {

    private static final Logger log = LoggerFactory.getLogger(MediaFileTest.class);
    // static final String runtimeEnv;
    public final static String TEST_IMAGE = "/hawk.jpg";

    public MediaFileTest() {
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setupWeblogger();
        // allow media uploads for this test
        Map<String, RuntimeConfigProperty> config = TestUtils.weblogger().getPropertiesManager().getProperties();
        config.get("uploads.enabled").setValue("true");
    }

    /**
     * Test creation of directory by path
     */
    @Test
    public void testCreateMediaFileDirectoryByPath() throws Exception {
        User testUser = null;
        Weblog testWeblog = null;

        // TODO: Setup code, to be moved to setUp method.
        log.info("Before setting up weblogger");
        // setup weblogger
        try {
            testUser = TestUtils.setupUser("mediaFileTestUser8");
            testWeblog = TestUtils
                    .setupWeblog("mediaFileTestWeblog8", testUser);
            TestUtils.endSession(true);
        } catch (Exception ex) {
            log.error("ERROR in test setup", ex);
            throw new Exception("Test setup failed", ex);
        }

        /*
         * Real test starts here.
         */
        MediaFileManager mfMgr = TestUtils.weblogger()
                .getMediaFileManager();

        testWeblog = TestUtils.getManagedWebsite(testWeblog);

        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);

        try {
            mfMgr.createMediaFileDirectory(testWeblog, "");
            assertTrue(false);
        } catch (WebloggerException e) {
            assertTrue(true);
        }

        try {
            mfMgr.createMediaFileDirectory(testWeblog, "default");
            assertTrue(false);
        } catch (WebloggerException e) {
            assertTrue(true);
        }

        MediaFileDirectory newDirectory1 = mfMgr
                .createMediaFileDirectory(testWeblog, "test1");
        MediaFileDirectory newDirectory2 = mfMgr
                .createMediaFileDirectory(testWeblog, "test2");
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);

        MediaFileDirectory newDirectory1ById = mfMgr
                .getMediaFileDirectory(newDirectory1.getId());
        assertEquals(newDirectory1, newDirectory1ById);

        MediaFileDirectory newDirectory2ById = mfMgr
                .getMediaFileDirectory(newDirectory2.getId());
        assertEquals("test2", newDirectory2ById.getName());

        // show throw error when creating directory that already exists
        try {
            mfMgr.createMediaFileDirectory(testWeblog, "test1");
            assertTrue(false);
        } catch (WebloggerException e) {
            assertTrue(true);
        }

        TestUtils.endSession(true);
        testWeblog = TestUtils.getManagedWebsite(testWeblog);

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
        TestUtils.endSession(true);
    }

    /**
     * Test directory creation
     */
    @Test
    public void testCreateMediaFileDirectory() throws Exception {
        User testUser = null;
        Weblog testWeblog = null;

        // TODO: Setup code, to be moved to setUp method.
        log.info("Before setting up weblogger");
        // setup weblogger
        try {
            testUser = TestUtils.setupUser("mediaFileTestUser");
            testWeblog = TestUtils.setupWeblog("mediaFileTestWeblog", testUser);
            TestUtils.endSession(true);
        } catch (Exception ex) {
            log.error("ERROR in test setup", ex);
            throw new Exception("Test setup failed", ex);
        }

        /**
         * Real test starts here.
         */
        MediaFileManager mfMgr = TestUtils.weblogger()
                .getMediaFileManager();

        testWeblog = TestUtils.getManagedWebsite(testWeblog);

        // no need to create root directory, that is done automatically now
        MediaFileDirectory directory = mfMgr
                .getDefaultMediaFileDirectory(testWeblog);

        TestUtils.endSession(true);

        MediaFileDirectory directoryById = mfMgr
                .getMediaFileDirectory(directory.getId());
        assertEquals(directory, directoryById);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFileDirectory rootDirectory = mfMgr
                .getDefaultMediaFileDirectory(testWeblog);
        assertEquals(directory, rootDirectory);

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
        TestUtils.endSession(true);
    }

    /**
     * Test getting list of all directories for a given user.
     */
    @Test
    public void testGetMediaFileDirectories() throws Exception {

        User testUser = null;
        Weblog testWeblog = null;
        testUser = TestUtils.setupUser("mediaFileTestUser2");
        testWeblog = TestUtils.setupWeblog("mediaFileTestWeblog2", testUser);

        MediaFileManager mfMgr = TestUtils.weblogger()
                .getMediaFileManager();

        // no need to create root directory, that is done automatically now
        MediaFileDirectory rootDirectory = mfMgr
                .getDefaultMediaFileDirectory(testWeblog);

        MediaFileDirectory directory2 = new MediaFileDirectory(testWeblog,
                "dir2", "directory 2" );
        mfMgr.createMediaFileDirectory(directory2);

        MediaFileDirectory directory3 = new MediaFileDirectory(testWeblog,
                "dir3", "directory 3");
        mfMgr.createMediaFileDirectory(directory3);

        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        List<MediaFileDirectory> directories = mfMgr
                .getMediaFileDirectories(testWeblog);
        assertNotNull(directories);
        assertEquals(3, directories.size());
        assertTrue(containsName(directories, "default"));
        assertTrue(containsName(directories, "dir2"));
        assertTrue(containsName(directories, "dir3"));

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
        TestUtils.endSession(true);
    }

    /**
     * Test utility to determine whether the given list of directories contains
     * a directory of given path.
     * 
     */
    private boolean containsName(Collection<MediaFileDirectory> directories,
            String name) {
        for (MediaFileDirectory directory : directories) {
            if (name.equals(directory.getName())) {
                return true;
            }
        }
        return false;

    }

    /**
     * Test utility to determine whether the list of files contains a file with
     * given name.
     * 
     */
    private boolean containsFileWithName(Collection<MediaFile> files,
            String name) {
        for (MediaFile file : files) {
            if (name.equals(file.getName())) {
                return true;
            }
        }
        return false;

    }

    /**
     * Test deletion of media file
     */
    @Test
    public void testDeleteMediaFile() throws Exception {
        User testUser;
        Weblog testWeblog;
        testUser = TestUtils.setupUser("mediaFileTestUser4");
        testWeblog = TestUtils.setupWeblog("mediaFileTestWeblog4", testUser);

        MediaFileManager mfMgr = TestUtils.weblogger()
                .getMediaFileManager();

        // no need to create root directory, that is done automatically now
        MediaFileDirectory rootDirectory = mfMgr
                .getDefaultMediaFileDirectory(testWeblog);

        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        MediaFile mediaFile = new MediaFile();
        mediaFile.setName("test4.jpg");
        mediaFile.setDescription("This is a test image 4");
        mediaFile.setCopyrightText("test 4 copyright text");
        mediaFile.setLength(3000);
        mediaFile.setDirectory(rootDirectory);
        mediaFile.setWeblog(testWeblog);
        mediaFile.setContentType("image/jpeg");
        mediaFile.setInputStream(getClass().getResourceAsStream(TEST_IMAGE));

        // Add tags
        TestUtils.setMediaTags(mediaFile, "tst4work tst4home");

        mfMgr.createMediaFile(testWeblog, mediaFile, new PressroomMessages());
        String id = mediaFile.getId();
        TestUtils.endSession(true);
        assertNotNull(id);
        assertNotNull(id.length() > 0);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFile mediaFile1 = mfMgr.getMediaFile(id);

        assertEquals("test4.jpg", mediaFile1.getName());
        assertNotNull(mediaFile1.getTags());
        assertEquals(2, mediaFile1.getTags().size());

        try {
            mfMgr.removeMediaFile(testWeblog, mediaFile1);
        } catch (Exception ignorable) {
            log.debug("ERROR removing media file", ignorable);
        }
        TestUtils.endSession(true);

        MediaFile mediaFile2 = mfMgr.getMediaFile(id);
        assertNull(mediaFile2);

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());

        TestUtils.endSession(true);
    }

    /**
     * Test creation of media file.
     */
    @Test
    public void testCreateMediaFile() throws Exception {

        User testUser;
        Weblog testWeblog;
        testUser = TestUtils.setupUser("mediaFileTestUser3");
        testWeblog = TestUtils.setupWeblog("mediaFileTestWeblog3", testUser);

        MediaFileManager mfMgr = TestUtils.weblogger()
                .getMediaFileManager();

        // no need to create root directory, that is done automatically now
        MediaFileDirectory rootDirectory = mfMgr
                .getDefaultMediaFileDirectory(testWeblog);

        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        MediaFile mediaFile = new MediaFile();
        mediaFile.setName("test.jpg");
        mediaFile.setDescription("This is a test image");
        mediaFile.setCopyrightText("test copyright text");
        mediaFile.setLength(2000);
        mediaFile.setDirectory(rootDirectory);
        mediaFile.setWeblog(testWeblog);
        mediaFile.setInputStream(getClass().getResourceAsStream(TEST_IMAGE));
        mediaFile.setContentType("image/jpeg");
        rootDirectory.getMediaFiles().add(mediaFile);

        // Add tags
        TestUtils.setMediaTags(mediaFile, "work home");

        mfMgr.createMediaFile(testWeblog, mediaFile, new PressroomMessages());
        TestUtils.endSession(true);
        assertNotNull(mediaFile.getId());
        assertNotNull(mediaFile.getId().length() > 0);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFile mediaFile1 = mfMgr.getMediaFile(mediaFile.getId());
        assertEquals("test.jpg", mediaFile1.getName());
        assertEquals("This is a test image", mediaFile1.getDescription());
        assertEquals("test copyright text", mediaFile1.getCopyrightText());
        assertEquals(2000, mediaFile1.getLength());

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    /**
     * Test searching media file.
     */
    @Test
    public void testSearchMediaFile() throws Exception {
        User testUser = null;
        Weblog testWeblog = null;
        testUser = TestUtils.setupUser("mediaFileTestUser7");
        testWeblog = TestUtils.setupWeblog("mediaFileTestWeblog7", testUser);

        MediaFileManager mfMgr = TestUtils.weblogger()
                .getMediaFileManager();

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFileDirectory rootDirectory = mfMgr
                .getDefaultMediaFileDirectory(testWeblog);

        try {

            String id1 = null;
            {
                MediaFile mf = new MediaFile();
                mf.setName("test_work.jpg");
                mf.setDescription("This is a test image");
                mf.setCopyrightText("test copyright text");
                mf.setLength(2000);
                mf.setDirectory(rootDirectory);
                mf.setWeblog(testWeblog);
                mf.setInputStream(getClass().getResourceAsStream(TEST_IMAGE));
                mf.setContentType("image/jpeg");
                rootDirectory.getMediaFiles().add(mf);

                mfMgr.createMediaFile(testWeblog, mf, new PressroomMessages());

                // Add tags
                TestUtils.setMediaTags(mf, "work");
                mfMgr.updateMediaFile(testWeblog, mf);

                mfMgr.createMediaFile(testWeblog, mf, new PressroomMessages());
                TestUtils.endSession(true);
                id1 = mf.getId();
                assertNotNull(mf.getId());
                assertNotNull(mf.getId().length() > 0);
            }

            String id2 = null;
            {
                testWeblog = TestUtils.getManagedWebsite(testWeblog);
                rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory
                        .getId());

                MediaFile mf = new MediaFile();
                mf = new MediaFile();
                mf.setName("test_home.jpg");
                mf.setDescription("This is a test image");
                mf.setCopyrightText("test copyright text");
                mf.setLength(3000);
                mf.setDirectory(rootDirectory);
                mf.setWeblog(testWeblog);
                mf.setInputStream(getClass().getResourceAsStream(TEST_IMAGE));
                mf.setContentType("image/jpeg");
                rootDirectory.getMediaFiles().add(mf);

                mfMgr.createMediaFile(testWeblog, mf, new PressroomMessages());

                // Add tags
                TestUtils.setMediaTags(mf, "home");
                mfMgr.updateMediaFile(testWeblog, mf);

                TestUtils.endSession(true);
                id2 = mf.getId();
                assertNotNull(mf.getId());
                assertNotNull(mf.getId().length() > 0);
            }

            String id3 = null;
            {
                testWeblog = TestUtils.getManagedWebsite(testWeblog);
                rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory
                        .getId());

                MediaFile mf = new MediaFile();
                mf = new MediaFile();
                mf.setName("test_pers.jpg");
                mf.setDescription("This is a personal test image");
                mf.setCopyrightText("test pers copyright text");
                mf.setLength(4000);
                mf.setWeblog(testWeblog);
                mf.setDirectory(rootDirectory);
                mf.setInputStream(getClass().getResourceAsStream(TEST_IMAGE));
                mf.setContentType("image/jpeg");
                rootDirectory.getMediaFiles().add(mf);

                mfMgr.createMediaFile(testWeblog, mf, new PressroomMessages());

                // Add tags
                TestUtils.setMediaTags(mf, "home");
                mfMgr.updateMediaFile(testWeblog, mf);

                TestUtils.endSession(true);
                id3 = mf.getId();
                assertNotNull(mf.getId());
                assertNotNull(mf.getId().length() > 0);
            }

            testWeblog = TestUtils.getManagedWebsite(testWeblog);

            List<MediaFile> searchResults;

            // search by name

            MediaFileFilter filter1 = new MediaFileFilter();
            filter1.setName("mytest.jpg");
            searchResults = mfMgr.searchMediaFiles(testWeblog, filter1);
            assertTrue(searchResults.isEmpty());

            MediaFileFilter filter2 = new MediaFileFilter();
            filter2.setName("test_home.jpg");
            searchResults = mfMgr.searchMediaFiles(testWeblog, filter2);
            assertFalse(searchResults.isEmpty());
            assertEquals(id2, (searchResults.get(0)).getId());
            assertNotNull((searchResults.get(0)).getDirectory());
            assertEquals("default", searchResults.get(0).getDirectory().getName());

            MediaFileFilter filter3 = new MediaFileFilter();
            filter3.setName("test_work.jpg");
            searchResults = mfMgr.searchMediaFiles(testWeblog, filter3);
            assertFalse(searchResults.isEmpty());
            assertEquals(id1, searchResults.get(0).getId());

            // search by tag

            // must be tickling an OpenJPA bug. this tag query works the
            // first time and then fails the second time it is run

            // MediaFileFilter filter5 = new MediaFileFilter();
            // filter5.setTags(Arrays.asList("home"));
            // searchResults = mfMgr.searchMediaFiles(testWeblog, filter5);
            // assertFalse(searchResults.isEmpty());
            // assertEquals(2, searchResults.size());
            //
            // MediaFileFilter filter51 = new MediaFileFilter();
            // filter51.setTags(Arrays.asList("home"));
            // searchResults = mfMgr.searchMediaFiles(testWeblog, filter51);
            // assertFalse(searchResults.isEmpty());
            // assertEquals(2, searchResults.size());

            MediaFileFilter filter4 = new MediaFileFilter();
            filter4.setTags(Arrays.asList("work"));
            searchResults = mfMgr.searchMediaFiles(testWeblog, filter4);
            assertFalse(searchResults.isEmpty());
            assertEquals(1, searchResults.size());
            assertEquals("test_work.jpg", searchResults.get(0).getName());

            // search by size

            MediaFileFilter filter6 = new MediaFileFilter();
            filter6.setSize(3000);
            filter6.setSizeFilterType(MediaFileFilter.SizeFilterType.LT);
            searchResults = mfMgr.searchMediaFiles(testWeblog, filter6);
            assertFalse(searchResults.isEmpty());
            assertEquals(1, searchResults.size());
            assertEquals("test_work.jpg", searchResults.get(0).getName());

            MediaFileFilter filter7 = new MediaFileFilter();
            filter7.setSize(3000);
            filter7.setSizeFilterType(MediaFileFilter.SizeFilterType.EQ);
            searchResults = mfMgr.searchMediaFiles(testWeblog, filter7);
            assertFalse(searchResults.isEmpty());
            assertEquals(1, searchResults.size());
            assertEquals("test_home.jpg", searchResults.get(0).getName());

            MediaFileFilter filter8 = new MediaFileFilter();
            filter8.setSize(3000);
            filter8.setSizeFilterType(MediaFileFilter.SizeFilterType.GT);
            searchResults = mfMgr.searchMediaFiles(testWeblog, filter8);
            assertFalse(searchResults.isEmpty());
            assertEquals(1, searchResults.size());
            assertEquals("test_pers.jpg", searchResults.get(0).getName());

            MediaFileFilter filter9 = new MediaFileFilter();
            filter9.setSize(3000);
            filter9.setSizeFilterType(MediaFileFilter.SizeFilterType.LTE);
            searchResults = mfMgr.searchMediaFiles(testWeblog, filter9);
            assertFalse(searchResults.isEmpty());
            assertEquals(2, searchResults.size());

            MediaFileFilter filter10 = new MediaFileFilter();
            filter10.setSize(3000);
            filter10.setSizeFilterType(MediaFileFilter.SizeFilterType.GTE);
            searchResults = mfMgr.searchMediaFiles(testWeblog, filter10);
            assertFalse(searchResults.isEmpty());
            assertEquals(2, searchResults.size());

            // search by type

            MediaFileFilter filter11 = new MediaFileFilter();
            filter11.setType(MediaFileType.IMAGE);
            searchResults = mfMgr.searchMediaFiles(testWeblog, filter11);
            assertFalse(searchResults.isEmpty());
            assertEquals(3, searchResults.size());

            MediaFileFilter filter12 = new MediaFileFilter();
            filter12.setType(MediaFileType.IMAGE);
            filter12.setTags(Arrays.asList("home"));
            searchResults = mfMgr.searchMediaFiles(testWeblog, filter12);
            assertFalse(searchResults.isEmpty());
            assertEquals(2, searchResults.size());

        } finally {
            TestUtils.endSession(true);
            TestUtils.teardownWeblog(testWeblog.getId());
            TestUtils.teardownUser(testUser.getUserName());
        }

    }

    /**
     * Test searching media file with paging logic.
     */
    @Test
    public void testSearchMediaFilePaging() throws Exception {
        User testUser = null;
        Weblog testWeblog = null;
        testUser = TestUtils.setupUser("mediaFileTestUser9");
        testWeblog = TestUtils.setupWeblog("mediaFileTestWeblog9", testUser);

        try {
            MediaFileManager mfMgr = TestUtils.weblogger()
                    .getMediaFileManager();

            // no need to create root directory, that is done automatically now
            MediaFileDirectory rootDirectory = mfMgr
                    .getDefaultMediaFileDirectory(testWeblog);

            for (int i = 0; i < 15; i++) {
                rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory
                        .getId());
                testWeblog = TestUtils.getManagedWebsite(testWeblog);
                MediaFile mediaFile = new MediaFile();
                // Zero-padded so the ORDER BY m.name paging below sorts the
                // same way under any database collation. Unpadded names put
                // test_file10 before test_file1 under en_US.UTF-8 but after it
                // under a binary collation, which made these assertions
                // database-specific.
                mediaFile.setName(String.format("test_file%02d.jpg", i));
                mediaFile.setDescription("This is a test image");
                mediaFile.setCopyrightText("test copyright text");
                mediaFile.setLength(2000);
                mediaFile.setWeblog(testWeblog);
                mediaFile.setInputStream(getClass().getResourceAsStream(
                        TEST_IMAGE));
                mediaFile.setContentType("image/jpeg");
                mediaFile.setDirectory(rootDirectory);
                mfMgr.createMediaFile(testWeblog, mediaFile,
                        new PressroomMessages());
                rootDirectory.getMediaFiles().add(mediaFile);
                assertNotNull(mediaFile.getId());
                assertNotNull(mediaFile.getId().length() > 0);
                TestUtils.endSession(true);
            }

            testWeblog = TestUtils.getManagedWebsite(testWeblog);
            MediaFileFilter filter1 = new MediaFileFilter();
            filter1.setSize(1000);
            filter1.setSizeFilterType(SizeFilterType.GT);
            List<MediaFile> searchResults1 = mfMgr.searchMediaFiles(testWeblog,
                    filter1);
            assertFalse(searchResults1.isEmpty());
            assertEquals(15, searchResults1.size());

            MediaFileFilter filter2 = new MediaFileFilter();
            filter2.setSize(1000);
            filter2.setSizeFilterType(SizeFilterType.GT);
            filter2.setStartIndex(5);
            filter2.setLength(3);
            List<MediaFile> searchResults2 = mfMgr.searchMediaFiles(testWeblog,
                    filter2);
            assertFalse(searchResults2.isEmpty());
            assertEquals(3, searchResults2.size());
            assertEquals("test_file05.jpg", searchResults2.get(0).getName());

            MediaFileFilter filter3 = new MediaFileFilter();
            filter3.setSize(1000);
            filter3.setSizeFilterType(SizeFilterType.GT);
            filter3.setStartIndex(13);
            filter3.setLength(6);
            List<MediaFile> searchResults3 = mfMgr.searchMediaFiles(testWeblog,
                    filter3);
            assertFalse(searchResults3.isEmpty());
            assertEquals(2, searchResults3.size());
            assertEquals("test_file13.jpg", searchResults3.get(0).getName());

            MediaFileFilter filter4 = new MediaFileFilter();
            filter4.setSize(1000);
            filter4.setSizeFilterType(SizeFilterType.GT);
            filter4.setStartIndex(14);
            filter4.setLength(1);
            List<MediaFile> searchResults4 = mfMgr.searchMediaFiles(testWeblog,
                    filter4);
            assertFalse(searchResults4.isEmpty());
            assertEquals(1, searchResults4.size());
            assertEquals("test_file14.jpg", searchResults4.get(0).getName());

            TestUtils.endSession(true);
        } finally {
            TestUtils.teardownWeblog(testWeblog.getId());
            TestUtils.teardownUser(testUser.getUserName());
        }
    }

    /**
     * Test searching media file with paging logic.
     */
    @Test
    public void testSearchMediaFileOrderBy() throws Exception {
        User testUser = null;
        Weblog testWeblog = null;
        testUser = TestUtils.setupUser("mediaFileTestUser10");
        testWeblog = TestUtils.setupWeblog("mediaFileTestWeblog10", testUser);

        MediaFileManager mfMgr = TestUtils.weblogger()
                .getMediaFileManager();

        // no need to create root directory, that is done automatically now
        MediaFileDirectory rootDirectory = mfMgr
                .getDefaultMediaFileDirectory(testWeblog);

        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());
        String[] contentTypes = { "image/gif", "image/jpeg", "image/bmp" };
        Calendar cal = Calendar.getInstance();
        cal.setTime(new Timestamp(System.currentTimeMillis()));
        for (int i = 0; i < 3; i++) {
            MediaFile mediaFile = new MediaFile();
            mediaFile
                    .setName("test_file<index>.jpg".replace("<index>", i + ""));
            mediaFile.setDescription("This is a test image");
            mediaFile.setCopyrightText("test copyright text");
            mediaFile.setLength(2000);
            mediaFile.setDirectory(rootDirectory);
            mediaFile.setWeblog(testWeblog);
            mediaFile
                    .setInputStream(getClass().getResourceAsStream(TEST_IMAGE));
            mediaFile.setContentType(contentTypes[i]);

            mediaFile.setDateUploaded(new Timestamp(cal.getTimeInMillis()));
            // Add one second for date sql on mysql
            cal.add(Calendar.SECOND, 1);
            mfMgr.createMediaFile(testWeblog, mediaFile, new PressroomMessages());
            rootDirectory.getMediaFiles().add(mediaFile);
            assertNotNull(mediaFile.getId());
            assertNotNull(mediaFile.getId().length() > 0);
        }
        TestUtils.endSession(true);
        testWeblog = TestUtils.getManagedWebsite(testWeblog);

        MediaFileFilter filter1 = new MediaFileFilter();
        filter1.setSize(1000);
        filter1.setSizeFilterType(SizeFilterType.GT);
        filter1.setOrder(MediaFileOrder.NAME);
        List<MediaFile> searchResults1 = mfMgr.searchMediaFiles(testWeblog,
                filter1);
        assertFalse(searchResults1.isEmpty());
        assertEquals(3, searchResults1.size());
        assertEquals("test_file0.jpg", searchResults1.get(0).getName());
        assertEquals("test_file1.jpg", searchResults1.get(1).getName());
        assertEquals("test_file2.jpg", searchResults1.get(2).getName());

        MediaFileFilter filter2 = new MediaFileFilter();
        filter2.setSize(1000);
        filter2.setSizeFilterType(SizeFilterType.GT);
        filter2.setOrder(MediaFileOrder.TYPE);
        List<MediaFile> searchResults2 = mfMgr.searchMediaFiles(testWeblog,
                filter2);
        assertFalse(searchResults2.isEmpty());
        assertEquals(3, searchResults2.size());
        assertEquals("test_file2.jpg", searchResults2.get(0).getName());
        assertEquals("test_file0.jpg", searchResults2.get(1).getName());
        assertEquals("test_file1.jpg", searchResults2.get(2).getName());

        MediaFileFilter filter3 = new MediaFileFilter();
        filter3.setSize(1000);
        filter3.setSizeFilterType(SizeFilterType.GT);
        filter3.setOrder(MediaFileOrder.DATE_UPLOADED);
        List<MediaFile> searchResults3 = mfMgr.searchMediaFiles(testWeblog,
                filter3);
        assertFalse(searchResults3.isEmpty());
        assertEquals(3, searchResults3.size());
        assertEquals("test_file0.jpg", searchResults3.get(0).getName());
        assertEquals("test_file1.jpg", searchResults3.get(1).getName());
        assertEquals("test_file2.jpg", searchResults3.get(2).getName());

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    /**
     * Test media file update
     */
    @Test
    public void testUpdateMediaFile() throws Exception {
        User testUser = null;
        Weblog testWeblog = null;
        testUser = TestUtils.setupUser("mediaFileTestUser5");
        testWeblog = TestUtils.setupWeblog("mediaFileTestWeblog5", testUser);

        MediaFileManager mfMgr = TestUtils.weblogger()
                .getMediaFileManager();

        // no need to create root directory, that is done automatically now
        MediaFileDirectory rootDirectory = mfMgr
                .getDefaultMediaFileDirectory(testWeblog);

        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        MediaFile mediaFile = new MediaFile();
        mediaFile.setName("test5.jpg");
        mediaFile.setDescription("This is a test image 5");
        mediaFile.setCopyrightText("test 5 copyright text");
        mediaFile.setLength(3000);
        mediaFile.setDirectory(rootDirectory);
        mediaFile.setWeblog(testWeblog);
        mediaFile.setInputStream(getClass().getResourceAsStream(TEST_IMAGE));
        mediaFile.setContentType("image/jpeg");

        // Add tags
        TestUtils.setMediaTags(mediaFile, "tst5work tst5home");

        mfMgr.createMediaFile(testWeblog, mediaFile, new PressroomMessages());
        rootDirectory.getMediaFiles().add(mediaFile);
        String id = mediaFile.getId();
        TestUtils.endSession(true);
        assertNotNull(id);
        assertNotNull(id.length() > 0);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFile mediaFile1 = mfMgr.getMediaFile(id);
        mediaFile1.setWeblog(testWeblog);
        mediaFile1.setName("updated.gif");
        mediaFile1.setDescription("updated desc");
        mediaFile1.setCopyrightText("updated copyright");
        mediaFile1.setContentType("image/gif");
        mfMgr.updateMediaFile(testWeblog, mediaFile1);
        TestUtils.endSession(true);

        MediaFile mediaFile2 = mfMgr.getMediaFile(id);
        assertEquals("updated.gif", mediaFile2.getName());
        assertEquals("updated desc", mediaFile2.getDescription());
        assertEquals("updated copyright", mediaFile2.getCopyrightText());
        assertEquals("image/gif", mediaFile2.getContentType());
        assertNotNull(mediaFile2.getTags());
        assertEquals(2, mediaFile2.getTags().size());

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    /**
     * Test media file and directory gets
     */
    @Test
    public void testGetDirectoryContents() throws Exception {
        User testUser = null;
        Weblog testWeblog = null;
        testUser = TestUtils.setupUser("mediaFileTestUser6");
        testWeblog = TestUtils.setupWeblog("mediaFileTestWeblog6", testUser);

        MediaFileManager mfMgr = TestUtils.weblogger()
                .getMediaFileManager();

        // no need to create root directory, that is done automatically now
        MediaFileDirectory rootDirectory = mfMgr
                .getDefaultMediaFileDirectory(testWeblog);

        MediaFileDirectory directory1 = new MediaFileDirectory(testWeblog,
                "dir1", "directory 1");
        mfMgr.createMediaFileDirectory(directory1);

        MediaFileDirectory directory2 = new MediaFileDirectory(testWeblog,
                "dir2", "directory 2");
        mfMgr.createMediaFileDirectory(directory2);

        MediaFileDirectory directory3 = new MediaFileDirectory(testWeblog,
                "dir3", "directory 3");
        mfMgr.createMediaFileDirectory(directory3);

        TestUtils.endSession(true);
        
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        MediaFile mediaFile = new MediaFile();
        mediaFile.setDirectory(rootDirectory);
        mediaFile.setWeblog(testWeblog);
        mediaFile.setName("test6_1.jpg");
        mediaFile.setDescription("This is a test image 6.1");
        mediaFile.setCopyrightText("test 6.1 copyright text");
        mediaFile.setLength(4000);
        mediaFile.setInputStream(getClass().getResourceAsStream(TEST_IMAGE));
        mediaFile.setContentType("image/jpeg");
        mfMgr.createMediaFile(testWeblog, mediaFile, new PressroomMessages());
        //rootDirectory.getMediaFiles().add(mediaFile);

        MediaFile mediaFile2 = new MediaFile();
        mediaFile2.setDirectory(rootDirectory);
        mediaFile2.setWeblog(testWeblog);
        mediaFile2.setName("test6_2.jpg");
        mediaFile2.setDescription("This is a test image 6.2");
        mediaFile2.setCopyrightText("test 6.2 copyright text");
        mediaFile2.setLength(4000);
        mediaFile2.setInputStream(getClass().getResourceAsStream(TEST_IMAGE));
        mediaFile2.setContentType("image/jpeg");
        mfMgr.createMediaFile(testWeblog, mediaFile2, new PressroomMessages());
        //rootDirectory.getMediaFiles().add(mediaFile2);

        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        List<MediaFileDirectory> childDirectories = testWeblog
                .getMediaFileDirectories();
        assertEquals(4, childDirectories.size());
        assertTrue(containsName(childDirectories, "dir1"));
        assertTrue(containsName(childDirectories, "dir2"));
        assertTrue(containsName(childDirectories, "dir3"));

        Set<MediaFile> mediaFiles = rootDirectory.getMediaFiles();
        assertEquals(2, mediaFiles.size());
        assertTrue(containsFileWithName(mediaFiles, "test6_1.jpg"));
        assertTrue(containsFileWithName(mediaFiles, "test6_2.jpg"));
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());
        assertTrue(rootDirectory.hasMediaFile("test6_1.jpg"));
        assertTrue(rootDirectory.hasMediaFile("test6_2.jpg"));

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    /**
     * Test moving files across directories.
     */
    @Test
    public void testMoveDirectoryContents() throws Exception {

        User testUser = null;
        Weblog testWeblog = null;
        testUser = TestUtils.setupUser("mediaFileTestUser11");
        testWeblog = TestUtils.setupWeblog("mediaFileTestUser11", testUser);

        MediaFileManager mfMgr = TestUtils.weblogger()
                .getMediaFileManager();

        // no need to create root directory, that is done automatically now
        MediaFileDirectory rootDirectory = mfMgr
                .getDefaultMediaFileDirectory(testWeblog);

        try {

            MediaFileDirectory directory1 = new MediaFileDirectory(
                    testWeblog, "dir1", "directory 1");
            mfMgr.createMediaFileDirectory(directory1);
            String dir1Id = directory1.getId();

            MediaFileDirectory directory2 = new MediaFileDirectory(
                    testWeblog, "dir2", "directory 2");
            mfMgr.createMediaFileDirectory(directory2);

            MediaFileDirectory directory3 = new MediaFileDirectory(
                    testWeblog, "dir3", "directory 3");
            mfMgr.createMediaFileDirectory(directory3);
            //rootDirectory.getChildDirectories().add(directory3);
            
            TestUtils.endSession(true);
            
            testWeblog = TestUtils.getManagedWebsite(testWeblog);
            rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

            MediaFile mediaFile = new MediaFile();
            mediaFile.setDirectory(rootDirectory);
            mediaFile.setWeblog(testWeblog);
            mediaFile.setName("test7_1.jpg");
            mediaFile.setDescription("This is a test image 7.1");
            mediaFile.setCopyrightText("test 7.1 copyright text");
            mediaFile.setLength(4000);
            mediaFile.setInputStream(getClass().getResourceAsStream(TEST_IMAGE));
            mediaFile.setContentType("image/jpeg");
            mfMgr.createMediaFile(testWeblog, mediaFile, new PressroomMessages());
            //rootDirectory.getMediaFiles().add(mediaFile);

            MediaFile mediaFile2 = new MediaFile();
            mediaFile2.setDirectory(rootDirectory);
            mediaFile2.setWeblog(testWeblog);
            mediaFile2.setName("test7_2.jpg");
            mediaFile2.setDescription("This is a test image 7.2");
            mediaFile2.setCopyrightText("test 7.2 copyright text");
            mediaFile2.setLength(4000);
            mediaFile2.setInputStream(getClass()
                    .getResourceAsStream(TEST_IMAGE));
            mediaFile2.setContentType("image/jpeg");
            mfMgr.createMediaFile(testWeblog, mediaFile2, new PressroomMessages());
            //rootDirectory.getMediaFiles().add(mediaFile2);

            TestUtils.endSession(true);

            testWeblog = TestUtils.getManagedWebsite(testWeblog);
            rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

            Set<MediaFile> mediaFiles = rootDirectory.getMediaFiles();
            assertEquals(2, mediaFiles.size());
            assertTrue(containsFileWithName(mediaFiles, "test7_1.jpg"));
            assertTrue(containsFileWithName(mediaFiles, "test7_2.jpg"));

            MediaFileDirectory targetDirectory = mfMgr
                    .getMediaFileDirectory(dir1Id);
            mfMgr.moveMediaFiles(mediaFiles, targetDirectory);
            TestUtils.endSession(true);

            rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());
            targetDirectory = mfMgr.getMediaFileDirectory(dir1Id);

            mediaFiles = targetDirectory.getMediaFiles();
            assertEquals(2, mediaFiles.size());
            assertTrue(containsFileWithName(mediaFiles, "test7_1.jpg"));
            assertTrue(containsFileWithName(mediaFiles, "test7_2.jpg"));

            mediaFiles = rootDirectory.getMediaFiles();
            assertEquals(0, mediaFiles.size());

        } finally {
            TestUtils.endSession(true);
            TestUtils.teardownWeblog(testWeblog.getId());
            TestUtils.teardownUser(testUser.getUserName());
            TestUtils.endSession(true);
        }
    }

    /**
     * Test deletion of media file folder association with named queries
     * 
     * This test fails but it should not, so Z'ed out not to run.
     */
    public void testDirectoryDeleteAssociation() throws Exception {

        User testUser = null;
        Weblog testWeblog = null;
        testUser = TestUtils.setupUser("mediaFileTestUser12");
        testWeblog = TestUtils.setupWeblog("mediaFileTestWeblog12", testUser);

        MediaFileManager mfMgr = TestUtils.weblogger()
                .getMediaFileManager();

        // no need to create root directory, that is done automatically now
        MediaFileDirectory rootDirectory = mfMgr
                .getDefaultMediaFileDirectory(testWeblog);

        MediaFileDirectory directory1 = new MediaFileDirectory(testWeblog,
                "dir1", "directory 1");
        mfMgr.createMediaFileDirectory(directory1);

        MediaFileDirectory directory2 = new MediaFileDirectory(testWeblog,
                "dir2", "directory 2");
        mfMgr.createMediaFileDirectory(directory2);

        MediaFileDirectory directory3 = new MediaFileDirectory(testWeblog,
                "dir3", "directory 3");
        mfMgr.createMediaFileDirectory(directory3);

        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        List<MediaFileDirectory> childDirectories = testWeblog.getMediaFileDirectories();

        assertEquals(3, childDirectories.size());

        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);

        // Delete folder
        MediaFileDirectory directoryById = mfMgr
                .getMediaFileDirectory(directory1.getId());

        mfMgr.removeMediaFileDirectory(directoryById);
        TestUtils.endSession(true);

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
        TestUtils.endSession(true);
    }

    // ----------------------------------------------------- Rendition pipeline

    @AfterEach
    public void resetCwebpDetection() {
        // A couple of tests below force the cwebp seam; make sure no test
        // leaks a forced result into whatever runs next in this JVM.
        CwebpEncoder.setAvailableForTesting(null);
    }

    /**
     * hawk.jpg is only 500px wide, so it can only ever prove the narrowest
     * (480) rung. A synthetic image lets these tests exercise multiple ladder
     * rungs and prove the ones at or above the source width are skipped.
     */
    private static byte[] generateImageBytes(int width, int height, String formatName) throws Exception {
        BufferedImage img = new BufferedImage(width, height,
                "png".equals(formatName) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, formatName, baos);
        return baos.toByteArray();
    }

    /**
     * Ladder generation hooks the same seam as the existing `_sm` admin
     * thumbnail (persistNewMediaFile -> updateThumbnail), with cwebp forced
     * off so the assertions are deterministic regardless of whether this
     * machine happens to have cwebp installed.
     */
    @Test
    public void testCreateMediaFileGeneratesResponsiveRenditions() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);

        User testUser = TestUtils.setupUser("renditionTestUser1");
        Weblog testWeblog = TestUtils.setupWeblog("renditionTestWeblog1", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        // 1000x700: wide enough to clear the 480 and 960 rungs, narrower than 1600/2400.
        byte[] jpegBytes = generateImageBytes(1000, 700, "jpg");
        MediaFile mediaFile = new MediaFile();
        mediaFile.setName("wide.jpg");
        mediaFile.setDirectory(rootDirectory);
        mediaFile.setWeblog(testWeblog);
        mediaFile.setContentType("image/jpeg");
        mediaFile.setLength(jpegBytes.length);
        mediaFile.setInputStream(new ByteArrayInputStream(jpegBytes));
        mfMgr.createMediaFile(testWeblog, mediaFile, new PressroomMessages());
        String id = mediaFile.getId();
        TestUtils.endSession(true);

        FileContentManager fmgr = TestUtils.weblogger().getFileContentManager();

        // Rungs narrower than the 1000px original must exist...
        FileContent rendition480 = fmgr.getFileContent(testWeblog, id + "_480");
        assertTrue(rendition480.getLength() > 0);
        FileContent rendition960 = fmgr.getFileContent(testWeblog, id + "_960");
        assertTrue(rendition960.getLength() > 0);
        assertTrue(rendition960.getLength() > rendition480.getLength(),
                "a 960w rendition must be a larger file than the 480w rendition of the same image");

        // ...and rungs at or above it must not.
        final Weblog assertWeblog1 = testWeblog;
        assertThrows(FileNotFoundException.class,
                () -> fmgr.getFileContent(assertWeblog1, id + "_1600"));
        assertThrows(FileNotFoundException.class,
                () -> fmgr.getFileContent(assertWeblog1, id + "_2400"));

        // No webp siblings: cwebp was forced off.
        assertThrows(FileNotFoundException.class,
                () -> fmgr.getFileContent(assertWeblog1, id + "_480.webp"));

        // The existing admin thumbnail must still be produced too.
        FileContent thumbnail = fmgr.getFileContent(testWeblog, id + "_sm");
        assertTrue(thumbnail.getLength() > 0);

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    @Test
    public void testCreateMediaFilePreservesPngFormatFamilyInRenditions() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);

        User testUser = TestUtils.setupUser("renditionTestUser2");
        Weblog testWeblog = TestUtils.setupWeblog("renditionTestWeblog2", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        byte[] pngBytes = generateImageBytes(900, 600, "png");
        MediaFile mediaFile = new MediaFile();
        mediaFile.setName("wide.png");
        mediaFile.setDirectory(rootDirectory);
        mediaFile.setWeblog(testWeblog);
        mediaFile.setContentType("image/png");
        mediaFile.setLength(pngBytes.length);
        mediaFile.setInputStream(new ByteArrayInputStream(pngBytes));
        mfMgr.createMediaFile(testWeblog, mediaFile, new PressroomMessages());
        String id = mediaFile.getId();
        TestUtils.endSession(true);

        FileContentManager fmgr = TestUtils.weblogger().getFileContentManager();
        FileContent rendition480 = fmgr.getFileContent(testWeblog, id + "_480");
        byte[] renditionBytes = rendition480.getInputStream().readAllBytes();
        // PNG signature -- proves the png original produced a png rendition, not jpeg.
        assertEquals((byte) 0x89, renditionBytes[0]);
        assertEquals('P', renditionBytes[1]);
        assertEquals('N', renditionBytes[2]);
        assertEquals('G', renditionBytes[3]);

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    /**
     * Runs only on a machine/container that actually has cwebp installed
     * (skipped, not failed, otherwise) -- real detection is used here rather
     * than the forced seam, so this is the one test that proves the real
     * binary integration works end to end.
     */
    @Test
    public void testCreateMediaFileGeneratesWebpSiblingsWhenCwebpIsAvailable() throws Exception {
        CwebpEncoder.setAvailableForTesting(null);
        Assumptions.assumeTrue(CwebpEncoder.isAvailable(),
                "cwebp is not installed on this machine -- skipping the webp-sibling check");

        User testUser = TestUtils.setupUser("renditionTestUser3");
        Weblog testWeblog = TestUtils.setupWeblog("renditionTestWeblog3", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        byte[] jpegBytes = generateImageBytes(1000, 700, "jpg");
        MediaFile mediaFile = new MediaFile();
        mediaFile.setName("wide.jpg");
        mediaFile.setDirectory(rootDirectory);
        mediaFile.setWeblog(testWeblog);
        mediaFile.setContentType("image/jpeg");
        mediaFile.setLength(jpegBytes.length);
        mediaFile.setInputStream(new ByteArrayInputStream(jpegBytes));
        mfMgr.createMediaFile(testWeblog, mediaFile, new PressroomMessages());
        String id = mediaFile.getId();
        TestUtils.endSession(true);

        FileContentManager fmgr = TestUtils.weblogger().getFileContentManager();
        FileContent webp480 = fmgr.getFileContent(testWeblog, id + "_480.webp");
        assertTrue(webp480.getLength() > 0);

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    @Test
    public void testUpdateMediaFileRegeneratesRenditions() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);

        User testUser = TestUtils.setupUser("renditionTestUser4");
        Weblog testWeblog = TestUtils.setupWeblog("renditionTestWeblog4", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        // Start narrow: only hawk.jpg (500w), no 960 rung yet.
        MediaFile mediaFile = new MediaFile();
        mediaFile.setName("upgrade.jpg");
        mediaFile.setDirectory(rootDirectory);
        mediaFile.setWeblog(testWeblog);
        mediaFile.setContentType("image/jpeg");
        mediaFile.setInputStream(getClass().getResourceAsStream(TEST_IMAGE));
        mfMgr.createMediaFile(testWeblog, mediaFile, new PressroomMessages());
        String id = mediaFile.getId();
        TestUtils.endSession(true);

        FileContentManager fmgr = TestUtils.weblogger().getFileContentManager();
        final Weblog assertWeblog2 = testWeblog;
        assertThrows(FileNotFoundException.class,
                () -> fmgr.getFileContent(assertWeblog2, id + "_960"),
                "the 500w original must not have produced a 960 rung yet");

        // Replace the content with a wider image via the update-with-stream seam.
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFile mediaFile1 = mfMgr.getMediaFile(id);
        mediaFile1.setWeblog(testWeblog);
        byte[] widerJpeg = generateImageBytes(1200, 800, "jpg");
        mediaFile1.setLength(widerJpeg.length);
        mfMgr.updateMediaFile(testWeblog, mediaFile1, new ByteArrayInputStream(widerJpeg));
        TestUtils.endSession(true);

        FileContent rendition960 = fmgr.getFileContent(testWeblog, id + "_960");
        assertTrue(rendition960.getLength() > 0,
                "updateMediaFile(...,is) must regenerate the ladder from the new content");

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    @Test
    public void testDeleteMediaFileRemovesRenditions() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);

        User testUser = TestUtils.setupUser("renditionTestUser5");
        Weblog testWeblog = TestUtils.setupWeblog("renditionTestWeblog5", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        byte[] jpegBytes = generateImageBytes(1000, 700, "jpg");
        MediaFile mediaFile = new MediaFile();
        mediaFile.setName("todelete.jpg");
        mediaFile.setDirectory(rootDirectory);
        mediaFile.setWeblog(testWeblog);
        mediaFile.setContentType("image/jpeg");
        mediaFile.setLength(jpegBytes.length);
        mediaFile.setInputStream(new ByteArrayInputStream(jpegBytes));
        mfMgr.createMediaFile(testWeblog, mediaFile, new PressroomMessages());
        String id = mediaFile.getId();
        TestUtils.endSession(true);

        FileContentManager fmgr = TestUtils.weblogger().getFileContentManager();
        // sanity: the rendition exists before delete
        assertTrue(fmgr.getFileContent(testWeblog, id + "_480").getLength() > 0);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFile toDelete = mfMgr.getMediaFile(id);
        mfMgr.removeMediaFile(testWeblog, toDelete);
        TestUtils.endSession(true);

        final Weblog assertWeblog3 = testWeblog;
        assertThrows(FileNotFoundException.class,
                () -> fmgr.getFileContent(assertWeblog3, id + "_480"),
                "removeMediaFile must clean up the 480 rendition");
        assertThrows(FileNotFoundException.class,
                () -> fmgr.getFileContent(assertWeblog3, id + "_960"),
                "removeMediaFile must clean up the 960 rendition");
        assertThrows(FileNotFoundException.class,
                () -> fmgr.getFileContent(assertWeblog3, id + "_sm"),
                "removeMediaFile must still clean up the admin thumbnail");
        assertThrows(FileNotFoundException.class,
                () -> fmgr.getFileContent(assertWeblog3, id),
                "removeMediaFile must still clean up the original");

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    @Test
    public void testRemoveMediaFileDirectoryRemovesRenditions() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);

        User testUser = TestUtils.setupUser("renditionTestUser6");
        Weblog testWeblog = TestUtils.setupWeblog("renditionTestWeblog6", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        byte[] jpegBytes = generateImageBytes(1000, 700, "jpg");
        MediaFile mediaFile = new MediaFile();
        mediaFile.setName("indir.jpg");
        mediaFile.setDirectory(rootDirectory);
        mediaFile.setWeblog(testWeblog);
        mediaFile.setContentType("image/jpeg");
        mediaFile.setLength(jpegBytes.length);
        mediaFile.setInputStream(new ByteArrayInputStream(jpegBytes));
        mfMgr.createMediaFile(testWeblog, mediaFile, new PressroomMessages());
        String id = mediaFile.getId();
        TestUtils.endSession(true);

        FileContentManager fmgr = TestUtils.weblogger().getFileContentManager();
        assertTrue(fmgr.getFileContent(testWeblog, id + "_480").getLength() > 0);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());
        mfMgr.removeMediaFileDirectory(rootDirectory);
        TestUtils.endSession(true);

        final Weblog assertWeblog4 = testWeblog;
        assertThrows(FileNotFoundException.class,
                () -> fmgr.getFileContent(assertWeblog4, id + "_480"),
                "removeMediaFileDirectory must clean up renditions of the files it contained");

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    @Test
    public void testRegenerateRenditionsBackfillsMissingRenditions() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);

        User testUser = TestUtils.setupUser("renditionTestUser7");
        Weblog testWeblog = TestUtils.setupWeblog("renditionTestWeblog7", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        byte[] jpegBytes = generateImageBytes(1000, 700, "jpg");
        MediaFile mediaFile = new MediaFile();
        mediaFile.setName("backfill.jpg");
        mediaFile.setDirectory(rootDirectory);
        mediaFile.setWeblog(testWeblog);
        mediaFile.setContentType("image/jpeg");
        mediaFile.setLength(jpegBytes.length);
        mediaFile.setInputStream(new ByteArrayInputStream(jpegBytes));
        mfMgr.createMediaFile(testWeblog, mediaFile, new PressroomMessages());
        String id = mediaFile.getId();
        TestUtils.endSession(true);

        // Simulate "legacy" data uploaded before the pipeline existed: strip
        // the renditions that were just generated, leaving only the original.
        FileContentManager fmgr = TestUtils.weblogger().getFileContentManager();
        fmgr.deleteFile(testWeblog, id + "_480");
        fmgr.deleteFile(testWeblog, id + "_960");
        final Weblog assertWeblog5 = testWeblog;
        assertThrows(FileNotFoundException.class,
                () -> fmgr.getFileContent(assertWeblog5, id + "_480"));

        // Also blank the metadata the upload pipeline fills in, so this really
        // is a file from before that pipeline existed. Without this the
        // blurhash assertion below passes on the value createMediaFile already
        // stored, and would keep passing if the backfill stopped computing it.
        MediaFile legacy = mfMgr.getMediaFile(id);
        legacy.setBlurhash(null);
        legacy.setExifCamera(null);
        mfMgr.updateMediaFile(TestUtils.getManagedWebsite(testWeblog), legacy);
        TestUtils.endSession(true);
        assertNull(mfMgr.getMediaFile(id).getBlurhash(), "fixture setup: blurhash must start null");

        long lastUpdatedBeforeBackfill = mfMgr.getMediaFile(id).getLastUpdated().getTime();
        // Guarantee a distinguishable millisecond boundary even on a very fast
        // clock/filesystem so the "must have moved" assertion below can't pass
        // by coincidence.
        Thread.sleep(5);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        int processed = mfMgr.regenerateRenditions(testWeblog);
        TestUtils.endSession(true);

        assertEquals(1, processed);
        assertTrue(fmgr.getFileContent(testWeblog, id + "_480").getLength() > 0,
                "the backfill action must regenerate the missing rendition");
        assertTrue(fmgr.getFileContent(testWeblog, id + "_960").getLength() > 0);

        long lastUpdatedAfterBackfill = mfMgr.getMediaFile(id).getLastUpdated().getTime();
        assertTrue(lastUpdatedAfterBackfill > lastUpdatedBeforeBackfill,
                "regenerateRenditions must bump lastUpdated so MediaResourceServlet's "
                        + "Last-Modified/304 check doesn't keep serving a client's cached, "
                        + "now-stale rendition after a backfill");

        // The blurhash too, not only the rendition ladder. A photo uploaded
        // before this pipeline existed has no placeholder, and this action is
        // the only remediation offered -- if it rebuilt just the images, those
        // photos would pop in on every page load forever.
        assertNotNull(mfMgr.getMediaFile(id).getBlurhash(),
                "the backfill must compute a blurhash placeholder, not only renditions");

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    // ------------------------------------------------- EXIF / GPS / blurhash

    /** Photo with a full EXIF block (camera/lens/exposure/aperture/iso/focal/taken) plus GPS. */
    private static final String EXIF_IMAGE = "/hawk-exif.jpg";

    /** 300x200, narrower than the 480 ladder rung, and carries no EXIF at all. */
    private static final String SMALL_NO_EXIF_IMAGE = "/small-photo.jpg";

    private void setStripGps(boolean strip) throws Exception {
        Map<String, RuntimeConfigProperty> config = TestUtils.weblogger()
                .getPropertiesManager().getProperties();
        config.get("uploads.exif.stripGps").setValue(Boolean.toString(strip));
    }

    private MediaFile uploadImage(Weblog weblog, MediaFileDirectory dir, String name, String resource)
            throws Exception {
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFile mediaFile = new MediaFile();
        mediaFile.setName(name);
        mediaFile.setDirectory(dir);
        mediaFile.setWeblog(weblog);
        mediaFile.setContentType("image/jpeg");
        mediaFile.setInputStream(getClass().getResourceAsStream(resource));
        mfMgr.createMediaFile(weblog, mediaFile, new PressroomMessages());
        return mediaFile;
    }

    @Test
    public void testCreateMediaFileExtractsExifMetadataWithGpsStrippedByDefault() throws Exception {
        User testUser = TestUtils.setupUser("exifTestUser1");
        Weblog testWeblog = TestUtils.setupWeblog("exifTestWeblog1", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        // uploads.exif.stripGps defaults to true -- no override needed here.
        MediaFile mediaFile = uploadImage(testWeblog, rootDirectory, "exif.jpg", EXIF_IMAGE);
        String id = mediaFile.getId();
        TestUtils.endSession(true);

        MediaFile stored = mfMgr.getMediaFile(id);
        assertEquals("Canon EOS R5", stored.getExifCamera());
        assertEquals("RF100-500mm F4.5-7.1 L IS USM", stored.getExifLens());
        assertNotNull(stored.getExifExposure());
        assertTrue(stored.getExifExposure().contains("1000"),
                "expected the 1/1000s exposure to show up in the description: " + stored.getExifExposure());
        assertNotNull(stored.getExifAperture());
        assertEquals(400, stored.getExifIso());
        assertNotNull(stored.getExifFocalLength());
        assertEquals(new Timestamp(
                new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss").parse("2024:06:15 10:30:00").getTime()),
                stored.getExifTaken());

        // GPS was present in the source file but must be stripped by default.
        assertNull(stored.getGpsLatitude());
        assertNull(stored.getGpsLongitude());

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    @Test
    public void testCreateMediaFileKeepsGpsWhenStrippingIsDisabled() throws Exception {
        User testUser = TestUtils.setupUser("exifTestUser2");
        Weblog testWeblog = TestUtils.setupWeblog("exifTestWeblog2", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        setStripGps(false);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        MediaFile mediaFile = uploadImage(testWeblog, rootDirectory, "exif-gps.jpg", EXIF_IMAGE);
        String id = mediaFile.getId();
        TestUtils.endSession(true);

        try {
            MediaFile stored = mfMgr.getMediaFile(id);
            assertNotNull(stored.getGpsLatitude());
            assertNotNull(stored.getGpsLongitude());
            assertEquals(37.8199, stored.getGpsLatitude(), 0.001);
            assertEquals(-122.4783, stored.getGpsLongitude(), 0.001);
        } finally {
            setStripGps(true);
            TestUtils.endSession(true);
            TestUtils.teardownWeblog(testWeblog.getId());
            TestUtils.teardownUser(testUser.getUserName());
        }
    }

    @Test
    public void testCreateMediaFileWithNoExifYieldsNullExifFieldsButStillBlurhashes() throws Exception {
        User testUser = TestUtils.setupUser("exifTestUser3");
        Weblog testWeblog = TestUtils.setupWeblog("exifTestWeblog3", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        MediaFile mediaFile = uploadImage(testWeblog, rootDirectory, "plain.jpg", TEST_IMAGE);
        String id = mediaFile.getId();
        TestUtils.endSession(true);

        MediaFile stored = mfMgr.getMediaFile(id);
        assertNull(stored.getExifCamera());
        assertNull(stored.getExifLens());
        assertNull(stored.getExifExposure());
        assertNull(stored.getExifAperture());
        assertNull(stored.getExifIso());
        assertNull(stored.getExifFocalLength());
        assertNull(stored.getExifTaken());
        assertNull(stored.getGpsLatitude());
        assertNull(stored.getGpsLongitude());

        // Absence of EXIF must never block blurhash encoding -- unrelated pipelines.
        assertNotNull(stored.getBlurhash());
        assertEquals(28, stored.getBlurhash().length());

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    @Test
    public void testBlurhashIsEncodedFromThe480RenditionWhenAvailable() throws Exception {
        User testUser = TestUtils.setupUser("blurhashTestUser1");
        Weblog testWeblog = TestUtils.setupWeblog("blurhashTestWeblog1", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        // 500px wide -- the 480 rung is generated.
        MediaFile mediaFile = uploadImage(testWeblog, rootDirectory, "wide-blur.jpg", TEST_IMAGE);
        String id = mediaFile.getId();
        TestUtils.endSession(true);

        FileContentManager fmgr = TestUtils.weblogger().getFileContentManager();
        assertTrue(fmgr.getFileContent(testWeblog, id + "_480").getLength() > 0,
                "sanity: the 480 rung must exist for this fixture");

        MediaFile stored = mfMgr.getMediaFile(id);
        assertNotNull(stored.getBlurhash());

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    @Test
    public void testBlurhashFallsBackToTheAdminThumbnailWhenNarrowerThanTheLadder() throws Exception {
        User testUser = TestUtils.setupUser("blurhashTestUser2");
        Weblog testWeblog = TestUtils.setupWeblog("blurhashTestWeblog2", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        // 300px wide -- narrower than the 480 rung, so the ladder produces nothing.
        MediaFile mediaFile = uploadImage(testWeblog, rootDirectory, "narrow.jpg", SMALL_NO_EXIF_IMAGE);
        String id = mediaFile.getId();
        TestUtils.endSession(true);

        FileContentManager fmgr = TestUtils.weblogger().getFileContentManager();
        final Weblog assertWeblog = testWeblog;
        assertThrows(FileNotFoundException.class,
                () -> fmgr.getFileContent(assertWeblog, id + "_480"),
                "sanity: this fixture must not produce a 480 rung");

        MediaFile stored = mfMgr.getMediaFile(id);
        assertNotNull(stored.getBlurhash(),
                "blurhash must fall back to the _sm admin thumbnail when no ladder rung exists");

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    @Test
    public void testRenditionsCarryNoExifOrGpsMetadataEvenWhenTheOriginalDid() throws Exception {
        User testUser = TestUtils.setupUser("exifStripTestUser1");
        Weblog testWeblog = TestUtils.setupWeblog("exifStripTestWeblog1", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        // Even with site-wide GPS stripping off, the rendition re-encode itself
        // (thumbnailator -> ImageIO) must never carry EXIF/GPS through --
        // that's a property of the re-encode, independent of the stored-field
        // stripping tested above.
        setStripGps(false);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        MediaFile mediaFile = uploadImage(testWeblog, rootDirectory, "exif-rendition.jpg", EXIF_IMAGE);
        String id = mediaFile.getId();
        TestUtils.endSession(true);

        try {
            FileContentManager fmgr = TestUtils.weblogger().getFileContentManager();

            // The original file on disk is untouched -- it must still carry its EXIF/GPS.
            byte[] originalBytes = fmgr.getFileContent(testWeblog, id).getInputStream().readAllBytes();
            Metadata originalMetadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(originalBytes));
            assertFalse(originalMetadata.getDirectoriesOfType(GpsDirectory.class).isEmpty(),
                    "the original upload file must be left untouched, GPS included");

            // The 480 rendition, however, must be clean: no EXIF, no GPS.
            byte[] renditionBytes = fmgr.getFileContent(testWeblog, id + "_480").getInputStream().readAllBytes();
            Metadata renditionMetadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(renditionBytes));
            assertTrue(renditionMetadata.getDirectoriesOfType(GpsDirectory.class).isEmpty(),
                    "renditions must not carry GPS metadata forward");
            assertTrue(renditionMetadata.getDirectoriesOfType(ExifIFD0Directory.class).isEmpty(),
                    "renditions must not carry EXIF metadata forward");
        } finally {
            setStripGps(true);
            TestUtils.endSession(true);
            TestUtils.teardownWeblog(testWeblog.getId());
            TestUtils.teardownUser(testUser.getUserName());
        }
    }

    /**
     * A crafted upload whose EXIF Make/Model/LensModel are far longer than the
     * VARCHAR(128)/VARCHAR(32) columns they are stored in must not break the
     * upload. The failure this pins is NOT visible inside updateThumbnail:
     * the over-long value is only rejected at the deferred flush (here,
     * endSession), well outside every catch that makes metadata failures
     * non-fatal, so the whole upload transaction failed with the file bytes
     * already written to disk.
     */
    @Test
    public void testCreateMediaFileClampsOverlongExifStringsInsteadOfFailingTheUpload() throws Exception {
        User testUser = TestUtils.setupUser("exifClampTestUser1");
        Weblog testWeblog = TestUtils.setupWeblog("exifClampTestWeblog1", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        MediaFile mediaFile = uploadImage(testWeblog, rootDirectory, "oversized.jpg", OVERSIZED_EXIF_IMAGE);
        String id = mediaFile.getId();
        // This is the assertion that matters: the flush must not blow up on
        // "value too long for type character varying".
        TestUtils.endSession(true);

        MediaFile stored = mfMgr.getMediaFile(id);
        assertNotNull(stored, "the upload must have survived its crafted EXIF block");
        assertEquals(ExifSupport.MAX_CAMERA_LENGTH, stored.getExifCamera().length());
        assertEquals(ExifSupport.MAX_LENS_LENGTH, stored.getExifLens().length());
        assertTrue(stored.getExifExposure().length() <= ExifSupport.MAX_SETTING_LENGTH);
        assertTrue(stored.getExifAperture().length() <= ExifSupport.MAX_SETTING_LENGTH);
        assertTrue(stored.getExifFocalLength().length() <= ExifSupport.MAX_SETTING_LENGTH);

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    // ------------------------------------------------------ EXIF orientation

    /** 1000x600 raster tagged EXIF Orientation=6, i.e. a 600x1000 upright portrait photo. */
    private static final String PORTRAIT_EXIF_IMAGE = "/portrait-exif.jpg";

    /** 600x400 image whose Make/Model/LensModel tags are hundreds of characters long. */
    private static final String OVERSIZED_EXIF_IMAGE = "/oversized-exif.jpg";

    /**
     * The upload path must correct EXIF orientation before deriving anything
     * from the raster: browsers rotate the original from its EXIF, but the
     * renditions are re-encoded pixel data with no metadata at all, so an
     * uncorrected ladder would show the same photo sideways or upright
     * depending on which srcset candidate the browser picked.
     */
    @Test
    public void testCreateMediaFileAppliesExifOrientationBeforeDerivingRenditions() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);

        User testUser = TestUtils.setupUser("orientationTestUser1");
        Weblog testWeblog = TestUtils.setupWeblog("orientationTestWeblog1", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        MediaFile mediaFile = uploadImage(testWeblog, rootDirectory, "portrait.jpg", PORTRAIT_EXIF_IMAGE);
        String id = mediaFile.getId();
        TestUtils.endSession(true);

        MediaFile stored = mfMgr.getMediaFile(id);
        assertEquals(600, stored.getWidth(),
                "stored dimensions must describe the upright photo, not the landscape raster");
        assertEquals(1000, stored.getHeight());

        FileContentManager fmgr = TestUtils.weblogger().getFileContentManager();
        BufferedImage rendition = ImageIO.read(
                fmgr.getFileContent(testWeblog, id + "_480").getInputStream());
        assertEquals(480, rendition.getWidth());
        assertEquals(800, rendition.getHeight(),
                "the 480w rung must be portrait (800 tall), not the sideways 288");

        final Weblog assertWeblog = testWeblog;
        assertThrows(FileNotFoundException.class,
                () -> fmgr.getFileContent(assertWeblog, id + "_960"),
                "the upright image is only 600 wide, so the 960 rung must be skipped");

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    /**
     * regenerateRenditions is the remediation path for photos uploaded before
     * the orientation fix: it must rebuild the ladder upright AND re-derive
     * the stored dimensions (a 90-degree orientation swaps them).
     */
    @Test
    public void testRegenerateRenditionsRemediatesSidewaysRenditions() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);

        User testUser = TestUtils.setupUser("orientationTestUser2");
        Weblog testWeblog = TestUtils.setupWeblog("orientationTestWeblog2", testUser);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFileDirectory rootDirectory = mfMgr.getDefaultMediaFileDirectory(testWeblog);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        rootDirectory = mfMgr.getMediaFileDirectory(rootDirectory.getId());

        MediaFile mediaFile = uploadImage(testWeblog, rootDirectory, "legacy-portrait.jpg",
                PORTRAIT_EXIF_IMAGE);
        String id = mediaFile.getId();
        TestUtils.endSession(true);

        // Rewrite the record into the pre-fix state: sideways renditions and
        // raster (landscape) dimensions.
        FileContentManager fmgr = TestUtils.weblogger().getFileContentManager();
        byte[] sideways = generateImageBytes(480, 288, "jpg");
        fmgr.saveFileContent(testWeblog, id + "_480", new ByteArrayInputStream(sideways));
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        MediaFile legacy = mfMgr.getMediaFile(id);
        legacy.setWidth(1000);
        legacy.setHeight(600);
        mfMgr.updateMediaFile(testWeblog, legacy);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        assertEquals(1, mfMgr.regenerateRenditions(testWeblog));
        TestUtils.endSession(true);

        BufferedImage rendition = ImageIO.read(
                fmgr.getFileContent(testWeblog, id + "_480").getInputStream());
        assertEquals(480, rendition.getWidth());
        assertEquals(800, rendition.getHeight(),
                "the backfill must replace the sideways rendition with an upright one");

        MediaFile remediated = mfMgr.getMediaFile(id);
        assertEquals(600, remediated.getWidth(),
                "the backfill must also correct the stored dimensions");
        assertEquals(1000, remediated.getHeight());

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    @Test
    public void removingAWeblogTakesEveryDirectoryWithIt() throws Exception {
        // Deleting a weblog used to reclaim only its default media directory.
        // Everything else -- rows and files on disk -- stayed behind with no
        // owner, which surfaced as test uploads piling up past the per-weblog
        // quota until a fixture failed with error.upload.dirmax, and in
        // production would be unreachable files nothing ever reclaims.
        User user = TestUtils.setupUser("mediasweepuser");
        Weblog weblog = TestUtils.setupWeblog("mediasweepblog", user);
        TestUtils.endSession(true);

        TestUtils.setupImageMediaFile(weblog, "in-default.jpg");
        TestUtils.setupImageMediaFile(weblog, "in-album.jpg", "an-album");
        TestUtils.endSession(true);

        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        assertEquals(2, mfMgr.getMediaFileDirectories(managed).size(),
                "fixture must have made both directories");

        mfMgr.removeAllFiles(managed);
        TestUtils.endSession(true);

        assertTrue(mfMgr.getMediaFileDirectories(TestUtils.getManagedWebsite(weblog)).isEmpty(),
                "removeAllFiles must reclaim every directory, not just the default");

        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }
}