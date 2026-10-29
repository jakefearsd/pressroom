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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import com.wikantik.pressroom.WebloggerException;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WeblogEntriesMonthPager} and
 * {@link WeblogEntriesDayPager}, the archive pagers.
 *
 * <p>These two carry a second axis of navigation on top of paging: as well as
 * next/previous <em>page</em> within a month or day, they offer next/previous
 * <em>collection</em> -- the neighbouring month or day. Both ends of that axis
 * are clamped: forward stops at today so crawlers cannot walk into an infinite
 * run of empty future archives, and backward stops at the weblog's creation
 * date for the same reason.
 */
class WeblogEntriesArchivePagerTest extends EntriesPagerTestSupport {

    /** A month comfortably in the past, so "before today" clamping is not in play. */
    private static final String PAST_MONTH = "202001";
    private static final String PAST_DAY = "20200115";

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

    /** A 6-char month string for the current month, in the weblog's timezone. */
    private static String currentMonth() {
        return format("yyyyMM", new Date());
    }

    /** An 8-char day string for today, in the weblog's timezone. */
    private static String today() {
        return format("yyyyMMdd", new Date());
    }

    private static String format(String pattern, Date date) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return formatter.format(date);
    }

    private WeblogEntriesMonthPager monthPager(Weblog weblog, String dateString, int page) {
        return new WeblogEntriesMonthPager(urlStrategy, weblogger, weblog, "en_US", null, null,
                dateString, null, null, page);
    }

    private WeblogEntriesDayPager dayPager(Weblog weblog, String dateString, int page) {
        return new WeblogEntriesDayPager(urlStrategy, weblogger, weblog, "en_US", null, null, dateString,
                null, null, page);
    }

    // ------------------------------------------------------------ month pager

    @Test
    void monthPagerQueriesOnlyThatMonth() throws Exception {
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(Map.of());

        withRuntimeConfig(() -> monthPager(weblog(), PAST_MONTH, 0));

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(entryManager).getWeblogEntryObjectMap(criteria.capture());

        assertEquals("202001", format("yyyyMM", criteria.getValue().getStartDate()),
                "The window must start inside the requested month");
        assertEquals("202001", format("yyyyMM", criteria.getValue().getEndDate()),
                "The window must end inside the requested month, or the archive "
                        + "page shows entries from a neighbouring month");
        assertEquals(WeblogEntry.PubStatus.PUBLISHED, criteria.getValue().getStatus(),
                "An archive page must never show drafts");
    }

    @Test
    void monthPagerCarriesEveryFilterIntoTheQuery() throws Exception {
        // Each of these is a separate setter on the criteria object. Dropping
        // any one of them silently widens the archive page: the wrong weblog,
        // the wrong category, every language instead of one, or the whole
        // collection instead of one page.
        Weblog weblog = weblog();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(Map.of());

        withRuntimeConfig(() -> new WeblogEntriesMonthPager(urlStrategy, weblogger, weblog, "en_US", null,
                null, PAST_MONTH, "Java", List.of("testing"), 1));

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(entryManager).getWeblogEntryObjectMap(criteria.capture());
        WeblogEntrySearchCriteria wesc = criteria.getValue();

        assertEquals(weblog, wesc.getWeblog(), "The query must be scoped to this weblog");
        assertEquals("Java", wesc.getCatName(), "The category filter must reach the query");
        assertEquals(List.of("testing"), wesc.getTags(), "The tag filter must reach the query");
        assertEquals("en_US", wesc.getLocale(), "The locale filter must reach the query");
        assertEquals(PAGE_SIZE, wesc.getOffset(), "Page 1 starts one page in");
        assertEquals(PAGE_SIZE + 1, wesc.getMaxResults(),
                "One extra entry is fetched purely to detect a further page");
    }

    @Test
    void monthPagerOffersNoNextPageWhenTheMonthFitsOnOnePage() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(4, 6));

        withRuntimeConfig(() -> {
            WeblogEntriesMonthPager pager = monthPager(weblog(), PAST_MONTH, 0);

            assertEquals(PAGE_SIZE, totalEntries(pager.getEntries()));
            assertNull(pager.getNextLink(),
                    "Exactly one page worth of entries means no further page of this month");
            assertNull(pager.getNextName());
        });
    }

    @Test
    void monthPagerOffersANextPageWhenTheMonthOverflows() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(4, 7));

        withRuntimeConfig(() -> {
            WeblogEntriesMonthPager pager = monthPager(weblog(), PAST_MONTH, 0);

            assertEquals("collection:date=" + PAST_MONTH + ":page=1", pager.getNextLink(),
                    "Paging within a month must keep the month and advance the page");
            assertIsRenderedLabel(pager.getNextName(), "weblogEntriesPager.month.next",
                    "The next-page control of a month");
            assertTrue(pager.getNextName().contains("2020"),
                    "The month label names the month being paged; got: " + pager.getNextName());
        });
    }

    @Test
    void monthPagerOffersNoPreviousPageOnItsFirstPage() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(2));

        withRuntimeConfig(() -> {
            WeblogEntriesMonthPager pager = monthPager(weblog(), PAST_MONTH, 0);

            assertNull(pager.getPrevLink(),
                    "Page 0 of a month has nothing before it; the guard is offset > 0 "
                            + "and offset is 0 exactly on page 0");
            assertNull(pager.getPrevName());
        });
    }

    @Test
    void monthPagerOffersAPreviousPageFromItsSecondPage() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(2));

        withRuntimeConfig(() -> {
            WeblogEntriesMonthPager pager = monthPager(weblog(), PAST_MONTH, 1);

            assertEquals("collection:date=" + PAST_MONTH + ":page=0", pager.getPrevLink(),
                    "Page 1 of a month must step back to page 0 of the same month");
            assertIsRenderedLabel(pager.getPrevName(), "weblogEntriesPager.month.prev",
                    "The previous-page control of a month");
        });
    }

    @Test
    void monthPagerLinksToTheNeighbouringMonths() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(1));

        withRuntimeConfig(() -> {
            WeblogEntriesMonthPager pager = monthPager(weblog(), PAST_MONTH, 0);

            assertEquals("collection:date=202002:page=0", pager.getNextCollectionLink(),
                    "The month after 2020-01 is 2020-02");
            assertEquals("collection:date=201912:page=0", pager.getPrevCollectionLink(),
                    "The month before 2020-01 is 2019-12, crossing the year boundary");
            assertIsRenderedLabel(pager.getNextCollectionName(),
                    "weblogEntriesPager.month.nextCollection", "The next-month control");
            assertIsRenderedLabel(pager.getPrevCollectionName(),
                    "weblogEntriesPager.month.prevCollection", "The previous-month control");
            assertTrue(pager.getNextCollectionName().contains("2020"),
                    "The next-month label must name February 2020; got: "
                            + pager.getNextCollectionName());
            assertTrue(pager.getPrevCollectionName().contains("2019"),
                    "The previous-month label must name December 2019; got: "
                            + pager.getPrevCollectionName());
        });
    }

    @Test
    void monthPagerDoesNotLinkPastTheCurrentMonth() throws Exception {
        // Without this clamp a crawler could follow "next month" forever
        // through empty archive pages.
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(1));

        withRuntimeConfig(() -> {
            WeblogEntriesMonthPager pager = monthPager(weblog(), currentMonth(), 0);

            assertNull(pager.getNextCollectionLink(),
                    "The current month has no next month to offer");
            assertNull(pager.getNextCollectionName());
        });
    }

    @Test
    void monthPagerDoesNotLinkBeforeTheWeblogExisted() throws Exception {
        // Same guard in the other direction: a weblog created today has no
        // archive before this month.
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(1));

        withRuntimeConfig(() -> {
            WeblogEntriesMonthPager pager =
                    monthPager(weblogCreatedToday(), currentMonth(), 0);

            assertNull(pager.getPrevCollectionLink(),
                    "There is no archive from before the weblog was created");
            assertNull(pager.getPrevCollectionName());
        });
    }

    @Test
    void monthPagerHomeLinkDropsTheMonthEntirely() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(1));

        withRuntimeConfig(() -> {
            WeblogEntriesMonthPager pager = monthPager(weblog(), PAST_MONTH, 3);

            assertEquals("collection:date=null:page=0", pager.getHomeLink(),
                    "Home leaves the archive and returns to the weblog front page");
            assertIsRenderedLabel(pager.getHomeName(), "weblogEntriesPager.month.home",
                    "The home control of a month archive");
        });
    }

    @Test
    void monthPagerSurvivesAFailingQuery() throws Exception {
        when(entryManager.getWeblogEntryObjectMap(any()))
                .thenThrow(new WebloggerException("database is down"));

        withRuntimeConfig(() -> {
            WeblogEntriesMonthPager pager = monthPager(weblog(), PAST_MONTH, 0);

            assertTrue(pager.getEntries().isEmpty(),
                    "A failed lookup must render an empty archive, never null");
        });
    }

    // -------------------------------------------------------------- day pager

    @Test
    void dayPagerQueriesOnlyThatDay() throws Exception {
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(Map.of());

        withRuntimeConfig(() -> dayPager(weblog(), PAST_DAY, 0));

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(entryManager).getWeblogEntryObjectMap(criteria.capture());

        assertEquals("20200115", format("yyyyMMdd", criteria.getValue().getStartDate()),
                "The window must start on the requested day");
        assertEquals("20200115", format("yyyyMMdd", criteria.getValue().getEndDate()),
                "The window must end on the requested day, or the archive spills "
                        + "into the neighbouring day");
        assertTrue(criteria.getValue().getStartDate().before(criteria.getValue().getEndDate()),
                "The day window must run forwards");
    }

    @Test
    void dayPagerCarriesEveryFilterIntoTheQuery() throws Exception {
        Weblog weblog = weblog();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(Map.of());

        withRuntimeConfig(() -> new WeblogEntriesDayPager(urlStrategy, weblogger, weblog, "en_US", null,
                null, PAST_DAY, "Java", List.of("testing"), 1));

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(entryManager).getWeblogEntryObjectMap(criteria.capture());
        WeblogEntrySearchCriteria wesc = criteria.getValue();

        assertEquals(weblog, wesc.getWeblog(), "The query must be scoped to this weblog");
        assertEquals("Java", wesc.getCatName(), "The category filter must reach the query");
        assertEquals(List.of("testing"), wesc.getTags(), "The tag filter must reach the query");
        assertEquals("en_US", wesc.getLocale(), "The locale filter must reach the query");
        assertEquals(WeblogEntry.PubStatus.PUBLISHED, wesc.getStatus(),
                "An archive page must never show drafts");
        assertEquals(PAGE_SIZE, wesc.getOffset(), "Page 1 starts one page in");
        assertEquals(PAGE_SIZE + 1, wesc.getMaxResults(),
                "One extra entry is fetched purely to detect a further page");
    }

    @Test
    void dayPagerOffersNoNextPageWhenTheDayFitsOnOnePage() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(PAGE_SIZE));

        withRuntimeConfig(() -> {
            WeblogEntriesDayPager pager = dayPager(weblog(), PAST_DAY, 0);

            assertEquals(PAGE_SIZE, totalEntries(pager.getEntries()));
            assertNull(pager.getNextLink(),
                    "Exactly one page worth of entries means no further page of this day");
            assertNull(pager.getNextName(),
                    "The label must disappear with the link, or the template renders "
                            + "a caption with no href");
        });
    }

    @Test
    void dayPagerOffersANextPageWhenTheDayOverflows() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(PAGE_SIZE + 1));

        withRuntimeConfig(() -> {
            WeblogEntriesDayPager pager = dayPager(weblog(), PAST_DAY, 0);

            assertEquals("collection:date=" + PAST_DAY + ":page=1", pager.getNextLink(),
                    "Paging within a day must keep the day and advance the page");
            assertIsRenderedLabel(pager.getNextName(), "weblogEntriesPager.day.next",
                    "The next-page control of a day");
            assertTrue(pager.getNextName().contains("2020"),
                    "The day label names the day being paged; got: " + pager.getNextName());
        });
    }

    @Test
    void dayPagerOffersNoPreviousPageOnItsFirstPage() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(2));

        withRuntimeConfig(() -> {
            WeblogEntriesDayPager pager = dayPager(weblog(), PAST_DAY, 0);

            assertNull(pager.getPrevLink(), "Page 0 of a day has nothing before it");
            assertNull(pager.getPrevName());
        });
    }

    @Test
    void dayPagerOffersAPreviousPageFromItsSecondPage() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(2));

        withRuntimeConfig(() -> {
            WeblogEntriesDayPager pager = dayPager(weblog(), PAST_DAY, 1);

            assertEquals("collection:date=" + PAST_DAY + ":page=0", pager.getPrevLink(),
                    "Page 1 of a day must step back to page 0 of the same day");
            assertIsRenderedLabel(pager.getPrevName(), "weblogEntriesPager.day.prev",
                    "The previous-page control of a day");
        });
    }

    @Test
    void dayPagerLinksToTheNeighbouringDays() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(1));

        withRuntimeConfig(() -> {
            WeblogEntriesDayPager pager = dayPager(weblog(), PAST_DAY, 0);

            assertEquals("collection:date=20200116:page=0", pager.getNextCollectionLink(),
                    "The day after 2020-01-15 is 2020-01-16");
            assertEquals("collection:date=20200114:page=0", pager.getPrevCollectionLink(),
                    "The day before 2020-01-15 is 2020-01-14");
            assertIsRenderedLabel(pager.getNextCollectionName(),
                    "weblogEntriesPager.day.nextCollection", "The next-day control");
            assertIsRenderedLabel(pager.getPrevCollectionName(),
                    "weblogEntriesPager.day.prevCollection", "The previous-day control");
            assertTrue(pager.getNextCollectionName().contains("16"),
                    "The next-day label must name the 16th; got: "
                            + pager.getNextCollectionName());
            assertTrue(pager.getPrevCollectionName().contains("14"),
                    "The previous-day label must name the 14th; got: "
                            + pager.getPrevCollectionName());
        });
    }

    @Test
    void dayPagerNeighbourLinksCrossMonthBoundaries() throws Exception {
        // The naive "day +/- 1" arithmetic has to roll the month over. Getting
        // this wrong strands the reader at the edge of every month.
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(1));

        withRuntimeConfig(() -> {
            WeblogEntriesDayPager pager = dayPager(weblog(), "20200301", 0);

            assertEquals("collection:date=20200229:page=0", pager.getPrevCollectionLink(),
                    "The day before 2020-03-01 is 2020-02-29 in a leap year");
        });
    }

    @Test
    void dayPagerDoesNotLinkPastToday() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(1));

        withRuntimeConfig(() -> {
            WeblogEntriesDayPager pager = dayPager(weblog(), today(), 0);

            assertNull(pager.getNextCollectionLink(),
                    "Today has no next day to offer; without this clamp a crawler "
                            + "walks forward through empty archives forever");
            assertNull(pager.getNextCollectionName());
        });
    }

    @Test
    void dayPagerDoesNotLinkBeforeTheWeblogExisted() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(1));

        withRuntimeConfig(() -> {
            WeblogEntriesDayPager pager = dayPager(weblogCreatedToday(), today(), 0);

            assertNull(pager.getPrevCollectionLink(),
                    "There is no archive from before the weblog was created");
            assertNull(pager.getPrevCollectionName());
        });
    }

    @Test
    void dayPagerHomeLinkDropsTheDayEntirely() throws Exception {
        stubUrlStrategyEchoingPageNumber();
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(days(1));

        withRuntimeConfig(() -> {
            WeblogEntriesDayPager pager = dayPager(weblog(), PAST_DAY, 2);

            assertEquals("collection:date=null:page=0", pager.getHomeLink(),
                    "Home leaves the archive and returns to the weblog front page");
            assertIsRenderedLabel(pager.getHomeName(), "weblogEntriesPager.day.home",
                    "The home control of a day archive");
        });
    }

    @Test
    void dayPagerSurvivesAFailingQuery() throws Exception {
        when(entryManager.getWeblogEntryObjectMap(any()))
                .thenThrow(new WebloggerException("database is down"));

        withRuntimeConfig(() -> {
            WeblogEntriesDayPager pager = dayPager(weblog(), PAST_DAY, 0);

            assertTrue(pager.getEntries().isEmpty(),
                    "A failed lookup must render an empty archive, never null");
            assertFalse(pager.getEntries().containsKey(null));
        });
    }

    @Test
    void archiveWindowsAreComputedInTheWeblogTimezone() throws Exception {
        // A weblog set to a far-eastern timezone must not have its "day" cut at
        // the server's midnight; readers would see entries filed under the
        // wrong date.
        Weblog weblog = weblog();
        weblog.setTimeZone("Pacific/Kiritimati");
        when(entryManager.getWeblogEntryObjectMap(any())).thenReturn(Map.of());

        withRuntimeConfig(() -> dayPager(weblog, PAST_DAY, 0));

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(entryManager).getWeblogEntryObjectMap(criteria.capture());

        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Pacific/Kiritimati"));
        cal.setTime(criteria.getValue().getStartDate());
        assertEquals(15, cal.get(Calendar.DAY_OF_MONTH),
                "The window must begin on the 15th as seen from the weblog's own "
                        + "timezone, not the server's");
    }
}
