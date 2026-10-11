package org.apache.roller.weblogger.ui.restapi.mcp;

import java.util.ArrayList;
import java.util.List;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The servlet in front of the SDK transport: for now, simply the transport,
 * through its whole servlet life cycle.
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

    @Test
    void theTransportAnswersTheRequest() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new McpEndpointServlet(transport).service(request, response);

        assertEquals(List.of(List.of(request, response)), transport.exchangesSeen);
        assertEquals(202, response.getStatus());
    }

    @Test
    void initialisesAndDestroysTheTransportWithItself() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        McpEndpointServlet servlet = new McpEndpointServlet(transport);
        MockServletConfig config = new MockServletConfig("mcp");

        servlet.init(config);
        assertSame(config, transport.getServletConfig());

        servlet.destroy();
        assertTrue(transport.destroyed);
    }
}
