package com.wikantik.pressroom.ui.core.filters;

import java.io.IOException;
import java.util.Optional;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Answers a GET (or HEAD) for a legacy admin route that has moved to the React admin with a 302 to its
 * React route, as {@link LegacyAdminRedirects} tabulates. Registered after Spring Security, so the old URL
 * keeps its authorisation: a signed-out visitor is sent to sign in and the saved request is the old URL,
 * which then redirects here. Other methods are not touched; a POST to a migrated screen has no handler
 * any more and answers as an unknown route does.
 */
public class LegacyAdminRedirectFilter implements Filter {

    @Override
    @SuppressFBWarnings(
            value = {"BC_UNCONFIRMED_CAST", "UNVALIDATED_REDIRECT"},
            justification = "Deployed only over HTTP via FilterRegistrationBean on the embedded Tomcat "
                    + "connector, so the request and response are HTTP ones. The Location is the request's "
                    + "own context path, a fixed prefix, a route from the table and percent-encoded "
                    + "parameter values, so it is host-relative and cannot leave the site.")
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            chain.doFilter(req, res);
            return;
        }
        String ctx = request.getContextPath() == null ? "" : request.getContextPath();
        String path = request.getRequestURI().substring(ctx.length());
        Optional<String> target = LegacyAdminRedirects.locate(ctx, path, request::getParameter);
        if (target.isEmpty()) {
            chain.doFilter(req, res);
            return;
        }
        HttpServletResponse response = (HttpServletResponse) res;
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", target.get());
    }
}
