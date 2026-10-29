package com.wikantik.pressroom.ui.rendering.servlets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a resource file is written to the client, and what happens when the write
 * fails partway through.
 *
 * <p>That second case is the reason this is tested at all. The bytes are
 * already going out when the failure happens, so the response is normally
 * COMMITTED -- and {@code sendError} on a committed response throws
 * IllegalStateException. ResourceServlet used to guard the reset with
 * isCommitted() but not the sendError, so a mid-stream failure on a public
 * resource produced an IllegalStateException from the error handler in place of
 * the IOException that actually happened, and logged nothing about the real
 * cause. PreviewResourceServlet had it right. Both share the correct shape now.
 */
class StreamResourceTest {

    private static InputStream bytes(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    /** A stream that fails on read, as a disk or network error would. */
    private static InputStream failing() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk went away");
            }
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("disk went away");
            }
        };
    }

    @Test
    void theFileIsWrittenToTheResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        RenderingServletUtils.streamResource(bytes("hello"), response, "photo.jpg");

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8),
                response.getContentAsByteArray());
    }

    @Test
    void theStreamIsClosedAfterASuccessfulWrite() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CloseTracking stream = new CloseTracking(bytes("hello"));

        RenderingServletUtils.streamResource(stream, response, "photo.jpg");

        assertTrue(stream.closed, "the file handle must not be leaked");
    }

    @Test
    void theStreamIsClosedEvenWhenTheWriteFails() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CloseTracking stream = new CloseTracking(failing());

        RenderingServletUtils.streamResource(stream, response, "photo.jpg");

        assertTrue(stream.closed,
                "a failed write must not leak the handle either -- this is the path that "
                        + "runs when a disk or a client goes away, i.e. the common one");
    }

    /**
     * The bug this shape exists to prevent.
     */
    @Test
    void aFailureAfterTheResponseIsCommittedDoesNotTryToSendAnError() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(true);
        when(response.getOutputStream()).thenReturn(mock(ServletOutputStream.class));

        assertDoesNotThrow(
                () -> RenderingServletUtils.streamResource(failing(), response, "photo.jpg"),
                "sendError on a committed response throws IllegalStateException, which "
                        + "would replace the IOException that actually happened");

        verify(response, never()).sendError(anyInt());
        verify(response, never()).reset();
    }

    @Test
    void aFailureBeforeAnythingWasWrittenReportsAServerError() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        RenderingServletUtils.streamResource(failing(), response, "photo.jpg");

        assertEquals(500, response.getStatus(),
                "nothing had been written yet, so the client can still be told");
    }

    @Test
    void nothingToStreamIsANotFoundRatherThanAnException() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        RenderingServletUtils.streamResource(null, response, "photo.jpg");

        assertEquals(404, response.getStatus(),
                "no caller can currently hand over null, but it holds on an invariant "
                        + "three classes away; 404 beats a NullPointerException if it ever "
                        + "stops holding");
    }

    /** Wraps a stream to record whether it was closed. */
    private static final class CloseTracking extends InputStream {
        private final InputStream delegate;
        private boolean closed;

        CloseTracking(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            delegate.close();
        }
    }
}
