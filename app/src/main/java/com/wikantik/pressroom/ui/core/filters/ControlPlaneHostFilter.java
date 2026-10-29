package com.wikantik.pressroom.ui.core.filters;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.business.VirtualHostRegistry;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;

/**
 * Sends admin and automation-API requests that arrived on a weblog's custom
 * domain back to the site host, so there is one session cookie, one login and
 * one API base url however many weblogs exist.
 *
 * <p>Registered at filter order 35 -- ahead of the Spring Security chain (40),
 * so an unauthenticated admin request is moved to the right host before
 * security can mint a session and a CSRF token on the wrong one. That is also
 * ahead of {@code BootstrapFilter} (50): this filter sees requests before the
 * business tier is up, which is why it takes the {@link WebloggerProvider} and
 * asks {@code isBootstrapped()} before touching the {@link VirtualHostRegistry}
 * on the (lazy) facade -- pre-bootstrap the answer is "no custom host", never
 * an attempt to build the tier (DI wave, plan Task 6b).
 */
public class ControlPlaneHostFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneHostFilter.class);

    /** The public rendering namespace, which must stay on the custom domain. */
    private static final String PUBLIC_RENDERING = "/roller-ui/rendering/";

    /**
     * The readers' content bundle (code highlighting, copy buttons, heading
     * anchors), loaded by every theme's content-asset macro. A public asset
     * that lives under /roller-ui only because the webapp root is the one
     * static location this deployment serves (see app/frontend/build.mjs).
     * Redirected, it would be cross-origin, and every theme's CSP is
     * {@code script-src 'self'}. One file, not the directory: the editor's
     * bundle beside it is control plane like the rest of /roller-ui.
     */
    private static final String READER_CONTENT_BUNDLE = "/roller-ui/scripts/roller-content.js";

    private final WebloggerProvider provider;
    private final Weblogger weblogger;

    public ControlPlaneHostFilter(WebloggerProvider provider, Weblogger weblogger) {
        this.provider = provider;
        this.weblogger = weblogger;
    }

    /** The weblog handle a Host header resolves to, or null -- always null before bootstrap. */
    private String customDomainHandle(String hostHeader) {
        if (!provider.isBootstrapped()) {
            return null;
        }
        VirtualHostRegistry registry = weblogger.getVirtualHostRegistry();
        return registry == null ? null : registry.handleFor(hostHeader);
    }

    /**
     * True when this path is control plane and belongs on the site host.
     * Package-private-plus for the test; the ordering of the two /roller-ui
     * checks is the whole rule.
     */
    public static boolean belongsToSiteHost(String path) {
        if (path == null) {
            return false;
        }
        if (path.startsWith(PUBLIC_RENDERING) || READER_CONTENT_BUNDLE.equals(path)) {
            return false;
        }
        return path.startsWith("/roller-ui/") || "/roller-ui".equals(path)
                || path.startsWith("/api/") || "/api".equals(path);
    }

    @Override
    @SuppressFBWarnings(
            value = {"BC_UNCONFIRMED_CAST", "UNVALIDATED_REDIRECT"},
            justification = "This application is deployed exclusively over HTTP -- every filter is "
                    + "registered via a FilterRegistrationBean against Spring Boot's embedded Tomcat "
                    + "HTTP connector (ServletRegistrationConfig), and no other protocol connector is "
                    + "ever configured. The servlet container therefore guarantees doFilter is invoked "
                    + "only with HttpServletRequest/HttpServletResponse; the cast is unconfirmed only "
                    + "under the general, protocol-independent Filter contract, not in this deployment. "
                    + "The redirect's Location is site.absoluteurl (operator configuration) plus the "
                    + "request's own path, which belongsToSiteHost has just required to start with "
                    + "/roller-ui/ or /api/, so it cannot leave the site host.")
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();
        if (request.getContextPath() != null && !request.getContextPath().isEmpty()) {
            path = path.substring(request.getContextPath().length());
        }

        if (belongsToSiteHost(path)
                && customDomainHandle(request.getHeader("Host")) != null) {

            String siteUrl = siteHostUrl();
            if (siteUrl == null) {
                // No host-independent statement of where the control plane
                // lives. Redirecting to getAbsoluteContextURL() here would use
                // whatever InitFilter latched from the first request after
                // boot -- which under virtual hosts can be a custom domain,
                // producing an infinite redirect. Degrade to pre-vhost
                // behaviour instead: serve the request.
                log.warn("A weblog has a custom domain but site.absoluteurl is unset; "
                        + "serving {} on the custom domain rather than "
                        + "redirecting. Set site.absoluteurl to enable the "
                        + "control-plane boundary.", path);
                chain.doFilter(req, res);
                return;
            }

            StringBuilder target = new StringBuilder(siteUrl).append(path);
            if (request.getQueryString() != null) {
                target.append('?').append(request.getQueryString());
            }
            // Permanent, so browsers cache it: a wrong site.absoluteurl keeps sending an admin to the old host
            // until the browser's cache is cleared. Change that setting deliberately and check it from a fresh
            // browser profile.
            response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            response.setHeader("Location", target.toString());
            return;
        }

        chain.doFilter(req, res);
    }

    /**
     * The site host, read from site.absoluteurl DIRECTLY (database row, then
     * PRESSROOM_SITE_ABSOLUTEURL) and never through getAbsoluteContextURL(),
     * which falls back to InitFilter's latched value.
     */
    private static String siteHostUrl() {
        String configured = WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl");
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return configured.endsWith("/")
                ? configured.substring(0, configured.length() - 1)
                : configured;
    }
}
