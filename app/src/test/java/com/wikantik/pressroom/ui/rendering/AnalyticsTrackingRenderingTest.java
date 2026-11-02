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
package com.wikantik.pressroom.ui.rendering;

import java.util.ArrayList;
import java.util.List;

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
 * The tracker tag is automatic: the showAnalyticsTrackingCode macro asks
 * {@code $analytics} whether this blog is registered with Umami and, if so,
 * builds the script tag from the website id, the same-origin base path and the
 * blog's real host. Nothing is read off the weblog row, and no render calls
 * Umami (the stand-in manager throws on anything but the in-memory reads).
 */
class AnalyticsTrackingRenderingTest {

    private static final List<String> THEMES = List.of("journal", "portfolio", "travel", "frontpage");
    private static final String HANDLE = "trackingrenderblog";
    private static final String SITE_ID = "11111111-2222-3333-4444-555555555555";

    private static final String CSP_STANDARD =
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                    + "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                    + "img-src * data:; frame-src https://www.youtube-nocookie.com "
                    + "https://player.vimeo.com; base-uri 'self'; connect-src 'self'; "
                    + "form-action 'self'; frame-ancestors 'none'\">";

    private static final String CSP_JOURNAL =
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                    + "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                    + "img-src * data:; frame-src https://www.youtube-nocookie.com "
                    + "https://player.vimeo.com; font-src 'self'; base-uri 'self'; "
                    + "connect-src 'self'; form-action 'self'; frame-ancestors 'none'\">";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("trackingrenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
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

    private static String tag(String websiteId, String host) {
        return "<script defer src=\"/analytics/script.js\" data-website-id=\"" + websiteId
                + "\" data-host-url=\"/analytics\"" + (host == null ? "" : " data-domains=\"" + host + "\"")
                + " data-performance=\"true\"></script>";
    }

    private void edit(String theme, String customDomain) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(theme);
        managed.setCustomDomain(customDomain);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private String renderHomePage() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", "/" + HANDLE);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "home page must render");
        return response.getContentAsString();
    }

    private void savePage(String slug) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle("About");
        page.setContent("Body of " + slug);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private String renderPage(String slug) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", "/" + HANDLE + "/" + slug);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "page view must render");
        return response.getContentAsString();
    }

    @Test
    void aPageViewCarriesTheTrackerToo() throws Exception {
        edit("journal", null);
        savePage("about");
        try (StubAnalytics _ = StubAnalytics.configured().register(weblog.getId(), SITE_ID)) {
            String body = renderPage("about");
            assertTrue(body.contains(tag(SITE_ID, "localhost")),
                    "journal's own _page override must carry the tracker:\n" + body);
            assertTrue(body.contains(CSP_JOURNAL), "the page view's CSP must be byte-unchanged:\n" + body);
        }
    }

    @Test
    void anUnregisteredBlogsPageViewCarriesNone() throws Exception {
        edit("journal", null);
        savePage("about");
        try (StubAnalytics _ = StubAnalytics.configured()) {
            assertFalse(renderPage("about").contains("/analytics/"));
        }
    }

    @Test
    void registeredBlogCarriesFullTracker() throws Exception {
        edit("journal", null);
        try (StubAnalytics _ = StubAnalytics.configured().register(weblog.getId(), SITE_ID)) {
            String body = renderHomePage();
            // site URL in the rendering suite is http://localhost:8080/pressroom
            assertTrue(body.contains(tag(SITE_ID, "localhost")), body);
        }
    }

    @Test
    void unregisteredBlogCarriesNone() throws Exception {
        edit("journal", null);
        try (StubAnalytics _ = StubAnalytics.configured()) {
            String body = renderHomePage();
            assertFalse(body.contains("/analytics/"), body);
            assertFalse(body.contains("<script defer"), body);
        }
    }

    @Test
    void notConfiguredCarriesNone() throws Exception {
        edit("journal", null);
        try (StubAnalytics _ = StubAnalytics.notConfigured().register(weblog.getId(), SITE_ID)) {
            String body = renderHomePage();
            assertFalse(body.contains("/analytics/"), body);
        }
    }

    @Test
    void everyBundledThemeEmitsTheTag() throws Exception {
        List<String> failures = new ArrayList<>();
        try (StubAnalytics _ = StubAnalytics.configured().register(weblog.getId(), SITE_ID)) {
            for (String theme : THEMES) {
                try {
                    edit(theme, null);
                    String body = renderHomePage();
                    assertTrue(body.contains(tag(SITE_ID, "localhost")), theme + ": " + body);
                    assertFalse(body.contains("#showAnalyticsTrackingCode"), theme);
                } catch (Throwable failure) {
                    failures.add(theme + " -> " + failure);
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n  ", failures));
    }

    @Test
    void cspUnchanged() throws Exception {
        try (StubAnalytics _ = StubAnalytics.configured().register(weblog.getId(), SITE_ID)) {
            for (String theme : List.of("journal", "portfolio", "travel")) {
                edit(theme, null);
                String body = renderHomePage();
                assertTrue(body.contains(tag(SITE_ID, "localhost")), theme);
                assertTrue(body.contains("journal".equals(theme) ? CSP_JOURNAL : CSP_STANDARD),
                        theme + ": the CSP meta must be byte-unchanged:\n" + body);
            }
        }
    }

    @Test
    void customDomainBlogTracksItsOwnHost() throws Exception {
        edit("journal", "Blog.Example.COM:8443");
        try (StubAnalytics _ = StubAnalytics.configured().register(weblog.getId(), SITE_ID)) {
            String body = renderHomePage();
            assertTrue(body.contains(tag(SITE_ID, "blog.example.com")), body);
        }
    }

    @Test
    void trackerAttributesAreEscaped() throws Exception {
        edit("journal", null);
        try (StubAnalytics _ = StubAnalytics.configured().register(weblog.getId(), "a\"><b>x")) {
            String body = renderHomePage();
            assertFalse(body.contains("a\"><b>x"), body);
            assertTrue(body.contains("data-website-id=\"a&quot;&gt;&lt;b&gt;x\""), body);
        }
    }

    @Test
    void renderingNeverCallsAnalyticsService() throws Exception {
        // StubAnalytics throws on anything but the three in-memory reads
        for (String theme : THEMES) {
            edit(theme, null);
            try (StubAnalytics _ = StubAnalytics.configured().register(weblog.getId(), SITE_ID)) {
                assertTrue(renderHomePage().contains(SITE_ID), theme);
            }
        }
    }
}
