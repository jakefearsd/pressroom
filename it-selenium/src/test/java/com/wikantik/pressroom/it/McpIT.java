package com.wikantik.pressroom.it;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.wikantik.pressroom.it.support.AdminToken;
import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The MCP endpoint against the packaged, running WAR. MockMvc boots no
 * container, so only this class can show that a tool call forwarded inside
 * Tomcat reaches its /api/v1 route with the caller's identity, that writes
 * commit, and that the transport answers MCP correctly. Acceptance, not TDD:
 * each behaviour was driven by a failing unit test first.
 */
class McpIT extends PressroomIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger IDS = new AtomicInteger();

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    // ---------------------------------------------------------- the endpoint

    @Test
    void initializeAnswersAsPressroomWithNoSession() throws Exception {
        AdminToken token = AdminToken.mint("McpIT initialize", "ADMIN", null);
        try {
            HttpResponse<String> response = post(token.raw(), rpcBody("initialize", Map.of(
                    "protocolVersion", "2025-06-18",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "McpIT", "version", "1"))), Map.of());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode result = JSON.readTree(response.body()).get("result");
            assertEquals("pressroom", result.get("serverInfo").get("name").asString(), response.body());
            assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty(), "stateless: no session id");
        } finally {
            AdminToken.revoke(token.id());
        }
    }

    @Test
    void anEntryRoundTripThroughMcpAloneMatchesRest() throws Exception {
        AdminToken token = AdminToken.mint("McpIT round trip", "ADMIN", null);
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
            AdminToken.revoke(token.id());
        }
    }

    @Test
    void errorsThroughMcpCarryTheApisOwnStatusAndDetail() throws Exception {
        AdminToken read = AdminToken.mint("McpIT read", "READ", null);
        AdminToken pinned = AdminToken.mint("McpIT pinned", "POST", WEBLOG_HANDLE);
        AdminToken unpinned = AdminToken.mint("McpIT unpinned", "POST", null);
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
            AdminToken.revoke(read.id());
            AdminToken.revoke(pinned.id());
            AdminToken.revoke(unpinned.id());
        }
    }

    @Test
    void withoutAValidTokenTheEndpointAnswers401AndSetsNoCookie() throws Exception {
        String init = rpcBody("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "McpIT", "version", "1")));
        HttpResponse<String> none = post(null, init, Map.of());
        assertEquals(401, none.statusCode());
        assertTrue(none.headers().firstValue("Set-Cookie").isEmpty());
        HttpResponse<String> unknown = post("prs_not_a_real_token", init, Map.of());
        assertEquals(401, unknown.statusCode());
        HttpResponse<String> old = post("rlr_not_a_real_token", init, Map.of()); // rebrand:keep
        assertEquals(401, old.statusCode());
        assertEquals(unknown.body(), old.body());
    }

    /**
     * A password is refused by the security chain before the endpoint runs:
     * since 0.16.0 HTTP Basic authenticates only /api/v1/tokens, and the 401
     * says so.
     */
    @Test
    void basicCredentialsAreRefused() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/mcp"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", AdminToken.adminBasic())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(BodyPublishers.ofString(rpcBody("tools/list", Map.of())))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("Password authentication is accepted only on /api/v1/tokens."),
                response.body());
    }

    @Test
    void aForeignOriginIsRefusedAndAnOversizedBodyIsRefused() throws Exception {
        AdminToken token = AdminToken.mint("McpIT origin", "READ", null);
        try {
            HttpResponse<String> foreign = post(token.raw(), rpcBody("tools/list", Map.of()),
                    Map.of("Origin", "https://evil.example"));
            assertEquals(403, foreign.statusCode());

            // Sent with no Content-Length (chunked), a little over the cap: the transport reads
            // up to the cap, refuses with 413, and the few KiB left unread are swallowed by Tomcat,
            // so the 413 always arrives. (A known oversize Content-Length is refused unread, and
            // Tomcat closes the connection over more than its 2 MiB maxSwallowSize: a client may see
            // a reset.) The cap is what the app itself reports in GET /api/v1/meta.
            int mcpCapBytes = limit(token, "maxBodyBytes");
            byte[] huge = rpcBody("tools/call", Map.of("name", "whoami",
                    "arguments", Map.of("pad", "x".repeat(mcpCapBytes + 1024)))).getBytes(UTF_8);
            assertEquals(413, post(token.raw(),
                    BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(huge)), Map.of()).statusCode());
        } finally {
            AdminToken.revoke(token.id());
        }
    }

    @Test
    void getAndDeleteAreNotAllowed() throws Exception {
        AdminToken token = AdminToken.mint("McpIT methods", "READ", null);
        try {
            for (String method : new String[] {"GET", "DELETE"}) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/mcp"))
                        .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token.raw())
                        .header("Accept", "application/json, text/event-stream")
                        .method(method, BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(405, response.statusCode(), method);
            }
        } finally {
            AdminToken.revoke(token.id());
        }
    }

    @Test
    void aToolCallCostsOneThrottleUnitNotTwo() throws Exception {
        // 120 per 60 s per token. Counted twice, call 61 would be throttled.
        AdminToken token = AdminToken.mint("McpIT throttle", "READ", null);
        try {
            for (int i = 0; i < 70; i++) {
                HttpResponse<String> response = post(token.raw(),
                        rpcBody("tools/call", Map.of("name", "whoami", "arguments", Map.of())), Map.of());
                assertEquals(200, response.statusCode(), "call " + (i + 1) + ": " + response.body());
            }
        } finally {
            AdminToken.revoke(token.id());
        }
    }

    @Test
    void toolsListHasTheFifteenToolsAndTheInstructionsArrive() throws Exception {
        AdminToken token = AdminToken.mint("McpIT list", "READ", null);
        try {
            HttpResponse<String> listed = post(token.raw(), rpcBody("tools/list", Map.of()), Map.of());
            JsonNode tools = JSON.readTree(listed.body()).get("result").get("tools");
            assertEquals(15, tools.size(), listed.body()); // site_info made 14, businesses 15 (0.16.0)
            HttpResponse<String> init = post(token.raw(), rpcBody("initialize", Map.of("protocolVersion",
                    "2025-06-18", "capabilities", Map.of(), "clientInfo", Map.of("name", "McpIT", "version", "1"))),
                    Map.of());
            assertTrue(JSON.readTree(init.body()).get("result").get("instructions").asString()
                    .contains("admin UI"), init.body());
        } finally {
            AdminToken.revoke(token.id());
        }
    }

    @Test
    void theEntriesPubTimeDescriptionIsTheOpenApiDocumentsText() throws Exception {
        AdminToken token = AdminToken.mint("McpIT openapi", "READ", null);
        try {
            JsonNode openapi = JSON.readTree(restGet(token.raw(), "/api/v1/openapi.json").body());
            String apiText = openapi.at("/components/schemas/EntryWrite/properties/pubTime/description").asString();
            assertFalse(apiText.isBlank(), "openapi.json carries EntryWrite.pubTime's description");

            JsonNode tools = JSON.readTree(post(token.raw(), rpcBody("tools/list", Map.of()), Map.of()).body())
                    .get("result").get("tools");
            JsonNode entries = null;
            for (JsonNode tool : tools) {
                if ("entries".equals(tool.get("name").asString())) {
                    entries = tool;
                }
            }
            assertNotNull(entries, "tools/list names the entries tool");
            assertEquals(apiText, entries.at("/inputSchema/properties/pubTime/description").asString());
        } finally {
            AdminToken.revoke(token.id());
        }
    }

    @Test
    void aPathValueThatWouldLeaveItsSegmentIsRefusedAndNothingChanges() throws Exception {
        AdminToken token = AdminToken.mint("McpIT segment", "ADMIN", null);
        try {
            String before = restGet(token.raw(), "/api/v1/weblogs/" + WEBLOG_HANDLE).body();

            JsonNode refused = callTool(token.raw(), "weblogs", Map.of("action", "update",
                    "weblog", WEBLOG_HANDLE + "/entries/x", "name", "McpIT hijacked"));

            assertTrue(isError(refused), refused.toString());
            assertEquals(400, JSON.readTree(text(refused)).get("status").asInt());
            assertEquals(JSON.readTree(before),
                    JSON.readTree(restGet(token.raw(), "/api/v1/weblogs/" + WEBLOG_HANDLE).body()));
        } finally {
            AdminToken.revoke(token.id());
        }
    }

    @Test
    void adminAccountsCannotBeChangedThroughMcpOrRest() throws Exception {
        AdminToken token = AdminToken.mint("McpIT admin rule", "ADMIN", null);
        try {
            JsonNode viaMcp = callTool(token.raw(), "users",
                    Map.of("action", "update", "userName", ADMIN_USERNAME, "enabled", false));
            assertTrue(isError(viaMcp));
            JsonNode problem = JSON.readTree(text(viaMcp));
            assertEquals(403, problem.get("status").asInt());
            assertEquals("Admin accounts are managed in the admin UI.", problem.get("detail").asString());

            HttpResponse<String> viaRest = restSend(token.raw(), "PATCH",
                    "/api/v1/admin/users/" + ADMIN_USERNAME, "{\"emailAddress\":\"x@evil.example\"}");
            assertEquals(403, viaRest.statusCode(), viaRest.body());

            JsonNode who = callTool(token.raw(), "whoami", Map.of());
            assertFalse(isError(who), "the admin account is still enabled: " + who);
        } finally {
            AdminToken.revoke(token.id());
        }
    }

    // ------------------------------------------------- step 2: a new weblog's needs

    @Test
    @ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
    void aNewWeblogIsCreatedConfiguredAndPublishedThroughMcpAlone() throws Exception {
        AdminToken token = AdminToken.mint("McpIT new weblog", "ADMIN", null);
        String handle = "mcpit" + (System.nanoTime() % 1_000_000_000L);
        try {
            JsonNode created = callTool(token.raw(), "weblogs", Map.of("action", "create", "handle", handle,
                    "name", "McpIT weblog", "theme", "journal", "timeZone", "Europe/Berlin"));
            assertFalse(isError(created), created.toString());

            JsonNode placed = callTool(token.raw(), "weblogs", Map.of("action", "update", "weblog", handle,
                    "placeType", "LodgingBusiness", "placeLocality", "Berlin", "placeLat", 52.52, "placeLng", 13.40));
            assertEquals("Berlin", JSON.readTree(text(placed)).get("placeLocality").asString(), text(placed));

            Map<String, Object> eventArgs = new LinkedHashMap<>(Map.of("action", "create", "weblog", handle,
                    "title", "Konzert", "text", "<p>x</p>", "status", "PUBLISHED", "jsonLdType", "EVENT",
                    "eventStart", "2026-08-02T19:30", "eventEnd", "2026-08-02T22:00"));
            eventArgs.put("eventLocation", "Waldb\u00fchne");
            eventArgs.put("geoLatitude", 52.5146);
            eventArgs.put("geoLongitude", 13.2283);
            JsonNode event = callTool(token.raw(), "entries", eventArgs);
            assertFalse(isError(event), event.toString());
            JsonNode entry = JSON.readTree(text(event));
            assertEquals("2026-08-02T17:30:00Z", entry.get("eventStart").asString());
            String id = entry.get("id").asString();

            JsonNode cleared = callTool(token.raw(), "entries", Map.of("action", "update", "weblog", handle, "id", id,
                    "clear", List.of("geoLatitude", "geoLongitude")));
            JsonNode after = JSON.readTree(text(cleared));
            assertTrue(after.path("geoLatitude").isNull() || after.path("geoLatitude").isMissingNode(), after.toString());

            String permalinkHtml = client.send(HttpRequest.newBuilder(URI.create(entry.get("permalink").asString()))
                    .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            // the Event block's startDate is the same instant as 19:30 Berlin
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"startDate\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(permalinkHtml);
            assertTrue(m.find(), "an Event block with a startDate");
            assertEquals(java.time.Instant.parse("2026-08-02T17:30:00Z"),
                    java.time.OffsetDateTime.parse(m.group(1)).toInstant());

            JsonNode me = JSON.readTree(text(callTool(token.raw(), "whoami", Map.of())));
            boolean listed = false;
            for (JsonNode w : me.get("weblogs")) {
                listed |= handle.equals(w.get("handle").asString()) && "ADMIN".equals(w.get("permission").asString());
            }
            assertTrue(listed, me.toString());
        } finally {
            removeWeblog(handle);
            AdminToken.revoke(token.id());
        }
    }

    /**
     * {@code uploads.enabled} is off in the seed IT fixture, and the upload-disabled answer comes before the
     * address check, so this turns it on (and back) under the exclusive GLOBAL_CONFIG lock, as ApiIT does.
     */
    @Test
    @ResourceLock(PressroomIT.GLOBAL_CONFIG)
    void importingFromAnInternalAddressIsRefused() throws Exception {
        AdminToken token = AdminToken.mint("McpIT import refusals", "POST", WEBLOG_HANDLE);
        loginAsAdmin();
        boolean uploadsWere = false;
        try {
            uploadsWere = setGlobalFlag("uploads.enabled", true);
            for (String url : List.of("http://127.0.0.1/", "http://localhost/", "http://169.254.169.254/")) {
                JsonNode refused = callTool(token.raw(), "media",
                        Map.of("action", "import", "weblog", WEBLOG_HANDLE, "url", url));
                assertTrue(isError(refused), url);
                JsonNode problem = JSON.readTree(text(refused));
                assertEquals(400, problem.get("status").asInt(), url);
                assertEquals("That address is not on the public internet.", problem.get("detail").asString(), url);
            }
        } finally {
            setGlobalFlag("uploads.enabled", uploadsWere);
            logout();
            AdminToken.revoke(token.id());
        }
    }

    @Test
    @ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
    void creatingAWeblogWithAPinnedOrReadTokenIsRefusedAndCreatesNothing() throws Exception {
        AdminToken pinned = AdminToken.mint("McpIT create pinned", "POST", WEBLOG_HANDLE);
        AdminToken read = AdminToken.mint("McpIT create read", "READ", null);
        AdminToken admin = AdminToken.mint("McpIT create check", "ADMIN", null);
        String handle = "mcpit" + (System.nanoTime() % 1_000_000_000L);
        try {
            Map<String, Object> args = Map.of("action", "create", "handle", handle,
                    "name", "McpIT refused", "theme", "journal");

            JsonNode viaPinned = callTool(pinned.raw(), "weblogs", args);
            assertTrue(isError(viaPinned), viaPinned.toString());
            assertEquals(404, JSON.readTree(text(viaPinned)).get("status").asInt(), text(viaPinned));

            JsonNode viaRead = callTool(read.raw(), "weblogs", args);
            assertTrue(isError(viaRead), viaRead.toString());
            JsonNode problem = JSON.readTree(text(viaRead));
            assertEquals(403, problem.get("status").asInt(), text(viaRead));
            assertEquals("This token is read-only.", problem.get("detail").asString());

            assertEquals(404, restGet(admin.raw(), "/api/v1/weblogs/" + handle).statusCode(),
                    "neither refused call created the weblog");
        } finally {
            AdminToken.revoke(pinned.id());
            AdminToken.revoke(read.id());
            AdminToken.revoke(admin.id());
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Removes a weblog through the admin UI, as {@code VirtualHostIT} does. */
    private void removeWeblog(String handle) {
        loginAsAdmin();
        openPath("/pressroom-ui/authoring/weblogRemove.rol?weblog=" + handle);
        $("form[action$='/pressroom-ui/authoring/weblogRemove!remove.rol'] button[type='submit']")
                .should(visible).click();
        $("#messages").should(exist);
        BrowserHealth.current().settle();
        logout();
    }


    /** Criterion 21: field errors arrive through MCP unchanged, at whichever context path the suite runs. */
    @Test
    void aRefusedWriteThroughMcpListsEveryBadField() throws Exception {
        AdminToken token = AdminToken.mint("McpIT field errors", "POST", null);
        try {
            JsonNode refused = callTool(token.raw(), "entries", Map.of("action", "create",
                    "weblog", WEBLOG_HANDLE, "title", "t", "text", "x",
                    "category", "No Such Category", "geoLatitude", 91));
            assertTrue(isError(refused));
            JsonNode problem = JSON.readTree(text(refused));
            assertEquals(400, problem.get("status").asInt());
            List<String> fields = new ArrayList<>();
            for (JsonNode error : problem.get("errors")) {
                fields.add(error.get("field").asString());
            }
            assertEquals(Set.of("category", "geoLatitude"), Set.copyOf(fields));
            assertTrue(problem.get("detail").asString().contains("it is one of:"));
        } finally {
            AdminToken.revoke(token.id());
        }
    }

    /**
     * An entry of exactly the maximum text length is stored and read back intact through MCP;
     * one character more is refused as field.tooLong (400) on the text field, and creates nothing.
     */
    @Test
    void anEntryAtTheMaximumLengthRoundTripsAndOneMoreIsRefused() throws Exception {
        AdminToken token = AdminToken.mint("McpIT max length", "POST", null);
        String id = null;
        try {
            int max = limit(token, "entryTextMaxChars");
            String text = "a".repeat(max);
            JsonNode created = callTool(token.raw(), "entries", Map.of("action", "create",
                    "weblog", WEBLOG_HANDLE, "title", "McpIT max length " + System.nanoTime(), "text", text));
            assertFalse(isError(created), created.toString().substring(0, Math.min(400, created.toString().length())));
            id = JSON.readTree(text(created)).get("id").asString();

            JsonNode got = callTool(token.raw(), "entries",
                    Map.of("action", "get", "weblog", WEBLOG_HANDLE, "id", id));
            assertFalse(isError(got));
            assertEquals(text, JSON.readTree(text(got)).get("text").asString(), "the stored text is intact");

            JsonNode over = callTool(token.raw(), "entries", Map.of("action", "create",
                    "weblog", WEBLOG_HANDLE, "title", "McpIT over length", "text", text + "a"));
            assertTrue(isError(over));
            JsonNode problem = JSON.readTree(text(over));
            assertEquals(400, problem.get("status").asInt());
            assertEquals("field.tooLong", problem.get("code").asString());
            assertEquals("text", problem.get("errors").get(0).get("field").asString());
        } finally {
            if (id != null) {
                restSend(token.raw(), "DELETE", "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + id, null);
                restSend(token.raw(), "POST",
                        "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + id + "/delete-forever", null);
            }
            AdminToken.revoke(token.id());
        }
    }

    /** Criterion 21: a chosen anchor is the permalink's last segment, and a second claim is a 409. */
    @Test
    void anAnchorChosenThroughMcpIsThePermalinkAndCannotBeClaimedTwice() throws Exception {
        AdminToken token = AdminToken.mint("McpIT anchor", "POST", null);
        String anchor = "mcpit-anchor-" + System.nanoTime();
        String id = null;
        try {
            JsonNode created = callTool(token.raw(), "entries", Map.of("action", "create",
                    "weblog", WEBLOG_HANDLE, "title", "Any title at all", "text", "x", "anchor", anchor));
            assertFalse(isError(created), created.toString());
            JsonNode view = JSON.readTree(text(created));
            id = view.get("id").asString();
            assertTrue(view.get("permalink").asString().endsWith("/" + anchor), view.toString());

            JsonNode again = callTool(token.raw(), "entries", Map.of("action", "create",
                    "weblog", WEBLOG_HANDLE, "title", "Another", "text", "x", "anchor", anchor));
            assertTrue(isError(again));
            JsonNode problem = JSON.readTree(text(again));
            assertEquals(409, problem.get("status").asInt());
            assertEquals("anchor", problem.get("errors").get(0).get("field").asString());
        } finally {
            if (id != null) {
                restSend(token.raw(), "DELETE", "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + id, null);
                restSend(token.raw(), "POST",
                        "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + id + "/delete-forever", null);
            }
            AdminToken.revoke(token.id());
        }
    }


    /** One of the API's size limits, as the running app reports it in {@code GET /api/v1/meta}: the app is the one source. */
    int limit(AdminToken token, String name) throws Exception {
        HttpResponse<String> meta = restGet(token.raw(), "/api/v1/meta");
        assertEquals(200, meta.statusCode(), meta.body());
        return JSON.readTree(meta.body()).get("limits").get(name).asInt();
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
        return post(bearer, BodyPublishers.ofString(body), extraHeaders);
    }

    HttpResponse<String> post(String bearer, HttpRequest.BodyPublisher body, Map<String, String> extraHeaders)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/mcp"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        extraHeaders.forEach(builder::header);
        return client.send(builder.POST(body).build(), HttpResponse.BodyHandlers.ofString());
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
