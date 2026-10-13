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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.MediaFileFilter;
import org.apache.roller.weblogger.pojos.MediaFileFilter.MediaFileOrder;
import org.apache.roller.weblogger.pojos.MediaFileFilter.SizeFilterType;
import org.apache.roller.weblogger.pojos.MediaFileType;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.util.RollerMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JPAMediaFileManagerImpl#searchMediaFiles} filters and ordering, and
 * the ownership and format refusals of {@code cropMediaFile}, against the
 * real tier. The library screen and the media API both list through this
 * query, and a crop overwrites the only copy of an upload, so a wrong filter
 * or a missing refusal is visible to a user.
 *
 * <p>Characterisation tests: written against the existing behaviour and
 * expected to pass immediately.
 */
class MediaFileSearchFilterTest {

    private User user;
    private Weblog weblog;
    private Weblog other;
    private MediaFileManager mfMgr;
    private MediaFile alpha;
    private MediaFile song;
    private MediaFile gamma;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("mfsearch");
        weblog = TestUtils.setupWeblog("mfsearchblog", user);
        other = TestUtils.setupWeblog("mfsearchother", user);
        TestUtils.endSession(true);
        mfMgr = TestUtils.weblogger().getMediaFileManager();
        Map<String, RuntimeConfigProperty> config = TestUtils.weblogger().getPropertiesManager().getProperties();
        config.get("uploads.enabled").setValue("true");

        weblog = TestUtils.getManagedWebsite(weblog);
        alpha = upload("alpha-notes.txt", "text/plain", "12345");
        song = upload("beta-song.mp3", "audio/mpeg", "x".repeat(20));
        gamma = upload("gamma.txt", "text/plain", "y".repeat(12));
        weblog = TestUtils.getManagedWebsite(weblog);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownWeblog(other.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private MediaFile upload(String name, String contentType, String body) throws Exception {
        MediaFileDirectory dir = mfMgr.getDefaultMediaFileDirectory(TestUtils.getManagedWebsite(weblog));
        MediaFile mf = new MediaFile();
        mf.setName(name);
        mf.setDirectory(dir);
        mf.setWeblog(TestUtils.getManagedWebsite(weblog));
        mf.setContentType(contentType);
        mf.setLength(body.length());
        mf.setInputStream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        RollerMessages errors = new RollerMessages();
        mfMgr.createMediaFile(TestUtils.getManagedWebsite(weblog), mf, errors);
        assertEquals(0, errors.getErrorCount(), "fixture upload refused: " + errors);
        TestUtils.endSession(true);
        return mfMgr.getMediaFile(mf.getId());
    }

    private List<String> search(MediaFileFilter filter) throws Exception {
        return mfMgr.searchMediaFiles(TestUtils.getManagedWebsite(weblog), filter).stream()
                .map(MediaFile::getName).toList();
    }

    private static MediaFileFilter sized(SizeFilterType type, long size) {
        MediaFileFilter f = new MediaFileFilter();
        f.setSize(size);
        f.setSizeFilterType(type);
        return f;
    }

    // ------------------------------------------------------------------ search

    @Test
    void anEmptyFilterListsEveryFileOfTheWeblogByName() throws Exception {
        assertEquals(List.of("alpha-notes.txt", "beta-song.mp3", "gamma.txt"), search(new MediaFileFilter()));
    }

    @Test
    void theNameFilterIsATrimmedPrefixMatchUnlessItAlreadyEndsInAWildcard() throws Exception {
        MediaFileFilter f = new MediaFileFilter();
        f.setName("  beta ");
        assertEquals(List.of("beta-song.mp3"), search(f));
        f.setName("%.txt");
        assertEquals(List.of("alpha-notes.txt", "gamma.txt"), search(f), "an explicit wildcard is used as given");
        f.setName("nothing");
        assertTrue(search(f).isEmpty());
    }

    @Test
    void theSizeFilterComparesAccordingToItsType() throws Exception {
        assertEquals(List.of("beta-song.mp3"), search(sized(SizeFilterType.GT, 12)));
        assertEquals(List.of("beta-song.mp3", "gamma.txt"), search(sized(SizeFilterType.GTE, 12)));
        assertEquals(List.of("gamma.txt"), search(sized(SizeFilterType.EQ, 12)));
        assertEquals(List.of("alpha-notes.txt"), search(sized(SizeFilterType.LT, 12)));
        assertEquals(List.of("alpha-notes.txt", "gamma.txt"), search(sized(SizeFilterType.LTE, 12)));
    }

    @Test
    void aSingleTagAndATypeNarrowTheListing() throws Exception {
        MediaFile managed = mfMgr.getMediaFile(song.getId());
        mfMgr.updateTags(managed, List.of("loud"));
        mfMgr.updateMediaFile(TestUtils.getManagedWebsite(weblog), managed);
        TestUtils.endSession(true);

        MediaFileFilter byTag = new MediaFileFilter();
        byTag.setTags(List.of("loud"));
        assertEquals(List.of("beta-song.mp3"), search(byTag));

        MediaFileFilter audio = new MediaFileFilter();
        audio.setType(MediaFileType.AUDIO);
        assertEquals(List.of("beta-song.mp3"), search(audio));

        MediaFileFilter images = new MediaFileFilter();
        images.setType(MediaFileType.IMAGE);
        assertTrue(search(images).isEmpty());
    }

    @Test
    void resultsCanBeOrderedByTypeOrNameAndPaged() throws Exception {
        MediaFileFilter byType = new MediaFileFilter();
        byType.setOrder(MediaFileOrder.TYPE);
        List<String> typed = search(byType);
        assertEquals("beta-song.mp3", typed.get(0), "audio/mpeg sorts before text/plain");

        MediaFileFilter byDate = new MediaFileFilter();
        byDate.setOrder(MediaFileOrder.DATE_UPLOADED);
        assertEquals(3, search(byDate).size());

        MediaFileFilter page = new MediaFileFilter();
        page.setOrder(MediaFileOrder.NAME);
        page.setStartIndex(1);
        page.setLength(1);
        assertEquals(List.of("beta-song.mp3"), search(page), "the second file by name");
    }

    @Test
    void otherWeblogsFilesAreNeverListed() throws Exception {
        MediaFileFilter all = new MediaFileFilter();
        assertTrue(mfMgr.searchMediaFiles(TestUtils.getManagedWebsite(other), all).isEmpty());
    }

    // -------------------------------------------------------------------- crop

    @Test
    void aCropIsRefusedForAWeblogThatDoesNotOwnTheFileOrForAFileThatCannotBeReEncoded() throws Exception {
        MediaFile managed = mfMgr.getMediaFile(gamma.getId());
        Weblog foreign = TestUtils.getManagedWebsite(other);
        Weblog owner = TestUtils.getManagedWebsite(weblog);

        WebloggerException notOwner = assertThrows(WebloggerException.class,
                () -> mfMgr.cropMediaFile(foreign, managed, 0, 0, 1, 1));
        assertTrue(notOwner.getMessage().contains("does not belong"), notOwner.getMessage());
        assertThrows(WebloggerException.class, () -> mfMgr.cropMediaFile(null, managed, 0, 0, 1, 1),
                "no weblog is no authority");
        WebloggerException notAnImage = assertThrows(WebloggerException.class,
                () -> mfMgr.cropMediaFile(owner, managed, 0, 0, 1, 1));
        assertTrue(notAnImage.getMessage().contains("cannot be cropped"), notAnImage.getMessage());
    }
}
