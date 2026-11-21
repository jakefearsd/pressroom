package com.wikantik.pressroom.ui.restapi.auth;

import java.util.List;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.restapi.ApiProblemWriter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The caller kind is recognised from the authentication each mechanism
 * really produces: the Bearer filter, the Basic filter with the marker
 * details source, form login, OAuth2 login, and no authentication at all.
 */
class CallerTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theBearerFilterProducesATokenCallerCarryingThePrincipal() throws Exception {
        User user = new User();
        user.setUserName("agent");
        ApiToken token = new ApiToken();
        token.setUser(user);
        token.setScopeWeblog("blog");
        token.setScopeRole(ApiToken.Role.POST);
        ApiTokenManager mgr = mock(ApiTokenManager.class);
        when(mgr.authenticate("prs_x")).thenReturn(token);
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(mock(Weblogger.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Authorization", "Bearer prs_x");

        new ApiTokenAuthFilter(() -> mgr, provider, new ApiProblemWriter(new ObjectMapper()))
                .doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        Caller caller = Caller.current();
        assertEquals(Caller.Kind.TOKEN, caller.kind());
        assertEquals("blog", caller.principal().scopeWeblog());
        assertEquals("blog", caller.pin());
    }

    @Test
    void theBasicFilterWithTheMarkerSourceProducesAPasswordCaller() throws Exception {
        AuthenticationManager manager = mock(AuthenticationManager.class);
        when(manager.authenticate(any())).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
        BasicAuthenticationFilter filter = new BasicAuthenticationFilter(manager);
        filter.setAuthenticationDetailsSource(PasswordCallerDetails.SOURCE);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tokens");
        request.addHeader("Authorization", "Basic YWxpY2U6c2VjcmV0");

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        Caller caller = Caller.current();
        assertEquals(Caller.Kind.PASSWORD, caller.kind());
        assertNull(caller.principal());
        assertNull(caller.pin());
    }

    @Test
    void formLoginIsASession() {
        UsernamePasswordAuthenticationToken auth =
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(new MockHttpServletRequest()));
        assertEquals(Caller.Kind.SESSION, Caller.of(auth).kind());
    }

    @Test
    void oauth2LoginIsASession() {
        var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("reader")),
                java.util.Map.of("sub", "s"), "sub");
        OAuth2AuthenticationToken auth = new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(new MockHttpServletRequest()));
        assertEquals(Caller.Kind.SESSION, Caller.of(auth).kind());
    }

    @Test
    void noAuthenticationAnAnonymousOneOrAnUnauthenticatedOneIsAnonymous() {
        assertEquals(Caller.Kind.ANONYMOUS, Caller.current().kind());
        assertEquals(Caller.Kind.ANONYMOUS, Caller.of(null).kind());
        assertEquals(Caller.Kind.ANONYMOUS, Caller.of(new AnonymousAuthenticationToken(
                "k", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))).kind());
        assertEquals(Caller.Kind.ANONYMOUS, Caller.of(
                UsernamePasswordAuthenticationToken.unauthenticated("a", "b")).kind());
    }

    /**
     * The fail-closed rule: an authenticated Authentication whose details this
     * class does not recognise is never a SESSION, so a future login mechanism
     * cannot silently gain session powers; it reads as the most restrictive
     * authenticated kind, PASSWORD.
     */
    @Test
    void unrecognisedDetailsOnAnAuthenticatedCallerNeverResolveToSession() {
        TestingAuthenticationToken noDetails = new TestingAuthenticationToken("alice", "x", "r");
        assertEquals(Caller.Kind.PASSWORD, Caller.of(noDetails).kind());

        TestingAuthenticationToken odd = new TestingAuthenticationToken("alice", "x", "r");
        odd.setDetails(new Object());
        assertEquals(Caller.Kind.PASSWORD, Caller.of(odd).kind());
    }

    @Test
    void currentReadsTheSecurityContext() {
        Authentication auth = new TestingAuthenticationToken("alice", "x", "r");
        SecurityContextHolder.getContext().setAuthentication(auth);
        assertSame(Caller.Kind.PASSWORD, Caller.current().kind());
    }
}
