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

import com.codeborne.selenide.Selenide;
import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Agent accounts and the token UI on the packaged WAR (spec 2026-10-13;
 * criteria 4, 7, 20, 21, 22, 23 and 24), at whichever context path the suite
 * runs. MockMvc runs no real transaction, filter chain or flash redirect;
 * this class does. Holds GLOBAL_CONFIG for READ: granting a membership needs
 * groupblogging.enabled, which GlobalConfigMatrixIT turns off.
 */
@ResourceLock(value = RollerIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class AgentAccountsIT extends RollerIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger IDS = new AtomicInteger();

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * Criterion 24: an admin creates an agent, adds it to a weblog as Author
     * and mints a Write token pinned to that weblog; the token answers whoami
     * as an agent on that weblog and creates an entry there; once revoked,
     * its next call is a 401.
     */
    @Test
    void anAdminBootstrapsAnAgentThatPublishesThroughMcpUntilItsTokenIsRevoked() throws Exception {
        String agent = "itagent" + nonce();
        loginAsAdmin();
        createAgent(agent, false);

        $("#userWeblogsAddHandle").selectOptionByValue(WEBLOG_HANDLE);
        $("#userWeblogsAddLevel").selectOptionByValue("post");
        clickAndAwaitNewPage($("#userWeblogsAddButton"));
        $("#userWeblogsTable").shouldHave(text(WEBLOG_HANDLE));

        String secret = mintOnThisPage("bootstrap", "POST", WEBLOG_HANDLE);
        String snippet = $("#mcpSnippet").getValue();
        assertTrue(snippet.contains("/api/mcp"), snippet);
        assertTrue(snippet.contains("Authorization: Bearer " + secret), snippet);

        // Criterion 10 in the browser: the reveal shows the secret once.
        Selenide.refresh();
        BrowserHealth.current().settle();
        $("#tokenAlreadyShown").should(exist);
        assertTrue($$("#revealedToken").isEmpty(), "a second visit must not show the secret");

        String entryId = null;
        try {
            JsonNode who = JSON.readTree(toolText(callTool(secret, "whoami", Map.of())));
            assertEquals("AGENT", who.get("kind").asString(), who.toString());
            assertEquals(agent, who.get("userName").asString(), who.toString());
            assertEquals(1, who.get("weblogs").size(), who.toString());
            assertEquals(WEBLOG_HANDLE, who.get("weblogs").get(0).get("handle").asString());

            JsonNode created = callTool(secret, "entries", Map.of("action", "create", "weblog", WEBLOG_HANDLE,
                    "title", "AgentAccountsIT " + agent, "text", "<p>Written by an agent.</p>"));
            assertFalse(isError(created), created.toString());
            entryId = JSON.readTree(toolText(created)).get("id").asString();
        } finally {
            if (entryId != null) {
                adminRest("DELETE", "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + entryId);
                adminRest("POST", "/api/v1/weblogs/" + WEBLOG_HANDLE + "/entries/" + entryId + "/delete-forever");
            }
        }

        openAgentPage(agent);
        revokeTheTokenOnThisPage();
        assertEquals(401, mcpPost(secret, rpcBody("tools/call",
                Map.of("name", "whoami", "arguments", Map.of()))).statusCode());

        // Leave the shared weblog as it was.
        awaitNewPageAfter(() -> {
            $("#userWeblogsTable button[data-confirm]").click();
            Selenide.confirm();
        });
        $("#userWeblogsTable").shouldNot(exist);
        logout();
    }

    /** Criterion 4: HTTP Basic as an agent is a 401 on /api/v1/me. */
    @Test
    void httpBasicAsAnAgentIs401() throws Exception {
        String agent = "itbasic" + nonce();
        loginAsAdmin();
        createAgent(agent, false);
        logout();

        HttpResponse<String> me = send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/me"))
                .header("Authorization", basic(agent, "any-password-at-all")).GET());

        assertEquals(401, me.statusCode(), me.body());
    }

    /**
     * Criterion 7 and review focus 2: disabling an agent stops its token on
     * the next request, over REST and over MCP; enabling it makes the same
     * token work again -- on the running app, with whatever it caches.
     */
    @Test
    void disablingAnAgentStopsItsTokenAndEnablingRestoresIt() throws Exception {
        String agent = "itdisable" + nonce();
        loginAsAdmin();
        createAgent(agent, false);
        String secret = mintOnThisPage("disable-check", "READ", null);
        assertEquals(200, bearerGet(secret, "/api/v1/me").statusCode());

        openAgentPage(agent);
        awaitNewPageAfter(() -> {
            $("#agentDisableButton").click();
            Selenide.confirm();
        });
        $("#agentEnableButton").should(exist);
        assertEquals(401, bearerGet(secret, "/api/v1/me").statusCode(), "REST");
        assertEquals(401, mcpPost(secret, rpcBody("tools/call",
                Map.of("name", "whoami", "arguments", Map.of()))).statusCode(), "MCP");

        awaitNewPageAfter(() -> {
            $("#agentEnableButton").click();
            Selenide.confirm();
        });
        $("#agentDisableButton").should(exist);
        assertEquals(200, bearerGet(secret, "/api/v1/me").statusCode(), "the same token works again");

        revokeTheTokenOnThisPage();
        logout();
    }

    /** Criterion 23: a site-admin agent's ADMIN token reaches the admin API only while the switch is on. */
    @Test
    void aSiteAdminAgentReachesTheAdminApiOnlyWhileTheSwitchIsOn() throws Exception {
        String agent = "itsiteadmin" + nonce();
        loginAsAdmin();
        createAgent(agent, true);
        String secret = mintOnThisPage("site-admin", "ADMIN", null);
        assertEquals(200, bearerGet(secret, "/api/v1/admin/config").statusCode());

        openAgentPage(agent);
        $("#bean_siteAdmin").shouldBe(checked).click();
        clickAndAwaitNewPage($("#agentSaveButton"));
        $("#bean_siteAdmin").shouldNotBe(checked);
        assertEquals(403, bearerGet(secret, "/api/v1/admin/config").statusCode());

        revokeTheTokenOnThisPage();
        logout();
    }

    /** Criteria 21 and 22: the API reports an agent's kind and refuses to change it, over REST and MCP. */
    @Test
    void theApiReportsAnAgentButCannotChangeIt() throws Exception {
        String agent = "itpatch" + nonce();
        loginAsAdmin();
        createAgent(agent, false);
        logout();
        Token admin = mintAsAdmin("AgentAccountsIT admin");
        try {
            HttpResponse<String> patch = send(HttpRequest.newBuilder(
                            URI.create(baseUrl() + "/api/v1/admin/users/" + agent))
                    .header("Authorization", "Bearer " + admin.raw())
                    .header("Content-Type", "application/json")
                    .method("PATCH", BodyPublishers.ofString("{\"enabled\":false}")));
            assertEquals(403, patch.statusCode(), patch.body());
            assertEquals("Agent accounts are managed in the admin UI.",
                    JSON.readTree(patch.body()).get("detail").asString());

            JsonNode viaMcp = callTool(admin.raw(), "users",
                    Map.of("action", "update", "userName", agent, "enabled", false));
            assertTrue(isError(viaMcp), viaMcp.toString());
            JsonNode problem = JSON.readTree(toolText(viaMcp));
            assertEquals(403, problem.get("status").asInt());
            assertEquals("Agent accounts are managed in the admin UI.", problem.get("detail").asString());

            JsonNode users = JSON.readTree(bearerGet(admin.raw(), "/api/v1/admin/users").body());
            String kind = null;
            for (JsonNode user : users) {
                if (agent.equals(user.get("userName").asString())) {
                    kind = user.get("kind").asString();
                }
            }
            assertEquals("AGENT", kind, "the newest account is on the first page, with its kind");
        } finally {
            revokeAsAdmin(admin.id());
        }
    }

    /** Criterion 20: a person's form sign-in shows on Admin → Users as their last active time. */
    @Test
    void aPersonsSignInShowsAsLastActiveOnTheUsersList() {
        String person = "itlast" + nonce();
        String password = "it-last-password";
        loginAsAdmin();
        openPath("/roller-ui/admin/createUser.rol");
        $("input[name='bean.userName']").should(visible).setValue(person);
        $("input[name='bean.screenName']").setValue("Screen " + person);
        $("input[name='bean.fullName']").setValue("Full " + person);
        if ($("#bean_passwordModeNow").exists()) {
            $("#bean_passwordModeNow").click();
        }
        $("input[name='bean.password']").setValue(password);
        $("input[name='bean.emailAddress']").setValue(person + "@example.com");
        clickAndAwaitNewPage($("#save_button"));

        openPath("/roller-ui/admin/userAdmin.rol?q=" + person);
        $("a[data-username='" + person + "']").closest("tr").find("time").shouldNot(exist);
        logout();

        loginAs(person, password);
        logout();

        loginAsAdmin();
        openPath("/roller-ui/admin/userAdmin.rol?q=" + person);
        $("a[data-username='" + person + "']").closest("tr").find("time").should(exist);
        logout();
    }

    // ---------------------------------------------------------------- helpers

    private void createAgent(String userName, boolean siteAdmin) {
        openPath("/roller-ui/admin/createAgent.rol");
        $("#bean_userName").should(visible).setValue(userName);
        $("#bean_name").setValue("IT Agent " + userName);
        if (siteAdmin) {
            $("#bean_siteAdmin").click();
        }
        clickAndAwaitNewPage($("#agentCreateButton"));
        $("#agentPage").should(exist);
    }

    private void openAgentPage(String userName) {
        openPath("/roller-ui/admin/agent.rol?userName=" + userName);
        $("#agentPage").should(exist);
    }

    /** Fills the token form on the page in view and returns the secret the reveal shows. */
    private String mintOnThisPage(String label, String role, String pin) {
        $("#tokenLabel").setValue(label);
        $("#tokenRole").selectOptionByValue(role);
        if (pin != null) {
            $("#tokenPin").selectOptionByValue(pin);
        }
        clickAndAwaitNewPage($("#tokenCreateButton"));
        return $("#revealedToken").should(visible).getValue();
    }

    private void revokeTheTokenOnThisPage() {
        awaitNewPageAfter(() -> {
            $("#tokenListTable button[data-confirm]").click();
            Selenide.confirm();
        });
    }

    record Token(String id, String raw) { }

    private Token mintAsAdmin(String label) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("label", label);
        body.put("role", "ADMIN");
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/tokens"))
                .header("Authorization", basic(ADMIN_USERNAME, ADMIN_PASSWORD))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(body))));
        assertEquals(201, response.statusCode(), response.body());
        JsonNode json = JSON.readTree(response.body());
        return new Token(json.get("info").get("id").asString(), json.get("token").asString());
    }

    private void revokeAsAdmin(String id) throws Exception {
        adminRest("DELETE", "/api/v1/tokens/" + id);
    }

    private HttpResponse<String> adminRest(String method, String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", basic(ADMIN_USERNAME, ADMIN_PASSWORD))
                .method(method, BodyPublishers.noBody()));
    }

    private HttpResponse<String> bearerGet(String token, String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + token).GET());
    }

    private HttpResponse<String> mcpPost(String bearer, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + bearer)
                .POST(BodyPublishers.ofString(body)));
    }

    private JsonNode callTool(String bearer, String tool, Map<String, Object> arguments) throws Exception {
        HttpResponse<String> response = mcpPost(bearer, rpcBody("tools/call",
                Map.of("name", tool, "arguments", arguments)));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode json = JSON.readTree(response.body());
        if (json.has("error") || !json.has("result")) {
            fail("JSON-RPC error or no result: [" + response.body() + "]");
        }
        return json.get("result");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String rpcBody(String method, Object params) throws Exception {
        Map<String, Object> rpc = new LinkedHashMap<>();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", IDS.incrementAndGet());
        rpc.put("method", method);
        rpc.put("params", params);
        return JSON.writeValueAsString(rpc);
    }

    private static String toolText(JsonNode result) {
        return result.get("content").get(0).get("text").asString();
    }

    private static boolean isError(JsonNode result) {
        return result.path("isError").asBoolean(false);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(UTF_8));
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
