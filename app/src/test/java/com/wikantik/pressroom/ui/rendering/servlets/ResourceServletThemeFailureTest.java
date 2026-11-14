package com.wikantik.pressroom.ui.rendering.servlets;

import com.wikantik.pressroom.TestUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.ThemeResource;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.core.PressroomContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A theme that cannot be read is a server problem, not a missing file: the
 * reader gets a 500 rather than a 404 that would be cached and believed.
 */
class ResourceServletThemeFailureTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("resfailuser");
        weblog = TestUtils.setupWeblog("resfailblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void aThemeLookupThatFailsIsA500NotA404() throws Exception {
        ThemeManager broken = mock(ThemeManager.class);
        when(broken.getTheme(org.mockito.ArgumentMatchers.any(Weblog.class)))
                .thenThrow(new WebloggerException("theme directory unreadable"));
        Weblogger real = TestUtils.weblogger();
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogManager()).thenReturn(real.getWeblogManager());
        when(weblogger.getThemeManager()).thenReturn(broken);
        ResourceServlet servlet = new ResourceServlet(weblogger);
        servlet.init(new MockServletConfig(PressroomContext.getServletContext()));

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/pressroom-ui/rendering/resources", "/resfailblog/style.css");
        MockHttpServletResponse response = RenderingTestSupport.execute(servlet, request);

        assertEquals(500, response.getStatus());
    }

    /**
     * Characterisation test, written before doGet was split up and passing
     * against the old code: a resource the weblog's theme has is served from
     * the theme, with its modification time, and never looked for among the
     * uploads.
     */
    @Test
    void aResourceTheThemeHasIsServedFromTheTheme() throws Exception {
        byte[] css = "body { color: red }".getBytes(StandardCharsets.US_ASCII);
        ThemeResource resource = mock(ThemeResource.class);
        when(resource.getLastModified()).thenReturn(1_700_000_000_000L);
        when(resource.getInputStream()).thenReturn(new ByteArrayInputStream(css));
        WeblogTheme theme = mock(WeblogTheme.class);
        when(theme.getResource("style.css")).thenReturn(resource);
        ThemeManager themes = mock(ThemeManager.class);
        when(themes.getTheme(org.mockito.ArgumentMatchers.any(Weblog.class))).thenReturn(theme);
        Weblogger real = TestUtils.weblogger();
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogManager()).thenReturn(real.getWeblogManager());
        when(weblogger.getThemeManager()).thenReturn(themes);
        ResourceServlet servlet = new ResourceServlet(weblogger);
        servlet.init(new MockServletConfig(PressroomContext.getServletContext()));

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/pressroom-ui/rendering/resources", "/resfailblog/style.css");
        MockHttpServletResponse response = RenderingTestSupport.execute(servlet, request);

        assertEquals(200, response.getStatus());
        assertArrayEquals(css, response.getContentAsByteArray());
        assertEquals(1_700_000_000_000L, response.getDateHeader("Last-Modified"));
        verify(weblogger, never()).getMediaFileManager();
    }

    /**
     * A 304 sends no bytes, so the stream opened to find the resource must
     * be closed there and then. It was left open for the collector: a file
     * handle per revalidation of every theme stylesheet and upload.
     */
    @Test
    void aNotModifiedAnswerClosesTheStreamItOpened() throws Exception {
        boolean[] closed = {false};
        InputStream css = new ByteArrayInputStream(new byte[] {1, 2, 3}) {
            @Override
            public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        };
        ThemeResource resource = mock(ThemeResource.class);
        when(resource.getLastModified()).thenReturn(1_700_000_000_000L);
        when(resource.getInputStream()).thenReturn(css);
        WeblogTheme theme = mock(WeblogTheme.class);
        when(theme.getResource("style.css")).thenReturn(resource);
        ThemeManager themes = mock(ThemeManager.class);
        when(themes.getTheme(org.mockito.ArgumentMatchers.any(Weblog.class))).thenReturn(theme);
        Weblogger real = TestUtils.weblogger();
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogManager()).thenReturn(real.getWeblogManager());
        when(weblogger.getThemeManager()).thenReturn(themes);
        ResourceServlet servlet = new ResourceServlet(weblogger);
        servlet.init(new MockServletConfig(PressroomContext.getServletContext()));

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/pressroom-ui/rendering/resources", "/resfailblog/style.css");
        request.addHeader("If-Modified-Since", 1_700_000_000_000L);
        MockHttpServletResponse response = RenderingTestSupport.execute(servlet, request);

        assertEquals(304, response.getStatus());
        assertTrue(closed[0], "the stream opened for a 304 must be closed");
    }
}
