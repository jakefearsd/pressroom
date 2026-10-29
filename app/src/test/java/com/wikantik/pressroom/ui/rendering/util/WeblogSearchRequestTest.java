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

package com.wikantik.pressroom.ui.rendering.util;

import static org.mockito.Mockito.mock;
import com.wikantik.pressroom.business.Weblogger;
import org.junit.jupiter.api.Test;

import static com.wikantik.pressroom.ui.rendering.util.WeblogSearchRequest.SEARCH_SERVLET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link WeblogSearchRequest}.
 *
 * <p>Search is addressed entirely through the query string:
 * {@code /pressroom-ui/rendering/search/<handle>?q=...}. Anything after the handle
 * in the path is a sign the URL was built wrong, so the parser refuses it
 * outright rather than searching something unintended.
 */
class WeblogSearchRequestTest {

    private static WeblogSearchRequest parse(String pathInfo, String... params)
            throws InvalidRequestException {
        return new WeblogSearchRequest(mock(Weblogger.class), MockRequest.with(SEARCH_SERVLET, pathInfo, params));
    }

    @Test
    void requestAimedAtAnotherServletIsRejected() {
        assertThrows(InvalidRequestException.class,
                () -> new WeblogSearchRequest(mock(Weblogger.class), 
                        MockRequest.with("/pressroom-ui/rendering/page", "/myblog")),
                "A page URL must not parse as a search request");
    }

    @Test
    void requestWithNoServletPathIsRejected() {
        assertThrows(InvalidRequestException.class,
                () -> new WeblogSearchRequest(mock(Weblogger.class), MockRequest.with(null, "/myblog")),
                "A null servlet path cannot match the search servlet");
    }

    @Test
    void handleOnlyPathIsTheOnlyAcceptedForm() throws Exception {
        WeblogSearchRequest request = parse("/myblog", "q", "roller");

        assertEquals("myblog", request.getWeblogHandle());
        assertEquals("roller", request.getQuery());
    }

    @Test
    void localeSegmentIsAcceptedBecauseItIsConsumedBeforeThePathInfoCheck() throws Exception {
        // The locale is stripped by WeblogRequest, so it never counts as
        // leftover path info. Localised search links must keep working.
        WeblogSearchRequest request = parse("/myblog/en_US", "q", "roller");

        assertEquals("en_US", request.getLocale());
        assertEquals("roller", request.getQuery());
    }

    @Test
    void anyLeftoverPathInfoIsRejected() {
        assertThrows(InvalidRequestException.class, () -> parse("/myblog/entry/hello"),
                "Search takes no path arguments; a path here means the URL was "
                        + "built for a different servlet");
    }

    @Test
    void blankQueryIsTreatedAsNoQueryAtAll() throws Exception {
        // The search servlet renders an empty results page for a null query.
        // Letting "   " through would run a Lucene query for whitespace.
        assertNull(parse("/myblog", "q", "   ").getQuery(),
                "A whitespace-only q must not become a search term");
        assertNull(parse("/myblog", "q", "").getQuery(), "An empty q is not a search");
        assertNull(parse("/myblog").getQuery(), "An absent q is not a search");
    }

    @Test
    void queryIsNotDecodedAgainAfterTheContainerHasDecodedIt() throws Exception {
        // Unlike the category, the search term is stored verbatim. Recording it
        // so that adding a decode here becomes a visible decision -- it would
        // change what users searched for.
        assertEquals("100%", parse("/myblog", "q", "100%").getQuery(),
                "The search term is used as typed, with no second decode");
    }

    @Test
    void pageNumberIsReadAndBadValuesFallBackToTheFirstPage() throws Exception {
        assertEquals(4, parse("/myblog", "page", "4").getPageNum());
        assertEquals(0, parse("/myblog").getPageNum(), "absent means first page");
        assertEquals(0, parse("/myblog", "page", "abc").getPageNum(), "letters");
        assertEquals(0, parse("/myblog", "page", "").getPageNum(), "empty value");
        assertEquals(0, parse("/myblog", "page", "99999999999999999999").getPageNum(),
                "a value past Integer.MAX_VALUE must not overflow into a real page");
    }

    @Test
    void negativePageNumberIsCarriedThroughToTheSearchPager() throws Exception {
        // SearchResultsPager only emits a previous link when page > 0, so a
        // negative index is inert there rather than being clamped here.
        assertEquals(-2, parse("/myblog", "page", "-2").getPageNum());
    }

    @Test
    void categoryFilterIsDecodedAndBlankValuesAreIgnored() throws Exception {
        assertEquals("Java Tips", parse("/myblog", "cat", "Java%20Tips").getWeblogCategoryName());
        assertNull(parse("/myblog", "cat", "  ").getWeblogCategoryName(),
                "A blank cat is no filter and must not restrict the search to a "
                        + "category that cannot exist");
        assertNull(parse("/myblog").getWeblogCategoryName());
    }

    @Test
    void malformedPercentEscapeInTheCategoryIsRejectedAsAnInvalidRequest() {
        assertThrows(InvalidRequestException.class, () -> parse("/myblog", "cat", "100%"),
                "A bare '%' must produce InvalidRequestException rather than an "
                        + "unchecked IllegalArgumentException");
    }

    @Test
    void settersAllowTheServletToOverrideWhatWasParsed() throws Exception {
        // SearchServlet builds a request from the URL and then adjusts it, so
        // the setters are part of the contract, not dead accessors.
        WeblogSearchRequest request = parse("/myblog", "q", "roller");
        request.setQuery("something else");
        request.setPageNum(7);
        request.setWeblogCategoryName("Java");

        assertEquals("something else", request.getQuery());
        assertEquals(7, request.getPageNum());
        assertEquals("Java", request.getWeblogCategoryName());
    }
}
