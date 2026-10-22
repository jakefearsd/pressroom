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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code weblog.lastModified} is the only thing that expires a cached page,
 * feed or sitemap, and those caches compare it for equality or order. Two
 * saves landing in the same millisecond used to stamp the same value, so a
 * document cached between them was served on after the second change. Every
 * bump must therefore move the stamp strictly forward, and the move must
 * survive the round trip through the {@code timestamp(3)} column.
 */
class WeblogLastModifiedTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("lastModifiedUser");
        weblog = TestUtils.setupWeblog("lastmodifiedblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    /**
     * A hundred back-to-back saves take far less than a hundred
     * milliseconds, so on a wall-clock stamp many of them collide; this
     * fails on every run against {@code setLastModified(new Date())}.
     */
    @Test
    void everySaveMovesLastModifiedStrictlyForward() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        List<Long> stamps = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
            stamps.add(managed.getLastModified().getTime());
        }

        for (int i = 1; i < stamps.size(); i++) {
            assertTrue(stamps.get(i) > stamps.get(i - 1),
                    "save " + i + " did not move lastModified past " + stamps.get(i - 1) + ": " + stamps);
        }
    }

    @Test
    void theForwardMoveSurvivesTheDatabaseRoundTrip() throws Exception {
        List<Long> stored = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Weblog managed = TestUtils.getManagedWebsite(weblog);
            TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
            TestUtils.endSession(true);
            stored.add(TestUtils.getManagedWebsite(weblog).getLastModified().getTime());
        }

        for (int i = 1; i < stored.size(); i++) {
            assertTrue(stored.get(i) > stored.get(i - 1),
                    "stored stamp " + i + " did not move forward: " + stored);
        }
    }

    /**
     * Saving a shared business touches every weblog using it without going
     * through {@code saveWeblog}; it must move their stamps forward too.
     */
    @Test
    void touchingAWeblogTwiceInOneInstantStillMovesItForward() {
        Weblog detached = new Weblog();
        detached.setLastModified(new Date(System.currentTimeMillis() + 60_000));
        Date ahead = detached.getLastModified();

        detached.touchLastModified();

        assertTrue(detached.getLastModified().after(ahead),
                "a stamp already at or past the clock must still advance");
    }

    /**
     * Rebuilding renditions rewrites every image's stored dimensions, EXIF
     * and blurhash, all of which [image] and [gallery] print; like every
     * other media change it must move the weblog's {@code lastModified}, the
     * only thing that expires a rendered page or a kept entry render.
     */
    @Test
    void regeneratingRenditionsMovesTheWeblogsLastModified() throws Exception {
        TestUtils.setupImageMediaFile(weblog, "regen-photo.jpg");
        TestUtils.endSession(true);
        long before = TestUtils.getManagedWebsite(weblog).getLastModified().getTime();
        TestUtils.endSession(false);

        int processed = TestUtils.weblogger().getMediaFileManager()
                .regenerateRenditions(TestUtils.getManagedWebsite(weblog));
        TestUtils.endSession(true);

        assertEquals(1, processed);
        long after = TestUtils.getManagedWebsite(weblog).getLastModified().getTime();
        assertTrue(after > before, "lastModified " + before + " did not move: " + after);
    }
}
