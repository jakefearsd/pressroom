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

import java.util.List;
import java.util.stream.Stream;
import java.util.Map;

import com.wikantik.pressroom.business.URLStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link AbstractPager}, the base every simple pager
 * (users, weblogs, comments, media files, entry lists) inherits its next/prev
 * link logic from.
 *
 * <p>All the interesting behaviour is at the two ends of the collection: the
 * first page must not offer a previous link and the last page must not offer a
 * next one. A dangling link at either end is a 404 the reader hits by clicking
 * a control the page told them was there.
 */
class AbstractPagerTest {

    private static final String BASE_URL = "http://localhost/pressroom/users";

    /**
     * Minimal concrete pager.
     *
     * <p>{@code AbstractPager} now owns the paging arithmetic, so "there is a
     * next page" is not injected any more -- it is derived, the way the real
     * pagers derive it: the fetch is asked for one row more than a page holds,
     * and the extra row IS the signal. This stub therefore expresses "more" by
     * returning that extra row.
     */
    private static final class FixedPager extends AbstractPager<String> {

        private final List<String> fetched;

        FixedPager(int pageNum, List<String> items, boolean more) {
            super(mock(URLStrategy.class), BASE_URL, pageNum, items.size());
            this.fetched = more
                    ? Stream.concat(items.stream(), Stream.of("one-more")).toList()
                    : items;
        }

        @Override
        protected List<String> fetchPage(int offset, int limit) {
            return fetched;
        }

        @Override
        protected String itemLabel() {
            return "thing";
        }
    }

    /** A pager whose fetch returns exactly one page, so nothing follows it. */
    private static final class DefaultPager extends AbstractPager<String> {

        DefaultPager(int pageNum) {
            super(mock(URLStrategy.class), BASE_URL, pageNum, 1);
        }

        @Override
        protected List<String> fetchPage(int offset, int limit) {
            return List.of("only");
        }

        @Override
        protected String itemLabel() {
            return "thing";
        }
    }

    // ------------------------------------------------------------ first page

    @Test
    void firstPageOffersNoPreviousLink() {
        FixedPager pager = new FixedPager(0, List.of("a", "b"), true);

        assertNull(pager.getPrevLink(),
                "Page 0 has nothing before it; a previous link here points at page -1");
        assertNull(pager.getPrevName(),
                "The previous label must disappear along with the link, or the "
                        + "template renders a caption with no href");
    }

    @Test
    void firstPageStillOffersANextLinkWhenMoreItemsExist() {
        FixedPager pager = new FixedPager(0, List.of("a", "b"), true);

        assertEquals(BASE_URL + "?page=1", pager.getNextLink());
        assertEquals("Next", pager.getNextName());
    }

    // ------------------------------------------------------------- last page

    @Test
    void lastPageOffersNoNextLink() {
        FixedPager pager = new FixedPager(3, List.of("a", "b"), false);

        assertNull(pager.getNextLink(),
                "With no further items the next link must be absent, not point at "
                        + "an empty page 4");
        assertNull(pager.getNextName(),
                "The next label must disappear along with the link");
    }

    @Test
    void lastPageStillOffersAPreviousLink() {
        FixedPager pager = new FixedPager(3, List.of("a", "b"), false);

        assertEquals(BASE_URL + "?page=2", pager.getPrevLink());
        assertEquals("Previous", pager.getPrevName());
    }

    // ------------------------------------------------------------ empty view

    @Test
    void emptyResultSetOffersNeitherDirection() {
        FixedPager pager = new FixedPager(0, List.of(), false);

        assertNull(pager.getPrevLink(), "Nothing to page back to");
        assertNull(pager.getNextLink(), "Nothing to page forward to");
        assertNull(pager.getPrevName());
        assertNull(pager.getNextName());
    }

    // ---------------------------------------------------- page-index handling

    @Test
    void pageOneIsTheFirstPageThatGetsAPreviousLink() {
        // Page 0 -> no link, page 1 -> link to page 0. These two cases sit on
        // either side of the `page > 0` test, so an off-by-one there shows up
        // as either a dangling link on page 0 or an unreachable page 0.
        assertNull(new FixedPager(0, List.of("a"), false).getPrevLink());
        assertEquals(BASE_URL + "?page=0", new FixedPager(1, List.of("a"), false).getPrevLink(),
                "Page 1's previous link must lead back to page 0");
    }

    @Test
    void negativePageNumbersAreClampedToTheFirstPage() {
        // Nothing stops a reader editing ?page=-5 into the URL. The pager must
        // treat it as page 0 rather than generating ?page=-6 links.
        FixedPager pager = new FixedPager(-5, List.of("a"), true);

        assertEquals(0, pager.getPage(), "A negative page index must clamp to 0");
        assertNull(pager.getPrevLink(), "A clamped page is the first page");
        assertEquals(BASE_URL + "?page=1", pager.getNextLink(),
                "Paging forward from a clamped page must land on page 1, not page -4");
    }

    @Test
    void largePageNumbersAreCarriedThroughUnchanged() {
        FixedPager pager = new FixedPager(1000, List.of("a"), true);

        assertEquals(BASE_URL + "?page=1001", pager.getNextLink());
        assertEquals(BASE_URL + "?page=999", pager.getPrevLink());
    }

    // ------------------------------------------------------------- base class

    @Test
    void aFetchThatDoesNotOverflowAPageOffersNoNextLink() {
        // The default is deliberately false: a pager that has not worked out
        // whether more items exist must not advertise a page that may not be there.
        DefaultPager pager = new DefaultPager(0);

        assertNull(pager.getNextLink(),
                "AbstractPager.hasMoreItems() defaults to false, so the next link "
                        + "must be absent until a subclass says otherwise");
    }

    @Test
    void homeLinkAndNameAreTheUnadornedBaseUrl() {
        FixedPager pager = new FixedPager(3, List.of("a"), true);

        assertEquals(BASE_URL, pager.getHomeLink(),
                "Home must drop the page parameter entirely, not reset it to page=0");
        assertEquals("Home", pager.getHomeName());
    }

    @Test
    void urlIsBuiltByAppendingAQueryStringToTheBaseUrl() {
        FixedPager pager = new FixedPager(0, List.of("a"), false);

        assertEquals(BASE_URL + "?letter=a", pager.createURL(BASE_URL, Map.of("letter", "a")));
    }

    @Test
    void urlAndPageCanBeReassignedAfterConstruction() {
        // SiteModel and the feed models build a pager and then retarget it, so
        // these setters are part of the contract.
        FixedPager pager = new FixedPager(0, List.of("a"), true);
        pager.setUrl("http://localhost/pressroom/weblogs");
        pager.setPage(2);

        assertEquals("http://localhost/pressroom/weblogs", pager.getUrl());
        assertEquals(2, pager.getPage());
        assertEquals("http://localhost/pressroom/weblogs?page=3", pager.getNextLink(),
                "Links must be built from the reassigned url and page");
        assertNotNull(pager.getPrevLink(), "Page 2 has a previous page");
    }
}
