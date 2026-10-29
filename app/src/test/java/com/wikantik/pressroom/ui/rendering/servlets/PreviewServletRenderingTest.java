package com.wikantik.pressroom.ui.rendering.servlets;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PreviewServlet is the authoring-side render of a weblog. It requires no
 * request principal (access control is URL-layer, in SecurityConfig's
 * authorization rules) — so these tests drive it exactly like the public
 * servlets.
 */
class PreviewServletRenderingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("previewuser");
        weblog = TestUtils.setupWeblog("previewblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void frontPagePreviewRendersPublishedEntry() throws Exception {
        TestUtils.setupWeblogEntry("preview-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/authoring/preview", "/previewblog");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("text/html"));
        assertTrue(response.getContentAsString().contains("preview-entry"),
                "preview must render the entry:\n" + response.getContentAsString());
    }

    @Test
    void themeParameterPreviewsAnotherTheme() throws Exception {
        TestUtils.setupWeblogEntry("themed-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/authoring/preview", "/previewblog");
        request.setParameter("theme", "portfolio");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("themed-entry"),
                "theme-preview must still render the weblog's entries");
    }

    @Test
    void permalinkPreviewRendersTheEntry() throws Exception {
        TestUtils.setupWeblogEntry("preview-permalink", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/authoring/preview", "/previewblog/entry/preview-permalink");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("preview-permalink"));
    }

    @Test
    void unknownWeblogPreviewIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/authoring/preview", "/nosuchblog");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);

        assertEquals(404, response.getStatus());
    }

    @Test
    void anImageShortcodeExpandsInThePreviewToo() throws Exception {
        // The preview renders through the same wrapper/transformation chain as
        // the live site (PreviewPageModel wraps the same WeblogEntry), so the
        // unconditional shortcode expander must already be live here -- authors
        // see the responsive figure before publishing.
        com.wikantik.pressroom.pojos.MediaFile image =
                TestUtils.setupImageMediaFile(weblog, "preview-hawk.jpg");
        com.wikantik.pressroom.pojos.WeblogEntry entry =
                TestUtils.setupWeblogEntry("shortcode-preview", weblog, user);
        entry.setText("before [image id=" + image.getId() + " caption=\"A hawk\"] after");
        com.wikantik.pressroom.TestUtils.weblogger()
                .getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/authoring/preview", "/previewblog/entry/shortcode-preview");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("<figure class=\"shortcode-image\">"),
                "the [image] shortcode must expand in the preview:\n" + body);
        assertTrue(decoded(body).contains("?w=480 480w"),
                "srcset must offer the 480w ladder rendition (hawk.jpg is 500w):\n" + body);
        assertTrue(body.contains("<figcaption>A hawk</figcaption>"), body);
        assertFalse(body.contains("[image id="),
                "the raw shortcode text must not leak into the preview:\n" + body);
    }

    @Test
    void draftEntryPreviewRendersViaPreviewEntryParam() throws Exception {
        // WeblogPreviewRequest reads ?previewEntry=<anchor> and
        // PreviewPageModel routes it through WeblogEntriesPreviewPager, which
        // (unlike the public pager PageModel uses) does not filter by
        // PubStatus -- so a DRAFT entry renders here. This is the inverse of
        // PageServletRenderingTest#draftEntryPermalinkIsNotFound.
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        WeblogCategory category = managed.getWeblogCategories().iterator().next();
        TestUtils.setupWeblogEntry("draft-preview-entry", category, PubStatus.DRAFT, managed, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/authoring/preview", "/previewblog");
        request.setParameter("previewEntry", "draft-preview-entry");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.previewServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("draft-preview-entry"),
                "draft preview must render the DRAFT entry:\n" + response.getContentAsString());
    }

    /**
     * The rendered page with HTML entities decoded.
     *
     * <p>The sanitizer entity-encodes characters inside attribute values --
     * {@code ?w=480} is serialized as {@code ?w&#61;480}. A browser decodes
     * those before it parses a srcset or resolves a URL, so the page works;
     * an assertion written against the author's literal text would not.
     * Decoding first keeps the assertion about meaning rather than about the
     * serializer's choices.
     */
    private static String decoded(String body) {
        return org.apache.commons.text.StringEscapeUtils.unescapeHtml4(body);
    }
}
