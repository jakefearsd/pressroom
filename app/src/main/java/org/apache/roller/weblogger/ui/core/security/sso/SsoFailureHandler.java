package org.apache.roller.weblogger.ui.core.security.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Sends a failed or refused sign-in to the login page with {@code ?sso=<code>}.
 *
 * <p>{@link ReaderOidcUserService#REFUSED} becomes {@code refused}; everything
 * else -- a forged or missing {@code state}, a failed code exchange, an
 * invalid ID token, the provider's own error answer, the account store
 * failing -- becomes {@code failed} (spec, Errors).
 *
 * <p>Logs one WARN line with that code and the OAuth2 error code, nothing
 * more: an exception's message and an error's description can carry provider
 * text, and the error code itself arrives on a URL anyone can type, so it is
 * cut down to a short run of safe characters before it reaches the log.
 *
 * <p>Answers the request inside the security chain, so it releases the
 * persistence session; see {@link PersistenceSession}.
 */
public class SsoFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger("roller.sso");
    private static final int MAX_LOGGED_CODE = 64;

    private final WebloggerProvider webloggerProvider;

    /** @param webloggerProvider answers "is the tier up?" for the release on the way out */
    public SsoFailureHandler(WebloggerProvider webloggerProvider) {
        this.webloggerProvider = webloggerProvider;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        try {
            String oauthErrorCode = exception instanceof OAuth2AuthenticationException oauth
                    ? loggable(oauth.getError().getErrorCode())
                    : exception.getClass().getSimpleName();
            String code = ReaderOidcUserService.REFUSED.equals(oauthErrorCode)
                    ? SsoPaths.ERROR_REFUSED : SsoPaths.ERROR_FAILED;
            log.warn("Reader sign-in refused or failed: {} ({})", code, oauthErrorCode);
            response.sendRedirect(SsoPaths.loginError(request.getContextPath(), code));
        } finally {
            PersistenceSession.release(webloggerProvider);
        }
    }

    /** RFC 6749 error codes are short and plain; anything else is cut down to that shape. */
    private static String loggable(String errorCode) {
        String safe = errorCode.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.length() > MAX_LOGGED_CODE ? safe.substring(0, MAX_LOGGED_CODE) : safe;
    }
}
