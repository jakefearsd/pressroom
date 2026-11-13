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

package com.wikantik.pressroom.ui.rendering.model;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.business.search.SearchHit;
import com.wikantik.pressroom.business.search.SearchHits;
import com.wikantik.pressroom.business.search.SearchSpec;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import com.wikantik.pressroom.pojos.wrapper.WeblogWrapper;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogSearchRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What the model asks the index for in each view, and what it hands the
 * templates. The index is a mock: its own behaviour is tested with it.
 */
class SearchResultsModelScopesTest {

    private IndexManager index;
    private Weblogger weblogger;
    private URLStrategy urls;
    private Weblog blog;
    private MockedStatic<WebloggerRuntimeConfig> config;
    private final Timestamp published = new Timestamp(System.currentTimeMillis() - 60_000L);

    @BeforeEach
    void setUp() {
        index = mock(IndexManager.class);
        weblogger = mock(Weblogger.class);
        when(weblogger.getIndexManager()).thenReturn(index);
        urls = mock(URLStrategy.class);
        blog = new Weblog();
        blog.setHandle("myblog");
        blog.setLocale("en_US");
        config = org.mockito.Mockito.mockStatic(WebloggerRuntimeConfig.class);
    }

    @AfterEach
    void tearDown() {
        config.close();
    }

    private SearchHit hit(String id) {
        WeblogEntryWrapper entry = mock(WeblogEntryWrapper.class);
        when(entry.getPubTime()).thenReturn(published);
        when(entry.getTitle()).thenReturn("title " + id);
        return new SearchHit(entry, "", false);
    }

    private static SearchHits hits(int total, boolean fallback, Map<String, Integer> perBlog, SearchHit... hits) {
        return new SearchHits(List.of(hits), total, fallback, perBlog, Set.of());
    }

    private SearchResultsModel model(String handle, boolean siteSpace, boolean blogOnly, int page)
            throws WebloggerException {
        config.when(() -> WebloggerRuntimeConfig.isSiteWideWeblog(handle)).thenReturn(siteSpace);
        WeblogSearchRequest request = new WeblogSearchRequest();
        request.setWeblog(blog);
        request.setWeblogHandle(handle);
        request.setQuery("spain");
        request.setLocale("en_US");
        request.setScopeBlog(blogOnly);
        request.setPageNum(page);
        WeblogPageRequest pageRequest = new WeblogPageRequest();
        pageRequest.setWeblog(blog);
        Map<String, Object> init = new HashMap<>();
        init.put("searchRequest", request);
        init.put("parsedRequest", pageRequest);
        init.put("weblogger", weblogger);
        init.put("urlStrategy", urls);
        SearchResultsModel model = new SearchResultsModel();
        model.init(init);
        return model;
    }

    private List<SearchSpec> specs(int calls) throws WebloggerException {
        ArgumentCaptor<SearchSpec> captor = ArgumentCaptor.forClass(SearchSpec.class);
        verify(index, times(calls)).find(captor.capture(), any());
        return captor.getAllValues();
    }

    @Test
    void blogViewAsksForEightOwnThenFiveOthers() throws Exception {
        when(index.find(any(), any())).thenReturn(SearchHits.NONE);

        SearchResultsModel m = model("myblog", false, false, 3);

        assertEquals(List.of(
                new SearchSpec("spain", SearchSpec.Scope.BLOG, "myblog", null, 0, 8, "en_US"),
                new SearchSpec("spain", SearchSpec.Scope.OTHER_BLOGS, "myblog", null, 0, 5, "en_US")),
                specs(2), "the blog view has no pages, so the page number is ignored");
        assertFalse(m.isSiteScope());
        assertFalse(m.isBlogOnly());
    }

    @Test
    void blogViewSeparatesOwnAndOtherHits() throws Exception {
        SearchHit own = hit("own");
        SearchHit other = hit("other");
        when(index.find(any(), any())).thenReturn(
                hits(12, false, Map.of(), own),
                hits(30, true, Map.of(), other));

        SearchResultsModel m = model("myblog", false, false, 0);

        assertEquals(List.of(own), m.getOwnHits());
        assertEquals(12, m.getOwnTotal());
        assertEquals(List.of(other), m.getOtherHits());
        assertTrue(m.isAnyWordFallback(), "either query falling back is said once");
        assertNull(m.getWeblogEntriesPager().getNextLink(), "the blog view is not paged");
    }

    @Test
    void blogWithMoreFromSiteOffAsksNoOtherBlog() throws Exception {
        blog.setShowMoreFromSite(false);
        SearchHit own = hit("own");
        when(index.find(any(), any())).thenReturn(hits(1, false, Map.of(), own));

        SearchResultsModel m = model("myblog", false, false, 0);

        assertEquals(List.of(SearchSpec.Scope.BLOG), specs(1).stream().map(SearchSpec::scope).toList());
        assertTrue(m.getOtherHits().isEmpty());
    }

    @Test
    void siteSpaceBlogAsksNoOtherBlog() throws Exception {
        blog.setSiteSpace(true);
        when(index.find(any(), any())).thenReturn(SearchHits.NONE);

        model("myblog", false, false, 0);

        assertEquals(1, specs(1).size(), "the site space has no 'other' blogs to add");
    }

    @Test
    void blogOnlyViewPagesTenAndAsksNoOtherBlog() throws Exception {
        when(index.find(any(), any())).thenReturn(SearchHits.NONE);

        SearchResultsModel m = model("myblog", false, true, 2);

        assertEquals(List.of(new SearchSpec("spain", SearchSpec.Scope.BLOG, "myblog", null, 20, 10, "en_US")),
                specs(1));
        assertTrue(m.isBlogOnly());
        assertTrue(m.getOtherHits().isEmpty());
    }

    @Test
    void siteViewAsksSiteScopePagedByTen() throws Exception {
        when(index.find(any(), any())).thenReturn(SearchHits.NONE);

        SearchResultsModel m = model("home", true, false, 1);

        assertEquals(List.of(new SearchSpec("spain", SearchSpec.Scope.SITE, "home", null, 10, 10, "en_US")),
                specs(1));
        assertTrue(m.isSiteScope());
        assertTrue(m.getOtherHits().isEmpty());
    }

    @Test
    void pagingIsDerivedFromTheRequestedOffsetOnly() throws Exception {
        // 11 matches, the last one stale: find reports 10 and returns nothing for page 1
        when(index.find(any(), any())).thenReturn(hits(10, false, Map.of()));

        SearchResultsModel m = model("myblog", false, true, 1);

        assertEquals(10, m.getOffset());
        assertEquals(0, m.getLimit());
        assertEquals(10, m.getHits());
        assertNull(m.getWeblogEntriesPager().getNextLink(),
                "ownTotal 10 is not more than offset 10 plus a page");
    }

    @Test
    void aFullPageWithMoreBehindItOffersNext() throws Exception {
        SearchHit a = hit("a");
        SearchHit b = hit("b");
        when(index.find(any(), any())).thenReturn(hits(25, false, Map.of(), a, b));
        when(urls.getWeblogSearchURL(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn("next-url");

        SearchResultsModel m = model("myblog", false, true, 1);

        assertEquals("next-url", m.getWeblogEntriesPager().getNextLink());
    }

    @Test
    void fallbackFlagIsTrueWhenEitherQueryFellBack() throws Exception {
        SearchHit own = hit("own");
        SearchHit other = hit("other");
        when(index.find(any(), any())).thenReturn(
                hits(1, false, Map.of(), own),
                hits(1, true, Map.of(), other));
        assertTrue(model("myblog", false, false, 0).isAnyWordFallback());

        when(index.find(any(), any())).thenReturn(
                hits(1, true, Map.of(), own),
                hits(1, false, Map.of(), other));
        assertTrue(model("myblog", false, false, 0).isAnyWordFallback());

        when(index.find(any(), any())).thenReturn(SearchHits.NONE);
        assertFalse(model("myblog", false, false, 0).isAnyWordFallback());
    }

    @Test
    void moreFromBlogIsCountMinusOne() throws Exception {
        SearchHit x = hit("x");
        when(index.find(any(), any())).thenReturn(
                hits(6, false, Map.of("a", 4, "b", 1, "c", 1), x));

        SearchResultsModel m = model("home", true, false, 0);

        assertEquals(3, m.getBlogCount());
        assertEquals(3, m.moreFromBlog("a"));
        assertEquals(0, m.moreFromBlog("b"));
        assertEquals(0, m.moreFromBlog("unknown"), "never negative");
    }

    @Test
    void indexFailureSetsErrorMessageAndEmptyLists() throws Exception {
        when(index.find(any(), any())).thenThrow(new WebloggerException("index is down"));

        SearchResultsModel m = model("myblog", false, false, 0);

        assertEquals("index is down", m.getErrorMessage());
        assertTrue(m.getOwnHits().isEmpty());
        assertTrue(m.getOtherHits().isEmpty());
        assertEquals(0, m.getOwnTotal());
        assertNull(m.getWeblogEntriesPager().getNextLink());
    }

    @Test
    void anIllegalArgumentFromFindIsAnErrorStateNotAThrow() throws Exception {
        when(index.find(any(), any())).thenThrow(new IllegalArgumentException("BLOG search needs a blog handle"));

        SearchResultsModel m = model("myblog", false, false, 0);

        assertEquals("BLOG search needs a blog handle", m.getErrorMessage());
        assertTrue(m.getOwnHits().isEmpty());
    }

    @Test
    void aFailingOtherBlogsQueryLeavesNoStaleState() throws Exception {
        SearchHit own = hit("own");
        when(index.find(any(), any())).thenReturn(
                new SearchHits(List.of(own), 1, true, Map.of("a", 2), Set.of("travel"))).thenThrow(
                new WebloggerException("second failed"));

        SearchResultsModel m = model("myblog", false, false, 0);

        assertEquals("second failed", m.getErrorMessage());
        assertTrue(m.getOwnHits().isEmpty());
        assertFalse(m.isAnyWordFallback(), "a fallback flag from the first query is stale");
        assertEquals(0, m.getBlogCount());
        assertTrue(m.getCategories().isEmpty());
    }

    @Test
    void aPageNumberPastTheEndIsShownAsTheFirstPage() throws Exception {
        SearchHit a = hit("a");
        // find answers a request past its end with the first page
        when(index.find(any(), any())).thenReturn(hits(7, false, Map.of(), a));
        when(urls.getWeblogSearchURL(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(call -> "page-" + call.getArgument(4));

        SearchResultsModel m = model("myblog", false, true, 5);

        assertEquals(0, m.getOffset());
        assertNull(m.getWeblogEntriesPager().getPrevLink(), "page 0 has nothing before it");
    }

    @Test
    void aNegativePageNumberIsPageZeroForTheLinks() throws Exception {
        SearchHit a = hit("a");
        SearchHit b = hit("b");
        when(index.find(any(), any())).thenReturn(hits(25, false, Map.of(), a, b));
        when(urls.getWeblogSearchURL(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(call -> "page-" + call.getArgument(4));

        SearchResultsModel m = model("myblog", false, true, -3);

        assertEquals("page-1", m.getWeblogEntriesPager().getNextLink());
        assertNull(m.getWeblogEntriesPager().getPrevLink());
    }

    @Test
    void theBlogViewKeepsTheCategoriesOfItsOwnResult() throws Exception {
        SearchHit own = hit("own");
        when(index.find(any(), any())).thenReturn(
                new SearchHits(List.of(own), 1, false, Map.of(), Set.of("travel")), SearchHits.NONE);

        assertEquals(Set.of("travel"), model("myblog", false, false, 0).getCategories());
    }

    @Test
    void noQueryRunsNoSearch() throws Exception {
        config.when(() -> WebloggerRuntimeConfig.isSiteWideWeblog("myblog")).thenReturn(false);
        WeblogSearchRequest request = new WeblogSearchRequest();
        request.setWeblog(blog);
        request.setWeblogHandle("myblog");
        WeblogPageRequest pageRequest = new WeblogPageRequest();
        pageRequest.setWeblog(blog);
        Map<String, Object> init = new HashMap<>();
        init.put("searchRequest", request);
        init.put("parsedRequest", pageRequest);
        init.put("weblogger", weblogger);
        init.put("urlStrategy", urls);
        SearchResultsModel m = new SearchResultsModel();

        m.init(init);

        verify(index, never()).find(any(), any());
        assertTrue(m.getOwnHits().isEmpty());
        assertNull(m.getShowAllUrl());
    }

    @Test
    void urlsKeepTheWordsAndAskForTheRightScope() throws Exception {
        when(index.find(any(), any())).thenReturn(SearchHits.NONE);
        WeblogManager weblogManager = mock(WeblogManager.class);
        Weblog site = new Weblog();
        site.setHandle("home");
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogManager.getSiteSpace()).thenReturn(site);
        when(urls.getWeblogSearchURL(blog, "en_US", "spain", null, 0, true, false)).thenReturn("all");
        when(urls.getWeblogSearchURL(site, null, "spain", null, 0, false, true)).thenReturn("site");
        Weblog other = new Weblog();
        other.setHandle("other");
        when(urls.getWeblogSearchURL(other, null, "spain", null, 0, true, true)).thenReturn("other-blog");

        SearchResultsModel m = model("myblog", false, false, 0);

        assertEquals("all", m.getShowAllUrl());
        assertEquals("site", m.getSiteSearchUrl());
        assertEquals("other-blog", m.blogSearchUrl(WeblogWrapper.wrap(other, urls, weblogger)));
    }

    @Test
    void noSiteSpaceMeansNoSiteSearchUrl() throws Exception {
        when(index.find(any(), any())).thenReturn(SearchHits.NONE);
        WeblogManager weblogManager = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogManager.getSiteSpace()).thenReturn(null);

        assertNull(model("myblog", false, false, 0).getSiteSearchUrl());
    }

    @Test
    void theDayGroupedBridgeHoldsTheOwnHits() throws Exception {
        SearchHit own = hit("own");
        SearchHit other = hit("other");
        when(index.find(any(), any())).thenReturn(
                hits(1, false, Map.of(), own), hits(1, false, Map.of(), other));

        SearchResultsModel m = model("myblog", false, false, 0);

        assertEquals(1, m.getResults().size());
        assertEquals(1, m.getResults().values().iterator().next().size());
        verifyNoInteractions(urls);
    }
}
