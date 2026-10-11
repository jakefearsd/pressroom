package org.apache.roller.weblogger.boot;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import org.apache.roller.weblogger.ui.restapi.mcp.ApiCall;
import org.apache.roller.weblogger.ui.restapi.mcp.ApiDispatcher;
import org.apache.roller.weblogger.ui.restapi.mcp.ApiReply;
import org.apache.roller.weblogger.ui.restapi.mcp.ApiRouteTable;
import org.apache.roller.weblogger.ui.restapi.mcp.ApiRouteTables;
import org.apache.roller.weblogger.ui.restapi.mcp.McpEndpointServlet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The MCP endpoint's wiring, driven through the real SDK transport and
 * server with no container: the servlet's mapping, the initialize answer,
 * and a tool call reaching the dispatcher with the MCP request it came in
 * on. Whether the dispatcher's forward then reaches the API is McpIT's.
 */
class McpServerConfigTest {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final ApiRouteTable ROUTES = ApiRouteTables.real();

    private final McpServerConfig config = new McpServerConfig();

    @Test
    void theEndpointIsMappedAtExactlyApiMcp() {
        HttpServletStatelessServerTransport transport = config.mcpTransport();
        ServletRegistrationBean<McpEndpointServlet> registration = config.mcpEndpointRegistration(
                transport, config.mcpServer(transport, (request, call) -> fail("nothing is dispatched"), ROUTES));

        assertEquals(List.of("/api/mcp"), List.copyOf(registration.getUrlMappings()));
        assertEquals("/api/mcp", McpServerConfig.ENDPOINT);
        assertEquals("mcp", registration.getServletName());
    }

    /**
     * The transport is an HttpServlet bean, and Boot maps every Servlet bean
     * that no registration claims (at "/" when it is the only one, else at
     * "/beanName/") -- which would serve the SDK transport a second time,
     * outside the API security chain, beside the one McpEndpointServlet wraps.
     */
    @Test
    void onlyTheEndpointServletIsMappedNeverTheBareTransport() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // a ready singleton: registered as a definition, the context would re-aware it
            context.getBeanFactory().registerSingleton("requestMappingHandlerMapping",
                    ApiRouteTables.realMapping());
            context.register(McpServerConfig.class);
            context.refresh();
            List<String> mapped = new ArrayList<>();
            for (ServletContextInitializer initializer : new ServletContextInitializerBeans(context.getBeanFactory())) {
                if (initializer instanceof ServletRegistrationBean<?> servlet && servlet.isEnabled()) {
                    mapped.add(servlet.getServlet().getClass().getSimpleName() + " " + servlet.getUrlMappings());
                }
            }
            assertEquals(List.of("McpEndpointServlet [/api/mcp]"), mapped);
        }
    }

    @Test
    void initializeAnswersAsRollerWithTheBuildsVersion() throws Exception {
        JsonNode answer = post(rpc("initialize", Map.of(
                "protocolVersion", "2025-06-18",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "McpServerConfigTest", "version", "1"))),
                (request, call) -> fail("initialize dispatches nothing"));

        JsonNode serverInfo = answer.get("result").get("serverInfo");
        assertEquals("roller", serverInfo.get("name").asString(), answer.toString());
        assertEquals(builtVersion(), serverInfo.get("version").asString());
        assertTrue(answer.get("result").get("capabilities").has("tools"), answer.toString());
    }

    @Test
    void aToolCallIsDispatchedOnBehalfOfTheMcpRequestItCameIn() throws Exception {
        List<ApiCall> calls = new ArrayList<>();
        List<Object> requests = new ArrayList<>();
        ApiDispatcher dispatcher = (request, call) -> {
            calls.add(call);
            requests.add(request);
            return new ApiReply(200, "application/json", "{\"userName\":\"u\"}");
        };

        MockHttpServletRequest mcpRequest =
                mcpRequest(rpc("tools/call", Map.of("name", "whoami", "arguments", Map.of())));
        JsonNode answer = post(mcpRequest, dispatcher);

        assertEquals(List.of(new ApiCall("GET", "/v1/me", Map.of(), null)), calls);
        assertSame(mcpRequest, requests.get(0), "the tool is handed the request the servlet was");
        JsonNode result = answer.get("result");
        assertEquals("{\"userName\":\"u\"}", result.get("content").get(0).get("text").asString());
        assertFalse(result.path("isError").asBoolean(false), answer.toString());
    }

    @Test
    void anApiRefusalIsAToolErrorCarryingTheApisBody() throws Exception {
        JsonNode answer = post(rpc("tools/call", Map.of("name", "entries",
                        "arguments", Map.of("action", "trash", "weblog", "b", "id", "e1"))),
                (request, call) -> new ApiReply(404, "application/problem+json", "{\"status\":404}"));

        JsonNode result = answer.get("result");
        assertTrue(result.get("isError").asBoolean(), answer.toString());
        assertEquals("{\"status\":404}", result.get("content").get(0).get("text").asString());
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode post(String body, ApiDispatcher dispatcher) throws Exception {
        return post(mcpRequest(body), dispatcher);
    }

    private JsonNode post(MockHttpServletRequest request, ApiDispatcher dispatcher) throws Exception {
        HttpServletStatelessServerTransport transport = config.mcpTransport();
        McpEndpointServlet servlet =
                config.mcpEndpointRegistration(transport, config.mcpServer(transport, dispatcher, ROUTES)).getServlet();
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(request, response);

        assertEquals(200, response.getStatus(), response.getContentAsString());
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest mcpRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", McpServerConfig.ENDPOINT);
        request.setContentType("application/json");
        request.addHeader("Accept", "application/json, text/event-stream");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static String rpc(String method, Object params) {
        Map<String, Object> rpc = new LinkedHashMap<>();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", 1);
        rpc.put("method", method);
        rpc.put("params", params);
        return JSON.writeValueAsString(rpc);
    }

    private static String builtVersion() throws Exception {
        Properties props = new Properties();
        try (InputStream in = McpServerConfigTest.class.getResourceAsStream("/roller-version.properties")) {
            assertNotNull(in, "roller-version.properties is on the classpath");
            props.load(in);
        }
        return props.getProperty("ro.version");
    }
}
