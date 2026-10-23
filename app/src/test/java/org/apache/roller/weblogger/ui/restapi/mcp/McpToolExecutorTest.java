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

    /**
     * The 500 MCP builds itself is REST's opaque internal-error problem
     * (README, Errors), not a bare {status,title} with an HTTP reason phrase.
     */
    private static final String OPAQUE_500 = "{\"type\":\"https://roller.invalid/problems/internal-error\","
            + "\"title\":\"Internal error\",\"status\":500,\"detail\":\"The request could not be completed.\"}";

    /** A refusal MCP makes itself is the same problem shape a REST 400 is: a type, its title, the instance. */
    @Test
    void aRefusalMcpMakesItselfIsARestShapedProblem() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        CallToolResult result = executor.call(request, tool("entries"), Map.of("action", "get", "weblog", "b"));

        assertTrue(result.isError());
        tools.jackson.databind.JsonNode problem = new tools.jackson.databind.ObjectMapper().readTree(text(result));
        assertEquals("https://roller.invalid/problems/invalid-request", problem.get("type").asString());
        assertEquals("Invalid request", problem.get("title").asString());
        assertEquals(400, problem.get("status").asInt());
        assertEquals("/api/mcp", problem.get("instance").asString());
        assertTrue(problem.get("detail").asString().contains("`id`"), text(result));
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
    void aPathValueWithCharactersThatNeedEncodingStaysEncodedInsideItsSegment() {
        call("entries", Map.of("action", "get", "weblog", "my blog", "id", "a?x%"));
        assertEquals("/v1/weblogs/my%20blog/entries/a%3Fx%25", dispatched.get(0).path());
    }

    @Test
    void aPathValueThatCouldLeaveItsSegmentIsRefusedNotDispatched() {
        // the container decodes %2F and strips ;params when it forwards, so encoding alone is not enough
        for (String bad : List.of("a/../b", "b/entries/x", "a\\b", "a;x", "..;")) {
            CallToolResult result = call("entries", Map.of("action", "get", "weblog", bad, "id", "e1"));

            assertTrue(result.isError(), bad);
            assertTrue(text(result).contains("`weblog`") && text(result).contains("\"status\":400"), text(result));
        }
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void aWholeNumberDoubleStringifiesWithoutADecimalPoint() {
        call("entries", Map.of("action", "list", "weblog", "b", "limit", 10.0));
        assertEquals(List.of("10"), dispatched.get(0).query().get("limit"));

        call("entries", Map.of("action", "get", "weblog", "b", "id", 10.0));
        assertEquals("/v1/weblogs/b/entries/10", dispatched.get(1).path());

        call("entries", Map.of("action", "list", "weblog", "b", "limit", 10.5));
        assertEquals(List.of("10.5"), dispatched.get(2).query().get("limit"));
    }

    @Test
    void aDispatchThatFailsIsAnOpaqueFiveHundredToolError() {
        List<ApiDispatcher> failing = List.of(
                (request, call) -> {
                    throw new java.io.IOException("disk");
                },
                (request, call) -> {
                    throw new IllegalStateException("boom");
                });
        for (ApiDispatcher dispatcher : failing) {
            CallToolResult result = new McpToolExecutor(dispatcher)
                    .call(new MockHttpServletRequest(), tool("whoami"), Map.of());

            assertTrue(result.isError());
            assertEquals(OPAQUE_500, text(result));
        }
    }

    @Test
    void settingsGivenAsAStringIsRefusedNotDispatched() {
        CallToolResult result = call("site_settings", Map.of("action", "update", "settings", "site.shortName=x"));

        assertTrue(result.isError());
        assertTrue(text(result).contains("`settings` must be an object."), text(result));
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void anApiErrorIsAToolErrorCarryingTheProblemBody() {
        nextReply = new ApiReply(403, "application/problem+json",
                "{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"This token is read-only.\"}");
        CallToolResult result = call("entries", Map.of("action", "create", "weblog", "b", "title", "t"));

        assertTrue(result.isError());
        assertTrue(text(result).contains("This token is read-only."), text(result));
    }

    /**
     * A busy 503 (the anchor-lock timeout) reaches the agent as a tool error
     * carrying REST's own problem, retry advice in its detail: the
     * Retry-After header does not travel through MCP.
     */
    @Test
    void aBusyReplyIsAToolErrorCarryingTheRetryableProblem() {
        String busy = "{\"type\":\"https://roller.invalid/problems/busy\",\"title\":\"Busy\",\"status\":503,"
                + "\"detail\":\"Another save of this weblog is in progress; retry shortly.\"}";
        nextReply = new ApiReply(503, "application/problem+json", busy);

        CallToolResult result = call("entries", Map.of("action", "create", "weblog", "b", "title", "t"));

        assertTrue(result.isError());
        assertEquals(busy, text(result));
    }

    @Test
    void anEmptyOrNonJsonErrorBodyBecomesAProblemShape() {
        nextReply = new ApiReply(405, "text/html", "<html>nope</html>");
        CallToolResult result = call("entries", Map.of("action", "list", "weblog", "b"));

        assertTrue(result.isError());
        assertEquals("{\"type\":\"about:blank\",\"title\":\"Method Not Allowed\",\"status\":405}", text(result));
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
        for (String part : new String[] {"tok-1", "alice", "entries", "update", "weblog=b", "status=200", "ms="}) {
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

    @Test
    void aWeblogThatIsNotAHandleIsLoggedAsAQuestionMarkAndNeverForgesALine() {
        List<Object> hostile = List.of("b\n token=forged user=root", "x".repeat(65), "has space", 42,
                List.of("b"));
        for (Object weblog : hostile) {
            List<String> logged = logged(() ->
                    call("entries", Map.of("action", "get", "weblog", weblog, "id", "e1")));
            assertEquals(1, logged.size(), logged.toString());
            assertTrue(logged.get(0).contains("weblog=? "), logged.get(0));
            assertFalse(logged.get(0).contains("\n") || logged.get(0).contains("forged"), logged.get(0));
        }
        List<String> absent = logged(() -> call("whoami", Map.of()));
        assertTrue(absent.get(0).contains("weblog=- "), absent.get(0));
    }

    /** A bean whose getter throws: serialising it fails inside toCall. */
    public static final class Unserialisable {
        public String getBoom() {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void anUncheckedFailureOutsideDispatchIsTheOpaque500AndStillOneLogLine() {
        // an argument Jackson cannot serialise makes toCall throw, outside the dispatch catch
        Map<String, Object> args = Map.of("action", "create", "weblog", "b", "title", new Unserialisable());
        CallToolResult[] result = new CallToolResult[1];
        List<String> logged = logged(() -> result[0] = call("entries", args));

        assertTrue(result[0].isError());
        assertEquals(OPAQUE_500, text(result[0]));
        assertTrue(dispatched.isEmpty());
        assertEquals(1, logged.size(), logged.toString());
        assertTrue(logged.get(0).contains("status=500") && logged.get(0).contains("tool=entries"), logged.get(0));
    }

    @Test
    void aCallWithNoArgumentsMapAtAllIsTreatedAsNoArguments() {
        CallToolResult result = executor.call(new MockHttpServletRequest(), tool("whoami"), null);

        assertFalse(result.isError());
        assertEquals("/v1/me", dispatched.get(0).path());
    }

    @Test
    void anErrorReplyWithNoContentTypeOrABlankJsonBodyBecomesAProblemShape() {
        nextReply = new ApiReply(500, null, "{\"leak\":\"internal detail\"}");
        CallToolResult noType = call("entries", Map.of("action", "list", "weblog", "b"));
        assertEquals(OPAQUE_500, text(noType));

        nextReply = new ApiReply(404, "application/json", "  ");
        CallToolResult blank = call("entries", Map.of("action", "list", "weblog", "b"));
        assertEquals("{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}", text(blank));
    }

    @Test
    void anActionNameGivenForASingleActionToolIsRejectedAsNotApplying() {
        CallToolResult result = call("whoami", Map.of("action", "get"));

        assertTrue(result.isError());
        assertTrue(text(result).contains("`action` does not apply to `whoami`"), text(result));
        assertTrue(dispatched.isEmpty());
    }
}
