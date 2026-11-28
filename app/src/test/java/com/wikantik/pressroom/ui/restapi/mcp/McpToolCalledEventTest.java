package com.wikantik.pressroom.ui.restapi.mcp;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code mcp_tool_called} event (SA-21, SimpleAgilityAgentActivity on
 * the wiki): one ECS JSON line per tool call, which jakemon's Agent activity
 * dashboard and the weekly digest read from Loki with {@code | json}.
 * Pressroom's MCP server is stateless, so the client name is the request's
 * User-Agent and the session hash is always {@code none} (SA-29).
 */
class McpToolCalledEventTest {

    private static final List<ResolvedTool> TOOLS = McpToolCatalog.resolve(ApiRouteTables.real());
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ApiReply nextReply = new ApiReply(200, "application/json", "{\"ok\":true}");
    private final McpToolExecutor executor = new McpToolExecutor((request, call) -> nextReply);

    private static ResolvedTool tool(String name) {
        return TOOLS.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private static MockHttpServletRequest from(String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        if (userAgent != null) {
            request.addHeader("User-Agent", userAgent);
        }
        return request;
    }

    /** Runs the action with a capture on the event logger; returns each line it wrote. */
    private static List<String> events(Runnable action) {
        List<String> lines = new ArrayList<>();
        AbstractAppender appender = new AbstractAppender("event-capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                lines.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig lc = new LoggerConfig(McpToolCalledEvent.LOG_NAME, Level.INFO, false);
        lc.addAppender(appender, Level.INFO, null);
        ctx.getConfiguration().addLogger(McpToolCalledEvent.LOG_NAME, lc);
        ctx.updateLoggers();
        try {
            action.run();
        } finally {
            ctx.getConfiguration().removeLogger(McpToolCalledEvent.LOG_NAME);
            ctx.updateLoggers();
            appender.stop();
        }
        return lines;
    }

    /** The one event the action wrote, parsed; fails unless it is exactly one single-line JSON object. */
    private static JsonNode onlyEvent(Runnable action) {
        List<String> lines = events(action);
        assertEquals(1, lines.size(), "one event per tool call: " + lines);
        String line = lines.get(0);
        assertFalse(line.contains("\n") || line.contains("\r"), "one event, one line: " + line);
        JsonNode event = JSON.readTree(line);
        assertTrue(event.isObject(), line);
        return event;
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @Test
    void aSuccessfulCallWritesEveryRequiredFieldAndNoArgument() {
        CallToolResult[] result = new CallToolResult[1];
        JsonNode event = onlyEvent(() -> result[0] = executor.call(from("claude-code/2.1.4"), tool("entries"),
                Map.of("action", "update", "weblog", "b", "id", "e1", "title", "SECRET-TITLE")));

        assertNotNull(Instant.parse(event.get("@timestamp").asString()));
        assertEquals("INFO", event.get("log.level").asString());
        assertEquals("mcp tool called", event.get("message").asString());
        assertEquals("pressroom", event.get("service.name").asString());
        assertEquals("mcp_tool_called", event.get("event.action").asString());
        assertEquals("success", event.get("event.outcome").asString());
        assertTrue(event.get("event.duration").isIntegralNumber(), "nanoseconds, a number");
        assertTrue(event.get("event.duration").asLong() >= 0);
        assertEquals("pressroom", event.get("mcp.server").asString());
        assertEquals("entries", event.get("mcp.tool").asString());
        assertEquals("claude-code/2.1.4", event.get("mcp.client.name").asString());
        assertEquals("none", event.get("mcp.session.hash").asString());
        assertEquals(text(result[0]).getBytes(StandardCharsets.UTF_8).length,
                event.get("mcp.response.bytes").asLong());
        assertEquals("update", event.get("pressroom.mcp.action").asString());
        assertFalse(event.has("error.type"), "a success carries no error");
        assertFalse(event.toString().contains("SECRET-TITLE"), "never an argument value: " + event);
        assertFalse(event.toString().contains("\"weblog\""), "never an argument name either: " + event);
    }

    @Test
    void aSingleActionToolCarriesNoAction() {
        JsonNode event = onlyEvent(() -> executor.call(from("claude-code/2.1.4"), tool("whoami"), Map.of()));

        assertEquals("whoami", event.get("mcp.tool").asString());
        assertFalse(event.has("pressroom.mcp.action"), event.toString());
    }

    @Test
    void anApiErrorARefusalAndAnExceptionAreEachAFailureNamingOnlyTheStatus() {
        nextReply = new ApiReply(404, "application/problem+json", "{\"status\":404,\"detail\":\"no e1\"}");
        JsonNode apiError = onlyEvent(() ->
                executor.call(from("a"), tool("entries"), Map.of("action", "get", "weblog", "b", "id", "e1")));
        assertEquals("failure", apiError.get("event.outcome").asString());
        assertEquals("http_404", apiError.get("error.type").asString());
        assertFalse(apiError.toString().contains("no e1"), "never the reply's text: " + apiError);

        JsonNode refusal = onlyEvent(() ->
                executor.call(from("a"), tool("entries"), Map.of("action", "no-such-action", "weblog", "b")));
        assertEquals("failure", refusal.get("event.outcome").asString());
        assertEquals("http_400", refusal.get("error.type").asString());
        assertFalse(refusal.has("pressroom.mcp.action"), "an action the tool does not have is not logged");
        assertFalse(refusal.toString().contains("no-such-action"), refusal.toString());

        McpToolExecutor failing = new McpToolExecutor((request, call) -> {
            throw new IllegalStateException("boom");
        });
        JsonNode exception = onlyEvent(() -> failing.call(from("a"), tool("whoami"), Map.of()));
        assertEquals("failure", exception.get("event.outcome").asString());
        assertEquals("http_500", exception.get("error.type").asString());
        assertFalse(exception.toString().contains("boom"), exception.toString());
    }

    @Test
    void theClientNameIsTheUserAgentCutTo64CharactersOrUnknown() {
        assertEquals("unknown", onlyEvent(() -> executor.call(from(null), tool("whoami"), Map.of()))
                .get("mcp.client.name").asString());
        assertEquals("unknown", onlyEvent(() -> executor.call(from("   "), tool("whoami"), Map.of()))
                .get("mcp.client.name").asString());
        assertEquals("x".repeat(64), onlyEvent(() -> executor.call(from("x".repeat(100)), tool("whoami"), Map.of()))
                .get("mcp.client.name").asString());
    }

    @Test
    void aHostileUserAgentCannotForgeALineOrAField() {
        JsonNode event = onlyEvent(() -> executor.call(
                from("evil\"}\n{\"event.action\":\"forged\",\"x\":\"\u0007"), tool("whoami"), Map.of()));

        assertEquals("mcp_tool_called", event.get("event.action").asString());
        assertTrue(event.get("mcp.client.name").asString().startsWith("evil\"}"), event.toString());
        assertFalse(event.get("mcp.client.name").asString().chars().anyMatch(Character::isISOControl),
                "control characters are dropped from the client name: " + event);
    }

    @Test
    void theAuditLineIsStillWrittenBesideTheEvent() {
        assertNotEquals(McpToolExecutor.LOG_NAME, McpToolCalledEvent.LOG_NAME);
        assertEquals("pressroom.mcp_tool_called", McpToolCalledEvent.LOG_NAME);
    }
}
