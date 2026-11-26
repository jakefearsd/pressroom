package com.wikantik.pressroom.boot;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.core.security.ReturnPathLoginSuccessHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import jakarta.servlet.http.HttpServletRequest;
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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Session access to {@code /api} (spec 2026-11-25-react-admin-migration-design, A3;
 * SA-18 rules 1 and 2), through the real {@link SecurityConfig} chains: a request
 * with no {@code Authorization} header may authenticate from the admin UI's
 * session, a write then needs the session's CSRF token, {@code /api/mcp} never
 * accepts a session, and a request that does carry the header never falls back
 * to the session.
 *
 * <p>The handlers behind the routes are stubs: what is pinned is the security
 * decision, not a controller's business logic.
 */
class ApiSessionAccessTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger APPLIED = new AtomicInteger();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final WebloggerProvider PROVIDER = mock(WebloggerProvider.class);
    private static final UserManager USERS = mock(UserManager.class);

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mockMvc;

    @Controller
    static class StubController {

        @RequestMapping({"/api/v1/me", "/api/v1/probe", "/api/mcp", "/api/v1/tokens", "/api/v1/tokens/abc"})
        @ResponseBody
        String api(HttpServletRequest request) {
            if (!"GET".equals(request.getMethod())) {
                APPLIED.incrementAndGet();
            }
            return "{\"ok\":true}";
        }

        @RequestMapping({"/pressroom-ui/app.rol", "/pressroom-ui/app/**", "/pressroom-ui/app-messages.rol"})
        @ResponseBody
        String react() {
            return "shell";
        }

        /** What the shell controller reads: the UI chain's token, header name included. */
        @RequestMapping("/pressroom-ui/csrf-probe.rol")
        @ResponseBody
        String csrf(HttpServletRequest request) {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            return token.getHeaderName() + "=" + token.getToken();
        }
    }

    @Configuration
    @EnableWebSecurity
    @Import({SecurityConfig.class, StubController.class})
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        WebloggerProvider webloggerProvider() {
            return PROVIDER;
        }

        @Bean
        MessageSource messageSource() {
            ResourceBundleMessageSource source = new ResourceBundleMessageSource();
            source.setBasename("ApplicationResources");
            source.setDefaultEncoding("UTF-8");
            return source;
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
        context.close();
    }

    @BeforeEach
    void resetProvider() {
        reset(PROVIDER, USERS);
        Weblogger weblogger = mock(Weblogger.class);
        when(PROVIDER.isBootstrapped()).thenReturn(true);
        when(PROVIDER.getWeblogger()).thenReturn(weblogger);
        when(weblogger.getUserManager()).thenReturn(USERS);
        APPLIED.set(0);
    }

    // ------------------------------------------------------------ helpers

    /** A session holding a signed-in user, as form login leaves it. */
    private static MockHttpSession sessionOf(String name, String authority) {
        UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken.authenticated(
                name, null, List.of(new SimpleGrantedAuthority(authority)));
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(new MockHttpServletRequest()));
        return sessionOf(auth);
    }

    /** A session whose account the (bootstrapped) business tier knows and has enabled. */
    private static MockHttpSession signedUp(String name, String authority) {
        com.wikantik.pressroom.pojos.User user = new com.wikantik.pressroom.pojos.User();
        user.setUserName(name);
        user.setEnabled(Boolean.TRUE);
        try {
            when(USERS.getUserByUserName(name)).thenReturn(user);
        } catch (com.wikantik.pressroom.WebloggerException e) {
            throw new IllegalStateException(e);
        }
        return sessionOf(name, authority);
    }

    private static MockHttpSession sessionOf(Authentication auth) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(auth));
        return session;
    }

    private static MockHttpServletRequestBuilder api(String method, String path) {
        int n = REQUESTS.incrementAndGet();
        return request(HttpMethod.valueOf(method), path).with(r -> {
            r.setRemoteAddr("10.30." + n / 250 + "." + (n % 250 + 1));
            return r;
        });
    }

    /** The token the shell would embed: asked of the UI chain, with this session. */
    private static String[] shellToken(MockHttpSession session) throws Exception {
        String body = mockMvc.perform(get("/pressroom-ui/csrf-probe.rol").session(session))
                .andReturn().getResponse().getContentAsString();
        return body.split("=", 2);
    }

    private static void assertProblem(MvcResult result, int status, String code) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertTrue(result.getResponse().getContentType().startsWith("application/problem+json"));
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertEquals(code, body.path("code").asString());
    }

    private static void assertNoCookies(MvcResult result) {
        assertTrue(result.getResponse().getHeaders("Set-Cookie").isEmpty(), "no Set-Cookie from /api");
        assertTrue(result.getResponse().getCookies().length == 0);
    }

    // ------------------------------------------------------------ criterion 1: session on /api

    @Test
    void anEditorsSessionAuthenticatesAGetWithNoAuthorizationHeader() throws Exception {
        MvcResult result = mockMvc.perform(api("GET", "/api/v1/me").session(signedUp("erin", "editor")))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertNoCookies(result);
    }

    @Test
    void anAdminsSessionAuthenticatesToo() throws Exception {
        assertEquals(200, mockMvc.perform(api("GET", "/api/v1/me").session(signedUp("root", "admin")))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void aSignedOutRequestIsA401Problem() throws Exception {
        MockHttpSession session = signedUp("erin", "editor");
        session.invalidate();
        assertProblem(mockMvc.perform(api("GET", "/api/v1/me").session(session)).andReturn(),
                401, "auth.unauthorized");
        assertProblem(mockMvc.perform(api("GET", "/api/v1/me")).andReturn(), 401, "auth.unauthorized");
    }

    @Test
    void anApiCallWithNoSessionCreatesNoSessionAndSetsNoCookie() throws Exception {
        MvcResult result = mockMvc.perform(api("GET", "/api/v1/me")).andReturn();
        assertNull(result.getRequest().getSession(false));
        assertNoCookies(result);
    }

    @Test
    void aRememberMeCookieIsNotHonouredOnApi() throws Exception {
        MvcResult result = mockMvc.perform(api("GET", "/api/v1/me")
                .cookie(new jakarta.servlet.http.Cookie("remember-me", "dGhlZmFrZQ"))).andReturn();
        assertProblem(result, 401, "auth.unauthorized");
        assertNoCookies(result);
    }

    // ------------------------------------------------------------ criterion 3: boundaries

    @Test
    void aSessionOnMcpIsA401() throws Exception {
        MockHttpSession session = signedUp("erin", "editor");
        String[] token = shellToken(session);
        assertProblem(mockMvc.perform(api("POST", "/api/mcp").session(session).header(token[0], token[1]))
                .andReturn(), 401, "auth.unauthorized");
        assertProblem(mockMvc.perform(api("GET", "/api/mcp").session(session)).andReturn(),
                401, "auth.unauthorized");
        assertEquals(0, APPLIED.get());
    }

    @Test
    void anSsoReaderSessionIsA401WhereverAnAnonymousCallerIs() throws Exception {
        MockHttpSession reader = signedUp("rae", "reader");
        assertProblem(mockMvc.perform(api("GET", "/api/v1/me").session(reader)).andReturn(),
                401, "auth.unauthorized");
        assertProblem(mockMvc.perform(api("GET", "/api/v1/tokens").session(reader)).andReturn(),
                401, "auth.unauthorized");
        String[] token = shellToken(reader);
        assertProblem(mockMvc.perform(api("PATCH", "/api/v1/probe").session(reader).header(token[0], token[1]))
                .andReturn(), 401, "auth.unauthorized");
        assertEquals(0, APPLIED.get());
    }

    @Test
    void aSessionPlusABadBearerHeaderIsA401TheHeaderWins() throws Exception {
        assertProblem(mockMvc.perform(api("GET", "/api/v1/me").session(signedUp("erin", "editor"))
                .header("Authorization", "Bearer prs_nope")).andReturn(), 401, "auth.unauthorized");
    }

    @Test
    void aSessionPlusAnyOtherAuthorizationHeaderNeverFallsBackToTheSession() throws Exception {
        assertEquals(401, mockMvc.perform(api("GET", "/api/v1/me").session(signedUp("erin", "editor"))
                .header("Authorization", "Basic Zm9vOmJhcg==")).andReturn().getResponse().getStatus());
    }

    // ------------------------------------------------------------ criterion 2: CSRF

    @Test
    void aSessionWriteWithTheRightTokenIsApplied() throws Exception {
        MockHttpSession session = signedUp("erin", "editor");
        String[] token = shellToken(session);
        assertEquals(SecurityConfig.CSRF_HEADER_NAME, token[0], "the header name the shell is told");

        MvcResult result = mockMvc.perform(api("PATCH", "/api/v1/probe").session(session)
                .header(SecurityConfig.CSRF_HEADER_NAME, token[1])).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(1, APPLIED.get());
        assertNoCookies(result);
    }

    @Test
    void oneTokenServesBothChainsAcrossRequests() throws Exception {
        MockHttpSession session = signedUp("erin", "editor");
        String[] token = shellToken(session);
        for (String method : new String[] {"POST", "PATCH", "DELETE", "PUT"}) {
            assertEquals(200, mockMvc.perform(api(method, "/api/v1/probe").session(session)
                    .header(token[0], token[1])).andReturn().getResponse().getStatus(), method);
        }
        assertEquals(4, APPLIED.get());
    }

    @Test
    void aSessionWriteWithNoTokenIs403CsrfInvalidAndNothingIsApplied() throws Exception {
        MockHttpSession session = signedUp("erin", "editor");
        shellToken(session);
        MvcResult result = mockMvc.perform(api("PATCH", "/api/v1/probe").session(session)).andReturn();
        assertProblem(result, 403, "csrf.invalid");
        assertFalse(JSON.readTree(result.getResponse().getContentAsString()).path("detail").asString().isBlank());
        assertEquals(0, APPLIED.get());
    }

    @Test
    void aSessionWriteWithAWrongTokenIs403CsrfInvalidAndNothingIsApplied() throws Exception {
        MockHttpSession session = signedUp("erin", "editor");
        shellToken(session);
        assertProblem(mockMvc.perform(api("POST", "/api/v1/probe").session(session)
                .header(SecurityConfig.CSRF_HEADER_NAME, "not-the-token")).andReturn(), 403, "csrf.invalid");
        assertEquals(0, APPLIED.get());
    }

    @Test
    void aSessionWriteBeforeAnyTokenWasIssuedIsRefusedToo() throws Exception {
        assertProblem(mockMvc.perform(api("POST", "/api/v1/probe").session(signedUp("erin", "editor"))
                .header(SecurityConfig.CSRF_HEADER_NAME, "guess")).andReturn(), 403, "csrf.invalid");
        assertEquals(0, APPLIED.get());
    }

    @Test
    void sessionReadsNeedNoToken() throws Exception {
        assertEquals(200, mockMvc.perform(api("GET", "/api/v1/probe").session(signedUp("erin", "editor")))
                .andReturn().getResponse().getStatus());
        assertEquals(200, mockMvc.perform(api("OPTIONS", "/api/v1/probe").session(signedUp("erin", "editor")))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void aBearerWriteNeedsNoCsrfToken() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        ApiTokenManager manager = mock(ApiTokenManager.class);
        User agent = new User();
        agent.setUserName("agent");
        agent.setEnabled(Boolean.TRUE);
        ApiToken token = new ApiToken();
        token.setUser(agent);
        token.setScopeRole(ApiToken.Role.ADMIN);
        when(PROVIDER.getWeblogger()).thenReturn(weblogger);
        when(weblogger.getApiTokenManager()).thenReturn(manager);
        when(manager.authenticate("prs_good")).thenReturn(token);

        MvcResult result = mockMvc.perform(api("PATCH", "/api/v1/probe")
                .header("Authorization", "Bearer prs_good")).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(1, APPLIED.get());
    }

    @Test
    void aBearerWriteStaysCsrfFreeEvenWithASessionCookieAlongside() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        ApiTokenManager manager = mock(ApiTokenManager.class);
        User agent = new User();
        agent.setUserName("agent");
        agent.setEnabled(Boolean.TRUE);
        ApiToken token = new ApiToken();
        token.setUser(agent);
        token.setScopeRole(ApiToken.Role.ADMIN);
        when(PROVIDER.getWeblogger()).thenReturn(weblogger);
        when(weblogger.getApiTokenManager()).thenReturn(manager);
        when(manager.authenticate("prs_good")).thenReturn(token);

        assertEquals(200, mockMvc.perform(api("PATCH", "/api/v1/probe").session(signedUp("erin", "editor"))
                .header("Authorization", "Bearer prs_good")).andReturn().getResponse().getStatus());
    }

    @Test
    void aWriteWithNoCredentialAtAllIsStillA401NotACsrfRefusal() throws Exception {
        assertProblem(mockMvc.perform(api("POST", "/api/v1/probe")).andReturn(), 401, "auth.unauthorized");
    }

    // ------------------------------------------------------------ criterion 5: tokens

    @Test
    void aSessionCallerReachesTheTokenResourceWithCsrfOnWrites() throws Exception {
        MockHttpSession session = signedUp("erin", "editor");
        String[] token = shellToken(session);

        assertEquals(200, mockMvc.perform(api("GET", "/api/v1/tokens").session(session)).andReturn()
                .getResponse().getStatus());
        assertProblem(mockMvc.perform(api("POST", "/api/v1/tokens").session(session)).andReturn(),
                403, "csrf.invalid");
        assertProblem(mockMvc.perform(api("DELETE", "/api/v1/tokens/abc").session(session)).andReturn(),
                403, "csrf.invalid");
        assertEquals(0, APPLIED.get());
        assertEquals(200, mockMvc.perform(api("POST", "/api/v1/tokens").session(session)
                .header(token[0], token[1])).andReturn().getResponse().getStatus());
        assertEquals(200, mockMvc.perform(api("DELETE", "/api/v1/tokens/abc").session(session)
                .header(token[0], token[1])).andReturn().getResponse().getStatus());
        assertEquals(2, APPLIED.get());
    }

    // ------------------------------------------------------------ a changed password ends the session

    @Test
    void aSessionWhosePasswordChangedIsA401OnApiToo() throws Exception {
        User erin = new User();
        erin.setUserName("erin");
        erin.setPassword("hash-1");
        erin.setEnabled(Boolean.TRUE);
        when(USERS.getUserByUserName("erin", null)).thenReturn(erin);
        MockHttpSession session = signedUp("erin", "editor");

        assertEquals(200, mockMvc.perform(api("GET", "/api/v1/me").session(session)).andReturn()
                .getResponse().getStatus());
        erin.setPassword("hash-2");
        assertProblem(mockMvc.perform(api("GET", "/api/v1/me").session(session)).andReturn(),
                401, "auth.unauthorized");
        assertTrue(session.isInvalid());
    }

    // ------------------------------------------------------------ criterion 6/7: the React app's routes

    @Test
    void theAppRoutesRequireAnEditorOrAnAdmin() throws Exception {
        for (String path : List.of("/pressroom-ui/app.rol", "/pressroom-ui/app/", "/pressroom-ui/app/weblogs/x/traffic",
                "/pressroom-ui/app/assets/a.js", "/pressroom-ui/app-messages.rol")) {
            assertEquals(200, mockMvc.perform(get(path).session(signedUp("erin", "editor"))).andReturn()
                    .getResponse().getStatus(), path);
            assertEquals(200, mockMvc.perform(get(path).session(signedUp("root", "admin"))).andReturn()
                    .getResponse().getStatus(), path);
            assertEquals(403, mockMvc.perform(get(path).session(signedUp("rae", "reader"))).andReturn()
                    .getResponse().getStatus(), path);
            MvcResult anonymous = mockMvc.perform(get(path)).andReturn();
            assertEquals(302, anonymous.getResponse().getStatus(), path);
            assertEquals("/pressroom-ui/login.rol", anonymous.getResponse().getRedirectedUrl(), path);
        }
    }

    @Test
    void aDeepLinkSignInReturnsToTheDeepLink() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/pressroom-ui/app/weblogs/x/traffic").queryParam("range", "30d").session(session))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isFound());

        MockHttpServletRequest signIn = new MockHttpServletRequest("POST", "/pressroom_j_security_check");
        signIn.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ReturnPathLoginSuccessHandler().onAuthenticationSuccess(signIn, response,
                UsernamePasswordAuthenticationToken.authenticated("erin", null,
                        List.of(new SimpleGrantedAuthority("editor"))));

        assertNotNull(response.getRedirectedUrl());
        assertEquals("http://localhost/pressroom-ui/app/weblogs/x/traffic?range=30d&continue", response.getRedirectedUrl());
    }

    // ------------------------------------------------------------ review follow-ups

    /** A header that names no usable credential is still an Authorization header: the session is not consulted. */
    @Test
    void anEmptyBlankOrTokenlessAuthorizationHeaderNeverFallsBackToTheSession() throws Exception {
        for (String value : new String[] {"", "   ", "Bearer", "Bearer ", "Bearer    "}) {
            assertProblem(mockMvc.perform(api("GET", "/api/v1/me").session(signedUp("erin", "editor"))
                    .header("Authorization", value)).andReturn(), 401, "auth.unauthorized");
        }
        assertEquals(0, APPLIED.get());
    }

    /** What actually happens to other spellings of the MCP endpoint with a live session: refused, nothing applied. */
    @Test
    void otherSpellingsOfMcpNeverTakeTheSession() throws Exception {
        MockHttpSession session = signedUp("erin", "editor");
        String[] token = shellToken(session);
        for (String path : new String[] {"/api/mcp/", "/api/mcp/x", "/api/%6dcp"}) {
            for (String method : new String[] {"GET", "POST"}) {
                MvcResult result = mockMvc.perform(api(method, path).session(session)
                        .header(token[0], token[1])).andReturn();
                int status = result.getResponse().getStatus();
                assertTrue(status == 401 || status == 400, method + " " + path + " answered " + status);
            }
        }
        assertEquals(0, APPLIED.get());
    }

    /** The API mount serves only the API: a UI route under /api is refused even for a live editor session. */
    @Test
    void aSessionCannotReachUiRoutesThroughTheApiMount() throws Exception {
        MockHttpSession session = signedUp("erin", "editor");
        String[] token = shellToken(session);
        assertEquals(403, mockMvc.perform(api("GET", "/api/pressroom-ui/menu.rol").session(session))
                .andReturn().getResponse().getStatus());
        assertEquals(403, mockMvc.perform(api("POST", "/api/pressroom-ui/menu.rol").session(session)
                .header(token[0], token[1])).andReturn().getResponse().getStatus());
        assertEquals(403, mockMvc.perform(api("POST", "/api/pressroom-ui/menu.rol").session(session))
                .andReturn().getResponse().getStatus());
        assertEquals(0, APPLIED.get());
    }

    /** A signed-in account disabled after sign-in loses the session's API access, read and write alike. */
    @Test
    void anAccountDisabledWhileSignedInIs401OnApi() throws Exception {
        MockHttpSession session = signedUp("erin", "editor");
        String[] token = shellToken(session);
        assertEquals(200, mockMvc.perform(api("GET", "/api/v1/me").session(session)).andReturn()
                .getResponse().getStatus());

        // The enabled-only lookup no longer finds her.
        when(USERS.getUserByUserName("erin")).thenReturn(null);

        assertProblem(mockMvc.perform(api("GET", "/api/v1/me").session(session)).andReturn(),
                401, "auth.unauthorized");
        assertProblem(mockMvc.perform(api("PATCH", "/api/v1/probe").session(session)
                .header(token[0], token[1])).andReturn(), 401, "auth.unauthorized");
        assertEquals(0, APPLIED.get());
    }

    @Test
    void aLookupThatThrowsRefusesTheSessionToo() throws Exception {
        MockHttpSession session = signedUp("erin", "editor");
        when(USERS.getUserByUserName("erin")).thenThrow(new com.wikantik.pressroom.WebloggerException("down"));
        assertProblem(mockMvc.perform(api("GET", "/api/v1/me").session(session)).andReturn(),
                401, "auth.unauthorized");
    }

    @Test
    void theCsrfTokenIsReadFromTheHeaderOnly() throws Exception {
        MockHttpSession session = signedUp("erin", "editor");
        String[] token = shellToken(session);
        assertProblem(mockMvc.perform(api("POST", "/api/v1/probe").session(session)
                .queryParam("_csrf", token[1])).andReturn(), 403, "csrf.invalid");
        assertProblem(mockMvc.perform(api("POST", "/api/v1/probe").session(session)
                .param("_csrf", token[1])).andReturn(), 403, "csrf.invalid");
        assertEquals(0, APPLIED.get());
    }
}
