package com.wikantik.pressroom.ui.restapi.auth;

import java.io.IOException;

import com.wikantik.pressroom.ui.core.security.CredentialStampFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a request to {@code /api} that carries no {@code Authorization} header
 * authenticate from the admin UI's existing session (spec
 * 2026-11-25-react-admin-migration-design, A3; SA-18 rules 1 and 2).
 *
 * <p>Read-only on the session: it looks the session up with
 * {@code getSession(false)} and copies the signed-in {@link Authentication}
 * into this request's context. It never creates a session, never writes to
 * one, and runs no remember-me, so the API sets no cookie.
 *
 * <p>What it accepts, fail closed:
 * <ul>
 *   <li>no {@code Authorization} header at all: a request that names a
 *       credential is judged on that credential alone, so a bad Bearer token
 *       is a 401 and never falls back to the session;</li>
 *   <li>not a path the {@code refused} matcher names ({@code /api/mcp}: bearer
 *       only);</li>
 *   <li>an authentication {@link Caller} recognises as {@link Caller.Kind#SESSION}
 *       (form login, remember-me and single sign-on all leave one);</li>
 *   <li>holding {@code admin} or {@code editor}. An SSO reader holds neither,
 *       so it stays anonymous and gets the 401 an anonymous caller gets.</li>
 * </ul>
 *
 * <p>An accepted session then goes through {@link CredentialStampFilter}, so
 * a session whose password has changed ends here as it does on the UI chain.
 * That filter is run only for a session caller: it compares the context's
 * user with the session's, which would be wrong for a Bearer caller who also
 * sends a cookie.
 */
public class ApiSessionAuthFilter extends OncePerRequestFilter {

    private final RequestMatcher refused;
    private final CredentialStampFilter credentialStamp;

    /**
     * @param refused requests that never take a session, whatever the cookie
     * @param credentialStamp ends a session whose password changed
     */
    public ApiSessionAuthFilter(RequestMatcher refused, CredentialStampFilter credentialStamp) {
        this.refused = refused;
        this.credentialStamp = credentialStamp;
    }

    /** Whether {@code auth} is the kind of caller a session may vouch for on {@code /api}. */
    static boolean mayUseApi(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || Caller.of(auth).kind() != Caller.Kind.SESSION) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if ("admin".equals(authority.getAuthority()) || "editor".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = request.getHeader("Authorization") == null && !refused.matches(request)
                ? fromSession(request) : null;
        if (mayUseApi(auth)) {
            SecurityContextHolder.getContext().setAuthentication(auth);
            credentialStamp.doFilter(request, response, chain);
            return;
        }
        chain.doFilter(request, response);
    }

    private static Authentication fromSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object held = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return held instanceof SecurityContext context ? context.getAuthentication() : null;
    }
}
