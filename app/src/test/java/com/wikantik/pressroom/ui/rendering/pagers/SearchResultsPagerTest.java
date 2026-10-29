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

import java.util.Date;
import java.util.Map;
import java.util.Set;

import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import com.wikantik.pressroom.ui.rendering.util.WeblogSearchRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SearchResultsPager}.
 *
 * <p>Paging search results has an extra requirement over the other pagers: the
 * query and category have to travel on every link. A next link that loses the
 * query takes the reader from "page 2 of results for X" to "page 2 of nothing",
 * which looks like the search silently failed.
 */
class SearchResultsPagerTest {

    private static final String WEBLOG_URL = "http://localhost/roller/myblog/";

    private URLStrategy urlStrategy;
    private Weblog weblog;

    @BeforeEach
    void setUp() {
        urlStrategy = mock(URLStrategy.class);
        weblog = new Weblog();
        weblog.setHandle("myblog");
        weblog.setLocale("en_US");
        when(urlStrategy.getWeblogURL(any(), any(), anyBoolean())).thenReturn(WEBLOG_URL);
        when(urlStrategy.getWeblogSearchURL(any(), any(), any(), any(), anyInt(), anyBoolean()))
                .thenAnswer(call -> WEBLOG_URL + "?q=" + call.getArgument(2)
                        + "&page=" + call.getArgument(4));
    }

    private SearchResultsPager pager(int pageNum, String category, boolean more) {
        WeblogSearchRequest searchRequest = new WeblogSearchRequest();
        searchRequest.setWeblog(weblog);
        searchRequest.setQuery("pressroom");
        searchRequest.setWeblogCategoryName(category);
        searchRequest.setLocale("en_US");
        searchRequest.setPageNum(pageNum);
        return new SearchResultsPager(urlStrategy, searchRequest, Map.of(), more);
    }

    @Test
    void firstPageOffersNoPreviousLinkOrLabel() {
        SearchResultsPager pager = pager(0, null, true);

        assertNull(pager.getPrevLink(),
                "Page 0 of the results has nothing before it");
        assertNull(pager.getPrevName(),
                "The label must disappear with the link, or the template renders "
                        + "a caption with no href");
    }

    @Test
    void firstPageOffersANextLinkWhenMoreResultsExist() {
        SearchResultsPager pager = pager(0, null, true);

        assertEquals(WEBLOG_URL + "?q=pressroom&page=1", pager.getNextLink(),
                "Page 0's next link must target page 1 and keep the query");
        assertRenderedLabel(pager.getNextName(), "searchPager.next", "The next control");
    }

    @Test
    void lastPageOffersNoNextLinkOrLabel() {
        SearchResultsPager pager = pager(2, null, false);

        assertNull(pager.getNextLink(),
                "With no further results the next link must be absent, not point "
                        + "at an empty page 3");
        assertNull(pager.getNextName());
    }

    @Test
    void previousLinkStepsBackExactlyOnePage() {
        SearchResultsPager pager = pager(2, null, false);

        assertEquals(WEBLOG_URL + "?q=pressroom&page=1", pager.getPrevLink(),
                "Page 2's previous link must target page 1, not page 0 or page 2");
        assertRenderedLabel(pager.getPrevName(), "searchPager.prev", "The previous control");
    }

    @Test
    void pageOneIsTheFirstPageWithAPreviousLink() {
        // Page 0 and page 1 sit either side of the `page > 0` test.
        assertNull(pager(0, null, false).getPrevLink());
        assertNotNull(pager(1, null, false).getPrevLink(),
                "Page 1 must be able to get back to page 0");
    }

    @Test
    void emptyResultSetOffersNeitherDirection() {
        SearchResultsPager pager = pager(0, null, false);

        assertNull(pager.getPrevLink(), "Nothing to page back to");
        assertNull(pager.getNextLink(), "Nothing to page forward to");
    }

    @Test
    void categoryFilterTravelsOnPagingLinks() {
        // Losing the category would widen the result set mid-pagination, so
        // page 2 would show entries page 1 could not have contained.
        SearchResultsPager pager = pager(1, "Java", true);

        pager.getNextLink();
        pager.getPrevLink();

        verify(urlStrategy, atLeastOnce()).getWeblogSearchURL(eq(weblog), eq("en_US"),
                eq("pressroom"), eq("Java"), eq(2), eq(false));
        verify(urlStrategy, atLeastOnce()).getWeblogSearchURL(eq(weblog), eq("en_US"),
                eq("pressroom"), eq("Java"), eq(0), eq(false));
    }

    @Test
    void homeLinkDropsTheSearchEntirely() {
        SearchResultsPager pager = pager(3, "Java", true);

        assertEquals(WEBLOG_URL, pager.getHomeLink(),
                "Home leaves the search behind and returns to the weblog itself");
        assertRenderedLabel(pager.getHomeName(), "searchPager.home", "The home control");
    }

    /**
     * A label must be real user-facing text. I18nMessages returns the lookup
     * key itself when the bundle entry is missing, so a non-null check alone
     * would pass while the page renders "searchPager.next" to the reader.
     */
    private static void assertRenderedLabel(String label, String messageKey, String what) {
        assertNotNull(label, what + " must have a label");
        assertFalse(label.isBlank(), what + " must not render as an empty caption");
        assertNotEquals(messageKey, label,
                what + " fell back to the raw message key, so " + messageKey
                        + " is missing from ApplicationResources");
    }

    @Test
    void resultsAreHandedBackUntouched() {
        // The pager is a view over results the search model already built; it
        // must not copy or reorder them.
        Map<Date, Set<WeblogEntryWrapper>> results = Map.of(new Date(1_000L), Set.of());
        WeblogSearchRequest searchRequest = new WeblogSearchRequest();
        searchRequest.setWeblog(weblog);
        searchRequest.setLocale("en_US");

        SearchResultsPager pager =
                new SearchResultsPager(urlStrategy, searchRequest, results, false);

        assertSame(results, pager.getEntries());
    }

    @Test
    void searchResultsHaveNoAdjacentCollections() {
        // Unlike the date-based pagers there is no "next month" of results, so
        // all four collection accessors are deliberately null.
        SearchResultsPager pager = pager(1, null, true);

        assertNull(pager.getNextCollectionLink());
        assertNull(pager.getNextCollectionName());
        assertNull(pager.getPrevCollectionLink());
        assertNull(pager.getPrevCollectionName());
    }

    /**
     * A locale with a variant component (e.g. "en_US_POSIX") used to leave
     * viewLocale null all the way through the constructor -- neither the
     * one- nor two-part branch matched -- which reached
     * I18nMessages.getMessages(Locale), itself unconditionally dereferencing
     * its parameter (NP_NULL_PARAM_DEREF).
     */
    @Test
    void labelsResolveForAThreePartLocale() {
        WeblogSearchRequest searchRequest = new WeblogSearchRequest();
        searchRequest.setWeblog(weblog);
        searchRequest.setQuery("pressroom");
        searchRequest.setLocale("en_US_POSIX");
        searchRequest.setPageNum(1);

        SearchResultsPager pager =
                new SearchResultsPager(urlStrategy, searchRequest, Map.of(), true);

        assertRenderedLabel(pager.getHomeName(), "searchPager.home",
                "The home control under a three-part locale");
    }

    @Test
    void localeIsTakenFromTheWeblogWhenTheSearchUrlHadNone() {
        // A search URL without a locale segment must still produce localised
        // labels, using the weblog's own locale.
        WeblogSearchRequest searchRequest = new WeblogSearchRequest();
        searchRequest.setWeblog(weblog);
        searchRequest.setQuery("pressroom");
        searchRequest.setPageNum(1);

        SearchResultsPager pager =
                new SearchResultsPager(urlStrategy, searchRequest, Map.of(), true);

        assertRenderedLabel(pager.getHomeName(), "searchPager.home",
                "The home control under the fallback locale");
        assertRenderedLabel(pager.getNextName(), "searchPager.next",
                "The next control under the fallback locale");
    }
}
