package com.wikantik.pressroom.ui.core.filters;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Sends the admin UI's old path to its new one with a permanent redirect:
 * {@code /roller-ui/...} answers 301 (GET, HEAD) or 308 (anything else, so a
 * POST keeps its method) with {@code Location} at {@code /pressroom-ui/...},
 * query string and context path kept. Registered ahead of Spring Security so
 * the redirect needs no session and no authentication. The login processing
 * URL is deliberately not redirected.
 */
public class LegacyUiRedirectFilter implements Filter {

    private static final String OLD_PREFIX = "/roller-ui"; // rebrand:keep
    private static final String NEW_PREFIX = "/pressroom-ui";

    @Override
    @SuppressFBWarnings(
            value = {"BC_UNCONFIRMED_CAST", "UNVALIDATED_REDIRECT"},
            justification = "Deployed only over HTTP via FilterRegistrationBean on the embedded Tomcat "
                    + "connector, so the request and response are HTTP ones. The Location is the "
                    + "request's own context path plus a fixed prefix plus the rest of its own path, "
                    + "which is host-relative and begins with a single slash-led segment, so it "
                    + "cannot leave the site.")
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        String ctx = request.getContextPath() == null ? "" : request.getContextPath();
        String path = request.getRequestURI().substring(ctx.length());

        boolean legacy = OLD_PREFIX.equals(path) || path.startsWith(OLD_PREFIX + "/");
        if (!legacy) {
            chain.doFilter(req, res);
            return;
        }
        String method = request.getMethod();
        int status = "GET".equals(method) || "HEAD".equals(method)
                ? HttpServletResponse.SC_MOVED_PERMANENTLY : 308;
        String query = request.getQueryString();
        String location = ctx + NEW_PREFIX + path.substring(OLD_PREFIX.length())
                + (query == null || query.isEmpty() ? "" : "?" + query);
        HttpServletResponse response = (HttpServletResponse) res;
        response.setStatus(status);
        response.setHeader("Location", location);
    }
}
