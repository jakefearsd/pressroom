package org.apache.roller.weblogger.ui.restapi.auth;

import jakarta.servlet.FilterChain;
import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.restapi.ApiProblemWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiTokenAuthFilterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ApiProblemWriter PROBLEM_WRITER = new ApiProblemWriter(OBJECT_MAPPER);

    /** A provider reporting a bootstrapped tier whose facade is a Mockito mock. */
    private final Weblogger weblogger = mock(Weblogger.class);
    private final WebloggerProvider provider = bootstrappedProvider(weblogger);

    private static WebloggerProvider bootstrappedProvider(Weblogger weblogger) {
        WebloggerProvider p = mock(WebloggerProvider.class);
        when(p.isBootstrapped()).thenReturn(true);
        when(p.getWeblogger()).thenReturn(weblogger);
        return p;
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static ApiToken token(String weblog, ApiToken.Role role) {
        User user = new User();
        user.setUserName("agent");
        ApiToken t = new ApiToken();
        t.setUser(user);
        t.setScopeWeblog(weblog);
        t.setScopeRole(role);
        return t;
    }

    @Test
    void aValidBearerTokenAuthenticatesWithTheUserNameAsPrincipal() throws Exception {
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        when(mgr.authenticate("rlr_good")).thenReturn(token("testblog", ApiToken.Role.POST));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");
        request.addHeader("Authorization", "Bearer rlr_good");
        FilterChain chain = mock(FilterChain.class);

        new ApiTokenAuthFilter(() -> mgr, provider, PROBLEM_WRITER)
                .doFilter(request, new MockHttpServletResponse(), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        // RollerHandlerInterceptor.resolveAuthenticatedUser has a branch for a
        // String principal, so identity resolution works unchanged.
        assertEquals("agent", auth.getPrincipal());
        ApiPrincipal details = (ApiPrincipal) auth.getDetails();
        assertEquals("testblog", details.scopeWeblog());
        assertEquals(ApiToken.Role.POST, details.scopeRole());
        verify(chain).doFilter(any(), any());
    }

    @Test
    void anUnknownTokenLeavesTheContextEmptyAndStillCallsTheChain() throws Exception {
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        when(mgr.authenticate(anyString())).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");
        request.addHeader("Authorization", "Bearer rlr_bad");
        FilterChain chain = mock(FilterChain.class);

        new ApiTokenAuthFilter(() -> mgr, provider, PROBLEM_WRITER)
                .doFilter(request, new MockHttpServletResponse(), chain);

        // The filter never rejects; authorization is the chain's job, so an
        // unauthenticated request reaches it and gets a 401 there.
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(any(), any());
    }

    /**
     * This filter runs inside the security chain at order 40;
     * {@code PersistenceSessionFilter} (the only thing that otherwise ever
     * releases the current thread's persistence session) is order 60. When
     * authenticate() does not establish a SecurityContext, the request is
     * about to be refused by {@code authorizeHttpRequests} --
     * {@code ExceptionTranslationFilter} hands the 401 straight to {@code
     * ApiAuthenticationEntryPoint} without ever calling the outer chain, so
     * order 60 never runs. Left unreleased, the EntityManager this filter's
     * own digest lookup just bound to this Tomcat worker thread would stay
     * open -- and bound to whatever persistence context it accumulated --
     * until some unrelated later request on the same thread happened to
     * reach order 60 and inherit it.
     */
    @Test
    void aFailedAuthenticationReleasesThePersistenceSessionSinceOrder60NeverRuns() throws Exception {
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        when(mgr.authenticate(anyString())).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Authorization", "Bearer rlr_bad");
        FilterChain chain = mock(FilterChain.class);

        new ApiTokenAuthFilter(() -> mgr, provider, PROBLEM_WRITER)
                .doFilter(request, new MockHttpServletResponse(), chain);

        verify(weblogger).release();
    }

    /**
     * Before the tier is bootstrapped there is no session to release -- and
     * asking the provider for the Weblogger would throw. This filter runs
     * ahead of {@code BootstrapFilter}, so it genuinely can see such a request.
     */
    @Test
    void aFailedAuthenticationBeforeBootstrapReleasesNothing() throws Exception {
        WebloggerProvider notUp = mock(WebloggerProvider.class);
        when(notUp.isBootstrapped()).thenReturn(false);
        when(notUp.getWeblogger()).thenThrow(new IllegalStateException("not bootstrapped"));
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        when(mgr.authenticate(anyString())).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Authorization", "Bearer rlr_bad");
        FilterChain chain = mock(FilterChain.class);

        assertDoesNotThrow(() -> new ApiTokenAuthFilter(() -> mgr, notUp, PROBLEM_WRITER)
                .doFilter(request, new MockHttpServletResponse(), chain));

        verify(notUp, never()).getWeblogger();
        verify(chain).doFilter(any(), any());
    }

    /**
     * The converse of the test above, and just as load-bearing: a
     * successful Bearer authentication always reaches order 60, regardless
     * of which of {@code authorizeHttpRequests}' three rules
     * ({@code POST /api/v1/tokens}'s {@code authenticated()}, {@code
     * /api/v1/ping}'s {@code permitAll()}, or the {@code
     * anyRequest().authenticated()} fallback) applies to the path -- an
     * authenticated caller satisfies {@code authenticated()} outright, and
     * {@code permitAll()} lets the request through independent of
     * authentication either way. So releasing here too would close the
     * EntityManager out from under the controller that is about to run on
     * it. Order 60 must stay the sole releaser on this path.
     */
    @Test
    void aSuccessfulAuthenticationDoesNotReleaseHere() throws Exception {
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        when(mgr.authenticate("rlr_good")).thenReturn(token("testblog", ApiToken.Role.POST));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");
        request.addHeader("Authorization", "Bearer rlr_good");
        FilterChain chain = mock(FilterChain.class);

        new ApiTokenAuthFilter(() -> mgr, provider, PROBLEM_WRITER)
                .doFilter(request, new MockHttpServletResponse(), chain);

        verify(weblogger, never()).release();
    }

    private static ApiToken tokenOwnedBy(User owner) {
        ApiToken t = new ApiToken();
        t.setUser(owner);
        t.setScopeRole(ApiToken.Role.POST);
        return t;
    }

    private Authentication filterWith(ApiToken token) throws Exception {
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        when(mgr.authenticate("rlr_owned")).thenReturn(token);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Authorization", "Bearer rlr_owned");
        FilterChain chain = mock(FilterChain.class);

        new ApiTokenAuthFilter(() -> mgr, provider, PROBLEM_WRITER)
                .doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.clearContext();
        return auth;
    }

    /** Criterion 7: checked here, explicitly, not left to the enabled-only user lookup downstream. */
    @Test
    void aDisabledPersonsTokenAuthenticatesNoOne() throws Exception {
        User person = new User();
        person.setUserName("jake");
        person.setEnabled(Boolean.FALSE);

        assertNull(filterWith(tokenOwnedBy(person)));
        verify(weblogger).release();
    }

    /** Criterion 7: an agent likewise -- and re-enabling it makes the same token work again. */
    @Test
    void aDisabledAgentsTokenAuthenticatesNoOneUntilReEnabled() throws Exception {
        User agent = new User();
        agent.setUserName("bot");
        agent.setKind(User.Kind.AGENT);
        agent.setEnabled(Boolean.FALSE);
        ApiToken token = tokenOwnedBy(agent);

        assertNull(filterWith(token));

        agent.setEnabled(Boolean.TRUE);
        Authentication auth = filterWith(token);
        assertNotNull(auth);
        assertEquals("bot", auth.getPrincipal());
    }

    @Test
    void aMissingOrNonBearerHeaderIsIgnored() throws Exception {
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tokens");
        request.addHeader("Authorization", "Basic am9objpwdw==");
        FilterChain chain = mock(FilterChain.class);

        new ApiTokenAuthFilter(() -> mgr, provider, PROBLEM_WRITER)
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(mgr);
    }

    /**
     * A ServletFilter runs outside DispatcherServlet's reach, so
     * ApiExceptionHandler's @RestControllerAdvice can never see an exception
     * thrown from here -- a throttled request must get its problem+json body
     * written directly by the filter, and must never reach the chain.
     */
    @Test
    void aThrottledCallerGetsAProblemJsonResponseAndNeverReachesTheChain() throws Exception {
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        when(mgr.authenticate(anyString())).thenReturn(null);
        // threshold 1: the first call is allowed through, the second is not.
        ApiThrottle throttle = ApiThrottle.forTesting(1, 60);
        ApiTokenAuthFilter filter = new ApiTokenAuthFilter(() -> mgr, provider, throttle, PROBLEM_WRITER);

        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/ping");
        first.addHeader("Authorization", "Bearer rlr_same");
        FilterChain firstChain = mock(FilterChain.class);
        filter.doFilter(first, new MockHttpServletResponse(), firstChain);
        verify(firstChain).doFilter(any(), any());

        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/v1/ping");
        second.addHeader("Authorization", "Bearer rlr_same");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain secondChain = mock(FilterChain.class);

        filter.doFilter(second, response, secondChain);

        assertEquals(429, response.getStatus());
        assertEquals("application/problem+json", response.getContentType());
        JsonNode body = OBJECT_MAPPER.readTree(response.getContentAsByteArray());
        assertEquals(429, body.get("status").asInt());
        verifyNoInteractions(secondChain);
    }
}
