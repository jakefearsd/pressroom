package com.wikantik.pressroom.ui.restapi.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

/**
 * The details the HTTP Basic filter attaches to the Authentication it
 * establishes. Form login, reader SSO and Basic all produce plain
 * {@link WebAuthenticationDetails} by default, so a password caller cannot be
 * told from a session by inference; this subclass marks it positively. Still a
 * {@code WebAuthenticationDetails}, so anything reading the remote address or
 * session id is unaffected. {@link Caller} is the only reader of the marker.
 */
public final class PasswordCallerDetails extends WebAuthenticationDetails {

    /** Handed to {@code httpBasic(...).authenticationDetailsSource(...)}. */
    public static final AuthenticationDetailsSource<HttpServletRequest, PasswordCallerDetails> SOURCE =
            PasswordCallerDetails::new;

    private static final long serialVersionUID = 1L;

    private PasswordCallerDetails(HttpServletRequest request) {
        super(request);
    }
}
