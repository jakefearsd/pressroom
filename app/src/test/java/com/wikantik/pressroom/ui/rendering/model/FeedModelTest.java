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

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MultiWeblogURLStrategy;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.RuntimeConfigAttachment;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.ui.rendering.pagers.Pager;
import com.wikantik.pressroom.ui.rendering.util.WeblogFeedRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FeedModel}, the {@code $model} object used while
 * rendering RSS and Atom.
 *
 * <p>What matters here is that the paging links a feed advertises carry the
 * same narrowing the reader subscribed with. A feed for one category or tag
 * whose "next page" link drops the filter quietly serves the whole blog to a
 * subscriber who asked for a slice of it.
 */
class FeedModelTest {

    private static final String ABSOLUTE_SITE = "http://blogs.example.com/roller";

    private RuntimeConfigAttachment runtimeConfig;
    private Weblogger weblogger;
    private PropertiesManager properties;
    private Weblog weblog;
    private String previousRelativeContextURL;

    @BeforeEach
    void setUp() {
        // The facade the model is GIVEN through initData (plan Task 10).
        weblogger = mock(Weblogger.class);
        properties = mock(PropertiesManager.class);
        when(weblogger.getPropertiesManager()).thenReturn(properties);
        // WebloggerRuntimeConfig's feed-size read goes through the manager
        // attached to it (plan Task 19); the FeedEntriesPager the model builds
        // takes the facade from the model (plan Task 11), and nothing else is
        // reachable statically any more.
        runtimeConfig = RuntimeConfigAttachment.of(properties);

        previousRelativeContextURL = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("/roller");
        WebloggerRuntimeConfig.setAbsoluteContextURL(ABSOLUTE_SITE);

        weblog = new Weblog("testblog", "testuser", "Test Blog", "a test blog",
                "blog@example.com", "journal", "en_US", "UTC");
    }

    @AfterEach
    void tearDown() {
        WebloggerRuntimeConfig.setRelativeContextURL(previousRelativeContextURL);
        WebloggerRuntimeConfig.setAbsoluteContextURL(null);
        runtimeConfig.close();
    }

    private WeblogFeedRequest feedRequest(String type, String format) {
        WeblogFeedRequest request = new WeblogFeedRequest();
        request.setWeblog(weblog);
        request.setWeblogHandle(weblog.getHandle());
        request.setType(type);
        request.setFormat(format);
        return request;
    }

    private FeedModel modelFor(WeblogFeedRequest request) throws WebloggerException {
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", request);
        initData.put("weblogger", weblogger);
        initData.put("urlStrategy", new MultiWeblogURLStrategy());
        FeedModel model = new FeedModel();
        model.init(initData);
        return model;
    }

    /**
     * The entries pager's self URL is on the concrete pager, not on
     * {@link Pager}, so the cast is unavoidable — and it doubles as an
     * assertion that the model built the pager type it is supposed to.
     */
    private static FeedModel.FeedEntriesPager entriesPager(FeedModel model) {
        return (FeedModel.FeedEntriesPager) model.getWeblogEntriesPager();
    }

    private static String path(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    private static Map<String, String> queryParams(String url) {
        int q = url.indexOf('?');
        Map<String, String> params = new LinkedHashMap<>();
        if (q < 0) {
            return params;
        }
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return params;
    }

    // ------------------------------------------------------------------ init

    @Test
    void modelIsRegisteredUnderTheNameThemesUse() {
        assertEquals("model", new FeedModel().getModelName(),
                "Feed templates reference this model as $model, the same name pages "
                        + "use, so one template can serve both.");
    }

    @Test
    void initWithoutAParsedRequestFails() {
        FeedModel model = new FeedModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(new HashMap<>()),
                "init() must reject init data with no request.");
        assertTrue(thrown.getMessage().contains("weblogRequest"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    @Test
    void initRejectsRequestsThatAreNotFeedRequests() {
        WeblogPageRequest pageRequest = new WeblogPageRequest();
        pageRequest.setWeblog(weblog);
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", pageRequest);

        FeedModel model = new FeedModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData),
                "FeedModel must refuse a request it cannot serve.");
        assertTrue(thrown.getMessage().contains("WeblogFeedRequest"),
                "The failure should say which request type is required; was: "
                        + thrown.getMessage());
    }

    @Test
    void initWithoutAUrlStrategyIsRefused() {
        // Used to fall back to the static locator's strategy; the only caller
        // that relied on that (WeblogCacheWarmupJob) now passes one.
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", feedRequest("entries", "rss"));
        initData.put("weblogger", weblogger);
        FeedModel model = new FeedModel();

        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData),
                "init() must refuse init data with no 'urlStrategy'.");
        assertTrue(thrown.getMessage().contains("urlStrategy"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    @Test
    void initWithoutAWebloggerIsRefused() {
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", feedRequest("entries", "rss"));
        initData.put("urlStrategy", new MultiWeblogURLStrategy());
        FeedModel model = new FeedModel();

        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData),
                "init() must refuse init data with no 'weblogger'.");
        assertTrue(thrown.getMessage().contains("weblogger"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    // ---------------------------------------------------------- request data

    @Test
    void theModelExposesWhatTheFeedRequestAskedFor() throws Exception {
        WeblogFeedRequest request = feedRequest("entries", "atom");
        request.setLocale("fr_FR");
        request.setWeblogCategoryName("Tech News");
        request.setTags(List.of("java", "pressroom"));
        request.setExcerpts(true);

        FeedModel model = modelFor(request);

        assertEquals("fr_FR", model.getLocale(), "$model.locale drives feed language.");
        assertEquals("Tech News", model.getCategoryName(),
                "$model.categoryName is rendered into the feed title.");
        assertEquals(List.of("java", "pressroom"), model.getTags(),
                "$model.tags is rendered into the feed title.");
        assertTrue(model.getExcerpts(),
                "$model.excerpts decides whether entries carry full text.");
        assertEquals("testblog", model.getWeblog().getHandle(),
                "$model.weblog must be the blog the feed is for.");
    }

    @Test
    void anUnfilteredFeedReportsNoCategoryTagsOrExcerpts() throws Exception {
        FeedModel model = modelFor(feedRequest("entries", "rss"));

        assertNull(model.getCategoryName(), "No category filter means null, not \"\".");
        assertNull(model.getTags(), "No tag filter means null.");
        assertFalse(model.getExcerpts(), "Full text is the default.");
    }

    // ---------------------------------------------------------------- pagers

    @Test
    void theEntriesPagerUrlIsTheAbsoluteFeedUrl() throws Exception {
        Pager<?> pager = modelFor(feedRequest("entries", "rss")).getWeblogEntriesPager();

        assertEquals(ABSOLUTE_SITE + "/testblog/feed/entries/rss", pager.getHomeLink(),
                "A feed's self link must be absolute — it is the feed's identity to "
                        + "every subscriber's reader.");
    }

    @Test
    void theEntriesPagerUrlKeepsTheCategoryTagsAndExcerptFilters() throws Exception {
        WeblogFeedRequest request = feedRequest("entries", "atom");
        request.setWeblogCategoryName("Tech News");
        request.setTags(List.of("java", "pressroom"));
        request.setExcerpts(true);

        String url = entriesPager(modelFor(request)).getUrl();

        assertEquals(ABSOLUTE_SITE + "/testblog/feed/entries/atom", path(url),
                "The filters belong in the query string, not the path.");
        assertEquals(Map.of("tags", "java+pressroom", "cat", "Tech+News", "excerpts", "true"),
                queryParams(url),
                "Every filter the subscriber asked for must survive into the feed's "
                        + "own URL, encoded.");
    }

    @Test
    void theEntriesPagerDropsFiltersThatAreNotSet() throws Exception {
        WeblogFeedRequest request = feedRequest("entries", "atom");
        request.setWeblogCategoryName("   ");
        request.setTags(List.of());

        assertEquals(Map.of(), queryParams(entriesPager(modelFor(request)).getUrl()),
                "A blank category and an empty tag list must not become empty query "
                        + "parameters.");
    }

    /**
     * Regression test. {@code AbstractPager} builds its next/previous links
     * from {@code Map.of("page", ...)}, which is immutable; the feed pagers
     * (this one, plus the media-files and search pagers since removed) used
     * to write their filters straight into that map, so asking a filtered
     * feed for a paging link threw {@code UnsupportedOperationException} and
     * took the whole feed render down.
     */
    @Test
    void pagingLinksCarryTheFiltersInsteadOfThrowing() throws Exception {
        WeblogFeedRequest request = feedRequest("entries", "atom");
        request.setWeblogCategoryName("Tech");
        request.setTags(List.of("java"));
        request.setPage(2);

        FeedModel model = modelFor(request);

        assertEquals(Map.of("page", "1", "cat", "Tech", "tags", "java"),
                queryParams(model.getWeblogEntriesPager().getPrevLink()),
                "Paging back inside a filtered entry feed must stay inside the filter.");
    }

    @Test
    void theFirstPageHasNoPreviousLink() throws Exception {
        assertNull(modelFor(feedRequest("entries", "atom")).getWeblogEntriesPager().getPrevLink(),
                "Page 0 must not advertise a previous page.");
    }

    /**
     * The number of entries a feed carries is read from the runtime properties
     * table on every request. It used to be captured in a {@code static final}
     * at class-load time, which meant an administrator changing it in the admin
     * UI saw no effect until the application was restarted.
     */
    @Test
    void theConfiguredEntryCountIsAppliedToTheFeedQuery() throws Exception {
        when(properties.getProperty("site.newsfeeds.defaultEntries"))
                .thenReturn(new RuntimeConfigProperty("site.newsfeeds.defaultEntries", "25"));
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);

        WeblogFeedRequest request = feedRequest("entries", "atom");
        request.setPage(2);
        modelFor(request).getWeblogEntriesPager();

        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(entryManager).getWeblogEntries(criteria.capture());

        assertEquals(26, criteria.getValue().getMaxResults(),
                "The query asks for one more than the page size so the pager can "
                        + "tell whether another page exists.");
        assertEquals(50, criteria.getValue().getOffset(),
                "Page 2 of 25-entry pages starts at offset 50; a wrong entry count "
                        + "silently shifts every page boundary.");
    }
}
