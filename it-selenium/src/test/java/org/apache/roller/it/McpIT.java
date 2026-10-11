package org.apache.roller.it;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The MCP endpoint against the packaged, running WAR. MockMvc boots no
 * container, so only this class can show that a tool call forwarded inside
 * Tomcat reaches its /api/v1 route with the caller's identity, that writes
 * commit, and that the transport answers MCP correctly. Acceptance, not TDD:
 * each behaviour was driven by a failing unit test first.
 */
class McpIT extends RollerIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger IDS = new AtomicInteger();

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    // ---------------------------------------------------------- the endpoint

    @Test
    void initializeAnswersAsRollerWithNoSession() throws Exception {
        Token token = mint("McpIT initialize", "ADMIN", null);
        try {
            HttpResponse<String> response = post(token.raw(), rpcBody("initialize", Map.of(
                    "protocolVersion", "2025-06-18",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "McpIT", "version", "1"))), Map.of());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode result = JSON.readTree(response.body()).get("result");
            assertEquals("roller", result.get("serverInfo").get("name").asString(), response.body());
            assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty(), "stateless: no session id");
        } finally {
            revoke(token.id());
        }
    }

    @Test
    void anEntryRoundTripThroughMcpAloneMatchesRest() throws Exception {
        Token token = mint("McpIT round trip", "ADMIN", null);
        String id = null;
        try {
            JsonNode created = callTool(token.raw(), "entries", Map.of("action", "create",
                    "weblog", WEBLOG_HANDLE, "title", "McpIT place " + System.nanoTime(), "text", "<p>x</p>",
                    "jsonLdType", "TOURIST_ATTRACTION", "geoLatitude", 32.9228, "geoLongitude", -117.2534));
            assertFalse(isError(created), created.toString());
            id = JSON.readTree(text(created)).get("id").asString();

            JsonNode got = callTool(token.raw(), "entries", Map.of("action", "get", "weblog", WEBLOG_HANDLE, "id", id));
            assertEquals(JSON.readTree(restGet(token.raw(), "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + id).body()),
                    JSON.readTree(text(got)), "MCP get and REST GET agree");
            assertEquals(32.9228, JSON.readTree(text(got)).get("geoLatitude").asDouble());

            JsonNode updated = callTool(token.raw(), "entries", Map.of("action", "update",
                    "weblog", WEBLOG_HANDLE, "id", id, "searchDescription", "From McpIT"));
            assertEquals("From McpIT", JSON.readTree(text(updated)).get("searchDescription").asString());

            assertFalse(isError(callTool(token.raw(), "entries",
                    Map.of("action", "trash", "weblog", WEBLOG_HANDLE, "id", id))));
            assertFalse(isError(callTool(token.raw(), "entries",
                    Map.of("action", "delete_forever", "weblog", WEBLOG_HANDLE, "id", id))));
            assertEquals(404, restGet(token.raw(), "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + id).statusCode());
            id = null;
        } finally {
            if (id != null) {
                restSend(token.raw(), "DELETE", "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + id, null);
                restSend(token.raw(), "POST",
                        "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + id + "/delete-forever", null);
            }
            revoke(token.id());
        }
    }

    @Test
    void errorsThroughMcpCarryTheApisOwnStatusAndDetail() throws Exception {
        Token read = mint("McpIT read", "READ", null);
        Token pinned = mint("McpIT pinned", "POST", WEBLOG_HANDLE);
        Token unpinned = mint("McpIT unpinned", "POST", null);
        try {
            JsonNode readWrite = callTool(read.raw(), "entries", Map.of("action", "create",
                    "weblog", WEBLOG_HANDLE, "title", "t", "text", "x"));
            assertTrue(isError(readWrite));
            JsonNode problem = JSON.readTree(text(readWrite));
            assertEquals(403, problem.get("status").asInt());
            assertEquals("This token is read-only.", problem.get("detail").asString());

            JsonNode otherWeblog = callTool(pinned.raw(), "entries",
                    Map.of("action", "list", "weblog", "mcpit_other_weblog"));
            assertTrue(isError(otherWeblog));
            assertEquals(404, JSON.readTree(text(otherWeblog)).get("status").asInt());

            // a weblog-pinned token meets 404 on an administration route (it names no weblog);
            // the 403 "not scoped for administration" is for a token that is not pinned
            JsonNode adminOnly = callTool(unpinned.raw(), "users", Map.of("action", "list"));
            assertTrue(isError(adminOnly));
            assertEquals(403, JSON.readTree(text(adminOnly)).get("status").asInt());
        } finally {
            revoke(read.id());
            revoke(pinned.id());
            revoke(unpinned.id());
        }
    }

    @Test
    void withoutAValidTokenTheEndpointAnswers401AndSetsNoCookie() throws Exception {
        String init = rpcBody("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "McpIT", "version", "1")));
        HttpResponse<String> none = post(null, init, Map.of());
        assertEquals(401, none.statusCode());
        assertTrue(none.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals(401, post("rlr_not_a_real_token", init, Map.of()).statusCode());
    }

    @Test
    void basicCredentialsAreRefused() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/mcp"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", basicAuth())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(BodyPublishers.ofString(rpcBody("tools/list", Map.of())))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("MCP accepts API tokens only."), response.body());
    }

    @Test
    void aForeignOriginIsRefusedAndAnOversizedBodyIsRefused() throws Exception {
        Token token = mint("McpIT origin", "READ", null);
        try {
            HttpResponse<String> foreign = post(token.raw(), rpcBody("tools/list", Map.of()),
                    Map.of("Origin", "https://evil.example"));
            assertEquals(403, foreign.statusCode());

            String huge = rpcBody("tools/call", Map.of("name", "whoami",
                    "arguments", Map.of("pad", "x".repeat(2 * 1024 * 1024))));
            assertEquals(413, post(token.raw(), huge, Map.of()).statusCode());
        } finally {
            revoke(token.id());
        }
    }

    @Test
    void getAndDeleteAreNotAllowed() throws Exception {
        Token token = mint("McpIT methods", "READ", null);
        try {
            for (String method : new String[] {"GET", "DELETE"}) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/mcp"))
                        .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token.raw())
                        .header("Accept", "application/json, text/event-stream")
                        .method(method, BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(405, response.statusCode(), method);
            }
        } finally {
            revoke(token.id());
        }
    }

    @Test
    void aToolCallCostsOneThrottleUnitNotTwo() throws Exception {
        // 120 per 60 s per token. Counted twice, call 61 would be throttled.
        Token token = mint("McpIT throttle", "READ", null);
        try {
            for (int i = 0; i < 70; i++) {
                HttpResponse<String> response = post(token.raw(),
                        rpcBody("tools/call", Map.of("name", "whoami", "arguments", Map.of())), Map.of());
                assertEquals(200, response.statusCode(), "call " + (i + 1) + ": " + response.body());
            }
        } finally {
            revoke(token.id());
        }
    }

    @Test
    void toolsListHasTheElevenToolsAndTheInstructionsArrive() throws Exception {
        Token token = mint("McpIT list", "READ", null);
        try {
            HttpResponse<String> listed = post(token.raw(), rpcBody("tools/list", Map.of()), Map.of());
            JsonNode tools = JSON.readTree(listed.body()).get("result").get("tools");
            assertEquals(11, tools.size(), listed.body());
            HttpResponse<String> init = post(token.raw(), rpcBody("initialize", Map.of("protocolVersion",
                    "2025-06-18", "capabilities", Map.of(), "clientInfo", Map.of("name", "McpIT", "version", "1"))),
                    Map.of());
            assertTrue(JSON.readTree(init.body()).get("result").get("instructions").asString()
                    .contains("admin UI"), init.body());
        } finally {
            revoke(token.id());
        }
    }

    @Test
    void aPathValueThatWouldLeaveItsSegmentIsRefusedAndNothingChanges() throws Exception {
        Token token = mint("McpIT segment", "ADMIN", null);
        try {
            String before = restGet(token.raw(), "/api/v1/weblogs/" + WEBLOG_HANDLE).body();

            JsonNode refused = callTool(token.raw(), "weblogs", Map.of("action", "update",
                    "weblog", WEBLOG_HANDLE + "/entries/x", "name", "McpIT hijacked"));

            assertTrue(isError(refused), refused.toString());
            assertEquals(400, JSON.readTree(text(refused)).get("status").asInt());
            assertEquals(JSON.readTree(before),
                    JSON.readTree(restGet(token.raw(), "/api/v1/weblogs/" + WEBLOG_HANDLE).body()));
        } finally {
            revoke(token.id());
        }
    }

    // ---------------------------------------------------------------- helpers

    record Token(String id, String raw) { }

    Token mint(String label, String role, String weblog) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("label", label);
        body.put("role", role);
        if (weblog != null) {
            body.put("weblog", weblog);
        }
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/tokens"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", basicAuth())
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        JsonNode json = JSON.readTree(response.body());
        return new Token(json.get("info").get("id").asString(), json.get("token").asString());
    }

    void revoke(String id) throws Exception {
        client.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/tokens/" + id))
                .timeout(Duration.ofSeconds(20)).header("Authorization", basicAuth()).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    static String basicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString((ADMIN_USERNAME + ":" + ADMIN_PASSWORD).getBytes());
    }

    static String rpcBody(String method, Object params) throws Exception {
        Map<String, Object> rpc = new LinkedHashMap<>();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", IDS.incrementAndGet());
        rpc.put("method", method);
        rpc.put("params", params);
        return JSON.writeValueAsString(rpc);
    }

    HttpResponse<String> post(String bearer, String body, Map<String, String> extraHeaders) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/mcp"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        extraHeaders.forEach(builder::header);
        return client.send(builder.POST(BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode callTool(String bearer, String tool, Map<String, Object> arguments) throws Exception {
        HttpResponse<String> response = post(bearer, rpcBody("tools/call",
                Map.of("name", tool, "arguments", arguments)), Map.of());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode json = JSON.readTree(response.body());
        if (json.has("error") || !json.has("result")) {
            fail("JSON-RPC error or no result: [" + response.body() + "]");
        }
        return json.get("result");
    }

    static String text(JsonNode result) {
        return result.get("content").get(0).get("text").asString();
    }

    static boolean isError(JsonNode result) {
        return result.path("isError").asBoolean(false);
    }

    HttpResponse<String> restGet(String bearer, String path) throws Exception {
        return restSend(bearer, "GET", path, null);
    }

    HttpResponse<String> restSend(String bearer, String method, String path, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + bearer);
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        HttpRequest.BodyPublisher publisher = json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
        return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }
}
