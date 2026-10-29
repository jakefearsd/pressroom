package com.wikantik.pressroom.ui.rendering;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import com.wikantik.pressroom.ui.rendering.velocity.InvalidReferenceRecorder;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared static-page fallback ({@code templates/weblog/page.vm}) titles
 * a page the way the bundled themes do: "Page — Weblog", and an author's
 * meta title alone, as the whole title, since authors write a complete one
 * there. Reached through a CUSTOM theme with no templates of its own, so no
 * {@code _page} override can answer instead.
 */
class FallbackPageTitleTest {

    /**
     * What the shipped templates leave unresolved on these renders, each with its reason.
     * A "KNOWN DEFECT" entry is a bug recorded here, not a rationale.
     */
    private static final Set<String> EXPECTED_REFERENCES = Set.of(
            "templates/weblog/page.vm|standard: $model.page.metaTitle", // null when the page has no meta title; tested with $utils.isNotEmpty
            "templates/weblog/page.vm|standard: $model.weblog.stylesheet", // KNOWN DEFECT: WeblogWrapper.getStylesheet() returns null for a custom theme with no stylesheet and templates/weblog/page.vm line 33 emits it unguarded, so the href prints as the literal text $model.weblog.stylesheet
            "weblog.vm: $model.pageOgImage", // null when the page has no OG image; assigned to $seoImage, which is tested with #if
            "weblog.vm: $model.tags", // null when the view is not a tag view; tested with #if/#elseif
            "weblog.vm: $model.weblogCategory", // null when the view is not a category view; tested with #if/#elseif
            "weblog.vm: $seoBusinessJson", // null when the weblog has no business profile; tested with #if
            "weblog.vm: $seoImage", // null when nothing supplies an image; tested with #if
            "weblog.vm: $seoPage.metaTitle", // null when the page has no meta title; tested with $utils.isNotEmpty
            "weblog.vm: $seoPage.searchDescription", // null when the page has no search description; tested with $utils.isNotEmpty
            "weblog.vm: $utils.businessJsonLd()", // returns null when the weblog has no business profile; the result is tested with #if($seoBusinessJson)
            "weblog.vm: $weblog", // KNOWN DEFECT: showContentAssets takes no argument, so $weblog in its $config.getColophon($weblog) call is never bound; getColophon(null) falls back to the site colophon, which is why only the version (the same for every blog) is read and nothing visibly breaks
            "weblog.vm: $weblog.analyticsSiteId", // null unless analytics is configured; tested with $utils.isNotEmpty
            "weblog.vm: seoBusinessJson", // #set of a null business JSON-LD; tested with #if
            "weblog.vm: seoImage" // #set of a null image; tested with #if
    );

    @BeforeAll
    static void clearInvalidReferenceRecorder() {
        InvalidReferenceRecorder.clear();
    }

    @AfterAll
    static void onlyTheExpectedReferencesWereUnresolved() {
        StrictReferences.assertRecordedExactly("FallbackPageTitleTest", EXPECTED_REFERENCES);
    }

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("fallbacktitleuser");
        weblog = TestUtils.setupWeblog("fallbacktitleblog", user);
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(WeblogTheme.CUSTOM);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.weblogger().getWeblogPageManager().removePages(TestUtils.getManagedWebsite(weblog));
        TestUtils.endSession(true);
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private void savePage(String slug, String title, String metaTitle) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setMetaTitle(metaTitle);
        page.setContent("Body of " + slug);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private String render(String slug) throws Exception {
        MockHttpServletResponse response = RenderingTestSupport.execute(RenderingTestSupport.pageServlet(),
                RenderingTestSupport.anonymousGet("/roller-ui/rendering/page", "/fallbacktitleblog/" + slug));
        assertEquals(200, response.getStatus());
        return response.getContentAsString();
    }

    @Test
    void aPageIsTitledPageThenWeblogWithAnEmDash() throws Exception {
        savePage("about", "About & Contact", null);

        String body = render("about");

        assertTrue(body.contains("<h1>About &amp; Contact</h1>"), "this is the fallback template:\n" + body);
        assertTrue(body.contains("<title>About &amp; Contact — Test Weblog</title>"), body);
    }

    @Test
    void aMetaTitleIsTheWholeTitle() throws Exception {
        savePage("rates", "Rates", "Rates & seasons in Lisbon");

        String body = render("rates");

        assertTrue(body.contains("<title>Rates &amp; seasons in Lisbon</title>"), body);
    }
}
