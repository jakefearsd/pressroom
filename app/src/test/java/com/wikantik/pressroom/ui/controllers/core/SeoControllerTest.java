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
package com.wikantik.pressroom.ui.controllers.core;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import jakarta.servlet.http.HttpServlet;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.VirtualHostRegistry;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.config.StaticConfigOverride;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.business.SitemapEntry;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.ui.core.filters.InitFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SeoController}, run against the real business tier the way
 * the rendering-servlet tests are: real weblogs and entries in PostgreSQL, the
 * real URL strategy, and the controller invoked directly.
 *
 * <p>What matters here: the index lists a visible weblog and skips a hidden
 * one, the per-weblog sitemap contains only published non-noindex entries, the
 * documents actually parse as namespace-aware XML, image entries carry the
 * featured image's media-resource URL, and robots.txt advertises the index.
 */
class SeoControllerTest {

    private static final String ABSOLUTE_CONTEXT = "http://localhost:8080/pressroom";

    /**
     * An {@code InitFilter} over the suite's real, bootstrapped tier -- it
     * takes its provider and facade by constructor since the DI wave (plan
     * Task 6b). (The test side still reaches the tier through the static
     * until plan Task 20.)
     */
    private static InitFilter initFilterOverTheRealTier() {
        Weblogger tier = TestUtils.weblogger();
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(tier);
        return new InitFilter(provider, tier);
    }

    private SeoController controller;
    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        // What InitFilter would have computed from the first request.
        WebloggerRuntimeConfig.setAbsoluteContextURL(ABSOLUTE_CONTEXT);
        WebloggerRuntimeConfig.setRelativeContextURL("/pressroom");

        user = TestUtils.setupUser("seoControllerUser");
        weblog = TestUtils.setupWeblog("seocontrollerblog", user);
        TestUtils.endSession(true);

        controller = new SeoController();
        inject(controller, "weblogger", TestUtils.weblogger());
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // --- robots.txt ---

    @Test
    void robotsAllowsEverythingAndAdvertisesTheSitemapIndex() {
        ResponseEntity<String> response = controller.robots(new MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("User-agent: *"),
                "robots.txt must address all crawlers:\n" + body);
        assertTrue(body.contains("Disallow:\n"),
                "robots.txt must allow everything (empty Disallow):\n" + body);
        assertTrue(body.contains("Sitemap: " + ABSOLUTE_CONTEXT + "/sitemap.xml"),
                "robots.txt must point at the absolute sitemap index URL:\n" + body);
    }

    /**
     * The symptom that proved the bug on a real deployment: the app's
     * environment carried {@code PRESSROOM_SITE_ABSOLUTEURL=http://docker2.lan},
     * the Global Config row was left at its blank default, the first request
     * after a restart arrived by IP -- and robots.txt advertised
     * {@code Sitemap: http://192.168.5.52/sitemap.xml}.
     */
    @Test
    void robotsAdvertisesTheEnvironmentSiteUrlNotTheFirstRequestsHost() {
        assertTrue(WebloggerRuntimeConfig.getProperty("site.absoluteurl") == null
                        || WebloggerRuntimeConfig.getProperty("site.absoluteurl").isBlank(),
                "precondition: the Global Config row is at its blank default");
        try (StaticConfigOverride _ = StaticConfigOverride.set("site.absoluteurl", "http://docker2.lan")) {
            WebloggerRuntimeConfig.setAbsoluteContextURL("http://192.168.5.52");

            String body = controller.robots(new MockHttpServletRequest()).getBody();

            assertNotNull(body);
            assertTrue(body.contains("Sitemap: http://docker2.lan/sitemap.xml"),
                    "robots.txt must advertise the configured site url:\n" + body);
        } finally {
            WebloggerRuntimeConfig.setAbsoluteContextURL(ABSOLUTE_CONTEXT);
        }
    }

    @Test
    void sitemapLocsAreHttpsWhenTlsIsTerminatedUpstream() throws Exception {
        try {
            // The production topology: Caddy terminates TLS and proxies over
            // plain http with X-Forwarded-Proto: https. Run the real filter
            // chain -- ForwardedHeaderFilter (registered by
            // server.forward-headers-strategy=framework) in front of
            // InitFilter -- and let it capture the absolute context URL,
            // exactly as the first proxied request in production would.
            MockHttpServletRequest first = new MockHttpServletRequest("GET", "/pressroom/");
            first.setServerName("photos.example.com");
            first.setServerPort(80);
            first.setContextPath("/pressroom");
            first.addHeader("X-Forwarded-Proto", "https");
            new ForwardedHeaderFilter().doFilter(first, new MockHttpServletResponse(),
                    new MockFilterChain(new HttpServlet() { }, initFilterOverTheRealTier()));

            ResponseEntity<String> response = controller.sitemapIndex(new MockHttpServletRequest());

            assertEquals(200, response.getStatusCode().value());
            String body = response.getBody();
            assertNotNull(body);
            assertTrue(body.contains("<loc>https://photos.example.com/pressroom/sitemap-"
                            + weblog.getHandle() + ".xml</loc>"),
                    "sitemap <loc>s must be https when TLS is terminated upstream:\n" + body);
            assertFalse(body.contains("<loc>http://"),
                    "no http:// loc may survive behind a TLS-terminating proxy:\n" + body);
        } finally {
            WebloggerRuntimeConfig.setAbsoluteContextURL(ABSOLUTE_CONTEXT);
            WebloggerRuntimeConfig.setRelativeContextURL("/pressroom");
        }
    }

    // --- /sitemap.xml (index) ---

    @Test
    void sitemapIndexListsTheVisibleWeblogAndParsesAsXml() throws Exception {
        ResponseEntity<String> response = controller.sitemapIndex(new MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertNotNull(body);
        Document doc = parse(body);
        assertEquals("sitemapindex", doc.getDocumentElement().getLocalName());
        assertEquals("http://www.sitemaps.org/schemas/sitemap/0.9",
                doc.getDocumentElement().getNamespaceURI());
        assertTrue(body.contains(
                "<loc>" + ABSOLUTE_CONTEXT + "/sitemap-" + weblog.getHandle() + ".xml</loc>"),
                "The index must list the per-weblog sitemap URL:\n" + body);
    }

    @Test
    void sitemapIndexExcludesHiddenWeblogs() throws Exception {
        Weblog hidden = TestUtils.setupWeblog("seohiddenblog", user);
        try {
            hidden = TestUtils.getManagedWebsite(hidden);
            hidden.setVisible(Boolean.FALSE);
            TestUtils.weblogger().getWeblogManager().saveWeblog(hidden);
            TestUtils.endSession(true);

            String body = controller.sitemapIndex(new MockHttpServletRequest()).getBody();
            assertNotNull(body);
            assertFalse(body.contains("/sitemap-" + hidden.getHandle() + ".xml"),
                    "A weblog with visible=false must not appear in the index:\n" + body);
        } finally {
            TestUtils.teardownWeblog(hidden.getId());
            TestUtils.endSession(true);
        }
    }

    // --- /sitemap-<handle>.xml (per-weblog) ---

    @Test
    void weblogSitemapContainsHomeAndPublishedPermalinkOnly() throws Exception {
        WeblogEntry published = TestUtils.setupWeblogEntry("seoPublishedEntry", weblog, user);
        WeblogEntry draft = TestUtils.setupWeblogEntry("seoDraftEntry",
                TestUtils.getManagedWebsite(weblog).getWeblogCategories().iterator().next(),
                PubStatus.DRAFT, weblog, user);
        WeblogEntry noindexed = TestUtils.setupWeblogEntry("seoNoindexEntry", weblog, user);
        noindexed = TestUtils.getManagedWeblogEntry(noindexed);
        noindexed.setNoindex(Boolean.TRUE);
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(noindexed);
        // A trashed entry must be as invisible to crawlers as a draft -- same
        // reasoning as the draft/noindex cases above: a query default that got
        // "simplified" to drop the TRASHED exclusion would advertise a URL that
        // 404s the moment a crawler follows it.
        WeblogEntry trashed = TestUtils.setupWeblogEntry("seoTrashedEntry",
                TestUtils.getManagedWebsite(weblog).getWeblogCategories().iterator().next(),
                PubStatus.TRASHED, weblog, user);
        TestUtils.endSession(true);

        ResponseEntity<String> response = controller.weblogSitemap(weblog.getHandle(), new MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertNotNull(body);
        Document doc = parse(body);
        assertEquals("urlset", doc.getDocumentElement().getLocalName());
        assertTrue(body.contains(
                "<loc>" + ABSOLUTE_CONTEXT + "/" + weblog.getHandle() + "/</loc>"),
                "The weblog home page must be listed:\n" + body);
        assertTrue(body.contains("/entry/" + published.getAnchor()),
                "The published entry's permalink must be listed:\n" + body);
        assertFalse(body.contains(draft.getAnchor()),
                "Draft entries must not leak into the sitemap:\n" + body);
        assertFalse(body.contains(noindexed.getAnchor()),
                "noindex entries must be excluded from the sitemap:\n" + body);
        assertFalse(body.contains(trashed.getAnchor()),
                "Trashed entries must not be advertised to crawlers:\n" + body);
    }

    @Test
    void weblogSitemapEmitsAnImageEntryForTheFeaturedImage() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "seo-featured.jpg");
        WeblogEntry entry = TestUtils.setupWeblogEntry("seoFeaturedEntry", weblog, user);
        entry = TestUtils.getManagedWeblogEntry(entry);
        entry.setFeaturedImageId(image.getId());
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);

        String body = controller.weblogSitemap(weblog.getHandle(), new MockHttpServletRequest()).getBody();

        assertNotNull(body);
        Document doc = parse(body);
        assertEquals(1, doc.getElementsByTagNameNS(
                "http://www.google.com/schemas/sitemap-image/1.1", "image").getLength(),
                "Exactly one image:image element must be emitted:\n" + body);
        assertTrue(body.contains("<image:loc>" + ABSOLUTE_CONTEXT + "/" + weblog.getHandle()
                + "/mediaresource/" + image.getId() + "?w=1600</image:loc>"),
                "The image loc must be the media-resource URL of the largest rendition:\n"
                        + body);
    }

    @Test
    void weblogSitemapExcludesAFeaturedImageInAPrivateDirectory() throws Exception {
        MediaFile hidden = TestUtils.setupImageMediaFile(weblog, "seo-private.jpg", "seoprivdir");
        MediaFile managed = TestUtils.weblogger().getMediaFileManager()
                .getMediaFile(hidden.getId());
        managed.getDirectory().setPrivate(true);
        WeblogEntry entry = TestUtils.setupWeblogEntry("seoPrivateImageEntry", weblog, user);
        entry = TestUtils.getManagedWeblogEntry(entry);
        entry.setFeaturedImageId(hidden.getId());
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);

        String body = controller.weblogSitemap(weblog.getHandle(), new MockHttpServletRequest()).getBody();

        assertNotNull(body);
        parse(body);
        assertTrue(body.contains("/entry/" + entry.getAnchor()),
                "The entry itself must still be listed:\n" + body);
        assertFalse(body.contains("image:loc"),
                "A featured image living in a private directory must never be "
                        + "advertised to crawlers:\n" + body);
    }

    @Test
    void weblogSitemapSkipsAFeaturedImageThatNoLongerExists() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("seoGoneImageEntry", weblog, user);
        entry = TestUtils.getManagedWeblogEntry(entry);
        entry.setFeaturedImageId("no-such-media-file-id");
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);

        String body = controller.weblogSitemap(weblog.getHandle(), new MockHttpServletRequest()).getBody();

        assertNotNull(body);
        parse(body);
        assertTrue(body.contains("/entry/" + entry.getAnchor()),
                "The entry itself must still be listed:\n" + body);
        assertFalse(body.contains("image:loc"),
                "A dangling featuredImageId must not produce an image entry:\n" + body);
    }

    @Test
    void weblogSitemapReturns404ForANonexistentHandle() {
        // valid handle syntax, no such weblog: exercises the plain null-lookup
        // path, not the invalid-handle exception below
        ResponseEntity<String> response = controller.weblogSitemap("nosuchweblog", new MockHttpServletRequest());
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void weblogSitemapReturns404ForASyntacticallyInvalidHandle() {
        // dashes fail getWeblogByHandle's alphanumeric validation, which
        // throws; crawlers probing /sitemap-*.xml must get a 404, not a 500
        ResponseEntity<String> response = controller.weblogSitemap("no-such-weblog-handle", new MockHttpServletRequest());
        assertEquals(404, response.getStatusCode().value());
    }

    // --- security metadata (what PressroomHandlerInterceptor enforces) ---

    @Test
    void seoEndpointsAreServableToAnonymousReaders() {
        assertFalse(controller.isUserRequired(),
                "Crawlers are not logged in; the SEO surface must not require a user");
        assertFalse(controller.isWeblogRequired(),
                "The routes carry no weblog request parameter");
        assertTrue(controller.requiredGlobalPermissionActions().isEmpty(),
                "No global permission may gate the SEO surface");
    }

    // --- business-tier failures (mocked: the real tier cannot be made to throw) ---

    @Test
    void sitemapIndexReturns500WhenTheWeblogListCannotBeLoaded() throws Exception {
        Weblogger broken = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        when(broken.getWeblogManager()).thenReturn(weblogManager);
        when(weblogManager.getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new WebloggerException("database down"));
        // The controller asks the facade's registry whether the request is on
        // a custom domain before listing; a registry over the same failing
        // manager answers "no" (its rebuild failure is logged, not thrown).
        when(broken.getVirtualHostRegistry()).thenReturn(new VirtualHostRegistry(weblogManager));
        SeoController failing = new SeoController();
        inject(failing, "weblogger", broken);

        ResponseEntity<String> response = failing.sitemapIndex(new MockHttpServletRequest());

        assertEquals(500, response.getStatusCode().value(),
                "A failing weblog query must surface as a server error, not an empty index");
    }

    @Test
    void weblogSitemapReturns500WhenTheEntryQueryFails() throws Exception {
        Weblogger broken = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        when(broken.getWeblogManager()).thenReturn(weblogManager);
        when(broken.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogManager.getWeblogByHandle("mockblog", Boolean.TRUE))
                .thenReturn(activeWeblog("mockblog"));
        when(broken.getUrlStrategy()).thenReturn(mock(URLStrategy.class));
        when(entryManager.getSitemapEntries(any(), anyInt()))
                .thenThrow(new WebloggerException("database down"));
        SeoController failing = new SeoController();
        inject(failing, "weblogger", broken);

        ResponseEntity<String> response = failing.weblogSitemap("mockblog", new MockHttpServletRequest());

        assertEquals(500, response.getStatusCode().value(),
                "A failing entry query must surface as a server error, not a truncated sitemap");
    }

    /**
     * Fail closed: a build that fails part-way (entries read, pages not) is a
     * 500 and leaves nothing in the cache, so the next request builds the
     * whole document afresh rather than serving a partial one.
     */
    @Test
    void aSitemapBuildThatFailsIsNotCached() throws Exception {
        Weblogger flaky = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        WeblogPageManager pageManager = mock(WeblogPageManager.class);
        URLStrategy urlStrategy = mock(URLStrategy.class);
        when(flaky.getWeblogManager()).thenReturn(weblogManager);
        when(flaky.getWeblogEntryManager()).thenReturn(entryManager);
        when(flaky.getWeblogPageManager()).thenReturn(pageManager);
        when(flaky.getUrlStrategy()).thenReturn(urlStrategy);
        Weblog mockWeblog = activeWeblog("mockblog");
        mockWeblog.setLastModified(new java.util.Date(1_700_000_000_000L));
        when(weblogManager.getWeblogByHandle("mockblog", Boolean.TRUE)).thenReturn(mockWeblog);
        when(urlStrategy.getWeblogURL(mockWeblog, null, true)).thenReturn("https://example.com/mockblog/");
        when(urlStrategy.getWeblogEntryURL(mockWeblog, null, "kept", true))
                .thenReturn("https://example.com/mockblog/entry/kept");
        when(entryManager.getSitemapEntries(mockWeblog, SeoController.MAX_SITEMAP_URLS))
                .thenReturn(List.of(new SitemapEntry("kept", null, null, false, null, null)));
        when(pageManager.getPublishedPages(mockWeblog))
                .thenThrow(new WebloggerException("database down"))
                .thenReturn(List.of());
        SeoController controller = new SeoController();
        inject(controller, "weblogger", flaky);

        assertEquals(500, controller.weblogSitemap("mockblog", new MockHttpServletRequest())
                .getStatusCode().value());
        ResponseEntity<String> retry = controller.weblogSitemap("mockblog", new MockHttpServletRequest());

        assertEquals(200, retry.getStatusCode().value());
        assertTrue(retry.getBody().contains("<loc>https://example.com/mockblog/entry/kept</loc>"),
                "the retry must build the whole document:\n" + retry.getBody());
        verify(entryManager, times(2)).getSitemapEntries(mockWeblog, SeoController.MAX_SITEMAP_URLS);
    }

    /**
     * Two edge cases in one document, asserted separately: entries and
     * weblogs without any dates must omit {@code lastmod}
     * rather than emit an empty element; and a URL containing {@code &} must
     * be escaped on the wire yet round-trip back to the original through a
     * real XML parser.
     */
    @Test
    void weblogSitemapSurvivesMissingDatesAndAmpersands() throws Exception {
        String rawEntryURL = "http://localhost:8080/pressroom/mockblog/entry/mock-entry?a=1&b=2";

        Weblogger broken = mock(Weblogger.class);
        WeblogManager weblogManager = mock(WeblogManager.class);
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        WeblogPageManager pageManager = mock(WeblogPageManager.class);
        URLStrategy urlStrategy = mock(URLStrategy.class);
        when(broken.getWeblogManager()).thenReturn(weblogManager);
        when(broken.getWeblogEntryManager()).thenReturn(entryManager);
        when(broken.getWeblogPageManager()).thenReturn(pageManager);
        when(broken.getUrlStrategy()).thenReturn(urlStrategy);

        Weblog mockWeblog = activeWeblog("mockblog");
        when(weblogManager.getWeblogByHandle("mockblog", Boolean.TRUE)).thenReturn(mockWeblog);
        when(pageManager.getPublishedPages(mockWeblog)).thenReturn(List.of());

        // no pubTime, no updateTime -- appendLastmod must skip the element
        when(entryManager.getSitemapEntries(mockWeblog, SeoController.MAX_SITEMAP_URLS))
                .thenReturn(List.of(new SitemapEntry("mock-entry", null, null, false, null, null)));
        when(urlStrategy.getWeblogURL(mockWeblog, null, true))
                .thenReturn("http://localhost:8080/pressroom/mockblog/");
        when(urlStrategy.getWeblogEntryURL(mockWeblog, null, "mock-entry", true))
                .thenReturn(rawEntryURL);

        SeoController failing = new SeoController();
        inject(failing, "weblogger", broken);

        ResponseEntity<String> response = failing.weblogSitemap("mockblog", new MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(-1, response.getHeaders().getLastModified(),
                "a weblog with no lastModified has none to report");
        String body = response.getBody();
        assertNotNull(body);
        Document doc = parse(body);
        assertTrue(body.contains("/entry/mock-entry"),
                "The entry itself must still be listed:\n" + body);
        assertFalse(body.contains("<lastmod>"),
                "With no dates anywhere, no lastmod element may be emitted:\n" + body);
        assertTrue(body.contains("?a=1&amp;b=2</loc>"),
                "The ampersand must be escaped on the wire:\n" + body);
        assertTrue(locTexts(doc).contains(rawEntryURL),
                "The escaped loc must round-trip back to the original URL through "
                        + "a real XML parser:\n" + body);
    }

    // --- support ---

    private static Weblog activeWeblog(String handle) {
        Weblog mockWeblog = new Weblog();
        mockWeblog.setHandle(handle);
        mockWeblog.setActive(Boolean.TRUE);
        mockWeblog.setVisible(Boolean.TRUE);
        mockWeblog.setLastModified(null);
        return mockWeblog;
    }

    /** The decoded text of every (namespaced) {@code loc} element. */
    private static List<String> locTexts(Document doc) {
        NodeList locs = doc.getElementsByTagNameNS(
                "http://www.sitemaps.org/schemas/sitemap/0.9", "loc");
        java.util.ArrayList<String> texts = new java.util.ArrayList<>();
        for (int i = 0; i < locs.getLength(); i++) {
            texts.add(locs.item(i).getTextContent());
        }
        return texts;
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    /**
     * Sets {@code BaseController}'s protected {@code weblogger} field the way
     * Spring's autowiring would; this test package cannot see the field
     * directly and spring-test is not on the classpath.
     */
    private static void inject(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new IllegalStateException("No field '" + name + "' on " + target.getClass());
    }
}
