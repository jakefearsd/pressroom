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

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.StrictReferences;
import com.wikantik.pressroom.ui.rendering.velocity.InvalidReferenceRecorder;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's live preview asks the preview servlet for a <em>shell</em>:
 * the theme's own stylesheet and asset macros wrapped around one empty
 * article, which the editor then fills over {@code postMessage} with the
 * fragment {@code entryEdit!preview.rol} rendered. Nothing else about the
 * theme's page is drawn -- no header, no nav, no pager -- because the shell
 * is a typography surface, not a page.
 *
 * <p>Two rungs, mirroring the {@code _page} ladder in {@code PageServlet}: a
 * theme may ship its own {@code _preview} (which knows its own reading-column
 * classes), and a theme that has never heard of the editor falls back to the
 * shared {@code WEB-INF/velocity/templates/weblog/preview.vm}. Both are
 * pinned here, along with the regression guard that a request <em>without</em>
 * the parameter still renders the ordinary preview page.
 */
class PreviewShellRenderingTest {

    /**
     * What the shipped templates leave unresolved on these renders, each with its reason.
     * A "KNOWN DEFECT" entry is a bug recorded here, not a rationale.
     */
    private static final Set<String> EXPECTED_REFERENCES = Set.of(
            "journal:Weblog|standard: $model.tags", // null when the view is not a tag view; tested with #if/#elseif
            "journal:Weblog|standard: $model.weblogCategory", // null when the view is not a category view; tested with #if/#elseif
            "journal:Weblog|standard: $model.weblogDateLabel", // null when the view is not a date archive; tested with #elseif
            "journal:Weblog|standard: $pager.nextLink", // null on the last page; tested with #if
            "journal:Weblog|standard: $pager.prevLink", // null on the first page; tested with #if
            "weblog.vm: $colophonFacts.copyrightText", // null unless the weblog sets copyright text; tested with $utils.isNotEmpty
            "weblog.vm: $colophonFacts.privacyUrl", // null unless a privacy URL is configured; tested with $utils.isNotEmpty
            "weblog.vm: $model.page", // null when the view is not a static page; tested with #if / negated in #set
            "weblog.vm: $model.tags", // null when the view is not a tag view; tested with #if/#elseif
            "weblog.vm: $model.weblogCategory", // null when the view is not a category view; tested with #if/#elseif
            "weblog.vm: $model.weblogDate", // null when the view is not a date archive; tested with #if / negated in #set
            "weblog.vm: $seoBusinessJson", // null when the weblog has no business profile; tested with #if
            "weblog.vm: $utils.businessJsonLd()", // returns null when the weblog has no business profile; the result is tested with #if($seoBusinessJson)
            "weblog.vm: $weblog", // KNOWN DEFECT: showContentAssets takes no argument, so $weblog in its $config.getColophon($weblog) call is never bound; getColophon(null) falls back to the site colophon, which is why only the version (the same for every blog) is read and nothing visibly breaks
            "weblog.vm: $weblog.analyticsSiteId", // null unless analytics is configured; tested with $utils.isNotEmpty
            "weblog.vm: $weblog.newsletterListUuid", // null unless a newsletter list is configured; tested with #if
            "weblog.vm: seoBusinessJson" // #set of a null business JSON-LD; tested with #if
    );

    @BeforeAll
    static void clearInvalidReferenceRecorder() {
        InvalidReferenceRecorder.clear();
    }

    @AfterAll
    static void onlyTheExpectedReferencesWereUnresolved() {
        StrictReferences.assertRecordedExactly("PreviewShellRenderingTest", EXPECTED_REFERENCES);
    }

    private static final String PREVIEW_SERVLET = "/roller-ui/authoring/preview";

    private static final String HANDLE = "previewshellblog";

    /**
     * Journal's pinned permalink policy (see {@code JournalThemeRenderingTest}
     * {@code CSP_JOURNAL}) with one directive changed: the shell is
     * <em>meant</em> to be framed, by the editor on the same origin, so
     * {@code frame-ancestors 'none'} becomes {@code 'self'}. Everything else
     * is byte-identical on purpose -- a shell that relaxed anything further
     * would preview content under a policy the published page does not have,
     * which is the one way a preview can lie.
     */
    private static final String CSP_JOURNAL_PREVIEW =
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                    + "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                    + "img-src * data:; frame-src https://www.youtube-nocookie.com "
                    + "https://player.vimeo.com; font-src 'self'; base-uri 'self'; "
                    + "connect-src 'self'; form-action 'self'; frame-ancestors 'self'\">";

    /**
     * The same policy for travel and portfolio, which use system fonts only
     * and so name no {@code font-src} on any of their pages. Keeping the
     * shells' policies split the same way their permalinks are split is the
     * point: a shell that quietly added a directive its theme does not have
     * would preview under rules the published page never applies.
     */
    private static final String CSP_PREVIEW_NO_WEBFONT =
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                    + "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                    + "img-src * data:; frame-src https://www.youtube-nocookie.com "
                    + "https://player.vimeo.com; base-uri 'self'; "
                    + "connect-src 'self'; form-action 'self'; frame-ancestors 'self'\">";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("previewshelluser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void theShellCarriesTheThemeStylesheetAssetsAndArticle() throws Exception {
        String html = preview("/" + HANDLE + "/", "shell", "true");

        assertTrue(html.contains("<link rel=\"stylesheet\" href=\""),
                "the shell must link the weblog's own stylesheet:\n" + html);
        assertTrue(html.contains("journal-custom.css"),
                "the linked stylesheet must be the journal theme's:\n" + html);
        assertTrue(html.contains("photoswipe.css"),
                "gallery assets must ship, or a [gallery] previews unstyled:\n" + html);
        assertTrue(html.contains("leaflet"),
                "map assets must ship, or a [map] previews as an empty box:\n" + html);
        assertTrue(html.contains("<article id=\"previewArticle\" class=\"qj-prose\""),
                "the article must carry journal's own prose class:\n" + html);
        assertTrue(html.contains(CSP_JOURNAL_PREVIEW),
                "the shell's policy must be journal's permalink policy with "
                        + "frame-ancestors 'self':\n" + html);
        assertTrue(html.contains("addEventListener('message'"),
                "the shell listens for the editor's fragments:\n" + html);
        assertFalse(html.contains("qj-head"),
                "the shell is a typography surface, not a page -- no site chrome:\n" + html);
        assertNoUnresolvedReferences(html);
    }

    /**
     * The other two bundled themes, in one test rather than two: the fixture
     * is the expensive part and the assertion is identical bar the strings.
     *
     * <p>Worth having even though {@code ThemeCspCoverageTest} already scans
     * every theme's templates for a policy -- a source scan cannot tell that
     * {@code preview.vm} is reachable, that its macros resolve, or that the
     * class it names is the one the theme's stylesheet actually styles.
     */
    @Test
    void theOtherBundledThemesShipTheirOwnShellsToo() throws Exception {
        assertThemeShell("travel", "tg-entry-content", CSP_PREVIEW_NO_WEBFONT);
        assertThemeShell("portfolio", "pf-entry-content", CSP_PREVIEW_NO_WEBFONT);
    }

    @Test
    void aWeblogWhoseThemeShipsNoPreviewTemplateGetsTheSharedShell() throws Exception {
        useAThemeWithNoPreviewTemplate();

        String html = preview("/" + HANDLE + "/", "shell", "true");

        assertTrue(html.contains("<article id=\"previewArticle\" class=\"roller-preview-prose\""),
                "a theme that has never heard of the editor still previews, through the "
                        + "shared shell:\n" + html);
        assertTrue(html.contains("frontpage-custom.css"),
                "the shared shell still links the weblog's own stylesheet:\n" + html);
        assertTrue(html.contains("addEventListener('message'"),
                "the shared shell listens for fragments too:\n" + html);
        assertNoUnresolvedReferences(html);
    }

    /**
     * The parameter is the only thing that selects the shell. Without it the
     * preview servlet must behave exactly as it did before this feature
     * existed -- the theme's full default page, chrome and all.
     */
    @Test
    void withoutTheParameterTheOrdinaryPreviewPageIsUnchanged() throws Exception {
        String html = preview("/" + HANDLE + "/");

        assertTrue(html.contains("qj-head"),
                "the ordinary preview is still the theme's whole page:\n" + html);
        assertFalse(html.contains("previewArticle"),
                "nothing about the shell may leak into the ordinary preview:\n" + html);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Velocity in this codebase is lenient: {@code runtime.references.strict}
     * is unset and invalid-reference logging is off, so a reference to a
     * member that does not exist prints as literal text into the page with
     * nothing failing anywhere (see CLAUDE.md, "Templates"). These are new
     * templates whose only references are {@code $model.*} and {@code $url.*},
     * every one of which must resolve -- an unresolved stylesheet reference
     * would show up as an unstyled preview and no failing test at all.
     */
    private static void assertNoUnresolvedReferences(String html) {
        assertFalse(html.contains("$model."),
                "an unresolved $model reference leaked into the shell:\n" + html);
        assertFalse(html.contains("$url."),
                "an unresolved $url reference leaked into the shell:\n" + html);
    }

    /** One theme's shell: its own prose class, its own policy, no chrome. */
    private void assertThemeShell(String themeId, String proseClass, String csp)
            throws Exception {
        switchTheme(themeId);

        String html = preview("/" + HANDLE + "/", "shell", "true");

        assertTrue(html.contains("<article id=\"previewArticle\" class=\"" + proseClass + "\""),
                themeId + "'s shell must carry its own reading-column class:\n" + html);
        assertTrue(html.contains(csp), themeId + "'s shell policy:\n" + html);
        assertTrue(html.contains(themeId + "-custom.css"),
                themeId + "'s shell must link its own stylesheet:\n" + html);
        assertTrue(html.contains("addEventListener('message'"), html);
        assertNoUnresolvedReferences(html);
    }

    private String preview(String pathInfo, String... params) throws Exception {
        MockHttpServletRequest request =
                RenderingTestSupport.anonymousGet(PREVIEW_SERVLET, pathInfo);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request.setParameter(params[i], params[i + 1]);
        }
        MockHttpServletResponse response =
                RenderingTestSupport.execute(RenderingTestSupport.previewServlet(), request);
        assertEquals(200, response.getStatus(), "preview must render for " + pathInfo);
        return response.getContentAsString();
    }

    /**
     * Switches the weblog to the one bundled theme that ships no
     * {@code _preview} (frontpage; journal/portfolio/travel all ship one) --
     * the same weblog {@code PageRoutingTest} uses to reach the shared
     * {@code _page} fallback, for the same reason.
     */
    private void useAThemeWithNoPreviewTemplate() throws Exception {
        switchTheme("frontpage");
    }

    private void switchTheme(String themeId) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(themeId);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }
}
