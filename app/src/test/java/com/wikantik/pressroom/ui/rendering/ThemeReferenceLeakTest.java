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
package com.wikantik.pressroom.ui.rendering;

import com.wikantik.pressroom.ui.rendering.velocity.InvalidReferenceRecorder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.pojos.CustomTemplateRendition;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.TemplateRendition.TemplateLanguage;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders every bundled theme's templates through the real servlets and
 * fails on any unresolved Velocity reference or directive left in the body.
 *
 * <p><strong>This is a characterisation test</strong> -- it is expected to
 * pass on arrival and exists to prove that later changes did not break a
 * template. It is the mechanical guard the DI wave
 * ({@code docs/superpowers/specs/2026-08-22-retire-static-service-locator-design.md},
 * Decision 7) depends on: {@code WEB-INF/velocity.properties} sets no
 * {@code runtime.references.strict} and turns {@code
 * runtime.log.invalid.reference} off, so a template reference to a Java
 * member that no longer exists does not throw and does not log -- it prints
 * as the literal source text ({@code $entry.displayContent}) into the
 * rendered page, silently. That wave deletes a dozen entity getters that
 * templates reach through the wrappers; without this test nothing would
 * notice. Before it, only a handful of rendering tests checked for
 * {@code $entry.}/{@code $utils.}/{@code $model} leaks, each on one or two
 * pages.
 *
 * <p>Coverage: for each of {@code journal}, {@code portfolio}, {@code travel}
 * a weblog with one published entry carrying every bundled shortcode
 * ({@code [image]}, {@code [gallery]}, {@code [map]} with pins, {@code [faq]}),
 * a named category, a tag and a featured image, plus one published static
 * page carrying {@code [contact]}; rendered as the home page, the permalink,
 * the category page, the month and day archives, the static page, the search
 * results page, a bare 404 and the Velocity error page (forced by a custom
 * template with a parse error). There is no tag view to render: no bundled
 * theme ships a TAGSINDEX template and the main template is hidden, so the
 * tagged entry's tags are covered on every other page instead. Then, once: the
 * {@code frontpage} theme's home and directory pages (as the configured
 * front-page weblog, aggregated), the per-weblog Atom feed and the site-wide
 * Atom feed, and the JSON-LD/SEO head every page carries.
 *
 * <p>Proven to bite: with {@code WeblogEntryWrapper.getDisplayContent()}
 * temporarily renamed in the main source, this test failed naming
 * {@code $entry.displayContent} inside {@code qj-prose} / {@code
 * pf-entry-content} / {@code tg-entry-content} on the permalink page of all
 * three themes (the home/category/archive pages were untouched because
 * {@code _day.vm} calls the one-argument {@code $entry.displayContent($link)}
 * form, a different method -- precisely the kind of distinction a grep for the
 * reference name misses). The main source was then restored.
 *
 * <p>{@code <script>} and {@code <style>} blocks are stripped before
 * scanning: inline scripts may legitimately use {@code $x} identifiers, and
 * the JSON-LD head is a {@code <script>} whose keys would otherwise be noise.
 * Keep the fixture content free of a literal {@code $} rather than weakening
 * the scan.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ThemeReferenceLeakTest {

    /**
     * What the shipped templates leave unresolved on these renders, each with its reason.
     * A "KNOWN DEFECT" entry is a bug recorded here, not a rationale.
     */
    private static final Set<String> EXPECTED_REFERENCES = Set.of(
            "feeds.vm: $entry.findEntryAttribute()", // null when the entry has no media-cast attribute; the result is tested with #if($mc_url && ...)
            "feeds.vm: $entry.summary", // null when the entry has no summary; tested with $utils.isNotEmpty
            "feeds.vm: $mc_url", // null without a media-cast attribute; tested with #if in feeds.vm
            "feeds.vm: mc_length", // #set of a null media-cast attribute; tested with #if in feeds.vm
            "feeds.vm: mc_type", // #set of a null media-cast attribute; tested with #if in feeds.vm
            "feeds.vm: mc_url", // #set of a null media-cast attribute; tested with #if in feeds.vm
            "frontpage:Weblog|standard: $pager.nextLink", // null on the last page; tested with #if
            "frontpage:Weblog|standard: $pager.prevLink", // null on the first page; tested with #if
            "frontpage:_blogdirectory|standard: $chosenLetter", // directory letter filter (_blogdirectory.vm line 7), set only when ?letter= is given; tested with #if and ==
            "frontpage:_blogdirectory|standard: $model.getRequestParameter()", // null without a ?letter= parameter; tested by #if at _blogdirectory.vm line 6
            "frontpage:_blogdirectory|standard: $weblogsPager.nextLink", // null on the last directory page; tested with #if
            "frontpage:_blogdirectory|standard: $weblogsPager.prevLink", // null on the first directory page; tested with #if
            "frontpage:_header|standard: $fdMetaTitle", // null unless a static page has a meta title; tested with #if in frontpage _header.vm
            "frontpage:_header|standard: $fdPageTitle", // unset on the home view; tested with #elseif in frontpage _header.vm
            "frontpage:_header|standard: $fdReading", // unset except on a page whose title is the h1; tested with #if in frontpage _header.vm
            "journal:Weblog|standard: $model.tags", // null when the view is not a tag view; tested with #if/#elseif
            "journal:Weblog|standard: $model.weblogCategory", // null when the view is not a category view; tested with #if/#elseif
            "journal:Weblog|standard: $model.weblogDateLabel", // null when the view is not a date archive; tested with #elseif
            "journal:Weblog|standard: $pager.nextLink", // null on the last page; tested with #if
            "journal:Weblog|standard: $pager.prevLink", // null on the first page; tested with #if
            "journal:_page|standard: $model.page.metaTitle", // null when the page has no meta title; tested with $utils.isNotEmpty
            "journal:permalink|standard: $model.weblogEntry.pojo.metaTitle", // null when the entry has no meta title; tested with $utils.isNotEmpty
            "journal:permalink|standard: $newer", // unset on the newest entry; tested with #if($older || $newer)
            "journal:permalink|standard: $older", // unset on the oldest entry; tested with #if($older || $newer)
            "journal:permalink|standard: $pager.nextEntry", // null on the newest entry; assigned to $newer, which is tested with #if
            "journal:permalink|standard: $pager.prevEntry", // null on the oldest entry; assigned to $older, which is tested with #if
            "journal:permalink|standard: newer", // #set of a null value ($pager.nextEntry) on the newest entry
            "journal:permalink|standard: older", // #set of a null value ($pager.prevEntry) on the oldest entry
            "portfolio:Weblog|standard: $model.tags", // null when the view is not a tag view; tested with #if/#elseif
            "portfolio:Weblog|standard: $model.weblogCategory", // null when the view is not a category view; tested with #if/#elseif
            "portfolio:Weblog|standard: $model.weblogDateLabel", // null when the view is not a date archive; tested with #elseif
            "portfolio:Weblog|standard: $pager.nextLink", // null on the last page; tested with #if
            "portfolio:Weblog|standard: $pager.prevLink", // null on the first page; tested with #if
            "portfolio:_day|standard: $entry.featuredImage.altText", // null when the featured image has no alt text; tested with $utils.isNotEmpty
            "portfolio:_page|standard: $model.page.metaTitle", // null when the page has no meta title; tested with $utils.isNotEmpty
            "portfolio:permalink|standard: $model.weblogEntry.pojo.metaTitle", // null when the entry has no meta title; tested with $utils.isNotEmpty
            "portfolio:permalink|standard: $newer", // unset on the newest entry; tested with #if($older || $newer)
            "portfolio:permalink|standard: $older", // unset on the oldest entry; tested with #if($older || $newer)
            "portfolio:permalink|standard: $pager.nextEntry", // null on the newest entry; assigned to $newer, which is tested with #if
            "portfolio:permalink|standard: $pager.prevEntry", // null on the oldest entry; assigned to $older, which is tested with #if
            "portfolio:permalink|standard: newer", // #set of a null value ($pager.nextEntry) on the newest entry
            "portfolio:permalink|standard: older", // #set of a null value ($pager.prevEntry) on the oldest entry
            "site-entries-atom.vm|standard: $model.categoryName", // null when the feed is not a category feed; tested with #elseif
            "site-entries-atom.vm|standard: $model.tags", // null when the view is not a tag view; tested with #if/#elseif
            "travel:Weblog|standard: $model.tags", // null when the view is not a tag view; tested with #if/#elseif
            "travel:Weblog|standard: $model.weblogCategory", // null when the view is not a category view; tested with #if/#elseif
            "travel:Weblog|standard: $model.weblogDateLabel", // null when the view is not a date archive; tested with #elseif
            "travel:Weblog|standard: $pager.nextLink", // null on the last page; tested with #if
            "travel:Weblog|standard: $pager.prevLink", // null on the first page; tested with #if
            "travel:_day|standard: $entry.featuredImage.altText", // null when the featured image has no alt text; tested with $utils.isNotEmpty
            "travel:_page|standard: $model.page.metaTitle", // null when the page has no meta title; tested with $utils.isNotEmpty
            "travel:permalink|standard: $model.weblogEntry.pojo.metaTitle", // null when the entry has no meta title; tested with $utils.isNotEmpty
            "travel:permalink|standard: $newer", // unset on the newest entry; tested with #if($older || $newer)
            "travel:permalink|standard: $older", // unset on the oldest entry; tested with #if($older || $newer)
            "travel:permalink|standard: $pager.nextEntry", // null on the newest entry; assigned to $newer, which is tested with #if
            "travel:permalink|standard: $pager.prevEntry", // null on the oldest entry; assigned to $older, which is tested with #if
            "travel:permalink|standard: newer", // #set of a null value ($pager.nextEntry) on the newest entry
            "travel:permalink|standard: older", // #set of a null value ($pager.prevEntry) on the oldest entry
            "weblog-entries-atom.vm|standard: $model.categoryName", // null when the feed is not a category feed; tested with #elseif
            "weblog-entries-atom.vm|standard: $model.tags", // null when the view is not a tag view; tested with #if/#elseif
            "weblog.vm: $colophonFacts.copyrightText", // null unless the weblog sets copyright text; tested with $utils.isNotEmpty
            "weblog.vm: $colophonFacts.privacyUrl", // null unless a privacy URL is configured; tested with $utils.isNotEmpty
            "weblog.vm: $entry.pojo.metaTitle", // null when the entry has no meta title; tested with $utils.isNotEmpty in the showEntryTitle macro
            "weblog.vm: $image.objectPosition", // null when the image has no focal point; assigned to $responsiveObjectPosition, which is tested with #if
            "weblog.vm: $model.canonicalUrl", // null when no canonical URL applies; assigned to $seoCanonical, which is tested with #if
            "weblog.vm: $model.page", // null when the view is not a static page; tested with #if / negated in #set
            "weblog.vm: $model.pageOgImage", // null when the page has no OG image; assigned to $seoImage, which is tested with #if
            "weblog.vm: $model.tags", // null when the view is not a tag view; tested with #if/#elseif
            "weblog.vm: $model.term", // null outside a search; tested with $utils.isNotEmpty
            "weblog.vm: $model.weblogCategory", // null when the view is not a category view; tested with #if/#elseif
            "weblog.vm: $model.weblogDate", // null when the view is not a date archive; tested with #if / negated in #set
            "weblog.vm: $page.metaTitle", // null when the page has no meta title; tested with $utils.isNotEmpty in the showPageTitle macro
            "weblog.vm: $responsiveObjectPosition", // null when the image has no focal point; tested with #if
            "weblog.vm: $seoBusinessJson", // null when the weblog has no business profile; tested with #if
            "weblog.vm: $seoCanonical", // null when no canonical URL applies; tested with #if
            "weblog.vm: $seoEntry.ogImage", // null when the entry has no OG image; falls back to the featured image under #if(!$seoImage)
            "weblog.vm: $seoEntry.pojo.metaTitle", // null when the entry has no meta title; tested with $utils.isNotEmpty
            "weblog.vm: $seoImage", // null when nothing supplies an image; tested with #if
            "weblog.vm: $seoPage.metaTitle", // null when the page has no meta title; tested with $utils.isNotEmpty
            "weblog.vm: $seoPage.searchDescription", // null when the page has no search description; tested with $utils.isNotEmpty
            "weblog.vm: $seoPublisher", // null when the weblog has no business profile; tested with #if
            "weblog.vm: $seoTypedJson", // null unless the entry is a travel entry; tested with #if
            "weblog.vm: $site", // bound only on the aggregated front page; absence is the test in #if(!$site && ...) in showWeblogSearchAgainForm
            "weblog.vm: $utils.businessJsonLd()", // returns null when the weblog has no business profile; the result is tested with #if($seoBusinessJson)
            "weblog.vm: $utils.businessPublisherJson()", // returns null when the weblog has no business profile; the result is tested with #if($seoPublisher)
            "weblog.vm: $utils.travelJsonLd()", // returns null for an entry that is not a travel entry; the result is tested with #if($seoTypedJson)
            "weblog.vm: $weblog", // KNOWN DEFECT: showContentAssets takes no argument, so $weblog in its $config.getColophon($weblog) call is never bound; getColophon(null) falls back to the site colophon, which is why only the version (the same for every blog) is read and nothing visibly breaks
            "weblog.vm: $weblog.analyticsSiteId", // null unless analytics is configured; tested with $utils.isNotEmpty
            "weblog.vm: $weblog.newsletterListUuid", // null unless a newsletter list is configured; tested with #if
            "weblog.vm: responsiveObjectPosition", // #set of a null focal point; tested with #if
            "weblog.vm: seoBusinessJson", // #set of a null business JSON-LD; tested with #if
            "weblog.vm: seoCanonical", // #set of a null canonical URL; tested with #if
            "weblog.vm: seoImage", // #set of a null image; tested with #if
            "weblog.vm: seoPublisher", // #set of a null publisher JSON; tested with #if
            "weblog.vm: seoTypedJson" // #set of a null typed JSON-LD; tested with #if
    );

    @BeforeAll
    void clearInvalidReferenceRecorder() {
        InvalidReferenceRecorder.clear();
    }

    @AfterAll
    void onlyTheExpectedReferencesWereUnresolved() {
        StrictReferences.assertRecordedExactly("ThemeReferenceLeakTest", EXPECTED_REFERENCES);
    }

    private static final String PAGE_SERVLET = "/roller-ui/rendering/page";
    private static final String SEARCH_SERVLET = "/roller-ui/rendering/search";
    private static final String FEED_SERVLET = "/roller-ui/rendering/feed";

    private static final String FRONTPAGE_HANDLE_PROP = "site.frontpage.weblog.handle";
    private static final String FRONTPAGE_AGGREGATED_PROP = "site.frontpage.weblog.aggregated";

    private static final String CATEGORY = "Leak Notes";
    private static final String TAG = "leaktag";
    private static final String ENTRY_ANCHOR = "leak-entry";
    private static final String PAGE_SLUG = "about-leaks";
    private static final String BROKEN_TEMPLATE = "broken-template";

    /**
     * A Velocity reference: {@code $ref}, {@code $!ref}, {@code ${ref}},
     * with any dotted property/method chain. The {@code \{?} is deliberately
     * not closed -- a leaked {@code ${entry.title}} leaks with its brace.
     */
    private static final Pattern REFERENCE = Pattern.compile(
            "\\$!?\\{?[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    /** A Velocity directive that survived the merge as literal text. */
    private static final Pattern DIRECTIVE = Pattern.compile(
            "#(show[A-Za-z]+\\(|foreach\\b|if\\b|elseif\\b|else\\b|end\\b|set\\b|macro\\b|include\\(|parse\\()");

    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile(
            "<(script|style)\\b[^>]*>.*?</\\1\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final int CONTEXT_CHARS = 40;

    /**
     * The bundled content themes this class renders page by page. The
     * frontpage theme is rendered separately. RenderedTemplateCoverageTest
     * pins this list to the theme directories, so a new theme cannot ship
     * unrendered.
     */
    static final List<String> CONTENT_THEMES = List.of("journal", "portfolio", "travel");

    static List<String> contentThemes() {
        return CONTENT_THEMES;
    }

    /** Everything one themed weblog needs for the render matrix. */
    private record ThemeFixture(User user, Weblog weblog, String handle, String monthArchive,
            String dayArchive) {
    }

    private final Map<String, ThemeFixture> fixtures = new LinkedHashMap<>();
    private final Map<String, String> originalProperties = new LinkedHashMap<>();

    private User frontpageUser;
    private Weblog frontpageWeblog;
    private String frontpageHandle;

    @BeforeAll
    void setUpFixtures() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        // unique handles per run: FeedServlet has no cache bypass, and a
        // stale cached feed for a reused handle would hide a leak
        String suffix = Long.toString(System.nanoTime(), 36);
        for (String theme : CONTENT_THEMES) {
            fixtures.put(theme, themedWeblog(theme, suffix));
        }
        frontpageHandle = "leakfront" + suffix;
        frontpageUser = TestUtils.setupUser("leakfrontuser" + suffix);
        frontpageWeblog = TestUtils.setupWeblog(frontpageHandle, frontpageUser);
        switchTheme(frontpageWeblog, "frontpage");
        setProperty(FRONTPAGE_HANDLE_PROP, frontpageHandle);
        setProperty(FRONTPAGE_AGGREGATED_PROP, "true");
    }

    @AfterAll
    void tearDownFixtures() throws Exception {
        try {
            PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
            Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
            originalProperties.forEach((name, value) -> config.get(name).setValue(value));
            pmgr.saveProperties(config);
            TestUtils.weblogger().flush();
            TestUtils.endSession(true);
        } finally {
            for (ThemeFixture fixture : fixtures.values()) {
                TestUtils.teardownWeblog(fixture.weblog().getId());
                TestUtils.teardownUser(fixture.user().getUserName());
            }
            if (frontpageWeblog != null) {
                TestUtils.teardownWeblog(frontpageWeblog.getId());
            }
            if (frontpageUser != null) {
                TestUtils.teardownUser(frontpageUser.getUserName());
            }
            TestUtils.endSession(true);
        }
    }

    // ---------------------------------------------------------------- tests

    @ParameterizedTest(name = "{0}")
    @MethodSource("contentThemes")
    void everyPageOfTheThemeRendersWithoutAnUnresolvedReference(String theme) throws Exception {
        ThemeFixture fixture = fixtures.get(theme);
        String base = "/" + fixture.handle();
        List<String> failures = new ArrayList<>();

        scan(failures, theme + " home", page(base + "/", 200));
        scan(failures, theme + " permalink", page(base + "/entry/" + ENTRY_ANCHOR, 200));
        scan(failures, theme + " category", page(base + "/category/" + CATEGORY, 200));
        scan(failures, theme + " month archive", page(base + "/date/" + fixture.monthArchive(), 200));
        scan(failures, theme + " day archive", page(base + "/date/" + fixture.dayArchive(), 200));
        // No bundled theme ships a TAGSINDEX template; a tag archive renders
        // through the theme's default template (PageServlet.selectTemplate).
        scan(failures, theme + " tag archive", page(base + "/tags/" + TAG, 200));
        scan(failures, theme + " static page", page(base + "/" + PAGE_SLUG, 200));
        scan(failures, theme + " search results", search(base, "leak"));
        scan(failures, theme + " 404", page(base + "/entry/no-such-anchor", 404));
        // error-page.vm: a custom template that fails to parse makes
        // VelocityRenderer fall back to the error page (see its constructor)
        scan(failures, theme + " error page", page(base + "/page/" + BROKEN_TEMPLATE, -1));

        assertTrue(failures.isEmpty(), "unresolved Velocity references leaked into the "
                + theme + " theme's rendered pages:\n" + String.join("\n", failures));
    }

    @Test
    void theFrontpageThemeAndTheFeedsRenderWithoutAnUnresolvedReference() throws Exception {
        ThemeFixture journal = fixtures.get("journal");
        List<String> failures = new ArrayList<>();

        scan(failures, "frontpage home", page("/" + frontpageHandle + "/", 200));
        scan(failures, "frontpage directory", page("/" + frontpageHandle + "/page/directory", 200));
        scan(failures, "weblog atom feed", feed("/" + journal.handle() + "/entries/atom"));
        scan(failures, "site atom feed", feed("/" + frontpageHandle + "/entries/atom"));

        assertTrue(failures.isEmpty(), "unresolved Velocity references leaked into the "
                + "frontpage theme or a feed:\n" + String.join("\n", failures));
    }

    // -------------------------------------------------------------- scanning

    private static void scan(List<String> failures, String label, String body) {
        String visible = SCRIPT_OR_STYLE.matcher(body).replaceAll("");
        collect(failures, label, visible, REFERENCE);
        collect(failures, label, visible, DIRECTIVE);
    }

    private static void collect(List<String> failures, String label, String body, Pattern pattern) {
        Matcher matcher = pattern.matcher(body);
        while (matcher.find()) {
            int from = Math.max(0, matcher.start() - CONTEXT_CHARS);
            int to = Math.min(body.length(), matcher.end() + CONTEXT_CHARS);
            String context = body.substring(from, to).replaceAll("\\s+", " ");
            failures.add(label + ": `" + matcher.group() + "` in ..." + context + "...");
        }
    }

    // ------------------------------------------------------------- rendering

    private static String page(String pathInfo, int expectedStatus) throws Exception {
        RenderingTestSupport.clearRenderCaches();
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(PAGE_SERVLET, pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        if (expectedStatus >= 0) {
            assertEquals(expectedStatus, response.getStatus(),
                    "unexpected status for " + pathInfo + ":\n" + response.getContentAsString());
        }
        return response.getContentAsString();
    }

    private static String search(String base, String query) throws Exception {
        RenderingTestSupport.clearRenderCaches();
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(SEARCH_SERVLET, base);
        request.setParameter("q", query);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);
        assertEquals(200, response.getStatus(), "search must render:\n"
                + response.getContentAsString());
        return response.getContentAsString();
    }

    private static String feed(String pathInfo) throws Exception {
        RenderingTestSupport.clearRenderCaches();
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(FEED_SERVLET, pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.feedServlet(), request);
        assertEquals(200, response.getStatus(), "feed must render for " + pathInfo + ":\n"
                + response.getContentAsString());
        return response.getContentAsString();
    }

    // -------------------------------------------------------------- fixtures

    private ThemeFixture themedWeblog(String theme, String suffix) throws Exception {
        String handle = "leak" + theme + suffix;
        User user = TestUtils.setupUser("leak" + theme + "user" + suffix);
        Weblog weblog = TestUtils.setupWeblog(handle, user);
        switchTheme(weblog, theme);

        WeblogCategory category = TestUtils.setupWeblogCategory(
                TestUtils.getManagedWebsite(weblog), CATEGORY);
        TestUtils.endSession(true);

        // one image in the default directory: the [image] target, the
        // [gallery dir="default"] content, and the featured image
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "leak-image-" + theme);
        TestUtils.endSession(true);

        WeblogEntry entry = TestUtils.setupWeblogEntry(ENTRY_ANCHOR,
                TestUtils.getManagedWeblogCategory(category), weblog, user);
        WeblogEntryManager emgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = emgr.getWeblogEntry(entry.getId());
        managed.setText("Opening prose with **markdown** and a [link](https://example.test/).\n\n"
                + "[image id=\"" + image.getId() + "\" caption=\"A hawk\" alt=\"A hawk in flight\"]\n\n"
                + "[gallery dir=\"default\"]\n\n"
                + "[map zoom=\"7\"]\n"
                + "[pin lat=\"64.1466\" lng=\"-21.9426\" label=\"Reykjavik\"]\n"
                + "[pin lat=\"63.4053\" lng=\"-19.0755\" label=\"Vik\"]\n"
                + "[/map]\n\n"
                + "[faq]\n[q]Best month?[/q]\n[a]June, by <b>far</b>.[/a]\n[/faq]\n\n"
                + "Closing prose.");
        managed.setSearchDescription("Three mornings of fog, one of clear light.");
        managed.setFeaturedImageId(image.getId());
        managed.addTag(TAG);
        emgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);

        savePage(weblog, PAGE_SLUG, "About These Leaks",
                "Some prose about the weblog. [contact]");
        saveBrokenCustomTemplate(weblog);

        // the archive paths are the entry's pubtime in the weblog's zone
        WeblogEntry persisted = emgr.getWeblogEntry(entry.getId());
        Date pubTime = persisted.getPubTime();
        Weblog persistedWeblog = TestUtils.getManagedWebsite(weblog);
        String month = format("yyyyMM", pubTime, persistedWeblog);
        String day = format("yyyyMMdd", pubTime, persistedWeblog);
        TestUtils.endSession(true);

        return new ThemeFixture(user, weblog, handle, month, day);
    }

    private static String format(String pattern, Date date, Weblog weblog) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern);
        formatter.setTimeZone(weblog.getTimeZoneInstance());
        return formatter.format(date);
    }

    private static void switchTheme(Weblog weblog, String themeName) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(themeName);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    private static void savePage(Weblog weblog, String slug, String title, String content)
            throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent(content);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    /** A custom template that cannot parse, so the error page renders. */
    private static void saveBrokenCustomTemplate(Weblog weblog) throws Exception {
        WeblogManager wmgr = TestUtils.weblogger().getWeblogManager();
        WeblogTemplate template = new WeblogTemplate();
        template.setWeblog(TestUtils.getManagedWebsite(weblog));
        template.setAction(ComponentType.CUSTOM);
        template.setName(BROKEN_TEMPLATE);
        template.setLink(BROKEN_TEMPLATE);
        template.setDescription(BROKEN_TEMPLATE);
        template.setHidden(false);
        template.setNavbar(false);
        template.setLastModified(new Date());
        wmgr.saveTemplate(template);
        CustomTemplateRendition rendition =
                new CustomTemplateRendition(template, RenditionType.STANDARD);
        rendition.setTemplate("<p>before</p>\n#if($model.weblog.name\n<p>never closed</p>");
        rendition.setTemplateLanguage(TemplateLanguage.VELOCITY);
        wmgr.saveTemplateRendition(rendition);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    /**
     * Mirrors FrontpageRenderingTest: read the live map through
     * PropertiesManager, mutate, save, flush, end the session so the next
     * request reads it fresh; the original value is captured on first touch
     * so tearDown can restore it.
     */
    private void setProperty(String name, String value) throws Exception {
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        originalProperties.computeIfAbsent(name, key -> config.get(key).getValue());
        config.get(name).setValue(value);
        pmgr.saveProperties(config);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }
}
