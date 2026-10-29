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
package com.wikantik.pressroom.ui.rendering.servlets;

import java.util.LinkedHashMap;
import java.util.Map;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the restyled frontpage ("front door") aggregator theme through the
 * real PageServlet. This theme renders only for the one weblog named by the
 * runtime property {@code site.frontpage.weblog.handle}, with {@code
 * site.frontpage.weblog.aggregated} also on (see
 * {@code WebloggerRuntimeConfig#isSiteWideWeblog}) -- that pair is what adds
 * {@code $site} (SiteModel) to the Velocity context (PageServlet, {@code
 * rendering.siteModels}), which is why ThemeMatrixIT excludes this theme:
 * every other bundled theme renders for whichever weblog the fixture hands
 * it, and this one only renders correctly for the configured site-wide
 * weblog.
 *
 * <p>The restyle keeps every {@code $site}/{@code $model} call the previous
 * markup made (site-wide entries pager, pinned entries, new-weblogs list,
 * weblog-by-handle lookup) -- only the chrome/classes/CSS changed, to the
 * fd-* vocabulary in docs/design/journal/frontpage-front-door.html.
 */
class FrontpageRenderingTest {

    private static final String SITE_HANDLE = "frontpagesiteblog";
    private static final String CONTRIBUTOR_HANDLE = "frontpagecontributorblog";
    private static final String BASE = "/roller/" + SITE_HANDLE;

    private static final String FRONTPAGE_HANDLE_PROP = "site.frontpage.weblog.handle";
    private static final String FRONTPAGE_AGGREGATED_PROP = "site.frontpage.weblog.aggregated";
    private static final String SITE_NAME_PROP = "site.name";
    private static final String SITE_DESCRIPTION_PROP = "site.description";

    /**
     * The fd-hero renders $config.siteName/$config.siteDescription -- the
     * whole-installation Admin Settings fields, not the front weblog's own
     * name/tagline -- exactly as the pre-restyle template did (ConfigModel
     * reads the "site.name"/"site.description" runtime properties). Unlike
     * Weblog.setName/setTagline (which strip markup with Utilities.removeHTML
     * at the setter, before any template ever sees the value), these are
     * plain strings with no sanitizer upstream, so they are the one field in
     * this theme where the template's own $utils.escapeHTML call is the ONLY
     * thing standing between an admin-typed "<script>" and the rendered
     * page -- the deliberately dangerous value in the escaping test below.
     */
    private static final String DANGEROUS_SITE_NAME = "Coastal Guides <script>alert(1)</script>";
    private static final String DANGEROUS_SITE_DESCRIPTION = "Notes & guides for the coast";

    private static final String ANALYTICS_SITE_ID = "11111111-2222-3333-4444-555555555555";

    /**
     * What {@code #showAnalyticsTrackingCode} builds when analyticsSiteId is
     * set -- same shape {@code AnalyticsInjectionRenderingTest} pins for the
     * other three bundled themes' home pages. Frontpage's head never called
     * the macro at all (pre-existing gap: front-door traffic was invisible to
     * Umami), so this class is the only coverage for it.
     */
    private static final String EXPECTED_ANALYTICS_SCRIPT_TAG =
            "<script defer src=\"/analytics/script.js\" "
                    + "data-website-id=\"" + ANALYTICS_SITE_ID + "\" "
                    + "data-host-url=\"/analytics\"></script>";

    /**
     * Frontpage self-hosts Plex Serif/Sans/Mono via webjar (fd-site/fd-post
     * .t/fd-blog .n need the serif, fd-post .d/fd-blog .h need the mono),
     * unlike travel/portfolio (system fonts only, no font-src). The original
     * policy named no font-src at all -- default-src 'none' refuses
     * anything a policy does not name, so the restyle inserts font-src
     * 'self' at the same relative position journal's CSP does among its own
     * directives (ThemeCspCoverageTest
     * #everyFontAThemeAsksForIsShippedAndAllowedByItsPolicy enforces the
     * pairing for every theme, not just this one). The frame-src arrived
     * with the theme's own _page: a static page on the front-door weblog can
     * carry a [video], which the shared fallback page could play, so the
     * shared head now calls #showEmbedAssets and names the two providers
     * exactly as journal does.
     */
    private static final String CSP_FRONTPAGE =
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                    + "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                    + "img-src * data:; frame-src https://www.youtube-nocookie.com "
                    + "https://player.vimeo.com; font-src 'self'; base-uri 'self'; connect-src 'self'; "
                    + "form-action 'self'; frame-ancestors 'none'\">";

    private User siteUser;
    private Weblog siteWeblog;
    private User contributorUser;
    private Weblog contributorWeblog;

    private final Map<String, String> originalProperties = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();

        siteUser = TestUtils.setupUser("frontpagesiteuser");
        siteWeblog = TestUtils.setupWeblog(SITE_HANDLE, siteUser);
        Weblog managedSite = TestUtils.getManagedWebsite(siteWeblog);
        managedSite.setEditorTheme("frontpage");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managedSite);
        TestUtils.endSession(true);

        contributorUser = TestUtils.setupUser("frontpagecontribuser");
        contributorWeblog = TestUtils.setupWeblog(CONTRIBUTOR_HANDLE, contributorUser);
        Weblog managedContributor = TestUtils.getManagedWebsite(contributorWeblog);
        // Weblog.setName/setTagline already strip markup (Utilities.removeHTML)
        // before this ever reaches a template, so a bare "&" -- untouched by
        // that stripper -- is what proves THIS template's own
        // $utils.escapeHTML($blog.name)/$utils.escapeHTML($blog.tagline)
        // calls are doing real work, in the "from <weblog>" credit and the
        // fd-blog directory card.
        managedContributor.setName("Harbor & Cove Notes");
        managedContributor.setTagline("Guides & notes for the coast");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managedContributor);
        TestUtils.endSession(true);

        setProperty(FRONTPAGE_HANDLE_PROP, SITE_HANDLE);
        setProperty(FRONTPAGE_AGGREGATED_PROP, "true");
    }

    @AfterEach
    void tearDown() throws Exception {
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        originalProperties.forEach((name, value) -> config.get(name).setValue(value));
        pmgr.saveProperties(config);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        TestUtils.teardownWeblog(contributorWeblog.getId());
        TestUtils.teardownUser(contributorUser.getUserName());
        TestUtils.teardownWeblog(siteWeblog.getId());
        TestUtils.teardownUser(siteUser.getUserName());
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- helpers

    private String render(String pathInfo) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "page must render for " + pathInfo);
        return response.getContentAsString();
    }

    private WeblogEntry contributorEntry(String anchor) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, contributorWeblog, contributorUser);
        TestUtils.endSession(true);
        return entry;
    }

    /**
     * Pins an entry (site.getPinnedWeblogEntries) and backdates its pubTime
     * so it is deliberately the OLDEST entry across the site -- outside the
     * ordinary pager's top-25 window once enough newer filler entries exist.
     * That is what makes the assertion below prove the dedicated pinned
     * block rendered, rather than the entry merely showing up because it was
     * recent.
     */
    private WeblogEntry pinnedContributorEntry(String anchor) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, contributorWeblog, contributorUser);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setPinnedToMain(true);
        managed.setPubTime(new java.sql.Timestamp(
                System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(1)));
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return entry;
    }

    /**
     * Mirrors AnalyticsInjectionRenderingTest's pattern for driving a
     * runtime property from a real (non-mocked) rendering test: read the
     * live map through PropertiesManager, mutate, save, flush, end the
     * session so the next request reads it fresh. The property's original
     * value is captured the first time it is touched, so tearDown can put
     * every property this test changed back exactly as it found it.
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

    private static void assertFrontpageHead(String body) {
        assertTrue(body.contains(CSP_FRONTPAGE),
                "the frontpage head must carry the CSP_STANDARD directives plus font-src "
                        + "'self' for its self-hosted webfonts:\n" + body);
        assertTrue(body.contains("<link rel=\"canonical\""),
                "#showSeoHead must contribute the canonical link:\n" + body);
        assertTrue(body.contains("frontpage-custom.css"),
                "the head must link the theme stylesheet:\n" + body);
        assertTrue(body.contains("function rollerInitContent(") && body.contains(".markdown-alert-note {"),
                "the content-asset macro must be in the head, like every other theme's:\n" + body);
        assertFalse(body.contains("#showContentAssets"), body);
        // a Velocity error would leak the raw directive or reference text
        assertFalse(body.contains("#showSeoHead"), body);
        assertFalse(body.contains("#showGalleryAssets"), body);
        assertFalse(body.contains("#showMapAssets"), body);
        assertFalse(body.contains("$config.siteName"), body);
        assertFalse(body.contains("$utils."), body);
    }

    // ------------------------------------------------------------ front door

    @Test
    void theFrontDoorShowsTheHeroLatestPostsAndDirectory() throws Exception {
        contributorEntry("field-notes-from-the-coast");

        String body = render("/" + SITE_HANDLE + "/");

        assertFrontpageHead(body);

        assertTrue(body.contains("class=\"fd-hero\""),
                "the site hero (name + tagline) must be present:\n" + body);
        assertTrue(body.contains("class=\"fd-site\""), body);
        assertTrue(body.contains("class=\"fd-tag\""), body);

        assertTrue(body.contains("class=\"fd-post\""),
                "each site-wide entry must render as an fd-post row:\n" + body);
        assertTrue(body.contains("field-notes-from-the-coast"),
                "the contributed entry's anchor/title must render:\n" + body);
        assertTrue(body.contains("from <a"),
                "the fd-post row must carry a \"from <weblog>\" credit:\n" + body);

        assertTrue(body.contains("class=\"fd-dir\""),
                "the weblog directory grid must be present:\n" + body);
        assertTrue(body.contains("class=\"fd-blog\""),
                "each directory entry must render as an fd-blog card:\n" + body);
        assertTrue(body.contains("class=\"live\""),
                "each fd-blog card must carry the live-status dot:\n" + body);
        assertTrue(body.contains(CONTRIBUTOR_HANDLE),
                "the contributing weblog's handle must appear in the directory:\n" + body);
    }

    /**
     * W2: feeds are Atom only. This anchor is a visible link on the site's
     * front door (not just head autodiscovery), so it is the one place a
     * leftover {@code $url.feed.entries.rss} reference -- which would print
     * as literal text under this fork's non-strict Velocity settings, not
     * throw -- would be immediately visible to a reader.
     */
    @Test
    void theFrontDoorAdvertisesTheCombinedAtomFeedNotRss() throws Exception {
        String body = render("/" + SITE_HANDLE + "/");

        assertTrue(body.contains("Subscribe to the combined Atom feed"),
                "the front door must advertise the combined Atom feed:\n" + body);
        assertFalse(body.contains("$url.feed.entries.rss"),
                "a stale RSS reference would render as literal text, not fail the "
                        + "build:\n" + body);
        assertFalse(body.toLowerCase().contains("rss"),
                "no RSS wording should remain on the front door:\n" + body);
    }

    @Test
    void theHomePageLinksToTheWeblogDirectoryPage() throws Exception {
        String body = render("/" + SITE_HANDLE + "/");

        assertFrontpageHead(body);
        assertTrue(body.contains("class=\"fd-tabs\""),
                "the tab nav must be present:\n" + body);
        assertTrue(body.contains(BASE + "/page/directory"),
                "the directory tab must link to the directory page:\n" + body);
    }

    @Test
    void theWeblogDirectoryPageRendersTheFullDirectory() throws Exception {
        String body = render("/" + SITE_HANDLE + "/page/directory");

        assertFrontpageHead(body);
        assertTrue(body.contains("class=\"fd-dir\""),
                "the full weblog directory grid must be present:\n" + body);
        assertTrue(body.contains("class=\"fd-blog\""), body);
        assertTrue(body.contains(CONTRIBUTOR_HANDLE),
                "the contributing weblog must be listed in the full directory:\n" + body);
        assertTrue(body.contains("class=\"fd-letters\""),
                "the A-Z letter index must be present:\n" + body);
        // directory.vm #sets $fdPageTitle before including _header, which is
        // the only thing that distinguishes this tab's <title> from the front
        // door's -- they were identical, and #includeTemplate is a #parse, so
        // if context sharing ever stops working this is where it shows.
        assertTrue(body.contains("<title>Directory \u2014 "),
                "the directory page must name itself in the title:\n" + body);
    }

    /**
     * weblog.vm gated the pinned-entries block on {@code
     * #if(!$pager.nextLink)} -- true only on the LAST page of the site-wide
     * pager -- instead of {@code #if(!$pager.prevLink)}, true only on the
     * FIRST page. Pinned entries belong at the top of the front page, so on
     * any site with more than one page of entries (more than 25, the
     * template's hardcoded page size) they never rendered at all. This test
     * forces a second page (54 filler entries, all newer than the pinned
     * one -- comfortably more than the 50 entries spanning pages one and
     * two combined, so the pinned entry sits beyond BOTH pages' ordinary
     * item windows, not just page one's) so the pinned entry can only
     * appear in either response through the dedicated pinned block, never
     * through ordinary pagination.
     *
     * <p>The negative half matters as much as the positive one: the bug was
     * directional (which page the gate fires on), so a test that only
     * asserts presence on page one would pass just as happily against a
     * gate of {@code #if(true)} (pinned entries on every page) as against
     * the correct {@code #if(!$pager.prevLink)}. Asserting ABSENCE on page
     * two is what actually pins the direction.
     */
    @Test
    void theFirstPageShowsPinnedEntriesEvenWhenTheSiteHasMultiplePages() throws Exception {
        pinnedContributorEntry("pinned-coastal-showcase");
        for (int i = 0; i < 54; i++) {
            contributorEntry("filler-entry-" + i);
        }

        String firstPage = render("/" + SITE_HANDLE + "/");

        assertTrue(firstPage.contains("pinned-coastal-showcase"),
                "the pinned entry -- backdated outside the top-25 window -- must still "
                        + "render on the first page via the pinned-entries block:\n" + firstPage);

        MockHttpServletRequest secondPageRequest = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", "/" + SITE_HANDLE + "/");
        secondPageRequest.setParameter("page", "1");
        MockHttpServletResponse secondPageResponse = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), secondPageRequest);
        assertEquals(200, secondPageResponse.getStatus());
        String secondPage = secondPageResponse.getContentAsString();

        assertFalse(secondPage.contains("pinned-coastal-showcase"),
                "the pinned-entries block only belongs on the FIRST page -- it must not "
                        + "repeat (or newly appear) on page two:\n" + secondPage);
    }

    // -------------------------------------------------------------- escaping

    @Test
    void theSiteNameAndDescriptionAreEscapedNotLiveHtml() throws Exception {
        // $config.siteName/$config.siteDescription have no upstream
        // sanitizer (unlike Weblog.name/tagline -- see the class comment),
        // so this is the field where the restyled template's own
        // $utils.escapeHTML($config.siteName) call is the only thing
        // standing between an admin-typed <script> and the rendered page.
        setProperty(SITE_NAME_PROP, DANGEROUS_SITE_NAME);
        setProperty(SITE_DESCRIPTION_PROP, DANGEROUS_SITE_DESCRIPTION);

        String body = render("/" + SITE_HANDLE + "/");

        assertFalse(body.contains("<script>alert(1)</script>"),
                "a site name carrying a live <script> tag must never reach the page "
                        + "unescaped:\n" + body);
        assertTrue(body.contains("Coastal Guides &lt;script&gt;alert(1)&lt;/script&gt;"),
                "the site name must render HTML-escaped inside the fd-hero:\n" + body);
        assertTrue(body.contains("Notes &amp; guides for the coast"),
                "the site description must render HTML-escaped inside the fd-hero:\n" + body);
    }

    @Test
    void contributingWeblogNamesAndTaglinesAreEscaped() throws Exception {
        contributorEntry("harbor-cottage-guide");

        String body = render("/" + SITE_HANDLE + "/");

        // Weblog.setName/setTagline already strip markup via
        // Utilities.removeHTML before a template ever sees the value, so a
        // bare "&" is the one character that survives to be observable here.
        // The name assertion below exercises WeblogWrapper#getName's own
        // built-in StringEscapeUtils.escapeHtml4 -- the template renders
        // $blog.name/$entry.website.name bare, deliberately NOT wrapped in
        // another $utils.escapeHTML (that would double-encode, see weblog.vm's
        // fd-dir comment). $blog.tagline is NOT pre-escaped by its wrapper
        // (WeblogWrapper#getTagline only sanitizes/strips markup), so the
        // tagline assertion is the one that actually proves THIS template's
        // own $utils.escapeHTML($blog.tagline) call is doing real work.
        assertFalse(body.contains("Harbor & Cove Notes"),
                "the fd-post credit / fd-blog card must not carry a raw ampersand:\n" + body);
        assertTrue(body.contains("Harbor &amp; Cove Notes"),
                "the contributing weblog's name must render HTML-escaped "
                        + "(WeblogWrapper#getName's own escaping):\n" + body);
        assertTrue(body.contains("Guides &amp; notes for the coast"),
                "the contributing weblog's tagline must render HTML-escaped by this "
                        + "template's own $utils.escapeHTML call:\n" + body);
    }

    // ------------------------------------------------------------------- CSP

    @Test
    void theCspIsSentOnEveryFrontpagePage() throws Exception {
        assertTrue(render("/" + SITE_HANDLE + "/").contains(CSP_FRONTPAGE),
                "the home page must carry the CSP with font-src for self-hosted webfonts");
        assertTrue(render("/" + SITE_HANDLE + "/page/directory").contains(CSP_FRONTPAGE),
                "the directory page must carry the same CSP as the home page");
    }

    // ------------------------------------------------------------ analytics

    @Test
    void theFrontDoorInjectsTheUmamiScriptTagWhenASiteIdIsSet() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(siteWeblog);
        managed.setAnalyticsSiteId(ANALYTICS_SITE_ID);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);

        String body = render("/" + SITE_HANDLE + "/");

        assertTrue(body.contains(EXPECTED_ANALYTICS_SCRIPT_TAG),
                "the front page must inject the Umami script tag for the front "
                        + "weblog's analyticsSiteId:\n" + body);
        assertTrue(body.contains(CSP_FRONTPAGE),
                "wiring analytics into the head must not touch the CSP:\n" + body);
    }

    @Test
    void theFrontDoorInjectsNoAnalyticsScriptWithoutASiteId() throws Exception {
        String body = render("/" + SITE_HANDLE + "/");

        assertFalse(body.contains("/analytics/"),
                "no site id means no Umami script must be injected:\n" + body);
    }

    // -------------------------------------------------- document shell (a11y)

    /**
     * The front door's shell, same contract as the three per-weblog themes'
     * (see {@code JournalThemeRenderingTest#theDocumentShellDeclaresItsLanguageAndOffersASkipLink}):
     * a BCP-47 {@code lang}, a skip link ahead of the top bar, its
     * {@code #main} target, and one {@code <h1>} -- the site name. The
     * "Latest across the site" / "Weblogs" strap lines were {@code <p>} until
     * the 2026-08-20 sweep, so the page's sections had no headings at all.
     */
    @Test
    void theDocumentShellDeclaresItsLanguageAndOffersASkipLink() throws Exception {
        contributorEntry("shell-check");

        String body = render("/" + SITE_HANDLE + "/");

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
        assertTrue(body.contains("<h1 class=\"fd-site\">"),
                "the site name must be the page's h1:\n" + body);
        assertTrue(body.contains("<h2 class=\"fd-label\">Latest across the site</h2>"),
                "each section strap line must be a heading, not a paragraph:\n" + body);
        assertTrue(body.contains("<span class=\"live\" aria-hidden=\"true\">"),
                "the always-green live dot is decorative and must be hidden from "
                        + "assistive technology:\n" + body);
    }

    // ------------------------------------------------- fit and finish (0.3.0)

    private static int occurrences(String body, String needle) {
        int count = 0;
        for (int at = body.indexOf(needle); at >= 0; at = body.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    /**
     * Nothing excluded the pinned block's entries from the pager's own items,
     * so a recent pinned entry was listed twice on the first page, and its
     * row carried no sign of why it sat above newer ones.
     */
    @Test
    void aRecentPinnedEntryIsListedOnceAndSaysItIsPinned() throws Exception {
        WeblogEntry pinned = TestUtils.setupWeblogEntry("pinned-and-recent", contributorWeblog,
                contributorUser);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(pinned.getId());
        managed.setPinnedToMain(true);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        contributorEntry("an-ordinary-entry");

        String body = render("/" + SITE_HANDLE + "/");

        assertEquals(1, occurrences(body, "/entry/pinned-and-recent\""),
                "the pinned entry must be listed once, in the pinned block:\n" + body);
        assertEquals(1, occurrences(body, "<span class=\"pin\">Pinned</span>"),
                "only the pinned row carries the label:\n" + body);
        assertTrue(body.indexOf("pinned-and-recent") < body.indexOf("an-ordinary-entry"),
                "and it leads the list:\n" + body);
    }

    /** Journal's Newer/Older steps, in place of "&lt; Previous | Next &gt;". */
    @Test
    void theFrontDoorPagerStepsBackToOlderPosts() throws Exception {
        for (int i = 0; i < 26; i++) {
            contributorEntry("paged-entry-" + i);
        }

        String body = render("/" + SITE_HANDLE + "/");

        int nav = body.indexOf("aria-label=\"More entries\"");
        assertTrue(nav >= 0, "a second page of posts needs a pager:\n" + body);
        String pager = body.substring(nav, body.indexOf("</nav>", nav));
        assertTrue(pager.contains("rel=\"next\""), pager);
        assertTrue(pager.contains(">Older</span>"), pager);
        assertFalse(pager.contains("&gt;") || pager.contains("&lt;") || pager.contains("|"),
                "the legacy strip must be gone:\n" + pager);
    }

    /** The tab you are on is marked, for sight and for assistive technology. */
    @Test
    void theTabsMarkTheViewYouAreOn() throws Exception {
        String home = render("/" + SITE_HANDLE + "/");
        assertTrue(home.contains("aria-current=\"page\">Recent posts</a>"),
                "the home tab must be current on the front door:\n" + home);
        assertFalse(home.contains("aria-current=\"page\">Directory</a>"), home);

        String directory = render("/" + SITE_HANDLE + "/page/directory");
        assertTrue(directory.contains("aria-current=\"page\">Directory</a>"),
                "the directory tab must be current on the directory:\n" + directory);
        assertFalse(directory.contains("aria-current=\"page\">Recent posts</a>"), directory);
    }

    /**
     * The search branch was keyed on {@code $model.results}, an empty map on
     * a search that found nothing -- and Velocity's #if treats an empty map
     * as false, so a reader who searched for something absent got the front
     * door back with no word about their search.
     */
    @Test
    void aSearchThatFindsNothingSaysSoInsteadOfShowingTheFrontDoor() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/search", "/" + SITE_HANDLE);
        request.setParameter("q", "zzznope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);
        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();

        assertTrue(body.contains("<h2 class=\"fd-label\">Search results</h2>"), body);
        assertTrue(body.contains("No posts matched that search."),
                "a zero-hit search must say so:\n" + body);
        assertFalse(body.contains("Latest across the site"),
                "and must not fall back to the front door:\n" + body);
        assertTrue(body.contains("<title>Search: zzznope \u2014 "),
                "the tab must name the search:\n" + body);
        assertTrue(body.contains("<h2 class=\"fd-label\">Search all posts</h2>"),
                "the bottom form searches posts, not blogs:\n" + body);
    }

    /**
     * The grid listed weblogs CREATED in the last 365 days under the heading
     * "Weblogs", so on a small site it would shrink and then vanish a year
     * after launch while the heading went on promising weblogs.
     */
    @Test
    void theWeblogsGridKeepsAWeblogOlderThanAYear() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(contributorWeblog);
        managed.setDateCreated(new java.util.Date(System.currentTimeMillis()
                - java.util.concurrent.TimeUnit.DAYS.toMillis(800)));
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);

        String body = render("/" + SITE_HANDLE + "/");

        int grid = body.indexOf("<div class=\"fd-dir\">");
        assertTrue(grid >= 0, "the weblogs grid must render:\n" + body);
        assertTrue(body.indexOf(CONTRIBUTOR_HANDLE, grid) > grid,
                "a two-year-old weblog is still a weblog of this site:\n" + body);
    }

    /**
     * Frontpage shipped no _page, so a static page on the front-door weblog
     * rendered through the shared fallback: no fd class on the body, so the
     * browser-default look inside an otherwise designed site.
     */
    @Test
    void aStaticPageOnTheFrontDoorWeblogWearsTheFrontDoorChrome() throws Exception {
        com.wikantik.pressroom.pojos.WeblogPage page =
                new com.wikantik.pressroom.pojos.WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(siteWeblog));
        page.setSlug("about");
        page.setTitle("About <this> site");
        page.setContent("Who runs the site. [contact]");
        page.setStatus(com.wikantik.pressroom.pojos.WeblogPage.PubStatus.PUBLISHED);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        String body = render("/" + SITE_HANDLE + "/about");

        assertFrontpageHead(body);
        assertTrue(body.contains("<body class=\"fd\">"), "the page must wear the fd chrome:\n" + body);
        assertTrue(body.contains("class=\"fd-topbar\""), body);
        assertTrue(body.contains("<h1 class=\"fd-page-title\">About &lt;this&gt; site</h1>"),
                "the raw page title must be escaped once, as the page's h1:\n" + body);
        assertEquals(1, occurrences(body, "<h1"),
                "the site name steps down to a paragraph on a page with its own title:\n" + body);
        assertTrue(body.contains("<article class=\"fd-page roller-content\">"),
                "the content sits in the content-asset scope:\n" + body);
        assertTrue(body.contains("contact-form-slot"), "shortcodes must expand:\n" + body);
        assertTrue(body.contains(".video-embed"),
                "a [video] on the page must still play, as it did in the fallback:\n" + body);
        assertTrue(body.contains("<title>About &lt;this&gt; site \u2014 "), body);
        assertFalse(body.contains("<h1>About"), "not the bare fallback template:\n" + body);
    }
}
