package com.wikantik.pressroom.ui.rendering.servlets;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.ThemeResource;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.ui.core.PressroomContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The preview counterpart of ResourceServlet: it serves a weblog's uploads and
 * its theme's static files while an author is previewing a theme.
 *
 * <p>It had no tests at all -- 18 of 18 branches uncovered -- which is worth
 * saying plainly, because it is reachable by anyone who can reach the authoring
 * ui and it streams files off disk.
 *
 * <p>What is still not covered here is the theme-resource half. No bundled
 * theme declares a &lt;resource&gt; element, so {@code theme.getResource(...)}
 * returns null for journal, portfolio, travel and frontpage alike; reaching it
 * needs a custom theme directory built for the purpose. That is the same
 * fixture the theme-loading classes need and is deliberately left for when
 * that gets built.
 */
class PreviewResourceServletTest {

    private static final String PREVIEW_RESOURCE = "/pressroom-ui/authoring/previewresource";

    private final String handle = "previewresourceblog";
    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("previewresourceuser");
        weblog = TestUtils.setupWeblog(handle, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private MockHttpServletResponse get(String pathInfo, String... params) throws Exception {
        MockHttpServletRequest request =
                RenderingTestSupport.anonymousGet(PREVIEW_RESOURCE, pathInfo);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request.setParameter(params[i], params[i + 1]);
        }
        return RenderingTestSupport.execute(
                RenderingTestSupport.previewResourceServlet(), request);
    }

    private MediaFile uploadedImage(String name) throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, name);
        image.setOriginalPath("/" + name);
        MediaFileManager mfMgr = TestUtils.weblogger().getMediaFileManager();
        mfMgr.updateMediaFile(TestUtils.getManagedWebsite(weblog), image);
        TestUtils.endSession(true);
        return image;
    }

    @Test
    void anUploadedFileIsStreamed() throws Exception {
        uploadedImage("preview-photo.jpg");

        MockHttpServletResponse response = get("/" + handle + "/preview-photo.jpg");

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsByteArray().length > 1000,
                "the real image bytes must be streamed, not a placeholder");
    }

    /**
     * The authoring path admits any editor, not only this weblog's members, so a
     * private directory's file is refused here exactly as on the public
     * ResourceServlet: same status, no bytes, as though it did not exist.
     */
    @Test
    void aFileInAPrivateDirectoryIsNotServedByItsOriginalPath() throws Exception {
        MediaFile image = uploadedImage("private-photo.jpg");
        TestUtils.weblogger().getMediaFileManager().getMediaFile(image.getId())
                .getDirectory().setPrivate(true);
        TestUtils.endSession(true);

        MockHttpServletResponse response = get("/" + handle + "/private-photo.jpg");

        assertEquals(404, response.getStatus());
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void anUnknownWeblogIsNotFound() throws Exception {
        assertEquals(404, get("/nosuchblog/whatever.jpg").getStatus(),
                "a preview url naming no weblog must 404 rather than reaching the "
                        + "filesystem lookup at all");
    }

    @Test
    void anUnknownFileIsNotFound() throws Exception {
        assertEquals(404, get("/" + handle + "/no-such-file.jpg").getStatus());
    }

    @Test
    void aThemeParameterDoesNotChangeWhereUploadsComeFrom() throws Exception {
        uploadedImage("themed-photo.jpg");

        MockHttpServletResponse response =
                get("/" + handle + "/themed-photo.jpg", "theme", "portfolio");

        assertEquals(200, response.getStatus(),
                "an upload belongs to the weblog, not to the theme being previewed, so "
                        + "previewing another theme must not hide it");
        assertTrue(response.getContentAsByteArray().length > 1000);
    }

    @Test
    void aConditionalRequestForAnUnchangedFileIsNotModified() throws Exception {
        uploadedImage("conditional-photo.jpg");

        MockHttpServletResponse first = get("/" + handle + "/conditional-photo.jpg");
        assertEquals(200, first.getStatus());
        long lastModified = first.getDateHeader("Last-Modified");

        MockHttpServletRequest conditional =
                RenderingTestSupport.anonymousGet(PREVIEW_RESOURCE,
                        "/" + handle + "/conditional-photo.jpg");
        conditional.addHeader("If-Modified-Since", lastModified);

        assertEquals(304, RenderingTestSupport.execute(
                        RenderingTestSupport.previewResourceServlet(), conditional).getStatus(),
                "an author reloading a preview must not re-download every image each time");
    }

    private static ThemeResource themeFile(String text) {
        ThemeResource resource = mock(ThemeResource.class);
        when(resource.getLastModified()).thenReturn(1_700_000_000_000L);
        when(resource.getInputStream())
                .thenReturn(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)));
        return resource;
    }

    private MockHttpServletResponse getWith(ThemeManager themes, String... params) throws Exception {
        Weblogger real = TestUtils.weblogger();
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogManager()).thenReturn(real.getWeblogManager());
        when(weblogger.getMediaFileManager()).thenReturn(real.getMediaFileManager());
        when(weblogger.getThemeManager()).thenReturn(themes);
        PreviewResourceServlet servlet = new PreviewResourceServlet(weblogger);
        servlet.init(new MockServletConfig(PressroomContext.getServletContext()));
        MockHttpServletRequest request =
                RenderingTestSupport.anonymousGet(PREVIEW_RESOURCE, "/" + handle + "/style.css");
        for (int i = 0; i + 1 < params.length; i += 2) {
            request.setParameter(params[i], params[i + 1]);
        }
        return RenderingTestSupport.execute(servlet, request);
    }

    /**
     * Characterisation test, written before doGet shared its lookups with
     * ResourceServlet and passing against the old code: the previewed theme
     * is asked first, then the weblog's own theme.
     */
    @Test
    void thePreviewedThemeWinsOverTheWeblogsOwnTheme() throws Exception {
        SharedTheme previewed = mock(SharedTheme.class);
        ThemeResource previewedFile = themeFile("previewed");
        when(previewed.getResource("style.css")).thenReturn(previewedFile);
        WeblogTheme own = mock(WeblogTheme.class);
        ThemeResource ownFile = themeFile("own");
        when(own.getResource("style.css")).thenReturn(ownFile);
        ThemeManager themes = mock(ThemeManager.class);
        when(themes.getTheme("previewed")).thenReturn(previewed);
        when(themes.getTheme(org.mockito.ArgumentMatchers.any(Weblog.class))).thenReturn(own);

        assertEquals("previewed", getWith(themes, "theme", "previewed").getContentAsString());
        assertEquals("own", getWith(themes).getContentAsString());
    }

    /** Characterisation test, as above: a weblog theme that cannot be read is a 500. */
    @Test
    void aThemeLookupThatFailsIsA500() throws Exception {
        ThemeManager themes = mock(ThemeManager.class);
        when(themes.getTheme(org.mockito.ArgumentMatchers.any(Weblog.class)))
                .thenThrow(new WebloggerException("theme directory unreadable"));

        assertEquals(500, getWith(themes).getStatus());
    }
}
