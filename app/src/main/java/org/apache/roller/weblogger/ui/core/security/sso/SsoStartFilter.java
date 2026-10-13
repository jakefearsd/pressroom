package org.apache.roller.weblogger.ui.core.security.sso;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Supplier;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
 * <p><b>A sign-in starts on the Absolute URL's host.</b> The return path and
 * Spring's authorization request live in the session of the host the browser
 * used, and the provider always sends the browser back to the Absolute URL's
 * host. Started on any other ({@code http://docker2.lan:8080} for
 * {@code https://blog.wikantik.com}), the callback would find a session holding
 * neither, and the sign-in could never complete. So when the request's scheme,
 * host or port is not the Absolute URL's (a default port is the same as none),
 * this redirects to {@code <Absolute URL>/roller-ui/sso/start?return=<the
 * sanitised return>} before touching the session, and the start runs again
 * there. The request's scheme, host and port are what the trusted proxy
 * reported ({@code server.forward-headers-strategy=framework}); behind a proxy
 * that does not send {@code X-Forwarded-Proto}, the public host looks like
 * another host to itself. An Absolute URL with no scheme or host cannot be
 * compared, and answers unavailable.
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
    @SuppressFBWarnings(
            value = "UNVALIDATED_REDIRECT",
            justification = "Both targets are fixed: the authorization path under the context path, or "
                    + "(notHere) the login page or the configured Absolute URL's own start path, carrying "
                    + "a return path that ReturnPaths.sanitize has confined and URLEncoder has encoded.")
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contextPath = request.getContextPath();
        try {
            String returnPath = ReturnPaths.sanitize(request.getParameter("return"), contextPath);
            String elsewhere = notHere(request, returnPath);
            if (elsewhere != null) {
                response.sendRedirect(elsewhere);
                return;
            }
            request.getSession().setAttribute(ReturnPaths.SESSION_ATTRIBUTE, returnPath);
            response.sendRedirect(contextPath + SsoPaths.AUTHORIZE_BASE + "/" + SsoSettings.REGISTRATION_ID);
        } finally {
            PersistenceSession.release(webloggerProvider);
        }
    }

    /**
     * @return where to send the browser instead of the provider -- the login page
     *         when sign-in cannot complete, or this start on the Absolute URL's
     *         host -- or null to go ahead. A check that throws has not passed: it
     *         answers unavailable too.
     */
    private String notHere(HttpServletRequest request, String returnPath) {
        String contextPath = request.getContextPath();
        try {
            String absolute = absoluteUrl.get();
            if (absolute == null || absolute.isBlank()) {
                return unavailable(contextPath, "absolute_url_unset");
            }
            URI canonical = URI.create(absolute.trim());
            if (canonical.getScheme() == null || canonical.getHost() == null) {
                return unavailable(contextPath, "absolute_url_invalid");
            }
            if (!isSameOrigin(request, canonical)) {
                return SsoPaths.onAbsoluteUrl(absolute, SsoPaths.START)
                        + "?return=" + URLEncoder.encode(returnPath, StandardCharsets.UTF_8);
            }
            if (registrations.findByRegistrationId(SsoSettings.REGISTRATION_ID) == null) {
                return unavailable(contextPath, "registration_unresolved");
            }
            return null;
        } catch (RuntimeException ex) {
            // The class name only: the message could carry configuration or provider text.
            return unavailable(contextPath, ex.getClass().getSimpleName());
        }
    }

    private static String unavailable(String contextPath, String reason) {
        log.warn("Reader sign-in refused or failed: {} ({})", SsoPaths.ERROR_UNAVAILABLE, reason);
        return SsoPaths.loginError(contextPath, SsoPaths.ERROR_UNAVAILABLE);
    }

    /** @return true when the request's scheme, host and port are the Absolute URL's */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Compares the request's scheme and host with the configured Absolute URL, "
                    + "whose host java.net.URI parses only when ASCII. The request's values come through "
                    + "Tomcat, which decodes headers as ISO-8859-1, and no character in U+0080 to U+00FF "
                    + "equalsIgnoreCase-matches an ASCII one (checked over the whole range on 2026-10-12).")
    private static boolean isSameOrigin(HttpServletRequest request, URI canonical) {
        String scheme = canonical.getScheme().toLowerCase(Locale.ROOT);
        return scheme.equalsIgnoreCase(request.getScheme())
                && canonical.getHost().equalsIgnoreCase(request.getServerName())
                && portOrDefault(scheme, canonical.getPort()) == portOrDefault(scheme, request.getServerPort());
    }

    /** @return {@code port}, or the scheme's default when there is none (80 for http, 443 for https) */
    private static int portOrDefault(String scheme, int port) {
        if (port > 0) {
            return port;
        }
        return "https".equals(scheme) ? 443 : 80;
    }
}
