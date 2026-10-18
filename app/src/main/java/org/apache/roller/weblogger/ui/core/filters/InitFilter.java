/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package org.apache.roller.weblogger.ui.core.filters;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.validator.routines.UrlValidator;
import org.apache.roller.weblogger.business.VirtualHostRegistry;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;

/**
 * A special initialization filter which ensures that we have an opportunity to
 * extract a few pieces of information about the environment we are running in
 * when the first request is sent.
 *
 * <p>Takes the {@link WebloggerProvider} and the (lazy) facade so it can ask
 * the {@link VirtualHostRegistry} whether a request arrived on a custom domain
 * -- and skip that question before the tier is up (DI wave, plan Task 6b).
 *
 * @web.filter name="InitFilter"
 */
public class InitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(InitFilter.class);

    private boolean initialized = false;

    private final WebloggerProvider provider;
    private final Weblogger weblogger;

    public InitFilter(WebloggerProvider provider, Weblogger weblogger) {
        this.provider = provider;
        this.weblogger = weblogger;
    }

    /** True when the Host header names a weblog's custom domain; always false before bootstrap. */
    private boolean onACustomDomain(String hostHeader) {
        if (!provider.isBootstrapped()) {
            return false;
        }
        VirtualHostRegistry registry = weblogger.getVirtualHostRegistry();
        return registry != null && registry.handleFor(hostHeader) != null;
    }

    @Override
    @SuppressFBWarnings(
            value = "BC_UNCONFIRMED_CAST",
            justification = "This application is deployed exclusively over HTTP -- every filter is "
                    + "registered via a FilterRegistrationBean against Spring Boot's embedded Tomcat "
                    + "HTTP connector (ServletRegistrationConfig), and no other protocol connector is "
                    + "ever configured. The servlet container therefore guarantees doFilter is invoked "
                    + "only with HttpServletRequest/HttpServletResponse; the cast is unconfirmed only "
                    + "under the general, protocol-independent Filter contract, not in this deployment.")
    public void doFilter(ServletRequest req, ServletResponse res,
            FilterChain chain) throws IOException, ServletException {

        if (!initialized) {

            // first request, lets do our initialization
            HttpServletRequest request = (HttpServletRequest) req;

            // The relative url is only the context path, the same whatever
            // host the request named, so it is set even by a request that may
            // not latch the absolute url below. Left null, every $url.site in
            // a theme printed literally until a public request arrived (a LAN
            // name such as docker1.lan fails the validator), and the page
            // cache went on serving that broken render.
            WebloggerRuntimeConfig.setRelativeContextURL(request.getContextPath());

            UrlValidator validator = new UrlValidator(
                            new String[]{"http", "https"},
                            UrlValidator.ALLOW_LOCAL_URLS); // for integration tests

            // I2 (optional half): a request that arrived on a weblog's OWN
            // custom domain must never be the one that latches the site's
            // absolute context url -- that would make every domain-less
            // weblog inherit THIS weblog's hostname in its own canonical
            // url/og:url/feed id/sitemap/robots.txt/password-reset links
            // until a request on a non-custom-domain host happens to arrive
            // (which "initialized" staying false here still allows: this
            // skips latching for THIS request only, not permanently).
            if (!onACustomDomain(request.getHeader("Host"))
                    && validator.isValid(request.getRequestURL().toString())) {

                // determine absolute and relative url paths to the app
                String relPath = request.getContextPath();
                String absPath = this.getAbsoluteUrl(request);

                // set them in our config
                WebloggerRuntimeConfig.setAbsoluteContextURL(absPath);
                WebloggerRuntimeConfig.setRelativeContextURL(relPath);

                log.debug("relPath = {}", relPath);
                log.debug("absPath = {}", absPath);

                this.initialized = true;
            }

        }

        chain.doFilter(req, res);
    }

    private String getAbsoluteUrl(HttpServletRequest request) {
        return getAbsoluteUrl(request.isSecure(),
                request.getServerName(), request.getContextPath(),
                request.getRequestURI(), request.getRequestURL().toString());
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void destroy() {
    }

    protected static String getAbsoluteUrl(boolean secure, String serverName, String contextPath, String requestURI, String requestURL){

        String url;

        // Trust the container's own request URL, which is proxy-aware when
        // server.forward-headers-strategy=framework is set (Spring's
        // ForwardedHeaderFilter rewrites getScheme()/isSecure()/
        // getRequestURL() from X-Forwarded-Proto before this filter runs --
        // the production Caddy topology). The only correction needed is a
        // request that reports secure while its URL still says http, e.g. a
        // connector that sets the secure flag without rewriting the scheme.
        String fullUrl = requestURL;
        if (secure && fullUrl.startsWith("http://")) {
            fullUrl = "https://" + fullUrl.substring("http://".length());
        }

        // if the uri is only "/" then we are basically done
        if ("/".equals(requestURI)) {
            log.debug("requestURI is only '/'. fullUrl: {}", fullUrl);
            return removeTrailingSlash(fullUrl);
        }

        // find first "/" starting after hostname is specified
        int index = fullUrl.indexOf('/',
                fullUrl.indexOf(serverName));

        if (index != -1) {
            // extract just the part leading up to uri
            url = fullUrl.substring(0, index);
        } else {
            url = fullUrl.trim();
        }

        // then just add on the context path
        url += contextPath;

        // make certain that we don't end with a /
        return removeTrailingSlash(url);
    }

    protected static String removeTrailingSlash(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
