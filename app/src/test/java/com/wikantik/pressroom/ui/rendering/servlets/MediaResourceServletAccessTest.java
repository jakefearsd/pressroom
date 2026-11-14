package com.wikantik.pressroom.ui.rendering.servlets;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.core.PressroomContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who may fetch a media file, and when a reader's copy is still good. The
 * private-directory exception for a weblog's own editors is the one place the
 * servlet consults the session, so each way that check can come out refused,
 * including "could not be asked", is pinned here: refusing is a 404 identical
 * to an unknown id.
 */
class MediaResourceServletAccessTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("mediaaccessuser");
        weblog = TestUtils.setupWeblog("mediaaccessblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        SecurityContextHolder.clearContext();
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private MediaFile privateImage(String name) throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, name, "privatedir");
        TestUtils.weblogger().getMediaFileManager().getMediaFile(image.getId()).getDirectory().setPrivate(true);
        TestUtils.endSession(true);
        return image;
    }

    private MockHttpServletRequest requestFor(MediaFile image) {
        return RenderingTestSupport.anonymousGet(
                "/pressroom-ui/rendering/media-resources", "/mediaaccessblog/" + image.getId());
    }

    /**
     * The servlet sends a stored file with the uploader's claimed type; one
     * claiming text/html must reach a browser as a sandboxed download, never
     * as a page on the site's origin (MediaResponseHeadersTest has the rules).
     */
    @Test
    void anUploadClaimingToBeAPageIsASandboxedDownload() throws Exception {
        MediaFile file = TestUtils.setupImageMediaFile(weblog, "page.html");
        MediaFile managed = TestUtils.weblogger().getMediaFileManager().getMediaFile(file.getId());
        managed.setContentType("text/html");
        TestUtils.weblogger().getMediaFileManager().updateMediaFile(TestUtils.getManagedWebsite(weblog), managed);
        TestUtils.endSession(true);

        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), requestFor(file));

        assertEquals(200, response.getStatus());
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("sandbox", response.getHeader("Content-Security-Policy"));
        assertNotNull(response.getHeader("Content-Disposition"));
    }

    @Test
    void anImageIsServedInlineAndNeverSniffed() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "inline.jpg");
        TestUtils.endSession(true);

        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), requestFor(image));

        assertEquals(200, response.getStatus());
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals(null, response.getHeader("Content-Disposition"));
    }

    @Test
    void aFileNotModifiedSinceTheReadersCopyAnswers304WithNoBody() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "cached.jpg");
        TestUtils.endSession(true);
        MockHttpServletResponse first = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), requestFor(image));
        String lastModified = first.getHeader("Last-Modified");
        assertNotNull(lastModified, "without it a reader can never revalidate");

        MockHttpServletRequest again = requestFor(image);
        again.addHeader("If-Modified-Since", lastModified);
        MockHttpServletResponse second = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), again);

        assertEquals(200, first.getStatus());
        assertEquals(304, second.getStatus());
        assertEquals(0, second.getContentAsByteArray().length);
    }

    @Test
    void anAnonymousAuthenticationDoesNotOpenAPrivateDirectory() throws Exception {
        MediaFile image = privateImage("anon.jpg");
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), requestFor(image));

        assertEquals(404, response.getStatus());
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void anAuthenticatedNameWithNoAccountDoesNotOpenAPrivateDirectory() throws Exception {
        MediaFile image = privateImage("ghost.jpg");
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("no-such-account", "n/a", "editor"));

        assertEquals(404, RenderingTestSupport.execute(
                RenderingTestSupport.mediaResourceServlet(), requestFor(image)).getStatus());
    }

    /** A permission check that cannot run is not a check that passed. */
    @Test
    void aSessionCheckThatFailsRefusesRatherThanServesPrivateMedia() throws Exception {
        MediaFile image = privateImage("failclosed.jpg");
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(user.getUserName(), "n/a", "editor"));
        UserManager broken = mock(UserManager.class);
        when(broken.getUserByUserName(anyString())).thenThrow(new RuntimeException("store unreachable"));
        Weblogger real = TestUtils.weblogger();
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getMediaFileManager()).thenReturn(real.getMediaFileManager());
        when(weblogger.getUserManager()).thenReturn(broken);
        MediaResourceServlet servlet = new MediaResourceServlet(weblogger);
        servlet.init(new MockServletConfig(PressroomContext.getServletContext()));

        MockHttpServletResponse response = RenderingTestSupport.execute(servlet, requestFor(image));

        assertEquals(404, response.getStatus());
        assertEquals(0, response.getContentAsByteArray().length);
    }

    // ---- a media store that fails or returns something unservable

    /** The servlet over a media manager answering {@code getMediaFile(id, true)} with {@code answer}. */
    private MockHttpServletResponse servedBy(org.mockito.stubbing.Answer<MediaFile> answer, boolean thumbnail)
            throws Exception {
        com.wikantik.pressroom.business.MediaFileManager media =
                mock(com.wikantik.pressroom.business.MediaFileManager.class);
        when(media.getMediaFile(anyString(), org.mockito.ArgumentMatchers.eq(true))).thenAnswer(answer);
        Weblogger weblogger = mock(Weblogger.class, org.mockito.AdditionalAnswers.delegatesTo(TestUtils.weblogger()));
        org.mockito.Mockito.doReturn(media).when(weblogger).getMediaFileManager();
        MediaResourceServlet servlet = new MediaResourceServlet(weblogger);
        servlet.init(new MockServletConfig(PressroomContext.getServletContext()));
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/pressroom-ui/rendering/media-resources", "/mediaaccessblog/m1");
        if (thumbnail) {
            request.setParameter("t", "true");
        }
        return RenderingTestSupport.execute(servlet, request);
    }

    /** A stored file of this weblog, with the given bytes and thumbnail behaviour. */
    private MediaFile storedFile(java.io.InputStream bytes, boolean thumbnailFails) throws Exception {
        MediaFile file = new MediaFile() {
            private static final long serialVersionUID = 1L;

            @Override
            public java.io.InputStream getThumbnailInputStream() {
                if (thumbnailFails) {
                    throw new java.io.UncheckedIOException(new java.io.IOException("thumbnail unreadable"));
                }
                return null;
            }
        };
        file.setId("m1");
        file.setContentType("image/jpeg");
        file.setLastUpdated(new java.sql.Timestamp(1_700_000_000_000L));
        com.wikantik.pressroom.pojos.MediaFileDirectory directory =
                new com.wikantik.pressroom.pojos.MediaFileDirectory(TestUtils.getManagedWebsite(weblog), "d", null);
        file.setDirectory(directory);
        file.setInputStream(bytes);
        return file;
    }

    @Test
    void aMediaLookupThatFailsOrFindsNothingServableIs404() throws Exception {
        assertEquals(404, servedBy(i -> { throw new com.wikantik.pressroom.WebloggerException("db down"); }, false)
                .getStatus());
        assertEquals(404, servedBy(i -> null, false).getStatus());
        MediaFile unsaved = storedFile(new java.io.ByteArrayInputStream(new byte[] {1}), false);
        unsaved.setLastUpdated(null);
        assertEquals(404, servedBy(i -> unsaved, false).getStatus(), "no modification time: not a stored file");
    }

    /** A thumbnail that cannot be read falls back to the original, not to an error. */
    @Test
    void aThumbnailThatCannotBeReadServesTheOriginal() throws Exception {
        byte[] original = {9, 8, 7};
        MediaFile file = storedFile(new java.io.ByteArrayInputStream(original), true);

        MockHttpServletResponse response = servedBy(i -> file, true);

        assertEquals(200, response.getStatus());
        assertEquals("image/jpeg", response.getContentType());
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, response.getContentAsByteArray());
    }

    /** Bytes that cannot be read from storage are a 500, never a truncated 200. */
    @Test
    void aFileThatCannotBeReadFromStorageIs500() throws Exception {
        java.io.InputStream failing = new java.io.InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("disk error");
            }
        };
        MediaFile file = storedFile(failing, false);

        assertEquals(500, servedBy(i -> file, false).getStatus());
    }
}
