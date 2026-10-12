package org.apache.roller.weblogger.ui.core.security.sso;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Supplier;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code GET /roller-ui/sso/start?return=<path>}: the first step of reader sign-in.
 *
 * <p>A security-chain filter rather than a controller: {@code DispatcherServlet}
 * maps {@code *.rol} only, so it would never see this path. Spring's own
 * authorization endpoint, which this redirects to, is a filter for the same
 * reason.
 *
 * <p>It checks that sign-in can complete before anything else happens. With
 * no Absolute URL there is no callback the provider would accept, and with no
 * client registration there is no provider; either answers
 * {@code ?sso=unavailable} and redirects to no provider. Only then does it
 * store the sanitised return path in the session and hand over to
 * {@link SsoPaths#AUTHORIZE_BASE}.
 *
 * <p>Every request this filter answers ends inside the security chain, so it
 * releases the persistence session itself; see {@link PersistenceSession}.
 */
public class SsoStartFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("roller.sso");

    private final Supplier<String> absoluteUrl;
    private final ClientRegistrationRepository registrations;
    private final WebloggerProvider webloggerProvider;

    /**
     * @param absoluteUrl       the configured Absolute URL, read per sign-in (never the request's host)
     * @param registrations     the provider registration; resolving it may fetch discovery
     * @param webloggerProvider answers "is the tier up?" for the release on the way out
     */
    public SsoStartFilter(Supplier<String> absoluteUrl, ClientRegistrationRepository registrations,
            WebloggerProvider webloggerProvider) {
        this.absoluteUrl = absoluteUrl;
        this.registrations = registrations;
        this.webloggerProvider = webloggerProvider;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"GET".equals(request.getMethod())
                || !SsoPaths.START.equals(SsoPaths.pathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contextPath = request.getContextPath();
        try {
            String unavailable = unavailableReason();
            if (unavailable != null) {
                log.warn("Reader sign-in refused or failed: {} ({})", SsoPaths.ERROR_UNAVAILABLE, unavailable);
                response.sendRedirect(SsoPaths.loginError(contextPath, SsoPaths.ERROR_UNAVAILABLE));
                return;
            }
            request.getSession().setAttribute(ReturnPaths.SESSION_ATTRIBUTE,
                    ReturnPaths.sanitize(request.getParameter("return"), contextPath));
            response.sendRedirect(contextPath + SsoPaths.AUTHORIZE_BASE + "/" + SsoSettings.REGISTRATION_ID);
        } finally {
            PersistenceSession.release(webloggerProvider);
        }
    }

    /**
     * @return why sign-in cannot complete, or null when it can. A check that
     *         throws has not passed: it answers unavailable too.
     */
    private String unavailableReason() {
        try {
            String absolute = absoluteUrl.get();
            if (absolute == null || absolute.isBlank()) {
                return "absolute_url_unset";
            }
            if (registrations.findByRegistrationId(SsoSettings.REGISTRATION_ID) == null) {
                return "registration_unresolved";
            }
            return null;
        } catch (RuntimeException ex) {
            // The class name only: the message could carry configuration or provider text.
            return ex.getClass().getSimpleName();
        }
    }
}
