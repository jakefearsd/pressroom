package com.wikantik.pressroom.ui.restapi.mcp;

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
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.ui.restapi.auth.ApiPrincipal;
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

    /** Spec 2026-11-15 criterion 8: an agent sets a blog's order and pins a post through MCP. */
    @Test
    void anAgentSetsTheBlogOrderAndPinsAPost() {
        CallToolResult order = call("weblogs", Map.of("action", "update", "weblog", "b", "entryOrder", "OLDEST"));
        CallToolResult pin = call("entries", Map.of("action", "update", "weblog", "b", "id", "e1",
                "pinned", true));

        assertFalse(order.isError(), text(order));
        assertFalse(pin.isError(), text(pin));
        ApiCall weblogCall = dispatched.get(0);
        assertEquals("PATCH", weblogCall.method());
        assertEquals("/v1/weblogs/b", weblogCall.path());
        assertTrue(weblogCall.jsonBody().contains("\"entryOrder\":\"OLDEST\""), weblogCall.jsonBody());
        ApiCall entryCall = dispatched.get(1);
        assertEquals("/v1/weblogs/b/entries/e1", entryCall.path());
        assertTrue(entryCall.jsonBody().contains("\"pinned\":true"), entryCall.jsonBody());
    }

    /**
     * Spec 2026-11-15 criterion 18: an agent makes a series through MCP --
     * pages set_parts sends the ordered ids as the PUT body, and entries
     * update carries strand in the PATCH body, as does clear: ["strand"].
     */
    @Test
    void anAgentSetsAStrandsPartsAndPutsAnEntryInOne() {
        CallToolResult parts = call("pages", Map.of("action", "set_parts", "weblog", "b", "id", "p1",
                "entries", List.of("e3", "e1")));
        CallToolResult join = call("entries", Map.of("action", "update", "weblog", "b", "id", "e2",
                "strand", "p1"));
        CallToolResult leave = call("entries", Map.of("action", "update", "weblog", "b", "id", "e2",
                "clear", List.of("strand")));

        assertFalse(parts.isError(), text(parts));
        assertFalse(join.isError(), text(join));
        assertFalse(leave.isError(), text(leave));
        assertEquals(List.of("PUT /v1/weblogs/b/pages/p1/parts", "PATCH /v1/weblogs/b/entries/e2",
                "PATCH /v1/weblogs/b/entries/e2"), dispatched.stream().map(c -> c.method() + " " + c.path()).toList());
        assertEquals("{\"entries\":[\"e3\",\"e1\"]}", dispatched.get(0).jsonBody());
        assertEquals("{\"strand\":\"p1\"}", dispatched.get(1).jsonBody());
        assertEquals("{\"clear\":[\"strand\"]}", dispatched.get(2).jsonBody());
    }

    /**
     * fields (spec 2026-10-24, section 2) is a query array on every entries
     * action that returns entries, whichever variant of get the call selects,
     * and on update it travels beside the JSON body, never inside it.
     */
    @Test
    void entriesFieldsIsAnArrayThatReachesEachRouteAsARepeatedQueryParameter() {
        List<String> asked = List.of("anchor", "searchDescription");
        call("entries", Map.of("action", "get", "weblog", "b", "id", "e1", "fields", asked));
        call("entries", Map.of("action", "get", "weblog", "b", "anchor", "harbour-walk", "fields", asked));
        call("entries", Map.of("action", "list", "weblog", "b", "fields", asked));
        call("entries", Map.of("action", "update", "weblog", "b", "id", "e1", "searchDescription", "New.",
                "fields", asked));
        call("entries", Map.of("action", "create", "weblog", "b", "title", "t", "text", "x", "fields", asked));
        call("entries", Map.of("action", "restore", "weblog", "b", "id", "e1", "fields", asked));

        assertEquals(List.of("/v1/weblogs/b/entries/e1", "/v1/weblogs/b/entries/by-anchor/harbour-walk",
                "/v1/weblogs/b/entries", "/v1/weblogs/b/entries/e1", "/v1/weblogs/b/entries",
                "/v1/weblogs/b/entries/e1/restore"), dispatched.stream().map(ApiCall::path).toList());
        for (ApiCall sent : dispatched) {
            assertEquals(asked, sent.query().get("fields"), sent.path());
        }
        String update = dispatched.get(3).jsonBody();
        assertTrue(update.contains("\"searchDescription\":\"New.\"") && !update.contains("fields"), update);
    }

    /** audits seo_entry (spec 2026-10-24, section 6) is one entry's audit, by its id. */
    @Test
    void auditsSeoEntryIsTheOneEntryAuditById() {
        CallToolResult result = call("audits", Map.of("action", "seo_entry", "weblog", "b", "id", "e1"));

        assertFalse(result.isError());
        ApiCall sent = dispatched.get(0);
        assertEquals("GET", sent.method());
        assertEquals("/v1/weblogs/b/audit/seo/entries/e1", sent.path());
        assertNull(sent.jsonBody());
        assertTrue(sent.query().isEmpty(), sent.query().toString());
    }

    @Test
    void auditsSeoEntryWithoutAnIdIsAToolErrorAndNothingIsDispatched() {
        CallToolResult result = call("audits", Map.of("action", "seo_entry", "weblog", "b"));

        assertTrue(result.isError());
        assertTrue(text(result).contains("id"), text(result));
        assertTrue(dispatched.isEmpty());
    }

    /**
     * The revision actions (spec 2026-10-24, section 5) reach their routes:
     * revisionId fills the path beside id, against is a query parameter, and
     * a restore's expectedContentHash travels in its body, fields beside it.
     */
    @Test
    void theRevisionActionsDispatchToTheirRoutes() {
        call("entries", Map.of("action", "revisions", "weblog", "b", "id", "e1"));
        call("entries", Map.of("action", "revision", "weblog", "b", "id", "e1", "revisionId", "r1",
                "against", "r2"));
        call("entries", Map.of("action", "restore_revision", "weblog", "b", "id", "e1", "revisionId", "r1",
                "expectedContentHash", "ab", "fields", List.of("text")));

        assertEquals(List.of("GET /v1/weblogs/b/entries/e1/revisions", "GET /v1/weblogs/b/entries/e1/revisions/r1",
                "POST /v1/weblogs/b/entries/e1/revisions/r1/restore"),
                dispatched.stream().map(c -> c.method() + " " + c.path()).toList());
        assertEquals(List.of("r2"), dispatched.get(1).query().get("against"));
        assertEquals("{\"expectedContentHash\":\"ab\"}", dispatched.get(2).jsonBody());
        assertEquals(List.of("text"), dispatched.get(2).query().get("fields"));
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
    private static final String OPAQUE_500 = "{\"type\":\"https://pressroom.invalid/problems/internal-error\","
            + "\"title\":\"Internal error\",\"status\":500,\"detail\":\"The request could not be completed.\","
            + "\"code\":\"server.internalError\"}";

    /** A refusal MCP makes itself is the same problem shape a REST 400 is: a type, its title, the instance. */
    @Test
    void aRefusalMcpMakesItselfIsARestShapedProblem() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        CallToolResult result = executor.call(request, tool("entries"), Map.of("action", "get", "weblog", "b"));

        assertTrue(result.isError());
        tools.jackson.databind.JsonNode problem = new tools.jackson.databind.ObjectMapper().readTree(text(result));
        assertEquals("https://pressroom.invalid/problems/invalid-request", problem.get("type").asString());
        assertEquals("Invalid request", problem.get("title").asString());
        assertEquals(400, problem.get("status").asInt());
        assertEquals("/api/mcp", problem.get("instance").asString());
        assertTrue(problem.get("detail").asString().contains("`id`"), text(result));
    }

    @Test
    void aMissingRequiredArgumentIsAToolErrorAndNothingIsDispatched() {
        CallToolResult result = call("entries", Map.of("action", "get", "weblog", "b"));

        assertTrue(result.isError());
        assertTrue(text(result).contains("`id` or `anchor`"), text(result));
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
        String busy = "{\"type\":\"https://pressroom.invalid/problems/busy\",\"title\":\"Busy\",\"status\":503,"
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
        assertEquals("{\"type\":\"about:blank\",\"title\":\"Method Not Allowed\",\"status\":405,\"code\":\"http.error\"}", text(result));
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

    /** Runs the action with a capture on the pressroom.mcp logger; returns the formatted lines. */
    private static List<String> logged(Runnable action) {
        return logged(action, new ApiPrincipal("alice", null, ApiToken.Role.POST, "tok-1"));
    }

    /** As above, with the given details on the authentication (a token's principal, or none). */
    private static List<String> logged(Runnable action, Object details) {
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
            auth.setDetails(details);
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
        assertEquals("pressroom.mcp", McpToolExecutor.LOG_NAME);
    }

    /**
     * Characterisation, written before the caller model replaced the
     * {@code instanceof ApiPrincipal} test and passing on the old code: the
     * call log names the token for a token caller and "-" for any other.
     */
    @Test
    void aCallLogNamesTheTokenOnlyForATokenCaller() {
        List<String> basic = logged(() -> call("entries", Map.of("action", "get", "weblog", "b", "id", "e1")),
                null);
        assertEquals(1, basic.size(), basic.toString());
        assertTrue(basic.get(0).startsWith("token=- user=alice "), basic.get(0));

        List<String> token = logged(() -> call("entries", Map.of("action", "get", "weblog", "b", "id", "e1")));
        assertTrue(token.get(0).startsWith("token=tok-1 user=alice "), token.get(0));
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
        assertEquals("{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,\"code\":\"http.error\"}", text(blank));
    }

    @Test
    void anActionNameGivenForASingleActionToolIsRejectedAsNotApplying() {
        CallToolResult result = call("whoami", Map.of("action", "get"));

        assertTrue(result.isError());
        assertTrue(text(result).contains("`action` does not apply to `whoami`"), text(result));
        assertTrue(dispatched.isEmpty());
    }
}
