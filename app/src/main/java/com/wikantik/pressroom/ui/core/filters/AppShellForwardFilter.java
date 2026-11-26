package com.wikantik.pressroom.ui.core.filters;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Forwards a GET under {@code /pressroom-ui/app/} that is not one of the React
 * admin's own files to the shell route, so a deep link or a reload lands in the
 * app and its router takes over. Registered after Spring Security, so the
 * request is authorised (and a signed-out one sent to the login page with this
 * URL as its return path) before it is forwarded.
 *
 * <p>The files are {@code admin.js}, {@code admin.css}, their source maps and
 * {@code assets/**}; serving those as the shell would hand the browser HTML
 * for a script.
 */
public class AppShellForwardFilter implements Filter {

    static final String SHELL = "/pressroom-ui/app.rol";

    private static final String APP = "/pressroom-ui/app";
    private static final String ASSETS = APP + "/assets/";

    @Override
    @SuppressFBWarnings(
            value = "BC_UNCONFIRMED_CAST",
            justification = "Deployed only over HTTP via FilterRegistrationBean on the embedded Tomcat "
                    + "connector, so the request is an HTTP one. The forward target is the constant "
                    + "shell route, never derived from the request.")
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        String ctx = request.getContextPath() == null ? "" : request.getContextPath();
        String path = request.getRequestURI().substring(ctx.length());
        if ("GET".equals(request.getMethod()) && isAppRoute(path)) {
            request.getRequestDispatcher(SHELL).forward(req, res);
            return;
        }
        chain.doFilter(req, res);
    }

    private static boolean isAppRoute(String path) {
        if (APP.equals(path)) {
            return true;
        }
        if (!path.startsWith(APP + "/")) {
            return false;
        }
        return !path.startsWith(ASSETS) && !isBundleFile(path.substring(APP.length() + 1));
    }

    private static boolean isBundleFile(String name) {
        return "admin.js".equals(name) || "admin.css".equals(name)
                || "admin.js.map".equals(name) || "admin.css.map".equals(name);
    }
}
