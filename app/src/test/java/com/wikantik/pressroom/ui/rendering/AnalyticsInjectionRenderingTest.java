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
 * {@code #showAnalyticsTrackingCode} building the Umami script tag from
 * {@code $weblog.analyticsSiteId} plus the startup-scoped
 * {@code $config.analyticsBasePath}/{@code analyticsScriptName} -- across all
 * three bundled themes' home pages AND the {@code [page]} view (journal's own
 * {@code _page} override), plus one pinned case for the "no id configured"
 * branch. This is the only branch the macro has; there is no free-text
 * fallback.
 *
 * <p>One test looping the themes rather than one method per theme --
 * same economical fixture pattern as {@code PageNavRenderingTest}.
 */
class AnalyticsInjectionRenderingTest {

    private static final List<String> THEMES =
            List.of("journal", "portfolio", "travel");

    private static final String HANDLE = "analyticsrenderblog";

    private static final String SITE_ID = "11111111-2222-3333-4444-555555555555";

    /** What #showAnalyticsTrackingCode builds when analyticsSiteId is set. */
    private static final String EXPECTED_SCRIPT_TAG =
            "<script defer src=\"/analytics/script.js\" "
                    + "data-website-id=\"" + SITE_ID + "\" "
                    + "data-host-url=\"/analytics\"></script>";

    /**
     * The CSP meta every bundled theme's home page carries (journal's head
     * adds font-src for its self-hosted Plex webfonts; see
     * {@link #CSP_JOURNAL}). Pinned byte-for-byte, the same convention
     * {@code PortfolioThemeRenderingTest}/{@code TravelThemeRenderingTest}
     * use for their own CSP directives -- proof that wiring analytics into
     * the head touched none of it.
     */
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

        user = TestUtils.setupUser("analyticsrenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.weblogger().getWeblogPageManager()
                .removePages(TestUtils.getManagedWebsite(weblog));
        TestUtils.endSession(true);
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- helpers

    private void setAnalyticsSiteId(String value) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setAnalyticsSiteId(value);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    private void switchTheme(String themeName) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(themeName);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    private String renderHomePage() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/" + HANDLE);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "home page must render");
        return response.getContentAsString();
    }

    private void savePage(String slug, String title) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent("Body of " + slug);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private String renderPage(String slug) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/" + HANDLE + "/" + slug);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "page view must render");
        return response.getContentAsString();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void everyBundledThemeInjectsTheScriptTagWhenASiteIdIsSet() throws Exception {
        setAnalyticsSiteId(SITE_ID);

        List<String> failures = new ArrayList<>();
        for (String theme : THEMES) {
            try {
                RenderingTestSupport.clearRenderCaches();
                switchTheme(theme);
                String body = renderHomePage();
                assertTrue(body.contains(EXPECTED_SCRIPT_TAG),
                        theme + ": expected the Umami script tag:\n" + body);
                assertTrue(body.contains(expectedCsp(theme)),
                        theme + ": CSP meta must be byte-unchanged:\n" + body);
                // a Velocity error resolving the macro would leak raw directives
                assertFalse(body.contains("#showAnalyticsTrackingCode"),
                        theme + ":\n" + body);
            } catch (Throwable failure) {
                failures.add(theme + " -> " + failure);
            }
        }

        assertTrue(failures.isEmpty(),
                "themes failed the analytics injection check:\n  "
                        + String.join("\n  ", failures));
    }

    @Test
    void aPageViewAlsoInjectsTheScriptTag() throws Exception {
        setAnalyticsSiteId(SITE_ID);
        savePage("about", "About");

        String body = renderPage("about");

        assertTrue(body.contains(EXPECTED_SCRIPT_TAG),
                "page.vm must inject the same Umami script tag as the theme home "
                        + "pages:\n" + body);
        assertTrue(body.contains(CSP_JOURNAL),
                "journal page.vm's CSP meta must be byte-unchanged:\n" + body);
    }

    @Test
    void noAnalyticsSiteIdMeansNoAnalyticsScriptAnywhere() throws Exception {
        setAnalyticsSiteId(null);
        savePage("about", "About");

        for (String theme : THEMES) {
            RenderingTestSupport.clearRenderCaches();
            switchTheme(theme);
            String body = renderHomePage();
            assertFalse(body.contains("/analytics/"),
                    theme + ": no site id means no Umami script must be injected:\n" + body);
        }

        RenderingTestSupport.clearRenderCaches();
        String pageBody = renderPage("about");
        assertFalse(pageBody.contains("/analytics/"),
                "no site id means page.vm must not inject a Umami script either:\n"
                        + pageBody);
    }

    private static String expectedCsp(String theme) {
        return "journal".equals(theme) ? CSP_JOURNAL : CSP_STANDARD;
    }
}
