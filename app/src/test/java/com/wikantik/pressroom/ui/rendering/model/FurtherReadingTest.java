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

package com.wikantik.pressroom.ui.rendering.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.related.GeoBox;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;

/**
 * When a post page asks other blogs for Nearby posts, and which of the
 * answers it shows (0.9.1 spec, criteria 4 to 8). The managers are mocks:
 * what is pinned here is how many queries run and what is done with the rows.
 */
class FurtherReadingTest {

    private Weblogger weblogger;
    private WeblogEntryManager entries;
    private URLStrategy urls;
    private Weblog home;
    private Weblog neighbour;
    private final List<WeblogEntry> own = new ArrayList<>();
    private final List<WeblogEntry> others = new ArrayList<>();
    private int day;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = mock(Weblogger.class);
        entries = mock(WeblogEntryManager.class);
        urls = mock(URLStrategy.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(entries.getWeblogEntries(any())).thenReturn(own);
        when(entries.getSharedTagCounts(any())).thenReturn(Map.of());
        when(entries.getPublishedEntriesInBox(any(), any(), anyInt())).thenReturn(others);
        home = weblog("home");
        neighbour = weblog("neighbour");
    }

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setId(handle + "-id");
        weblog.setHandle(handle);
        weblog.setName(handle);
        weblog.setLocale("en_US");
        weblog.setVisible(Boolean.TRUE);
        weblog.setActive(Boolean.TRUE);
        return weblog;
    }

    private WeblogEntry post(Weblog weblog, String id) {
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        entry.setAnchor(id);
        entry.setWebsite(weblog);
        entry.setStatus(PubStatus.PUBLISHED);
        entry.setPubTime(new Timestamp(1_700_000_000_000L + 86_400_000L * day++));
        return entry;
    }

    /** A post {@code offset} degrees of latitude north of Newcastle, CA (0.1 is about 11 km). */
    private WeblogEntry placed(Weblog weblog, String id, double offset) {
        WeblogEntry entry = post(weblog, id);
        entry.setGeoLatitude(38.874 + offset);
        entry.setGeoLongitude(-121.133);
        return entry;
    }

    private static List<String> nearbyIds(FurtherReading reading) {
        return reading.getNearby().stream().map(near -> near.getEntry().getId()).toList();
    }

    private static List<String> relatedIds(FurtherReading reading) {
        return reading.getRelated().stream().map(wrapper -> wrapper.getId()).toList();
    }

    /** Criteria 4, 5 and 8. */
    @Test
    void otherBlogsFillWhatTheOwnBlogLeavesAndAreMarked() throws Exception {
        WeblogEntry current = placed(home, "current", 0);
        own.addAll(List.of(current, placed(home, "own-near", 0.2), post(home, "own-unplaced")));
        others.addAll(List.of(placed(neighbour, "other-far", 0.5), placed(neighbour, "other-near", 0.1)));

        FurtherReading reading = FurtherReading.of(current, urls, weblogger);

        assertEquals(List.of("own-near", "other-near", "other-far"), nearbyIds(reading));
        assertEquals(List.of(false, true, true),
                reading.getNearby().stream().map(FurtherReading.NearbyEntry::isOtherBlog).toList());
        assertEquals(List.of("own-unplaced"), relatedIds(reading), "Keep reading stays same-blog");

        ArgumentCaptor<GeoBox> box = ArgumentCaptor.forClass(GeoBox.class);
        verify(entries, times(1)).getPublishedEntriesInBox(eq(home), box.capture(),
                eq(FurtherReading.OTHER_BLOG_ROWS));
        assertEquals(GeoBox.around(38.874, -121.133, FurtherReading.NEARBY_KM), box.getValue());
        assertTrue(FurtherReading.OTHER_BLOG_ROWS <= 50, "a bounded read");
    }

    /** Criterion 7. */
    @Test
    void aFullOwnBlogAsksNoOtherBlog() throws Exception {
        WeblogEntry current = placed(home, "current", 0);
        own.addAll(List.of(current, placed(home, "a", 0.1), placed(home, "b", 0.2), placed(home, "c", 0.3)));
        others.add(placed(neighbour, "other-nearer", 0.01));

        FurtherReading reading = FurtherReading.of(current, urls, weblogger);

        assertEquals(List.of("a", "b", "c"), nearbyIds(reading));
        verify(entries, never()).getPublishedEntriesInBox(any(), any(), anyInt());
    }

    /** Criterion 7. */
    @Test
    void aPostWithoutCoordinatesAsksNoOtherBlog() throws Exception {
        WeblogEntry current = post(home, "current");
        own.addAll(List.of(current, placed(home, "a", 0.1)));
        others.add(placed(neighbour, "other", 0.01));

        FurtherReading reading = FurtherReading.of(current, urls, weblogger);

        assertEquals(List.of(), nearbyIds(reading));
        verify(entries, never()).getPublishedEntriesInBox(any(), any(), anyInt());
    }

    /**
     * Criterion 6: the rule is discovery's own (DiscoveryEligibility), applied
     * to every row whatever the query let through.
     */
    @Test
    void rowsTheFrontPageWouldNotShowAreDropped() throws Exception {
        WeblogEntry current = placed(home, "current", 0);
        own.add(current);
        Weblog hidden = weblog("hidden");
        hidden.setVisible(Boolean.FALSE);
        Weblog inactive = weblog("inactive");
        inactive.setActive(Boolean.FALSE);
        Weblog siteSpace = weblog("site");
        siteSpace.setSiteSpace(true);
        WeblogEntry noindex = placed(neighbour, "noindex", 0.02);
        noindex.setNoindex(Boolean.TRUE);
        WeblogEntry draft = placed(neighbour, "draft", 0.03);
        draft.setStatus(PubStatus.DRAFT);
        WeblogEntry scheduled = placed(neighbour, "scheduled", 0.04);
        scheduled.setStatus(PubStatus.SCHEDULED);
        others.addAll(List.of(placed(hidden, "on-hidden", 0.01), placed(inactive, "on-inactive", 0.01),
                placed(siteSpace, "on-site-space", 0.01), noindex, draft, scheduled,
                placed(neighbour, "shown", 0.3)));

        assertEquals(List.of("shown"), nearbyIds(FurtherReading.of(current, urls, weblogger)));
    }

    /** A blog that turned "more from the site" off shows no other blog's post here either. */
    @Test
    void aBlogThatOptedOutOfOtherBlogsPostsAsksNoOtherBlog() throws Exception {
        home.setShowMoreFromSite(Boolean.FALSE);
        WeblogEntry current = placed(home, "current", 0);
        own.addAll(List.of(current, placed(home, "own-near", 0.2)));
        others.add(placed(neighbour, "other", 0.01));

        FurtherReading reading = FurtherReading.of(current, urls, weblogger);

        assertEquals(List.of("own-near"), nearbyIds(reading));
        verify(entries, never()).getPublishedEntriesInBox(any(), any(), anyInt());
    }
}
