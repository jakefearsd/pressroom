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
package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
