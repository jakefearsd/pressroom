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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wikantik.pressroom.WebloggerException;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WeblogEntriesLatestPager}, the pager behind every
 * weblog homepage.
 *
 * <p>The page-size arithmetic here is unusual: entries arrive grouped into days
 * and the pager has to stop after {@code length} entries in total, counting
 * across the day boundaries. Miscounting shows a partial day or an extra entry,
 * and the same counter decides whether a "next page" link is offered at all.
 */
class WeblogEntriesLatestPagerTest extends EntriesPagerTestSupport {

    private WeblogEntriesLatestPager pager(int page) {
        return new WeblogEntriesLatestPager(urlStrategy, weblogger, weblog(), "en_US", null, null, null,
                null, null, page);
    }

    /** Entries grouped into days, as getWeblogEntryObjectMap returns them. */
    private static Map<Date, List<WeblogEntry>> days(int... entriesPerDay) {
        Map<Date, List<WeblogEntry>> map = new LinkedHashMap<>();
        long day = 0;
        for (int count : entriesPerDay) {
            List<WeblogEntry> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                WeblogEntry entry = new WeblogEntry();
                entry.setAnchor("entry-" + day + "-" + i);
                entry.setPubTime(new Timestamp(day * 86_400_000L + i));
                entries.add(entry);
            }
            map.put(new Date(day * 86_400_000L), entries);
            day++;
        }
        return map;
    }

    private static int totalEntries(Map<Date, ? extends List<?>> entries) {
        return entries.values().stream().mapToInt(List::size).sum();
    }

    @Test
    void exactlyOnePageOfEntriesReportsNoFurtherPages() throws Exception {
        // PAGE_SIZE entries spread over several days is the last page; the
        // counter must not report more just because there were multiple days.
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(3, 3, 4));

        withRuntimeConfig(() -> {
            WeblogEntriesLatestPager pager = pager(0);

            assertEquals(PAGE_SIZE, totalEntries(pager.getEntries()),
                    "A full page of entries must all be shown");
            assertFalse(pager.hasMoreEntries(),
                    "Exactly one page worth of entries means there is no next page");
            assertNull(pager.getNextLink(),
                    "No next link may be offered when the collection ended here");
        });
    }

    @Test
    void oneExtraEntryReportsAFurtherPageAndIsNotItselfDisplayed() throws Exception {
        // PAGE_SIZE+1 is the smallest signal that another page exists.
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(3, 3, 5));

        withRuntimeConfig(() -> {
            WeblogEntriesLatestPager pager = pager(0);

            assertEquals(PAGE_SIZE, totalEntries(pager.getEntries()),
                    "The probe entry must be dropped, not rendered as an extra post");
            assertTrue(pager.hasMoreEntries(), "One entry past the page size means more pages");
            assertEquals("collection:date=null:page=1", pager.getNextLink(),
                    "Page 0's next link must target page 1");
        });
    }

    @Test
    void theCutoffIsCountedAcrossDaysNotWithinEachDay() throws Exception {
        // A single day holding more than a page must still be truncated to the
        // page size; counting per-day would render the whole day.
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(15));

        withRuntimeConfig(() -> {
            WeblogEntriesLatestPager pager = pager(0);

            assertEquals(PAGE_SIZE, totalEntries(pager.getEntries()),
                    "A day bigger than the page must be cut at the page size");
            assertTrue(pager.hasMoreEntries());
        });
    }

    @Test
    void daysLeftEmptyByTheCutoffAreOmittedFromTheMap() throws Exception {
        // Templates iterate the day map and print a date heading for each key.
        // A day whose entries were all cut would render as a heading with
        // nothing under it.
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(10, 5));

        withRuntimeConfig(() -> {
            WeblogEntriesLatestPager pager = pager(0);

            assertEquals(1, pager.getEntries().size(),
                    "The day that was entirely cut off must not appear as an empty "
                            + "date heading");
        });
    }

    @Test
    void entriesAreOrderedNewestDayFirst() throws Exception {
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(1, 1, 1));

        withRuntimeConfig(() -> {
            WeblogEntriesLatestPager pager = pager(0);

            List<Date> dayKeys = List.copyOf(pager.getEntries().keySet());
            assertEquals(3, dayKeys.size());
            assertTrue(dayKeys.get(0).after(dayKeys.get(1)),
                    "A weblog homepage lists the newest day first; got " + dayKeys);
            assertTrue(dayKeys.get(1).after(dayKeys.get(2)));
        });
    }

    @Test
    void searchCriteriaCarryTheOffsetPageSizeAndPublishedFilter() throws Exception {
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(Map.of());

        withRuntimeConfig(() -> pager(2));

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(entryManager).getWeblogEntryObjectMap(criteria.capture());

        assertEquals(2 * PAGE_SIZE, criteria.getValue().getOffset(),
                "Page 2 must start one full page past page 1");
        assertEquals(PAGE_SIZE + 1, criteria.getValue().getMaxResults(),
                "One extra entry is fetched purely to detect a further page");
        assertEquals(WeblogEntry.PubStatus.PUBLISHED, criteria.getValue().getStatus(),
                "A weblog homepage must never show drafts");
        assertNotNull(criteria.getValue().getEndDate(),
                "The homepage must exclude future-dated entries with an end date");
    }

    @Test
    void everyFilterIsCarriedIntoTheQuery() throws Exception {
        // Each of these is a separate setter on the criteria object. Dropping
        // any one silently widens the homepage: the wrong weblog, an ignored
        // category or tag filter, or every language at once.
        com.wikantik.pressroom.pojos.Weblog weblog = weblog();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(Map.of());

        withRuntimeConfig(() -> new WeblogEntriesLatestPager(urlStrategy, weblogger, weblog, "en_US", null,
                null, null, "Java", List.of("testing"), 0));

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(entryManager).getWeblogEntryObjectMap(criteria.capture());
        WeblogEntrySearchCriteria wesc = criteria.getValue();

        assertEquals(weblog, wesc.getWeblog(), "The query must be scoped to this weblog");
        assertEquals("Java", wesc.getCatName(), "The category filter must reach the query");
        assertEquals(List.of("testing"), wesc.getTags(), "The tag filter must reach the query");
        assertEquals("en_US", wesc.getLocale(), "The locale filter must reach the query");
    }

    @Test
    void futureDatedEntriesAreExcludedByAnEndDateOfNow() throws Exception {
        // Scheduled posts sit in the table with a publication time in the
        // future. Without this bound they would appear on the homepage early.
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(Map.of());

        Date before = new Date();
        withRuntimeConfig(() -> pager(0));

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(entryManager).getWeblogEntryObjectMap(criteria.capture());

        Date endDate = criteria.getValue().getEndDate();
        assertNotNull(endDate, "The homepage must bound the query at the present moment");
        assertFalse(endDate.before(before),
                "The end date must be now, not some earlier instant; got " + endDate);
    }

    @Test
    void entriesAreFetchedOnceAndReused() throws Exception {
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(2));

        withRuntimeConfig(() -> {
            WeblogEntriesLatestPager pager = pager(0);
            pager.getEntries();
            pager.getEntries();
        });

        // The constructor primes the collection; later reads must be free.
        verify(entryManager).getWeblogEntryObjectMap(any());
    }

    @Test
    void aFailingQueryLeavesAnEmptyPagerRatherThanBreakingTheWeblog() throws Exception {
        when(entryManager.getWeblogEntryObjectMap(any()))
                .thenThrow(new WebloggerException("database is down"));

        withRuntimeConfig(() -> {
            WeblogEntriesLatestPager pager = pager(0);

            assertTrue(pager.getEntries().isEmpty(),
                    "A failed lookup must render an empty weblog, never null");
            assertFalse(pager.hasMoreEntries());
        });
    }
}
