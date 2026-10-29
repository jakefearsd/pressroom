package com.wikantik.pressroom.ui.rendering;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.business.jpa.QueryRecorder;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The public-URL front door: /roller/<handle>/... → rendering servlet forwards. */
class WeblogRequestMapperTest {

    private User user;
    private Weblog weblog;
    private WeblogRequestMapper mapper;
    private final Map<String, String> originalProperties = new LinkedHashMap<>();

    /**
     * A provider over the suite's real, bootstrapped tier: the mapper is
     * constructed with it and the facade (DI wave, plan Task 6b) instead of
     * locating them. (The test side still reaches the tier through the static
     * until plan Task 20.)
     */
    private static WebloggerProvider bootstrappedProvider() {
        Weblogger tier = TestUtils.weblogger();
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(tier);
        return provider;
    }

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("mapperuser");
        weblog = TestUtils.setupWeblog("mapperblog", user);
        TestUtils.endSession(true);
        mapper = new WeblogRequestMapper(bootstrappedProvider(), TestUtils.weblogger());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (!originalProperties.isEmpty()) {
            PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
            Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
            originalProperties.forEach((name, value) -> config.get(name).setValue(value));
            pmgr.saveProperties(config);
            TestUtils.weblogger().flush();
            TestUtils.endSession(true);
        }
        Weblog site = TestUtils.weblogger().getWeblogManager().getSiteSpace();
        if (site != null) {
            TestUtils.weblogger().getWeblogManager().removeWeblog(site);
            TestUtils.endSession(true);
        }
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
        // The tier's VirtualHostRegistry is a JVM-wide cache -- a custom domain
        // set by one test must not leak into the next.
        TestUtils.weblogger().getVirtualHostRegistry().invalidate();
    }

    private MockHttpServletRequest publicUrl(String method, String uriAfterContext) {
        return publicUrlAt("/roller", method, uriAfterContext);
    }

    /**
     * The same request, deployed under an arbitrary context path. The context
     * path is a deployment detail -- Roller must be correct both at the root
     * ({@code ""}, the shipped default) and under a prefix a reverse proxy or
     * a servlet container chose -- so every url the mapper *emits* has to be
     * built against the context it was actually reached through, never
     * assumed.
     */
    private MockHttpServletRequest publicUrlAt(String contextPath, String method,
                                               String uriAfterContext) {
        MockHttpServletRequest request =
                new MockHttpServletRequest(method, contextPath + uriAfterContext);
        request.setContextPath(contextPath);
        return request;
    }

    @Test
    void theSiteSpaceIsNotAWeblogToTheMapper() throws Exception {
        Weblog site = TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(user.getUserName()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        try {
            for (String path : new String[] {"/site-space/", "/site-space/about", "/site-space"}) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                assertFalse(mapper.handleRequest(publicUrl("GET", path), response), path);
                assertNull(response.getForwardedUrl(), path);
            }
        } finally {
            TestUtils.weblogger().getWeblogManager()
                    .removeWeblog(TestUtils.weblogger().getWeblogManager().getSiteSpace());
            TestUtils.endSession(true);
        }
    }

    @Test
    void weblogHomeForwardsToPageServlet() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean handled = mapper.handleRequest(request, response);

        assertTrue(handled);
        assertEquals("/roller-ui/rendering/page/mapperblog", response.getForwardedUrl());
    }

    @Test
    void permalinkForwardsToPageServlet() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/entry/my-post");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/entry/my-post",
                response.getForwardedUrl());
    }

    @Test
    void feedUrlForwardsToFeedServlet() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/feed/entries/rss");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/feed/mapperblog/entries/rss",
                response.getForwardedUrl());
    }

    @Test
    void searchUrlForwardsToSearchServlet() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/search");
        request.setParameter("q", "term");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/search/mapperblog", response.getForwardedUrl());
    }

    /**
     * The redirect target is sent to the browser, not dispatched internally,
     * so it must carry the context path. A leading-slash location is resolved
     * by the container against the SERVER root, not the application root
     * (Servlet 6.1, {@code sendRedirect}) -- so dropping the context sends a
     * reader deployed at /roller to /mapperblog/, which 404s.
     */
    @Test
    void missingTrailingSlashRedirects() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertNotNull(response.getRedirectedUrl(), "must redirect to the canonical slash form");
        assertEquals("/roller/mapperblog/", response.getRedirectedUrl());
    }

    /** The same redirect at the root context, where there is no prefix to add. */
    @Test
    void theTrailingSlashRedirectIsBareAtTheRootContext() throws Exception {
        MockHttpServletRequest request = publicUrlAt("", "GET", "/mapperblog");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/mapperblog/", response.getRedirectedUrl());
    }

    /**
     * A weblog url at the root context still reaches the rendering servlet.
     * Forwards are container-internal and therefore context-RELATIVE, which is
     * why these targets carry no prefix at either context -- the opposite
     * convention from the redirect above, and the reason the two cannot share
     * one url builder.
     */
    @Test
    void permalinkForwardsToPageServletAtTheRootContext() throws Exception {
        MockHttpServletRequest request = publicUrlAt("", "GET", "/mapperblog/entry/my-post");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/entry/my-post",
                response.getForwardedUrl());
    }

    @Test
    void unknownHandleIsNotHandled() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/nosuchblog/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(request, response),
                "an unknown handle must fall through to the next filter");
        assertNull(response.getForwardedUrl());
    }

    // ------------------------------------------- what is NOT a weblog handle

    /**
     * The protected-URL list is what stops an application path being mistaken
     * for a weblog. Every name in {@code rendering.weblogMapper.pressroomProtectedUrls}
     * is a path this mapper must decline so the real servlet behind it runs --
     * and a weblog registered under one of those handles must not be able to
     * shadow it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"roller-ui", "images", "theme", "themes", "index.jsp",
            "favicon.svg", "robots.txt", "sitemap.xml", "share", "webjars",
            "page", "flavor", "rss", "atom", "language", "search", "comments", "resource"})
    void protectedPathsAreDeclinedSoTheirOwnServletsRun(String reserved) throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/" + reserved + "/anything");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(request, response),
                reserved + " is a reserved application path, not a weblog handle");
        assertNull(response.getForwardedUrl());
    }

    @Test
    void theBareContextRootIsNotAWeblog() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(request, response),
                "the site root belongs to the front page, not to a weblog lookup");
    }

    // --------------------------------------------------------------- locales

    /**
     * A locale segment is optional and sits before the context. Misreading it
     * as the context would route /blog/en/entry/x to a page named "en".
     */
    @Test
    void aLocaleSegmentIsRecognisedAndPassedThrough() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/en/entry/my-post");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/en/entry/my-post",
                response.getForwardedUrl());
    }

    /**
     * With no context after it, a locale still needs the trailing-slash
     * redirect -- /blog/en and /blog/en/ are different urls.
     */
    @Test
    void aLocaleWithNoContextRedirectsForTheTrailingSlash() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/en");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller/mapperblog/en/", response.getRedirectedUrl());
    }

    /**
     * A path segment that merely looks like a locale must not be swallowed as
     * one, or a page named "entry" would become unreachable.
     */
    @Test
    void aNonLocaleFirstSegmentIsTreatedAsTheContext() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/category/travel");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/category/travel",
                response.getForwardedUrl());
    }

    /**
     * Extra path segments past the context are reassembled rather than
     * dropped: a category can be nested.
     */
    @Test
    void extraPathSegmentsAreReassembledOntoTheForward() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/category/travel/spain");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/category/travel/spain",
                response.getForwardedUrl());
    }

    // ------------------------------------------------- the trailing-slash rules

    /**
     * The query string has to survive the trailing-slash redirect, or a link
     * to /blog?q=x loses its parameters on the way to /blog/.
     */
    @Test
    void theTrailingSlashRedirectKeepsTheQueryString() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog");
        request.setQueryString("page=2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller/mapperblog/?page=2", response.getRedirectedUrl());
    }

    /**
     * Past the weblog root the rule inverts: a trailing slash on a permalink is
     * a different url from the permalink, and only one of them may exist.
     */
    @Test
    void aTrailingSlashOnAPermalinkIsNotFound() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/entry/my-post/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response),
                "the mapper answers this itself rather than declining it");
        assertEquals(404, response.getStatus());
    }

    /**
     * Tags invert it again, and in both directions: the index wants the slash,
     * a tag query must not have one.
     */
    @Test
    void theTagsIndexRequiresItsTrailingSlash() throws Exception {
        MockHttpServletResponse withSlash = new MockHttpServletResponse();
        assertTrue(mapper.handleRequest(publicUrl("GET", "/mapperblog/tags/"), withSlash));
        assertEquals("/roller-ui/rendering/page/mapperblog/tags", withSlash.getForwardedUrl());

        MockHttpServletResponse without = new MockHttpServletResponse();
        assertTrue(mapper.handleRequest(publicUrl("GET", "/mapperblog/tags"), without));
        assertEquals(404, without.getStatus(),
                "the tags index without its slash is not a url this site serves");
    }

    @Test
    void aTagQueryMustNotCarryATrailingSlash() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(mapper.handleRequest(publicUrl("GET", "/mapperblog/tags/spain+food/"), response));
        assertEquals(404, response.getStatus());
    }

    // --------------------------------------------------- static-page slugs

    /**
     * A single unrecognised segment is a page-slug candidate
     * (/<handle>/<slug>): every reserved word the mapper actually knows
     * about (page/entry/date/category/tags/feed/resource/mediaresource/
     * search) is handled by name above, so anything else that reaches the
     * default branch with no further path data must be forwarded to
     * PageServlet, which resolves the slug and 404s itself when it does not
     * name a published page (see WeblogPageRequest/WeblogPageManager). Before
     * this, the mapper declined the request outright and a published static
     * page never became reachable.
     */
    @Test
    void anUnknownSingleSegmentForwardsToPageServletAsAPageSlugCandidate() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/about");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/about", response.getForwardedUrl());
    }

    /**
     * The locale segment, when present, must survive onto the forwarded
     * page-slug url exactly as it does for every other context -- the
     * page-slug branch builds its own forward url from scratch rather than
     * reusing the locale-aware builder above it, so it has to repeat the
     * locale handling, not just the handle.
     */
    @Test
    void anUnknownSingleSegmentWithALocaleCarriesTheLocaleOntoTheForward() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/en/about");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/en/about",
                response.getForwardedUrl());
    }

    /**
     * A second path segment is never a page slug -- WeblogPageRequest only
     * treats a single remaining path element as one -- so this must stay
     * declined exactly as it was before the page-slug forward existed.
     */
    @Test
    void anUnknownTwoSegmentPathStaysDeclined() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/about/extra");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(request, response),
                "a second path segment past an unknown context is not a page slug");
        assertNull(response.getForwardedUrl());
    }

    /**
     * A known context (page/entry/date/category/tags/feed/resource/
     * mediaresource/search) must keep forwarding exactly as before -- the
     * page-slug fallback only ever fires from the switch's default branch.
     */
    @Test
    void aKnownContextStillForwardsAsBefore() throws Exception {
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/date/20260101");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/date/20260101",
                response.getForwardedUrl());
    }

    // ------------------------------------------------------------ POST rules

    /**
     * POST used to be routed here only for comment submission: a permalink
     * carrying a "content" param forwarded to the comment servlet. That
     * servlet is gone with the comment subsystem, so a POST is declined
     * unconditionally now, whether or not it carries a content parameter and
     * whether or not it targets a permalink -- there is nothing left in the
     * public url space for this mapper to route a POST to.
     */
    @Test
    void aPostToAPermalinkIsDeclinedEvenWithAContentParameter() throws Exception {
        MockHttpServletRequest request = publicUrl("POST", "/mapperblog/entry/my-post");
        request.setParameter("content", "a comment");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(request, response),
                "comment-post forwarding is gone with the comment servlet");
        assertNull(response.getForwardedUrl());
    }

    @Test
    void aPostToAPermalinkWithoutContentIsDeclined() throws Exception {
        MockHttpServletRequest request = publicUrl("POST", "/mapperblog/entry/my-post");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(request, response));
        assertNull(response.getForwardedUrl());
    }

    @Test
    void aPostSomewhereOtherThanAPermalinkIsDeclined() throws Exception {
        MockHttpServletRequest request = publicUrl("POST", "/mapperblog/category/travel");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(request, response),
                "posting into the public url space is not something this mapper routes");
        assertNull(response.getForwardedUrl());
    }

    // ------------------------------------------------- virtual hosts

    private MockHttpServletRequest vhostUrl(String method, String uri) {
        MockHttpServletRequest request = publicUrlAt("", method, uri);
        request.addHeader("Host", "vhost.example.com");
        request.setServerName("vhost.example.com");
        return request;
    }

    /**
     * On a custom domain the WHOLE path is weblog-relative: there is no handle
     * segment, because the host supplied it.
     */
    @Test
    void aPermalinkOnACustomDomainForwardsToThePageServlet() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(vhostUrl("GET", "/entry/my-post"), response));
        assertEquals("/roller-ui/rendering/page/mapperblog/entry/my-post",
                response.getForwardedUrl());
    }

    @Test
    void theWeblogHomeOnACustomDomainForwardsToThePageServlet() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(vhostUrl("GET", "/"), response));
        assertEquals("/roller-ui/rendering/page/mapperblog", response.getForwardedUrl());
    }

    /**
     * The point of the feature: the first path segment is NOT a handle on a
     * custom domain, so a segment that happens to name another weblog is a page
     * slug on THIS one, not a route to that other weblog.
     */
    @Test
    void aSegmentNamingAnotherWeblogIsAPageSlugOnACustomDomain() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // No trailing slash, matching the non-vhost sibling test
        // (anUnknownSingleSegmentForwardsToPageServletAsAPageSlugCandidate,
        // which uses "/mapperblog/about" rather than "/mapperblog/about/") --
        // a single content segment PLUS a trailing slash is exactly the shape
        // aTrailingSlashOnAPermalinkIsNotFound covers, and is a 404 there for
        // the identical reason it would be here: this mapper enforces the
        // no-trailing-slash rule identically once there is a content segment,
        // vhost or not (see the "Host-first resolution" comment above).
        assertTrue(mapper.handleRequest(vhostUrl("GET", "/someotherblog"), response));
        assertEquals("/roller-ui/rendering/page/mapperblog/someotherblog",
                response.getForwardedUrl());
    }

    /** An unclaimed host falls through to today's path-segment resolution. */
    @Test
    void anUnclaimedHostStillResolvesByPathSegment() throws Exception {
        MockHttpServletRequest request = publicUrlAt("", "GET", "/mapperblog/entry/my-post");
        request.addHeader("Host", "unclaimed.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("/roller-ui/rendering/page/mapperblog/entry/my-post",
                response.getForwardedUrl());
    }

    /**
     * A permalink on a custom domain must render, not 404. The mapper 404s any
     * request where weblogRequestContext != null and trailingSlash is set, so
     * forcing trailingSlash for every vhost request would swallow every
     * permalink on every custom domain.
     */
    @Test
    void aPermalinkOnACustomDomainIsNot404() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(vhostUrl("GET", "/entry/my-post"), response));
        assertEquals(200, response.getStatus());
        assertNotNull(response.getForwardedUrl());
    }

    /**
     * Protected paths stay protected on a custom domain. Without this the
     * mapper claims every path on that host and forwards theme assets, the
     * contact endpoint and robots.txt into the page servlet.
     */
    @ParameterizedTest
    @ValueSource(strings = {"themes", "webjars", "roller-ui", "newsletter",
                            "robots.txt", "sitemap.xml"})
    void protectedPathsAreStillDeclinedOnACustomDomain(String reserved) throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(vhostUrl("GET", "/" + reserved + "/x"), response),
                reserved + " must fall through to its own servlet on a custom domain");
    }

    /**
     * The mirror of protectedPathsAreStillDeclinedOnACustomDomain: a weblog
     * request CONTEXT is reserved on the site host only because it sits behind
     * a handle there. On a custom domain it is the first segment and must
     * reach the weblog -- /page/<theme>.css is the theme stylesheet on every
     * single page, so declining it renders every vhost page unstyled.
     */
    @ParameterizedTest
    @ValueSource(strings = {"page", "search", "resource"})
    void weblogRequestContextsAreNotReservedOnACustomDomain(String context) throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(vhostUrl("GET", "/" + context + "/x"), response),
                context + " must reach the weblog on a custom domain");
        assertNotNull(response.getForwardedUrl());
    }

    /**
     * The custom domain is the single canonical address, so the old path form
     * permanently redirects to it -- which is what keeps already-indexed urls
     * and existing inbound links working after a weblog gains a hostname.
     */
    @Test
    void thePathFormRedirectsToTheCustomDomain() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletRequest request = publicUrlAt("", "GET", "/mapperblog/entry/my-post");
        request.addHeader("Host", "blog.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals(301, response.getStatus());
        assertEquals("https://vhost.example.com/entry/my-post", response.getRedirectedUrl());
    }

    /**
     * The same redirect deployed under a context path. This crosses hosts, so
     * unlike the mapper's OWN trailing-slash redirect (see
     * {@link #missingTrailingSlashRedirects}) the target is built from
     * {@code pathInfo}, which is already context-stripped -- the context path
     * has to be re-added, or a reader deployed at /roller is sent to
     * https://vhost.example.com/entry/my-post, which 404s on that same
     * deployment.
     */
    @Test
    void thePathFormRedirectsToTheCustomDomainUnderAContextPath() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/entry/my-post");
        request.addHeader("Host", "blog.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals(301, response.getStatus());
        assertEquals("https://vhost.example.com/roller/entry/my-post",
                response.getRedirectedUrl());
    }

    /**
     * The bare weblog-home form under a context path -- the pathInfo == null,
     * trailingSlash == true branch pair, at a non-root context this time.
     */
    @Test
    void thePathFormRedirectsTheWeblogHomeWithTrailingSlashUnderAContextPath() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletRequest request = publicUrl("GET", "/mapperblog/");
        request.addHeader("Host", "blog.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals(301, response.getStatus());
        assertEquals("https://vhost.example.com/roller/", response.getRedirectedUrl());
    }

    @Test
    void thePathFormRedirectKeepsTheQueryString() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletRequest request = publicUrlAt("", "GET", "/mapperblog/entry/my-post");
        request.addHeader("Host", "blog.example.com");
        request.setQueryString("p=2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals("https://vhost.example.com/entry/my-post?p=2",
                response.getRedirectedUrl());
    }

    /**
     * The bare weblog-home form ({@code /mapperblog/}, the canonical non-vhost
     * shape covered on the plain path by {@link #weblogHomeForwardsToPageServlet})
     * is the single most common inbound link shape this feature has to handle
     * -- pathInfo is null and trailingSlash is true, the exact branch pair the
     * three redirect tests above never exercised (they all use
     * "/mapperblog/entry/my-post", which is neither).
     */
    @Test
    void thePathFormRedirectsTheWeblogHomeWithTrailingSlash() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletRequest request = publicUrlAt("", "GET", "/mapperblog/");
        request.addHeader("Host", "blog.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals(301, response.getStatus());
        assertEquals("https://vhost.example.com/", response.getRedirectedUrl());
    }

    /**
     * The no-trailing-slash weblog-home form ({@code /mapperblog}) is, before
     * this feature, precisely the shape that triggers the OLD context-relative
     * trailing-slash redirect a few lines below this block. The custom-domain
     * redirect has to intercept it first, or a reader pays two hops -- add the
     * slash on the old host, then cross to the domain -- which is a wasted
     * round trip and a crawl-budget cost the whole feature exists to avoid.
     * Asserting the exact Location proves it is the single, domain-form hop
     * and not the old form ("/mapperblog/", which is what this request would
     * redirect to on this empty-context test setup if the old redirect fired
     * instead).
     * <p>
     * The resulting Location is a bare "https://vhost.example.com" -- no
     * trailing slash, no path. That is what the implementation actually
     * produces (pathInfo is null and trailingSlash is false, so neither of
     * the block's two append branches fires) and is accepted as-is: it is
     * equivalent to the root and every browser/crawler treats it the same as
     * "https://vhost.example.com/".
     */
    @Test
    void thePathFormRedirectsTheWeblogHomeWithoutTrailingSlash() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletRequest request = publicUrlAt("", "GET", "/mapperblog");
        request.addHeader("Host", "blog.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals(301, response.getStatus());
        assertEquals("https://vhost.example.com", response.getRedirectedUrl());
    }

    /** A request already ON the custom domain must not redirect to itself. */
    @Test
    void aRequestOnTheCustomDomainIsNotRedirected() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(vhostUrl("GET", "/entry/my-post"), response));
        assertNull(response.getRedirectedUrl());
    }

    /**
     * I3: a locale-shaped first segment with nothing after it
     * ({@code weblogRequestContext} stays null, since {@code isLocale}
     * matches but the split has no second element) triggers the
     * trailing-slash redirect branch. On the site host that branch's target
     * legitimately carries the handle -- but on a custom domain the handle
     * was never in the path to begin with, so appending it here leaked it
     * back into a url that only 404s: {@code /roller/mapperblog/de/} on a
     * host that resolves the ENTIRE path as weblog-relative.
     */
    @Test
    void aLocaleShapedFirstSegmentOnACustomDomainRedirectsWithoutTheHandle() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(vhostUrl("GET", "/de"), response));
        assertEquals("/de/", response.getRedirectedUrl(),
                "the redirect target must omit the handle on a custom domain");
    }

    /**
     * The trailing-slash sibling of the test above: with the slash already
     * present, {@code trailingSlash} is true, so the redirect branch never
     * fires and this instead falls straight through to the homepage forward
     * in the "de" locale -- unaffected by the I3 fix, but worth pinning at
     * the same intersection (locale segment x vhost) the redirect test
     * covers, since the class had locale tests and vhost tests and none at
     * this intersection before.
     */
    @Test
    void aLocaleShapedFirstSegmentWithATrailingSlashOnACustomDomainForwardsHome() throws Exception {
        givenCustomDomain("vhost.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(vhostUrl("GET", "/de/"), response));
        assertNull(response.getRedirectedUrl());
        assertEquals("/roller-ui/rendering/page/mapperblog/de", response.getForwardedUrl());
    }

    // ------------------------------------------------- site pages at /<slug>

    private void setProperty(String name, String value) throws Exception {
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        originalProperties.computeIfAbsent(name, key -> config.get(key).getValue());
        config.get(name).setValue(value);
        pmgr.saveProperties(config);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private void givenSitePage(String slug, WeblogPage.PubStatus status) throws Exception {
        Weblog site = TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(user.getUserName()));
        TestUtils.weblogger().flush();
        WeblogPage page = new WeblogPage();
        page.setWeblog(site);
        page.setSlug(slug);
        page.setTitle(slug);
        page.setContent("Body of " + slug);
        page.setStatus(status);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    @Test
    void aPublishedSitePageSlugForwardsToTheSiteSpace() throws Exception {
        givenSitePage("mapperabout", WeblogPage.PubStatus.PUBLISHED);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(publicUrl("GET", "/mapperabout"), response));
        assertEquals("/roller-ui/rendering/page/site-space/mapperabout", response.getForwardedUrl());
    }

    /**
     * A draft holds its slug against blogs (Task 4), but it is not served: the
     * mapper decides from the published pages, so a draft declines and 404s
     * like any unknown path (spec AC2).
     */
    @Test
    void aDraftSitePageSlugIsNotHandled() throws Exception {
        givenSitePage("mapperdraft", WeblogPage.PubStatus.DRAFT);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(publicUrl("GET", "/mapperdraft"), response));
        assertNull(response.getForwardedUrl());
        assertNull(response.getHeader("Location"));
    }

    /**
     * The front door's page as a draft: / shows the directory, and its slug
     * must not answer a permanent, cacheable 301 to that directory (AC2, AC12).
     */
    @Test
    void aDraftFrontDoorPageIsNotRedirectedNorForwarded() throws Exception {
        givenSitePage("mapperhome", WeblogPage.PubStatus.DRAFT);
        setProperty("site.frontpage.page", "mapperhome");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(publicUrl("GET", "/mapperhome"), response));
        assertEquals(200, response.getStatus(), "no 301");
        assertNull(response.getHeader("Location"));
        assertNull(response.getForwardedUrl());
    }

    /**
     * An unclaimed single segment is every bot probe and stray favicon, so
     * once warm it costs no page query (the published list is remembered by
     * id) ...
     */
    @Test
    void aWarmUnclaimedSegmentSelectsNoSitePages() throws Exception {
        givenSitePage("mapperabout", WeblogPage.PubStatus.PUBLISHED);
        mapper.handleRequest(publicUrl("GET", "/mapperprobe"), new MockHttpServletResponse());
        TestUtils.endSession(false);

        List<String> queries = QueryRecorder.during(() -> assertFalse(
                mapper.handleRequest(publicUrl("GET", "/mapperprobe"), new MockHttpServletResponse())));

        assertEquals(List.of(), QueryRecorder.sqlOn(queries, "roller_weblogpage"), queries.toString());
    }

    /**
     * ... and on a site with no site space, no site-space lookup either: the
     * only weblog query left is the blog-handle check the mapper always made.
     */
    @Test
    void aWarmUnclaimedSegmentWithNoSiteSpaceLooksForItOnce() throws Exception {
        mapper.handleRequest(publicUrl("GET", "/mapperprobe"), new MockHttpServletResponse());
        TestUtils.endSession(false);

        List<String> queries = QueryRecorder.during(() -> assertFalse(
                mapper.handleRequest(publicUrl("GET", "/mapperprobe"), new MockHttpServletResponse())));

        assertEquals(1, QueryRecorder.on(queries, Weblog.class).size(),
                "only the handle check for the segment itself: " + queries);
    }

    @Test
    void aSitePageForwardsAtTheRootContextToo() throws Exception {
        givenSitePage("mapperabout", WeblogPage.PubStatus.PUBLISHED);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(publicUrlAt("", "GET", "/mapperabout"), response));
        assertEquals("/roller-ui/rendering/page/site-space/mapperabout", response.getForwardedUrl());
    }

    @Test
    void aSingleSegmentNamingNoSitePageIsNotHandled() throws Exception {
        givenSitePage("mapperabout", WeblogPage.PubStatus.PUBLISHED);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(publicUrl("GET", "/mappernosuchpage"), response));
        assertNull(response.getForwardedUrl());
    }

    @Test
    void aSingleSegmentWithNoSiteSpaceIsNotHandled() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(publicUrl("GET", "/mapperabout"), response));
        assertNull(response.getForwardedUrl());
    }

    /** A site page is one segment: /about/ and /about/x are nothing this mapper serves. */
    @ParameterizedTest
    @ValueSource(strings = {"/mapperabout/", "/mapperabout/more"})
    void aSitePageSlugWithMoreAfterItIsNotHandled(String path) throws Exception {
        givenSitePage("mapperabout", WeblogPage.PubStatus.PUBLISHED);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(publicUrl("GET", path), response), path);
        assertNull(response.getForwardedUrl(), path);
    }

    @Test
    void anEncodedSlugIsLookedUpDecodedAndForwardedAsItCame() throws Exception {
        givenSitePage("mapper story", WeblogPage.PubStatus.PUBLISHED);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(publicUrl("GET", "/mapper+story"), response));
        assertEquals("/roller-ui/rendering/page/site-space/mapper+story", response.getForwardedUrl());
    }

    @Test
    void aMalformedEncodingIsNotHandled() throws Exception {
        givenSitePage("mapperabout", WeblogPage.PubStatus.PUBLISHED);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(publicUrl("GET", "/mapper%zz"), response));
        assertNull(response.getForwardedUrl());
    }

    /** The front door has one public address, and it is the root (spec AC12). */
    @Test
    void theFrontDoorPagesOwnSlugIsAPermanentRedirectToTheRoot() throws Exception {
        givenSitePage("mapperhome", WeblogPage.PubStatus.PUBLISHED);
        setProperty("site.frontpage.page", "mapperhome");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(publicUrl("GET", "/mapperhome"), response));
        assertEquals(301, response.getStatus());
        assertEquals("/roller/", response.getHeader("Location"));
        assertNull(response.getForwardedUrl());
    }

    @Test
    void theFrontDoorRedirectKeepsTheQueryAndIsBareAtTheRootContext() throws Exception {
        givenSitePage("mapperhome", WeblogPage.PubStatus.PUBLISHED);
        setProperty("site.frontpage.page", "mapperhome");
        MockHttpServletRequest request = publicUrlAt("", "GET", "/mapperhome");
        request.setQueryString("utm_source=x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(request, response));
        assertEquals(301, response.getStatus());
        assertEquals("/?utm_source=x", response.getHeader("Location"));
    }

    /** Another page is not the front door just because one is set. */
    @Test
    void aSitePageThatIsNotTheFrontDoorStillForwards() throws Exception {
        givenSitePage("mapperhome", WeblogPage.PubStatus.PUBLISHED);
        givenSitePage("mapperabout", WeblogPage.PubStatus.PUBLISHED);
        setProperty("site.frontpage.page", "mapperhome");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(publicUrl("GET", "/mapperabout"), response));
        assertEquals("/roller-ui/rendering/page/site-space/mapperabout", response.getForwardedUrl());
    }

    /**
     * The editor refuses a site slug that is a blog's handle, so the clash
     * can only be forced (here: the blog created after the page, around the
     * creation rules). The blog wins and the site page is not served there.
     */
    @Test
    void aBlogHandleWinsOverASitePageOfTheSameName() throws Exception {
        givenSitePage("mapperclash", WeblogPage.PubStatus.PUBLISHED);
        Weblog clash = TestUtils.setupWeblog("mapperclash", user);
        TestUtils.endSession(true);
        try {
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertTrue(mapper.handleRequest(publicUrl("GET", "/mapperclash"), response));
            assertNull(response.getForwardedUrl());
            assertEquals("/roller/mapperclash/", response.getRedirectedUrl(),
                    "the blog's own trailing-slash redirect, not the site page");
        } finally {
            TestUtils.teardownWeblog(clash.getId());
            TestUtils.endSession(true);
        }
    }

    /** Custom domains are unaffected: /about there is that blog's Page (review focus 1). */
    @Test
    void onACustomDomainASitePageSlugIsTheBlogsOwnPage() throws Exception {
        givenSitePage("mapperabout", WeblogPage.PubStatus.PUBLISHED);
        setProperty("site.frontpage.page", "mapperabout");
        givenCustomDomain("vhost.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(mapper.handleRequest(vhostUrl("GET", "/mapperabout"), response));
        assertEquals("/roller-ui/rendering/page/mapperblog/mapperabout", response.getForwardedUrl());
        assertEquals(200, response.getStatus(), "never the front door's 301");
    }

    @Test
    void aPostToASitePageIsNeverForwarded() throws Exception {
        givenSitePage("mapperabout", WeblogPage.PubStatus.PUBLISHED);
        givenSitePage("mapperhome", WeblogPage.PubStatus.PUBLISHED);
        setProperty("site.frontpage.page", "mapperhome");
        for (String path : new String[] {"/mapperabout", "/mapperhome"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertFalse(mapper.handleRequest(publicUrl("POST", path), response), path);
            assertNull(response.getForwardedUrl(), path);
            assertNull(response.getHeader("Location"), path);
        }
    }

    @Test
    void beforeBootstrapASingleSegmentIsNotASitePage() throws Exception {
        givenSitePage("mapperabout", WeblogPage.PubStatus.PUBLISHED);
        WebloggerProvider cold = mock(WebloggerProvider.class);
        when(cold.isBootstrapped()).thenReturn(false);
        WeblogRequestMapper coldMapper = new WeblogRequestMapper(cold, TestUtils.weblogger());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(coldMapper.handleRequest(publicUrl("GET", "/mapperabout"), response));
        assertNull(response.getForwardedUrl());
    }

    /** A lookup that throws is a decline (the 404 it would have been), never a 500. */
    @Test
    void aSitePageLookupThatThrowsIsNotHandled() throws Exception {
        Weblogger broken = mock(Weblogger.class);
        when(broken.getWeblogManager()).thenThrow(new IllegalStateException("tier gone"));
        WeblogRequestMapper brokenMapper = new WeblogRequestMapper(bootstrappedProvider(), broken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(brokenMapper.handleRequest(publicUrl("GET", "/mapperabout"), response));
        assertNull(response.getForwardedUrl());
    }

    private void givenCustomDomain(String host) throws Exception {
        Weblog stored = TestUtils.weblogger().getWeblogManager()
                .getWeblogByHandle("mapperblog");
        stored.setCustomDomain(host);
        TestUtils.weblogger().getWeblogManager().saveWeblog(stored);
        TestUtils.endSession(true);
        TestUtils.weblogger().getVirtualHostRegistry().invalidate();
    }

    // ------------------------------------- site-space assets at /site-space/<context>/...

    /**
     * The site space's stylesheet, theme resources and media files reuse the
     * per-weblog asset routes, so their urls keep the handle (the url strategy
     * builds {@code <ctx>/site-space/<context>/...}). The mapper forwards
     * exactly those contexts to the site space's servlets; the PageServlet's
     * and the resource servlets' FORWARD gate then serves them.
     */
    @Test
    void theSiteSpacesAssetUrlsForwardToTheirServlets() throws Exception {
        Map<String, String> expected = Map.of(
                "/site-space/page/frontpage-custom.css",
                "/roller-ui/rendering/page/site-space/page/frontpage-custom.css",
                "/site-space/resource/img/logo.png",
                "/roller-ui/rendering/resources/site-space/img/logo.png",
                "/site-space/mediaresource/abc123",
                "/roller-ui/rendering/media-resources/site-space/abc123");
        for (String contextPath : new String[] {"/roller", ""}) {
            for (Map.Entry<String, String> route : expected.entrySet()) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                assertTrue(mapper.handleRequest(
                        publicUrlAt(contextPath, "GET", route.getKey()), response), route.getKey());
                assertEquals(route.getValue(), response.getForwardedUrl(), contextPath + route.getKey());
            }
        }
    }

    /**
     * Everything else under the handle stays a 404: the site space has no
     * home, entries, feed, search, archive or tags, and its pages live at
     * {@code /<slug>}, never at {@code /site-space/<slug>} (spec AC7).
     */
    @ParameterizedTest
    @ValueSource(strings = {"/site-space/entry/x", "/site-space/feed/entries/atom",
            "/site-space/search", "/site-space/category/General", "/site-space/tags/x",
            "/site-space/date/20261025", "/site-space/page", "/site-space/page/x.css/",
            "/site-space/en/page/x.css", "/site-space/mediaresource", "/site-space/about/more"})
    void everyOtherSiteSpaceUrlIsNotHandled(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(publicUrl("GET", path), response), path);
        assertNull(response.getForwardedUrl(), path);
        assertNull(response.getHeader("Location"), path);
    }

    @Test
    void aPostToASiteSpaceAssetIsNeverForwarded() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(publicUrl("POST", "/site-space/page/x.css"), response));
        assertNull(response.getForwardedUrl());
    }

    @Test
    void beforeBootstrapASiteSpaceAssetIsNotHandled() throws Exception {
        WebloggerProvider cold = mock(WebloggerProvider.class);
        when(cold.isBootstrapped()).thenReturn(false);
        WeblogRequestMapper coldMapper = new WeblogRequestMapper(cold, TestUtils.weblogger());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(coldMapper.handleRequest(publicUrl("GET", "/site-space/page/x.css"), response));
        assertNull(response.getForwardedUrl());
    }
}
