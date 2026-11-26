package com.wikantik.pressroom.ui.restapi.auth;

import java.io.IOException;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.core.security.CredentialStampFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>It looks the session up with {@code getSession(false)} and copies the
 * signed-in {@link Authentication} into this request's context. It never
 * creates a session and runs no remember-me, so the API sets no cookie. It
 * writes to an existing session only through {@link CredentialStampFilter},
 * which may set its password-stamp attributes (and ends it when the password
 * changed).
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
 *   <li>an account the business tier still finds enabled, as the token path
 *       requires of a token's owner; a disabled or deleted account, a failed
 *       lookup, or a tier that is not up refuses the session (fail closed);</li>
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

    private static final Logger log = LoggerFactory.getLogger(ApiSessionAuthFilter.class);

    private final RequestMatcher refused;
    private final CredentialStampFilter credentialStamp;
    private final WebloggerProvider webloggerProvider;

    /**
     * @param refused requests that never take a session, whatever the cookie
     * @param credentialStamp ends a session whose password changed
     * @param webloggerProvider asked whether the account is still enabled
     */
    public ApiSessionAuthFilter(RequestMatcher refused, CredentialStampFilter credentialStamp,
                                WebloggerProvider webloggerProvider) {
        this.refused = refused;
        this.credentialStamp = credentialStamp;
        this.webloggerProvider = webloggerProvider;
    }

    /** Whether the account behind {@code auth} still exists and is enabled; false on any doubt. */
    private boolean stillEnabled(Authentication auth) {
        if (!webloggerProvider.isBootstrapped()) {
            return false;
        }
        Weblogger weblogger = webloggerProvider.getWeblogger();
        try {
            User user = weblogger.getUserManager().getUserByUserName(auth.getName());
            return user != null && Boolean.TRUE.equals(user.getEnabled());
        } catch (WebloggerException | RuntimeException e) {
            log.warn("Could not check the account of {}; refusing its session on the API", auth.getName(), e);
            return false;
        } finally {
            // Inside the security chain: a request answered here never reaches
            // PersistenceSessionFilter (see CredentialStampFilter).
            weblogger.release();
        }
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
        if (mayUseApi(auth) && stillEnabled(auth)) {
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
