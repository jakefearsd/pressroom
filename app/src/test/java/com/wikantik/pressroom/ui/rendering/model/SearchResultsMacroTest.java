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
package com.wikantik.pressroom.ui.rendering.model;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.business.search.SearchHit;
import com.wikantik.pressroom.business.search.SearchHits;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.wrapper.WeblogCategoryWrapper;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import com.wikantik.pressroom.pojos.wrapper.WeblogWrapper;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogSearchRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The search macros of {@code WEB-INF/velocity/weblog.vm} rendered on their
 * own: the real macros, the real {@link SearchResultsModel} over a mocked
 * index, the real message bundle and the real escaping helpers, in a bare
 * Velocity engine (as {@code ThemeColophonTest} renders the colophon). Fast
 * enough to pin exact reader-visible copy: where the "some of the words" line
 * sits, counts of one and of many, the 500-hit cap, "more from this blog",
 * the blog search box's promise, and that a blog name is escaped exactly once.
 */
class SearchResultsMacroTest {

    private static final Path WEBLOG_VM = Path.of("src/main/webapp/WEB-INF/velocity/weblog.vm");
    private static final String NASTY_NAME = "<b>\"quote\" & more";
    private static final String NASTY_ESCAPED = "&lt;b&gt;&quot;quote&quot; &amp; more";

    private IndexManager index;
    private Weblogger weblogger;
    private URLStrategy urls;
    private Weblog blog;
    private MockedStatic<WebloggerRuntimeConfig> config;
    private final Timestamp published = new Timestamp(System.currentTimeMillis() - 86_400_000L);

    @BeforeEach
    void setUp() throws Exception {
        index = mock(IndexManager.class);
        weblogger = mock(Weblogger.class);
        when(weblogger.getIndexManager()).thenReturn(index);
        WeblogManager weblogs = mock(WeblogManager.class);
        when(weblogs.getSiteSpace()).thenReturn(blogNamed("site", "The Site"));
        when(weblogger.getWeblogManager()).thenReturn(weblogs);
        urls = mock(URLStrategy.class);
        when(urls.getWeblogSearchURL(any(), any(), any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn("https://example.test/search?q=w&scope=blog");
        when(urls.getWeblogURL(any(), any(), anyBoolean()))
                .thenAnswer(inv -> "https://example.test/" + ((Weblog) inv.getArgument(0)).getHandle() + "/");
        blog = blogNamed("myblog", "Test Blog");
        config = org.mockito.Mockito.mockStatic(WebloggerRuntimeConfig.class);
    }

    @AfterEach
    void tearDown() {
        config.close();
    }

    // ---------------------------------------------------------------- B1: the fallback line

    @Test
    void theFallbackLineSitsInTheOtherBlogsSectionWhenOnlyItFellBack() throws Exception {
        String body = blogView(new SearchHits(List.of(hit("own", blog)), 1, false, Map.of(), Set.of()),
                new SearchHits(List.of(hit("other", other())), 1, true, Map.of(), Set.of()));

        assertEquals(1, count(body, "class=\"search-fallback\""), body);
        assertInOrder(body, "From Test Blog", "Title own", "search-group-other", "class=\"search-fallback\"",
                "Title other");
    }

    @Test
    void theFallbackLineSitsInThisBlogsSectionWhenOnlyItFellBack() throws Exception {
        String body = blogView(new SearchHits(List.of(hit("own", blog)), 1, true, Map.of(), Set.of()),
                new SearchHits(List.of(hit("other", other())), 1, false, Map.of(), Set.of()));

        assertEquals(1, count(body, "class=\"search-fallback\""), body);
        assertInOrder(body, "From Test Blog", "class=\"search-fallback\"", "Title own", "search-group-other",
                "Title other");
    }

    @Test
    void theSiteViewSaysItsFallbackUnderItsHeading() throws Exception {
        String body = siteView(new SearchHits(List.of(hit("a1", blog)), 1, true, Map.of("myblog", 1), Set.of()));

        assertEquals(1, count(body, "class=\"search-fallback\""), body);
        assertInOrder(body, "class=\"search-group\"", "class=\"search-fallback\"", "Title a1");
    }

    // ---------------------------------------------------------------- B3: one and many

    @Test
    void theBlogHeadingCountsOneArticleAndMany() throws Exception {
        String one = blogView(new SearchHits(List.of(hit("own", blog)), 1, false, Map.of(), Set.of()),
                SearchHits.NONE);
        assertTrue(one.contains("From Test Blog · 1 article</h2>"), one);

        String two = blogView(new SearchHits(List.of(hit("own", blog), hit("own2", blog)), 2, false, Map.of(),
                Set.of()), SearchHits.NONE);
        assertTrue(two.contains("From Test Blog · 2 articles</h2>"), two);
    }

    @Test
    void theSiteHeadingCountsOneAndMany() throws Exception {
        String one = siteView(new SearchHits(List.of(hit("a1", blog)), 1, false, Map.of("myblog", 1), Set.of()));
        assertTrue(one.contains("1 article across 1 blog</h2>"), one);

        Weblog other = other();
        String many = siteView(new SearchHits(List.of(hit("a1", blog), hit("a2", blog), hit("b1", other)), 3,
                false, Map.of("myblog", 2, "other", 1), Set.of()));
        assertTrue(many.contains("3 articles across 2 blogs</h2>"), many);
    }

    @Test
    void moreFromThisBlogCountsOneAndMany() throws Exception {
        String one = siteView(new SearchHits(List.of(hit("a1", blog)), 2, false, Map.of("myblog", 2), Set.of()));
        assertTrue(one.contains(">1 more post from this blog</a>"), one);

        String two = siteView(new SearchHits(List.of(hit("a1", blog)), 3, false, Map.of("myblog", 3), Set.of()));
        assertTrue(two.contains(">2 more posts from this blog</a>"), two);
    }

    /** Every bundle's counted search copy formats for one and for many, with no placeholder left over. */
    @Test
    void everyBundlesSearchCountsFormatForOneAndMany() {
        List<String> broken = new ArrayList<>();
        for (String suffix : List.of("", "_de", "_es", "_fr", "_ja", "_ko", "_ru", "_zh_CN")) {
            ResourceBundle bundle = bundle(suffix);
            for (int n : new int[] {1, 2, 500}) {
                check(broken, suffix, bundle, "search.fromBlog", "Blog", n);
                check(broken, suffix, bundle, "search.fromBlogCapped", "Blog", n);
                check(broken, suffix, bundle, "search.siteCount", n, n);
                check(broken, suffix, bundle, "search.siteCountCapped", n, n);
                check(broken, suffix, bundle, "search.moreFromBlog", n);
                check(broken, suffix, bundle, "search.showAll", n);
                check(broken, suffix, bundle, "search.showAllCapped", n);
                check(broken, suffix, bundle, "searchSummary.textCapped", 1, 10, n);
                check(broken, suffix, bundle, "macro.weblog.searchhitsCapped", "word", n);
            }
        }
        assertTrue(broken.isEmpty(), String.join("\n", broken));
    }

    private static void check(List<String> broken, String suffix, ResourceBundle bundle, String key, Object... args) {
        try {
            String out = MessageFormat.format(bundle.getString(key), args);
            if (out.contains("{") || out.contains("}")) {
                broken.add(suffix + " " + key + ": " + out);
            }
        } catch (RuntimeException e) {
            broken.add(suffix + " " + key + ": " + e);
        }
    }

    // ---------------------------------------------------------------- B4: the 500-hit cap

    @Test
    void aCappedBlogSectionSaysMoreThanAndShowsTheFirst() throws Exception {
        List<SearchHit> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eight.add(hit("own" + i, blog));
        }
        String body = blogView(new SearchHits(eight, 500, false, Map.of(), Set.of(), true), SearchHits.NONE);

        assertTrue(body.contains("From Test Blog · more than 500 articles</h2>"), body);
        assertTrue(body.contains(">Show the first 500 from this blog</a>"), body);
        assertFalse(body.contains("· 500 articles"), body);
        assertFalse(body.contains("Show all 500"), body);
    }

    @Test
    void aCappedSiteViewSaysMoreThanInItsHeadingTheHitLineAndThePagingLine() throws Exception {
        SearchResultsModel model = model("site", true, false,
                new SearchHits(List.of(hit("a1", blog), hit("b1", other())), 500, false,
                        Map.of("myblog", 300, "other", 200), Set.of(), true));

        String results = render(model, "#showSearchResults($model)");
        assertTrue(results.contains("More than 500 articles across 2 blogs</h2>"), results);

        String chrome = render(model, "#set($pager = $model.getWeblogEntriesPager())\n"
                + "#showWeblogSearchAgainForm($model.weblog)\n#showNextPrevSearchControl($pager)");
        assertTrue(chrome.contains("More than 500 entries match \"spain\""), chrome);
        assertTrue(chrome.contains("Showing 1–2 of the first 500"), chrome);
        assertFalse(chrome.contains(">500 entries match"), chrome);
    }

    @Test
    void anUncappedCountStaysExact() throws Exception {
        SearchResultsModel model = model("site", true, false,
                new SearchHits(List.of(hit("a1", blog)), 42, false, Map.of("myblog", 42), Set.of()));

        assertTrue(render(model, "#showSearchResults($model)").contains("42 articles across 1 blog</h2>"));
        String chrome = render(model, "#set($pager = $model.getWeblogEntriesPager())\n"
                + "#showWeblogSearchAgainForm($model.weblog)\n#showNextPrevSearchControl($pager)");
        assertTrue(chrome.contains("42 entries match \"spain\""), chrome);
        assertTrue(chrome.contains("Showing 1–1 of 42"), chrome);
    }

    // ---------------------------------------------------------------- B5: more from this blog, once

    @Test
    void moreFromThisBlogIsOnItsBlogsFirstHitOnly() throws Exception {
        Weblog other = other();
        String body = siteView(new SearchHits(List.of(hit("a1", blog), hit("b1", other), hit("a2", blog)), 44,
                false, Map.of("myblog", 42, "other", 2), Set.of()));

        assertEquals(2, count(body, "class=\"search-more\""), body);
        assertInOrder(body, "Title a1", ">41 more posts from this blog</a>", "Title b1",
                ">1 more post from this blog</a>", "Title a2");
        assertEquals(1, count(body, "41 more posts"), body);
    }

    // ---------------------------------------------------------------- B2: the blog search box

    @Test
    void theBlogSearchBoxPromisesTheRestOfTheSiteOnlyWhenItWillShowIt() throws Exception {
        String shown = renderBox(blog);
        assertTrue(shown.contains("placeholder=\"Search Test Blog and the rest of Harbour &amp; Hills\""), shown);

        blog.setShowMoreFromSite(Boolean.FALSE);
        String optedOut = renderBox(blog);
        assertTrue(optedOut.contains("placeholder=\"Search Test Blog\""), optedOut);
        assertTrue(optedOut.contains("aria-label=\"Search Test Blog\""), optedOut);
        assertFalse(optedOut.contains("the rest of"), optedOut);
    }

    // ---------------------------------------------------------------- D2: a blog name is escaped once
    // The box and other-blog tests are characterisation tests: written after
    // the code, they passed at once (the escaping was already right; the final
    // review asked for the boundary to be pinned). The heading test first
    // failed on "1 articles" (B3) only.

    @Test
    void aBlogNameIsEscapedOnceInTheSearchBox() throws Exception {
        rename(blog, NASTY_NAME);
        String box = renderBox(blog);

        assertTrue(box.contains("placeholder=\"Search " + NASTY_ESCAPED + " and the rest of Harbour &amp; Hills\""),
                box);
        assertTrue(box.contains("aria-label=\"Search " + NASTY_ESCAPED + " and the rest of"), box);
        assertEscapedOnce(box);
    }

    @Test
    void aBlogNameIsEscapedOnceInTheFromBlogHeading() throws Exception {
        rename(blog, NASTY_NAME);
        String body = blogView(new SearchHits(List.of(hit("own", blog)), 1, false, Map.of(), Set.of()),
                SearchHits.NONE);

        assertTrue(body.contains("<h2 class=\"search-group\">From " + NASTY_ESCAPED + " · 1 article</h2>"), body);
        assertEscapedOnce(body);
    }

    @Test
    void anOtherBlogsNameIsEscapedOnceInItsResult() throws Exception {
        Weblog other = other();
        rename(other, NASTY_NAME);
        String body = blogView(new SearchHits(List.of(hit("own", blog)), 1, false, Map.of(), Set.of()),
                new SearchHits(List.of(hit("other", other)), 1, false, Map.of(), Set.of()));

        assertTrue(body.contains("<a class=\"search-blog\" href=\"https://example.test/other/\">"
                + NASTY_ESCAPED + "</a>"), body);
        assertEscapedOnce(body);
    }

    private static void assertEscapedOnce(String html) {
        assertFalse(html.contains("<b>"), "raw markup from the name: " + html);
        assertFalse(html.contains("&amp;lt;") || html.contains("&amp;quot;") || html.contains("&amp;amp;"),
                "escaped twice: " + html);
    }

    // ---------------------------------------------------------------- helpers

    private static Weblog blogNamed(String handle, String name) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setName(name);
        weblog.setLocale("en_US");
        return weblog;
    }

    private static Weblog other() {
        return blogNamed("other", "Other Blog");
    }

    /**
     * Sets the name past {@code Weblog.setName}, which strips tags: the test
     * stands for any name that reaches a page with markup characters in it.
     */
    private static void rename(Weblog weblog, String name) throws Exception {
        Field field = Weblog.class.getDeclaredField("name");
        field.setAccessible(true);
        field.set(weblog, name);
    }

    private SearchHit hit(String id, Weblog from) {
        WeblogEntryWrapper entry = mock(WeblogEntryWrapper.class);
        when(entry.getTitle()).thenReturn("Title " + id);
        when(entry.getAnchor()).thenReturn(id);
        when(entry.getPermalink()).thenReturn("https://example.test/" + from.getHandle() + "/entry/" + id);
        when(entry.getPubTime()).thenReturn(published);
        when(entry.getWebsite()).thenReturn(WeblogWrapper.wrap(from, urls, weblogger));
        WeblogCategoryWrapper category = mock(WeblogCategoryWrapper.class);
        when(category.getName()).thenReturn("Travel");
        when(entry.getCategory()).thenReturn(category);
        return new SearchHit(entry, "", from != blog);
    }

    private String blogView(SearchHits own, SearchHits others) throws Exception {
        return render(model("myblog", false, false, own, others), "#showSearchResults($model)");
    }

    private String siteView(SearchHits found) throws Exception {
        return render(model("site", true, false, found), "#showSearchResults($model)");
    }

    private SearchResultsModel model(String handle, boolean siteWide, boolean blogOnly, SearchHits first,
            SearchHits... rest) throws WebloggerException {
        when(index.find(any(), any())).thenReturn(first, rest);
        config.when(() -> WebloggerRuntimeConfig.isSiteWideWeblog(handle)).thenReturn(siteWide);
        WeblogSearchRequest request = new WeblogSearchRequest();
        request.setWeblog(blog);
        request.setWeblogHandle(handle);
        request.setQuery("spain");
        request.setLocale("en_US");
        request.setScopeBlog(blogOnly);
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

    private String renderBox(Weblog weblog) throws Exception {
        VelocityContext context = context();
        context.put("weblog", WeblogWrapper.wrap(weblog, urls, weblogger));
        return evaluate(context, "#showBlogSearchBox($weblog)");
    }

    private String render(SearchResultsModel model, String call) throws Exception {
        VelocityContext context = context();
        context.put("model", model);
        return evaluate(context, call);
    }

    private VelocityContext context() throws Exception {
        VelocityContext context = new VelocityContext();
        context.put("text", new StubText(bundle("")));
        UtilitiesModel utils = new UtilitiesModel();
        WeblogPageRequest pageRequest = new WeblogPageRequest();
        pageRequest.setWeblog(blog);
        utils.init(Map.of("parsedRequest", pageRequest));
        context.put("utils", utils);
        context.put("url", new StubUrl());
        context.put("config", new StubConfig());
        return context;
    }

    private static String evaluate(VelocityContext context, String call) throws Exception {
        VelocityEngine engine = new VelocityEngine();
        engine.init();
        StringWriter out = new StringWriter();
        engine.evaluate(context, out, "search-macros",
                Files.readString(WEBLOG_VM, StandardCharsets.UTF_8) + "\n" + call + "\n");
        String html = out.toString().replaceAll("\\s+", " ").trim();
        assertFalse(html.contains("$srModel") || html.contains("$srHit") || html.contains("$srEntry")
                || html.contains("$model") || html.contains("$text"), "a Velocity reference leaked: " + html);
        return html;
    }

    private static ResourceBundle bundle(String suffix) {
        Locale locale = suffix.isEmpty() ? Locale.ROOT : Locale.forLanguageTag(suffix.substring(1).replace('_', '-'));
        return ResourceBundle.getBundle("ApplicationResources", locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    private static int count(String body, String needle) {
        return body.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static void assertInOrder(String body, String... needles) {
        int at = -1;
        for (String needle : needles) {
            int next = body.indexOf(needle, at + 1);
            assertTrue(next > at, "expected '" + needle + "' after position " + at + " in:\n" + body);
            at = next;
        }
    }

    /** $text as the renderer's MessageModel formats it. */
    public static final class StubText {
        private final ResourceBundle bundle;

        StubText(ResourceBundle bundle) {
            this.bundle = bundle;
        }

        public String get(String key) {
            return bundle.getString(key);
        }

        public String get(String key, List<Object> args) {
            return MessageFormat.format(bundle.getString(key), args.toArray());
        }
    }

    /** $url: the two helpers the search macros call. */
    public static final class StubUrl {
        public String entry(String anchor) {
            return "https://example.test/myblog/entry/" + anchor;
        }

        public String getSearch() {
            return "https://example.test/myblog/search";
        }
    }

    /** $config: the site's name, raw, as the runtime configuration holds it. */
    public static final class StubConfig {
        public String getSiteName() {
            return "Harbour & Hills";
        }
    }
}
