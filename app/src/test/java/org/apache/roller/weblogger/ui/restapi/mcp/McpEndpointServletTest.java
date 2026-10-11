package org.apache.roller.weblogger.ui.restapi.mcp;

import java.util.ArrayList;
import java.util.List;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.ui.restapi.ApiProblemWriter;
import org.apache.roller.weblogger.ui.restapi.auth.ApiPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The servlet in front of the SDK transport: the transport through its whole
 * servlet life cycle, but only for a request authenticated by an API token.
 */
class McpEndpointServletTest {

    /** Stands in for the SDK transport; records what it was handed. */
    private static final class RecordingTransport extends HttpServlet {
        private static final long serialVersionUID = 1L;
        private final transient List<Object> exchangesSeen = new ArrayList<>();
        private boolean destroyed;

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) {
            exchangesSeen.add(List.of(request, response));
            response.setStatus(202);
        }

        @Override
        public void destroy() {
            destroyed = true;
        }
    }

    private static McpEndpointServlet servlet(HttpServlet transport) {
        return new McpEndpointServlet(transport, new ApiProblemWriter(JsonMapper.builder().build()));
    }

    private static void authenticate(Object details) {
        UsernamePasswordAuthenticationToken auth =
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void basicCredentialsAreRefusedWithAProblem() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        authenticate(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet(transport).service(new MockHttpServletRequest("POST", "/api/mcp"), response);

        assertTrue(transport.exchangesSeen.isEmpty());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("MCP accepts API tokens only."),
                response.getContentAsString());
    }

    @Test
    void noAuthenticationAtAllIsRefusedToo() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet(transport).service(new MockHttpServletRequest("POST", "/api/mcp"), response);

        assertTrue(transport.exchangesSeen.isEmpty());
        assertEquals(401, response.getStatus());
    }

    @Test
    void theTransportAnswersTheRequest() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        authenticate(new ApiPrincipal("alice", null, ApiToken.Role.POST, "t1"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet(transport).service(request, response);

        assertEquals(List.of(List.of(request, response)), transport.exchangesSeen);
        assertEquals(202, response.getStatus());
    }

    @Test
    void initialisesAndDestroysTheTransportWithItself() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        McpEndpointServlet servlet = servlet(transport);
        MockServletConfig config = new MockServletConfig("mcp");

        servlet.init(config);
        assertSame(config, transport.getServletConfig());

        servlet.destroy();
        assertTrue(transport.destroyed);
    }
}
