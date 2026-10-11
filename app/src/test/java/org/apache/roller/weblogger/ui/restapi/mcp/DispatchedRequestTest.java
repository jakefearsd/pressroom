package org.apache.roller.weblogger.ui.restapi.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The request a tool call is forwarded as: the API sees the method and JSON
 * body the action needs, and nothing else from the MCP request -- not its
 * Authorization header (identity travels in the security context), not its
 * cookies, not its JSON-RPC body.
 */
class DispatchedRequestTest {

    private static MockHttpServletRequest mcpRequest() {
        MockHttpServletRequest mcp = new MockHttpServletRequest("POST", "/api/mcp");
        mcp.addHeader("Authorization", "Bearer rlr_secret");
        mcp.addHeader("Mcp-Protocol-Version", "2025-06-18");
        mcp.setCookies(new Cookie("JSESSIONID", "x"));
        mcp.setContent("{\"jsonrpc\":\"2.0\"}".getBytes(StandardCharsets.UTF_8));
        mcp.setContentType("application/json");
        return mcp;
    }

    @Test
    void carriesTheActionsMethodAndJsonBody() throws Exception {
        DispatchedRequest req = new DispatchedRequest(mcpRequest(), "PATCH", "{\"title\":\"t\"}");

        assertEquals("PATCH", req.getMethod());
        assertEquals("application/json", req.getContentType());
        assertEquals("application/json", req.getHeader("content-type"));
        assertEquals(13, req.getContentLength());
        assertEquals("{\"title\":\"t\"}", new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void aCallWithoutABodyHasNoContentType() throws Exception {
        DispatchedRequest req = new DispatchedRequest(mcpRequest(), "GET", null);

        assertNull(req.getContentType());
        assertEquals(0, req.getContentLength());
        assertEquals(-1, req.getInputStream().read());
    }

    @Test
    void nothingElseFromTheMcpRequestLeaksThrough() {
        DispatchedRequest req = new DispatchedRequest(mcpRequest(), "GET", null);

        assertNull(req.getHeader("Authorization"));
        assertNull(req.getHeader("Mcp-Protocol-Version"));
        assertNull(req.getCookies());
        assertEquals("application/json", req.getHeader("Accept"));
        assertTrue(Collections.list(req.getHeaderNames()).stream()
                .noneMatch(h -> h.equalsIgnoreCase("Authorization")));
        assertFalse(req.getHeaders("Authorization").hasMoreElements());
        assertEquals(-1, req.getDateHeader("If-Modified-Since"));
        assertEquals(-1, req.getIntHeader("Content-Length"));
    }

    @Test
    void theHeadersItDoesCarryDescribeTheBody() {
        DispatchedRequest req = new DispatchedRequest(mcpRequest(), "POST", "{\"title\":\"é\"}");

        assertEquals(List.of("Accept", "Content-Type", "Content-Length"), Collections.list(req.getHeaderNames()));
        assertEquals(List.of("application/json"), Collections.list(req.getHeaders("content-type")));
        assertEquals(14, req.getIntHeader("Content-Length"), "a length in bytes, not characters");
        assertEquals(14L, req.getContentLengthLong());
        assertEquals("UTF-8", req.getCharacterEncoding());
        assertEquals(List.of("Accept"), Collections.list(
                new DispatchedRequest(mcpRequest(), "GET", null).getHeaderNames()));
    }

    @Test
    void theBodyReadsTheSameAsAReaderAndAsAStream() throws Exception {
        DispatchedRequest req = new DispatchedRequest(mcpRequest(), "POST", "{\"title\":\"é\"}");

        assertEquals("{\"title\":\"é\"}", req.getReader().readLine());

        ServletInputStream in = req.getInputStream();
        assertTrue(in.isReady());
        assertFalse(in.isFinished());
        in.readAllBytes();
        assertTrue(in.isFinished());
        assertThrows(UnsupportedOperationException.class, () -> in.setReadListener(null));
    }
}
