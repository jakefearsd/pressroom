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

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.jpa.JPAMediaFileManagerImpl;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.business.jpa.JPAWebloggerImpl;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.util.HTMLSanitizer;
import com.wikantik.pressroom.util.PressroomMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * The entry-HTML cache against the real tier: each change that reaches a
 * reader through the database -- an edit, a rolled-back edit, a media
 * directory's privacy toggle, a media file's own metadata -- makes the live
 * render fresh, through the production {@link EntryRenderer} bean and its
 * cache. Every render here is one session's freshly loaded entity, as a
 * reader's request would see it.
 */
class EntryHtmlCacheLifecycleTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("entryhtmlcacheuser");
        weblog = TestUtils.setupWeblog("entryhtmlcacheblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.endSession(false);
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static WeblogEntryManager entries() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    private WeblogEntry publish(String anchor, String text) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, weblog, user);
        WeblogEntry managed = entries().getWeblogEntry(entry.getId());
        managed.setText(text);
        entries().saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return entry;
    }

    /** What a reader is served for the entry now: a fresh session, the live path. */
    private static String liveRender(WeblogEntry entry) throws Exception {
        TestUtils.endSession(false);
        WeblogEntry loaded = entries().getWeblogEntry(entry.getId());
        return TestUtils.weblogger().getEntryRenderer().cachedTransformedText(loaded);
    }

    @Test
    void aSavedEditIsServedFresh() throws Exception {
        WeblogEntry entry = publish("edited-entry", "first version");
        assertTrue(liveRender(entry).contains("first version"));
        assertTrue(liveRender(entry).contains("first version"));

        WeblogEntry managed = entries().getWeblogEntry(entry.getId());
        managed.setText("second version");
        entries().saveWeblogEntry(managed);
        TestUtils.endSession(true);

        assertTrue(liveRender(entry).contains("second version"));
    }

    @Test
    void anEditThatRolledBackIsNeverServed() throws Exception {
        WeblogEntry entry = publish("rolled-back-entry", "committed text");
        String committed = liveRender(entry);

        // Rendered on the live path inside the transaction that then rolls
        // back: the slot is written for text, and a weblog stamp, that never
        // reach the database.
        WeblogEntry managed = entries().getWeblogEntry(entry.getId());
        managed.setText("text that rolled back");
        entries().saveWeblogEntry(managed);
        String inFlight = TestUtils.weblogger().getEntryRenderer().cachedTransformedText(managed);
        assertTrue(inFlight.contains("text that rolled back"));
        TestUtils.endSession(false);

        assertEquals(committed, liveRender(entry));
    }

    @Test
    void aGalleryIsNotServedStaleAcrossThePrivacyToggle() throws Exception {
        TestUtils.setupImageMediaFile(weblog, "trip-photo.jpg", "trip");
        TestUtils.endSession(true);
        WeblogEntry entry = publish("gallery-entry", "[gallery dir=\"trip\"]");

        assertFalse(liveRender(entry).contains("[gallery"), "a public directory renders as a gallery");

        togglePrivate("trip");
        assertTrue(liveRender(entry).contains("[gallery"),
                "a private directory is refused, never served from the render made while it was public");

        togglePrivate("trip");
        assertFalse(liveRender(entry).contains("[gallery"), "and public again renders again");
    }

    /** What {@code MediaFileViewController.togglePrivate} does. */
    private void togglePrivate(String directoryName) throws Exception {
        TestUtils.endSession(false);
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        MediaFileDirectory directory = TestUtils.weblogger().getMediaFileManager()
                .getMediaFileDirectoryByName(managed, directoryName);
        directory.setPrivate(!directory.isPrivate());
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    @Test
    void anImagesUpdatedAltTextIsServedFresh() throws Exception {
        MediaFile photo = TestUtils.setupImageMediaFile(weblog, "alt-photo.jpg");
        TestUtils.endSession(true);
        WeblogEntry entry = publish("image-entry", "[image id=\"" + photo.getId() + "\"]");
        setAltText(photo, "A hawk on a fence");
        assertTrue(liveRender(entry).contains("A hawk on a fence"), liveRender(entry));

        setAltText(photo, "A hawk in flight");
        assertTrue(liveRender(entry).contains("A hawk in flight"), liveRender(entry));
    }

    private void setAltText(MediaFile photo, String altText) throws Exception {
        TestUtils.endSession(false);
        MediaFileManager media = TestUtils.weblogger().getMediaFileManager();
        MediaFile managed = media.getMediaFile(photo.getId());
        managed.setAltText(altText);
        media.updateMediaFile(TestUtils.getManagedWebsite(weblog), managed);
        TestUtils.endSession(true);
    }

    // ------------------------------------------------ media derived after the upload

    /** The EXIF camera of {@code /hawk-exif.jpg}, printed by [gallery] as data-exif-camera. */
    private static final String CAMERA = "Canon EOS R5";

    /**
     * An upload derives the photo's EXIF, GPS and blurhash after its row,
     * dimensions and first renditions are committed. A reader rendering in
     * between sees the photo without them; that render must not outlive the
     * upload, so the weblog's stamp has to land with -- not before -- the
     * last derived write. The reader runs on its own thread (its own
     * session) at the moment the 480 rendition is written.
     */
    @Test
    void aGalleryRenderedWhileAnUploadIsDerivingItsMetadataIsNotKept() throws Throwable {
        TestUtils.setupImageMediaFile(weblog, "first.jpg", "trip");
        TestUtils.endSession(true);
        WeblogEntry entry = publish("upload-entry", "[gallery dir=\"trip\"]");
        withUnsanitizedRenders(() -> {
            liveRender(entry);

            MediaFile upload = new MediaFile();
            AtomicReference<String> during = new AtomicReference<>();
            MediaFileManager hooked = renderingDuringRendition(upload.getId(), entry, during);
            TestUtils.endSession(false);
            Weblog managed = TestUtils.getManagedWebsite(weblog);
            upload.setName("upload.jpg");
            upload.setDirectory(hooked.getMediaFileDirectoryByName(managed, "trip"));
            upload.setWeblog(managed);
            upload.setContentType("image/jpeg");
            upload.setInputStream(resource("/hawk-exif.jpg"));
            hooked.createMediaFile(managed, upload, new PressroomMessages());
            TestUtils.endSession(true);

            assertFalse(during.get().contains(CAMERA), "the reader must have rendered inside the window");
            assertTrue(liveRender(entry).contains(CAMERA),
                    "the render made before the EXIF was stored must not be served after the upload");
        });
    }

    /** As above, for new content uploaded over an existing media file. */
    @Test
    void aGalleryRenderedWhileReplacedContentIsDerivingItsMetadataIsNotKept() throws Throwable {
        MediaFile photo = TestUtils.setupImageMediaFile(weblog, "replaced.jpg", "trip");
        TestUtils.endSession(true);
        WeblogEntry entry = publish("replace-entry", "[gallery dir=\"trip\"]");
        withUnsanitizedRenders(() -> {
            assertFalse(liveRender(entry).contains(CAMERA));

            AtomicReference<String> during = new AtomicReference<>();
            MediaFileManager hooked = renderingDuringRendition(photo.getId(), entry, during);
            TestUtils.endSession(false);
            MediaFile managed = hooked.getMediaFile(photo.getId());
            hooked.updateMediaFile(TestUtils.getManagedWebsite(weblog), managed, resource("/hawk-exif.jpg"));
            TestUtils.endSession(true);

            assertFalse(during.get().contains(CAMERA), "the reader must have rendered inside the window");
            assertTrue(liveRender(entry).contains(CAMERA),
                    "the render made before the new EXIF was stored must not be served after the upload");
        });
    }

    private static InputStream resource(String name) {
        return EntryHtmlCacheLifecycleTest.class.getResourceAsStream(name);
    }

    /**
     * The real media manager, on the live persistence strategy, except that
     * writing {@code <mediaFileId>_480} first has a reader on another thread
     * render {@code entry} on the live path into {@code during}.
     */
    private static MediaFileManager renderingDuringRendition(String mediaFileId, WeblogEntry entry,
            AtomicReference<String> during) throws Exception {
        Weblogger real = TestUtils.weblogger();
        FileContentManager realContent = real.getFileContentManager();
        FileContentManager hookedContent = mock(FileContentManager.class,
                org.mockito.AdditionalAnswers.delegatesTo(realContent));
        doAnswer(invocation -> {
            Thread reader = new Thread(() -> {
                try {
                    WeblogEntry loaded = real.getWeblogEntryManager().getWeblogEntry(entry.getId());
                    during.set(real.getEntryRenderer().cachedTransformedText(loaded));
                } catch (WebloggerException e) {
                    during.set(e.toString());
                } finally {
                    real.release();
                }
            });
            reader.start();
            reader.join();
            realContent.saveFileContent(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2));
            return null;
        }).when(hookedContent).saveFileContent(any(Weblog.class), eq(mediaFileId + "_480"), any(InputStream.class));
        Weblogger hookedWeblogger = mock(Weblogger.class, org.mockito.AdditionalAnswers.delegatesTo(real));
        doReturn(hookedContent).when(hookedWeblogger).getFileContentManager();
        java.lang.reflect.Field field = JPAWebloggerImpl.class.getDeclaredField("strategy");
        field.setAccessible(true);
        return new JPAMediaFileManagerImpl(hookedWeblogger, (JPAPersistenceStrategy) field.get(real));
    }

    /** EXIF travels on the lightbox anchor, which the sanitizer drops for the test's localhost urls. */
    private static void withUnsanitizedRenders(org.junit.jupiter.api.function.Executable body) throws Throwable {
        Boolean saved = HTMLSanitizer.xssEnabled;
        HTMLSanitizer.xssEnabled = Boolean.FALSE;
        try {
            body.execute();
        } finally {
            HTMLSanitizer.xssEnabled = saved;
        }
    }
}
