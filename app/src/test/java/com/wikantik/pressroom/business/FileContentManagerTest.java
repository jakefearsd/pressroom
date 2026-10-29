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
import com.wikantik.pressroom.util.UUIDGenerator;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.FileContent;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.util.PressroomMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test File Management business layer operations.
 */
public class FileContentManagerTest  {

    private static final Logger log = LoggerFactory.getLogger(FileContentManagerTest.class);
    User testUser = null;
    Weblog testWeblog = null;

    @BeforeEach
    public void setUp() throws Exception {

        // setup weblogger
        TestUtils.setupWeblogger();

    }

    @AfterEach
    public void tearDown() throws Exception {
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        config.get("uploads.dir.maxsize").setValue("30000");
        config.get("uploads.types.forbid").setValue("");
        config.get("uploads.types.allowed").setValue("");
        config.get("uploads.enabled").setValue("true");
        pmgr.saveProperties(config);
        TestUtils.endSession(true);
    }

    /**
     * The uploads dir is a boundary, and {@code checkFileName} is the only
     * thing enforcing it. It rejected {@code ".."} and nothing else, which
     * left an absolute {@code fileId} to walk straight out: {@code
     * getRealFile} resolves the id against the weblog dir with {@link
     * java.nio.file.Path#resolve}, and resolve() returns its ARGUMENT
     * unchanged when that argument is absolute -- the base is discarded, no
     * {@code ".."} ever appears, and the guard sees nothing to object to.
     *
     * <p>The write path never had this hole, which is why it is easy to
     * assume the read path doesn't either: {@code saveFileContent} builds its
     * target with {@code Path.of(dir, fileId)}, and that JOINS its arguments
     * rather than resolving them, so an absolute id lands harmlessly inside
     * the weblog dir. Only {@code getFileContent} and {@code deleteFile} go
     * through {@code getRealFile}.
     *
     * <p>Every id in production today is a generated UUID, so this was not
     * reachable; it is fixed because a boundary check that misses a whole
     * class of input is one careless caller away from being load-bearing.
     */
    @Nested
    class TraversalGuard {

        /**
         * Deliberately a transient pojo rather than a persisted fixture:
         * {@code getRealFile} reads nothing but {@code weblog.getHandle()},
         * so a database round-trip would only add a shared-username
         * collision with the other tests in this class.
         */
        private Weblog weblog() {
            Weblog w = new Weblog();
            w.setHandle("fcm-traversal-handle");
            return w;
        }

        @Test
        void anAbsoluteFileIdCannotBeReadFromOutsideTheUploadsDir() throws Exception {
            Path outside = Files.createTempFile("fcm-outside-", ".txt");
            Files.writeString(outside, "secret from outside the uploads dir");
            try {
                FileContentManager fcm = TestUtils.weblogger().getFileContentManager();
                assertThrows(FilePathException.class,
                        () -> fcm.getFileContent(weblog(), outside.toAbsolutePath().toString()),
                        "an absolute fileId must be refused, not resolved outside the uploads dir");
            } finally {
                Files.deleteIfExists(outside);
            }
        }

        @Test
        void anAbsoluteFileIdCannotBeDeletedFromOutsideTheUploadsDir() throws Exception {
            Path outside = Files.createTempFile("fcm-outside-del-", ".txt");
            Files.writeString(outside, "must survive");
            try {
                FileContentManager fcm = TestUtils.weblogger().getFileContentManager();
                assertThrows(FilePathException.class,
                        () -> fcm.deleteFile(weblog(), outside.toAbsolutePath().toString()),
                        "an absolute fileId must be refused, not deleted outside the uploads dir");
                assertTrue(Files.exists(outside), "the file outside the uploads dir was deleted");
            } finally {
                Files.deleteIfExists(outside);
            }
        }

        @Test
        void anIdThatIsNotAValidPathIsRefused() {
            FileContentManager fcm = TestUtils.weblogger().getFileContentManager();
            // Path.of rejects an embedded NUL outright, which reaches
            // checkFileName's InvalidPathException branch rather than its
            // isAbsolute() one.
            assertThrows(FilePathException.class,
                    () -> fcm.getFileContent(weblog(), "hello\u0000world"),
                    "an id that is not even a valid path must be refused");
        }

        /**
         * Drives {@link FileContentManagerImpl#requireInside} directly. No
         * public call can reach its throwing branch today, because
         * checkFileName refuses both ".." and absolute ids first -- and that
         * redundancy is the point: this pins the boundary itself, so it keeps
         * holding if the name check ever stops covering a shape.
         */
        @Test
        void requireInsideRejectsAPathOutsideTheRoot() {
            Path root = Path.of("/srv/uploads/myweblog");
            assertThrows(FilePathException.class,
                    () -> FileContentManagerImpl.requireInside(root, Path.of("/etc/passwd"), "x"),
                    "a path outside the root must be refused");
            assertThrows(FilePathException.class,
                    () -> FileContentManagerImpl.requireInside(
                            root, root.resolve("../../etc/passwd"), "x"),
                    "a path that normalizes outside the root must be refused");
        }

        @Test
        void requireInsideAcceptsAPathUnderTheRoot() throws Exception {
            Path root = Path.of("/srv/uploads/myweblog");
            assertEquals(root.resolve("photo.jpg"),
                    FileContentManagerImpl.requireInside(root, root.resolve("photo.jpg"), "photo.jpg"));
            assertEquals(root, FileContentManagerImpl.requireInside(root, root, null));
        }

        @Test
        void aRelativeTraversalIsStillRefused() {
            FileContentManager fcm = TestUtils.weblogger().getFileContentManager();
            assertThrows(FilePathException.class,
                    () -> fcm.getFileContent(weblog(), "../../etc/hostname"),
                    "the pre-existing \"..\" guard must keep working");
        }
    }

    /**
     * Test simple file save/delete.
     */
    @Test
    public void testFileCRUD() throws Exception {

        try {
            testUser = TestUtils.setupUser("FCMTest_userName1");
            testWeblog = TestUtils.setupWeblog("FCMTest_handle1", testUser);
            TestUtils.endSession(true);
        } catch (Exception ex) {
            log.error("ERROR in test setup", ex);
        }

        // update roller properties to prepare for test
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        config.get("uploads.enabled").setValue("true");
        config.get("uploads.types.allowed").setValue("opml");
        config.get("uploads.dir.maxsize").setValue("1.00");
        pmgr.saveProperties(config);
        TestUtils.endSession(true);

        /* NOTE: upload dir for unit tests is set in
        roller/testdata/roller-custom.properties */
        FileContentManager fmgr = TestUtils.weblogger().getFileContentManager();

        // File should not exist initially
        try {
            FileContent fileContent = fmgr.getFileContent(testWeblog, "bookmarks-file-id");
            assertTrue(false, "Non-existant file retrieved without any exception");
        } catch (FileNotFoundException e) {
            assertTrue(true, "Exception thrown for non-existant file as expected");
        }

        // store a file
        InputStream is = getClass().getResourceAsStream("/bookmarks.opml");
        fmgr.saveFileContent(testWeblog, "bookmarks-file-id", is);

        // make sure file was stored successfully
        FileContent fileContent1 = fmgr.getFileContent(testWeblog, "bookmarks-file-id");
        assertEquals("bookmarks-file-id", fileContent1.getFileId());


        // delete file
        fmgr.deleteFile(testWeblog, "bookmarks-file-id");

        // File should not exist after delete
        try {
            FileContent fileContent = fmgr.getFileContent(testWeblog, "bookmarks-file-id");
            assertTrue(false, "Non-existant file retrieved without any exception");
        } catch (FileNotFoundException e) {
            assertTrue(true, "Exception thrown for non-existant file as expected");
        }

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    /**
     * The write must be atomic with respect to the destination: overwriting
     * an existing file with a stream that fails partway (disk full, broken
     * pipe) must leave the previous bytes untouched, not a truncated stub.
     * This is the guarantee the destructive media-crop path leans on -- it
     * overwrites a previously-good original the user has no other copy of.
     */
    @Test
    public void testAMidWriteFailureLeavesTheExistingFileUntouched() throws Exception {

        testUser = TestUtils.setupUser("FCMTest_userName3");
        testWeblog = TestUtils.setupWeblog("FCMTest_handle3", testUser);
        TestUtils.endSession(true);

        FileContentManager fmgr = TestUtils.weblogger().getFileContentManager();

        byte[] original = "the original, known-good content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        fmgr.saveFileContent(testWeblog, "atomic-file-id", new ByteArrayInputStream(original));

        // a stream that yields some bytes and then breaks mid-transfer
        InputStream failing = new InputStream() {
            private int served = 0;

            @Override
            public int read() throws java.io.IOException {
                if (served++ < 10) {
                    return 'x';
                }
                throw new java.io.IOException("simulated disk/stream failure mid-write");
            }
        };

        assertThrows(FileIOException.class,
                () -> fmgr.saveFileContent(testWeblog, "atomic-file-id", failing),
                "the failure must surface, not be swallowed");

        byte[] after = fmgr.getFileContent(testWeblog, "atomic-file-id")
                .getInputStream().readAllBytes();
        assertArrayEquals(original, after,
                "a failed overwrite must leave the previous content byte-identical");

        // and the aborted temp file must not linger next to the original
        java.io.File weblogDir = new java.io.File(
                com.wikantik.pressroom.config.WebloggerConfig.getProperty("mediafiles.storage.dir"),
                testWeblog.getHandle());
        String[] leftovers = weblogDir.list((dir, name) -> name.startsWith("roller-save-"));
        assertNotNull(leftovers, "the weblog's uploads directory must exist");
        assertEquals(0, leftovers.length,
                "aborted writes must clean up their temp file: " + java.util.Arrays.toString(leftovers));

        fmgr.deleteFile(testWeblog, "atomic-file-id");
        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }

    /**
     * The temp-file cleanup after an aborted write is best-effort by design:
     * it must never throw, because that would mask the original write
     * failure the caller is about to report.
     */
    @Test
    public void testDeleteTempQuietlyNeverThrows() throws Exception {
        // deleting a non-empty directory throws DirectoryNotEmptyException
        // (an IOException) from Files.deleteIfExists -- the cheapest real
        // trigger for the cleanup-failure branch
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("fcm-test-");
        java.nio.file.Path child = java.nio.file.Files.createFile(dir.resolve("child"));
        try {
            FileContentManagerImpl.deleteTempQuietly(dir);
            assertTrue(java.nio.file.Files.exists(dir),
                    "the failed delete must be swallowed, not escalated");
        } finally {
            java.nio.file.Files.deleteIfExists(child);
            java.nio.file.Files.deleteIfExists(dir);
        }

        // and the normal case really deletes
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("fcm-test-", ".tmp");
        FileContentManagerImpl.deleteTempQuietly(temp);
        assertFalse(java.nio.file.Files.exists(temp));
    }

    /**
     * {@code saveFileContent} itself is covered end to end by the rest of
     * this class against real weblog uploads dirs, where {@code requireParent}'s
     * argument is always dirPath's absolute path plus a fileId and so always
     * has a parent. This pins the one case that can't arise there: a path
     * with no parent at all (NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE on
     * {@code Path.getParent()}).
     */
    @Nested
    class RequireParent {

        @Test
        void returnsTheParentOfAnOrdinaryPath() throws IOException {
            assertEquals(Path.of("/a/b"), FileContentManagerImpl.requireParent(Path.of("/a/b/c")));
        }

        @Test
        void rejectsAPathWithNoParent() {
            IOException ex = assertThrows(IOException.class,
                    () -> FileContentManagerImpl.requireParent(Path.of("/")));
            assertTrue(ex.getMessage().contains("Cannot determine parent directory"));
        }
    }

    /**
     * The upload gate, one rule at a time.
     *
     * <p>This replaced a single {@code testCanSave} whose later assertions were
     * vacuous: it called {@code canSave(...)} three times but only ever assigned
     * the first result, then asserted on that stale variable. The forbidden-type
     * rule and the uploads-disabled rule therefore passed no matter what the
     * code did -- the worst kind of coverage, because the report counted them.
     *
     * <p>Each rule now gets its own case, and each asserts on the message the
     * caller is given as well as the verdict: the media page shows that message
     * and nothing else tells an author why their file bounced.
     */
    @Nested
    class CanSave {

        @BeforeEach
        void createWeblog() throws Exception {
            testUser = TestUtils.setupUser("FCMTest_canSave");
            testWeblog = TestUtils.setupWeblog("FCMTest_canSave", testUser);
            TestUtils.endSession(true);
        }

        @AfterEach
        void removeWeblog() throws Exception {
            TestUtils.teardownWeblog(testWeblog.getId());
            TestUtils.teardownUser(testUser.getUserName());
            TestUtils.endSession(true);
        }

        @Test
        void anOrdinaryFileIsAccepted() throws Exception {
            configure("true", "1.00", "30000", "", "");

            PressroomMessages messages = new PressroomMessages();
            assertTrue(canSave("hawk.jpg", "image/jpeg", 1000, messages),
                    "a small image with no restrictions configured must be accepted");
            assertEquals(0, messages.getErrorCount());
        }

        @Test
        void uploadsDisabledRefusesEverything() throws Exception {
            configure("false", "1.00", "30000", "", "");

            PressroomMessages messages = new PressroomMessages();
            assertFalse(canSave("hawk.jpg", "image/jpeg", 10, messages),
                    "with uploads switched off site-wide nothing may be saved");
            assertTrue(errorKeys(messages).contains("error.upload.disabled"),
                    "got: " + errorKeys(messages));
        }

        @Test
        void aFileOverTheSizeLimitIsRefused() throws Exception {
            configure("true", "1.00", "30000", "", "");

            PressroomMessages messages = new PressroomMessages();
            assertFalse(canSave("hawk.jpg", "image/jpeg", 2_500_000, messages),
                    "2.5MB must not pass a 1MB per-file limit");
            assertTrue(errorKeys(messages).contains("error.upload.filemax"),
                    "got: " + errorKeys(messages));
        }

        /**
         * The quota is on the weblog's whole directory, so the check has to add
         * the incoming file to what is already there rather than looking at
         * either alone.
         */
        @Test
        void aFileThatWouldPushTheWeblogOverQuotaIsRefused() throws Exception {
            configure("true", "10.00", "0.001", "", "");

            PressroomMessages messages = new PressroomMessages();
            assertFalse(canSave("hawk.jpg", "image/jpeg", 5000, messages),
                    "5KB must not fit inside a 1KB directory quota");
            assertTrue(errorKeys(messages).contains("error.upload.dirmax"),
                    "got: " + errorKeys(messages));
        }

        @Test
        void aForbiddenExtensionIsRefused() throws Exception {
            configure("true", "1.00", "30000", "", "gif");

            PressroomMessages messages = new PressroomMessages();
            assertFalse(canSave("test.gif", "image/gif", 10, messages),
                    "an extension on the forbid list must be refused");
            assertTrue(errorKeys(messages).contains("error.upload.forbiddenFile"),
                    "got: " + errorKeys(messages));

            assertTrue(canSave("test.jpg", "image/jpeg", 10, new PressroomMessages()),
                    "and only that extension -- everything else still passes");
        }

        @Test
        void aForbiddenContentTypeIsRefused() throws Exception {
            configure("true", "1.00", "30000", "", "application/x-shockwave-flash");

            assertFalse(canSave("movie.swf", "application/x-shockwave-flash", 10,
                            new PressroomMessages()),
                    "forbid rules may name a content type as well as an extension");
        }

        /**
         * A non-empty allow list inverts the default: anything not named is
         * refused, which is how an installation locks uploads down to images.
         */
        @Test
        void anAllowListRefusesEverythingItDoesNotName() throws Exception {
            configure("true", "1.00", "30000", "jpg,png", "");

            assertTrue(canSave("hawk.jpg", "image/jpeg", 10, new PressroomMessages()));
            assertFalse(canSave("notes.txt", "text/plain", 10, new PressroomMessages()),
                    "an extension absent from a non-empty allow list must be refused");
        }

        /**
         * In a Turkish locale, {@code "I".toLowerCase()} is {@code "ı"}
         * (dotless i), not {@code "i"} -- so an uppercase {@code .GIF}
         * upload stops matching a lowercase {@code "gif"} allow-list entry
         * once the JVM's default locale is Turkish. The comparison must be
         * locale-independent.
         */
        @Test
        void aTurkishDefaultLocaleDoesNotBreakTheUppercaseExtensionAllowList() throws Exception {
            configure("true", "1.00", "30000", "gif", "");

            java.util.Locale original = java.util.Locale.getDefault();
            try {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"));
                assertTrue(canSave("PHOTO.GIF", "image/gif", 10, new PressroomMessages()),
                        "an uppercase .GIF extension must still match a lowercase "
                                + "'gif' allow-list entry under a Turkish default locale");
            } finally {
                // the suite shares one JVM; leaking a Turkish default would
                // poison unrelated tests that assume Locale.getDefault() is stable
                java.util.Locale.setDefault(original);
            }
        }

        @Test
        void anAllowListMayNameAContentTypeRange() throws Exception {
            configure("true", "1.00", "30000", "image/*", "");

            assertTrue(canSave("hawk.jpg", "image/jpeg", 10, new PressroomMessages()),
                    "image/* must admit image/jpeg");
            assertFalse(canSave("notes.txt", "text/plain", 10, new PressroomMessages()),
                    "and must not admit text/plain");
        }

        /**
         * Forbid is evaluated after allow and wins. An installation that allows
         * images but forbids one troublesome type has to get the forbid.
         */
        @Test
        void forbidOverridesAllow() throws Exception {
            configure("true", "1.00", "30000", "image/*", "gif");

            assertFalse(canSave("test.gif", "image/gif", 10, new PressroomMessages()),
                    "the forbid list is the last word");
            assertTrue(canSave("hawk.jpg", "image/jpeg", 10, new PressroomMessages()),
                    "without disturbing the rest of the allow list");
        }

        /**
         * A missing or malformed content type is refused rather than waved
         * through. It is the only thing the type rules have to judge by, and a
         * client that omits it must not thereby escape them.
         */
        @Test
        void aFileWithNoUsableContentTypeIsRefused() throws Exception {
            configure("true", "1.00", "30000", "", "");

            assertFalse(canSave("mystery", null, 10, new PressroomMessages()),
                    "no content type at all");
            assertFalse(canSave("mystery", "nonsense", 10, new PressroomMessages()),
                    "a content type with no subtype is not one");
        }

        private boolean canSave(String fileName, String contentType, long size,
                PressroomMessages messages) throws Exception {
            return TestUtils.weblogger().getFileContentManager()
                    .canSave(TestUtils.getManagedWebsite(testWeblog), fileName, contentType,
                            size, messages);
        }

        private void configure(String enabled, String maxFileMB, String maxDirMB,
                String allowed, String forbid) throws Exception {
            PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
            Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
            config.get("uploads.enabled").setValue(enabled);
            config.get("uploads.file.maxsize").setValue(maxFileMB);
            config.get("uploads.dir.maxsize").setValue(maxDirMB);
            config.get("uploads.types.allowed").setValue(allowed);
            config.get("uploads.types.forbid").setValue(forbid);
            pmgr.saveProperties(config);
            TestUtils.endSession(true);
        }

        private List<String> errorKeys(PressroomMessages messages) {
            List<String> keys = new ArrayList<>();
            messages.getErrors().forEachRemaining(error -> keys.add(error.getKey()));
            return keys;
        }
    }

    /**
     * Responsive-image renditions (`<id>_480`, `<id>_480.webp`, ...) must not
     * count against a weblog's upload quota -- only what the user actually
     * uploaded should. Exercises the precise filename match directly against
     * FileContentManager rather than going through the whole image pipeline,
     * and proves the contrast case too: an ordinary uploaded file whose name
     * happens to contain underscores must still count.
     */
    @Test
    public void testRenditionsExcludedFromQuotaButUnderscoredUserFilesStillCount() throws Exception {

        // Weblog teardown does not clean up on-disk uploads, so a fixed
        // handle would accumulate real files
        // across repeated local `mvn test` runs against the same
        // target/test-classes tree and eventually trip the quota check for
        // reasons unrelated to this test. A per-run handle keeps this test's
        // directory pristine every time; the files it writes are also
        // deleted explicitly below for good hygiene.
        String handle = "FCMTest_handle3_" + UUIDGenerator.generateUUID().substring(0, 8);
        testUser = TestUtils.setupUser("FCMTest_userName3");
        testWeblog = TestUtils.setupWeblog(handle, testUser);
        TestUtils.endSession(true);

        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        config.get("uploads.enabled").setValue("true");
        config.get("uploads.types.allowed").setValue("");
        config.get("uploads.types.forbid").setValue("");
        // ~20KB quota: small enough that a single 40KB "rendition" would trip
        // it if (incorrectly) counted, but well above the 1KB "original".
        config.get("uploads.dir.maxsize").setValue("0.02");
        pmgr.saveProperties(config);
        TestUtils.endSession(true);

        FileContentManager fmgr = TestUtils.weblogger().getFileContentManager();

        String id = UUIDGenerator.generateUUID();
        String underscoredUserFile = "user_photo_with_underscores.jpg";
        byte[] small = new byte[1000];
        byte[] big = new byte[40000];

        try {
            fmgr.saveFileContent(testWeblog, id, new ByteArrayInputStream(small));
            fmgr.saveFileContent(testWeblog, id + "_480", new ByteArrayInputStream(big));
            fmgr.saveFileContent(testWeblog, id + "_480.webp", new ByteArrayInputStream(big));
            fmgr.saveFileContent(testWeblog, id + "_sm", new ByteArrayInputStream(big));

            assertFalse(fmgr.overQuota(testWeblog),
                    "generated renditions must be excluded from the upload quota");

            // Contrast: a legitimate uploaded file with underscores in its name
            // (not the rendition convention: not a 36-char id prefix) must still
            // count toward quota.
            fmgr.saveFileContent(testWeblog, underscoredUserFile, new ByteArrayInputStream(big));

            assertTrue(fmgr.overQuota(testWeblog),
                    "a legitimately underscored user filename must still count toward quota");
        } finally {
            fmgr.deleteFile(testWeblog, id);
            fmgr.deleteFile(testWeblog, id + "_480");
            fmgr.deleteFile(testWeblog, id + "_480.webp");
            fmgr.deleteFile(testWeblog, id + "_sm");
            fmgr.deleteFile(testWeblog, underscoredUserFile);
        }

        TestUtils.endSession(true);
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
    }
}
