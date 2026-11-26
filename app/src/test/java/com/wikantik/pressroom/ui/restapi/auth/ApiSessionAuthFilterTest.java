package com.wikantik.pressroom.ui.restapi.auth;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.User;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What {@link ApiSessionAuthFilter} accepts from a session, branch by branch. */
class ApiSessionAuthFilterTest {

    private final WebloggerProvider provider = mock(WebloggerProvider.class);
    private final UserManager users = mock(UserManager.class);
    private final Weblogger weblogger = mock(Weblogger.class);
    private final RequestMatcher refusesMcp = request -> request.getRequestURI().startsWith("/api/mcp");
    private final ApiSessionAuthFilter filter =
            new ApiSessionAuthFilter(refusesMcp, new CredentialStampFilter(provider), provider);

    @BeforeEach
    void tierIsUpAndKnowsEveryone() throws Exception {
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(weblogger);
        when(weblogger.getUserManager()).thenReturn(users);
        when(users.getUserByUserName(anyString())).thenAnswer(call -> {
            User user = new User();
            user.setUserName(call.getArgument(0));
            user.setEnabled(Boolean.TRUE);
            return user;
        });
    }

    @Test
    void aDisabledUnknownOrUnreadableAccountOrADownTierRefusesTheSession() throws Exception {
        Authentication editor = auth("editor", true);
        User disabled = new User();
        disabled.setEnabled(Boolean.FALSE);
        when(users.getUserByUserName("erin")).thenReturn(disabled);
        assertNull(seenBy(requestWith("/api/v1/me", new SecurityContextImpl(editor))), "disabled");
        when(users.getUserByUserName("erin")).thenReturn(null);
        assertNull(seenBy(requestWith("/api/v1/me", new SecurityContextImpl(editor))), "unknown");
        when(users.getUserByUserName("erin")).thenThrow(new WebloggerException("down"));
        assertNull(seenBy(requestWith("/api/v1/me", new SecurityContextImpl(editor))), "lookup failed");
        when(provider.isBootstrapped()).thenReturn(false);
        assertNull(seenBy(requestWith("/api/v1/me", new SecurityContextImpl(editor))), "tier not up");
    }

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

    /**
     * The account check fails closed on its own, not by the next filter happening to refuse too: only the
     * first lookup fails here, so a filter that read a failure as "still enabled" would let the session in.
     */
    @Test
    void aLookupThatFailsOnceRefusesTheSessionEvenIfTheNextWouldSucceed() throws Exception {
        User enabled = new User();
        enabled.setUserName("erin");
        enabled.setEnabled(Boolean.TRUE);
        when(users.getUserByUserName("erin")).thenThrow(new WebloggerException("blip")).thenReturn(enabled);

        assertNull(seenBy(requestWith("/api/v1/me", new SecurityContextImpl(auth("editor", true)))));
    }

    /** The check runs inside the security chain, so it releases the persistence session itself, success or not. */
    @Test
    void theAccountCheckReleasesThePersistenceSessionWhateverItAnswers() throws Exception {
        seenBy(requestWith("/api/v1/me", new SecurityContextImpl(auth("editor", true))));
        org.mockito.Mockito.verify(weblogger, org.mockito.Mockito.atLeastOnce()).release();

        org.mockito.Mockito.clearInvocations(weblogger);
        when(users.getUserByUserName("erin")).thenThrow(new WebloggerException("down"));
        seenBy(requestWith("/api/v1/me", new SecurityContextImpl(auth("editor", true))));
        org.mockito.Mockito.verify(weblogger, org.mockito.Mockito.atLeastOnce()).release();
    }
}
