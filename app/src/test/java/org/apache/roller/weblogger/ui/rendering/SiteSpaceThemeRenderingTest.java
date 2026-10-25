/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.rendering;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.CwebpEncoder;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A site page in each of the four site themes (spec AC10, AC3): no chrome
 * that only a blog with posts can back (feed autodiscovery, category list,
 * search form, frontpage's "Recent posts" tab), the site's own nav, name and
 * canonical url, the colophon with its Sign in link -- and the assets it
 * references (the theme stylesheet, an {@code [image]}) actually served.
 *
 * <p>Requests go through the real {@link WeblogRequestMapper} and then, as a
 * FORWARD dispatch, the servlet it names, so a url a theme emits is proved
 * reachable rather than merely well formed. Context {@code /roller}.
 */
class SiteSpaceThemeRenderingTest {

    private static final List<String> THEMES = List.of("journal", "portfolio", "travel", "frontpage");
    private static final Map<String, String> BODY_CLASS = Map.of(
            "journal", "<body class=\"qj\">", "portfolio", "<body class=\"pf\">",
            "travel", "<body class=\"tg\">", "frontpage", "<body class=\"fd\">");
    private static final String SITE_NAME = "Sitetheme Harbour";
    private static final String BLOG = "sitethemeblog";

    private static final String PAGE_SERVLET = "/roller-ui/rendering/page";
    private static final String RESOURCE_SERVLET = "/roller-ui/rendering/resources";
    private static final String MEDIA_SERVLET = "/roller-ui/rendering/media-resources";

    private static final Pattern LINK_TAG = Pattern.compile("<(?:a|link)\\b[^>]*>");
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");
    private static final Pattern STYLESHEET =
            Pattern.compile("<link rel=\"stylesheet\" href=\"([^\"]*)\"");
    private static final Pattern MEDIA_SRC = Pattern.compile("src=\"([^\"]*mediaresource[^\"]*)\"");
    private static final List<String> DEAD_FOR_THE_SITE =
            List.of("/feed/", "/category/", "/search", "/page/directory", "site-space");

    private User admin;
    private Weblog site;
    private Weblog blog;
    private MediaFile image;
    private WeblogRequestMapper mapper;
    private final Map<String, String> originalProperties = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        CwebpEncoder.setAvailableForTesting(false);

        admin = TestUtils.setupUser("sitethemeadmin");
        TestUtils.weblogger().getUserManager().grantRole("admin", admin);
        TestUtils.endSession(true);
        site = TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(admin.getUserName()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        blog = TestUtils.setupWeblog(BLOG, admin);
        TestUtils.endSession(true);

        setProperty("site.name", SITE_NAME);
        setProperty("site.frontpage.page", "");
        image = TestUtils.setupImageMediaFile(site, "sitetheme.jpg");
        TestUtils.endSession(true);

        savePage(site, "sitethemeabout", "About the Harbour",
                "Hello harbour.\n\n[image id=" + image.getId() + "]", false);
        savePage(site, "sitethemecontact", "Contact Desk", "Write to us.", true);
        savePage(blog, "sitethemeblogpage", "Blog Page", "Blog body.", true);

        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(TestUtils.weblogger());
        mapper = new WeblogRequestMapper(provider, TestUtils.weblogger());
    }

    @AfterEach
    void tearDown() throws Exception {
        CwebpEncoder.setAvailableForTesting(null);
        if (!originalProperties.isEmpty()) {
            PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
            Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
            originalProperties.forEach((name, value) -> config.get(name).setValue(value));
            pmgr.saveProperties(config);
            TestUtils.weblogger().flush();
            TestUtils.endSession(true);
        }
        WeblogManager weblogs = TestUtils.weblogger().getWeblogManager();
        Weblog current = weblogs.getSiteSpace();
        if (current != null) {
            weblogs.removeWeblog(current);
        }
        TestUtils.endSession(true);
        TestUtils.teardownWeblog(blog.getId());
        TestUtils.teardownUser(admin.getUserName());
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    @Test
    void aSitePageOffersNoBlogOnlyChromeInAnyTheme() throws Exception {
        List<String> failures = new ArrayList<>();
        for (String theme : THEMES) {
            String body = sitePage(theme);
            for (String href : linkHrefs(body)) {
                for (String dead : DEAD_FOR_THE_SITE) {
                    if (href.contains(dead)) {
                        failures.add(theme + ": a link to " + href);
                    }
                }
            }
            if (Pattern.compile("<form[^>]*action=\"[^\"]*search").matcher(body).find()) {
                failures.add(theme + ": a search form");
            }
            if (body.contains("rel=\"alternate\"")) {
                failures.add(theme + ": feed autodiscovery");
            }
            if (body.contains("#show") || body.contains("$model") || body.contains("$url")) {
                failures.add(theme + ": literal Velocity in the page");
            }
        }
        assertTrue(failures.isEmpty(), "blog-only chrome on a site page: " + failures);
    }

    @Test
    void aSitePageCarriesTheSitesNavNameCanonicalAndColophon() throws Exception {
        for (String theme : THEMES) {
            String body = sitePage(theme);
            assertTrue(body.contains(BODY_CLASS.get(theme)), theme + " chrome:\n" + body);
            assertTrue(body.contains("href=\"/roller/sitethemecontact\">Contact Desk</a>"),
                    theme + ": the nav lists the show-in-nav site page:\n" + body);
            assertTrue(body.contains(">" + SITE_NAME + "</a>"), theme + ": the site's name:\n" + body);
            assertTrue(body.contains("<link rel=\"canonical\" href=\"http://localhost:8080/roller/sitethemeabout\">"),
                    theme + ": the canonical is the page's own /<slug>:\n" + body);
            int colophon = body.indexOf("<div class=\"colophon\">");
            assertTrue(colophon >= 0, theme + ": no colophon:\n" + body);
            assertTrue(body.indexOf("/roller-ui/login", colophon) > colophon,
                    theme + ": the colophon's Sign in link (AC10):\n" + body);
        }
    }

    @Test
    void theFrontpageThemeSwapsItsTabsForTheSiteNavAndTheDirectory() throws Exception {
        String body = sitePage("frontpage");

        assertFalse(body.contains("Recent posts"), body);
        assertTrue(body.contains("href=\"/roller/roller-ui/siteDirectory.rol\""), body);
    }

    @Test
    void theFrontDoorPagesCanonicalIsTheRoot() throws Exception {
        setProperty("site.frontpage.page", "sitethemeabout");
        for (String theme : THEMES) {
            useSiteTheme(theme);
            MockHttpServletResponse response = forwardedToServlet(
                    PAGE_SERVLET + "/site-space/sitethemeabout", null);
            String body = response.getContentAsString();
            assertEquals(200, response.getStatus(), body);
            assertTrue(body.contains("<link rel=\"canonical\" href=\"http://localhost:8080/roller/\">"),
                    theme + ":\n" + body);
        }
    }

    /**
     * The ruling that came with the url rule: the stylesheet and the media
     * urls a site page emits keep the handle, and the mapper and servlets
     * answer them. Before it, both came out as {@code <ctx>/page/...} and
     * {@code <ctx>/mediaresource/...}, which nothing routes.
     */
    @Test
    void aSitePagesStylesheetAndImageAreServedInEveryTheme() throws Exception {
        for (String theme : THEMES) {
            String body = sitePage(theme);

            Matcher css = STYLESHEET.matcher(body);
            assertTrue(css.find(), theme + ": no stylesheet:\n" + body);
            assertTrue(css.group(1).startsWith("/roller/site-space/page/" + theme + "-custom.css"),
                    css.group(1));
            MockHttpServletResponse stylesheet = served(css.group(1));
            assertEquals(200, stylesheet.getStatus(), theme + " stylesheet " + css.group(1));
            assertTrue(stylesheet.getContentAsString().contains("{"), theme + ": a stylesheet body");

            Matcher img = MEDIA_SRC.matcher(body);
            assertTrue(img.find(), theme + ": the [image] did not render:\n" + body);
            assertTrue(img.group(1).contains("/roller/site-space/mediaresource/" + image.getId()),
                    img.group(1));
            MockHttpServletResponse media = served(img.group(1));
            assertEquals(200, media.getStatus(), theme + " image " + img.group(1));
            assertTrue(media.getContentAsByteArray().length > 0, theme + ": image bytes");
        }
    }

    @Test
    void aSiteResourceIsServedThroughTheResourceRoute() throws Exception {
        givenOriginalPath("/sitetheme-orig.jpg");
        String url = TestUtils.weblogger().getUrlStrategy()
                .getWeblogResourceURL(TestUtils.weblogger().getWeblogManager().getSiteSpace(),
                        "sitetheme-orig.jpg", false);
        TestUtils.endSession(true);

        assertEquals("/roller/site-space/resource/sitetheme-orig.jpg", url);
        MockHttpServletResponse response = served(url);
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsByteArray().length > 0);
    }

    /**
     * Only the mapper's forward reaches the site space's assets; its handle is
     * not an address. Each path is served when forwarded, so the 404 is the
     * dispatch gate's and not a missing file's.
     */
    @Test
    void aDirectRequestForASiteAssetIs404() throws Exception {
        givenOriginalPath("/sitetheme-orig.jpg");
        useSiteTheme("frontpage");
        for (String path : List.of(PAGE_SERVLET + "/site-space/page/frontpage-custom.css",
                MEDIA_SERVLET + "/site-space/" + image.getId(),
                RESOURCE_SERVLET + "/site-space/sitetheme-orig.jpg")) {
            assertEquals(200, forwardedToServlet(path, null).getStatus(), "forwarded " + path);
            MockHttpServletRequest direct = servletRequest(path, null);
            direct.setDispatcherType(DispatcherType.REQUEST);
            assertEquals(404, RenderingTestSupport.execute(servletFor(path), direct).getStatus(), path);
        }
    }

    /**
     * Under the handle the page context serves the theme's stylesheet and
     * nothing else: frontpage's directory template is not a site address.
     */
    @Test
    void theSiteSpaceServesOnlyItsStylesheetUnderPage() throws Exception {
        useSiteTheme("frontpage");

        assertEquals(200, served("/roller/site-space/page/frontpage-custom.css").getStatus());
        assertEquals(404, served("/roller/site-space/page/directory").getStatus());
    }

    /**
     * The contact endpoint refuses the site space, so a site page's
     * {@code [contact]} shows a notice rather than a form that loses every
     * message; a blog's Page keeps its form slot.
     */
    @Test
    void aContactShortcodeOnASitePageShowsANoticeNotAForm() throws Exception {
        savePage(site, "sitethemewrite", "Write", "Say hello.\n\n[contact]", false);
        savePage(blog, "sitethemeblogwrite", "Write", "Say hello.\n\n[contact]", false);
        useSiteTheme("journal");

        MockHttpServletResponse onSite = served("/roller/sitethemewrite");
        String siteBody = onSite.getContentAsString();
        assertEquals(200, onSite.getStatus(), siteBody);
        assertFalse(siteBody.contains("contact-form-slot\""), siteBody);
        assertTrue(siteBody.contains("Contact forms are not available on site pages yet."), siteBody);

        RenderingTestSupport.clearRenderCaches();
        MockHttpServletResponse onBlog = RenderingTestSupport.execute(RenderingTestSupport.pageServlet(),
                RenderingTestSupport.anonymousGet(PAGE_SERVLET, "/" + BLOG + "/sitethemeblogwrite"));
        String blogBody = onBlog.getContentAsString();
        assertEquals(200, onBlog.getStatus(), blogBody);
        assertTrue(blogBody.contains("<div class=\"contact-form-slot\" data-weblog=\"" + BLOG + "\""), blogBody);
        assertFalse(blogBody.contains("Contact forms are not available"), blogBody);
    }

    /** Everything under the handle that is not an asset stays a 404: the mapper declines it. */
    @Test
    void theSiteSpacesOtherContextsAreNotServed() throws Exception {
        for (String path : List.of("/site-space/", "/site-space/sitethemeabout", "/site-space/entry/x",
                "/site-space/feed/entries/atom", "/site-space/search", "/site-space/category/General",
                "/site-space/tags/x", "/site-space/date/20261025")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(mapper.handleRequest(publicGet(path), response), path);
            assertNull(response.getForwardedUrl(), path);
        }
    }

    /** The control: an ordinary blog's page in the same theme keeps its feed, categories and search. */
    @Test
    void anOrdinaryBlogsPageKeepsItsBlogChromeInEveryTheme() throws Exception {
        for (String theme : THEMES) {
            Weblog managed = TestUtils.getManagedWebsite(blog);
            managed.setEditorTheme(theme);
            TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
            TestUtils.endSession(true);

            String body = blogPage();
            assertTrue(body.contains("rel=\"alternate\""), theme + ": feed autodiscovery:\n" + body);
            if ("frontpage".equals(theme)) {
                assertTrue(body.contains("Recent posts"), body);
                assertTrue(body.contains("/" + BLOG + "/page/directory\""), body);
            } else {
                assertTrue(body.contains("/" + BLOG + "/category/General\""), theme + ": categories:\n" + body);
                assertTrue(Pattern.compile("<form[^>]*action=\"[^\"]*/" + BLOG + "/search")
                        .matcher(body).find(), theme + ": search form:\n" + body);
            }
        }
    }

    /** AC3: the site's theme is the site's alone. */
    @Test
    void switchingTheSiteThemeChangesTheSitePageAndLeavesABlogByteIdentical() throws Exception {
        useSiteTheme("journal");
        String blogBefore = blogPage();
        String siteBefore = sitePage("journal");

        String siteAfter = sitePage("travel");
        String blogAfter = blogPage();

        assertNotEquals(siteBefore, siteAfter);
        assertTrue(siteAfter.contains(BODY_CLASS.get("travel")), siteAfter);
        assertEquals(blogBefore, blogAfter);
    }

    // ------------------------------------------------------------- helpers

    private void useSiteTheme(String theme) throws Exception {
        Weblog managed = TestUtils.weblogger().getWeblogManager().getSiteSpace();
        managed.setEditorTheme(theme);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    /** The site page at its public address, {@code /<slug>}, in {@code theme}. */
    private String sitePage(String theme) throws Exception {
        useSiteTheme(theme);
        MockHttpServletResponse response = served("/roller/sitethemeabout");
        String body = response.getContentAsString();
        assertEquals(200, response.getStatus(), body);
        return body;
    }

    private String blogPage() throws Exception {
        RenderingTestSupport.clearRenderCaches();
        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.pageServlet(),
                RenderingTestSupport.anonymousGet(PAGE_SERVLET, "/" + BLOG + "/sitethemeblogpage"));
        String body = response.getContentAsString();
        assertEquals(200, response.getStatus(), body);
        return body;
    }

    /**
     * A url as a theme emits it (absolute or context-relative, attribute
     * escaped), through the mapper and then the servlet it forwards to.
     */
    private MockHttpServletResponse served(String emitted) throws Exception {
        String url = emitted.replace("&amp;", "&").replace("http://localhost:8080", "");
        int q = url.indexOf('?');
        String uri = q < 0 ? url : url.substring(0, q);
        String query = q < 0 ? null : url.substring(q + 1);

        MockHttpServletRequest request = publicGet(uri.substring("/roller".length()));
        request.setQueryString(query);
        MockHttpServletResponse mapped = new MockHttpServletResponse();
        assertTrue(mapper.handleRequest(request, mapped), "the mapper must route " + emitted);
        String forward = mapped.getForwardedUrl();
        assertNotNull(forward, "the mapper must forward " + emitted);
        return forwardedToServlet(forward, query);
    }

    private static MockHttpServletRequest publicGet(String uriAfterContext) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/roller" + uriAfterContext);
        request.setContextPath("/roller");
        return request;
    }

    private static MockHttpServletResponse forwardedToServlet(String forward, String query) throws Exception {
        MockHttpServletRequest request = servletRequest(forward, query);
        request.setDispatcherType(DispatcherType.FORWARD);
        return RenderingTestSupport.execute(servletFor(forward), request);
    }

    private static MockHttpServletRequest servletRequest(String path, String query) {
        String servletPath = path.startsWith(MEDIA_SERVLET) ? MEDIA_SERVLET
                : path.startsWith(RESOURCE_SERVLET) ? RESOURCE_SERVLET : PAGE_SERVLET;
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                servletPath, path.substring(servletPath.length()));
        if (query != null) {
            request.setQueryString(query);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                request.addParameter(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1));
            }
        }
        return request;
    }

    private static HttpServlet servletFor(String path) throws Exception {
        if (path.startsWith(MEDIA_SERVLET)) {
            return RenderingTestSupport.mediaResourceServlet();
        }
        if (path.startsWith(RESOURCE_SERVLET)) {
            return RenderingTestSupport.resourceServlet();
        }
        return RenderingTestSupport.pageServlet();
    }

    /** Every link a reader can follow: anchors and non-stylesheet, non-icon link elements. */
    private static List<String> linkHrefs(String html) {
        List<String> hrefs = new ArrayList<>();
        Matcher tags = LINK_TAG.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            if (tag.startsWith("<link") && (tag.contains("rel=\"stylesheet\"") || tag.contains("rel=\"icon\""))) {
                continue;
            }
            Matcher href = HREF.matcher(tag);
            if (href.find()) {
                hrefs.add(href.group(1));
            }
        }
        return hrefs;
    }

    private void givenOriginalPath(String path) throws Exception {
        MediaFileManager media = TestUtils.weblogger().getMediaFileManager();
        MediaFile stored = media.getMediaFile(image.getId());
        stored.setOriginalPath(path);
        media.updateMediaFile(TestUtils.getManagedWebsite(site), stored);
        TestUtils.endSession(true);
    }

    private void savePage(Weblog owner, String slug, String title, String content, boolean inNav)
            throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(owner));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent(content);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        page.setShowInNav(inNav);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

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
