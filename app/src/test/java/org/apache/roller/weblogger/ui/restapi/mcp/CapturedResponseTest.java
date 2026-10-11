package org.apache.roller.weblogger.ui.restapi.mcp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Everything the API writes during a forwarded call lands here, to be read
 * back as an {@link ApiReply}. That none of it reaches the MCP response --
 * which the SDK transport still has to write the JSON-RPC answer to
 * afterwards -- is structural (this response holds no reference to it) and
 * is shown in a real container by {@code InProcessDispatchContainerTest}.
 */
class CapturedResponseTest {

    @Test
    void capturesStatusContentTypeAndBody() throws Exception {
        CapturedResponse captured = new CapturedResponse();

        captured.setStatus(201);
        captured.setContentType("application/json");
        captured.getWriter().write("{\"id\":\"e1\"}");

        ApiReply reply = captured.reply();
        assertEquals(201, reply.status());
        assertEquals("application/json", reply.contentType());
        assertEquals("{\"id\":\"e1\"}", reply.body());
        assertTrue(reply.isSuccess());
    }

    @Test
    void sendErrorIsCapturedAsAStatusNotAnErrorPage() throws Exception {
        CapturedResponse captured = new CapturedResponse();

        captured.sendError(404);
        assertEquals(404, captured.reply().status());
        assertFalse(captured.reply().isSuccess());

        captured.sendError(409, "conflict");
        assertEquals(409, captured.getStatus());
        assertEquals("", captured.reply().body(), "no error page is rendered into the body");
    }

    @Test
    void sendErrorDiscardsWhatWasWrittenBeforeIt() throws Exception {
        CapturedResponse captured = new CapturedResponse();
        captured.getWriter().write("half a page");
        captured.sendError(500);
        assertEquals("", captured.reply().body());

        captured.getOutputStream().write(new byte[] {'x'});
        captured.sendError(503, "busy");
        assertEquals(new ApiReply(503, null, ""), captured.reply());
    }

    @Test
    void aRedirectIsCapturedAsAStatusAndLocation() throws Exception {
        CapturedResponse captured = new CapturedResponse();
        captured.getWriter().write("discarded");

        captured.sendRedirect("/elsewhere");

        assertEquals(302, captured.getStatus());
        assertEquals("/elsewhere", captured.getHeader("Location"));
        assertEquals("", captured.reply().body());
        assertFalse(captured.reply().isSuccess());
    }

    @Test
    void neverReportsItselfCommittedSoAForwardIsAllowed() throws Exception {
        CapturedResponse captured = new CapturedResponse();
        captured.getOutputStream().write(new byte[] {'x'});
        captured.flushBuffer();
        assertFalse(captured.isCommitted());
        captured.resetBuffer();
        assertEquals("", captured.reply().body());
    }

    @Test
    void bytesAndCharactersLandInOneUtf8Body() throws Exception {
        CapturedResponse captured = new CapturedResponse();
        captured.setCharacterEncoding("ISO-8859-1");

        captured.getOutputStream().write("{\"t\":\"".getBytes(StandardCharsets.UTF_8));
        captured.getWriter().write("é\"}");

        assertEquals("UTF-8", captured.getCharacterEncoding(), "the body is always read back as UTF-8");
        assertEquals("{\"t\":\"é\"}", captured.reply().body());
        assertSame(captured.getOutputStream(), captured.getOutputStream());
        assertSame(captured.getWriter(), captured.getWriter());
        assertTrue(captured.getOutputStream().isReady());
        assertThrows(UnsupportedOperationException.class, () -> captured.getOutputStream().setWriteListener(null));
    }

    /**
     * Spring replaces a header by clearing it first with {@code setHeader(name, null)}
     * (ServletResponseHeadersAdapter.remove), as Tomcat's response treats it: a
     * removal. A stored null made the next getHeaders throw, so an API error
     * body written by an exception handler never reached the caller.
     */
    @Test
    void settingAHeaderToNullRemovesItAndAddingNullIsIgnored() {
        CapturedResponse captured = new CapturedResponse();
        captured.setHeader("Vary", "Accept");

        captured.setHeader("Vary", null);
        captured.addHeader("X-None", null);

        assertFalse(captured.containsHeader("Vary"));
        assertEquals(List.of(), List.copyOf(captured.getHeaders("Vary")));
        assertFalse(captured.containsHeader("X-None"));
    }

    @Test
    void headersAreCapturedCaseInsensitively() {
        CapturedResponse captured = new CapturedResponse();

        captured.setHeader("ETag", "\"v1\"");
        captured.addHeader("Vary", "Accept");
        captured.addHeader("vary", "Origin");
        captured.setIntHeader("X-Count", 3);
        captured.addIntHeader("X-Count", 4);
        captured.setDateHeader("Last-Modified", 1000L);
        captured.addDateHeader("X-Seen", 2000L);
        captured.addHeader("Content-Type", "application/problem+json");

        assertTrue(captured.containsHeader("etag"));
        assertFalse(captured.containsHeader("Location"));
        assertEquals("\"v1\"", captured.getHeader("ETAG"));
        assertNull(captured.getHeader("Location"));
        assertEquals(List.of("Accept", "Origin"), List.copyOf(captured.getHeaders("Vary")));
        assertEquals(List.of("3", "4"), List.copyOf(captured.getHeaders("x-count")));
        assertEquals(List.of(), List.copyOf(captured.getHeaders("Location")));
        assertEquals("1000", captured.getHeader("Last-Modified"));
        assertEquals("2000", captured.getHeader("X-Seen"));
        assertEquals(List.of("etag", "vary", "x-count", "last-modified", "x-seen", "content-type"),
                List.copyOf(captured.getHeaderNames()));
        assertEquals("application/problem+json", captured.getContentType(), "a Content-Type header sets it too");

        captured.setHeader("Content-Type", "application/json");
        assertEquals("application/json", captured.reply().contentType());
    }

    @Test
    void resetStartsTheCaptureOver() throws Exception {
        CapturedResponse captured = new CapturedResponse();
        captured.setStatus(500);
        captured.setContentType("text/html");
        captured.setHeader("X-Trace", "1");
        captured.getWriter().write("<html>");

        captured.reset();

        assertEquals(new ApiReply(200, null, ""), captured.reply());
        assertFalse(captured.containsHeader("X-Trace"));
    }

    @Test
    void containerHousekeepingIsHarmless() {
        CapturedResponse captured = new CapturedResponse();

        captured.setContentLength(5);
        captured.setContentLengthLong(5L);
        captured.setBufferSize(1);
        captured.addCookie(new Cookie("JSESSIONID", "x"));
        captured.setLocale(Locale.FRANCE);

        assertEquals("/a?b", captured.encodeURL("/a?b"));
        assertEquals("/a?b", captured.encodeRedirectURL("/a?b"));
        assertEquals(Locale.FRANCE, captured.getLocale());
        assertTrue(captured.getBufferSize() > 0);
        assertFalse(captured.containsHeader("Set-Cookie"), "a dispatched call sets no cookie");
        assertFalse(captured.containsHeader("Content-Length"));
    }
}
