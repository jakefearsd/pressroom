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

package com.wikantik.pressroom.business;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.related.GeoBox;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;

/**
 * {@link WeblogEntryManager#getPublishedEntriesInBox}: the one bounded query
 * behind a post page's cross-blog Nearby (0.9.1 spec, criteria 6 and 7).
 * The fixtures sit in the open ocean (Point Nemo), where no other test puts
 * a post, so the box holds only what this class made.
 */
class PublishedEntriesInBoxTest {

    private static final double LAT = -48.87;
    private static final double LNG = -123.39;
    private static final GeoBox BOX = GeoBox.around(LAT, LNG, 80);

    private User user;
    private final List<Weblog> weblogs = new ArrayList<>();
    private Weblog home;
    private Weblog other;
    private long clock = System.currentTimeMillis() - 86_400_000L * 30;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        String suffix = Long.toString(System.nanoTime(), 36);
        user = TestUtils.setupUser("inboxuser" + suffix);
        home = weblog("inboxhome" + suffix);
        other = weblog("inboxother" + suffix);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Weblog weblog : weblogs) {
            TestUtils.teardownWeblog(weblog.getId());
        }
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private interface Edit {
        void apply(WeblogEntry entry);
    }

    private interface BlogEdit {
        void apply(Weblog weblog);
    }

    private Weblog weblog(String handle) throws Exception {
        Weblog weblog = TestUtils.setupWeblog(handle, user);
        weblogs.add(weblog);
        return weblog;
    }

    private Weblog weblog(String handle, BlogEdit edit) throws Exception {
        Weblog weblog = weblog(handle);
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        edit.apply(managed);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        return weblog;
    }

    /** A post at the fixture place, each a day newer than the one before. */
    private void post(Weblog weblog, String anchor, PubStatus status, Edit edit) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, TestUtils.getManagedWebsite(weblog),
                TestUtils.getManagedUser(user), status);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        clock += 86_400_000L;
        managed.setPubTime(new Timestamp(clock));
        managed.setUpdateTime(new Timestamp(clock));
        managed.setGeoLatitude(LAT);
        managed.setGeoLongitude(LNG);
        edit.apply(managed);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
    }

    private void post(Weblog weblog, String anchor) throws Exception {
        post(weblog, anchor, PubStatus.PUBLISHED, entry -> { });
    }

    private List<String> anchorsInBox(GeoBox box, int max) throws Exception {
        List<String> anchors = TestUtils.weblogger().getWeblogEntryManager()
                .getPublishedEntriesInBox(TestUtils.getManagedWebsite(home), box, max)
                .stream().map(WeblogEntry::getAnchor).toList();
        TestUtils.endSession(true);
        return anchors;
    }

    @Test
    void returnsOtherBlogsPublishedPostsInTheBoxNewestFirst() throws Exception {
        post(other, "older");
        post(other, "outside", PubStatus.PUBLISHED, entry -> entry.setGeoLatitude(LAT + 5));
        post(other, "east-of-it", PubStatus.PUBLISHED, entry -> entry.setGeoLongitude(LNG + 5));
        post(other, "no-place", PubStatus.PUBLISHED, entry -> {
            entry.setGeoLatitude(null);
            entry.setGeoLongitude(null);
        });
        post(home, "own-blog");
        post(other, "newer");

        assertEquals(List.of("newer", "older"), anchorsInBox(BOX, 50));
    }

    /** Criterion 6: never a draft, a scheduled post, a noindex post, a hidden or an inactive blog. */
    @Test
    void leavesOutWhatTheFrontPageWouldNotShow() throws Exception {
        String suffix = other.getHandle().substring("inboxother".length());
        Weblog hidden = weblog("inboxhidden" + suffix, weblog -> weblog.setVisible(Boolean.FALSE));
        Weblog inactive = weblog("inboxinactive" + suffix, weblog -> weblog.setActive(Boolean.FALSE));
        long tomorrow = System.currentTimeMillis() + 86_400_000L;

        post(other, "shown");
        post(other, "draft", PubStatus.DRAFT, entry -> { });
        post(other, "pending", PubStatus.PENDING, entry -> { });
        post(other, "scheduled", PubStatus.SCHEDULED, entry -> entry.setPubTime(new Timestamp(tomorrow)));
        post(other, "published-tomorrow", PubStatus.PUBLISHED, entry -> entry.setPubTime(new Timestamp(tomorrow)));
        post(other, "noindex", PubStatus.PUBLISHED, entry -> entry.setNoindex(Boolean.TRUE));
        post(hidden, "on-a-hidden-blog");
        post(inactive, "on-an-inactive-blog");

        assertEquals(List.of("shown"), anchorsInBox(BOX, 50));
    }

    /** Criterion 7: the row limit holds however many posts the box has. */
    @Test
    void stopsAtTheRowLimit() throws Exception {
        post(other, "one");
        post(other, "two");
        post(other, "three");

        assertEquals(List.of("three", "two"), anchorsInBox(BOX, 2));
    }

    @Test
    void aBoxAcrossTheAntimeridianTakesBothSides() throws Exception {
        post(other, "west-side", PubStatus.PUBLISHED, entry -> {
            entry.setGeoLatitude(-48.87);
            entry.setGeoLongitude(179.95);
        });
        post(other, "east-side", PubStatus.PUBLISHED, entry -> {
            entry.setGeoLatitude(-48.87);
            entry.setGeoLongitude(-179.95);
        });
        post(other, "elsewhere");

        assertEquals(List.of("east-side", "west-side"), anchorsInBox(GeoBox.around(-48.87, 179.99, 80), 50));
    }
}
