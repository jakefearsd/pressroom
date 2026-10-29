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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WeblogEntryManager#getSitemapEntries}: the five columns the sitemap
 * needs, read in one query, in the order and under the cap the sitemap has
 * always used. The byte-for-byte document is pinned in
 * {@code SeoControllerSitemapTest}; this pins the query's own contract.
 */
class WeblogEntrySitemapQueryTest {

    private static final Instant BASE = Instant.parse("2026-02-01T00:00:00Z");

    private User user;
    private Weblog blog;
    private Weblog other;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("sitemapQueryUser");
        blog = TestUtils.setupWeblog("sitemapqueryblog", user);
        other = TestUtils.setupWeblog("sitemapqueryother", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(blog.getId());
        TestUtils.teardownWeblog(other.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void readsPublishedEntriesOfTheWeblogNewestFirstWithOnlyPublicImages() throws Exception {
        MediaFile open = TestUtils.setupImageMediaFile(blog, "query-public.jpg");
        MediaFile hidden = TestUtils.setupImageMediaFile(blog, "query-private.jpg", "queryprivdir");
        TestUtils.weblogger().getMediaFileManager().getMediaFile(hidden.getId())
                .getDirectory().setPrivate(true);
        TestUtils.endSession(true);

        WeblogEntry oldest = entry(blog, "oldest", PubStatus.PUBLISHED, 1, open.getId(), false);
        entry(blog, "private-image", PubStatus.PUBLISHED, 2, hidden.getId(), false);
        entry(blog, "blank-image", PubStatus.PUBLISHED, 3, " ", false);
        entry(blog, "noindexed", PubStatus.PUBLISHED, 4, null, true);
        entry(blog, "a-draft", PubStatus.DRAFT, 5, open.getId(), false);
        entry(other, "elsewhere", PubStatus.PUBLISHED, 6, null, false);

        List<SitemapEntry> rows = entries().getSitemapEntries(TestUtils.getManagedWebsite(blog), 100);

        assertEquals(List.of("noindexed", "blank-image", "private-image", "oldest"),
                rows.stream().map(SitemapEntry::anchor).toList(),
                "published entries of this weblog only, newest first, noindex included");
        assertTrue(rows.get(0).noindex(), "the noindex flag is read, not filtered");
        assertFalse(rows.get(1).noindex());
        assertNull(rows.get(1).publicImageId(), "a blank image id resolves to nothing");
        assertNull(rows.get(2).publicImageId(), "a private directory's image is never public");
        assertEquals(open.getId(), rows.get(3).publicImageId());
        assertEquals(BASE.plusSeconds(3600), rows.get(3).pubTime());
        assertEquals(oldest.getUpdateTime().toInstant(), rows.get(3).updateTime());
    }

    /**
     * The cap counts noindex rows, exactly as the old entity query's
     * maxResults did: the sitemap filters them after the cap.
     */
    @Test
    void theCapAppliesToTheNewestRowsBeforeAnyFiltering() throws Exception {
        entry(blog, "first", PubStatus.PUBLISHED, 1, null, false);
        entry(blog, "second", PubStatus.PUBLISHED, 2, null, false);
        entry(blog, "third", PubStatus.PUBLISHED, 3, null, true);

        List<SitemapEntry> rows = entries().getSitemapEntries(TestUtils.getManagedWebsite(blog), 2);

        assertEquals(List.of("third", "second"), rows.stream().map(SitemapEntry::anchor).toList());
    }

    /**
     * The old entity query ordered by pubtime alone, so entries published in
     * the same instant came back in whatever order the plan produced. The
     * id tiebreak makes that stable without changing any non-tied order.
     */
    @Test
    void entriesPublishedInTheSameInstantAreOrderedById() throws Exception {
        List<String> expected = new java.util.ArrayList<>();
        java.util.Map<String, String> anchorById = new java.util.TreeMap<>();
        for (int i = 0; i < 8; i++) {
            WeblogEntry tied = entry(blog, "tied-" + i, PubStatus.PUBLISHED, 1, null, false);
            anchorById.put(tied.getId(), tied.getAnchor());
        }
        expected.addAll(anchorById.values());

        List<SitemapEntry> rows = entries().getSitemapEntries(TestUtils.getManagedWebsite(blog), 100);

        assertEquals(expected, rows.stream().map(SitemapEntry::anchor).toList());
    }

    private WeblogEntry entry(Weblog weblog, String anchor, PubStatus status, int hours,
            String imageId, boolean noindex) throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry(anchor, weblog, user, status);
        WeblogEntry managed = TestUtils.getManagedWeblogEntry(created);
        managed.setPubTime(Timestamp.from(BASE.plusSeconds(hours * 3600L)));
        managed.setFeaturedImageId(imageId);
        managed.setNoindex(noindex);
        entries().saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return TestUtils.getManagedWeblogEntry(created);
    }

    private static WeblogEntryManager entries() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }
}
