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
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.RuntimeConfigAttachment;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.rendering.util.WeblogRequest;
import com.wikantik.pressroom.util.URLUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link URLModel}, the {@code $url} object themes use to build
 * every link on a blog — permalinks, category and date archives, feeds, search
 * and their paging links.
 *
 * <p>The tests run against the real {@link MultiWeblogURLStrategy} rather than a
 * mock, because the thing worth protecting is the shape of the emitted URL, not
 * the fact that a delegation happened. A URL that changes shape breaks
 * permalinks and feed identity for every existing post.
 *
 * <p>A mock properties manager is attached to {@code WebloggerRuntimeConfig} so nothing reaches the database:
 * the strategy only needs the two context URLs, which are plain static fields.
 */
class URLModelTest {

    private static final String SITE = "/roller";
    private static final String ABSOLUTE_SITE = "http://blogs.example.com/roller";

    private RuntimeConfigAttachment runtimeConfig;
    private Weblogger weblogger;
    private Weblog weblog;
    private URLModel model;

    private String previousRelativeContextURL;

    @BeforeEach
    void setUp() throws Exception {
        // WebloggerRuntimeConfig reads site.absoluteurl through the properties
        // manager attached to it before falling back to the value the
        // InitFilter published (spec Decision 8 / plan Task 19); a mock manager
        // is attached so that lookup answers null quietly on every URL built.
        // The facade the model is GIVEN through initData.
        weblogger = mock(Weblogger.class);
        runtimeConfig = RuntimeConfigAttachment.of(mock(PropertiesManager.class));

        previousRelativeContextURL = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL(SITE);
        WebloggerRuntimeConfig.setAbsoluteContextURL(ABSOLUTE_SITE);

        weblog = new Weblog("testblog", "testuser", "Test Blog", "a test blog",
                "blog@example.com", "journal", "en_US", "UTC");

        model = modelFor(weblog, null, new MultiWeblogURLStrategy());
    }

    @AfterEach
    void tearDown() {
        WebloggerRuntimeConfig.setRelativeContextURL(previousRelativeContextURL);
        WebloggerRuntimeConfig.setAbsoluteContextURL(null);
        runtimeConfig.close();
    }

    private URLModel modelFor(Weblog weblog, String locale, URLStrategy strategy)
            throws WebloggerException {
        WeblogRequest request = new WeblogRequest();
        request.setWeblog(weblog);
        request.setLocale(locale);

        Map<String, Object> initData = new LinkedHashMap<>();
        initData.put("parsedRequest", request);
        initData.put("weblogger", weblogger);
        if (strategy != null) {
            initData.put("urlStrategy", strategy);
        }

        URLModel model = new URLModel();
        model.init(initData);
        return model;
    }

    /** Everything before the '?'. */
    private static String path(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    /**
     * The query string as a map, values left percent-encoded. Compared as a map
     * because the strategy builds it from a HashMap, so the order of the pairs
     * is an implementation detail we must not depend on.
     */
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
        assertEquals("url", new URLModel().getModelName(),
                "Themes reference this model as $url; renaming it blanks every link "
                        + "in every theme at once.");
    }

    @Test
    void initWithoutAParsedRequestFails() {
        URLModel bare = new URLModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> bare.init(new LinkedHashMap<>()),
                "init() must reject init data with no request rather than building "
                        + "URLs for a null weblog.");
        assertTrue(thrown.getMessage().contains("weblogRequest"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    @Test
    void initWithoutAUrlStrategyIsRefused() {
        // Used to fall back to the static locator's strategy; the only caller
        // that relied on that (WeblogCacheWarmupJob) now passes one. Failing
        // at init beats a null link mid-render.
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> modelFor(weblog, null, null),
                "init() must refuse init data with no 'urlStrategy'.");
        assertTrue(thrown.getMessage().contains("urlStrategy"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    @Test
    void initWithoutAWebloggerIsRefused() {
        WeblogRequest request = new WeblogRequest();
        request.setWeblog(weblog);
        Map<String, Object> initData = new LinkedHashMap<>();
        initData.put("parsedRequest", request);
        initData.put("urlStrategy", new MultiWeblogURLStrategy());
        URLModel model = new URLModel();

        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData),
                "init() must refuse init data with no 'weblogger'.");
        assertTrue(thrown.getMessage().contains("weblogger"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    // ------------------------------------------------------------ site roots

    @Test
    void siteUrlsComeFromTheContextTheApplicationIsDeployedUnder() {
        assertEquals(SITE, model.getSite(),
                "$url.site is the relative context path; every hand-built link in a "
                        + "theme is prefixed with it.");
        assertEquals(ABSOLUTE_SITE, model.getAbsoluteSite(),
                "$url.absoluteSite is what feeds and e-mails use, so it must be the "
                        + "absolute form.");
    }

    @Test
    void absoluteSiteIgnoresTheRetiredPerWeblogPropertyEvenIfSomethingStillSetsIt() {
        // weblog.absoluteurl.<handle> used to let a weblog override $url.absoluteSite
        // with a vanity domain. That branch is gone: $url.absoluteSite means the SITE,
        // and every caller (the frontpage theme's brand/menu links, the atom feeds'
        // stylesheet href and site <id>, error-page.vm) is a site-level or
        // control-plane link that must stay on the installation's own host regardless
        // of any weblog's custom domain. A weblog's own urls come from
        // MultiWeblogURLStrategy, not this model method.
        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class)) {
            config.when(() -> WebloggerConfig.getProperty("weblog.absoluteurl.testblog"))
                    .thenReturn("https://vanity.example.com");

            assertEquals(ABSOLUTE_SITE, model.getAbsoluteSite(),
                    "getAbsoluteSite must no longer read the per-weblog property at all.");

            // The one static read allowed is the SITE's own url: a blank
            // Global Config row falls back to PRESSROOM_SITE_ABSOLUTEURL, which
            // lives in the static config. Nothing per-weblog may be read.
            config.verify(() -> WebloggerConfig.getProperty("site.absoluteurl"), atMostOnce());
            config.verifyNoMoreInteractions();
        }
    }

    @Test
    void loginAndLogoutPointAtTheSpringSecurityEndpoints() {
        assertEquals(SITE + "/pressroom-ui/login-redirect.rol", model.getLogin(),
                "The login link must match the URL SecurityConfig protects.");
        assertEquals(SITE + "/pressroom-ui/logout.rol", model.getLogout(),
                "The logout link must match the URL Spring Security listens on.");
    }

    @Test
    void actionUrlsAreBuiltPerNamespaceAndUnknownNamespacesYieldNothing() {
        assertEquals(ABSOLUTE_SITE + "/pressroom-ui/login.rol",
                model.action("login", "/pressroom-ui"),
                "Site-level actions take no weblog parameter.");

        String authoring = model.action("entryAdd", "/pressroom-ui/authoring");
        assertEquals(ABSOLUTE_SITE + "/pressroom-ui/authoring/entryAdd.rol", path(authoring),
                "Authoring actions live under the authoring namespace.");
        assertEquals(Map.of("weblog", "testblog"), queryParams(authoring),
                "Authoring actions must carry the weblog handle or the action cannot "
                        + "tell which blog it is editing.");

        assertEquals(ABSOLUTE_SITE + "/pressroom-ui/admin/globalConfig.rol",
                model.action("globalConfig", "/pressroom-ui/admin"),
                "Admin actions live under the admin namespace.");

        assertNull(model.action("whatever", "/made/up"),
                "An unrecognised namespace must yield null rather than a link that "
                        + "404s; the theme then renders nothing.");
        assertNull(model.action("whatever", null),
                "A null namespace must yield null.");
    }

    @Test
    void themeResourceUrlsHangOffTheSiteRoot() {
        assertEquals(SITE + "/themes/journal/style.css",
                model.themeResource("journal", "style.css"),
                "Theme resources are served straight off the site root.");
        assertEquals(ABSOLUTE_SITE + "/themes/journal/style.css",
                model.themeResource("journal", "style.css", true),
                "The absolute form is needed inside feeds.");
        assertEquals(SITE + "/themes/journal/style.css",
                model.themeResource("journal", "style.css", false),
                "absolute=false must behave like the two-argument form.");
    }

    // -------------------------------------------------------- weblog content

    @Test
    void homeUrlsCarryTheLocaleAndPageNumber() {
        assertEquals(ABSOLUTE_SITE + "/testblog/", model.getHome(),
                "The blog home page is the weblog root.");
        assertEquals(ABSOLUTE_SITE + "/testblog/?page=2", model.home(2),
                "Paging the home page adds ?page=N.");
        assertEquals(ABSOLUTE_SITE + "/testblog/", model.home(0),
                "Page 0 is the first page and must not add a page parameter.");
        assertEquals(ABSOLUTE_SITE + "/testblog/fr/", model.home("fr"),
                "A locale becomes a path segment after the handle.");
        assertEquals(ABSOLUTE_SITE + "/testblog/fr/?page=3", model.home("fr", 3),
                "Locale and page number must combine.");
    }

    @Test
    void aLocalisedRequestPutsTheLocaleInEveryUrl() throws Exception {
        // The locale is picked up from the request, not passed per call, so a
        // mistake here silently drops visitors back to the default language.
        URLModel french = modelFor(weblog, "fr_FR", new MultiWeblogURLStrategy());
        assertEquals(ABSOLUTE_SITE + "/testblog/fr_FR/", french.getHome(),
                "The request locale must appear in generated URLs.");
        assertEquals(ABSOLUTE_SITE + "/testblog/fr_FR/entry/hello", french.entry("hello"),
                "Permalinks must stay inside the locale the visitor is reading.");
    }

    @Test
    void entryPermalinksEncodeTheAnchor() {
        // The anchor comes from the entry title, so it can contain anything a
        // user can type. Leaving it raw would produce links that break at the
        // first space or ampersand.
        assertEquals(ABSOLUTE_SITE + "/testblog/entry/hello+%26+goodbye",
                model.entry("hello & goodbye"),
                "The anchor must be URL-encoded into the path.");
    }

    @Test
    void dateArchiveUrlsUseThePathFormTheRequestParserExpects() {
        // WeblogPageRequest only recognises /date/YYYYMMDD (or YYYYMM), so this
        // shape has to match on both sides or archive links 404.
        assertEquals(ABSOLUTE_SITE + "/testblog/date/20240115", model.date("20240115"),
                "Day archives are /date/YYYYMMDD.");
        assertEquals(ABSOLUTE_SITE + "/testblog/date/202401?page=2", model.date("202401", 2),
                "Month archives page with ?page=N.");
    }

    @Test
    void categoryUrlsEncodeTheCategoryName() {
        // Category names are free text: "C++ & friends" is legal.
        assertEquals(ABSOLUTE_SITE + "/testblog/category/Java+%26+C%2B%2B",
                model.category("Java & C++"),
                "A category name must be encoded into the path or the '&' starts a "
                        + "bogus query parameter.");
        assertEquals(ABSOLUTE_SITE + "/testblog/category/Java?page=2",
                model.category("Java", 2),
                "Category archives page with ?page=N.");
    }

    @Test
    void theRootCategorySentinelMeansNoCategoryFilter() {
        // "root" is how the older templates spelled "all categories"; it must
        // not become a literal /category/root path.
        assertEquals(ABSOLUTE_SITE + "/testblog/", model.category("root"),
                "$url.category('root') must be the unfiltered blog home.");
    }

    @Test
    void tagUrlsEncodeAndJoinTagsWithAPlus() {
        assertEquals(ABSOLUTE_SITE + "/testblog/tags/foo+bar", model.tag("foo bar"),
                "A single tag containing a space is encoded, and '+' is the encoded "
                        + "space — the same character used to join tags.");
        assertEquals(ABSOLUTE_SITE + "/testblog/tags/foo?page=2", model.tag("foo", 2),
                "Tag archives page with ?page=N.");
        assertEquals(ABSOLUTE_SITE + "/testblog/tags/foo+bar",
                model.tags(List.of("foo", "bar")),
                "Multiple tags are joined with '+' to mean intersection.");
    }

    /**
     * Regression test for a copy/paste bug: {@code tags(List, int)} passed a
     * hard-coded -1 as the page number, so "older entries" on any multi-tag
     * view linked back to page 1 and the reader could never page past the
     * first screen.
     */
    @Test
    void pagingAMultiTagViewActuallyChangesThePage() {
        assertEquals(ABSOLUTE_SITE + "/testblog/tags/foo+bar?page=2",
                model.tags(List.of("foo", "bar"), 2),
                "$url.tags($tags, 2) must produce page 2, not page 1.");
    }

    @Test
    void combiningADateAndACategoryFallsBackToQueryParameters() {
        // There is no path form for "this category in this month", so both go
        // in the query string.
        String url = model.collection("20240115", "Java & C++");
        assertEquals(ABSOLUTE_SITE + "/testblog/", path(url),
                "A combined filter has no path segment of its own.");
        assertEquals(Map.of("date", "20240115", "cat", "Java+%26+C%2B%2B"),
                queryParams(url),
                "Both filters must survive as encoded query parameters.");

        String paged = model.collection("20240115", "Java", 2);
        assertEquals(Map.of("date", "20240115", "cat", "Java", "page", "2"),
                queryParams(paged),
                "The page number joins the other filters.");
    }

    @Test
    void customPageUrlsCarryTheirFiltersAsQueryParameters() {
        assertEquals(ABSOLUTE_SITE + "/testblog/page/sidebar", model.page("sidebar"),
                "A custom template is addressed as /page/<link>.");

        String url = model.page("sidebar", "20240115", "Java", 2);
        assertEquals(ABSOLUTE_SITE + "/testblog/page/sidebar", path(url),
                "The filters must not disturb the page path.");
        assertEquals(Map.of("date", "20240115", "cat", "Java", "page", "2"),
                queryParams(url),
                "Custom pages only support query-parameter filters.");
    }

    @Test
    void pageLinksEncodeSpecialCharacters() {
        // Page links come from user input and can contain special characters
        // like spaces and ampersands. Leaving them raw would break the URL
        // at the first space or ampersand, just as with entry anchors.
        String encodedLink = URLUtilities.encode("notes & maps");
        assertEquals(ABSOLUTE_SITE + "/testblog/page/" + encodedLink, model.page("notes & maps"),
                "The page link must be URL-encoded into the path.");
    }

    @Test
    void resourceUrlsEncodeEachPathSegmentButKeepTheSlashes() {
        assertEquals(ABSOLUTE_SITE + "/testblog/resource/my+images/holiday+snap.png",
                model.resource("my images/holiday snap.png"),
                "Directory separators must survive encoding or the file cannot be "
                        + "found; everything else in each segment is encoded.");
        assertEquals(ABSOLUTE_SITE + "/testblog/resource/logo.png",
                model.resource("/logo.png"),
                "A leading slash is stripped so the path is not doubled.");
    }

    // -------------------------------------------------------------- searching

    @Test
    void theBareSearchUrlIsRelativeAndCarriesNoQuery() {
        // The search box posts to this URL, so it must be relative (same origin)
        // and must not arrive with a stale query already attached.
        assertEquals(SITE + "/testblog/search", model.getSearch(),
                "$url.search is the search form's action.");
    }

    @Test
    void searchUrlsEncodeTheQueryAndCarryTheCategoryAndPage() {
        String url = model.search("c++ tricks", 2);
        assertEquals(SITE + "/testblog/search", path(url), "Search stays at /search");
        assertEquals(Map.of("q", "c%2B%2B+tricks", "page", "2"), queryParams(url),
                "The query must be encoded — a raw '+' would decode as a space and "
                        + "silently change what the reader searched for.");

        assertEquals(Map.of("q", "java", "cat", "Tech+News", "page", "3"),
                queryParams(model.search("java", "Tech News", 3)),
                "Category-scoped search keeps all three parameters.");

        assertEquals(Map.of("q", "java"), queryParams(model.search("java", null, 0)),
                "Page 0 and a null category must not add empty parameters.");
    }

    @Test
    void absoluteSearchUrlsAreUsableOutsideTheSite() {
        String url = model.absoluteSearch("java", null, 0);
        assertEquals(ABSOLUTE_SITE + "/testblog/search", path(url),
                "OpenSearch descriptors are consumed by browsers, so the URL has to "
                        + "be absolute.");
        assertEquals(Map.of("q", "java"), queryParams(url), "The term must survive.");
    }

    // ------------------------------------------------------------------ feeds

    @Test
    void entryFeedUrlsAreAbsoluteAndTypedByFormat() {
        // Feed URLs are a feed's identity: readers de-duplicate on them, so a
        // change here makes every subscriber re-download every old post.
        assertEquals(ABSOLUTE_SITE + "/testblog/feed/entries/atom",
                model.getFeed().getEntries().getAtom(),
                "$url.feed.entries.atom must be the absolute Atom URL.");
    }

    @Test
    void entryFeedUrlsCanBeNarrowedByCategoryTagsAndExcerpts() {
        assertEquals(Map.of("cat", "Tech+News"),
                queryParams(model.getFeed().getEntries().atom("Tech News", false)),
                "excerpts=false must be omitted rather than sent as false.");
        assertEquals(Map.of("tags", "foo+bar", "excerpts", "true"),
                queryParams(model.getFeed().getEntries().atomByTags(List.of("foo", "bar"), true)),
                "Tag feeds can also be excerpt-only.");
    }

    // ------------------------------------------------------------ editor links

    @Test
    void editorLinksAreRelativeAndIdentifyTheWeblog() {
        String add = model.getCreateEntry();
        assertEquals(SITE + "/pressroom-ui/authoring/entryAdd.rol", path(add),
                "The 'new entry' link points at the authoring UI.");
        assertEquals(Map.of("weblog", "testblog"), queryParams(add),
                "The editor needs to know which blog it is posting to.");

        String settings = model.getEditSettings();
        assertEquals(SITE + "/pressroom-ui/authoring/weblogConfig.rol", path(settings),
                "The settings link points at the weblog config screen.");
        assertEquals(Map.of("weblog", "testblog"), queryParams(settings),
                "The settings screen needs the weblog handle.");
    }

    @Test
    void editEntryResolvesTheAnchorToAnEntryId() throws Exception {
        // Themes render this next to each post for logged-in authors. The
        // anchor is all the template has; the edit screen needs the id.
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-42");
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        when(entries.getWeblogEntryByAnchor(weblog, "hello")).thenReturn(entry);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);

        String url = model.editEntry("hello");

        assertEquals(SITE + "/pressroom-ui/authoring/entryEdit.rol", path(url),
                "The edit link points at the entry editor.");
        assertEquals(Map.of("weblog", "testblog", "bean.id", "entry-42"),
                queryParams(url),
                "The editor is addressed by entry id, resolved from the anchor.");
    }

    @Test
    void editEntryYieldsNothingWhenTheAnchorMatchesNoEntry() throws Exception {
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        when(entries.getWeblogEntryByAnchor(weblog, "missing")).thenReturn(null);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);

        assertNull(model.editEntry("missing"),
                "An unknown anchor must yield null so the theme renders nothing, "
                        + "rather than a link into the editor for a missing entry.");
    }

    @Test
    void editEntrySurvivesALookupFailure() throws Exception {
        // A database hiccup while rendering must not take the whole page down;
        // losing one edit link is the correct degradation.
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        when(entries.getWeblogEntryByAnchor(weblog, "hello"))
                .thenThrow(new WebloggerException("database is down"));
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);

        assertNull(model.editEntry("hello"),
                "A failed lookup must be swallowed and yield null.");
    }

    // ----------------------------------------------------------------- handles

    /**
     * The weblog handle is the one part of a URL that is <em>not</em> encoded —
     * it is spliced straight into the path by
     * {@code MultiWeblogURLStrategy.getWeblogURL}. That is only safe because
     * handles are constrained to alphanumerics when a blog is created
     * ({@code JPAWeblogManagerImpl.addWeblog}) and again when a request is
     * parsed ({@code WeblogRequest}). If that constraint is ever relaxed, every
     * URL below becomes an injection point and this test is where to start.
     */
    @Test
    void theWeblogHandleAppearsVerbatimInEveryUrlFamily() throws Exception {
        Weblog other = new Weblog("myBlog2024", "testuser", "Other", "d",
                "e@example.com", "journal", "en_US", "UTC");
        URLModel otherModel = modelFor(other, null, new MultiWeblogURLStrategy());

        assertEquals(ABSOLUTE_SITE + "/myBlog2024/", otherModel.getHome(),
                "Handles keep their case and digits in the path.");
        assertEquals(ABSOLUTE_SITE + "/myBlog2024/entry/x", otherModel.entry("x"),
                "Permalinks embed the handle verbatim.");
        assertEquals(ABSOLUTE_SITE + "/myBlog2024/feed/entries/atom",
                otherModel.getFeed().getEntries().getAtom(),
                "Feed URLs embed the handle verbatim.");
    }
}
