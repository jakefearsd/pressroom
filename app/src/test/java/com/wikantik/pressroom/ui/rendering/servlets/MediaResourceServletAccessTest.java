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
}
