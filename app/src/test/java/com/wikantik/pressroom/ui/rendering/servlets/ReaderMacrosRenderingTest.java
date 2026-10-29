package com.wikantik.pressroom.ui.rendering.servlets;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a reader meets in the shared macros of {@code WEB-INF/velocity/weblog.vm}:
 * the feed names a feed reader shows, the share card on a category page, the
 * current item of the category list, the comment form's label and its
 * no-sign-in sentence, the contact and subscribe forms' words and status
 * line, the search forms, the video and map placeholders. Rendered through
 * the real PageServlet where the output decides it; read from the source
 * where the behaviour lives in a script no unit test runs.
 */
class ReaderMacrosRenderingTest {

    private static final String HANDLE = "readermacroblog";
    private static final Path WEBLOG_VM = Path.of("src/main/webapp/WEB-INF/velocity/weblog.vm");

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("readermacrouser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static String render(String pathInfo) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "page must render for " + pathInfo);
        return response.getContentAsString();
    }

    private static String source() throws IOException {
        return Files.readString(WEBLOG_VM, StandardCharsets.UTF_8);
    }

    private static String macro(String name) throws IOException {
        String source = source();
        int start = source.indexOf("#macro(" + name);
        assertTrue(start >= 0, "no macro " + name);
        return source.substring(start, source.indexOf("\n#end", start));
    }

    /**
     * The custom-theme meta-description macro (kept for older custom themes)
     * printed the weblog's "about" and an entry's search description bare
     * into an attribute. Both are text -- the wrapper's about is reduced to
     * text, quotes intact -- so one quote closed the attribute.
     */
    @Test
    void theLegacyMetaDescriptionMacroEscapesWhatItPrints() throws IOException {
        String body = macro("showMetaDescription");

        assertTrue(body.contains("content=\"$utils.escapeHTML($model.weblog.about)\""), body);
        assertTrue(body.contains("content=\"$utils.escapeHTML($model.weblogEntry.searchDescription)\""), body);
        assertFalse(body.contains("content=\"$model."), body);
    }

    /** Finding 24: a feed reader names the subscription after the feed's title. */
    @Test
    void theMainFeedIsAdvertisedUnderTheWeblogsName() throws Exception {
        TestUtils.setupWeblogEntry("feed-title-entry", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/");

        assertTrue(body.contains("type=\"application/atom+xml\" title=\"Test Weblog\""),
                "the main feed's title is the weblog's name:\n" + body);
        assertFalse(body.contains("Recent Entries (Atom)"), body);
    }

    /**
     * Findings 35 and 18: a category page's share card names the category, and
     * the category list marks it as the current page while "All" is not.
     */
    @Test
    void aCategoryPageNamesItsCategoryAndMarksItCurrent() throws Exception {
        TestUtils.setupWeblogEntry("category-view-entry", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/category/General");

        assertTrue(body.contains("<meta property=\"og:title\" content=\"General — Test Weblog\">"),
                "a category page's og:title names the category:\n" + body);
        assertTrue(Pattern.compile("<a href=\"[^\"]*/category/General\" aria-current=\"page\">General</a>")
                        .matcher(body).find(),
                "the current category carries aria-current:\n" + body);
        assertFalse(Pattern.compile("aria-current=\"page\">All<").matcher(body).find(),
                "\"All\" is not the current page on a category view:\n" + body);
    }

    @Test
    void theHomePageMarksAllAsCurrentAndKeepsTheWeblogsOgTitle() throws Exception {
        TestUtils.setupWeblogEntry("home-view-entry", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/");

        assertTrue(body.contains("aria-current=\"page\">All</a>"),
                "\"All\" is the current page on the plain home view:\n" + body);
        assertTrue(body.contains("<meta property=\"og:title\" content=\"Test Weblog\">"), body);
    }

    /** Finding 18: a date archive shows less than all, so "All" is not its current page. */
    @Test
    void aDateArchiveDoesNotMarkAllAsCurrent() throws Exception {
        TestUtils.setupWeblogEntry("date-view-entry", weblog, user);
        TestUtils.endSession(true);
        String month = new java.text.SimpleDateFormat("yyyyMM", java.util.Locale.ROOT).format(new java.util.Date());

        String body = render("/" + HANDLE + "/date/" + month);

        assertFalse(Pattern.compile("aria-current=\"page\">All<").matcher(body).find(),
                "\"All\" is not the current page on a date archive:\n" + body);
    }

    /** Findings 31 and 32: the comment form's visible label and the no-sign-in sentence. */
    @Test
    void theCommentsSlotCarriesTheLabelAndTheNoSignInSentence() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setCommentsEnabled(true);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        TestUtils.setupWeblogEntry("comment-slot-entry", weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + HANDLE + "/entry/comment-slot-entry");

        assertTrue(body.contains("data-msg-label=\"Your comment\""), body);
        assertTrue(body.contains("data-msg-no-sign-in=\"Commenting is not available yet.\""), body);
    }

    @Test
    void theCommentScriptLabelsTheTextareaAndExplainsAMissingSignIn() throws IOException {
        String audience = macro("showAudienceAssets");
        assertTrue(audience.contains("label.htmlFor = textarea.id"),
                "the textarea has a visible label tied to it");
        assertFalse(audience.contains("textarea.setAttribute('aria-label'"),
                "the label names the field, not an aria-label copied from the button");
        assertTrue(audience.contains("element('button', 'comment-submit', text.post)"),
                "the button carries a class the shared style block styles");
        assertTrue(audience.contains("link ? null : text.noSignIn"),
                "no sign-in link still gets a sentence");
    }

    /** Finding 16: the contact and subscribe forms announce their status and speak the bundle. */
    @Test
    void theAudienceFormsAnnounceTheirStatusAndNameTheThrottle() throws Exception {
        String audience = macro("showAudienceAssets");
        Matcher status = Pattern.compile("<p class=\"audience-message\" role=\"status\" hidden>").matcher(audience);
        int statusLines = 0;
        while (status.find()) {
            statusLines++;
        }
        assertEquals(2, statusLines, "both forms' message lines are role=status:\n" + audience);
        assertTrue(audience.contains("response.status === 429") && audience.contains("words.rateLimited"),
                "a 429 says what it is");
        assertFalse(audience.contains("'Sorry, that did not work"), "no hard-coded English failure");
        assertFalse(audience.contains("'<label>Name "), "no hard-coded English labels");

        String body = render("/" + HANDLE + "/");
        assertTrue(body.contains("name: \"Name\"") && body.contains("rateLimited: \"Too many attempts"),
                "the words come from the bundle:\n" + body);
    }

    /** Finding 28: the search forms carry no alert(), no inline style and no line break. */
    @Test
    void theSearchFormsUseRequiredAndNoInlineStyle() throws IOException {
        String form = macro("showWeblogSearchForm");
        String again = macro("showWeblogSearchAgainForm");
        assertFalse(form.contains("alert("), form);
        assertFalse(form.contains("onsubmit"), form);
        assertTrue(form.contains(" required "), form);
        for (String body : new String[] {form, again}) {
            assertFalse(body.contains("style=\""), body);
            assertFalse(body.contains("<br"), body);
        }
    }

    /** Finding 7: the hit line is shown only when something matched, and reads as prose. */
    @Test
    void theHitLineIsShownOnlyWhenSomethingMatched() throws Exception {
        assertTrue(macro("showWeblogSearchAgainForm").contains("#if($model.hits > 0)"),
                "a zero-hit search must not print a hit line above the empty state");
        Properties bundle = new Properties();
        try (var in = Files.newBufferedReader(
                Path.of("src/main/resources/ApplicationResources.properties"), StandardCharsets.UTF_8)) {
            bundle.load(in);
        }
        assertEquals("1 entry matches \"lisbon\"", java.text.MessageFormat.format(
                bundle.getProperty("macro.weblog.searchhits"), "lisbon", 1));
        assertEquals("42 entries match \"lisbon\"", java.text.MessageFormat.format(
                bundle.getProperty("macro.weblog.searchhits"), "lisbon", 42));
        assertEquals("Showing 1–10 of 42", java.text.MessageFormat.format(
                bundle.getProperty("searchSummary.text"), 1, 10, 42));
        assertFalse(macro("showNextPrevSearchControl").contains("getHomeLink"),
                "search results have no \"Main\" link");
    }

    /** Finding 4: a video placeholder is a keyboard-reachable, named button. */
    @Test
    void aVideoPlaceholderIsAKeyboardButton() throws IOException {
        String embeds = macro("showEmbedAssets");
        assertTrue(embeds.contains("embed.setAttribute('role', 'button')"), embeds);
        assertTrue(embeds.contains("embed.setAttribute('tabindex', '0')"), embeds);
        assertTrue(embeds.contains("embed.setAttribute('aria-label'"), embeds);
        assertTrue(embeds.contains("e.key === 'Enter' || e.key === ' '"), embeds);
    }

    /** Finding 15, the script half: the map is a named region and its pin list stays for screen readers. */
    @Test
    void aMapIsANamedRegion() throws IOException {
        String maps = macro("showMapAssets");
        assertTrue(maps.contains("el.setAttribute('role', 'region')"), maps);
        assertTrue(maps.contains("el.setAttribute('aria-label'"), maps);
        assertTrue(maps.contains(".travel-map-pins.is-mapped"), "the pin list is hidden visually, not removed");
    }
}
