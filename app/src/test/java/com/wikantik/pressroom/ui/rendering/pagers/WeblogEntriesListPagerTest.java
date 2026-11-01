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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WeblogEntriesListPager}, the flat entry pager behind
 * the site-wide entry feeds and the front-page entry lists in SiteModel.
 *
 * <p>Its {@code lastUpdated} feeds straight into {@code <updated>} in
 * site-entries-atom.vm and {@code <lastBuildDate>} in site-entries-rss.vm, so
 * an under-reported value tells every aggregator that nothing changed.
 */
class WeblogEntriesListPagerTest {

    private static final String BASE_URL = "http://localhost/pressroom";
    private static final int LENGTH = 3;

    private final URLStrategy urlStrategy = mock(URLStrategy.class);

    private static WeblogEntry entry(long updateMillis, long pubMillis) {
        WeblogEntry entry = new WeblogEntry();
        entry.setUpdateTime(new Timestamp(updateMillis));
        entry.setPubTime(new Timestamp(pubMillis));
        return entry;
    }

    private static List<WeblogEntry> entries(int count) {
        List<WeblogEntry> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(entry(1_000L + i, 1_000L + i));
        }
        return list;
    }

    /** The facade the pager under test is constructed with; set per test by {@link #withEntryManager}. */
    private Weblogger weblogger;

    /**
     * Runs the body with {@link #weblogger} answering with the supplied manager.
     * Nothing is installed statically: the pager takes the facade in its
     * constructor (plan Task 11).
     */
    private void withEntryManager(WeblogEntryManager manager, Runnable body) {
        weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(manager);
        body.run();
    }

    @Test
    void entriesComeFromTheInjectedFacadesEntryManager() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getWeblogEntries(any())).thenReturn(entries(2));

        withEntryManager(manager, () -> {
            assertEquals(2, pager(0, -1).getItems().size(),
                    "the injected manager's entries are the page");
        });
        verify(manager).getWeblogEntries(any());
    }

    private WeblogEntriesListPager pager(int pageNum, int sinceDays) {
        return new WeblogEntriesListPager(urlStrategy, weblogger, BASE_URL, new Weblog(), null, null,
                null, "en", sinceDays, pageNum, LENGTH);
    }

    // ------------------------------------------------------------- page edges

    @Test
    void exactlyOnePageOfResultsReportsNoFurtherPages() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getWeblogEntries(any())).thenReturn(entries(LENGTH));

        withEntryManager(manager, () -> {
            WeblogEntriesListPager pager = pager(0, -1);

            assertEquals(LENGTH, pager.getItems().size());
            assertFalse(pager.hasMoreItems(),
                    "Exactly one page worth of rows means there is no next page");
            assertNull(pager.getNextLink());
        });
    }

    @Test
    void oneExtraRowReportsAFurtherPageButIsNotItselfDisplayed() throws Exception {
        // The `rawEntries.size() > length` test is the whole has-more decision.
        // LENGTH and LENGTH+1 bracket it.
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getWeblogEntries(any())).thenReturn(entries(LENGTH + 1));

        withEntryManager(manager, () -> {
            WeblogEntriesListPager pager = pager(0, -1);

            assertEquals(LENGTH, pager.getItems().size(),
                    "The probe row must not be rendered as a fourth entry");
            assertTrue(pager.hasMoreItems());
            assertEquals(BASE_URL + "?page=1", pager.getNextLink());
        });
    }

    @Test
    void firstPageOffersNoPreviousLinkAndLaterPagesDo() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getWeblogEntries(any())).thenReturn(entries(LENGTH));

        withEntryManager(manager, () -> {
            assertNull(pager(0, -1).getPrevLink(), "Page 0 has nothing before it");
            assertEquals(BASE_URL + "?page=0", pager(1, -1).getPrevLink(),
                    "Page 1 must lead back to page 0");
        });
    }

    @Test
    void searchCriteriaCarryTheOffsetPageSizeAndPublishedFilter() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getWeblogEntries(any())).thenReturn(List.of());

        withEntryManager(manager, () -> pager(2, -1));

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(manager).getWeblogEntries(criteria.capture());

        assertEquals(2 * LENGTH, criteria.getValue().getOffset(),
                "Page 2 of 3-item pages starts at row 6");
        assertEquals(LENGTH + 1, criteria.getValue().getMaxResults(),
                "One extra row is fetched purely to detect a further page");
        assertEquals(WeblogEntry.PubStatus.PUBLISHED, criteria.getValue().getStatus(),
                "A public listing must only show published entries; drafts would leak");
        assertEquals("en", criteria.getValue().getLocale());
    }

    @Test
    void everyFilterIsCarriedIntoTheQuery() throws Exception {
        // Each is a separate setter on the criteria object; dropping any one
        // widens the site-wide listing to content it was never meant to show.
        Weblog queryWeblog = new Weblog();
        com.wikantik.pressroom.pojos.User queryUser =
                new com.wikantik.pressroom.pojos.User();
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getWeblogEntries(any())).thenReturn(List.of());

        withEntryManager(manager, () -> new WeblogEntriesListPager(urlStrategy, weblogger, BASE_URL,
                queryWeblog, queryUser, "Java", List.of("testing"), "en", -1, 0, LENGTH));

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(manager).getWeblogEntries(criteria.capture());
        WeblogEntrySearchCriteria wesc = criteria.getValue();

        assertEquals(queryWeblog, wesc.getWeblog(), "The weblog filter must reach the query");
        assertEquals(queryUser, wesc.getUser(), "The author filter must reach the query");
        assertEquals("Java", wesc.getCatName(), "The category filter must reach the query");
        assertEquals(List.of("testing"), wesc.getTags(), "The tag filter must reach the query");
        assertEquals("en", wesc.getLocale(), "The locale filter must reach the query");
    }

    @Test
    void sinceDaysOfZeroMeansNoDateFloor() throws Exception {
        // The guard is `sinceDays > 0`, so zero must behave like "no window"
        // rather than "since this instant", which would empty the list.
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getWeblogEntries(any())).thenReturn(List.of());

        withEntryManager(manager, () -> pager(0, 0));

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(manager).getWeblogEntries(criteria.capture());

        assertNull(criteria.getValue().getStartDate(),
                "sinceDays=0 must not become a start date of 'now'");
    }

    @Test
    void positiveSinceDaysAppliesADateFloorInThePast() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getWeblogEntries(any())).thenReturn(List.of());

        withEntryManager(manager, () -> pager(0, 30));

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(manager).getWeblogEntries(criteria.capture());

        assertNotNull(criteria.getValue().getStartDate(), "sinceDays=30 must set a floor");
        // Checking only "before now" would pass for a floor of one second ago
        // or one century ago -- either silently changes the listing contents.
        // Calendar days in the server's zone, as the code counts them: a fixed
        // 24 h per day is an hour off whenever the window crosses a DST change.
        long thirtyDaysAgo = java.time.ZonedDateTime.now().minusDays(30).toInstant().toEpochMilli();
        long millisAgo = System.currentTimeMillis() - criteria.getValue().getStartDate().getTime();
        assertTrue(Math.abs(criteria.getValue().getStartDate().getTime() - thirtyDaysAgo) < 60_000L,
                "Expected a floor about 30 days ago, but it was "
                        + (millisAgo / 86_400_000.0) + " days ago");
    }

    // ----------------------------------------------------------- lastUpdated

    @Test
    void lastUpdatedIsTheNewestUpdateTimeEvenWhenItIsNotTheFirstItem() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getWeblogEntries(any())).thenReturn(List.of(
                entry(1_000L, 1_000L),
                entry(9_000L, 9_000L),
                entry(4_000L, 4_000L)));

        withEntryManager(manager, () -> {
            WeblogEntriesListPager pager = pager(0, -1);

            assertEquals(new Date(9_000L), pager.getLastUpdated(),
                    "lastUpdated must scan the whole page, not trust the first item");
        });
    }

    @Test
    void lastUpdatedTracksUpdateTimesEvenWhenPublicationTimesAreMuchOlder() throws Exception {
        // An entry that was published long ago and edited today is the normal
        // case for a correction. The scan compares update times, so it must
        // also report an update time -- reporting the publication time instead
        // pushes the feed's timestamp backwards past every entry in the page,
        // and aggregators holding a newer If-Modified-Since get a 304 and never
        // see the correction.
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getWeblogEntries(any())).thenReturn(List.of(
                entry(10_000L, 1_000L),
                entry(20_000L, 2_000L)));

        withEntryManager(manager, () -> {
            WeblogEntriesListPager pager = pager(0, -1);

            assertEquals(new Date(20_000L), pager.getLastUpdated(),
                    "lastUpdated must be the newest update time (20000), not the "
                            + "publication time of the entry that carried it");
        });
    }

    @Test
    void lastUpdatedIsComputedOnceAndReused() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getWeblogEntries(any())).thenReturn(List.of(entry(1_000L, 1_000L)));

        withEntryManager(manager, () -> {
            WeblogEntriesListPager pager = pager(0, -1);

            assertEquals(pager.getLastUpdated(), pager.getLastUpdated(),
                    "A feed must not report a different timestamp each time it is read");
        });
    }

    @Test
    void anEmptyPageReportsNowSoTheFeedStillValidates() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getWeblogEntries(any())).thenReturn(List.of());

        withEntryManager(manager, () -> {
            Date before = new Date();
            WeblogEntriesListPager pager = pager(0, -1);
            Date lastUpdated = pager.getLastUpdated();

            assertNotNull(lastUpdated, "An empty feed still needs a timestamp");
            assertFalse(lastUpdated.before(before),
                    "With no entries the timestamp must be 'now', not the epoch");
        });
    }

    @Test
    void aFailingQueryLeavesAnEmptyPagerRatherThanBreakingTheFeed() throws Exception {
        WeblogEntryManager manager = mock(WeblogEntryManager.class);
        when(manager.getWeblogEntries(any()))
                .thenThrow(new WebloggerException("database is down"));

        withEntryManager(manager, () -> {
            WeblogEntriesListPager pager = pager(0, -1);

            assertTrue(pager.getItems().isEmpty(),
                    "A failed lookup must yield an empty list, never null");
            assertFalse(pager.hasMoreItems());
        });
    }
}
