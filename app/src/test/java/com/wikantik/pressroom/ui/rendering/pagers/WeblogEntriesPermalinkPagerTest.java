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

package com.wikantik.pressroom.ui.rendering.pagers;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WeblogEntriesPermalinkPager} and its preview subclass
 * {@link WeblogEntriesPreviewPager}.
 *
 * <p>The permalink pager shows exactly one entry and navigates between
 * neighbouring posts rather than between pages. Two rules matter: an
 * unpublished entry must not be visible on the live weblog, and the
 * preview subclass must deliberately break that rule so authors can see drafts.
 */
class WeblogEntriesPermalinkPagerTest extends EntriesPagerTestSupport {

    private static final long PUBLISHED_AT = 1_600_000_000_000L;

    private static WeblogEntry entry(String anchor, String title, long pubMillis,
            WeblogEntry.PubStatus status) {
        WeblogEntry entry = new WeblogEntry();
        entry.setAnchor(anchor);
        entry.setTitle(title);
        entry.setPubTime(new Timestamp(pubMillis));
        entry.setStatus(status);
        return entry;
    }

    private WeblogEntriesPermalinkPager pager(String anchor) {
        return new WeblogEntriesPermalinkPager(urlStrategy, weblogger, weblog(), "en_US", null, anchor,
                null, null, null, 0);
    }

    private static int entryCount(Map<Date, ? extends Collection<WeblogEntryWrapper>> entries) {
        return entries.values().stream().mapToInt(Collection::size).sum();
    }

    // ------------------------------------------------------- the entry itself

    @Test
    void aPublishedEntryIsShown() throws Exception {
        when(entryManager.getWeblogEntryByAnchor(any(), eq("hello")))
                .thenReturn(entry("hello", "Hello", PUBLISHED_AT,
                        WeblogEntry.PubStatus.PUBLISHED));

        withRuntimeConfig(() -> {
            WeblogEntriesPermalinkPager pager = pager("hello");

            assertNotNull(pager.getEntries(), "A published permalink must render");
            assertEquals(1, entryCount(pager.getEntries()),
                    "A permalink shows exactly one entry");
        });
    }

    @Test
    void aDraftEntryIsNotShownOnTheLiveWeblog() throws Exception {
        // Anyone can guess a permalink. A draft must stay invisible until it is
        // published, so the pager returns nothing rather than the draft body.
        when(entryManager.getWeblogEntryByAnchor(any(), eq("secret")))
                .thenReturn(entry("secret", "Secret", PUBLISHED_AT,
                        WeblogEntry.PubStatus.DRAFT));

        withRuntimeConfig(() -> {
            WeblogEntriesPermalinkPager pager = pager("secret");

            assertNull(pager.getEntries(),
                    "A draft must never be rendered through the live permalink pager");
        });
    }

    @Test
    void anUnknownAnchorProducesNoEntries() throws Exception {
        when(entryManager.getWeblogEntryByAnchor(any(), anyString())).thenReturn(null);

        withRuntimeConfig(() -> {
            WeblogEntriesPermalinkPager pager = pager("does-not-exist");

            assertNull(pager.getEntries(),
                    "A permalink for an entry that does not exist must render nothing");
        });
    }

    @Test
    void aFailingLookupProducesNoEntriesRatherThanBreakingThePage() throws Exception {
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenThrow(new WebloggerException("database is down"));

        withRuntimeConfig(() -> {
            WeblogEntriesPermalinkPager pager = pager("hello");

            assertNull(pager.getEntries(), "A failed lookup must not propagate out of the pager");
        });
    }

    // ---------------------------------------------------- neighbour navigation

    @Test
    void neighbourLinksAreAbsentWhenThereAreNoNeighbours() throws Exception {
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenReturn(entry("hello", "Hello", PUBLISHED_AT,
                        WeblogEntry.PubStatus.PUBLISHED));
        when(entryManager.getNextEntry(any(), any(), any())).thenReturn(null);
        when(entryManager.getPreviousEntry(any(), any(), any())).thenReturn(null);

        withRuntimeConfig(() -> {
            WeblogEntriesPermalinkPager pager = pager("hello");

            assertNull(pager.getNextLink(),
                    "The newest entry has nothing after it; a next link here 404s");
            assertNull(pager.getNextName());
            assertNull(pager.getPrevLink(),
                    "The oldest entry has nothing before it");
            assertNull(pager.getPrevName());
        });
    }

    @Test
    void neighbourLinksPointAtTheNeighbouringAnchors() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenReturn(entry("hello", "Hello", PUBLISHED_AT,
                        WeblogEntry.PubStatus.PUBLISHED));
        when(entryManager.getNextEntry(any(), any(), any()))
                .thenReturn(entry("newer", "A newer post", PUBLISHED_AT + 1_000,
                        WeblogEntry.PubStatus.PUBLISHED));
        when(entryManager.getPreviousEntry(any(), any(), any()))
                .thenReturn(entry("older", "An older post", PUBLISHED_AT - 1_000,
                        WeblogEntry.PubStatus.PUBLISHED));

        withRuntimeConfig(() -> {
            WeblogEntriesPermalinkPager pager = pager("hello");

            assertEquals("entry:newer", pager.getNextLink(),
                    "Next must target the neighbouring entry, not a page index");
            assertEquals("entry:older", pager.getPrevLink());
        });
    }

    @Test
    void neighbourLabelsCarryTheNeighbouringTitles() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenReturn(entry("hello", "Hello", PUBLISHED_AT,
                        WeblogEntry.PubStatus.PUBLISHED));
        when(entryManager.getNextEntry(any(), any(), any()))
                .thenReturn(entry("newer", "A newer post", PUBLISHED_AT + 1_000,
                        WeblogEntry.PubStatus.PUBLISHED));

        withRuntimeConfig(() -> {
            WeblogEntriesPermalinkPager pager = pager("hello");

            assertNotNull(pager.getNextName(), "A next link needs a label to render");
            assertTrue(!pager.getNextName().isBlank(),
                    "The next control must not render as an empty caption");
            assertTrue(pager.getNextName().contains("newer"),
                    "The label is the neighbouring title so readers know where the "
                            + "link goes; got: " + pager.getNextName());
        });
    }

    @Test
    void overlongNeighbourTitlesAreTruncated() throws Exception {
        // Navigation labels sit inline in the template; an untruncated title
        // from a long post would break the layout.
        stubUrlStrategyEchoingPageNumber();
        String longTitle = "This is an extremely long entry title that would not fit inline";
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenReturn(entry("hello", "Hello", PUBLISHED_AT,
                        WeblogEntry.PubStatus.PUBLISHED));
        when(entryManager.getPreviousEntry(any(), any(), any()))
                .thenReturn(entry("older", longTitle, PUBLISHED_AT - 1_000,
                        WeblogEntry.PubStatus.PUBLISHED));

        withRuntimeConfig(() -> {
            WeblogEntriesPermalinkPager pager = pager("hello");

            String label = pager.getPrevName();
            assertNotNull(label);
            assertTrue(!label.isBlank(),
                    "Truncation must shorten the title, not erase the label entirely");
            assertTrue(label.startsWith("This is an"),
                    "The truncated label must keep the start of the title so it is "
                            + "still recognisable; got: " + label);
            assertTrue(label.length() < longTitle.length(),
                    "A long neighbour title must be shortened for the nav label; got: " + label);
        });
    }

    @Test
    void aFailingNeighbourLookupLeavesTheEntryItselfRenderable() throws Exception {
        // Losing the navigation is far better than losing the post.
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenReturn(entry("hello", "Hello", PUBLISHED_AT,
                        WeblogEntry.PubStatus.PUBLISHED));
        when(entryManager.getNextEntry(any(), any(), any()))
                .thenThrow(new WebloggerException("database is down"));

        withRuntimeConfig(() -> {
            WeblogEntriesPermalinkPager pager = pager("hello");

            assertNull(pager.getNextLink(), "A failed neighbour lookup yields no link");
            assertEquals(1, entryCount(pager.getEntries()),
                    "The entry itself must still render");
        });
    }

    @Test
    void aFutureDatedNeighbourIsHiddenUntilItIsDue() throws Exception {
        // Scheduled posts exist in the database before their publication time.
        // Linking to one would expose it early, so the neighbour is dropped.
        stubUrlStrategyEchoingPageNumber();
        long tomorrow = System.currentTimeMillis() + 86_400_000L;
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenReturn(entry("hello", "Hello", PUBLISHED_AT,
                        WeblogEntry.PubStatus.PUBLISHED));
        when(entryManager.getNextEntry(any(), any(), any()))
                .thenReturn(entry("scheduled", "Scheduled", tomorrow,
                        WeblogEntry.PubStatus.PUBLISHED));

        withRuntimeConfig(() -> {
            WeblogEntriesPermalinkPager pager = pager("hello");

            assertNull(pager.getNextLink(),
                    "An entry scheduled for tomorrow must not be linked today");
            assertNull(pager.getNextName());
        });
    }

    @Test
    void aFutureDatedPreviousNeighbourIsAlsoHidden() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        long tomorrow = System.currentTimeMillis() + 86_400_000L;
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenReturn(entry("hello", "Hello", PUBLISHED_AT,
                        WeblogEntry.PubStatus.PUBLISHED));
        when(entryManager.getPreviousEntry(any(), any(), any()))
                .thenReturn(entry("scheduled", "Scheduled", tomorrow,
                        WeblogEntry.PubStatus.PUBLISHED));

        withRuntimeConfig(() -> {
            WeblogEntriesPermalinkPager pager = pager("hello");

            assertNull(pager.getPrevLink(),
                    "A scheduled entry must not be linked from the previous control either");
        });
    }

    @Test
    void aNeighbourPublishedAnInstantAgoIsStillLinked() throws Exception {
        // Guards the guard above: the cutoff is "after now", so an entry whose
        // publication time has just passed must remain visible.
        stubUrlStrategyEchoingPageNumber();
        long amomentAgo = System.currentTimeMillis() - 1_000L;
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenReturn(entry("hello", "Hello", PUBLISHED_AT,
                        WeblogEntry.PubStatus.PUBLISHED));
        when(entryManager.getNextEntry(any(), any(), any()))
                .thenReturn(entry("justout", "Just out", amomentAgo,
                        WeblogEntry.PubStatus.PUBLISHED));

        withRuntimeConfig(() -> {
            WeblogEntriesPermalinkPager pager = pager("hello");

            assertEquals("entry:justout", pager.getNextLink(),
                    "An entry published a second ago is due and must be linked");
        });
    }

    @Test
    void aFailingPreviousNeighbourLookupLeavesTheEntryRenderable() throws Exception {
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenReturn(entry("hello", "Hello", PUBLISHED_AT,
                        WeblogEntry.PubStatus.PUBLISHED));
        when(entryManager.getPreviousEntry(any(), any(), any()))
                .thenThrow(new WebloggerException("database is down"));

        withRuntimeConfig(() -> {
            WeblogEntriesPermalinkPager pager = pager("hello");

            assertNull(pager.getPrevLink());
            assertEquals(1, entryCount(pager.getEntries()),
                    "The entry itself must still render");
        });
    }

    @Test
    void previewSurvivesAFailingLookup() throws Exception {
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenThrow(new WebloggerException("database is down"));

        withRuntimeConfig(() -> {
            WeblogEntriesPreviewPager pager = new WeblogEntriesPreviewPager(urlStrategy, weblogger,
                    weblog(), "en_US", null, "draft", null, null, null, 0);

            assertNull(pager.getEntries(),
                    "A failed preview lookup must not propagate out of the pager");
        });
    }

    @Test
    void permalinkHomeLinkReturnsToTheWeblogFrontPage() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenReturn(entry("hello", "Hello", PUBLISHED_AT,
                        WeblogEntry.PubStatus.PUBLISHED));

        withRuntimeConfig(() -> {
            WeblogEntriesPermalinkPager pager = pager("hello");

            assertEquals("collection:date=null:page=0", pager.getHomeLink(),
                    "Home must drop the entry anchor and return to the front page");
            assertIsRenderedLabel(pager.getHomeName(), "weblogEntriesPager.single.home",
                    "The home control of a permalink");
        });
    }

    @Test
    void permalinkHasNoAdjacentCollections() {
        withRuntimeConfig(() -> {
            WeblogEntriesPermalinkPager pager = pager("hello");

            assertNull(pager.getNextCollectionLink());
            assertNull(pager.getPrevCollectionLink());
        });
    }

    // --------------------------------------------------------------- preview

    @Test
    void previewShowsADraftThatTheLivePagerWouldHide() throws Exception {
        // This is the whole reason the preview subclass exists: authors have to
        // be able to see an unpublished post before publishing it.
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenReturn(entry("secret", "Secret", PUBLISHED_AT,
                        WeblogEntry.PubStatus.DRAFT));

        withRuntimeConfig(() -> {
            WeblogEntriesPreviewPager pager = new WeblogEntriesPreviewPager(urlStrategy, weblogger,
                    weblog(), "en_US", null, "secret", null, null, null, 0);

            assertNotNull(pager.getEntries(), "A preview must render a draft");
            assertEquals(1, entryCount(pager.getEntries()));

            // The preview works on a clone so it can adjust the publication
            // time without touching the stored entry. The clone must actually
            // carry the draft's content across, or the author previews a blank
            // post.
            WeblogEntryWrapper previewed =
                    pager.getEntries().values().iterator().next().iterator().next();
            assertEquals("Secret", previewed.getTitle(),
                    "The previewed clone must carry the draft's title");
            assertEquals("secret", previewed.getAnchor(),
                    "The previewed clone must carry the draft's anchor");
        });
    }

    @Test
    void previewGivesAnUnscheduledDraftAPublicationTimeSoItCanBeRendered() throws Exception {
        // A draft that was never scheduled has a null pubTime. The day-grouped
        // map is keyed by publication time, so the preview substitutes "now"
        // rather than failing on the null key.
        WeblogEntry unscheduled = entry("draft", "Draft", 0L, WeblogEntry.PubStatus.DRAFT);
        unscheduled.setPubTime(null);
        when(entryManager.getWeblogEntryByAnchor(any(), anyString())).thenReturn(unscheduled);

        withRuntimeConfig(() -> {
            Date before = new Date();
            WeblogEntriesPreviewPager pager = new WeblogEntriesPreviewPager(urlStrategy, weblogger,
                    weblog(), "en_US", null, "draft", null, null, null, 0);

            Map<Date, ?> entries = pager.getEntries();
            assertNotNull(entries, "An unscheduled draft must still preview");
            Date key = entries.keySet().iterator().next();
            assertTrue(!key.before(before),
                    "An unscheduled draft must be filed under the current time, not "
                            + "the epoch; got " + key);
        });
    }

    @Test
    void previewOfAnUnknownAnchorProducesNoEntries() throws Exception {
        when(entryManager.getWeblogEntryByAnchor(any(), anyString())).thenReturn(null);

        withRuntimeConfig(() -> {
            WeblogEntriesPreviewPager pager = new WeblogEntriesPreviewPager(urlStrategy, weblogger,
                    weblog(), "en_US", null, "does-not-exist", null, null, null, 0);

            assertNull(pager.getEntries());
        });
    }
}
