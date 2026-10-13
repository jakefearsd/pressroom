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
package org.apache.roller.weblogger.ui.rendering;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.business.WeblogRedirectManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogRedirect;

/**
 * The one implementation of the redirect consultation: resolve a
 * weblog-relative path against the redirect rules, and on a match answer
 * with a 301 whose Location derives from the WEBLOG -- never from the
 * request -- plus best-effort hit bookkeeping and one diagnostic log line.
 *
 * <p>The fail-closed property lives in the CALLERS, not here: this runs only
 * at code points where a 404 has already been decided (two in
 * {@code PageServlet}, two-plus-the-decline in {@code WeblogRequestMapper}),
 * so a rule can never shadow live content. Do not add a call site that has
 * not already decided to 404 -- that is the one way to break the design.
 * Spec: {@code docs/superpowers/specs/2026-08-24-url-redirects-design.md}.
 */
// Two loggers on purpose: the second is the shared redirect audit log, named for grepping.
@SuppressWarnings("PMD.MoreThanOneLogger")
public class RedirectResponder {

    private static final Logger log = LoggerFactory.getLogger(RedirectResponder.class);

    /** One INFO line per served redirect, greppable by the shared name. */
    private static final Logger redirectLog =
            LoggerFactory.getLogger(WeblogRedirectManager.LOG_NAME);

    private final Weblogger weblogger;

    public RedirectResponder(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    /**
     * Answer this would-404 request with a 301 if a rule matches.
     *
     * <p>A redirect is a favor granted on top of a settled outcome, so every
     * failure here -- the store unreachable, no weblog root to build a
     * Location from -- degrades to the 404 the caller already decided,
     * never to a 500.
     *
     * @param weblogRelativePath the path with handle, context path and query
     *        string already removed -- the form rules are stored in.
     * @return true when a 301 was written and the caller must not 404.
     */
    public boolean answer(Weblog weblog, String weblogRelativePath,
            HttpServletRequest request, HttpServletResponse response) {

        WeblogRedirect rule;
        try {
            rule = weblogger.getWeblogRedirectManager()
                    .resolve(weblog, weblogRelativePath);
        } catch (Exception ex) {
            log.warn("Could not consult redirect rules for weblog {} path {}",
                    weblog.getHandle(), weblogRelativePath, ex);
            return false;
        }
        if (rule == null) {
            return false;
        }

        // The Location derives from the weblog, never the request: the same
        // single method all weblog-content urls root through, so custom
        // domains and the servlet context path are inherited rather than
        // reimplemented (the vhost wave found three hand-built urls that each
        // dropped the context path -- this must not be the fourth).
        String root = weblogger.getUrlStrategy().getWeblogURL(weblog, null, true);
        if (root == null || root.isBlank()) {
            log.warn("No weblog root url for {}; serving the 404 instead of rule {}",
                    weblog.getHandle(), rule.getId());
            return false;
        }
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }

        String query = originalQueryString(request);
        String location = root + rule.getTargetPath()
                + (query != null ? "?" + query : "");

        response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
        response.setHeader("Location", location);

        weblogger.getWeblogRedirectManager().recordHit(rule);

        // Referer and User-Agent are attacker-controlled display text; they
        // pass through parameterized slots and nothing may follow them.
        redirectLog.info(
                "served: weblog={} rule={} origin={} uri={} -> {} referer={} userAgent={}",
                weblog.getHandle(), rule.getId(), rule.getOrigin(),
                originalUriWithQuery(request), location,
                request.getHeader("Referer"), request.getHeader("User-Agent"));

        return true;
    }

    /**
     * The query string the READER sent. Inside a forward the request's own
     * uri/query name the internal servlet path, and the original spelling
     * lives in the forward attributes.
     */
    private static String originalQueryString(HttpServletRequest request) {
        if (request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI) != null) {
            return (String) request.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING);
        }
        return request.getQueryString();
    }

    private static String originalUriWithQuery(HttpServletRequest request) {
        Object forwardUri = request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
        String uri = forwardUri != null ? forwardUri.toString() : request.getRequestURI();
        String query = originalQueryString(request);
        return query != null ? uri + "?" + query : uri;
    }
}
