package org.apache.roller.weblogger.ui.core.security.sso;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The reader sign-in URLs, each relative to the context path, and the
 * {@code ?sso=} codes the login page understands.
 */
public final class SsoPaths {

    /** Starts a sign-in; takes {@code ?return=}. */
    public static final String START = "/roller-ui/sso/start";
    /** Spring's authorization endpoint, moved; the registration id follows. */
    public static final String AUTHORIZE_BASE = "/roller-ui/sso/authorize";
    /** Where the provider sends the reader back. */
    public static final String CALLBACK = "/roller-ui/sso/callback";
    /** The login page, which shows the {@code ?sso=} message. */
    public static final String LOGIN_PAGE = "/roller-ui/login.rol";

    /** Sign-in is off, the Absolute URL is unset, or discovery is unreachable. */
    public static final String ERROR_UNAVAILABLE = "unavailable";
    /** State, code exchange, ID token or the provider's own answer failed. */
    public static final String ERROR_FAILED = "failed";
    /** Roller declined the sign-in: no usable email, or the account is disabled. */
    public static final String ERROR_REFUSED = "refused";

    private SsoPaths() {
    }

    /** @return the login page under {@code contextPath}, carrying {@code ?sso=code} */
    public static String loginError(String contextPath, String code) {
        return contextPath + LOGIN_PAGE + "?sso=" + code;
    }

    /** @return the request path without the context path */
    static String pathWithinApplication(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
