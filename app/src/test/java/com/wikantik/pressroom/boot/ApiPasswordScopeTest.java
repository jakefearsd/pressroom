/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wikantik.pressroom.boot;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.restapi.auth.Caller;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * HTTP Basic authenticates only the token resource, {@code /api/v1/tokens}
 * and {@code /api/v1/tokens/{id}} (spec 2026-11-20, Task 2). Everywhere else
 * under {@code /api/**} a Basic header is a 401 problem+json saying so,
 * whether or not the password is right.
 *
 * <p>Built the way {@link SecurityConfigTest} is (a hand-assembled context
 * importing the production {@link SecurityConfig}, through the real
 * {@code FilterChainProxy} and its firewall), with stub handlers at the real
 * API paths. Each stub answers its own name and the caller kind it saw, so a
 * 200 says which handler a request reached and by what credential.
 * Passwords are checked for real: the DAO provider reads users from a mocked
 * business tier.
 */
class ApiPasswordScopeTest {

    private static final String PASSWORD_REFUSED =
            "Password authentication is accepted only on /api/v1/tokens. "
                    + "Mint a token there and send it as a bearer token.";
    /** The detail a password the token resource's Basic filter turned away gets. */
    private static final String PASSWORD_REJECTED = "The username or password was not accepted.";
    private static final String CHALLENGE = "Basic realm=\"Pressroom\"";
    private static final String RAW_TOKEN = "prs_apipasswordscopetest";

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mockMvc;
    private static final ObjectMapper JSON = new ObjectMapper();

    private MockWeblogger tier;

    /** Stand-ins for the real handlers, at the paths the container serves them on. */
    @RestController
    static class ApiStubs {

        private static String answer(String handler) {
            return handler + " " + Caller.current().kind();
        }

        @GetMapping("/api/v1/tokens")
        String listTokens() {
            return answer("TokensApi.list");
        }

        @PostMapping("/api/v1/tokens")
        String issueToken() {
            return answer("TokensApi.issue");
        }

        @DeleteMapping("/api/v1/tokens/{id}")
        String revokeToken(@PathVariable("id") String id) {
            return answer("TokensApi.revoke");
        }

        @GetMapping("/api/v1/ping")
        String ping() {
            return answer("MetaApi.ping");
        }

        @GetMapping("/api/v1/me")
        String me() {
            return answer("MetaApi.me");
        }

        @GetMapping("/api/v1/openapi.json")
        String openApi() {
            return answer("springdoc");
        }

        @GetMapping("/api/v1/weblogs/{handle}/entries")
        String listEntries(@PathVariable("handle") String handle) {
            return answer("EntriesApi.list");
        }

        @PostMapping("/api/v1/weblogs/{handle}/entries")
        String createEntry(@PathVariable("handle") String handle) {
            return answer("EntriesWriteApi.create");
        }

        @GetMapping("/api/v1/admin/users")
        String adminUsers() {
            return answer("AdminApi.users");
        }

        @PostMapping("/api/mcp")
        String mcp() {
            return answer("McpEndpointServlet");
        }

        @GetMapping("/pressroom-ui/menu.rol")
        String menu() {
            return answer("MenuController");
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, ApiStubs.class})
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        WebloggerProvider webloggerProvider() {
            return mock(WebloggerProvider.class);
        }
    }

    @BeforeAll
    static void setUpContext() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        mockMvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterAll
    static void tearDownContext() {
        if (context != null) {
            context.close();
        }
    }

    /** alice (an editor with a password), an agent, and a token belonging to alice. */
    @BeforeEach
    void users() throws Exception {
        tier = MockWeblogger.create();
        User alice = new User();
        alice.setUserName("alice");
        alice.setPassword(TestUtils.TEST_PASSWORD_HASH);
        alice.setEnabled(Boolean.TRUE);
        when(tier.userManager().getUserByUserName("alice")).thenReturn(alice);
        when(tier.userManager().getRoles(alice)).thenReturn(List.of("editor"));

        User agent = new User();
        agent.setUserName("robot");
        agent.setPassword(TestUtils.TEST_PASSWORD_HASH);
        agent.setEnabled(Boolean.TRUE);
        agent.setKind(User.Kind.AGENT);
        when(tier.userManager().getUserByUserName("robot")).thenReturn(agent);

        // The lookup is enabled-only, so a disabled account is no account to it.
        when(tier.userManager().getUserByUserName("disabled")).thenReturn(null);

        ApiToken token = new ApiToken();
        token.setUser(alice);
        token.setScopeRole(ApiToken.Role.ADMIN);
        when(tier.apiTokenManager().authenticate(RAW_TOKEN)).thenReturn(token);

        WebloggerProvider provider = context.getBean(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(tier.weblogger());
    }

    @AfterEach
    void forgetUsers() {
        reset(context.getBean(WebloggerProvider.class));
    }

    // ---------------------------------------------------- the token resource

    /** Criterion 1: the token resource works with a password, as before, and the caller is PASSWORD. */
    @Test
    void aPasswordStillWorksOnTheTokenResource() throws Exception {
        assertReached(perform(withAlice(get("/api/v1/tokens"))), "TokensApi.list PASSWORD");
        assertReached(perform(withAlice(post("/api/v1/tokens"))), "TokensApi.issue PASSWORD");
        assertReached(perform(withAlice(delete("/api/v1/tokens/abc123"))), "TokensApi.revoke PASSWORD");
    }

    /**
     * The chain lets a token through to the token resource's handler, as it
     * did before passwords were confined there. What the handler then answers
     * (it refuses every token caller, 403) is pinned by {@code TokensApiTest},
     * not here: this harness stops at the handler.
     */
    @Test
    void theChainLetsATokenThroughToTheTokenResource() throws Exception {
        assertReached(perform(bearer(get("/api/v1/tokens"))), "TokensApi.list TOKEN");
        assertReached(perform(bearer(post("/api/v1/tokens"))), "TokensApi.issue TOKEN");
    }

    /** Criterion 2: a wrong password, a disabled account and an agent are 401 on the token resource. */
    @ParameterizedTest
    @ValueSource(strings = {"alice:wrong-password", "disabled:" + TestUtils.TEST_PASSWORD,
            "robot:" + TestUtils.TEST_PASSWORD, "nobody:" + TestUtils.TEST_PASSWORD})
    void aBadPasswordIs401OnTheTokenResource(String credentials) throws Exception {
        MvcResult result = perform(get("/api/v1/tokens").header("Authorization", basic(credentials)));
        assertFalse(body(result).contains("TokensApi"), body(result));
        assertProblem(result, PASSWORD_REJECTED);
        assertChallenged(result);
    }

    /**
     * Every 401 on the token resource challenges for Basic, so a client that
     * sends a password only when challenged (RFC 9110) can still mint.
     */
    @Test
    void noCredentialIs401OnTheTokenResourceWithTheUsualDetail() throws Exception {
        MvcResult result = perform(get("/api/v1/tokens"));
        assertProblem(result, "Authentication required.");
        assertChallenged(result);
        assertChallenged(perform(bearerWith(get("/api/v1/tokens"), "prs_unknown")));
    }

    // ------------------------------------------------------- everywhere else

    /**
     * Criterion 1: everywhere else a correct password is a 401 problem+json
     * saying where a password is accepted.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "GET /api/v1/me",
            "GET /api/v1/openapi.json",
            "GET /api/v1/weblogs/myblog/entries",
            "POST /api/v1/weblogs/myblog/entries",
            "GET /api/v1/admin/users",
            "POST /api/mcp",
            "GET /api/pressroom-ui/menu.rol",
    })
    void aCorrectPasswordIsRefusedEverywhereElse(String route) throws Exception {
        MvcResult result = perform(withAlice(request(route)));
        assertProblem(result, PASSWORD_REFUSED);
        assertNull(result.getResponse().getHeader("WWW-Authenticate"),
                "only the token resource challenges for a password");
    }

    /** Criterion 2: a wrong password elsewhere is the same 401: the password is never checked there. */
    @ParameterizedTest
    @ValueSource(strings = {"alice:wrong-password", "robot:" + TestUtils.TEST_PASSWORD, "nobody:x"})
    void aWrongPasswordIsRefusedEverywhereElseTheSameWay(String credentials) throws Exception {
        assertProblem(perform(get("/api/v1/me").header("Authorization", basic(credentials))), PASSWORD_REFUSED);
    }

    /** RFC 9110: the scheme name is case-insensitive, as Spring's own Basic filter reads it. */
    @Test
    void theSchemeIsRecognisedInAnyCase() throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString(("alice:" + TestUtils.TEST_PASSWORD).getBytes(StandardCharsets.UTF_8));
        assertProblem(perform(get("/api/v1/me").header("Authorization", "basic " + credentials)), PASSWORD_REFUSED);
    }

    /** Without a Basic header the detail stays the one every other 401 gives. */
    @Test
    void noCredentialElsewhereKeepsTheUsualDetail() throws Exception {
        assertProblem(perform(get("/api/v1/me")), "Authentication required.");
        assertProblem(perform(get("/api/v1/me").header("Authorization", "Bearer prs_unknown")),
                "Authentication required.");
    }

    @Test
    void aTokenStillWorksEverywhereElse() throws Exception {
        assertReached(perform(bearer(get("/api/v1/me"))), "MetaApi.me TOKEN");
        assertReached(perform(bearer(post("/api/mcp"))), "McpEndpointServlet TOKEN");
    }

    /** Criterion 4: ping needs no credential, and no credential spoils it. */
    @ParameterizedTest
    @ValueSource(strings = {"", "Basic YWxpY2U6cGFzc3dvcmQ=", "Basic YWxpY2U6d3Jvbmc=", "Bearer prs_unknown"})
    void pingStaysOpenWithAnyOrNoCredential(String authorization) throws Exception {
        MockHttpServletRequestBuilder ping = get("/api/v1/ping");
        if (!authorization.isEmpty()) {
            ping.header("Authorization", authorization);
        }
        MvcResult result = perform(ping);
        assertEquals(200, result.getResponse().getStatus(), body(result));
        assertTrue(body(result).startsWith("MetaApi.ping "), body(result));
    }

    // ------------------------------------------------------------ path tricks

    /**
     * Criterion 3: no spelling of a path widens Basic beyond the token
     * resource. Each request is refused (the firewall's 400, or a 401) or
     * reaches the token resource's own handler, never another one.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/tokens/../me",
            "/api/v1/tokens/%2E%2E/me",
            "/api/v1/%74okens",
            "/api/v1/tokens;x=1",
            "/api/v1/tokens;x=1/../me",
            "/api/v1/tokensX",
            "/api/v1/tokens/abc%2Fdef",
            "/api/v1/tokens/abc%2F..%2F..%2Fme",
            "/api/v1/tokens//../me",
            "/api/v1/tokens/",
    })
    void pathTricksDoNotWidenBasic(String path) throws Exception {
        MvcResult result = perform(withAlice(get(URI.create(path))));
        int status = result.getResponse().getStatus();
        String body = body(result);
        if (status >= 200 && status < 300) {
            assertTrue(body.startsWith("TokensApi."), path + " reached " + body);
        } else {
            assertTrue(status == 400 || status == 401 || status == 404 || status == 405,
                    path + " answered " + status + ": " + body);
            assertFalse(body.contains("Api.") || body.contains("springdoc") || body.contains("Mcp"),
                    path + " reached a handler: " + body);
        }
    }

    /** The two spellings a firewall must stop outright, rather than leave to routing. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/tokens/../me", "/api/v1/tokens;x=1", "/api/v1/tokens/abc%2Fdef"})
    void theFirewallRefusesTraversalSemicolonsAndEncodedSlashes(String path) throws Exception {
        assertEquals(400, perform(withAlice(get(URI.create(path)))).getResponse().getStatus(), path);
    }

    /** A near miss is not the token resource: it is refused like any other route. */
    @Test
    void aNearMissIsNotTheTokenResource() throws Exception {
        assertProblem(perform(withAlice(get("/api/v1/tokensX"))), PASSWORD_REFUSED);
    }

    // ----------------------------------------------------------- the session

    /**
     * Criterion 5: an admin-UI session is no credential on the API. Both API
     * chains are stateless and never read the session, which is what keeps a
     * SESSION caller off {@code /api} until that is built deliberately.
     */
    @Test
    void anAdminUiSessionIsNoCredentialOnTheApi() throws Exception {
        MvcResult login = mockMvc.perform(formLogin("/pressroom_j_security_check")
                        .user("j_username", "alice").password("j_password", TestUtils.TEST_PASSWORD))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session, "the form login made no session");

        // The control: the same session is signed in on the admin UI.
        MvcResult menu = mockMvc.perform(get("/pressroom-ui/menu.rol").session(session)).andReturn();
        assertEquals("MenuController SESSION", body(menu));

        assertProblem(mockMvc.perform(get("/api/v1/me").session(session)).andReturn(), "Authentication required.");
        assertProblem(mockMvc.perform(get("/api/v1/tokens").session(session)).andReturn(),
                "Authentication required.");
    }

    // ---------------------------------------------------------------- helpers

    private static MockHttpServletRequestBuilder request(String route) {
        String[] parts = route.split(" ", 2);
        return "POST".equals(parts[0]) ? post(parts[1]) : get(parts[1]);
    }

    private static MockHttpServletRequestBuilder withAlice(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", basic("alice:" + TestUtils.TEST_PASSWORD));
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request) {
        return bearerWith(request, RAW_TOKEN);
    }

    private static MockHttpServletRequestBuilder bearerWith(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private static String basic(String credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static void assertReached(MvcResult result, String expected) throws Exception {
        assertEquals(200, result.getResponse().getStatus(), body(result));
        assertEquals(expected, body(result));
    }

    private static void assertProblem(MvcResult result, String detail) throws Exception {
        assertEquals(401, result.getResponse().getStatus(), body(result));
        assertEquals("application/problem+json", result.getResponse().getContentType());
        JsonNode problem = JSON.readTree(body(result));
        assertEquals(detail, problem.get("detail").asString(), body(result));
        assertEquals(401, problem.get("status").asInt());
        // Task 3 gave the two password answers codes of their own.
        String code = PASSWORD_REJECTED.equals(detail) ? "auth.passwordRejected"
                : PASSWORD_REFUSED.equals(detail) ? "auth.passwordNotAcceptedHere" : "auth.unauthorized";
        assertEquals(code, problem.get("code").asString(), body(result));
    }

    private static void assertChallenged(MvcResult result) throws Exception {
        assertEquals(401, result.getResponse().getStatus(), body(result));
        assertEquals(CHALLENGE, result.getResponse().getHeader("WWW-Authenticate"));
    }
}
