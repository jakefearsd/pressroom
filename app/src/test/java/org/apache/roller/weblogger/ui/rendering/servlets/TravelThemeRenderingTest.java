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

import org.apache.commons.text.StringEscapeUtils;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.themes.SharedTheme;
import org.apache.roller.weblogger.pojos.MediaFile;
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
 * Renders the travel-guide theme through the real PageServlet: the front page
 * lays entries out as guide cards (hero image, title, excerpt), the permalink
 * gives them a wide reading column, and every head carries the same CSP meta
 * and the same four macros ({@code #showSeoHead}, the gallery grid styles, the
 * lightbox, and the Leaflet map assets) as the other five themes. Entries
 * without a featured image degrade to text-only cards rather than broken
 * markup, which is the case a guide blog hits constantly.
 */
class TravelThemeRenderingTest {

    private static final String HANDLE = "travelrenderblog";
    private static final String BASE = "http://localhost:8080/roller/" + HANDLE;

    /**
     * The directives this test actually cares about, not the whole policy
     * string -- the same constants {@code PortfolioThemeRenderingTest} and
     * {@code MapAssetsRenderingTest} hold. Pinning the whole string made it
     * impossible for a theme to name a directive its own assets need without
     * failing this test.
     */
    private static final String CSP_DATA_IMAGES = "img-src * data:;";

    /**
     * The single authorised CSP widening for this wave: frame-src limited to
     * the two providers {@code [video]} can target.
     */
    private static final String CSP_VIDEO_FRAMES =
            "frame-src https://www.youtube-nocookie.com https://player.vimeo.com;";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("travelrenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        switchTheme("travel");
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

    private WeblogEntry entryWithFeaturedImage(String anchor, String imageId)
            throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, weblog, user);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setFeaturedImageId(imageId);
        managed.setSearchDescription("Two days of hot springs and black sand.");
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return entry;
    }

    /** The shared head contract plus the no-Velocity-leak assertions. */
    private static void assertTravelHead(String body) {
        assertTrue(body.contains(CSP_DATA_IMAGES),
                "the travel head must allow data: images, like the other themes:\n" + body);
        assertTrue(body.contains(CSP_VIDEO_FRAMES),
                "the travel head must allow the video providers' frames:\n" + body);
        assertTrue(body.contains("<link rel=\"canonical\""),
                "#showSeoHead must contribute the canonical link:\n" + body);
        assertTrue(body.contains(".jgrid { display: flex;"),
                "#showGalleryGridStyles must be in the head:\n" + body);
        assertTrue(body.contains("/webjars/photoswipe/"),
                "#showGalleryAssets must ship the lightbox:\n" + body);
        assertTrue(body.contains("/webjars/leaflet/"),
                "#showMapAssets must ship Leaflet -- this is the travel theme:\n" + body);
        assertTrue(body.contains("travel-custom.css"),
                "the head must link the theme stylesheet override:\n" + body);
        assertTrue(body.contains("function rollerInitContent(") && body.contains(".markdown-alert-note {"),
                "the content-asset macro must be in the head: callouts, code blocks and "
                        + "heading anchors in .tg-entry-content depend on it:\n" + body);
        assertFalse(body.contains("#showContentAssets"), body);
        // a Velocity error would leak the raw directive or reference text
        assertFalse(body.contains("#showResponsiveImage"), body);
        assertFalse(body.contains("#showGalleryAssets"), body);
        assertFalse(body.contains("#showMapAssets"), body);
        assertFalse(body.contains("#showSeoHead"), body);
        assertFalse(body.contains("$entry.featuredImage"), body);
        assertFalse(body.contains("$utils."), body);
    }

    // ------------------------------------------------------------ discovery

    @Test
    void theThemeManagerListsTheTravelTheme() throws Exception {
        SharedTheme travel = TestUtils.weblogger().getThemeManager()
                .getEnabledThemesList().stream()
                .filter(theme -> "travel".equals(theme.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "travel theme missing from the enabled themes list"));
        assertEquals("Travel Guide", travel.getName());
        assertTrue(travel.getStylesheet() != null,
                "the theme must declare a stylesheet so per-weblog overrides work");
    }

    // ------------------------------------------------------------ front page

    @Test
    void theFrontPageLaysEntriesOutAsGuideCards() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "card-image");
        entryWithFeaturedImage("iceland-ring-road", image.getId());

        String body = render("/" + HANDLE + "/");

        assertTravelHead(body);
        assertTrue(body.contains("<div class=\"tg-cards\">"),
                "the front page must open the card grid:\n" + body);
        assertTrue(body.contains("class=\"tg-card\""),
                "each entry must render as a guide card:\n" + body);
        assertTrue(body.contains("class=\"tg-card-img\""),
                "a featured image must fill the card's media slot:\n" + body);
        assertTrue(body.contains("<picture>"),
                "the card image must go through #showResponsiveImage:\n" + body);
        assertTrue(body.contains(BASE + "/mediaresource/" + image.getId()),
                "and point at the entry's own featured image:\n" + body);
        assertTrue(body.contains("Two days of hot springs and black sand."),
                "the card excerpt must come from the entry's search description:\n" + body);
    }

    @Test
    void anEntryWithoutAFeaturedImageStillRendersACard() throws Exception {
        TestUtils.setupWeblogEntry("no-photos-yet", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/");

        assertTravelHead(body);
        assertTrue(body.contains("class=\"tg-card\""),
                "a text-only entry must still get a card:\n" + body);
        assertFalse(body.contains("class=\"tg-card-media\""),
                "but no media slot, since there is no image to put in it:\n" + body);
        assertFalse(body.contains("<picture>"),
                "and no responsive-image markup at all:\n" + body);
    }

    /**
     * A card showed an excerpt only when the author had written a search
     * description, which most entries never get, so cards in one row had
     * uneven heights. Journal's list falls back to the entry's opening words;
     * travel now does the same, emitted bare because removeHTML over rendered
     * content returns text whose entities are already escaped.
     */
    @Test
    void aCardWithoutASearchDescriptionShowsTheEntrysOpeningWords() throws Exception {
        TestUtils.setupWeblogEntry("no-summary-written", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/");

        assertTrue(body.contains("<p class=\"tg-card-excerpt\">blah blah entry</p>"),
                "the card must fall back to the entry's own opening words:\n" + body);
    }

    // ------------------------------------------------------------- permalink

    @Test
    void thePermalinkLeadsWithTheHeroAndOpensTheReadingColumn() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "hero-image");
        entryWithFeaturedImage("faroe-islands", image.getId());

        String body = render("/" + HANDLE + "/entry/faroe-islands");

        assertTravelHead(body);
        assertTrue(body.contains("<main class=\"tg-main tg-main-entry\" id=\"main\">"),
                "the permalink must use the narrower reading column:\n" + body);
        assertTrue(body.contains("<figure class=\"tg-hero\">"),
                "and lead with the hero figure:\n" + body);
        assertTrue(body.contains("class=\"tg-hero-img\""),
                "whose image is the featured image:\n" + body);
        assertTrue(body.contains("<h1 class=\"tg-entry-title\">"),
                "the entry title must be the permalink's h1 -- on a reading page the "
                        + "site name steps down to a paragraph:\n" + body);
        assertTrue(body.contains("<div class=\"tg-entry-content\">"),
                "and the content must open the measured column:\n" + body);
        assertFalse(body.contains("tg-comments"),
                "the comments section must be gone from the permalink");
        assertFalse(body.contains("commentForm"),
                "no comment form may survive in rendered output");
    }

    @Test
    void aPermalinkWithoutAFeaturedImageSkipsTheHero() throws Exception {
        TestUtils.setupWeblogEntry("packing-list", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/entry/packing-list");

        assertTravelHead(body);
        assertTrue(body.contains("<h1 class=\"tg-entry-title\">"), body);
        assertFalse(body.contains("<figure class=\"tg-hero\">"),
                "no featured image means no empty hero figure:\n" + body);
    }

    // ------------------------------------------------------- shortcode blocks

    @Test
    void theStylesheetStylesTheTravelShortcodeBlocks() throws Exception {
        // The theme's whole reason to exist: [map], [faq] and [cta] must land
        // in a guide-book frame without the author writing any theme markup.
        String css = render("/" + HANDLE + "/page/travel-custom.css");

        assertTrue(css.contains("body.tg .travel-map"),
                "the map container must be sized by the theme:\n" + css);
        assertTrue(css.contains("body.tg .faq"),
                "the FAQ definition list must be styled:\n" + css);
        assertTrue(css.contains("body.tg .cta-card"),
                "the CTA card must be styled:\n" + css);
        assertFalse(css.contains("@font-face"),
                "the theme CSP declares no font-src, so web fonts must not appear:\n" + css);
        assertFalse(css.contains("http://"),
                "no remote CSS references -- script/style-src are 'self':\n" + css);
    }

    // ----------------------------------------------------- escaping (once)

    /**
     * Entry titles are stored pre-escaped by {@code EntryBean.copyTo} (see
     * {@code EntryBeanTest#copyToEscapesTheTitleButNotTheBody}) -- mirrored
     * here rather than driving the controller. {@code _day.vm} used to call
     * {@code $utils.escapeHTML($entry.title)} on top of that, double-encoding
     * ('&' became {@code &amp;amp;}); this pins the fix (bare {@code
     * $entry.title}) on both the card title and the permalink h1.
     */
    @Test
    void theEntryTitleEscapesExactlyOnce() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("sea-and-stone", weblog, user);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        // Stored form of "Sea & Stone" -- the transformation EntryBean.copyTo
        // applies at save time (see EntryBeanTest:212), not raw author input.
        managed.setTitle(StringEscapeUtils.escapeHtml4("Sea & Stone"));
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);

        String cardBody = render("/" + HANDLE + "/");
        assertTrue(cardBody.contains("Sea &amp; Stone"),
                "the card title must render escaped exactly once:\n" + cardBody);
        assertFalse(cardBody.contains("&amp;amp;"),
                "the stored-escaped title must not be double-encoded:\n" + cardBody);

        String permalinkBody = render("/" + HANDLE + "/entry/sea-and-stone");
        assertTrue(permalinkBody.contains("Sea &amp; Stone"),
                "the permalink h1 must render escaped exactly once:\n" + permalinkBody);
        assertFalse(permalinkBody.contains("&amp;amp;"),
                "the stored-escaped title must not be double-encoded:\n" + permalinkBody);
    }

    /**
     * {@code $model.weblog.name} comes back from {@code WeblogWrapper#getName}
     * already {@code escapeHtml4}'d (see {@code WeblogWrapper.java:95}); the
     * template used to wrap it in {@code $utils.escapeHTML} too, double-
     * encoding ('&' became {@code &amp;amp;}). Pins the fix (bare {@code
     * $model.weblog.name}) on the front page's tg-name and the permalink's.
     * {@code Weblog.setName} does not escape, so the '&' below is stored raw.
     */
    @Test
    void theWeblogNameEscapesExactlyOnce() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setName("Fjord & Field Guides");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        TestUtils.setupWeblogEntry("sound-and-stone", weblog, user);
        TestUtils.endSession(true);

        String frontBody = render("/" + HANDLE + "/");
        assertTrue(frontBody.contains("Fjord &amp; Field Guides"),
                "the weblog name must render escaped exactly once:\n" + frontBody);
        assertFalse(frontBody.contains("&amp;amp;"),
                "no reference may double-encode a value that is already "
                        + "wrapper-escaped:\n" + frontBody);

        String permalinkBody = render("/" + HANDLE + "/entry/sound-and-stone");
        assertTrue(permalinkBody.contains("Fjord &amp; Field Guides"),
                "the permalink's weblog name must render escaped exactly once:\n"
                        + permalinkBody);
        assertFalse(permalinkBody.contains("&amp;amp;"),
                "no reference may double-encode a value that is already "
                        + "wrapper-escaped:\n" + permalinkBody);
    }

    /**
     * The travel half of the alt-text fix {@code PortfolioThemeRenderingTest}
     * documents: {@code #showResponsiveImage} escapes its {@code $alt}
     * argument, so handing it the stored-escaped {@code $entry.title} made a
     * screen reader read "&amp;amp;" aloud. The image's own
     * {@code MediaFile.altText} now wins, and the title fallback is
     * unescaped first.
     */
    @Test
    void theCardAltTextPrefersTheImagesOwnDescription() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "alt-image");
        String imageId = image.getId();
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        MediaFile managedImage = mfMgr.getMediaFile(imageId);
        managedImage.setAltText("The ring road at dusk, rain & low cloud");
        mfMgr.updateMediaFile(TestUtils.getManagedWebsite(weblog), managedImage);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        entryWithFeaturedImage("ring-road", imageId);

        String body = render("/" + HANDLE + "/");

        assertTrue(body.contains("alt=\"The ring road at dusk, rain &amp; low cloud\""),
                "the image's own alt text must win over the entry title:\n" + body);
        assertFalse(body.contains("&amp;amp;"), body);
    }

    @Test
    void theCardAltTextFallsBackToAnUnescapedTitle() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "noalt-image");
        String imageId = image.getId();
        TestUtils.endSession(true);
        WeblogEntry entry = entryWithFeaturedImage("noalt-entry", imageId);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setTitle(StringEscapeUtils.escapeHtml4("Rock & Road"));
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/");

        assertTrue(body.contains("alt=\"Rock &amp; Road\""),
                "an image with no alt text falls back to the title, escaped "
                        + "exactly once:\n" + body);
        assertFalse(body.contains("&amp;amp;"), body);
    }

    // -------------------------------------------------------------- _page

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

    @Test
    void aPageRendersThroughTheTravelThemeInsteadOfTheFallback() throws Exception {
        savePage("about", "About This Guide", "Some guide content. [contact]");

        String body = render("/" + HANDLE + "/about");

        assertTravelHead(body);
        assertTrue(body.contains("class=\"tg-header\""),
                "the page must wear the travel header chrome:\n" + body);
        assertTrue(body.contains("<h1 class=\"tg-entry-title\">About This Guide</h1>"),
                "the page title must render as the entry title:\n" + body);
        assertTrue(body.contains("<div class=\"tg-entry-content\">"),
                "the page content must open the measured column:\n" + body);
        assertTrue(body.contains("audience-hp"),
                "showAudienceAssets must run in the head, whatever the page contains -- "
                        + "this alone does not prove the [contact] shortcode rendered:\n" + body);
        assertTrue(body.contains("contact-form-slot"),
                "the [contact] shortcode must have expanded to its placeholder div in the "
                        + "rendered body -- this is what actually proves the form renders:\n" + body);
        assertFalse(body.contains("<h1>About This Guide</h1>"),
                "the naked fallback template's unstyled h1 must not be what renders:\n" + body);
    }

    // -------------------------------------------------- document shell (a11y)

    /**
     * The document shell every travel page shares: a BCP-47 {@code lang} (the
     * stored locale is {@code en_US}, which is not a tag any user agent
     * parses -- see {@code WeblogWrapper#getLanguageTag}), a skip link as the
     * first focusable node, the {@code #main} target it points at, and exactly
     * one {@code <h1>} -- the site name. Before the 2026-08-20 sweep the shell
     * had none of the four.
     */
    @Test
    void theDocumentShellDeclaresItsLanguageAndOffersASkipLink() throws Exception {

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
        assertTrue(body.contains("<h1 class=\"tg-name\">"),
                "the site name must be the page's h1:\n" + body);
        assertEquals(1, countOf(body, "<h1"),
                "a page must have exactly one h1 -- the site name:\n" + body);
        // This fixture has no entries, so the same render also exercises the
        // zero-entry branch -- and Velocity is lenient, so an #if whose
        // reference failed to resolve would print as literal text here rather
        // than failing (JournalThemeRenderingTest pins the wording; this pins
        // that the branch evaluates at all in this theme).
        assertTrue(body.contains("class=\"tg-list-empty\""),
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
