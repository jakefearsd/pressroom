package com.wikantik.pressroom.ui.rendering;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
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
                .anonymousGet("/pressroom-ui/rendering/page", "/routeblog" + path);
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

    /**
     * Characterisation: passed as soon as TRASHED existed, because the
     * request path serves only PUBLISHED. Pinned because a trashed page that
     * is still served is the trash's worst failure.
     */
    @Test
    void aTrashedPageIs404AndItsBlogStillRenders() throws Exception {
        assertEquals(200, get("/about").getStatus());
        var pages = TestUtils.weblogger().getWeblogPageManager();
        pages.trashPage(pages.getPageBySlug(TestUtils.getManagedWebsite(weblog), "about"));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertEquals(404, get("/about").getStatus(), "a trashed page must not be readable");
        assertEquals(200, get("").getStatus());
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
        managed.setEditorTheme(com.wikantik.pressroom.pojos.WeblogTheme.CUSTOM);
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

    // ------------------------------------------------------- the front door

    private static final String PAGE_BODY = "<strong>about</strong>";
    private static final String POSTS_LINK = ">Posts</a>";

    private MockHttpServletResponse get(String path, String... params) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", "/routeblog" + path);
        for (int i = 0; i < params.length; i += 2) {
            request.setParameter(params[i], params[i + 1]);
        }
        return RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request);
    }

    private String pageId(String slug) throws Exception {
        String id = TestUtils.weblogger().getWeblogPageManager()
                .getPageBySlug(TestUtils.getManagedWebsite(weblog), slug).getId();
        TestUtils.endSession(true);
        return id;
    }

    /**
     * Names the front page straight on the weblog row, past the page
     * manager's rule (which never leaves a draft there): what a row changed
     * behind the app's back looks like to the request path.
     */
    private void frontPage(String pageId) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setFrontPageId(pageId);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private void post(String anchor, String title, boolean pinned) throws Exception {
        com.wikantik.pressroom.pojos.WeblogEntry entry = TestUtils.setupWeblogEntry(anchor,
                TestUtils.getManagedWebsite(weblog), TestUtils.getManagedUser(user));
        com.wikantik.pressroom.business.WeblogEntryManager mgr =
                TestUtils.weblogger().getWeblogEntryManager();
        com.wikantik.pressroom.pojos.WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setTitle(title);
        managed.setPinned(pinned);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private static String canonical(String body) {
        int at = body.indexOf("rel=\"canonical\" href=\"");
        assertTrue(at > 0, "no canonical link:\n" + body);
        int from = at + "rel=\"canonical\" href=\"".length();
        return body.substring(from, body.indexOf('"', from));
    }

    @Test
    void withAFrontPageTheRootRendersIt() throws Exception {
        post("listed-post", "LISTEDTITLE", false);
        frontPage(pageId("about"));

        MockHttpServletResponse response = get("/");

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains(PAGE_BODY), "the root shows the page:\n" + body);
        assertFalse(body.contains("LISTEDTITLE"), "not the post listing:\n" + body);
        assertTrue(canonical(body).endsWith("/routeblog/"), "its canonical is the root: " + canonical(body));
        assertEquals(canonical(body), canonical(get("/about").getContentAsString()),
                "its own slug address canonicalises to the root too");
    }

    @Test
    void withAFrontPagePostsListsWithTheNavLinkAndAPostsCanonical() throws Exception {
        post("listed-post", "LISTEDTITLE", false);
        frontPage(pageId("about"));

        String posts = get("/posts").getContentAsString();

        assertTrue(posts.contains("LISTEDTITLE"), "/posts is the listing:\n" + posts);
        assertTrue(canonical(posts).endsWith("/routeblog/posts"), canonical(posts));
        assertTrue(posts.contains("/routeblog/posts\" aria-current=\"page\">Posts</a>"),
                "the nav links the listing, as the current view:\n" + posts);
        String front = get("/").getContentAsString();
        assertTrue(front.contains("/routeblog/posts\">Posts</a>"), "on the front page too:\n" + front);
    }

    /**
     * One link to the listing, not two: with a front door the category row's leading item reads "Posts"
     * in place of "All", and the front page itself is the blog's name, not a nav item of its own.
     */
    @Test
    void withAFrontPageTheNavHasOnePostsLinkAndNoLinkForTheFrontPageItself() throws Exception {
        frontPage(pageId("about"));

        for (String path : java.util.List.of("/", "/posts")) {
            String body = get(path).getContentAsString();
            assertEquals(1, body.split(POSTS_LINK, -1).length - 1, path + " has one Posts link:\n" + body);
            assertFalse(body.contains(">All</a>"), path + " has no All beside it:\n" + body);
            assertFalse(body.contains("page-nav-item\"><a href=\"/pressroom/routeblog/\""),
                    path + " does not list the front page in the nav:\n" + body);
        }
    }

    /** Old listing links keep listing on a blog that gained a front door; their canonical is the /posts form. */
    @Test
    void withAFrontPageARootListingParameterStillLists() throws Exception {
        post("listed-post", "LISTEDTITLE", false);
        frontPage(pageId("about"));

        String paged = get("/", "page", "1").getContentAsString();
        assertFalse(paged.contains(PAGE_BODY), paged);
        assertTrue(canonical(paged).endsWith("/routeblog/posts?page=1"), canonical(paged));

        String dated = get("/", "date", "202611").getContentAsString();
        assertFalse(dated.contains(PAGE_BODY), dated);
        assertEquals(200, get("/", "date", "202611").getStatus());
    }

    /**
     * A front page id that does not name a published page of this blog lists at the root: never a 404.
     * A characterisation test of the fallback: it passed before the front door existed, as the root
     * always listed.
     */
    @Test
    void aDraftOrForeignFrontPageListsAtTheRoot() throws Exception {
        post("listed-post", "LISTEDTITLE", false);
        frontPage(pageId("draft-page"));

        MockHttpServletResponse draft = get("/");
        assertEquals(200, draft.getStatus());
        assertTrue(draft.getContentAsString().contains("LISTEDTITLE"), draft.getContentAsString());

        User other = TestUtils.setupUser("routeotheruser");
        Weblog otherBlog = TestUtils.setupWeblog("routeotherblog", other);
        TestUtils.endSession(true);
        try {
            WeblogPage foreign = new WeblogPage();
            foreign.setWeblog(TestUtils.getManagedWebsite(otherBlog));
            foreign.setSlug("theirs");
            foreign.setTitle("Theirs");
            foreign.setContent("Not this blog's");
            foreign.setStatus(WeblogPage.PubStatus.PUBLISHED);
            TestUtils.weblogger().getWeblogPageManager().savePage(foreign);
            TestUtils.weblogger().flush();
            TestUtils.endSession(true);
            frontPage(foreign.getId());

            MockHttpServletResponse response = get("/");
            assertEquals(200, response.getStatus());
            assertTrue(response.getContentAsString().contains("LISTEDTITLE"), response.getContentAsString());
            assertFalse(response.getContentAsString().contains("Not this blog's"));
        } finally {
            frontPage(null);
            TestUtils.teardownWeblog(otherBlog.getId());
            TestUtils.teardownUser(other.getUserName());
            TestUtils.endSession(true);
        }
    }

    /** No front door: /posts is the same page as the root, canonical at the root, and no "Posts" link. */
    @Test
    void withoutAFrontDoorPostsIsTheRootsListing() throws Exception {
        post("listed-post", "LISTEDTITLE", false);

        String root = get("/").getContentAsString();
        String posts = get("/posts").getContentAsString();

        assertTrue(root.contains("LISTEDTITLE"), root);
        assertEquals(root, posts, "the same listing, byte for byte");
        assertTrue(canonical(posts).endsWith("/routeblog/"), canonical(posts));
        assertFalse(root.contains(POSTS_LINK), "no front door, no Posts link:\n" + root);
    }

    @Test
    void postsWithAPageNumberAndACategoryLists() throws Exception {
        post("listed-post", "LISTEDTITLE", false);
        frontPage(pageId("about"));
        String category = TestUtils.getManagedWebsite(weblog).getWeblogCategories().iterator().next().getName();
        TestUtils.endSession(true);

        assertTrue(get("/posts", "cat", category).getContentAsString().contains("LISTEDTITLE"));
        assertEquals(200, get("/posts", "page", "1").getStatus());
    }

    /** /posts is the home listing: the blog's pinned post still heads page one. */
    @Test
    void aPinnedPostHeadsPageOneOfPosts() throws Exception {
        post("fresh-post", "FRESHTITLE", false);
        post("pinned-post", "PINNEDTITLE", true);
        frontPage(pageId("about"));

        String body = get("/posts").getContentAsString();

        assertTrue(body.contains("class=\"entry-pinned\">Pinned<"), body);
        assertTrue(body.indexOf("PINNEDTITLE") < body.indexOf("FRESHTITLE"), body);
    }
}
