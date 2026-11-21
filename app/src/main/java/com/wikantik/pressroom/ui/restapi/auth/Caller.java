package com.wikantik.pressroom.ui.restapi.auth;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * Who is calling, and by what credential: the one place that reads an
 * {@link Authentication}'s details to answer it.
 *
 * <p>Every mechanism leaves a different mark: the Bearer filter attaches an
 * {@link ApiPrincipal}, the Basic filter a {@link PasswordCallerDetails}
 * (see {@code SecurityConfig.apiSecurityFilterChain}), and form login and
 * reader SSO plain {@link WebAuthenticationDetails}.
 *
 * <p>Lives beside {@link ApiPrincipal} rather than in {@code ui.controllers}
 * because it names that type; {@code PressroomHandlerInterceptor} keeps
 * reading a token's pin through {@code WeblogPinnedCredential} for that
 * reason.
 *
 * @param kind how the caller authenticated
 * @param principal the token ceiling, present exactly when {@code kind} is {@link Kind#TOKEN}
 */
public record Caller(Kind kind, ApiPrincipal principal) {

    /** How a request authenticated. */
    public enum Kind {
        /** An API token (Bearer). */
        TOKEN,
        /** A password over HTTP Basic. */
        PASSWORD,
        /** An interactive login: the admin UI's form login, or reader SSO. */
        SESSION,
        /** Nobody. */
        ANONYMOUS
    }

    private static final Caller NOBODY = new Caller(Kind.ANONYMOUS, null);

    /** The caller of the current request. */
    public static Caller current() {
        return of(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * Resolves {@code auth}. An authenticated caller whose details are not
     * recognised is read as {@link Kind#PASSWORD}, the most restrictive
     * authenticated kind and never {@link Kind#SESSION}, so a login mechanism
     * added later gains no session powers until it is recognised here.
     */
    public static Caller of(Authentication auth) {
        if (auth == null) {
            return NOBODY;
        }
        Object details = auth.getDetails();
        if (details instanceof ApiPrincipal p) {
            return new Caller(Kind.TOKEN, p);
        }
        if (!auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return NOBODY;
        }
        // Exact class: PasswordCallerDetails (a subclass) is the Basic filter's mark.
        if (details != null && details.getClass() == WebAuthenticationDetails.class) {
            return new Caller(Kind.SESSION, null);
        }
        return new Caller(Kind.PASSWORD, null);
    }

    /** The handle of the one weblog a token caller is pinned to, or null. */
    public String pin() {
        return principal == null ? null : principal.scopeWeblog();
    }
}
