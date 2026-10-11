package org.apache.roller.weblogger.ui.restapi.mcp;

import java.util.ArrayList;
import java.util.HashMap;
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
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.ui.restapi.auth.ApiPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

class McpToolExecutorTest {

    private static final List<ResolvedTool> TOOLS = McpToolCatalog.resolve(ApiRouteTables.real());

    private final List<ApiCall> dispatched = new ArrayList<>();
    private ApiReply nextReply = new ApiReply(200, "application/json", "{\"ok\":true}");
    private final McpToolExecutor executor = new McpToolExecutor((request, call) -> {
        dispatched.add(call);
        return nextReply;
    });

    private static ResolvedTool tool(String name) {
        return TOOLS.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private CallToolResult call(String tool, Map<String, Object> args) {
        return executor.call(new MockHttpServletRequest(), tool(tool), args);
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @Test
    void anActionBecomesItsApiCall() {
        CallToolResult result = call("entries", Map.of("action", "update", "weblog", "b", "id", "e1",
                "title", "New", "geoLatitude", 32.9));

        assertFalse(result.isError());
        assertEquals("{\"ok\":true}", text(result));
        ApiCall sent = dispatched.get(0);
        assertEquals("PATCH", sent.method());
        assertEquals("/v1/weblogs/b/entries/e1", sent.path());
        assertTrue(sent.jsonBody().contains("\"title\":\"New\""), sent.jsonBody());
        assertTrue(sent.jsonBody().contains("\"geoLatitude\":32.9"), sent.jsonBody());
        assertFalse(sent.jsonBody().contains("weblog"), "path arguments stay out of the body");
    }

    @Test
    void queryArgumentsBecomeQueryParametersAndArraysRepeat() {
        call("entries", Map.of("action", "list", "weblog", "b", "tags", List.of("a", "b"), "limit", 10,
                "status", "PUBLISHED"));

        ApiCall sent = dispatched.get(0);
        assertEquals("GET", sent.method());
        assertNull(sent.jsonBody());
        assertEquals(List.of("a", "b"), sent.query().get("tags"));
        assertEquals(List.of("10"), sent.query().get("limit"));
        assertEquals(List.of("PUBLISHED"), sent.query().get("status"));
    }

    @Test
    void aBooleanQueryValueStringifiesPlainly() {
        call("users", Map.of("action", "list", "enabled", false));
        assertEquals(List.of("false"), dispatched.get(0).query().get("enabled"));
    }

    @Test
    void anActionWithOnlyPathArgumentsDispatchesToItsRoute() {
        call("entries", Map.of("action", "restore", "weblog", "b", "id", "e1"));
        ApiCall sent = dispatched.get(0);
        assertEquals("POST", sent.method());
        assertEquals("/v1/weblogs/b/entries/e1/restore", sent.path());
    }

    @Test
    void aBodyRouteGivenNoBodyFieldsSendsAnEmptyObject() {
        call("redirects", Map.of("action", "create", "weblog", "b"));
        assertEquals("{}", dispatched.get(0).jsonBody());
    }

    @Test
    void theSettingsObjectIsTheConfigBody() {
        call("site_settings", Map.of("action", "update", "settings", Map.of("site.shortName", "Wikantik")));
        assertEquals("{\"site.shortName\":\"Wikantik\"}", dispatched.get(0).jsonBody());
    }

    @Test
    void whoamiTakesNoAction() {
        call("whoami", Map.of());
        assertEquals("/v1/me", dispatched.get(0).path());

        CallToolResult withAction = call("whoami", Map.of("action", "list"));
        assertTrue(withAction.isError());
        assertTrue(text(withAction).contains("does not apply"), text(withAction));
    }

    @Test
    void anUnknownOrNonStringActionIsAToolErrorListingTheValidOnes() {
        CallToolResult unknown = call("entries", Map.of("action", "publish", "weblog", "b"));
        assertTrue(unknown.isError());
        assertTrue(text(unknown).contains("list, get, create"), text(unknown));

        CallToolResult notString = call("entries", Map.of("action", 7));
        assertTrue(notString.isError());
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void anArgumentTheActionDoesNotTakeIsRejectedNotDropped() {
        CallToolResult result = call("entries", Map.of("action", "list", "weblog", "b", "geoLatitude", 1.0));

        assertTrue(result.isError());
        assertTrue(text(result).contains("`geoLatitude` does not apply to `entries` `list`"), text(result));
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void aMissingRequiredArgumentIsAToolErrorAndNothingIsDispatched() {
        CallToolResult result = call("entries", Map.of("action", "get", "weblog", "b"));

        assertTrue(result.isError());
        assertTrue(text(result).contains("`id`"), text(result));
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void aNullValuedArgumentCountsAsAbsent() {
        Map<String, Object> args = new HashMap<>(Map.of("action", "update", "weblog", "b", "id", "e1"));
        args.put("tags", null);
        call("entries", args);

        assertFalse(dispatched.get(0).jsonBody().contains("tags"), dispatched.get(0).jsonBody());
    }

    @Test
    void aPathValueStaysInsideItsSegment() {
        call("entries", Map.of("action", "get", "weblog", "my blog", "id", "a/../b?x"));
        assertEquals("/v1/weblogs/my%20blog/entries/a%2F..%2Fb%3Fx", dispatched.get(0).path());
    }

    @Test
    void anApiErrorIsAToolErrorCarryingTheProblemBody() {
        nextReply = new ApiReply(403, "application/problem+json",
                "{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"This token is read-only.\"}");
        CallToolResult result = call("entries", Map.of("action", "create", "weblog", "b", "title", "t"));

        assertTrue(result.isError());
        assertTrue(text(result).contains("This token is read-only."), text(result));
    }

    @Test
    void anEmptyOrNonJsonErrorBodyBecomesAProblemShape() {
        nextReply = new ApiReply(405, "text/html", "<html>nope</html>");
        CallToolResult result = call("entries", Map.of("action", "list", "weblog", "b"));

        assertTrue(result.isError());
        assertEquals("{\"status\":405,\"title\":\"Method Not Allowed\"}", text(result));
    }

    @Test
    void anEmptySuccessBodyIsAnEmptyObject() {
        nextReply = new ApiReply(204, null, "");
        CallToolResult result = call("redirects", Map.of("action", "delete", "weblog", "b", "id", "r1"));

        assertFalse(result.isError());
        assertEquals("{}", text(result));
    }

    @Test
    void aPathArgumentThatIsEmptyOrADotSegmentIsRefusedNotDispatched() {
        for (String bad : List.of("", ".", "..")) {
            CallToolResult result = call("entries", Map.of("action", "get", "weblog", "b", "id", bad));

            assertTrue(result.isError(), "id '" + bad + "'");
            assertTrue(text(result).contains("`id`"), text(result));
            assertTrue(text(result).contains("\"status\":400"), text(result));
        }
        assertTrue(dispatched.isEmpty(), "a dot segment would be normalized by the container and re-route the call");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Runs the action with a capture on the roller.mcp logger; returns the formatted lines. */
    private static List<String> logged(Runnable action) {
        List<String> lines = new ArrayList<>();
        AbstractAppender appender = new AbstractAppender("mcp-capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                lines.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig lc = new LoggerConfig(McpToolExecutor.LOG_NAME, Level.INFO, false);
        lc.addAppender(appender, Level.INFO, null);
        ctx.getConfiguration().addLogger(McpToolExecutor.LOG_NAME, lc);
        ctx.updateLoggers();
        try {
            UsernamePasswordAuthenticationToken auth =
                    UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());
            auth.setDetails(new ApiPrincipal("alice", null, ApiToken.Role.POST, "tok-1"));
            SecurityContextHolder.getContext().setAuthentication(auth);
            action.run();
        } finally {
            ctx.getConfiguration().removeLogger(McpToolExecutor.LOG_NAME);
            ctx.updateLoggers();
            appender.stop();
        }
        return lines;
    }

    @Test
    void eachCallLogsOneLineWithNoArgumentValues() {
        List<String> logged = logged(() ->
                call("entries", Map.of("action", "update", "weblog", "b", "id", "e1", "title", "SECRET-TITLE")));

        assertEquals(1, logged.size(), logged.toString());
        String line = logged.get(0);
        for (String part : new String[] {"tok-1", "alice", "entries", "update", "b", "200"}) {
            assertTrue(line.contains(part), part + " in " + line);
        }
        assertFalse(line.contains("SECRET-TITLE"), line);
        assertEquals("roller.mcp", McpToolExecutor.LOG_NAME);
    }

    @Test
    void anApiErrorARefusalAndADispatchFailureEachLogOnceWithTheirStatus() {
        nextReply = new ApiReply(404, "application/problem+json", "{\"status\":404}");
        List<String> apiError = logged(() -> call("entries", Map.of("action", "get", "weblog", "b", "id", "e1")));
        assertEquals(1, apiError.size(), apiError.toString());
        assertTrue(apiError.get(0).contains("status=404"), apiError.get(0));

        List<String> refusal = logged(() -> call("entries", Map.of("action", "get", "weblog", "b")));
        assertEquals(1, refusal.size(), refusal.toString());
        assertTrue(refusal.get(0).contains("status=400") && refusal.get(0).contains("tool=entries"), refusal.get(0));

        McpToolExecutor failing = new McpToolExecutor((request, call) -> {
            throw new IllegalStateException("boom");
        });
        List<String> failure = logged(() ->
                failing.call(new MockHttpServletRequest(), tool("whoami"), Map.of()));
        assertEquals(1, failure.size(), failure.toString());
        assertTrue(failure.get(0).contains("status=500") && failure.get(0).contains("action=-"), failure.get(0));
    }
}
