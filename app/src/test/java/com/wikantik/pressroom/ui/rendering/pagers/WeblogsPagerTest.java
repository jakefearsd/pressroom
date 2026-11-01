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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WeblogsPager}, which drives the site-wide weblog
 * directory and its A-Z letter pages.
 *
 * <p>Two things have to line up: the offset handed to the query
 * ({@code page * length}) and the "is there another page" decision, which is
 * made by asking for one row more than will be shown. Get the second wrong and
 * the directory either advertises an empty page or hides the tail of the list.
 */
class WeblogsPagerTest {

    private static final String BASE_URL = "http://localhost/pressroom/weblogs";
    private static final int LENGTH = 5;

    private final URLStrategy urlStrategy = mock(URLStrategy.class);

    /** Distinct weblogs; the pager only needs them to be non-null to wrap them. */
    private static List<Weblog> weblogs(int count) {
        List<Weblog> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Weblog weblog = new Weblog();
            weblog.setHandle("blog" + i);
            list.add(weblog);
        }
        return list;
    }

    /** The facade the pager under test is constructed with; set per test by {@link #withWeblogManager}. */
    private Weblogger weblogger;

    /**
     * Runs the body with {@link #weblogger} answering with the supplied manager.
     * Nothing is installed statically: the pager takes the facade in its
     * constructor (plan Task 11).
     */
    private void withWeblogManager(WeblogManager manager, Runnable body) {
        weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogManager()).thenReturn(manager);
        body.run();
    }

    @Test
    void weblogsComeFromTheInjectedFacadesWeblogManager() throws Exception {
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(weblogs(2));

        withWeblogManager(manager, () -> {
            WeblogsPager pager = new WeblogsPager(urlStrategy, weblogger, BASE_URL, "en", -1, 0, LENGTH);
            assertEquals(2, pager.getItems().size(), "the injected manager's weblogs are the page");
        });
        verify(manager).getWeblogs(any(), any(), any(), any(), anyInt(), anyInt());
    }

    // ---------------------------------------------------------- item fetching

    @Test
    void exactlyOnePageOfResultsReportsNoFurtherPages() throws Exception {
        // The query asks for LENGTH+1 rows. Getting back exactly LENGTH means
        // the collection ended here, so no next link may be offered.
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogs(anyBoolean(), anyBoolean(), any(), any(), anyInt(), anyInt()))
                .thenReturn(weblogs(LENGTH));

        withWeblogManager(manager, () -> {
            WeblogsPager pager = new WeblogsPager(urlStrategy, weblogger, BASE_URL, "en", -1, 0, LENGTH);

            assertEquals(LENGTH, pager.getItems().size(),
                    "A full page of results must all be shown");
            assertFalse(pager.hasMoreItems(),
                    "Exactly one page worth of rows means there is no next page; "
                            + "advertising one sends the reader to an empty listing");
            assertNull(pager.getNextLink());
        });
    }

    @Test
    void oneExtraRowReportsAFurtherPageButIsNotItselfDisplayed() throws Exception {
        // LENGTH+1 rows is the smallest signal that another page exists. The
        // extra row is a probe and must not leak into the displayed list.
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogs(anyBoolean(), anyBoolean(), any(), any(), anyInt(), anyInt()))
                .thenReturn(weblogs(LENGTH + 1));

        withWeblogManager(manager, () -> {
            WeblogsPager pager = new WeblogsPager(urlStrategy, weblogger, BASE_URL, "en", -1, 0, LENGTH);

            assertEquals(LENGTH, pager.getItems().size(),
                    "The probe row must be dropped, not rendered as a sixth entry");
            assertTrue(pager.hasMoreItems(), "One row past the page size means more pages");
            assertEquals(BASE_URL + "?page=1", pager.getNextLink());
        });
    }

    @Test
    void queryAsksForOneRowMoreThanThePageSizeAtTheRightOffset() throws Exception {
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogs(anyBoolean(), anyBoolean(), any(), any(), anyInt(), anyInt()))
                .thenReturn(weblogs(0));

        withWeblogManager(manager, () -> {
            new WeblogsPager(urlStrategy, weblogger, BASE_URL, "en", -1, 3, LENGTH);
        });

        ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> max = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(manager).getWeblogs(anyBoolean(), anyBoolean(), any(), any(),
                offset.capture(), max.capture());

        assertEquals(3 * LENGTH, offset.getValue(),
                "Page 3 of 5-item pages starts at row 15; a wrong offset silently "
                        + "repeats or skips weblogs");
        assertEquals(LENGTH + 1, max.getValue(),
                "One extra row is fetched purely to detect a further page");
    }

    @Test
    void sinceDaysOfMinusOneMeansNoDateFloor() throws Exception {
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogs(anyBoolean(), anyBoolean(), any(), any(), anyInt(), anyInt()))
                .thenReturn(weblogs(0));

        withWeblogManager(manager, () ->
                new WeblogsPager(urlStrategy, weblogger, BASE_URL, "en", -1, 0, LENGTH));

        org.mockito.Mockito.verify(manager).getWeblogs(anyBoolean(), anyBoolean(), isNull(),
                any(), anyInt(), anyInt());
    }

    @Test
    void positiveSinceDaysAppliesADateFloorInThePast() throws Exception {
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogs(anyBoolean(), anyBoolean(), any(), any(), anyInt(), anyInt()))
                .thenReturn(weblogs(0));

        withWeblogManager(manager, () ->
                new WeblogsPager(urlStrategy, weblogger, BASE_URL, "en", 7, 0, LENGTH));

        ArgumentCaptor<Date> startDate = ArgumentCaptor.forClass(Date.class);
        org.mockito.Mockito.verify(manager).getWeblogs(anyBoolean(), anyBoolean(),
                startDate.capture(), any(), anyInt(), anyInt());

        assertNotNull(startDate.getValue(), "sinceDays=7 must produce a start date");
        // Checking only "before now" would pass for a floor of one second ago
        // or one century ago -- either silently changes the directory contents.
        // Calendar days in the server's zone, as the code counts them: a fixed
        // 24 h per day is an hour off whenever the window crosses a DST change.
        long sevenDaysAgo = java.time.ZonedDateTime.now().minusDays(7).toInstant().toEpochMilli();
        long millisAgo = System.currentTimeMillis() - startDate.getValue().getTime();
        assertTrue(Math.abs(startDate.getValue().getTime() - sevenDaysAgo) < 60_000L,
                "Expected a floor about 7 days ago, but it was "
                        + (millisAgo / 86_400_000.0) + " days ago");
    }

    @Test
    void aFailingQueryLeavesAnEmptyPagerRatherThanBreakingThePage() throws Exception {
        // The weblog directory is a page on a running site. A query failure
        // should show an empty list, not a stack trace.
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogs(anyBoolean(), anyBoolean(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new WebloggerException("database is down"));

        withWeblogManager(manager, () -> {
            WeblogsPager pager = new WeblogsPager(urlStrategy, weblogger, BASE_URL, "en", -1, 0, LENGTH);

            assertTrue(pager.getItems().isEmpty(),
                    "A failed lookup must yield an empty list, never null");
            assertFalse(pager.hasMoreItems());
        });
    }

    @Test
    void plainListingDelegatesItsPreviousLinkToTheBasePager() throws Exception {
        // Without a letter the overrides fall through to AbstractPager, which
        // is a separate code path from the letter-carrying versions below.
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogs(anyBoolean(), anyBoolean(), any(), any(), anyInt(), anyInt()))
                .thenReturn(weblogs(2));

        withWeblogManager(manager, () -> {
            assertNull(new WeblogsPager(urlStrategy, weblogger, BASE_URL, "en", -1, 0, LENGTH).getPrevLink(),
                    "Page 0 of the plain directory has nothing before it");
            assertEquals(BASE_URL + "?page=0",
                    new WeblogsPager(urlStrategy, weblogger, BASE_URL, "en", -1, 1, LENGTH).getPrevLink(),
                    "Page 1 of the plain directory steps back to page 0 with no letter");
        });
    }

    // --------------------------------------------------------- letter listing

    @Test
    void letterListingQueriesByItsFirstCharacter() throws Exception {
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogsByLetter(anyChar(), anyInt(), anyInt()))
                .thenReturn(weblogs(2));

        withWeblogManager(manager, () -> {
            WeblogsPager pager =
                    new WeblogsPager(urlStrategy, weblogger, BASE_URL, "b", "en", -1, 0, LENGTH);

            assertEquals(2, pager.getItems().size());
        });

        org.mockito.Mockito.verify(manager).getWeblogsByLetter(eq('b'), eq(0), eq(LENGTH + 1));
    }

    @Test
    void letterListingCarriesTheLetterOnItsNextLink() throws Exception {
        // Dropping the letter would page from "weblogs starting with B, page 0"
        // into "all weblogs, page 1".
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogsByLetter(anyChar(), anyInt(), anyInt()))
                .thenReturn(weblogs(LENGTH + 1));

        withWeblogManager(manager, () -> {
            WeblogsPager pager =
                    new WeblogsPager(urlStrategy, weblogger, BASE_URL, "b", "en", -1, 0, LENGTH);

            String next = pager.getNextLink();
            assertNotNull(next, "There is another page of B weblogs");
            assertTrue(next.contains("page=1"), "Next must advance the page; got: " + next);
            assertTrue(next.contains("letter=b"),
                    "Next must keep the letter filter; got: " + next);
        });
    }

    @Test
    void letterListingOffersNoNextLinkOnItsLastPage() throws Exception {
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogsByLetter(anyChar(), anyInt(), anyInt()))
                .thenReturn(weblogs(LENGTH));

        withWeblogManager(manager, () -> {
            WeblogsPager pager =
                    new WeblogsPager(urlStrategy, weblogger, BASE_URL, "b", "en", -1, 0, LENGTH);

            assertNull(pager.getNextLink(),
                    "The letter listing must stop offering pages when the letter runs out");
        });
    }

    @Test
    void letterListingOffersNoPreviousLinkOnItsFirstPage() throws Exception {
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogsByLetter(anyChar(), anyInt(), anyInt()))
                .thenReturn(weblogs(LENGTH + 1));

        withWeblogManager(manager, () -> {
            WeblogsPager pager =
                    new WeblogsPager(urlStrategy, weblogger, BASE_URL, "b", "en", -1, 0, LENGTH);

            assertNull(pager.getPrevLink(),
                    "Page 0 of a letter listing has nothing before it; the check is "
                            + "page-1 >= 0 and must not be off by one");
        });
    }

    @Test
    void letterListingPreviousLinkAppearsFromPageOneAndKeepsTheLetter() throws Exception {
        WeblogManager manager = mock(WeblogManager.class);
        when(manager.getWeblogsByLetter(anyChar(), anyInt(), anyInt()))
                .thenReturn(weblogs(2));

        withWeblogManager(manager, () -> {
            WeblogsPager pager =
                    new WeblogsPager(urlStrategy, weblogger, BASE_URL, "b", "en", -1, 1, LENGTH);

            String prev = pager.getPrevLink();
            assertNotNull(prev, "Page 1 of a letter listing can go back to page 0");
            assertTrue(prev.contains("page=0"), "Previous must step back one page; got: " + prev);
            assertTrue(prev.contains("letter=b"),
                    "Previous must keep the letter filter; got: " + prev);
        });
    }
}
