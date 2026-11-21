package com.wikantik.pressroom.ui.restapi.auth;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ApiBodyLimitFilter;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ApiProblemWriter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The problems the security chain writes itself, outside ApiExceptionHandler's
 * reach, carry their specific codes and follow the caller's
 * {@code Accept-Language} (spec 2026-11-20-api-contract-design, Task 3,
 * criterion 3): the 401s of the entry points, the 403 of the access-denied
 * handler, the 429 of the throttle and the 413 of the body limit. The French
 * comes from a test-only bundle placed before ApplicationResources, as in
 * ProblemLocalisationTest; with no header each reads the English it always had.
 */
class SecurityChainProblemCodesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApiProblemWriter writer = new ApiProblemWriter(JSON, testMessages());

    private static ResourceBundleMessageSource testMessages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        String testBundle = ApiProblemWriter.class.getPackageName().replace('.', '/') + "/ProblemL10nTest";
        source.setBasenames(testBundle, "ApplicationResources");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String method, String uri, String acceptLanguage) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (acceptLanguage != null) {
            request.addHeader("Accept-Language", acceptLanguage);
        }
        return request;
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsByteArray());
    }

    private JsonNode commence(ApiAuthenticationEntryPoint entryPoint, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        entryPoint.commence(request, response, new InsufficientAuthenticationException("anonymous"));
        assertEquals(401, response.getStatus());
        return body(response);
    }

    private static void assertProblem(JsonNode body, String code, String detail) {
        assertEquals(code, body.get("code").asString(), "code of " + body);
        assertEquals(detail, body.get("detail").asString(), "detail of " + body);
    }

    @Test
    void anAnonymousCallerIsAuthUnauthorizedInTheCallersLanguage() throws Exception {
        for (ApiAuthenticationEntryPoint entryPoint : List.of(new ApiAuthenticationEntryPoint(writer),
                ApiAuthenticationEntryPoint.refusingPasswords(writer),
                ApiAuthenticationEntryPoint.challengingForPassword(writer))) {
            assertProblem(commence(entryPoint, request("GET", "/api/v1/me", null)),
                    "auth.unauthorized", "Authentication required.");
            assertProblem(commence(entryPoint, request("GET", "/api/v1/me", "fr")),
                    "auth.unauthorized", "Authentification requise.");
        }
    }

    @Test
    void aPasswordOffTheTokenResourceIsAuthPasswordNotAcceptedHere() throws Exception {
        ApiAuthenticationEntryPoint entryPoint = ApiAuthenticationEntryPoint.refusingPasswords(writer);
        MockHttpServletRequest english = request("GET", "/api/v1/me", null);
        english.addHeader("Authorization", "Basic YTpi");
        assertProblem(commence(entryPoint, english), "auth.passwordNotAcceptedHere",
                "Password authentication is accepted only on /api/v1/tokens. "
                        + "Mint a token there and send it as a bearer token.");

        MockHttpServletRequest french = request("GET", "/api/v1/me", "fr");
        french.addHeader("Authorization", "Basic YTpi");
        assertProblem(commence(entryPoint, french), "auth.passwordNotAcceptedHere",
                "Le mot de passe n'est accepté que sur /api/v1/tokens.");
    }

    @Test
    void aPasswordTheTokenResourceTurnsAwayIsAuthPasswordRejected() throws Exception {
        ApiAuthenticationEntryPoint entryPoint = ApiAuthenticationEntryPoint.rejectingPassword(writer);
        for (String language : new String[] {null, "fr"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            entryPoint.commence(request("POST", "/api/v1/tokens", language), response,
                    new BadCredentialsException("wrong"));
            assertEquals(401, response.getStatus());
            assertEquals(ApiAuthenticationEntryPoint.BASIC_CHALLENGE, response.getHeader("WWW-Authenticate"));
            assertProblem(body(response), "auth.passwordRejected", language == null
                    ? "The username or password was not accepted."
                    : "Le nom d'utilisateur ou le mot de passe n'a pas été accepté.");
        }
    }

    @Test
    void aDeniedRequestIsAuthForbiddenInTheCallersLanguage() throws Exception {
        ApiAccessDeniedHandler handler = new ApiAccessDeniedHandler(writer);
        for (String language : new String[] {null, "fr"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            handler.handle(request("GET", "/api/v1/admin/users", language), response, new AccessDeniedException("no"));
            assertEquals(403, response.getStatus());
            assertProblem(body(response), "auth.forbidden", language == null ? "Access denied." : "Accès refusé.");
        }
    }

    @Test
    void aThrottledCallerIsRequestThrottledInTheCallersLanguage() throws Exception {
        ApiTokenManager tokens = mock(ApiTokenManager.class);
        when(tokens.authenticate(anyString())).thenReturn(null);
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(mock(Weblogger.class));
        ApiTokenAuthFilter filter = new ApiTokenAuthFilter(() -> tokens, provider, ApiThrottle.forTesting(1, 60), writer);

        MockHttpServletRequest first = request("GET", "/api/v1/ping", null);
        first.addHeader("Authorization", "Bearer prs_same");
        filter.doFilter(first, new MockHttpServletResponse(), mock(FilterChain.class));
        for (String language : new String[] {null, "fr"}) {
            MockHttpServletRequest again = request("GET", "/api/v1/ping", language);
            again.addHeader("Authorization", "Bearer prs_same");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(again, response, mock(FilterChain.class));
            assertEquals(429, response.getStatus());
            assertProblem(body(response), "request.throttled", language == null
                    ? "Too many requests. Slow down and retry."
                    : "Trop de requêtes. Ralentissez et réessayez.");
        }
    }

    @Test
    void anOversizedBodyIsRequestTooLargeInTheCallersLanguage() throws Exception {
        for (String language : new String[] {null, "fr"}) {
            MockHttpServletRequest request = request("POST", "/api/v1/weblogs/b/entries", language);
            request.setContentType("application/json");
            request.setContent(new byte[ApiBodyLimitFilter.MAX_BODY_BYTES + 1]);
            MockHttpServletResponse response = new MockHttpServletResponse();
            new ApiBodyLimitFilter(writer).doFilter(request, response, mock(FilterChain.class));
            assertEquals(413, response.getStatus());
            assertProblem(body(response), "request.tooLarge", language == null
                    ? "The request body is over 4048576 bytes."
                    : "Le corps de la requête dépasse 4048576 octets.");
        }
    }

    // ---- the token-scope refusals: thrown to ApiExceptionHandler, so their codes are what matter here ----

    static class Plain {
        void handle() { }
    }

    @AdminScoped
    static class Admin {
        void handle() { }
    }

    private static HandlerMethod handler(Object bean) throws NoSuchMethodException {
        Method method = bean.getClass().getDeclaredMethod("handle");
        return new HandlerMethod(bean, method);
    }

    private static ApiException refused(String method, Weblog actionWeblog, String scope, ApiToken.Role role,
                                        Object bean, Map<String, String> uriVariables) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/v1/probe");
        if (actionWeblog != null) {
            request.setAttribute("actionWeblog", actionWeblog);
        }
        if (uriVariables != null) {
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, uriVariables);
        }
        var auth = new UsernamePasswordAuthenticationToken("agent", null, List.of());
        auth.setDetails(new ApiPrincipal("agent", scope, role, "token-1"));
        SecurityContextHolder.getContext().setAuthentication(auth);
        HandlerMethod handler = handler(bean);
        return assertThrows(ApiException.class,
                () -> new ApiScopeInterceptor().preHandle(request, new MockHttpServletResponse(), handler));
    }

    private static void assertRefusal(ApiException refusal, String code, String english) {
        assertEquals(code, refusal.toProblem(null).code());
        assertEquals(english, refusal.getMessage());
    }

    @Test
    void theTokenScopeRefusalsHaveTheirCodes() throws Exception {
        Weblog mine = new Weblog();
        mine.setHandle("myblog");
        Weblog other = new Weblog();
        other.setHandle("other");
        assertRefusal(refused("GET", mine, null, ApiToken.Role.POST, new Admin(), null),
                "token.notAdminScoped", "This token is not scoped for administration.");
        assertRefusal(refused("POST", mine, null, ApiToken.Role.READ, new Plain(), null),
                "token.readOnly", "This token is read-only.");
        assertRefusal(refused("GET", other, "myblog", ApiToken.Role.READ, new Plain(), null),
                "weblog.notFound", "No such weblog.");
        assertRefusal(refused("GET", null, "myblog", ApiToken.Role.READ, new Plain(), Map.of("handle", "x")),
                "weblog.notFound", "No such weblog.");
        assertRefusal(refused("GET", null, "myblog", ApiToken.Role.READ, new Plain(), Map.of()),
                "token.weblogScopedRoute", "This token is limited to the weblog 'myblog', and this route is not "
                        + "part of one weblog. Use a token with no weblog limit.");
    }
}
