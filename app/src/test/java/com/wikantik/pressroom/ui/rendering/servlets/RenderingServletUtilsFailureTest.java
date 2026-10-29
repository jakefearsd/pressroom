package com.wikantik.pressroom.ui.rendering.servlets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.cache.RenderCache;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The failure behaviour the rendering servlets share: what a reader sees when
 * a resource cannot be streamed or the model cannot be built, and the rule that
 * a development-time theme reload can never turn a reader's page into an error.
 */
class RenderingServletUtilsFailureTest {

    /** A response that has already started going out, as it has when a stream fails part-way. */
    private static final class CommittedResponse extends MockHttpServletResponse {
        @Override
        public boolean isCommitted() {
            return true;
        }
    }

    private static final class TrackedStream extends InputStream {
        boolean closed;
        private final IOException failure;

        TrackedStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public int read() throws IOException {
            throw failure;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void aStreamThatFailsBeforeAnythingIsSentBecomes500AndIsClosed() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        TrackedStream stream = new TrackedStream(new IOException("disk"));

        RenderingServletUtils.streamResource(stream, response, "resource x");

        assertEquals(500, response.getStatus());
        assertTrue(stream.closed, "the stream must be released on the failure path");
    }

    /** sendError on a committed response throws IllegalStateException, hiding the real IOException. */
    @Test
    void aStreamThatFailsAfterTheResponseStartedDoesNotTryToSendAnError() throws Exception {
        CommittedResponse response = new CommittedResponse();
        TrackedStream stream = new TrackedStream(new IOException("connection reset"));

        RenderingServletUtils.streamResource(stream, response, "resource x");

        assertEquals(200, response.getStatus(), "nothing more can be said to this client");
        assertTrue(stream.closed);
    }

    @Test
    void aMissingStreamIs404() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        RenderingServletUtils.streamResource(null, response, "resource x");

        assertEquals(404, response.getStatus());
    }

    @Test
    void aGoodStreamIsCopiedWholeAndClosed() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        RenderingServletUtils.streamResource(new ByteArrayInputStream("body{}".getBytes()), response, "resource x");

        assertEquals("body{}", response.getContentAsString());
    }

    @Test
    void aServerErrorBeforeAnythingIsSentDiscardsTheHalfWrittenBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getWriter().write("half a page");

        RenderingServletUtils.sendServerError(response, "boom", new IllegalStateException("x"));

        assertEquals(500, response.getStatus());
        assertEquals("", response.getContentAsString(), "the 500 must not be stapled to half a page");
    }

    @Test
    void aServerErrorOnACommittedResponseKeepsWhatWasAlreadySent() throws Exception {
        CommittedResponse response = new CommittedResponse();
        response.getWriter().write("half a page");
        response.getWriter().flush();

        RenderingServletUtils.sendServerError(response, "boom", new IllegalStateException("x"));

        assertEquals("half a page", response.getContentAsString(), "reset() on a committed response would throw");
    }

    // ---- development-time theme reload

    private final Weblog weblog = new Weblog();
    @SuppressWarnings("unchecked")
    private final RenderCache<Object> cache = mock(RenderCache.class);
    private final ThemeManager themes = mock(ThemeManager.class);

    @Test
    void aThemeThatReloadedDropsTheRenderedContentBuiltFromIt() throws Exception {
        weblog.setEditorTheme("journal");
        weblog.setLocale("en_US");
        when(themes.reLoadThemeFromDisk("journal")).thenReturn(true);

        RenderingServletUtils.reloadThemeFromDisk(weblog, cache, themes);

        verify(cache).clear();
    }

    @Test
    void aThemeThatDidNotChangeLeavesTheCacheAlone() throws Exception {
        weblog.setEditorTheme("journal");
        when(themes.reLoadThemeFromDisk("journal")).thenReturn(false);

        RenderingServletUtils.reloadThemeFromDisk(weblog, cache, themes);

        verify(cache, never()).clear();
    }

    @Test
    void aReloadThatFailsIsSwallowedSoTheReadersPageStillRenders() throws Exception {
        weblog.setEditorTheme("journal");
        when(themes.reLoadThemeFromDisk("journal")).thenThrow(new IllegalStateException("bad theme file"));

        RenderingServletUtils.reloadThemeFromDisk(weblog, cache, themes);

        verify(cache, never()).clear();
    }
}
