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
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.config.RuntimeConfigAttachment;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogEntriesDayPager;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogEntriesLatestPager;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogEntriesMonthPager;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogEntriesPager;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogEntriesPermalinkPager;
import com.wikantik.pressroom.ui.rendering.util.WeblogFeedRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PageModel}, the {@code $model} object on every weblog
 * page. Themes reach for {@code $model.weblog}, {@code $model.weblogEntry},
 * {@code $model.permalink} and {@code $model.getWeblogEntriesPager()} on
 * essentially every request.
 *
 * <p>The most consequential logic here is the choice of pager: which one gets
 * built decides whether a URL shows one post, one day, one month or the latest
 * entries. Getting it wrong turns a permalink into a front page, which search
 * engines and feed readers both notice.
 */
class PageModelTest {

    private RuntimeConfigAttachment runtimeConfig;
    private Weblogger weblogger;
    private Weblog weblog;
    private String previousRelativeContextURL;
    private String previousAbsoluteContextURL;

    @BeforeEach
    void setUp() {
        // The facade the model is GIVEN through initData: its managers are
        // stubbed per test. Deliberately not what the static returns, below.
        weblogger = mock(Weblogger.class);
        // WebloggerRuntimeConfig's site.* reads go through the properties
        // manager attached to it (spec Decision 8 / plan Task 19); nothing else
        // is reachable statically, so a model that regressed to locating its
        // managers statically would fail here rather than pass.
        runtimeConfig = RuntimeConfigAttachment.of(mock(PropertiesManager.class));

        previousRelativeContextURL = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("/roller");
        previousAbsoluteContextURL = WebloggerRuntimeConfig.getAbsoluteContextURL();
        WebloggerRuntimeConfig.setAbsoluteContextURL("http://localhost:8080/roller");

        weblog = new Weblog("testblog", "testuser", "Test Blog", "a test blog",
                "blog@example.com", "journal", "en_US", "UTC");
    }

    @AfterEach
    void tearDown() {
        WebloggerRuntimeConfig.setRelativeContextURL(previousRelativeContextURL);
        WebloggerRuntimeConfig.setAbsoluteContextURL(previousAbsoluteContextURL);
        runtimeConfig.close();
    }

    private WeblogPageRequest pageRequest() {
        WeblogPageRequest request = new WeblogPageRequest();
        request.setWeblog(weblog);
        request.setWeblogHandle(weblog.getHandle());
        return request;
    }

    private PageModel modelFor(WeblogPageRequest request, Map<String, Object> extras)
            throws WebloggerException {
        Map<String, Object> initData = new HashMap<>(extras);
        initData.put("parsedRequest", request);
        initData.put("weblogger", weblogger);
        initData.putIfAbsent("urlStrategy", new MultiWeblogURLStrategy());
        PageModel model = new PageModel();
        model.init(initData);
        return model;
    }

    private PageModel modelFor(WeblogPageRequest request) throws WebloggerException {
        return modelFor(request, Map.of());
    }

    // ------------------------------------------------------------------ init

    @Test
    void modelIsRegisteredUnderTheNameThemesUse() {
        assertEquals("model", new PageModel().getModelName(),
                "Themes reference this model as $model.");
    }

    @Test
    void initWithoutAParsedRequestFails() {
        PageModel model = new PageModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(new HashMap<>()),
                "init() must reject init data with no request.");
        assertTrue(thrown.getMessage().contains("weblogRequest"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    @Test
    void initRejectsRequestsThatAreNotPageRequests() {
        // PageModel casts the request to a WeblogPageRequest. Loading it for a
        // feed would otherwise fail with a bare ClassCastException halfway
        // through rendering.
        WeblogFeedRequest feedRequest = new WeblogFeedRequest();
        feedRequest.setWeblog(weblog);
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", feedRequest);

        PageModel model = new PageModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData),
                "PageModel must refuse a request it cannot serve.");
        assertTrue(thrown.getMessage().contains("WeblogPageRequest"),
                "The failure should say which request type is required; was: "
                        + thrown.getMessage());
    }

    @Test
    void initWithoutAUrlStrategyIsRefused() {
        // The model used to fall back to the static locator's strategy here. The
        // only caller that ever relied on that was WeblogCacheWarmupJob, which
        // now passes one; a missing strategy is a caller bug, and failing at
        // init beats a null link on every page.
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", pageRequest());
        initData.put("weblogger", weblogger);
        PageModel model = new PageModel();

        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData),
                "init() must refuse init data with no 'urlStrategy'.");
        assertTrue(thrown.getMessage().contains("urlStrategy"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    @Test
    void initWithoutAWebloggerIsRefused() {
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", pageRequest());
        initData.put("urlStrategy", new MultiWeblogURLStrategy());
        PageModel model = new PageModel();

        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData),
                "init() must refuse init data with no 'weblogger'.");
        assertTrue(thrown.getMessage().contains("weblogger"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    // ---------------------------------------------------------- request data

    @Test
    void theModelExposesTheRequestsLocaleAndWeblog() throws Exception {
        WeblogPageRequest request = pageRequest();
        request.setLocale("fr_FR");

        PageModel model = modelFor(request);

        assertEquals("fr_FR", model.getLocale(),
                "$model.locale drives which translation a theme renders.");
        assertEquals("testblog", model.getWeblog().getHandle(),
                "$model.weblog must be the blog the request is for.");
    }

    @Test
    void permalinkIsTrueOnlyWhenTheRequestNamesAnEntry() throws Exception {
        assertFalse(modelFor(pageRequest()).isPermalink(),
                "A collection view is not a permalink.");

        WeblogPageRequest request = pageRequest();
        request.setWeblogAnchor("hello-world");
        assertTrue(modelFor(request).isPermalink(),
                "$model.permalink gates the comment form and the canonical link tag.");
    }

    @Test
    void aPlainPageIsNeverSearchResults() throws Exception {
        // SearchResultsModel overrides this; the base page must say no, or every
        // page would render the "results for ..." heading.
        assertFalse(modelFor(pageRequest()).isSearchResults(),
                "PageModel itself never represents search results.");
    }

    @Test
    void theWeblogEntryIsExposedOnlyWhenTheRequestResolvedOne() throws Exception {
        assertNull(modelFor(pageRequest()).getWeblogEntry(),
                "With no entry on the request $model.weblogEntry must be null so the "
                        + "theme's #if guard works.");

        WeblogEntry entry = new WeblogEntry();
        entry.setAnchor("hello-world");
        entry.setTitle("Hello World");
        entry.setWebsite(weblog);
        WeblogPageRequest request = pageRequest();
        request.setWeblogAnchor("hello-world");
        request.setWeblogEntry(entry);

        assertEquals("Hello World", modelFor(request).getWeblogEntry().getTitle(),
                "The wrapped entry must be the one the request resolved.");
    }

    @Test
    void theWeblogCategoryIsExposedOnlyWhenTheRequestResolvedOne() throws Exception {
        assertNull(modelFor(pageRequest()).getWeblogCategory(),
                "A category name that matches nothing must yield null, not a blank "
                        + "wrapper.");

        WeblogCategory category = new WeblogCategory();
        category.setName("Java");
        WeblogPageRequest request = pageRequest();
        request.setWeblogCategoryName("Java");
        request.setWeblogCategory(category);

        assertEquals("Java", modelFor(request).getWeblogCategory().getName(),
                "The wrapped category must be the one the request resolved.");
    }

    @Test
    void theRequestedTagsAreExposedAsIs() throws Exception {
        assertNull(modelFor(pageRequest()).getTags(),
                "No /tags/ in the URL means no tag list.");

        WeblogPageRequest request = pageRequest();
        request.setTags(List.of("java", "roller"));
        assertEquals(List.of("java", "roller"), modelFor(request).getTags(),
                "$model.tags is what a theme prints as 'showing posts tagged ...'.");
    }

    @Test
    void theNamedTemplateIsExposedWhenTheRequestAsksForOne() throws Exception {
        WeblogTemplate template = new WeblogTemplate();
        template.setName("Sidebar");
        template.setLink("sidebar");
        WeblogPageRequest request = pageRequest();
        request.setWeblogPageName("sidebar");
        request.setWeblogPage(template);

        assertEquals("Sidebar", modelFor(request).getWeblogPage().getName(),
                "$model.weblogPage must be the template the URL named.");
    }

    @Test
    void withNoNamedTemplateTheThemesDefaultIsUsed() throws Exception {
        // Every blog URL that is not /page/<something> renders through the
        // theme's default template, so $model.weblogPage must resolve to it.
        WeblogTemplate template = new WeblogTemplate();
        template.setName("Weblog");
        template.setLink("weblog");
        WeblogTheme theme = mock(WeblogTheme.class);
        when(theme.getDefaultTemplate()).thenReturn(template);
        Weblog themed = mock(Weblog.class);
        ThemeManager themes = mock(ThemeManager.class);
        when(weblogger.getThemeManager()).thenReturn(themes);
        when(themes.getTheme(themed)).thenReturn(theme);

        WeblogPageRequest request = new WeblogPageRequest();
        request.setWeblog(themed);

        assertEquals("Weblog", modelFor(request).getWeblogPage().getName(),
                "With no template named in the URL the theme's default must be used.");
    }

    @Test
    void aThemeThatCannotSupplyADefaultTemplateYieldsNull() throws Exception {
        // A broken or half-uploaded custom theme must not take the page down; the
        // renderer above this decides what to do about the missing template.
        WeblogTheme theme = mock(WeblogTheme.class);
        when(theme.getDefaultTemplate()).thenThrow(new WebloggerException("no templates"));
        Weblog themed = mock(Weblog.class);
        ThemeManager themes = mock(ThemeManager.class);
        when(weblogger.getThemeManager()).thenReturn(themes);
        when(themes.getTheme(themed)).thenReturn(theme);

        WeblogPageRequest request = new WeblogPageRequest();
        request.setWeblog(themed);

        assertNull(modelFor(request).getWeblogPage(),
                "A theme that cannot supply a default template must yield null "
                        + "rather than propagating out of the renderer.");
    }

    // ------------------------------------------------------ request parameters

    @Test
    void requestParametersAreExposedByName() throws Exception {
        Map<String, String[]> params = Map.of(
                "page", new String[]{"2", "3"},
                "empty", new String[0]);

        PageModel model = modelFor(pageRequest(), Map.of("requestParameters", params));

        assertEquals("2", model.getRequestParameter("page"),
                "The first value wins when a parameter is repeated.");
        assertNull(model.getRequestParameter("empty"),
                "A parameter present but valueless must read as null.");
        assertNull(model.getRequestParameter("absent"),
                "An absent parameter must read as null.");
    }

    @Test
    void requestParametersReadAsNullWhenTheRendererPassedNone() throws Exception {
        // Feeds and previews do not pass a parameter map at all.
        assertNull(modelFor(pageRequest()).getRequestParameter("page"),
                "With no parameter map the accessor must return null, not throw.");
    }

    // ----------------------------------------------------------------- pagers

    @Test
    void anAnchorInTheRequestSelectsThePermalinkPager() throws Exception {
        WeblogPageRequest request = pageRequest();
        request.setWeblogAnchor("hello-world");

        assertInstanceOf(WeblogEntriesPermalinkPager.class,
                modelFor(request).getWeblogEntriesPager(),
                "A permalink URL must show exactly one entry.");
    }

    @Test
    void anEightCharacterDateSelectsTheDayPager() throws Exception {
        WeblogPageRequest request = pageRequest();
        request.setWeblogDate("20240115");

        assertInstanceOf(WeblogEntriesDayPager.class,
                modelFor(request).getWeblogEntriesPager(),
                "/date/YYYYMMDD is a single day's archive.");
    }

    @Test
    void aSixCharacterDateSelectsTheMonthPager() throws Exception {
        WeblogPageRequest request = pageRequest();
        request.setWeblogDate("202401");

        assertInstanceOf(WeblogEntriesMonthPager.class,
                modelFor(request).getWeblogEntriesPager(),
                "/date/YYYYMM is a month's archive.");
    }

    @Test
    void aDateOfAnyOtherLengthFallsBackToTheLatestPager() throws Exception {
        // Only 6 and 8 characters are meaningful; anything else must not be
        // mistaken for an archive request.
        WeblogPageRequest request = pageRequest();
        request.setWeblogDate("2024");

        assertInstanceOf(WeblogEntriesLatestPager.class,
                modelFor(request).getWeblogEntriesPager(),
                "An unrecognised date must degrade to the latest-entries view.");
    }

    @Test
    void aPlainRequestSelectsTheLatestPager() throws Exception {
        assertInstanceOf(WeblogEntriesLatestPager.class,
                modelFor(pageRequest()).getWeblogEntriesPager(),
                "The blog home page shows the latest entries.");
    }

    @Test
    void anExplicitCategoryArgumentOverridesTheOneInTheUrl() throws Exception {
        // $model.getWeblogEntriesPager("Java") inside a sidebar must be able to
        // show a different category from the one the visitor navigated to.
        WeblogPageRequest request = pageRequest();
        request.setWeblogCategoryName("News");

        WeblogEntriesPager pager = modelFor(request).getWeblogEntriesPager("Java");

        assertTrue(pager.getHomeLink().contains("category/Java"),
                "The argument must win over the request's category; link was: "
                        + pager.getHomeLink());
    }

    @Test
    void theNilAndEmptyCategoryArgumentsMeanUseWhateverTheUrlSaid() throws Exception {
        // "nil" is the documented way for a theme to say "no override" because
        // Velocity has no null literal. An empty string means the same.
        WeblogPageRequest request = pageRequest();
        request.setWeblogCategoryName("News");

        assertTrue(modelFor(request).getWeblogEntriesPager("nil").getHomeLink()
                        .contains("category/News"),
                "'nil' must not become a literal category name.");
        assertTrue(modelFor(request).getWeblogEntriesPager("").getHomeLink()
                        .contains("category/News"),
                "An empty override must not blank out the request's category.");
    }

    @Test
    void anExplicitTagArgumentReplacesTheTagsInTheUrl() throws Exception {
        WeblogPageRequest request = pageRequest();
        request.setTags(List.of("news"));

        WeblogEntriesPager pager = modelFor(request).getWeblogEntriesPagerByTag("java");

        assertTrue(pager.getHomeLink().contains("tags/java"),
                "$model.getWeblogEntriesPagerByTag('java') must show that tag; link "
                        + "was: " + pager.getHomeLink());
    }

    @Test
    void theNilAndEmptyTagArgumentsLeaveTheUrlsTagsAlone() throws Exception {
        WeblogPageRequest request = pageRequest();
        request.setTags(List.of("news"));

        assertTrue(modelFor(request).getWeblogEntriesPagerByTag("nil").getHomeLink()
                        .contains("tags/news"),
                "'nil' must not become a literal tag.");
        assertTrue(modelFor(request).getWeblogEntriesPagerByTag("").getHomeLink()
                        .contains("tags/news"),
                "An empty override must not blank out the request's tags.");
    }

    // ---------------------------------------------------------- canonical URL

    @Test
    void canonicalUrlOnAPermalinkIsTheAbsoluteEntryUrl() throws Exception {
        WeblogPageRequest request = pageRequest();
        request.setWeblogAnchor("my-entry");
        request.setWeblogEntry(new WeblogEntry());

        assertEquals("http://localhost:8080/roller/testblog/entry/my-entry",
                modelFor(request).getCanonicalUrl(),
                "With no per-entry override the canonical URL of a permalink is "
                        + "the entry's natural absolute URL.");
    }

    @Test
    void canonicalUrlHonoursThePerEntryOverride() throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setCanonicalUrl("https://elsewhere.example.com/original-post");
        WeblogPageRequest request = pageRequest();
        request.setWeblogAnchor("my-entry");
        request.setWeblogEntry(entry);

        assertEquals("https://elsewhere.example.com/original-post",
                modelFor(request).getCanonicalUrl(),
                "A non-blank per-entry canonical URL must win on the permalink: "
                        + "that is the whole point of the field.");
    }

    @Test
    void aBlankPerEntryOverrideIsIgnored() throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setCanonicalUrl("   ");
        WeblogPageRequest request = pageRequest();
        request.setWeblogAnchor("my-entry");
        request.setWeblogEntry(entry);

        assertEquals("http://localhost:8080/roller/testblog/entry/my-entry",
                modelFor(request).getCanonicalUrl(),
                "Whitespace in the editor field must not produce a blank "
                        + "rel=canonical, which would deindex the entry.");
    }

    @Test
    void canonicalUrlOnTheFrontPageIsTheAbsoluteHomeUrl() throws Exception {
        assertEquals("http://localhost:8080/roller/testblog/",
                modelFor(pageRequest()).getCanonicalUrl(),
                "The front page canonicalises to the weblog's home URL.");
    }

    @Test
    void canonicalUrlOnACollectionKeepsCategoryAndPageNumber() throws Exception {
        WeblogPageRequest request = pageRequest();
        request.setWeblogCategoryName("News");
        request.setPageNum(2);

        String canonical = modelFor(request).getCanonicalUrl();

        assertTrue(canonical.contains("category/News"),
                "A category page canonicalises to that category, not to the home "
                        + "page; was: " + canonical);
        assertTrue(canonical.contains("page=2"),
                "Page 2 must canonicalise to itself, not claim to be a duplicate "
                        + "of page 1; was: " + canonical);
    }

    @Test
    void canonicalUrlOnACustomPageUsesThePageUrl() throws Exception {
        WeblogPageRequest request = pageRequest();
        request.setWeblogPageName("directory");

        String canonical = modelFor(request).getCanonicalUrl();

        assertTrue(canonical.contains("page/directory"),
                "A custom page canonicalises to its own URL; was: " + canonical);
        assertTrue(canonical.startsWith("http://localhost:8080/roller/testblog/"),
                "The canonical URL must be absolute; was: " + canonical);
    }

    // ------------------------------------------------------------- nav pages

    @Test
    void getNavPagesReturnsOnlyThePublishedPagesMarkedShowInNav() throws Exception {
        WeblogPageManager pageManager = mock(WeblogPageManager.class);
        when(weblogger.getWeblogPageManager()).thenReturn(pageManager);

        WeblogPage shown = new WeblogPage();
        shown.setTitle("Shown");
        shown.setShowInNav(true);
        WeblogPage hidden = new WeblogPage();
        hidden.setTitle("Hidden");
        hidden.setShowInNav(false);
        when(pageManager.getPublishedPages(weblog)).thenReturn(List.of(shown, hidden));

        PageModel model = modelFor(pageRequest());

        assertEquals(List.of(shown), model.getNavPages(model.getWeblog()),
                "getNavPages must keep only the pages with showInNav=true, in the "
                        + "order the manager already returned them.");
    }

    @Test
    void getNavPagesDegradesToAnEmptyListWhenTheManagerFails() throws Exception {
        // A page lookup failure must not take the whole rendered page down --
        // the theme's nav just renders with nothing extra in it.
        WeblogPageManager pageManager = mock(WeblogPageManager.class);
        when(weblogger.getWeblogPageManager()).thenReturn(pageManager);
        when(pageManager.getPublishedPages(weblog))
                .thenThrow(new WebloggerException("boom"));

        PageModel model = modelFor(pageRequest());

        assertEquals(List.of(), model.getNavPages(model.getWeblog()),
                "A WebloggerException from the manager must not propagate into "
                        + "the rendered page.");
    }

    @Test
    void canonicalUrlIsNullOnSearchResults() throws Exception {
        // SearchResultsModel overrides isSearchResults(); a search page has no
        // canonical form, so the macro must get null and emit nothing.
        WeblogPageRequest request = pageRequest();
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", request);
        initData.put("weblogger", weblogger);
        initData.put("urlStrategy", new MultiWeblogURLStrategy());
        PageModel model = new PageModel() {
            @Override
            public boolean isSearchResults() {
                return true;
            }
        };
        model.init(initData);

        assertNull(model.getCanonicalUrl(),
                "Search pages must not claim a canonical URL.");
    }

    // ------------------------------------------------------- date archives

    /**
     * A date archive had no heading to show, because the model exposed
     * neither the archive's date nor a readable label for it. The label is
     * the month or day in the weblog's own language, and null off an archive.
     */
    @Test
    void aDateArchiveHasAReadableLabelInTheWeblogsLanguage() throws Exception {
        WeblogPageRequest month = pageRequest();
        month.setWeblogDate("202610");
        assertEquals("202610", modelFor(month).getWeblogDate());
        assertEquals("October 2026", modelFor(month).getWeblogDateLabel());

        WeblogPageRequest day = pageRequest();
        day.setWeblogDate("20261022");
        assertEquals("October 22, 2026", modelFor(day).getWeblogDateLabel());

        weblog.setLocale("de_DE");
        assertEquals("Oktober 2026", modelFor(month).getWeblogDateLabel());
    }

    @Test
    void noDateOrAnImpossibleOneHasNoLabel() throws Exception {
        assertNull(modelFor(pageRequest()).getWeblogDate());
        assertNull(modelFor(pageRequest()).getWeblogDateLabel());

        WeblogPageRequest impossible = pageRequest();
        impossible.setWeblogDate("202613");
        assertNull(modelFor(impossible).getWeblogDateLabel(), "month 13 is no archive");
    }
}
