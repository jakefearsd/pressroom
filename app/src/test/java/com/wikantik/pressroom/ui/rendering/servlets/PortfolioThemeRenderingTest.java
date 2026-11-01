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

import org.apache.commons.text.StringEscapeUtils;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the new portfolio theme through the real PageServlet /
 * SearchServlet: the front page packs featured images into the justified
 * {@code .pf-grid} (with the {@code --ar} aspect ratio driving the packing,
 * same convention as the gallery shortcode's {@code .jgrid}), the permalink
 * leads with a full-bleed hero, and every head carries the same CSP meta,
 * {@code #showSeoHead}, and the gallery grid + lightbox assets as the other
 * four themes. Entries without a featured image degrade to quiet text cards
 * rather than broken markup.
 */
class PortfolioThemeRenderingTest {

    private static final String HANDLE = "portfoliorenderblog";
    private static final String BASE = "http://localhost:8080/pressroom/" + HANDLE;

    /**
     * The directives this test actually cares about, not the whole policy
     * string -- pinning the whole string made it impossible for a theme to
     * name a directive its own assets need without failing this test (see
     * {@code MapAssetsRenderingTest#CSP_DATA_IMAGES}, which holds the same
     * lesson). {@code data:} joined {@code img-src} when Leaflet arrived: it
     * paints aborted and out-of-range tiles with a base64 GIF placeholder,
     * and per CSP3 the {@code *} wildcard does not match the {@code data:}
     * scheme.
     */
    private static final String CSP_DATA_IMAGES = "img-src * data:;";

    /**
     * The single authorised CSP widening for this wave: frame-src limited to
     * the two providers {@code [video]} can target. Same constant
     * {@code MapAssetsRenderingTest} and {@code TravelThemeRenderingTest} hold.
     */
    private static final String CSP_VIDEO_FRAMES =
            "frame-src https://www.youtube-nocookie.com https://player.vimeo.com;";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("portfoliorenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        switchTheme("portfolio");
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
                .anonymousGet("/pressroom-ui/rendering/page", pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(),
                "page must render for " + pathInfo);
        return response.getContentAsString();
    }

    private void switchTheme(String themeName) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(themeName);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    private WeblogEntry entryWithFeaturedImage(String anchor, String imageId)
            throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, weblog, user);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setFeaturedImageId(imageId);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return entry;
    }

    private void savePage(String slug, String title, String content) throws Exception {
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

    /** The shared head contract plus the no-Velocity-leak assertions. */
    private static void assertPortfolioHead(String body) {
        assertTrue(body.contains(CSP_DATA_IMAGES),
                "the portfolio head must allow data: images, like the other themes:\n" + body);
        assertTrue(body.contains(CSP_VIDEO_FRAMES),
                "the portfolio head must allow the video providers' frames:\n" + body);
        assertTrue(body.contains("<link rel=\"canonical\""),
                "#showSeoHead must contribute the canonical link:\n" + body);
        assertTrue(body.contains(".jgrid { display: flex;"),
                "#showGalleryGridStyles must be in the head:\n" + body);
        assertTrue(body.contains("/webjars/photoswipe/"),
                "#showGalleryAssets must ship the lightbox:\n" + body);
        assertTrue(body.contains("/webjars/leaflet/"),
                "#showMapAssets must be in the head -- all four portfolio "
                        + "templates (page.vm included) call it, and this shared "
                        + "assert is the page template's only Leaflet coverage:\n" + body);
        assertTrue(body.contains("portfolio-custom.css"),
                "the head must link the theme stylesheet override:\n" + body);
        assertTrue(body.contains("function pressroomInitContent(") && body.contains(".markdown-alert-note {"),
                "the content-asset macro must be in the head: callouts, code blocks and "
                        + "heading anchors in .pf-entry-content depend on it:\n" + body);
        assertFalse(body.contains("#showContentAssets"), body);
        // a Velocity error would leak the raw directive or reference text
        assertFalse(body.contains("#showResponsiveImage"), body);
        assertFalse(body.contains("#showGalleryAssets"), body);
        assertFalse(body.contains("#showGalleryGridStyles"), body);
        assertFalse(body.contains("#showSeoHead"), body);
        assertFalse(body.contains("$entry.featuredImage"), body);
    }

    // ------------------------------------------------------------ discovery

    @Test
    void theThemeManagerListsThePortfolioTheme() throws Exception {
        SharedTheme portfolio = TestUtils.weblogger().getThemeManager()
                .getEnabledThemesList().stream()
                .filter(theme -> "portfolio".equals(theme.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "portfolio theme missing from the enabled themes list"));
        assertEquals("Portfolio", portfolio.getName());
        assertTrue(portfolio.getStylesheet() != null,
                "the theme must declare a stylesheet so per-weblog overrides work");
    }

    // ------------------------------------------------------------ front page

    @Test
    void theFrontPagePacksFeaturedImagesIntoTheJustifiedGrid() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "grid-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        entryWithFeaturedImage("grid-entry", imageId);

        String body = render("/" + HANDLE);

        assertPortfolioHead(body);
        assertTrue(body.contains("<div class=\"pf-grid\">"),
                "the front page is the justified grid:\n" + body);
        // hawk.jpg is 500x373; the card carries width/height as --ar so the
        // grid can pack justified rows exactly like .jgrid does
        assertTrue(body.contains("<figure class=\"pf-card\" style=\"--ar:1.34"),
                "each card must carry its aspect ratio for justified packing:\n" + body);
        assertTrue(body.contains("class=\"pf-card-img\""),
                "the featured image renders through #showResponsiveImage:\n" + body);
        assertTrue(body.contains("<picture>"), body);
        assertTrue(body.contains(BASE + "/mediaresource/" + imageId),
                "the card image must come off the rendition ladder:\n" + body);
        assertTrue(body.contains("<h2 class=\"pf-card-title\">grid-entry</h2>"),
                "the card carries the title overlay:\n" + body);
        assertTrue(body.contains("<a class=\"pf-card-link\" href=\"" + BASE
                        + "/entry/grid-entry\">"),
                "the whole card links to the entry:\n" + body);
    }

    /**
     * A card carries its date and opening too, hidden while it is one picture
     * among others: a run of ONE picture post between rows of writing (the
     * first wikantik post with a featured image, 2026-10-31) shows them, since
     * a lone tile whose title appears only on hover said nothing about it.
     */
    @Test
    void aPictureCardCarriesItsDateAndOpeningForWhenItStandsAlone() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "lone-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        entryWithFeaturedImage("lone-entry", imageId);

        String body = render("/" + HANDLE);

        assertTrue(CARD_DATE.matcher(body).find(), "the card carries a machine-readable date:\n" + body);
        assertTrue(body.contains("<p class=\"pf-card-sum\">blah blah entry</p>"),
                "the card carries the post's opening:\n" + body);
    }

    private static final Pattern CARD_DATE = Pattern.compile(
            "<p class=\"pf-card-meta\"><time datetime=\"\\d{4}-\\d{2}-\\d{2}\">"
                    + "[A-Za-z]+ \\d{1,2}, \\d{4}</time>");

    /**
     * A post with no featured image is writing, not a picture, and gets a
     * list row -- title, date, category and a line of its prose -- rather
     * than an empty tile. Before this the wikantik weblog's front page was a
     * wall of teal boxes carrying nothing but a title each.
     */
    @Test
    void anEntryWithoutAFeaturedImageIsAListRowWithItsOpening() throws Exception {
        TestUtils.setupWeblogEntry("bare-entry", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE);

        assertPortfolioHead(body);
        assertTrue(body.contains("<div class=\"pf-list\">"), "imageless entries form a list:\n" + body);
        assertTrue(body.contains("<article class=\"pf-row\">"), body);
        assertTrue(body.contains("<h2 class=\"pf-row-title\"><a href=\"" + BASE
                + "/entry/bare-entry\">bare-entry</a></h2>"), "the title links to the entry:\n" + body);
        assertTrue(ROW_DATE.matcher(body).find(), "the row carries a machine-readable date:\n" + body);
        assertTrue(body.contains("<p class=\"pf-row-sum\">blah blah entry</p>"),
                "with no summary written, the row shows the post's opening:\n" + body);
        assertFalse(body.contains("pf-card"), "no tile is emitted for an imageless entry:\n" + body);
        assertFalse(body.contains("<picture>"),
                "no responsive image may be emitted without a featured image:\n" + body);
    }

    private static final Pattern ROW_DATE = Pattern.compile(
            "<p class=\"pf-row-meta\"><time datetime=\"\\d{4}-\\d{2}-\\d{2}\">"
                    + "[A-Za-z]+ \\d{1,2}, \\d{4}</time>");

    @Test
    void anAuthorsSummaryIsTheRowsLineEscapedOnce() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("summarised-entry", weblog, user);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setSummary("Fish & chips, and *why* the coast still serves them.");
        managed.setSearchDescription("Written for search engines.");
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE);

        assertTrue(body.contains("<p class=\"pf-row-sum\">Fish &amp; chips, and why the coast still serves them.</p>"),
                "the summary is shown as plain text, escaped exactly once:\n" + body);
        assertFalse(body.contains("Written for search engines."),
                "the author's summary outranks the search description:\n" + body);
    }

    /**
     * Pictures and writing interleave in publication order: each run of
     * image entries is its own justified grid, each run of imageless ones its
     * own list, and every container the day fragment opens is closed.
     */
    @Test
    void picturesAndWritingAlternateInRunsInPublicationOrder() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "run-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        publishAt(entryWithFeaturedImage("newest-picture", imageId), "2026-03-03 10:00:00");
        publishAt(TestUtils.setupWeblogEntry("older-essay", weblog, user), "2026-03-02 10:00:00");
        publishAt(TestUtils.setupWeblogEntry("oldest-essay", weblog, user), "2026-03-01 10:00:00");

        String body = render("/" + HANDLE);
        String main = body.substring(body.indexOf("<main"), body.indexOf("</main>"));

        int grid = main.indexOf("<div class=\"pf-grid\">");
        int list = main.indexOf("<div class=\"pf-list\">");
        assertTrue(grid >= 0 && list > grid, "the picture's grid run comes first, then the list:\n" + main);
        assertTrue(main.indexOf("newest-picture") < main.indexOf("older-essay")
                && main.indexOf("older-essay") < main.indexOf("oldest-essay"), main);
        assertEquals(1, count(main, "<div class=\"pf-list\">"),
                "consecutive imageless entries share one list:\n" + main);
        assertEquals(count(main, "<div"), count(main, "</div>"), "every run is closed:\n" + main);
    }

    private static void publishAt(WeblogEntry entry, String when) throws Exception {
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setPubTime(java.sql.Timestamp.valueOf(when));
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------- permalink

    @Test
    void thePermalinkLeadsWithAFullBleedHero() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "hero-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        entryWithFeaturedImage("hero-entry", imageId);

        String body = render("/" + HANDLE + "/entry/hero-entry");

        assertPortfolioHead(body);
        assertTrue(body.contains("<figure class=\"pf-hero\">"),
                "the permalink leads with the hero figure:\n" + body);
        assertTrue(body.contains("class=\"pf-hero-img\""), body);
        assertTrue(body.contains("sizes=\"100vw\""),
                "the hero is full-bleed, so it declares 100vw:\n" + body);
        assertTrue(body.contains("<h1 class=\"pf-entry-title\">hero-entry</h1>"), body);
        assertTrue(body.contains("blah blah entry"),
                "the entry content must render below the hero:\n" + body);
        assertFalse(body.contains("pf-comments"),
                "the comments section must be gone from the permalink");
        assertFalse(body.contains("commentForm"),
                "no comment form may survive in rendered output");
    }

    @Test
    void aPermalinkWithoutAFeaturedImageRendersWithoutBreakage() throws Exception {
        TestUtils.setupWeblogEntry("plain-entry", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/entry/plain-entry");

        assertPortfolioHead(body);
        assertFalse(body.contains("pf-hero"),
                "no hero markup may appear without a featured image:\n" + body);
        assertFalse(body.contains("<picture>"), body);
        assertTrue(body.contains("<h1 class=\"pf-entry-title\">plain-entry</h1>"), body);
        assertTrue(body.contains("blah blah entry"), body);
    }

    // ----------------------------------------------------- escaping (once)

    /**
     * Entry titles are stored pre-escaped by {@code EntryBean.copyTo} (see
     * {@code EntryBeanTest#copyToEscapesTheTitleButNotTheBody}) -- mirrored
     * here rather than driving the controller. {@code _day.vm} used to call
     * {@code $utils.escapeHTML($entry.title)} on top of that, double-encoding
     * ('&' became {@code &amp;amp;}); this pins the fix (bare {@code
     * $entry.title}) on the no-image grid card title and the permalink h1.
     */
    @Test
    void theEntryTitleEscapesExactlyOnce() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("salt-and-light", weblog, user);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        // Stored form of "Salt & Light" -- the transformation EntryBean.copyTo
        // applies at save time (see EntryBeanTest:212), not raw author input.
        managed.setTitle(StringEscapeUtils.escapeHtml4("Salt & Light"));
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);

        String cardBody = render("/" + HANDLE);
        assertTrue(cardBody.contains("Salt &amp; Light"),
                "the grid card title must render escaped exactly once:\n" + cardBody);
        assertFalse(cardBody.contains("&amp;amp;"),
                "the stored-escaped title must not be double-encoded:\n" + cardBody);

        String permalinkBody = render("/" + HANDLE + "/entry/salt-and-light");
        assertTrue(permalinkBody.contains("Salt &amp; Light"),
                "the permalink h1 must render escaped exactly once:\n" + permalinkBody);
        assertFalse(permalinkBody.contains("&amp;amp;"),
                "the stored-escaped title must not be double-encoded:\n" + permalinkBody);
    }

    /**
     * The alt attribute is the third escaping direction on this page and the
     * one that was wrong in both halves. {@code #showResponsiveImage} escapes
     * its {@code $alt} argument on the way out
     * ({@code alt="$utils.escapeHTML($alt)"}), and {@code _day.vm} handed it
     * {@code $entry.title} -- already stored escaped -- so a screen reader
     * announced the literal text "Salt &amp;amp; Light". Worse, the entry
     * title was being used as the image's description at all: the image now
     * speaks for itself through {@code MediaFile.altText} when it has one
     * (W4's whole point), and only falls back to the unescaped title.
     */
    @Test
    void theHeroAltTextPrefersTheImagesOwnDescription() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "alt-image");
        String imageId = image.getId();
        setAltText(imageId, "A hawk over the marsh & mudflats");
        TestUtils.endSession(true);
        WeblogEntry entry = entryWithFeaturedImage("alt-entry", imageId);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setTitle(StringEscapeUtils.escapeHtml4("Salt & Light"));
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/entry/alt-entry");

        assertTrue(body.contains("alt=\"A hawk over the marsh &amp; mudflats\""),
                "the image's own alt text must win over the entry title:\n" + body);
        assertFalse(body.contains("&amp;amp;"),
                "nothing on this page may double-encode:\n" + body);
    }

    @Test
    void theHeroAltTextFallsBackToAnUnescapedTitle() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "noalt-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        WeblogEntry entry = entryWithFeaturedImage("noalt-entry", imageId);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setTitle(StringEscapeUtils.escapeHtml4("Salt & Light"));
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/entry/noalt-entry");

        assertTrue(body.contains("alt=\"Salt &amp; Light\""),
                "an image with no alt text falls back to the title, escaped "
                        + "exactly once:\n" + body);
        assertFalse(body.contains("&amp;amp;"),
                "the stored-escaped title must be unescaped before the macro "
                        + "escapes it again:\n" + body);
    }

    private void setAltText(String mediaFileId, String altText) throws Exception {
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFile managed = mfMgr.getMediaFile(mediaFileId);
        managed.setAltText(altText);
        mfMgr.updateMediaFile(TestUtils.getManagedWebsite(weblog), managed);
        TestUtils.weblogger().flush();
    }

    /**
     * {@code $model.weblog.name} comes back from {@code WeblogWrapper#getName}
     * already {@code escapeHtml4}'d (see {@code WeblogWrapper.java:95}); the
     * template used to wrap it in {@code $utils.escapeHTML} too, double-
     * encoding ('&' became {@code &amp;amp;}). Pins the fix (bare {@code
     * $model.weblog.name}) on the front page's pf-name and the permalink's.
     * {@code Weblog.setName} does not escape, so the '&' below is stored raw.
     */
    @Test
    void theWeblogNameEscapesExactlyOnce() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setName("Light & Shadow Studio");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        TestUtils.setupWeblogEntry("frame-and-focus", weblog, user);
        TestUtils.endSession(true);

        String frontBody = render("/" + HANDLE);
        assertTrue(frontBody.contains("Light &amp; Shadow Studio"),
                "the weblog name must render escaped exactly once:\n" + frontBody);
        assertFalse(frontBody.contains("&amp;amp;"),
                "no reference may double-encode a value that is already "
                        + "wrapper-escaped:\n" + frontBody);

        String permalinkBody = render("/" + HANDLE + "/entry/frame-and-focus");
        assertTrue(permalinkBody.contains("Light &amp; Shadow Studio"),
                "the permalink's weblog name must render escaped exactly once:\n"
                        + permalinkBody);
        assertFalse(permalinkBody.contains("&amp;amp;"),
                "no reference may double-encode a value that is already "
                        + "wrapper-escaped:\n" + permalinkBody);
    }

    // ------------------------------------------------------------------ page

    @Test
    void aPageRendersThroughThePortfolioThemeInsteadOfTheFallback() throws Exception {
        savePage("about", "About This Work", "Some prose about the work. [contact]");

        String body = render("/" + HANDLE + "/about");

        assertPortfolioHead(body);
        assertTrue(body.contains("class=\"pf-header\""),
                "the page must wear the portfolio header chrome:\n" + body);
        assertTrue(body.contains("<h1 class=\"pf-entry-title\">About This Work</h1>"),
                "the page title must render as the entry title, the same class "
                        + "the permalink uses:\n" + body);
        assertTrue(body.contains("class=\"pf-entry-content\">"),
                "the page content must render inside the entry-content column:\n" + body);
        assertTrue(body.contains("audience-hp"),
                "the audience assets (contact form script/style) must be in the head:\n" + body);
        assertFalse(body.contains("<h1>About This Work</h1>"),
                "the naked fallback template's unstyled h1 must not be what renders:\n" + body);
    }

    // ------------------------------------------------------------ stylesheet

    /**
     * The stylesheet ships as a Velocity-rendered theme template (the
     * declared override target), so an id selector or stray directive-looking
     * token that Velocity chokes on would corrupt the whole file at serve
     * time -- render it for real.
     */
    @Test
    void theStylesheetRendersIntactThroughTheTemplateEngine() throws Exception {
        String body = render("/" + HANDLE + "/page/portfolio-custom.css");

        assertTrue(body.contains(".pf-grid {"), body);
        assertTrue(body.contains("flex-basis: calc(var(--ar) * var(--pf-row-h));"),
                "the justified-packing rule must survive Velocity:\n" + body);
        assertTrue(body.contains("#searchAgain input.text"),
                "id selectors must pass through Velocity untouched:\n" + body);
        assertFalse(body.contains("ParseErrorException"), body);
    }

    /**
     * The head links the stylesheet at a versioned address -- Cloudflare
     * holds an unversioned .css for four hours, which kept 0.3.1's list rows
     * on 0.3.0's rules -- and the versioned address is served like the plain
     * one.
     */
    @Test
    void theStylesheetIsLinkedAtAVersionedAddressThatIsServed() throws Exception {
        String body = render("/" + HANDLE + "/");
        assertTrue(Pattern.compile("href=\"[^\"]*/page/portfolio-custom\\.css\\?v=\\d+\"").matcher(body).find(),
                "the head must link a versioned stylesheet:\n" + body);

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/pressroom-ui/rendering/page", "/" + HANDLE + "/page/portfolio-custom.css");
        request.setQueryString("v=1234567890");
        request.setParameter("v", "1234567890");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains(".pf-grid {"), response.getContentAsString());
    }

    /**
     * {@code body.pf a} makes every link inherit the text colour with no
     * underline -- right for the chrome (the name, the nav, the cards), and
     * the reason a link inside a post read as plain text. Content links get
     * the accent and an underline back, scoped to {@code .pf-entry-content}
     * so the chrome rule above is untouched; captions and quotes drop to the
     * muted token (#8f8f96 on #0e0e10 is 6.0:1).
     */
    @Test
    void contentLinksCaptionsAndQuotesAreStyledInsideContentOnly() throws Exception {
        String css = render("/" + HANDLE + "/page/portfolio-custom.css");

        String chrome = rule(css, "body.pf a {");
        assertTrue(chrome.contains("color: inherit;") && chrome.contains("text-decoration: none;"),
                "the chrome link rule must stay as it was:\n" + chrome);

        // :where() keeps the exclusion from adding specificity: the [cta]
        // card is an accent-filled link of its own, and the shared heading
        // anchor must still out-rank this rule.
        String link = rule(css, "body.pf .pf-entry-content a:where(:not(.cta-card)) {");
        assertTrue(link.contains("color: var(--pf-accent);"), link);
        assertTrue(link.contains("text-decoration: underline;"), link);
        String hover = rule(css, "body.pf .pf-entry-content a:where(:not(.cta-card)):hover {");
        assertFalse(hover.isBlank(), "content links need a hover state");

        assertTrue(rule(css, "body.pf .pf-entry-content figcaption {")
                .contains("color: var(--pf-muted);"), css);
        String quote = rule(css, "body.pf .pf-entry-content blockquote {");
        assertTrue(quote.contains("border-left:") && quote.contains("color: var(--pf-muted);"), quote);
    }

    /** The body of the first rule with exactly this selector, or a failure naming it. */
    private static String rule(String css, String selectorAndBrace) {
        int at = css.indexOf("\n" + selectorAndBrace);
        assertTrue(at >= 0, "expected a rule " + selectorAndBrace + " in:\n" + css);
        int open = at + selectorAndBrace.length();
        return css.substring(open, css.indexOf('}', open));
    }

    /**
     * The list rows sit in the theme's reading measure, not the grid's full
     * bleed, and set the title in the theme's foreground with the accent kept
     * for the hover -- the one thing in the row a reader can act on.
     */
    @Test
    void theListRowsAreStyledAsAReadingList() throws Exception {
        String body = render("/" + HANDLE + "/page/portfolio-custom.css");

        assertTrue(body.contains("--pf-accent: #4FB3AA;"), "the theme declares its accent token:\n" + body);
        assertTrue(rule(body, ".pf-list {").contains("max-width"),
                "the list keeps a reading measure:\n" + body);
        assertTrue(rule(body, ".pf-row-title a:hover {").contains("var(--pf-accent)"),
                "the title's hover takes the accent:\n" + body);
        assertFalse(body.contains(".pf-card-text"), "the old text-tile rules are gone:\n" + body);
    }

    /**
     * The stylesheet half of the lone card: a grid holding one card takes
     * the list's reading measure, shows the title under the picture rather
     * than over it on hover, and shows the date and opening a card in a run
     * of pictures keeps hidden.
     */
    @Test
    void aLonePictureCardIsStyledAsAReadingRow() throws Exception {
        String body = render("/" + HANDLE + "/page/portfolio-custom.css");

        assertTrue(rule(body, ".pf-card-meta,\n.pf-card-sum {").contains("display: none;"),
                "in a run of pictures a card shows only its picture and title:\n" + body);
        String lone = ".pf-grid:has(> .pf-card:only-child)";
        assertTrue(rule(body, lone + " {").contains("max-width: 46rem;"),
                "a lone card takes the reading measure:\n" + body);
        String title = rule(body, lone + " .pf-card-title {");
        assertTrue(title.contains("position: static;") && title.contains("opacity: 1;"),
                "a lone card's title sits under the picture, always shown:\n" + body);
        assertTrue(rule(body, lone + " .pf-card-meta,\n" + lone + " .pf-card-sum {").contains("display: block;"),
                "a lone card shows its date and opening:\n" + body);
    }

    // ---------------------------------------------------------------- search

    @Test
    void theSearchPageRendersThePortfolioTemplate() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/search", "/" + HANDLE);
        request.setParameter("q", "zzznope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains(CSP_DATA_IMAGES),
                "the search head must allow data: images:\n" + body);
        assertTrue(body.contains(CSP_VIDEO_FRAMES),
                "the search head must allow the video providers' frames:\n" + body);
        assertTrue(body.contains(".jgrid { display: flex;"), body);
        assertTrue(body.contains("/webjars/photoswipe/"), body);
        assertTrue(body.contains("<div class=\"pf-entries\">"),
                "search results reuse the front page's runs of grid and list:\n" + body);
        assertTrue(body.contains("id=\"searchAgain\""),
                "the search-again form must be present:\n" + body);
    }

    // -------------------------------------------------- document shell (a11y)

    /**
     * The document shell every portfolio page shares: a BCP-47 {@code lang} (the
     * stored locale is {@code en_US}, which is not a tag any user agent
     * parses -- see {@code WeblogWrapper#getLanguageTag}), a skip link as the
     * first focusable node, the {@code #main} target it points at, and exactly
     * one {@code <h1>} -- the site name. Before the 2026-08-20 sweep the shell
     * had none of the four.
     */
    @Test
    void theDocumentShellDeclaresItsLanguageAndOffersASkipLink() throws Exception {

        String body = render("/" + HANDLE);

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
        assertTrue(body.contains("<h1 class=\"pf-name\">"),
                "the site name must be the page's h1:\n" + body);
        assertEquals(1, countOf(body, "<h1"),
                "a page must have exactly one h1 -- the site name:\n" + body);
        // This fixture has no entries, so the same render also exercises the
        // zero-entry branch -- and Velocity is lenient, so an #if whose
        // reference failed to resolve would print as literal text here rather
        // than failing (JournalThemeRenderingTest pins the wording; this pins
        // that the branch evaluates at all in this theme).
        assertTrue(body.contains("class=\"pf-list-empty\""),
                "an empty entry list must say something:\n" + body);
        assertFalse(body.contains("$pagerDays"),
                "a reference Velocity could not resolve prints as literal text:\n" + body);
    }

    /** Occurrences of {@code needle} in {@code haystack}. */
    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
