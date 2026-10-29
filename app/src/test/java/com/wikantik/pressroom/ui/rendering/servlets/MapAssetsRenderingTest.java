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
package com.wikantik.pressroom.ui.rendering.servlets;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the {@code #showSeoHead} call sites across the bundled themes and
 * asserts that {@code #showMapAssets} ships the Leaflet assets
 * <em>exactly once</em> per page: the webjar stylesheet, the webjar
 * script URL the bootstrap injects, the three marker images pinned onto
 * {@code L.Icon.Default}, the configured tile template, the OSM attribution
 * the tile policy requires, and the {@code .travel-map} presence guard that
 * keeps map-less pages from downloading any of it.
 *
 * <p>Missing one call site is a silent failure: the {@code [map]} div still
 * renders and simply never becomes a map. journal AND portfolio each have
 * FOUR top-level templates ({@code page.vm} included, alongside
 * {@code weblog.vm}/{@code permalink.vm}/{@code searchresults.vm}); this
 * file exercises the other three for both, and each theme's {@code page.vm}
 * head coverage lives in its own rendering test instead
 * ({@code JournalThemeRenderingTest}; {@code PortfolioThemeRenderingTest}'s
 * shared {@code assertPortfolioHead} carries the Leaflet check for all its
 * templates including the page). frontpage has its own {@code _header}.
 *
 * <p>This file also owns the CSP half of the change: Leaflet paints aborted
 * and out-of-range tiles with a {@code data:} GIF placeholder, and per CSP3
 * the {@code *} wildcard does not match {@code data:}, so every head that
 * declares a policy needs {@code img-src * data:} or the browser console
 * fills with violations on every pan and zoom.
 */
class MapAssetsRenderingTest {

    private static final String HANDLE = "mapassetblog";

    private static final String LEAFLET_CSS = "/webjars/leaflet/1.9.4/dist/leaflet.css";
    private static final String LEAFLET_JS = "/webjars/leaflet/1.9.4/dist/leaflet.js";
    private static final String MARKER_ICON =
            "/webjars/leaflet/1.9.4/dist/images/marker-icon.png";
    private static final String MARKER_ICON_2X =
            "/webjars/leaflet/1.9.4/dist/images/marker-icon-2x.png";
    private static final String MARKER_SHADOW =
            "/webjars/leaflet/1.9.4/dist/images/marker-shadow.png";

    /**
     * The cheap lazy-init guard: no .travel-map on the page, no leaflet.js.
     *
     * <p>The {@code :not([data-roller-map])} half arrived with the editor's
     * live preview, which re-runs this initialiser over swapped-in content --
     * an already-initialised container must be skipped, because
     * {@code L.map()} throws on one. It is part of the guard rather than an
     * extra check, so it is pinned here with it.
     */
    private static final String MAP_GUARD =
            ".querySelectorAll('.travel-map:not([data-roller-map])')";

    private static final String TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";

    /** Required by the OSM tile usage policy; Leaflet renders it into its own control. */
    private static final String ATTRIBUTION =
            "https://www.openstreetmap.org/copyright";

    /**
     * The directive this test is actually about.
     *
     * <p>Was the whole policy string, verbatim, which pinned far more than the
     * subject: it made every theme's policy identical by assertion, so a theme
     * could not name a directive its own assets need without failing a map
     * test. {@code journal} needs {@code font-src} for its webfonts and this
     * check was what stood in the way. What matters here is only that
     * {@code data:} images are allowed; the directives every theme genuinely
     * shares ({@code script-src}, {@code connect-src}, and having a policy at
     * all) are asserted for every template by {@code ThemeCspCoverageTest},
     * and the portfolio and travel themes still pin their own policies whole.
     */
    private static final String CSP_DATA_IMAGES = "img-src * data:;";

    /**
     * The single authorised CSP widening for this wave: frame-src limited to
     * the two providers {@code [video]} can target. Asserted wherever
     * {@link #CSP_DATA_IMAGES} is, for the five themes {@code #showEmbedAssets}
     * shipped to -- frontpage is out of scope for this wave and keeps its
     * existing policy unchanged.
     */
    private static final String CSP_VIDEO_FRAMES =
            "frame-src https://www.youtube-nocookie.com https://player.vimeo.com;";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("mapassetuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
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
                .anonymousGet("/pressroom-ui/rendering/page", pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "page must render for " + pathInfo);
        return response.getContentAsString();
    }

    private String search() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/search", "/" + HANDLE);
        request.setParameter("q", "anything");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);
        assertEquals(200, response.getStatus());
        return response.getContentAsString();
    }

    private void switchTheme(String themeName) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(themeName);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    private void entryWithText(String anchor, String text) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, weblog, user);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setText(text);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
    }

    private static void assertOnce(String body, String needle, String head, String what) {
        assertEquals(1, StringUtils.countMatches(body, needle),
                head + " must carry " + what + " exactly once:\n" + body);
    }

    private static void assertAssetsExactlyOnce(String body, String head) {
        assertOnce(body, LEAFLET_CSS, head, "the Leaflet stylesheet");
        assertOnce(body, LEAFLET_JS, head, "the Leaflet script URL");
        assertOnce(body, MARKER_ICON_2X, head, "the retina marker icon");
        assertOnce(body, MARKER_SHADOW, head, "the marker shadow");
        assertOnce(body, MAP_GUARD, head, "the .travel-map lazy-init guard");
        assertOnce(body, TILE_URL, head, "the configured tile template");
        assertOnce(body, ATTRIBUTION, head, "the OSM attribution");
        // marker-icon.png is a substring of marker-icon-2x.png, so count the
        // quoted form to keep this an honest exactly-once assertion
        assertOnce(body, MARKER_ICON + "'", head, "the marker icon");
        assertTrue(body.contains("maxZoom: 19"),
                head + " must raise Leaflet's default maxZoom of 18 to the z19 the "
                        + "OSM standard layer actually renders:\n" + body);
        // a Velocity error would leak the raw directive text into the page
        assertFalse(body.contains("#showMapAssets"),
                head + " leaked the macro directive:\n" + body);
        assertFalse(body.contains("$config.mapTileUrl"),
                head + " leaked the tile-URL reference; $config is not in scope:\n" + body);
    }

    // ------------------------------------------------------------ theme heads

    @Test
    void journalFrontPageShipsTheMapAssetsOnce() throws Exception {
        assertAssetsExactlyOnce(render("/" + HANDLE), "journal weblog.vm");
    }

    @Test
    void journalPermalinkShipsTheMapAssetsOnce() throws Exception {
        entryWithText("map-asset-entry", "<p>nothing to see</p>");
        assertAssetsExactlyOnce(render("/" + HANDLE + "/entry/map-asset-entry"),
                "journal permalink.vm");
    }

    @Test
    void journalSearchResultsShipTheMapAssetsOnce() throws Exception {
        assertAssetsExactlyOnce(search(), "journal searchresults.vm");
    }

    @Test
    void portfolioFrontPageShipsTheMapAssetsOnce() throws Exception {
        switchTheme("portfolio");
        assertAssetsExactlyOnce(render("/" + HANDLE), "portfolio weblog.vm");
    }

    @Test
    void portfolioPermalinkShipsTheMapAssetsOnce() throws Exception {
        switchTheme("portfolio");
        entryWithText("portfolio-map-entry", "<p>nothing to see</p>");
        assertAssetsExactlyOnce(render("/" + HANDLE + "/entry/portfolio-map-entry"),
                "portfolio permalink.vm");
    }

    @Test
    void portfolioSearchResultsShipTheMapAssetsOnce() throws Exception {
        switchTheme("portfolio");
        assertAssetsExactlyOnce(search(), "portfolio searchresults.vm");
    }

    @Test
    void frontpageThemeShipsTheMapAssetsOnce() throws Exception {
        switchTheme("frontpage");
        assertAssetsExactlyOnce(render("/" + HANDLE), "frontpage _header.vm");
    }

    // -------------------------------------------------------------- the guard

    /**
     * The tradeoff the macro documents: the stylesheet ships from the head
     * even on map-less pages (one small cacheable request), but leaflet.js is
     * 147 KB and must stay behind the guard -- nothing on this page carries a
     * .travel-map, so the script element is never injected.
     */
    @Test
    void aMapLessPageCarriesTheGuardButNoMap() throws Exception {
        String body = render("/" + HANDLE);
        assertTrue(body.contains(MAP_GUARD), body);
        assertFalse(body.contains("<div class=\"travel-map\""),
                "no map markup may appear without a [map] entry:\n" + body);
    }

    /**
     * The other half of the contract: an entry that really does carry a
     * {@code [map]} puts the div the guard looks for on the same page as the
     * assets. This is the seam between T2's emitter and T3's initialiser --
     * if either side renamed the class the map would go dark in silence.
     */
    @Test
    void anEntryWithAMapPutsTheGuardedClassOnThePage() throws Exception {
        entryWithText("real-map-entry",
                "<p>[map zoom=\"12\"][pin lat=\"48.8584\" lng=\"2.2945\" "
                        + "label=\"Eiffel Tower\"][/map]</p>");
        String body = render("/" + HANDLE + "/entry/real-map-entry");
        assertTrue(body.contains("<div class=\"travel-map\""),
                "the [map] shortcode must survive the sanitizer:\n" + body);
        assertTrue(body.contains("data-pins="),
                "the pin payload must reach the reader:\n" + body);
        assertAssetsExactlyOnce(body, "journal permalink with a map");
    }

    // ------------------------------------------- re-runnable initialisers

    /**
     * The editor's live preview replaces {@code #previewArticle}'s innerHTML
     * on every push, and the asset scripts bind on {@code DOMContentLoaded} --
     * which fired long before. So each of the four macros exposes a named
     * initialiser and chains it onto {@code window.rollerPreviewInit}, the seam
     * {@code #showPreviewShellScript} calls after each swap.
     *
     * <p>Asserted on a public permalink rather than on the shell, deliberately:
     * the shell only gets these functions because every ordinary theme head
     * emits them, and a macro that defined its initialiser only under some
     * preview-only condition would pass a shell-side test while leaving the
     * published page's own galleries and maps unchanged. This also pins the
     * other half of the bargain -- the load-time call is still there, so the
     * public page keeps initialising exactly once with no preview involved.
     */
    @Test
    void everyAssetMacroExposesARerunnableInitialiser() throws Exception {
        entryWithText("rerunnable-init-entry", "<p>nothing to see</p>");
        String body = render("/" + HANDLE + "/entry/rerunnable-init-entry");

        for (String initialiser : new String[] {
                "rollerInitGalleries", "rollerInitMaps", "rollerInitEmbeds", "rollerInitContent" }) {
            assertTrue(body.contains("function " + initialiser + "("),
                    "journal permalink must declare " + initialiser + ":\n" + body);
            assertTrue(body.contains(initialiser + "(document)"),
                    "the public page must still initialise once on load via "
                            + initialiser + ":\n" + body);
        }
        assertEquals(4, StringUtils.countMatches(body, "window.rollerPreviewInit ="),
                "each of the four macros must chain onto the preview seam:\n" + body);
    }

    // ----------------------------------------------------------------- CSP

    /**
     * Leaflet's placeholder for aborted/out-of-range tiles is
     * {@code data:image/gif;base64,R0lGODlh...}, and CSP3 says the
     * {@code *} source expression does not match {@code data:}. Without the
     * explicit scheme every pan and zoom logs a violation.
     */
    @Test
    void everyDeclaringHeadAllowsDataUrisForImages() throws Exception {
        String journalBody = render("/" + HANDLE);
        assertTrue(journalBody.contains(CSP_DATA_IMAGES),
                "journal weblog.vm must allow data: images");
        assertTrue(journalBody.contains(CSP_VIDEO_FRAMES),
                "journal weblog.vm must allow the video providers' frames");

        // frontpage is out of scope for the [video] CSP widening -- it renders
        // through $site, a different model, and #showEmbedAssets was not added
        // to it.
        switchTheme("frontpage");
        assertTrue(render("/" + HANDLE).contains(CSP_DATA_IMAGES),
                "frontpage _header.vm must allow data: images");

        switchTheme("portfolio");
        String portfolioBody = render("/" + HANDLE);
        assertTrue(portfolioBody.contains(CSP_DATA_IMAGES),
                "portfolio weblog.vm must allow data: images");
        assertTrue(portfolioBody.contains(CSP_VIDEO_FRAMES),
                "portfolio weblog.vm must allow the video providers' frames");
        String searchBody = search();
        assertTrue(searchBody.contains(CSP_DATA_IMAGES),
                "portfolio searchresults.vm must allow data: images");
        assertTrue(searchBody.contains(CSP_VIDEO_FRAMES),
                "portfolio searchresults.vm must allow the video providers' frames");
        entryWithText("csp-map-entry", "<p>nothing to see</p>");
        String permalinkBody = render("/" + HANDLE + "/entry/csp-map-entry");
        assertTrue(permalinkBody.contains(CSP_DATA_IMAGES),
                "portfolio permalink.vm must allow data: images");
        assertTrue(permalinkBody.contains(CSP_VIDEO_FRAMES),
                "portfolio permalink.vm must allow the video providers' frames");
    }

    /**
     * Guards the guard above: the declaring files must not have drifted
     * back to a bare {@code img-src *}, which would still contain the rest of
     * the policy and pass a naive substring check elsewhere.
     */
    @Test
    void noHeadStillShipsTheOldWildcardOnlyImageSource() throws Exception {
        assertFalse(render("/" + HANDLE).contains("img-src *;"),
                "img-src * alone no longer covers Leaflet's data: placeholder");
    }

    /**
     * The macro must not depend on the runtime properties table: the tile URL
     * is static config, so it renders identically whatever the admin has (or
     * has not) saved.
     */
    @Test
    void theTileTemplateSurvivesAnEmptyRuntimeConfig() throws Exception {
        assertTrue(WebloggerRuntimeConfig.getProperty("travel.map.tileUrl") == null
                        || WebloggerRuntimeConfig.getProperty("travel.map.tileUrl").isEmpty(),
                "travel.map.tileUrl must NOT be a runtime property: a runtimeConfigDefs "
                        + "entry needs a configForm.* message key that MessageKeyTest "
                        + "counts as an orphan, and that ratchet is fully consumed.");
        assertTrue(render("/" + HANDLE).contains(TILE_URL),
                "the head must still carry the static default tile template");
    }
}
