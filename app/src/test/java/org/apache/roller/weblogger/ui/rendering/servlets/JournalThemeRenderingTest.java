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
package org.apache.roller.weblogger.ui.rendering.servlets;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.text.StringEscapeUtils;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.themes.SharedTheme;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the journal theme's home page and reading view through the real
 * PageServlet/SearchServlet: a reading-first entry list with date marginalia
 * (the qj-date cell), serif titles, and the same head-chain contract every
 * other bundled theme carries.
 *
 * <p>Task 2 adds the dedicated permalink reading view (permalink.vm, the
 * qj-h1/qj-byline/qj-prose treatment per docs/design/journal/journal-permalink.html)
 * and the search results page (searchresults.vm) -- both now declared in
 * theme.xml instead of falling back to weblog.vm / the default template.
 */
class JournalThemeRenderingTest {

    private static final String HANDLE = "journalrenderblog";
    private static final String BASE = "/roller/" + HANDLE;

    /**
     * Journal self-hosts Plex Serif/Sans/Mono via webjar, unlike travel and
     * portfolio (system fonts only, no font-src). CSP_STANDARD's
     * default-src 'none' blocks any directive it does not name, so a webfont
     * theme needs its own font-src (CSP_JOURNAL in
     * AnalyticsInjectionRenderingTest pins the same string), added at the
     * same position. ThemeCspCoverageTest#everyFontAThemeAsksForIsShippedAndAllowedByItsPolicy
     * enforces this for every theme's CSS, not just this one.
     */
    private static final String CSP_JOURNAL =
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                    + "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                    + "img-src * data:; frame-src https://www.youtube-nocookie.com "
                    + "https://player.vimeo.com; font-src 'self'; base-uri 'self'; "
                    + "connect-src 'self'; form-action 'self'; frame-ancestors 'none'\">";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("journalrenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        switchTheme("journal");
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- helpers

    private String render(String pathInfo) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "page must render for " + pathInfo);
        return response.getContentAsString();
    }

    private void switchTheme(String themeName) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(themeName);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    private WeblogEntry entryWithSummary(String anchor, String summary) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, weblog, user);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setSearchDescription(summary);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return entry;
    }

    /** The shared head contract plus the no-Velocity-leak assertions. */
    private static void assertJournalHead(String body) {
        assertTrue(body.contains(CSP_JOURNAL),
                "the journal head must carry the CSP_STANDARD directives plus font-src "
                        + "'self' for its self-hosted webfonts:\n" + body);
        assertTrue(body.contains("<link rel=\"canonical\""),
                "#showSeoHead must contribute the canonical link:\n" + body);
        assertTrue(body.contains(".jgrid { display: flex;"),
                "#showGalleryGridStyles must be in the head:\n" + body);
        assertTrue(body.contains("/webjars/photoswipe/"),
                "#showGalleryAssets must ship the lightbox:\n" + body);
        assertTrue(body.contains(".video-embed"),
                "#showEmbedAssets must be in the head:\n" + body);
        assertTrue(body.contains("audience-hp"),
                "#showAudienceAssets must be in the head:\n" + body);
        assertTrue(body.contains("/webjars/leaflet/"),
                "#showMapAssets must ship Leaflet:\n" + body);
        assertTrue(body.contains("journal-custom.css"),
                "the head must link the theme stylesheet:\n" + body);
        assertTrue(body.contains("function rollerInitContent(") && body.contains(".markdown-alert-note {"),
                "the content-asset macro must be in the head: callouts, code blocks and "
                        + "heading anchors in .qj-prose depend on it:\n" + body);
        assertFalse(body.contains("#showContentAssets"), body);
        // a Velocity error would leak the raw directive or reference text
        assertFalse(body.contains("#showResponsiveImage"), body);
        assertFalse(body.contains("#showGalleryAssets"), body);
        assertFalse(body.contains("#showMapAssets"), body);
        assertFalse(body.contains("#showSeoHead"), body);
        assertFalse(body.contains("#showEmbedAssets"), body);
        assertFalse(body.contains("#showAudienceAssets"), body);
        // A broken $entry.xyz reference (e.g. a method the manager no longer
        // has) prints as this literal text in Velocity's lenient mode rather
        // than failing the render -- see _day.vm's now-fixed
        // "$entry.commentCount comments". This subsumes the old
        // $entry.featuredImage-only check.
        assertFalse(body.contains("$entry."), body);
        assertFalse(body.contains("$utils."), body);
    }

    // ------------------------------------------------------------ discovery

    @Test
    void theThemeManagerListsTheJournalTheme() throws Exception {
        SharedTheme journal = TestUtils.weblogger().getThemeManager()
                .getEnabledThemesList().stream()
                .filter(theme -> "journal".equals(theme.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "journal theme missing from the enabled themes list"));
        assertTrue(journal.getStylesheet() != null,
                "the theme must declare a stylesheet so per-weblog overrides work");
    }

    // ------------------------------------------------------------ front page

    @Test
    void theFrontPageListsEntriesWithDateMarginalia() throws Exception {
        entryWithSummary("field-notes-from-the-coast",
                "Three mornings of fog, one of clear light.");

        String body = render("/" + HANDLE + "/");

        assertJournalHead(body);
        assertTrue(body.contains("class=\"qj-entry\""),
                "each entry must render as a qj-entry row:\n" + body);
        assertTrue(body.contains("class=\"qj-date\""),
                "the date marginalia cell must be present:\n" + body);
        assertTrue(body.contains("class=\"qj-title\""),
                "the serif entry title must be present:\n" + body);
        assertTrue(body.contains("Three mornings of fog, one of clear light."),
                "the entry's search description must render as the summary:\n" + body);
        assertTrue(body.contains(BASE + "/entry/field-notes-from-the-coast"),
                "the title must link to the entry's permalink:\n" + body);
    }

    @Test
    void theNavPageLinksRenderInsideAnOpenList() throws Exception {
        String body = render("/" + HANDLE + "/");

        assertJournalHead(body);
        // #showPageLinks emits bare <li> items -- the theme must supply the
        // enclosing <ul> itself (PageNavRenderingTest pins this shape for the
        // other five bundled themes).
        assertTrue(body.contains("<nav class=\"qj-nav\">"),
                "the nav block must be present:\n" + body);
        assertFalse(body.contains("#showPageLinks"),
                "a Velocity error resolving the macro would leak the raw directive:\n" + body);
    }

    // -------------------------------------------------------------- pagination

    /**
     * {@code AbstractWeblogEntriesPager.getNextLink()} moves further BACK
     * through the archive (older entries); {@code getPrevLink()} moves
     * toward the present (newer entries) -- AbstractWeblogEntriesPager.java:
     * 146-178. The template's {@code rel="prev"}/{@code rel="next"}
     * attributes already track that correctly (prevLink carries rel="prev",
     * nextLink carries rel="next"); this pins that the human-visible LABEL
     * on each link matches its real direction rather than the inverted
     * labels the template shipped with.
     */
    @Test
    void pageTwoLabelsOlderOnTheRelNextLinkAndNewerOnTheRelPrevLink() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEntryDisplayCount(1);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);

        // Three entries at one-per-page: page 1 (0-indexed) then has both a
        // newer page behind it (prevLink) and an older page ahead of it
        // (nextLink), so both links render on the same response.
        TestUtils.setupWeblogEntry("newest-entry", weblog, user);
        TestUtils.setupWeblogEntry("middle-entry", weblog, user);
        TestUtils.setupWeblogEntry("oldest-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/" + HANDLE + "/");
        request.setParameter("page", "1");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();

        Matcher nextLink = Pattern.compile("rel=\"next\">(.*?)</a>", Pattern.DOTALL).matcher(body);
        assertTrue(nextLink.find(), "no rel=\"next\" link rendered on page 2:\n" + body);
        assertTrue(nextLink.group(1).contains("Older"),
                "the rel=\"next\" link moves to OLDER entries and must say so:\n" + nextLink.group(1));
        assertFalse(nextLink.group(1).contains("Newer"),
                "the rel=\"next\" link must not carry the newer-entries label:\n" + nextLink.group(1));

        Matcher prevLink = Pattern.compile("rel=\"prev\">(.*?)</a>", Pattern.DOTALL).matcher(body);
        assertTrue(prevLink.find(), "no rel=\"prev\" link rendered on page 2:\n" + body);
        assertTrue(prevLink.group(1).contains("Newer"),
                "the rel=\"prev\" link moves to NEWER entries and must say so:\n" + prevLink.group(1));
        assertFalse(prevLink.group(1).contains("Older"),
                "the rel=\"prev\" link must not carry the older-entries label:\n" + prevLink.group(1));
    }

    // ------------------------------------------------------------- permalink

    @Test
    void thePermalinkRendersTheReadingView() throws Exception {
        entryWithSummary("field-notes-from-the-coast",
                "Three mornings of fog, one of clear light.");

        String body = render("/" + HANDLE + "/entry/field-notes-from-the-coast");

        assertJournalHead(body);
        assertTrue(body.contains(CSP_JOURNAL),
                "the permalink template must carry the same CSP as every other "
                        + "journal template:\n" + body);
        // qj-crumb/qj-h1/qj-byline/qj-prose all come from _day.vm's
        // $model.permalink branch, which fires the same way regardless of
        // which theme.xml template action renders it -- they do NOT pin the
        // permalink.vm repoint itself. <main class="qj-main qj-main-entry">
        // does: only permalink.vm emits that combined class (weblog.vm's
        // <main> carries just "qj-main"), so if theme.xml's permalink action
        // ever regresses back to weblog.vm this assertion catches it even
        // though the _day.vm markup below would still render identically.
        assertTrue(body.contains("<main class=\"qj-main qj-main-entry\" id=\"main\">"),
                "the permalink must render through permalink.vm's own main "
                        + "shell, not weblog.vm's:\n" + body);
        assertTrue(body.contains("class=\"qj-crumb\""),
                "the weblog/category crumb must be present:\n" + body);
        assertTrue(body.contains("<h2 class=\"qj-h1\">"),
                "the entry title must render as the serif qj-h1:\n" + body);
        assertTrue(body.contains("class=\"qj-byline\""),
                "the byline (author + mono date) must be present:\n" + body);
        assertTrue(body.contains("class=\"qj-prose\""),
                "the entry content must render inside the qj-prose reading column:\n" + body);
        assertTrue(body.contains("field-notes-from-the-coast"),
                "the entry's own anchor/title must render:\n" + body);
        // TestUtils.setupWeblogEntry (called by entryWithSummary) always sets
        // the entry body text to "blah blah entry" -- assert that literal
        // text, not just the title/anchor/canonical, so this proves the
        // rendered body actually made it into the page (PortfolioThemeRenderingTest
        // pins the same literal for the same reason).
        assertTrue(body.contains("blah blah entry"),
                "the entry's rendered body content must appear inside qj-prose:\n" + body);
        assertFalse(body.contains("class=\"qj-entry\""),
                "the permalink must not fall back to the entry-list row markup:\n" + body);
        assertFalse(body.contains("qj-comments"),
                "the comments section must be gone from the permalink");
        assertFalse(body.contains("commentForm"),
                "no comment form may survive in rendered output");
    }

    /**
     * The scan in {@link ThemePolishTest#everyThemesPermalinkLinksTheEntrysTags}
     * proves all three themes call the macro; this proves the call actually
     * reaches a reader -- that the wrapper exposes the tags, the macro builds
     * a real archive url, and Velocity resolves both rather than printing the
     * reference as literal text (which is how this codebase fails).
     */
    @Test
    void thePermalinkLinksTheEntrysTags() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("tagged-entry", weblog, user);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.addTag("fieldwork");
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/entry/tagged-entry");

        assertTrue(body.contains("rel=\"tag\""),
                "the permalink must link the entry's tags:\n" + body);
        assertTrue(body.contains(">fieldwork</a>"),
                "the tag's own name must be the link text:\n" + body);
        assertTrue(body.contains("/tags/fieldwork"),
                "the tag link must point at the tag archive:\n" + body);
        assertFalse(body.contains("$entry.tags") || body.contains("#showEntryTags"),
                "no unresolved Velocity reference may survive:\n" + body);
    }

    // ---------------------------------------------------------------- search

    @Test
    void theSearchPageRendersThroughTheJournalTheme() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/search", "/" + HANDLE);
        request.setParameter("q", "zzznope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();

        // Not assertJournalHead here: search pages get no rel=canonical (see
        // #showSeoHead's own comment -- there is no one canonical URL for a
        // query), so the shared head assertion (which requires it) does not
        // apply to this template.
        assertTrue(body.contains(CSP_JOURNAL),
                "the search results head must carry the same CSP as every "
                        + "other journal template:\n" + body);
        assertTrue(body.contains(".jgrid { display: flex;"),
                "#showGalleryGridStyles must be in the head:\n" + body);
        assertTrue(body.contains("journal-custom.css"),
                "the head must link the theme stylesheet:\n" + body);
        assertTrue(body.contains("id=\"searchAgain\""),
                "the search-again form must render:\n" + body);
        assertTrue(body.contains("class=\"qj-search-head\""),
                "the search head wrapper must be present:\n" + body);
        assertFalse(body.contains("$utils."), body);
    }

    // -------------------------------------------------------------- _page

    private void savePage(String slug, String title, String content, WeblogPage.PubStatus status)
            throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent(content);
        page.setStatus(status);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    @Test
    void aPageRendersThroughTheJournalThemeInsteadOfTheFallback() throws Exception {
        savePage("about", "About This Journal", "Some prose about the journal. [contact]",
                WeblogPage.PubStatus.PUBLISHED);

        String body = render("/" + HANDLE + "/about");

        assertJournalHead(body);
        assertTrue(body.contains("class=\"qj-head\""),
                "the page must wear the journal header chrome:\n" + body);
        assertTrue(body.contains("<h2 class=\"qj-h1\">About This Journal</h2>"),
                "the page title must render as the serif qj-h1:\n" + body);
        assertTrue(body.contains("class=\"qj-prose\""),
                "the page content must render inside the qj-prose reading column:\n" + body);
        assertTrue(body.contains("audience-hp"),
                "showAudienceAssets must run in the head, whatever the page contains -- "
                        + "this alone does not prove the [contact] shortcode rendered:\n" + body);
        assertTrue(body.contains("contact-form-slot"),
                "the [contact] shortcode must have expanded to its placeholder div in the "
                        + "rendered body -- this is what actually proves the form renders:\n" + body);
        assertFalse(body.contains("<h1>About This Journal</h1>"),
                "the naked fallback template's unstyled h1 must not be what renders:\n" + body);
    }

    // ----------------------------------------------------- escaping (once)

    /**
     * Pins the fix end-to-end for the two independent single-escape
     * contracts a journal page's head/permalink carries: {@code
     * $model.weblog.name} comes back from {@code WeblogWrapper#getName}
     * already {@code escapeHtml4}'d, and an entry title is stored
     * pre-escaped by {@code EntryBean.copyTo} (mirrored here rather than
     * driving the controller, per {@code EntryBeanTest
     * #copyToEscapesTheTitleButNotTheBody}). Templates must emit both bare;
     * calling {@code $utils.escapeHTML} on either double-encodes. An '&'
     * in both fixtures makes a double-escape ({@code &amp;amp;}) impossible
     * to miss.
     */
    @Test
    void theWeblogNameAndEntryTitleEscapeExactlyOnce() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setName("Fog & Light Journal");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);

        WeblogEntry entry = TestUtils.setupWeblogEntry("tides-and-time", weblog, user);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managedEntry = mgr.getWeblogEntry(entry.getId());
        // Stored form of "Tides & Time" -- the transformation EntryBean.copyTo
        // applies at save time (see EntryBeanTest:212), not raw author input.
        managedEntry.setTitle(StringEscapeUtils.escapeHtml4("Tides & Time"));
        mgr.saveWeblogEntry(managedEntry);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/entry/tides-and-time");

        assertTrue(body.contains("Fog &amp; Light Journal"),
                "the weblog name must render escaped exactly once:\n" + body);
        assertTrue(body.contains("Tides &amp; Time"),
                "the entry title must render escaped exactly once:\n" + body);
        assertFalse(body.contains("&amp;amp;"),
                "no reference may double-encode a value that is already "
                        + "stored/wrapped escaped:\n" + body);
    }

    /**
     * The opposite direction to the test above, and the reason these two are
     * neighbours: {@code WeblogCategoryWrapper#getName} returns the pojo's
     * value untouched ({@code return this.pojo.getName();},
     * WeblogCategoryWrapper.java:63), so a category name reaches a template
     * <em>raw</em>. {@code weblog.vm}'s {@code #showWeblogCategoryLinksList}
     * -- which journal's weblog.vm, page.vm and permalink.vm all call --
     * emitted it bare, so a category named with an '&' rendered invalid
     * markup and one named with a '<' rendered live markup. {@code
     * _day.vm}'s category link already escaped; the nav list did not.
     */
    @Test
    void theCategoryNavListEscapesARawCategoryName() throws Exception {
        TestUtils.setupWeblogCategory(TestUtils.getManagedWebsite(weblog), "Tools & Toys");
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/");

        assertTrue(body.contains("Tools &amp; Toys"),
                "the category nav list must escape the raw wrapper value:\n" + body);
        assertFalse(body.contains(">Tools & Toys<"),
                "an unescaped category name is invalid markup at best:\n" + body);
        assertFalse(body.contains("&amp;amp;"),
                "escaping a raw value once must not become twice:\n" + body);
    }

    @Test
    void aDraftPageStill404sUnderTheJournalTheme() throws Exception {
        savePage("still-drafting", "Not Yet", "Nothing to see.", WeblogPage.PubStatus.DRAFT);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/" + HANDLE + "/still-drafting");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(404, response.getStatus(),
                "a draft page must not be reachable through the journal theme's _page "
                        + "template either -- draft status is checked before any template "
                        + "renders:\n" + response.getContentAsString());
    }

    // -------------------------------------------------- document shell (a11y)

    /**
     * The document shell every journal page shares: a BCP-47 {@code lang} (the
     * stored locale is {@code en_US}, which is not a tag any user agent
     * parses -- see {@code WeblogWrapper#getLanguageTag}), a skip link as the
     * first focusable node, the {@code #main} target it points at, and exactly
     * one {@code <h1>} -- the site name. Before the 2026-08-20 sweep the shell
     * had none of the four.
     */
    @Test
    void theDocumentShellDeclaresItsLanguageAndOffersASkipLink() throws Exception {
        entryWithSummary("shell-check", "A summary.");

        String body = render("/" + HANDLE + "/");

        assertTrue(body.contains("<html lang=\"en-US\">"),
                "the stored en_US locale must reach the page as the BCP-47 tag "
                        + "en-US:\n" + body);
        assertFalse(body.contains("lang=\"en_US\""),
                "the Java locale form is not a language tag:\n" + body);
        assertTrue(body.contains("href=\"#main\">Skip to content</a>"),
                "the skip link must render:\n" + body);
        assertTrue(body.contains("id=\"main\""),
                "the skip link's target id must exist on <main>:\n" + body);
        assertTrue(body.indexOf("href=\"#main\"") < body.indexOf("<header"),
                "the skip link must come before the header it exists to skip:\n" + body);
        assertTrue(body.contains("<h1 class=\"qj-site\">"),
                "the site name must be the page's h1:\n" + body);
        assertEquals(1, countOf(body, "<h1"),
                "a page must have exactly one h1 -- the site name:\n" + body);
    }

    /** Occurrences of {@code needle} in {@code haystack}. */
    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------ polish

    /**
     * The zero-entry branch has to be proven by a real render, not only by
     * the source scan in {@code ThemePolishTest}: Velocity is lenient here, so
     * an {@code #if($pagerDays.isEmpty())} whose reference failed to resolve
     * would quietly take the else branch and the sentence would never appear
     * on the one page it exists for. This weblog has no entries at all.
     */
    @Test
    void aWeblogWithNoEntriesSaysSoInsteadOfRenderingABlankColumn() throws Exception {
        String body = render("/" + HANDLE + "/");

        assertTrue(body.contains("class=\"qj-list-empty\""),
                "an empty entry list must say something:\n" + body);
        assertTrue(body.contains("Nothing has been published here yet."),
                "the home-page wording must be the home-page one:\n" + body);
        assertFalse(body.contains("$pagerDays"),
                "a reference Velocity could not resolve prints as literal text:\n" + body);
    }

    /**
     * The category view renders through weblog.vm, the same template as the
     * home page, and used to share its title. Also proves the empty-state
     * wording branches: this category has no entries either, and must say the
     * category-specific sentence rather than the home-page one.
     */
    @Test
    void theCategoryViewTitlesItselfAndSaysSoWhenEmpty() throws Exception {
        TestUtils.setupWeblogCategory(TestUtils.getManagedWebsite(weblog), "Field Notes");
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/category/Field Notes");

        assertTrue(body.contains("<title>Field Notes : Test Weblog</title>"),
                "the category view must name itself in the title:\n" + body);
        assertTrue(body.contains("Nothing has been filed under this category yet."),
                "the empty wording must be the category one:\n" + body);
    }

    /** A search results tab that says only "Search results" names nothing. */
    @Test
    void theSearchResultsTitleNamesTheQuery() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/search", "/" + HANDLE);
        request.setParameter("q", "lighthouse");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();

        assertTrue(body.contains("<title>Search: lighthouse : Test Weblog</title>"),
                "the query must reach the title, escaped exactly once "
                        + "(SearchResultsModel#getTerm has already escaped it):\n" + body);
        // The hit-count status line itself is NOT assertable from here:
        // #showNextPrevSearchControl only renders when $model.hits > 0, and no
        // unit fixture can produce a hit (indexing is asynchronous). It is
        // pinned by source scan instead -- see ThemePolishTest
        // #theSearchHitCountIsAStatusLineNotAHeading.
    }

    /** Dates carry the ISO value beside the human one. */
    @Test
    void entryDatesAreMachineReadable() throws Exception {
        entryWithSummary("dated-entry", "A summary.");

        String body = render("/" + HANDLE + "/");

        assertTrue(body.matches("(?s).*<time datetime=\"\\d{4}-\\d{2}-\\d{2}\">.*"),
                "the entry-list date must carry a machine-readable datetime:\n" + body);
        assertFalse(body.contains("$utils.formatIso8601Day"),
                "an unresolved reference prints as literal text:\n" + body);
    }

    /** The category crumb on a reading view is a link back to the category. */
    @Test
    void theReadingViewsCategoryCrumbLinksBackToTheCategory() throws Exception {
        entryWithSummary("crumb-entry", "A summary.");

        String body = render("/" + HANDLE + "/entry/crumb-entry");

        assertTrue(body.contains("<p class=\"qj-crumb\"><a href="),
                "the crumb must be a link, not dead text:\n" + body);
        assertTrue(body.contains("/category/"),
                "the crumb link must point at the entry's category:\n" + body);
    }
}
