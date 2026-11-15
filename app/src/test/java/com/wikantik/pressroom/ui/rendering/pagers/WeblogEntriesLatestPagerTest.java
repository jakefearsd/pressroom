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

    // ------------------------------------------------------- blog entry order

    private WeblogEntriesLatestPager pagerFor(com.wikantik.pressroom.pojos.Weblog blog, String cat,
            List<String> tags, int page) {
        return new WeblogEntriesLatestPager(urlStrategy, weblogger, blog, "en_US", null, null, null,
                cat, tags, page);
    }

    private static com.wikantik.pressroom.pojos.Weblog oldestFirstBlog() {
        com.wikantik.pressroom.pojos.Weblog blog = weblog();
        blog.setEntryOrder("OLDEST");
        return blog;
    }

    /** The anchors in the order a theme iterating the day map would print them. */
    private static List<String> anchors(Map<Date, ? extends List<com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper>> entries) {
        return entries.values().stream().flatMap(List::stream)
                .map(com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper::getAnchor).toList();
    }

    /** Posts A (oldest), B, C (newest), one per day, the way the manager hands them over. */
    private Map<Date, List<WeblogEntry>> threeDays() {
        Map<Date, List<WeblogEntry>> map = days(1, 1, 1);
        int n = 0;
        for (List<WeblogEntry> day : map.values()) {
            day.get(0).setAnchor(String.valueOf((char) ('A' + n++)));
        }
        return map;
    }

    @Test
    void anOldestFirstBlogListsItsOldestPostFirst() throws Exception {
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(threeDays());

        withRuntimeConfig(() -> {
            WeblogEntriesLatestPager pager = pagerFor(oldestFirstBlog(), null, null, 0);

            List<Date> dayKeys = List.copyOf(pager.getEntries().keySet());
            assertTrue(dayKeys.get(0).before(dayKeys.get(1)) && dayKeys.get(1).before(dayKeys.get(2)),
                    "An oldest-first blog lists the oldest day first; got " + dayKeys);
            assertEquals(List.of("A", "B", "C"), anchors(pager.getEntries()));
        });

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(entryManager).getWeblogEntryObjectMap(criteria.capture());
        assertEquals(WeblogEntrySearchCriteria.SortOrder.ASCENDING, criteria.getValue().getSortOrder());
    }

    @Test
    void aBlogWithNoOrderSetListsNewestFirst() throws Exception {
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(threeDays());

        withRuntimeConfig(() -> assertEquals(List.of("C", "B", "A"),
                anchors(pagerFor(weblog(), null, null, 0).getEntries())));

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(entryManager).getWeblogEntryObjectMap(criteria.capture());
        assertEquals(WeblogEntrySearchCriteria.SortOrder.DESCENDING, criteria.getValue().getSortOrder());
    }

    @Test
    void anOldestFirstCategoryListingRunsOldestFirst() throws Exception {
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(threeDays());

        withRuntimeConfig(() -> assertEquals(List.of("A", "B", "C"),
                anchors(pagerFor(oldestFirstBlog(), "Java", null, 0).getEntries())));
    }

    @Test
    void anOldestFirstTagListingRunsOldestFirst() throws Exception {
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(threeDays());

        withRuntimeConfig(() -> assertEquals(List.of("A", "B", "C"),
                anchors(pagerFor(oldestFirstBlog(), null, List.of("testing"), 0).getEntries())));
    }

    @Test
    void anOldestFirstBlogPagesForward() throws Exception {
        com.wikantik.pressroom.pojos.Weblog blog = oldestFirstBlog();
        blog.setEntryDisplayCount(2);
        // The manager answers page 0 with two posts plus the probe entry.
        Map<Date, List<WeblogEntry>> all = threeDays();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(all);

        withRuntimeConfig(() -> {
            WeblogEntriesLatestPager first = pagerFor(blog, null, null, 0);
            assertEquals(List.of("A", "B"), anchors(first.getEntries()));
            assertTrue(first.hasMoreEntries());
        });

        Map<Date, List<WeblogEntry>> rest = new LinkedHashMap<>();
        all.entrySet().stream().skip(2).forEach(e -> rest.put(e.getKey(), e.getValue()));
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(rest);
        withRuntimeConfig(() -> {
            WeblogEntriesLatestPager second = pagerFor(blog, null, null, 1);
            assertEquals(List.of("C"), anchors(second.getEntries()));
            assertFalse(second.hasMoreEntries());
        });
    }

    // --------------------------------------------------------------- pinned

    private static WeblogEntry pinnedEntry(String anchor, long pubTime, boolean pinned) {
        WeblogEntry entry = new WeblogEntry();
        entry.setAnchor(anchor);
        entry.setPubTime(new Timestamp(pubTime));
        entry.setPinned(pinned);
        entry.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        return entry;
    }

    /**
     * A manager that honours the pinned filter the way the real query does:
     * the object map and the plain list both read from the same posts,
     * keeping the ones the criteria's pinned flag allows, newest first (or
     * oldest first on an ascending sort), then applying offset and maximum.
     */
    private void stubManagerWith(WeblogEntry... posts) throws Exception {
        when(entryManager.getWeblogEntries(any())).thenAnswer(inv -> filtered(inv.getArgument(0), posts));
        when(entryManager.getWeblogEntryObjectMap(any())).thenAnswer(inv -> {
            Map<Date, List<WeblogEntry>> map = new LinkedHashMap<>();
            for (WeblogEntry e : filtered(inv.getArgument(0), posts)) {
                map.computeIfAbsent(new Date(e.getPubTime().getTime() / 86_400_000L * 86_400_000L),
                        k -> new ArrayList<>()).add(e);
            }
            return map;
        });
    }

    private static List<WeblogEntry> filtered(WeblogEntrySearchCriteria wesc, WeblogEntry[] posts) {
        List<WeblogEntry> out = new ArrayList<>();
        for (WeblogEntry e : posts) {
            boolean ok = (wesc.getPinned() == null || wesc.getPinned() == e.getPinned())
                    && (wesc.getStatus() == null || wesc.getStatus() == e.getStatus())
                    && (wesc.getEndDate() == null || !e.getPubTime().after(wesc.getEndDate()));
            if (ok) {
                out.add(e);
            }
        }
        out.sort(java.util.Comparator.comparing(WeblogEntry::getPubTime));
        if (wesc.getSortOrder() == WeblogEntrySearchCriteria.SortOrder.DESCENDING) {
            java.util.Collections.reverse(out);
        }
        int from = Math.min(wesc.getOffset(), out.size());
        int to = wesc.getMaxResults() > 0 ? Math.min(out.size(), from + wesc.getMaxResults()) : out.size();
        return new ArrayList<>(out.subList(from, to));
    }

    private static List<String> pinnedAnchors(WeblogEntriesLatestPager pager) {
        return pager.getPinnedEntries().stream()
                .map(com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper::getAnchor).toList();
    }

    private static final long DAY = 86_400_000L;

    @Test
    void aPinnedPostLeadsPageOneOfTheHomeListing() throws Exception {
        stubManagerWith(pinnedEntry("A", DAY, true), pinnedEntry("B", 2 * DAY, false),
                pinnedEntry("C", 3 * DAY, false));

        withRuntimeConfig(() -> {
            WeblogEntriesLatestPager pager = pagerFor(weblog(), null, null, 0);

            assertEquals(List.of("A"), pinnedAnchors(pager));
            assertEquals(List.of("C", "B"), anchors(pager.getEntries()));
        });
    }

    @Test
    void homePageTwoShowsNoPinnedPostAndSkipsNone() throws Exception {
        com.wikantik.pressroom.pojos.Weblog blog = weblog();
        blog.setEntryDisplayCount(2);
        stubManagerWith(pinnedEntry("P", 9 * DAY, true), pinnedEntry("u1", DAY, false),
                pinnedEntry("u2", 2 * DAY, false), pinnedEntry("u3", 3 * DAY, false),
                pinnedEntry("u4", 4 * DAY, false), pinnedEntry("u5", 5 * DAY, false));

        List<String> seen = new ArrayList<>();
        withRuntimeConfig(() -> {
            for (int page = 0; page < 3; page++) {
                WeblogEntriesLatestPager pager = pagerFor(blog, null, null, page);
                assertEquals(page == 0 ? List.of("P") : List.of(), pinnedAnchors(pager),
                        "only page one carries the pinned rows (page " + page + ")");
                seen.addAll(anchors(pager.getEntries()));
            }
        });
        assertEquals(List.of("u5", "u4", "u3", "u2", "u1"), seen,
                "each regular post exactly once, none skipped, the pinned one not counted");
    }

    @Test
    void aCategoryListingShowsAPinnedPostInItsPlace() throws Exception {
        stubManagerWith(pinnedEntry("A", DAY, true), pinnedEntry("B", 2 * DAY, false));

        withRuntimeConfig(() -> {
            WeblogEntriesLatestPager pager = pagerFor(weblog(), "Java", null, 0);

            assertTrue(pager.getPinnedEntries().isEmpty());
            assertEquals(List.of("B", "A"), anchors(pager.getEntries()));
        });
    }

    @Test
    void aTagListingShowsAPinnedPostInItsPlace() throws Exception {
        stubManagerWith(pinnedEntry("A", DAY, true), pinnedEntry("B", 2 * DAY, false));

        withRuntimeConfig(() -> {
            WeblogEntriesLatestPager pager = pagerFor(weblog(), null, List.of("testing"), 0);

            assertTrue(pager.getPinnedEntries().isEmpty());
            assertEquals(List.of("B", "A"), anchors(pager.getEntries()));
        });
    }

    @Test
    void aPinnedPostScheduledForLaterIsNotShownYet() throws Exception {
        long tomorrow = System.currentTimeMillis() + DAY;
        stubManagerWith(pinnedEntry("A", tomorrow, true), pinnedEntry("B", DAY, false));

        withRuntimeConfig(() -> {
            WeblogEntriesLatestPager pager = pagerFor(weblog(), null, null, 0);

            assertTrue(pager.getPinnedEntries().isEmpty());
            assertEquals(List.of("B"), anchors(pager.getEntries()));
        });
    }

    @Test
    void aPinnedDraftIsNotShown() throws Exception {
        WeblogEntry draft = pinnedEntry("A", DAY, true);
        draft.setStatus(WeblogEntry.PubStatus.DRAFT);
        stubManagerWith(draft, pinnedEntry("B", 2 * DAY, false));

        withRuntimeConfig(() -> {
            WeblogEntriesLatestPager pager = pagerFor(weblog(), null, null, 0);

            assertTrue(pager.getPinnedEntries().isEmpty());
            assertEquals(List.of("B"), anchors(pager.getEntries()));
        });
    }

    @Test
    void pinsFollowTheBlogsOrder() throws Exception {
        stubManagerWith(pinnedEntry("old", DAY, true), pinnedEntry("new", 2 * DAY, true));

        withRuntimeConfig(() -> {
            assertEquals(List.of("old", "new"), pinnedAnchors(pagerFor(oldestFirstBlog(), null, null, 0)));
            assertEquals(List.of("new", "old"), pinnedAnchors(pagerFor(weblog(), null, null, 0)));
        });
    }

    @Test
    void theQueryIsAskedForPinnedPostsPublishedOnlyAndNoMoreThanTheLimit() throws Exception {
        stubManagerWith(pinnedEntry("A", DAY, true));

        withRuntimeConfig(() -> pagerFor(weblog(), null, null, 0).getPinnedEntries());

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(entryManager).getWeblogEntries(criteria.capture());
        WeblogEntrySearchCriteria wesc = criteria.getValue();
        assertEquals(Boolean.TRUE, wesc.getPinned());
        assertEquals(WeblogEntry.PubStatus.PUBLISHED, wesc.getStatus());
        assertEquals("en_US", wesc.getLocale());
        assertNotNull(wesc.getEndDate());
        assertEquals(3, wesc.getMaxResults());
    }
}
