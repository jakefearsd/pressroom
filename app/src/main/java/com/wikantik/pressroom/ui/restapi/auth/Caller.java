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
 * (see {@code SecurityConfig.apiTokenResourceSecurityFilterChain}), and form login and
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
        ANONYMOUS,
        /**
         * Authenticated by a mechanism this class does not recognise. Never
         * {@link #SESSION}, so a future login mechanism gains no session powers;
         * the guards (AdminAccountGuard, TokensApi) refuse it, as they
         * refuse TOKEN. No such caller exists today.
         */
        UNRECOGNISED
    }

    private static final Caller NOBODY = new Caller(Kind.ANONYMOUS, null);

    /** The caller of the current request. */
    public static Caller current() {
        return of(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * Resolves {@code auth}. An authenticated caller whose details are not
     * recognised is {@link Kind#UNRECOGNISED}, never {@link Kind#SESSION},
     * so a login mechanism added later gains no session powers until it is
     * recognised here. PASSWORD is recognised positively, by
     * {@link PasswordCallerDetails}.
     */
    public static Caller of(Authentication auth) {
        if (auth == null) {
            return NOBODY;
        }
        // Judged before the details (0.16.3): token details on an Authentication
        // that is not authenticated make nobody. The token filter only ever sets
        // an authenticated one (CallerKindConfinementTest), so no answer moved.
        if (!auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return NOBODY;
        }
        Object details = auth.getDetails();
        if (details instanceof ApiPrincipal p) {
            return new Caller(Kind.TOKEN, p);
        }
        if (details instanceof PasswordCallerDetails) {
            return new Caller(Kind.PASSWORD, null);
        }
        // Exact class: PasswordCallerDetails (a subclass) was handled above.
        if (details != null && details.getClass() == WebAuthenticationDetails.class) {
            return new Caller(Kind.SESSION, null);
        }
        return new Caller(Kind.UNRECOGNISED, null);
    }
}
