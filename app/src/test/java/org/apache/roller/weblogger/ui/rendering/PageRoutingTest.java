package org.apache.roller.weblogger.ui.rendering;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.apache.roller.weblogger.ui.rendering.servlets.RenderingTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bare path segment resolves to a page. Before this, /handle/about threw
 * InvalidRequestException("invalid index page") -- a single element was only
 * ever legal for /tags.
 */
class PageRoutingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();

        user = TestUtils.setupUser("routeuser");
        weblog = TestUtils.setupWeblog("routeblog", user);
        TestUtils.endSession(true);

        savePage("about", "About Us", WeblogPage.PubStatus.PUBLISHED);
        savePage("draft-page", "Not Yet", WeblogPage.PubStatus.DRAFT);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private void savePage(String slug, String title, WeblogPage.PubStatus status)
            throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent("Body of **" + slug + "**");
        page.setStatus(status);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private MockHttpServletResponse get(String path) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/routeblog" + path);
        return RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request);
    }

    @Test
    void aPublishedPageRendersAtItsBareSlug() throws Exception {
        MockHttpServletResponse response = get("/about");

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("About Us"),
                response.getContentAsString());
    }

    @Test
    void thePageContentIsRenderedAsMarkdown() throws Exception {
        assertTrue(get("/about").getContentAsString().contains("<strong>about</strong>"));
    }

    @Test
    void aDraftPageIs404ToAnAnonymousReader() throws Exception {
        assertEquals(404, get("/draft-page").getStatus(),
                "an unpublished page must not be readable");
    }

    @Test
    void anUnknownSlugIs404() throws Exception {
        assertEquals(404, get("/no-such-page").getStatus());
    }

    @Test
    void theWeblogHomePageStillWorks() throws Exception {
        assertEquals(200, get("").getStatus());
    }

    // ------------------------------------------- the fallback page template

    /**
     * Switches the weblog to a custom theme with no templates of its own, so
     * it has no {@code _page} -- which is what makes
     * {@code WEB-INF/velocity/templates/weblog/page.vm}, the
     * {@code StaticThemeTemplate} fallback every unthemed page falls through
     * to, the thing that actually renders. Every bundled theme ships a
     * {@code _page} (frontpage's arrived last, in the 0.3.0 fit-and-finish
     * pass; until then this used frontpage), so a custom theme -- the case
     * the fallback exists for -- is the only route left. Nothing else in the
     * suite renders it, which is how it kept a head the other reader
     * templates had all outgrown.
     */
    private void useAThemeWithNoPageTemplate() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(org.apache.roller.weblogger.pojos.WeblogTheme.CUSTOM);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    /**
     * The fallback's nav held bare {@code <li>}s with no list around them
     * (invalid), it had no favicon, and its policy named no font-src, which
     * the shared preview shell's has: default-src 'none' refuses a theme
     * stylesheet's same-origin web font.
     */
    @Test
    void theFallbackPageTemplateHasAValidNavAFaviconAndAllowsItsFonts() throws Exception {
        useAThemeWithNoPageTemplate();

        String body = get("/about").getContentAsString();

        assertTrue(body.contains("<h1>About Us</h1>"), "the fallback template must render:\n" + body);
        assertTrue(body.contains("font-src 'self'"), "the fallback's CSP allows same-origin fonts:\n" + body);
        assertTrue(body.contains("rel=\"icon\""), "the fallback carries the site favicon:\n" + body);
        int nav = body.indexOf("<nav");
        assertTrue(nav > 0, body);
        String navBlock = body.substring(nav, body.indexOf("</nav>", nav));
        assertTrue(navBlock.contains("<ul"), "the nav's list items sit in a list:\n" + navBlock);
    }

    @Test
    void theFallbackPageTemplateAdvertisesTheFeedAndShipsItsAssetsInTheHead()
            throws Exception {
        useAThemeWithNoPageTemplate();

        String body = get("/about").getContentAsString();

        assertTrue(body.contains("<h1>About Us</h1>"),
                "this must be the naked fallback template, not a theme _page:\n" + body);
        int head = body.indexOf("</head>");
        assertTrue(head > 0, "the fallback page must have a head:\n" + body);
        String headBlock = body.substring(0, head);

        assertTrue(headBlock.contains("rel=\"alternate\" type=\"application/atom+xml\""),
                "the fallback page was the only reader template with no feed "
                        + "discovery link:\n" + body);
        assertTrue(headBlock.contains("/webjars/photoswipe/"),
                "#showGalleryAssets belongs in the head, beside the grid styles:\n" + body);
        assertTrue(headBlock.contains("/webjars/leaflet/"),
                "#showMapAssets belongs in the head:\n" + body);
        assertTrue(headBlock.contains(".video-embed"),
                "#showEmbedAssets belongs in the head:\n" + body);
        assertTrue(headBlock.contains("audience-hp"),
                "#showAudienceAssets belongs in the head:\n" + body);
    }

    /**
     * {@code WeblogWrapper#getName} is pre-escaped
     * ({@code StringEscapeUtils.escapeHtml4}, WeblogWrapper.java:96); the
     * fallback template wrapped it in {@code $utils.escapeHTML} again in
     * both the {@code <title>} and the header link, so an '&' in a weblog
     * name rendered as {@code &amp;amp;}. Every theme template already
     * emits it bare.
     */
    @Test
    void theFallbackPageTemplateEscapesTheWeblogNameExactlyOnce() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setName("Rock & Roll Weblog");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        useAThemeWithNoPageTemplate();

        String body = get("/about").getContentAsString();

        assertTrue(body.contains("Rock &amp; Roll Weblog"),
                "the weblog name must render escaped exactly once:\n" + body);
        assertFalse(body.contains("&amp;amp;"),
                "a pre-escaped wrapper value must not be escaped again:\n" + body);
    }

    @Test
    void aReservedContextStillRoutesToItsOwnView() throws Exception {
        assertFalse(get("/tags").getStatus() == 404,
                "/tags must keep resolving to the tags view, not a page lookup");
    }
}
