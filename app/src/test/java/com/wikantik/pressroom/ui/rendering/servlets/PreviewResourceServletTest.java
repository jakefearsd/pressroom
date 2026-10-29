package com.wikantik.pressroom.ui.rendering.servlets;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final String PREVIEW_RESOURCE = "/roller-ui/authoring/previewresource";

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
}
