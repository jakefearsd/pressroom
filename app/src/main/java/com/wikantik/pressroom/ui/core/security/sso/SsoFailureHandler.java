package com.wikantik.pressroom.ui.core.security.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.wikantik.pressroom.business.WebloggerProvider;
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
 * <p>Logs one WARN line with that code and the reason (spec, Logging). The
 * reason is Roller's own refusal reason when the cause chain holds a
 * {@link ReaderSignInRefused} -- a fixed phrase that never carries an email --
 * and otherwise the OAuth2 error code, or the exception's class, followed by
 * its causes' class names ({@code AuthenticationServiceException<-IllegalStateException}),
 * so an account store failing mid-sign-in still shows up. Never an exception
 * message or the OAuth2 description: a message can carry an email (an SQL
 * error echoing a row), and whoever holds a valid {@code state} can set the
 * description through {@code error_description}. The error code itself
 * arrives on a URL anyone can type, so it is cut down to a short run of safe
 * characters. No throwable is passed to the logger.
 *
 * <p>Answers the request inside the security chain, so it releases the
 * persistence session; see {@link PersistenceSession}.
 */
public class SsoFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger("pressroom.sso");
    private static final int MAX_LOGGED_CODE = 64;

    private final WebloggerProvider webloggerProvider;

    /** @param webloggerProvider answers "is the tier up?" for the release on the way out */
    public SsoFailureHandler(WebloggerProvider webloggerProvider) {
        this.webloggerProvider = webloggerProvider;
    }

    @Override
    @SuppressFBWarnings(
            value = "UNVALIDATED_REDIRECT",
            justification = "The target is SsoPaths.loginError with one of two fixed codes, under the "
                    + "container's own context path.")
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        try {
            String oauthErrorCode = exception instanceof OAuth2AuthenticationException oauth
                    ? loggable(oauth.getError().getErrorCode())
                    : null;
            String code = ReaderOidcUserService.REFUSED.equals(oauthErrorCode)
                    ? SsoPaths.ERROR_REFUSED : SsoPaths.ERROR_FAILED;
            log.warn("Reader sign-in refused or failed: {} ({})", code, reason(exception, oauthErrorCode));
            response.sendRedirect(SsoPaths.loginError(request.getContextPath(), code));
        } finally {
            PersistenceSession.release(webloggerProvider);
        }
    }

    /**
     * @return Roller's refusal reason if the cause chain carries one, otherwise the
     *         OAuth2 error code (or the exception's class) and its causes' class names
     */
    private static String reason(AuthenticationException exception, String oauthErrorCode) {
        String head = oauthErrorCode != null ? oauthErrorCode : exception.getClass().getSimpleName();
        for (Throwable cause : CauseChain.below(exception)) {
            if (cause instanceof ReaderSignInRefused refused) {
                return head + ": " + plain(refused.reason());
            }
        }
        return head + CauseChain.causeNames(exception);
    }

    /** A Roller refusal reason is a fixed phrase; control characters are dropped all the same. */
    private static String plain(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder plain = new StringBuilder();
        text.codePoints().filter(c -> !Character.isISOControl(c)).limit(MAX_LOGGED_CODE)
                .forEach(plain::appendCodePoint);
        return plain.toString();
    }

    /** RFC 6749 error codes are short and plain; anything else is cut down to that shape. */
    private static String loggable(String errorCode) {
        String safe = errorCode.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.length() > MAX_LOGGED_CODE ? safe.substring(0, MAX_LOGGED_CODE) : safe;
    }
}
