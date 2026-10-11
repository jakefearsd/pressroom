package org.apache.roller.weblogger.ui.restapi.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static ApiCall call(String method, String jsonBody) {
        return new ApiCall(method, "/v1/weblogs/b/entries", Map.of(), jsonBody);
    }

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
        DispatchedRequest req = new DispatchedRequest(mcpRequest(), call("PATCH", "{\"title\":\"t\"}"));

        assertEquals("PATCH", req.getMethod());
        assertEquals("application/json", req.getContentType());
        assertEquals("application/json", req.getHeader("content-type"));
        assertEquals(13, req.getContentLength());
        assertEquals("{\"title\":\"t\"}", new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void aCallWithoutABodyHasNoContentType() throws Exception {
        DispatchedRequest req = new DispatchedRequest(mcpRequest(), call("GET", null));

        assertNull(req.getContentType());
        assertEquals(0, req.getContentLength());
        assertEquals(-1, req.getInputStream().read());
    }

    @Test
    void nothingElseFromTheMcpRequestLeaksThrough() {
        DispatchedRequest req = new DispatchedRequest(mcpRequest(), call("GET", null));

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
        DispatchedRequest req = new DispatchedRequest(mcpRequest(), call("POST", "{\"title\":\"é\"}"));

        assertEquals(List.of("Accept", "Content-Type", "Content-Length"), Collections.list(req.getHeaderNames()));
        assertEquals(List.of("application/json"), Collections.list(req.getHeaders("content-type")));
        assertEquals(14, req.getIntHeader("Content-Length"), "a length in bytes, not characters");
        assertEquals(14L, req.getContentLengthLong());
        assertEquals("UTF-8", req.getCharacterEncoding());
        assertEquals(List.of("Accept"), Collections.list(
                new DispatchedRequest(mcpRequest(), call("GET", null)).getHeaderNames()));
    }

    @Test
    void theBodyReadsTheSameAsAReaderAndAsAStream() throws Exception {
        DispatchedRequest req = new DispatchedRequest(mcpRequest(), call("POST", "{\"title\":\"é\"}"));

        assertEquals("{\"title\":\"é\"}", req.getReader().readLine());

        ServletInputStream in = req.getInputStream();
        assertTrue(in.isReady());
        assertFalse(in.isFinished());
        in.readAllBytes();
        assertTrue(in.isFinished());
        assertThrows(UnsupportedOperationException.class, () -> in.setReadListener(null));
    }

    @Test
    void attributesSetDuringTheDispatchStayWithTheDispatch() {
        MockHttpServletRequest mcp = mcpRequest();
        mcp.setAttribute("outer.marker", "applied");
        mcp.setAttribute("outer.other", "kept");
        DispatchedRequest req = new DispatchedRequest(mcp, call("GET", null));

        req.setAttribute("actionWeblog", "it_weblog");
        req.removeAttribute("outer.marker");

        assertEquals("it_weblog", req.getAttribute("actionWeblog"));
        assertNull(req.getAttribute("outer.marker"), "removed, as the dispatch sees it");
        assertEquals("kept", req.getAttribute("outer.other"), "the MCP request's own attributes stay visible");
        assertEquals(Set.of("actionWeblog", "outer.other"), Set.copyOf(Collections.list(req.getAttributeNames())));

        assertNull(mcp.getAttribute("actionWeblog"), "nothing the dispatch set reaches the MCP request");
        assertEquals("applied", mcp.getAttribute("outer.marker"), "nor does a removal");
    }

    @Test
    void anAttributeCanBeSetAgainAfterRemovalAndNullingIsRemoval() {
        MockHttpServletRequest mcp = mcpRequest();
        mcp.setAttribute("outer.marker", "applied");
        DispatchedRequest req = new DispatchedRequest(mcp, call("GET", null));

        req.removeAttribute("outer.marker");
        req.setAttribute("outer.marker", "again");
        assertEquals("again", req.getAttribute("outer.marker"));

        req.setAttribute("outer.marker", null);
        assertNull(req.getAttribute("outer.marker"));
        assertEquals("applied", mcp.getAttribute("outer.marker"));
    }

    @Test
    void itsParametersAreExactlyTheCallsQuery() {
        MockHttpServletRequest mcp = mcpRequest();
        mcp.addParameter("q", "injected");
        mcp.addParameter("status", "PUBLISHED");
        DispatchedRequest req = new DispatchedRequest(mcp,
                new ApiCall("GET", "/v1/weblogs/b/entries", Map.of("q", List.of("a", "b")), null));

        assertEquals("a", req.getParameter("q"));
        assertArrayEquals(new String[] {"a", "b"}, req.getParameterValues("q"));
        assertNull(req.getParameter("status"));
        assertNull(req.getParameterValues("status"), "absent, as the servlet contract has it");
        assertEquals(List.of("q"), Collections.list(req.getParameterNames()));
        assertEquals(Set.of("q"), req.getParameterMap().keySet());
        assertArrayEquals(new String[] {"a", "b"}, req.getParameterMap().get("q"));
        assertThrows(UnsupportedOperationException.class, () -> req.getParameterMap().put("status", new String[0]));
    }
}
