package org.apache.roller.weblogger.ui.restapi.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forward a tool call becomes, against a servlet context whose one
 * route answers the way an API route does. That a real container's forward
 * leaves the MCP response writable is {@code InProcessDispatchContainerTest}'s
 * to show; that it reaches the right controller with the caller's identity
 * is McpIT's.
 */
class InProcessApiDispatcherTest {

    /** Records what was forwarded to it and answers 201 with a JSON body. */
    private static final class ApiRouteContext extends MockServletContext {
        private String path;
        private String method;
        private String body;
        private String authorization;

        @Override
        public RequestDispatcher getRequestDispatcher(String path) {
            this.path = path;
            if (path.startsWith("/api/v1/nowhere")) {
                return null;
            }
            return new RequestDispatcher() {
                @Override
                public void forward(ServletRequest request, ServletResponse response) throws IOException {
                    HttpServletRequest http = (HttpServletRequest) request;
                    method = http.getMethod();
                    body = new String(http.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    authorization = http.getHeader("Authorization");
                    HttpServletResponse out = (HttpServletResponse) response;
                    out.setStatus(201);
                    out.setContentType("application/json");
                    out.getWriter().write("{\"id\":\"e1\"}");
                }

                @Override
                public void include(ServletRequest request, ServletResponse response) {
                    throw new UnsupportedOperationException("a tool call is forwarded, never included");
                }
            };
        }
    }

    private final ApiRouteContext context = new ApiRouteContext();

    private MockHttpServletRequest mcpRequest() {
        MockHttpServletRequest mcp = new MockHttpServletRequest(context, "POST", "/api/mcp");
        mcp.addHeader("Authorization", "Bearer rlr_secret");
        return mcp;
    }

    @Test
    void forwardsTheCallToItsApiRouteAndReturnsWhatTheRouteWrote() throws Exception {
        ApiReply reply = new InProcessApiDispatcher().dispatch(mcpRequest(),
                new ApiCall("POST", "/v1/weblogs/b/entries", Map.of(), "{\"title\":\"t\"}"));

        assertEquals("/api/v1/weblogs/b/entries", context.path);
        assertEquals("POST", context.method);
        assertEquals("{\"title\":\"t\"}", context.body);
        assertNull(context.authorization, "identity travels in the security context, not a header");
        assertEquals(new ApiReply(201, "application/json", "{\"id\":\"e1\"}"), reply);
    }

    @Test
    void aCallWithoutAQueryGoesToTheBarePath() throws Exception {
        new InProcessApiDispatcher().dispatch(mcpRequest(), new ApiCall("GET", "/v1/me", null, null));

        assertEquals("/api/v1/me", context.path);
        assertEquals("GET", context.method);
        assertEquals("", context.body);
    }

    @Test
    void encodesEveryQueryValueSoNoneIsReadBackAsSomethingElse() throws Exception {
        Map<String, List<String>> query = Map.of("q", List.of("a b", "c&d=e", "10:00+02:00"));

        new InProcessApiDispatcher().dispatch(mcpRequest(), new ApiCall("GET", "/v1/weblogs/b/entries", query, null));

        // A bare '+' in a query string is read back as a space, so a time
        // zone offset would arrive as "10:00 02:00".
        assertEquals("/api/v1/weblogs/b/entries?q=a%20b&q=c%26d%3De&q=10%3A00%2B02%3A00", context.path);
    }

    @Test
    void aPathTheContainerCannotDispatchToIsRefusedByName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new InProcessApiDispatcher()
                .dispatch(mcpRequest(), new ApiCall("GET", "/v1/nowhere", null, null)));

        assertTrue(e.getMessage().contains("/api/v1/nowhere"), e.getMessage());
    }
}
