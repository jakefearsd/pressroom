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

    // ------------------------------------------------------------- the spike

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
    void aReadAWriteAndADeleteReachTheApiThroughMcp() throws Exception {
        Token token = mint("McpIT spike", "ADMIN", null);
        String entryId = null;
        try {
            JsonNode who = callTool(token.raw(), "whoami", Map.of());
            assertFalse(isError(who), who.toString());
            assertEquals(ADMIN_USERNAME, JSON.readTree(text(who)).get("userName").asString());

            JsonNode created = callTool(token.raw(), "spike_create_draft",
                    Map.of("weblog", WEBLOG_HANDLE, "title", "McpIT spike " + System.nanoTime()));
            assertFalse(isError(created), created.toString());
            entryId = JSON.readTree(text(created)).get("id").asString();

            HttpResponse<String> overRest = restGet(token.raw(),
                    "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + entryId);
            assertEquals(200, overRest.statusCode(), "the write must have committed: " + overRest.body());

            JsonNode trashed = callTool(token.raw(), "spike_trash", Map.of("weblog", WEBLOG_HANDLE, "id", entryId));
            assertFalse(isError(trashed), trashed.toString());
            assertEquals("TRASHED", JSON.readTree(restGet(token.raw(),
                    "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + entryId).body()).get("status").asString());
        } finally {
            if (entryId != null) {
                restSend(token.raw(), "POST",
                        "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + entryId + "/delete-forever", null);
            }
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
