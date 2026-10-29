package com.wikantik.pressroom.ui.rendering.servlets;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.CwebpEncoder;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaResourceServletRenderingTest {

    private User user;
    private Weblog weblog;
    private Weblog otherWeblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("mediauser");
        weblog = TestUtils.setupWeblog("mediablog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        CwebpEncoder.setAvailableForTesting(null);
        if (otherWeblog != null) {
            TestUtils.teardownWeblog(otherWeblog.getId());
            otherWeblog = null;
        }
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void mediaFileStreamsWithContentType() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "photo.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.mediaResourceServlet(), request);

        assertEquals(200, response.getStatus());
        assertEquals("image/jpeg", response.getContentType());
        assertTrue(response.getContentAsByteArray().length > 1000,
                "the real image bytes must be streamed");
    }

    @Test
    void thumbnailParameterStreamsSmallerImage() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "thumb.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest full = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        MockHttpServletRequest thumb = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        thumb.setParameter("t", "true");

        int fullSize = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), full).getContentAsByteArray().length;
        MockHttpServletResponse thumbResponse = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), thumb);

        assertEquals(200, thumbResponse.getStatus());
        assertTrue(thumbResponse.getContentAsByteArray().length < fullSize,
                "thumbnail must be smaller than the original");
    }

    @Test
    void unknownMediaFileIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/no-such-id");
        assertEquals(404, RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), request).getStatus());
    }

    @Test
    void unknownWeblogIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/nosuchblog/whatever");
        assertEquals(404, RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), request).getStatus());
    }

    // ------------------------------------------------- cross-weblog isolation
    //
    // A media file must only be addressable through its own weblog's handle.
    // A valid id fetched through a *different* (but existing) weblog's URL
    // space must 404 exactly like an unknown id, on every serving path
    // (original, ?t=true thumbnail, ?w= rendition).

    @Test
    void aMediaFileIsNotServedThroughAnotherWeblogsHandle() throws Exception {
        otherWeblog = TestUtils.setupWeblog("mediaotherblog", user);
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "isolated.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest crossWeblog = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediaotherblog/" + image.getId());
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.mediaResourceServlet(), crossWeblog);

        assertEquals(404, response.getStatus(),
                "a media file id must not be fetchable through another weblog's handle");
        assertEquals(0, response.getContentAsByteArray().length,
                "no media bytes may leak through the wrong weblog's URL space");
    }

    @Test
    void aThumbnailIsNotServedThroughAnotherWeblogsHandle() throws Exception {
        otherWeblog = TestUtils.setupWeblog("mediaotherblog", user);
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "isolatedthumb.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest crossWeblog = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediaotherblog/" + image.getId());
        crossWeblog.setParameter("t", "true");

        assertEquals(404, RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), crossWeblog).getStatus(),
                "the ?t=true thumbnail path must sit behind the same weblog check");
    }

    @Test
    void aRenditionIsNotServedThroughAnotherWeblogsHandle() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        otherWeblog = TestUtils.setupWeblog("mediaotherblog", user);
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "isolatedwidth.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest crossWeblog = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediaotherblog/" + image.getId());
        crossWeblog.setParameter("w", "480");

        assertEquals(404, RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), crossWeblog).getStatus(),
                "the ?w= rendition path must sit behind the same weblog check");
    }

    // ------------------------------------------------- private directories
    //
    // A file in a directory marked private must not be reachable through the
    // public media path at all -- it is not served publicly by any route.
    // The base path must 404 exactly like an unknown id, on every serving
    // variant.

    @Test
    void aFileInAPrivateDirectoryIsNotServedThroughTheBaseMediaPath() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "private.jpg", "privatedir");
        markDirectoryPrivate(image);

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.mediaResourceServlet(), request);

        assertEquals(404, response.getStatus(),
                "a private directory's files must 404 on the base media path");
        assertEquals(0, response.getContentAsByteArray().length,
                "no media bytes may leak from a private directory");
    }

    @Test
    void aPrivateDirectoryThumbnailIsNotServedThroughTheBaseMediaPath() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "privthumb.jpg", "privatedir");
        markDirectoryPrivate(image);

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        request.setParameter("t", "true");

        assertEquals(404, RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), request).getStatus(),
                "the ?t=true thumbnail path must sit behind the private-directory check");
    }

    @Test
    void aPrivateDirectoryRenditionIsNotServedThroughTheBaseMediaPath() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "privwidth.jpg", "privatedir");
        markDirectoryPrivate(image);

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        request.setParameter("w", "480");

        assertEquals(404, RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), request).getStatus(),
                "the ?w= rendition path must sit behind the private-directory check");
    }

    @Test
    void theOwningWeblogsEditorStillSeesPrivateDirectoryFiles() throws Exception {
        // The media-library screens render thumbnails through this servlet;
        // the author who just marked a directory private must keep seeing it.
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "priveditor.jpg", "privatedir");
        markDirectoryPrivate(image);

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        try {
            authenticateAs(user.getUserName());
            MockHttpServletResponse response = RenderingTestSupport
                    .execute(RenderingTestSupport.mediaResourceServlet(), request);

            assertEquals(200, response.getStatus(),
                    "the owning weblog's editor must see private-directory files");
            assertEquals("private, no-store", response.getHeader("Cache-Control"),
                    "an editor's private-media response must never enter a shared cache");
            assertTrue(response.getContentAsByteArray().length > 1000);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void anotherWeblogsEditorDoesNotSeePrivateDirectoryFiles() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "privstranger.jpg", "privatedir");
        markDirectoryPrivate(image);
        User stranger = TestUtils.setupUser("mediastranger");
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        try {
            authenticateAs(stranger.getUserName());
            assertEquals(404, RenderingTestSupport.execute(
                    RenderingTestSupport.mediaResourceServlet(), request).getStatus(),
                    "being logged in is not enough -- the editor exception is "
                            + "scoped to the owning weblog's members");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            TestUtils.teardownUser(stranger.getUserName());
            TestUtils.endSession(true);
        }
    }

    /**
     * A reader once given "Can write" and then demoted keeps the weblog membership
     * it was given, but a weblog permission ignores global roles. Private media also
     * needs the global {@code login} action, which a reader-only account does not
     * hold (spec, the reader role).
     */
    @Test
    void aWeblogMemberWithoutTheLoginActionDoesNotSeePrivateDirectoryFiles() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "privdemoted.jpg", "privatedir");
        markDirectoryPrivate(image);
        User demoted = TestUtils.setupUser("mediademoted");
        com.wikantik.pressroom.business.UserManager users = TestUtils.weblogger().getUserManager();
        users.grantWeblogPermission(TestUtils.getManagedWebsite(weblog), demoted,
                java.util.List.of(com.wikantik.pressroom.pojos.WeblogPermission.POST));
        users.revokeRole("editor", demoted);
        users.grantRole("reader", demoted);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        try {
            authenticateAs(demoted.getUserName());
            assertEquals(404, RenderingTestSupport.execute(
                    RenderingTestSupport.mediaResourceServlet(), request).getStatus(),
                    "a weblog member who may not author anything must not see private media");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            TestUtils.teardownUser(demoted.getUserName());
            TestUtils.endSession(true);
        }
    }

    /** Puts an authenticated principal into the security context, as the session filter would. */
    private static void authenticateAs(String userName) {
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication.TestingAuthenticationToken(
                                userName, "n/a", "editor"));
    }

    /** Flips the file's directory to private and flushes, as the editor toggle does. */
    private static void markDirectoryPrivate(MediaFile image) throws Exception {
        MediaFile managed = TestUtils.weblogger().getMediaFileManager().getMediaFile(image.getId());
        managed.getDirectory().setPrivate(true);
        TestUtils.endSession(true);
    }

    // ------------------------------------------------- ?w= responsive renditions

    @Test
    void widthParameterStreamsTheMatchingRendition() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "wide480.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest full = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        MockHttpServletRequest w480 = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        w480.setParameter("w", "480");

        int fullSize = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), full).getContentAsByteArray().length;
        MockHttpServletResponse w480Response = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), w480);

        assertEquals(200, w480Response.getStatus());
        assertEquals("image/jpeg", w480Response.getContentType());
        assertTrue(w480Response.getContentAsByteArray().length < fullSize,
                "the 480w rendition must be smaller than the 500w original (hawk.jpg)");
        assertEquals("Accept", w480Response.getHeader("Vary"),
                "a ?w= URL is content-negotiated on Accept, so shared caches must key on it");
    }

    @Test
    void aLadderWidthNeverGeneratedForThisImageFallsBackToTheOriginal() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        // hawk.jpg is 500w; 960 is a real ladder width but is never generated
        // for an original this narrow (960 >= 500).
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "notgenerated.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest full = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        MockHttpServletRequest w960 = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        w960.setParameter("w", "960");

        MockHttpServletResponse fullResponse = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), full);
        MockHttpServletResponse w960Response = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), w960);

        assertEquals(200, w960Response.getStatus());
        assertEquals("image/jpeg", w960Response.getContentType());
        assertEquals(fullResponse.getContentAsByteArray().length, w960Response.getContentAsByteArray().length,
                "a valid ladder width with no generated rendition must 404-safe fall back to the original");
    }

    @Test
    void aWidthOutsideTheLadderIsRejectedAndServesTheOriginal() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "rejectwidth.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest full = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        MockHttpServletRequest badWidth = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        // 999 is not one of the four ladder widths -- it must be validated
        // against the ladder set only, not treated as an arbitrary resize request.
        badWidth.setParameter("w", "999");

        MockHttpServletResponse fullResponse = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), full);
        MockHttpServletResponse badWidthResponse = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), badWidth);

        assertEquals(200, badWidthResponse.getStatus());
        assertEquals("image/jpeg", badWidthResponse.getContentType());
        assertEquals(fullResponse.getContentAsByteArray().length, badWidthResponse.getContentAsByteArray().length,
                "a width outside the ladder set must be rejected and fall back to the original");
    }

    @Test
    void webpIsNeverServedWhenCwebpWasUnavailableAtGenerationTime() throws Exception {
        CwebpEncoder.setAvailableForTesting(false);
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "nowebp.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        request.setParameter("w", "480");
        request.addHeader("Accept", "image/webp,image/*,*/*");

        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), request);

        assertEquals(200, response.getStatus());
        assertNotEquals("image/webp", response.getContentType(),
                "no webp sibling exists when cwebp was unavailable at generation time");
        assertEquals("image/jpeg", response.getContentType());
    }

    @Test
    void webpIsServedWhenTheClientAcceptsItAndCwebpWasAvailable() throws Exception {
        // Real detection (not forced) -- this is the one test that proves the
        // real cwebp integration works end to end; skipped rather than failed
        // when this machine has no cwebp installed.
        CwebpEncoder.setAvailableForTesting(null);
        Assumptions.assumeTrue(CwebpEncoder.isAvailable(),
                "cwebp is not installed on this machine -- skipping the webp negotiation check");

        MediaFile image = TestUtils.setupImageMediaFile(weblog, "webpnegotiation.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest webpRequest = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        webpRequest.setParameter("w", "480");
        webpRequest.addHeader("Accept", "image/webp,image/*,*/*");

        MockHttpServletRequest plainRequest = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/mediablog/" + image.getId());
        plainRequest.setParameter("w", "480");
        plainRequest.addHeader("Accept", "image/jpeg,image/*,*/*");

        MockHttpServletResponse webpResponse = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), webpRequest);
        MockHttpServletResponse plainResponse = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), plainRequest);

        assertEquals(200, webpResponse.getStatus());
        assertEquals("image/webp", webpResponse.getContentType(),
                "Accept: image/webp must negotiate the .webp sibling when it exists");
        assertEquals("image/jpeg", plainResponse.getContentType(),
                "no Accept: image/webp must serve the raster rendition");
        // Same URL, two different bodies and content types: without Vary a
        // shared cache would hand one client the other's variant.
        assertEquals("Accept", webpResponse.getHeader("Vary"));
        assertEquals("Accept", plainResponse.getHeader("Vary"));
    }
}
