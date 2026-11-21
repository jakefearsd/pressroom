package com.wikantik.pressroom.ui.restapi.mcp;

import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.ui.restapi.ApiProblemWriter;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refusals MCP makes itself carry codes under {@code mcp.}, with the
 * English they always had (spec 2026-11-20-api-contract-design, Task 3). Each
 * assertion checks the detail first, so on the code before Task 3 the English
 * passed (characterisation) and only the code failed.
 */
class McpProblemCodesTest {

    private static final List<ResolvedTool> TOOLS = McpToolCatalog.resolve(ApiRouteTables.real());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpToolExecutor executor = new McpToolExecutor((request, call) -> {
        throw new AssertionError("a refusal must not be dispatched: " + call);
    });

    private static ResolvedTool tool(String name) {
        return TOOLS.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private JsonNode refused(String tool, Map<String, Object> args) throws Exception {
        CallToolResult result = executor.call(new MockHttpServletRequest(), tool(tool), args);
        assertTrue(result.isError());
        JsonNode problem = JSON.readTree(((TextContent) result.content().get(0)).text());
        assertEquals(400, problem.get("status").asInt());
        return problem;
    }

    private static void assertProblem(JsonNode problem, String detail, String code) {
        assertEquals(detail, problem.get("detail").asString(), "detail of " + problem);
        assertEquals(code, problem.get("code").asString(), "code of " + problem);
    }

    private static String actions(String tool) {
        return String.join(", ", tool(tool).actions().keySet());
    }

    @Test
    void aMissingOrUnknownActionHasItsCode() throws Exception {
        assertProblem(refused("entries", Map.of("weblog", "b")),
                "entries has no action (none given); it is one of " + actions("entries") + ".", "mcp.actionMissing");
        assertProblem(refused("entries", Map.of("action", "nope", "weblog", "b")),
                "entries has no action 'nope'; it is one of " + actions("entries") + ".", "mcp.actionUnknown");
    }

    @Test
    void anArgumentTheActionDoesNotTakeIsMcpArgumentNotApplicable() throws Exception {
        assertProblem(refused("entries", Map.of("action", "list", "weblog", "b", "geoLatitude", 1.0)),
                "`geoLatitude` does not apply to `entries` `list`.", "mcp.argumentNotApplicable");
        assertProblem(refused("whoami", Map.of("action", "x")),
                "`action` does not apply to `whoami`.", "mcp.argumentNotApplicable");
    }

    @Test
    void aMissingArgumentIsMcpArgumentMissing() throws Exception {
        assertProblem(refused("comments", Map.of("action", "get", "weblog", "b")),
                "`comments` `get` needs `id`.", "mcp.argumentMissing");
    }

    @Test
    void anArgumentOfTheWrongShapeHasItsCode() throws Exception {
        assertProblem(refused("site_settings", Map.of("action", "update", "settings", "x")),
                "`settings` must be an object.", "mcp.argumentNotObject");
        assertProblem(refused("comments", Map.of("action", "get", "weblog", "b", "id", "a/b")),
                "`id` must be a single path segment: not empty, \".\" or \"..\", and without \"/\", \"\\\" or \";\".",
                "mcp.argumentNotPathSegment");
    }

    /** The endpoint's own 401, written before the MCP transport sees the request. */
    @Test
    void aCallerWithoutATokenIsMcpTokenRequired() throws Exception {
        McpEndpointServlet servlet = new McpEndpointServlet(new HttpServlet() {
            private static final long serialVersionUID = 1L;
        }, new ApiProblemWriter(JsonMapper.builder().build()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        servlet.service(new MockHttpServletRequest("POST", "/api/mcp"), response);

        assertEquals(401, response.getStatus());
        assertProblem(JSON.readTree(response.getContentAsString()), "MCP accepts API tokens only.", "mcp.tokenRequired");
    }
}
