/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.ui.controllers.pagers;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Entries screen's pager ({@code Entries.jsp} renders its prev/next
 * links through {@code fn:escapeXml}): which links exist on which page, and
 * how {@code bean.page} is joined onto a base url that may or may not already
 * carry the list's filter query string.
 *
 * <p>Characterisation tests: written against the existing behaviour and
 * expected to pass immediately.
 */
class EntriesPagerTest {

    private static final String PLAIN_BASE = "/pressroom-ui/authoring/entries.rol";
    private static final String FILTERED_BASE =
            "/pressroom-ui/authoring/entries.rol?weblog=myblog&bean.status=DRAFT";

    @Test
    void theFirstPageWithMoreItemsLinksForwardButNotBack() {
        EntriesPager pager = new EntriesPager(PLAIN_BASE, 0, List.of(), true);

        assertEquals(PLAIN_BASE + "?bean.page=1", pager.getNextLink());
        assertNull(pager.getPrevLink(), "page 0 has no previous page");
        assertTrue(pager.isMoreItems());
    }

    @Test
    void aMiddlePageLinksBothWaysToTheAdjacentPages() {
        EntriesPager pager = new EntriesPager(PLAIN_BASE, 3, List.of(), true);

        assertEquals(PLAIN_BASE + "?bean.page=4", pager.getNextLink());
        assertEquals(PLAIN_BASE + "?bean.page=2", pager.getPrevLink());
    }

    @Test
    void theLastPageLinksBackButNotForward() {
        EntriesPager pager = new EntriesPager(PLAIN_BASE, 2, List.of(), false);

        assertNull(pager.getNextLink(), "no more items means no next page");
        assertEquals(PLAIN_BASE + "?bean.page=1", pager.getPrevLink());
        assertFalse(pager.isMoreItems());
    }

    @Test
    void aSinglePageHasNeitherLink() {
        EntriesPager pager = new EntriesPager(PLAIN_BASE, 0, List.of(), false);

        assertNull(pager.getNextLink());
        assertNull(pager.getPrevLink());
    }

    /**
     * The controller's base url already carries the list's filters; the page
     * parameter must be appended with {@code &}, not start a second query
     * string with {@code ?} (which would fold it into the last filter value).
     */
    @Test
    void aBaseUrlThatAlreadyHasAQueryStringGetsThePageAppendedWithAnAmpersand() {
        EntriesPager pager = new EntriesPager(FILTERED_BASE, 1, List.of(), true);

        assertEquals(FILTERED_BASE + "&bean.page=2", pager.getNextLink());
        assertEquals(FILTERED_BASE + "&bean.page=0", pager.getPrevLink());
    }
}
