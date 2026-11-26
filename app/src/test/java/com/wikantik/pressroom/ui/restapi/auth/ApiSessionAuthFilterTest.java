package com.wikantik.pressroom.ui.restapi.auth;

import java.util.List;

import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.ui.core.security.CredentialStampFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** What {@link ApiSessionAuthFilter} accepts from a session, branch by branch. */
class ApiSessionAuthFilterTest {

    private final RequestMatcher refusesMcp = request -> request.getRequestURI().startsWith("/api/mcp");
    private final ApiSessionAuthFilter filter =
            new ApiSessionAuthFilter(refusesMcp, new CredentialStampFilter(mock(WebloggerProvider.class)));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication auth(String authority, boolean withDetails) {
        UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken.authenticated(
                "erin", null, authority == null ? List.of() : List.of(new SimpleGrantedAuthority(authority)));
        if (withDetails) {
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(new MockHttpServletRequest()));
        }
        return auth;
    }

    private static MockHttpServletRequest requestWith(String path, Object sessionContext) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, sessionContext);
        request.setSession(session);
        return request;
    }

    /** The authentication the chain's next filter sees. */
    private Authentication seenBy(MockHttpServletRequest request) throws Exception {
        Authentication[] seen = new Authentication[1];
        FilterChain chain = (req, res) -> seen[0] = SecurityContextHolder.getContext().getAuthentication();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return seen[0];
    }

    @Test
    void anEditorOrAdminSessionIsCopiedIntoTheRequestContext() throws Exception {
        Authentication editor = auth("editor", true);
        assertEquals(editor, seenBy(requestWith("/api/v1/me", new SecurityContextImpl(editor))));
        Authentication admin = auth("admin", true);
        assertEquals(admin, seenBy(requestWith("/api/v1/me", new SecurityContextImpl(admin))));
    }

    @Test
    void aReaderANoAuthorityAnAnonymousAndAnUnrecognisedSessionStayAnonymous() throws Exception {
        for (Authentication refused : List.of(auth("reader", true), auth(null, true),
                auth("editor", false), new TestingAuthenticationToken("erin", "x", "editor"),
                new AnonymousAuthenticationToken("k", "anon", AuthorityUtils.createAuthorityList("editor")),
                UsernamePasswordAuthenticationToken.unauthenticated("erin", "x"))) {
            assertNull(seenBy(requestWith("/api/v1/me", new SecurityContextImpl(refused))), refused.toString());
        }
    }

    @Test
    void aSessionWithNoSecurityContextOrNoSessionAtAllStaysAnonymous() throws Exception {
        assertNull(seenBy(requestWith("/api/v1/me", "not a context")));
        assertNull(seenBy(requestWith("/api/v1/me", new SecurityContextImpl())));
        MockHttpServletRequest noSession = new MockHttpServletRequest("GET", "/api/v1/me");
        assertNull(seenBy(noSession));
        assertNull(noSession.getSession(false), "never creates a session");
    }

    @Test
    void anAuthorizationHeaderOfAnyKindMeansTheSessionIsNotConsulted() throws Exception {
        MockHttpServletRequest request = requestWith("/api/v1/me", new SecurityContextImpl(auth("editor", true)));
        request.addHeader("Authorization", "Bearer prs_x");
        assertNull(seenBy(request));
    }

    @Test
    void aRefusedPathNeverTakesTheSession() throws Exception {
        assertNull(seenBy(requestWith("/api/mcp", new SecurityContextImpl(auth("editor", true)))));
    }

    @Test
    void mayUseApiRefusesNull() {
        assertFalse(ApiSessionAuthFilter.mayUseApi(null));
        assertTrue(ApiSessionAuthFilter.mayUseApi(auth("editor", true)));
    }
}
