package com.wikantik.pressroom.ui.restapi.mcp;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The suite's {@code mcp_tool_called} event (SA-21; contract on the wiki's
 * SimpleAgilityAgentActivity): one ECS JSON line per tool call, which
 * jakemon's Agent activity dashboard and the weekly digest read from Loki
 * with {@code | json}. It sits beside the free-text audit line
 * ({@link McpToolExecutor#LOG_NAME}), which stays for grep.
 *
 * <p>The line goes to its own logger, which log4j2.xml sends to a stdout
 * appender that writes the message alone: the whole line has to be JSON for
 * Loki to parse it. Jackson writes it, so a CR, LF or quote in a value is
 * escaped and can never end the line or add a field.
 *
 * <p>Pressroom's MCP server is stateless: it never issues a session, and the
 * client's {@code clientInfo} arrives only with {@code initialize}, not with
 * a tool call. So the client name is the request's User-Agent and the session
 * hash is always {@code none} (SA-29). Never logged: arguments, bodies,
 * results, the reply's text.
 */
final class McpToolCalledEvent {

    /**
     * The event lines go to this logger, and only they go to log4j2.xml's
     * {@code eventConsole}. Deliberately not under {@code pressroom.mcp}: a
     * child logger would also reach whatever listens on the audit logger.
     */
    static final String LOG_NAME = "pressroom.mcp_tool_called";

    private static final Logger events = LoggerFactory.getLogger(LOG_NAME);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int CLIENT_NAME_MAX = 64;
    private static final String NONE = "none";
    private static final String UNKNOWN = "unknown";

    private McpToolCalledEvent() {
    }

    /**
     * Writes the event for one finished call.
     *
     * @param tool the registered tool name
     * @param action the tool's action, or empty or {@code -} when there is none to name
     * @param status the HTTP status the call ended with: 400 or above for every failure, since
     *        McpToolExecutor gives a refusal 400 and an exception 500
     * @param result what the agent was sent back
     * @param request the MCP HTTP request, for the client name
     * @param durationNanos how long the call took
     */
    static void write(String tool, String action, int status, CallToolResult result,
            HttpServletRequest request, long durationNanos) {
        events.info("{}", line(tool, action, status, result, request, durationNanos, Instant.now()));
    }

    static String line(String tool, String action, int status, CallToolResult result,
            HttpServletRequest request, long durationNanos, Instant at) {
        boolean failed = status >= 400;
        ObjectNode event = JSON.createObjectNode();
        event.put("@timestamp", at.truncatedTo(ChronoUnit.MILLIS).toString());
        event.put("log.level", "INFO");
        event.put("message", "mcp tool called");
        event.put("service.name", "pressroom");
        event.put("event.action", "mcp_tool_called");
        event.put("event.outcome", failed ? "failure" : "success");
        event.put("event.duration", Math.max(0L, durationNanos));
        event.put("mcp.server", "pressroom");
        event.put("mcp.tool", tool);
        event.put("mcp.client.name", clientName(request));
        event.put("mcp.session.hash", NONE);
        event.put("mcp.response.bytes", responseBytes(result));
        if (!action.isEmpty() && !"-".equals(action)) {
            event.put("pressroom.mcp.action", action);
        }
        if (failed) {
            event.put("error.type", "http_" + status);
        }
        return JSON.writeValueAsString(event);
    }

    /** The User-Agent with control characters dropped, cut to 64 characters; {@code unknown} when there is none. */
    static String clientName(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent == null) {
            return UNKNOWN;
        }
        StringBuilder name = new StringBuilder();
        agent.strip().codePoints()
                .filter(cp -> !Character.isISOControl(cp))
                .limit(CLIENT_NAME_MAX)
                .forEach(name::appendCodePoint);
        String cleaned = name.toString().strip();
        return cleaned.isEmpty() ? UNKNOWN : cleaned;
    }

    /** The UTF-8 size of the result's text, which is all McpToolExecutor ever sends back. */
    private static long responseBytes(CallToolResult result) {
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .mapToLong(text -> text.text().getBytes(StandardCharsets.UTF_8).length)
                .sum();
    }
}
