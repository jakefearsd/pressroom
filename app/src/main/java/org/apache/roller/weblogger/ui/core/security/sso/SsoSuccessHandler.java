package org.apache.roller.weblogger.ui.core.security.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Sends a signed-in reader back to the page that started the sign-in.
 *
 * <p>Spring has already changed the session id (its fixation defence) by the
 * time this runs. The return path is taken from the session and removed, and
 * checked again on the way out although only {@link SsoStartFilter} ever
 * writes it. Logs one INFO line with the Roller username and whether the
 * account is new; never the email or a token.
 *
 * <p>Answers the request inside the security chain, so it releases the
 * persistence session the account lookup used; see {@link PersistenceSession}.
 */
public class SsoSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger("roller.sso");

    private final WebloggerProvider webloggerProvider;

    /** @param webloggerProvider answers "is the tier up?" for the release on the way out */
    public SsoSuccessHandler(WebloggerProvider webloggerProvider) {
        this.webloggerProvider = webloggerProvider;
    }

    @Override
    @SuppressFBWarnings(
            value = "UNVALIDATED_REDIRECT",
            justification = "The target is the context root or a return path ReturnPaths.sanitize has "
                    + "confined to a path under the context: no scheme, no host, no // or /\\ prefix, no "
                    + "control character.")
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        try {
            String contextPath = request.getContextPath();
            String target = contextPath + "/";
            HttpSession session = request.getSession(false);
            if (session != null) {
                Object stored = session.getAttribute(ReturnPaths.SESSION_ATTRIBUTE);
                session.removeAttribute(ReturnPaths.SESSION_ATTRIBUTE);
                if (stored instanceof String path) {
                    target = ReturnPaths.sanitize(path, contextPath);
                }
            }
            boolean newAccount = authentication.getPrincipal() instanceof RollerOidcUser reader
                    && reader.isNewAccount();
            log.info("Reader {} signed in ({} account)", authentication.getName(), newAccount ? "new" : "existing");
            response.sendRedirect(target);
        } finally {
            PersistenceSession.release(webloggerProvider);
        }
    }
}
