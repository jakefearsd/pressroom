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

package com.wikantik.pressroom.ui.rendering;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.business.VirtualHostRegistry;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.LocaleSegment;


/**
 * Handles rendering requests for Roller pages/feeds by routing to the appropriate Servlet.
 *
 * This request mapper is used to map all weblog specific urls of the form
 * /<weblog handle>/* to the appropriate servlet for handling the actual
 * request. It is constructed by {@code RequestMappingFilter} with the
 * {@link WebloggerProvider} and the facade (DI wave, plan Task 6b): before the
 * tier is up it declines every weblog-shaped url rather than touching the
 * facade, exactly as the static locator used to throw into the same
 * {@code catch}.
 *
 * TODO: we should try and make this class easier to extend and build upon
 */
public class WeblogRequestMapper {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogRequestMapper.class);
    
    private static final String PAGE_SERVLET = "/pressroom-ui/rendering/page";
    private static final String FEED_SERVLET = "/pressroom-ui/rendering/feed";
    private static final String RESOURCE_SERVLET = "/pressroom-ui/rendering/resources";
    private static final String MEDIA_SERVLET = "/pressroom-ui/rendering/media-resources";
    private static final String SEARCH_SERVLET = "/pressroom-ui/rendering/search";

    /**
     * Where one public weblog url shape is forwarded, and which of its parts
     * survive the trip.
     *
     * <p>This replaced a nine-label switch whose every arm rebuilt the same
     * {@code SERVLET/handle[/locale][/context][/data]} assembly by hand. The
     * arms differed only in the servlet and in these three booleans, but with
     * the assembly written out nine times that was close to unreadable -- and
     * the reason a reader could not tell, for instance, that a resource url
     * deliberately drops the locale while a feed url keeps it.
     *
     * @param servlet     servlet path to forward to
     * @param withLocale  carry the locale segment, when the request has one
     * @param withContext keep the context word itself in the path
     * @param withData    carry the trailing data segment, when there is one
     */
    private record ForwardTarget(String servlet, boolean withLocale,
                                 boolean withContext, boolean withData) {
    }

    /** No context word at all: the weblog's own home page. */
    private static final ForwardTarget HOME =
            new ForwardTarget(PAGE_SERVLET, true, false, false);

    /**
     * An unreserved first segment with nothing after it -- a static page slug.
     * WeblogPageRequest resolves it against WeblogPageManager and 404s itself,
     * drafts included, exactly as an unknown "page"/"entry" name does.
     */
    private static final ForwardTarget PAGE_SLUG =
            new ForwardTarget(PAGE_SERVLET, true, true, false);

    private static final Map<String, ForwardTarget> FORWARD_TARGETS = Map.of(
            "page",          new ForwardTarget(PAGE_SERVLET, true, true, true),
            "entry",         new ForwardTarget(PAGE_SERVLET, true, true, true),
            "date",          new ForwardTarget(PAGE_SERVLET, true, true, true),
            "category",      new ForwardTarget(PAGE_SERVLET, true, true, true),
            "tags",          new ForwardTarget(PAGE_SERVLET, true, true, true),
            "feed",          new ForwardTarget(FEED_SERVLET, true, false, true),
            "resource",      new ForwardTarget(RESOURCE_SERVLET, false, false, true),
            "mediaresource", new ForwardTarget(MEDIA_SERVLET, false, false, true),
            "search",        new ForwardTarget(SEARCH_SERVLET, true, false, false));

    /**
     * The contexts the site space answers under its handle: its assets. Its
     * pages live at {@code /<slug>}, but the url strategy builds the theme
     * stylesheet ({@code page/}), theme resources ({@code resource/}) and
     * media files ({@code mediaresource/}) as {@code /site-space/<context>/...}
     * because they reuse the per-weblog asset routes. Every other context
     * (entry, feed, search, category, tags, date) has nothing behind it for
     * the site, so it stays a 404.
     */
    private static final Set<String> SITE_ASSET_CONTEXTS = Set.of("page", "resource", "mediaresource");


    // url patterns that are not allowed to be considered weblog handles
    Set<String> restricted;

    // Reserved on a custom domain. See appProtectedUrls' comment in
    // pressroom.properties for why this is a strict subset of `restricted`.
    Set<String> appRestricted;

    private final WebloggerProvider provider;
    private final Weblogger weblogger;
    private final RedirectResponder redirectResponder;


    public WeblogRequestMapper(WebloggerProvider provider, Weblogger weblogger) {

        this.provider = provider;
        this.weblogger = weblogger;
        this.redirectResponder = new RedirectResponder(weblogger);
        this.restricted = new HashSet<>();

        // build pressroom restricted list
        String restrictList =
                WebloggerConfig.getProperty("rendering.weblogMapper.pressroomProtectedUrls");
        if(restrictList != null && !restrictList.isBlank()) {
            String[] restrict = restrictList.split(",");
            this.restricted.addAll(Arrays.asList(restrict));
        }

        // add user restricted list
        restrictList =
                WebloggerConfig.getProperty("rendering.weblogMapper.userProtectedUrls");
        if(restrictList != null && !restrictList.isBlank()) {
            String[] restrict = restrictList.split(",");
            this.restricted.addAll(Arrays.asList(restrict));
        }

        this.appRestricted = new HashSet<>();

        // build app-level restricted list -- the subset reserved on a
        // custom domain (see appProtectedUrls' comment in pressroom.properties)
        String appRestrictList =
                WebloggerConfig.getProperty("rendering.weblogMapper.appProtectedUrls");
        if(appRestrictList != null && !appRestrictList.isBlank()) {
            String[] restrict = appRestrictList.split(",");
            this.appRestricted.addAll(Arrays.asList(restrict));
        }

        // add user restricted list -- app-level by definition, so it applies
        // on a custom domain too
        restrictList =
                WebloggerConfig.getProperty("rendering.weblogMapper.userProtectedUrls");
        if(restrictList != null && !restrictList.isBlank()) {
            String[] restrict = restrictList.split(",");
            this.appRestricted.addAll(Arrays.asList(restrict));
        }
    }
    
    
    /**
     * Inspect the request and, if it is a weblog url, forward it to the right
     * rendering servlet. Returns {@code true} when the request was handled
     * (forwarded or redirected) and the filter chain must not continue.
     */
    @SuppressFBWarnings(
            value = "UNVALIDATED_REDIRECT",
            justification = "Both Locations stay on a fixed host: https:// plus the weblog's own custom "
                    + "domain from VirtualHostRegistry, or the context path plus a handle isWeblog() has "
                    + "just found stored and a locale segment LocaleSegment.isLocale admits only at two or "
                    + "five characters (on a custom domain the locale comes first). A segment holds no "
                    + "slash, and a backslash, which a browser would read as one, never arrives: Tomcat "
                    + "refuses it in a path with a 400. The request path only ever follows that host or a "
                    + "context-relative /.")
    public boolean handleRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        
        String weblogHandle = null;
        String weblogLocale = null;
        String weblogRequestContext = null;
        String weblogRequestData = null;
        
        log.debug("evaluating [{}]", request.getRequestURI());

        // Host-first resolution. A weblog that owns a hostname supplies its own
        // handle, so the ENTIRE path is weblog-relative and the first segment is
        // content (a page slug, a context) rather than a handle. Everything
        // after this block -- locale detection, context/data splitting, the
        // trailing-slash rules, the forward url -- is identical either way,
        // which is the whole reason resolution lives here rather than in a
        // second mapper that would have to reimplement it.
        String vhostHandle = vhostHandleFor(request.getHeader("Host"));

        NormalizedPath normalized =
                normalizePath(request.getRequestURI(), request.getContextPath());
        if (normalized == null) {
            return false;
        }
        String servlet = normalized.path();
        // kinda silly, but we need to keep track of whether or not the url had
        // a trailing slash so that we can act accordingly
        boolean trailingSlash = normalized.trailingSlash();
        String pathInfo = null;

        // The first path segment, whatever it turns out to mean. On a custom
        // domain it is content; otherwise it is the candidate weblog handle.
        int firstSlash = servlet.indexOf('/');
        String firstSegment = firstSlash < 0 ? servlet : servlet.substring(0, firstSlash);

        if (vhostHandle != null) {
            weblogHandle = vhostHandle;
            pathInfo = servlet.isEmpty() ? null : servlet;
            if (servlet.isEmpty()) {
                // The custom domain's root IS the weblog home and is already the
                // canonical url. There is no shorter form to redirect to, so
                // suppress the trailing-slash redirect for this case ONLY --
                // suppressing it for every vhost request would make the 404
                // branch below swallow every permalink.
                trailingSlash = true;
            }
        } else if (!servlet.isEmpty()) {
            weblogHandle = firstSegment;
            if (firstSlash != -1) {
                pathInfo = servlet.substring(firstSlash + 1);
            }
        }

        log.debug("potential weblog handle = {}", weblogHandle);

        // The protected-url list applies in BOTH modes, but which list differs.
        // On the site host a context is always the SECOND segment
        // (/<handle>/page/x), so the full `restricted` set (application paths
        // PLUS weblog request contexts) is tested against the first segment,
        // which is always the candidate handle. On a custom domain the whole
        // path is weblog-relative and the first segment is content -- a
        // context there is the FIRST segment (/page/x) -- so only
        // APPLICATION paths are reserved: /themes/**, /webjars/**,
        // /pressroom-ui/rendering/**, /newsletter/**, /robots.txt and
        // /sitemap.xml still must not be swallowed, but reserving the weblog
        // request contexts here would kill /page/<theme>.css (the theme
        // stylesheet on every single page), /search and /resource/<file> on
        // every vhost page. Only the isWeblog() half is skippable: a
        // host-resolved handle is a weblog by construction.
        Set<String> reserved = (vhostHandle != null) ? appRestricted : restricted;
        if (reserved.contains(firstSegment)) {
            log.debug("SKIPPED {}", firstSegment);
            return false;
        }
        if (vhostHandle == null && Weblog.SITE_SPACE_HANDLE.equals(weblogHandle)) {
            return forwardedAsSiteAsset(pathInfo, trailingSlash, request, response);
        }
        if (vhostHandle == null && !this.isWeblog(weblogHandle)) {
            // Not a blog: on the site host a lone segment may be a site page,
            // /<slug>. Asked only now, after the cheaper checks have failed and
            // a blog has had its claim (a blog handle wins over a site slug),
            // and never on a custom domain, where /<slug> is that blog's Page.
            if (firstSlash < 0 && !servlet.isEmpty() && !trailingSlash) {
                return answeredAsSitePage(firstSegment, request, response);
            }
            log.debug("SKIPPED {}", weblogHandle);
            return false;
        }

        // The custom domain is canonical: a weblog that has one is reachable at
        // exactly one address per page, and any other host permanently
        // redirects there. Absolute by necessity -- this crosses hosts -- and
        // it STILL needs the context path: this is one Tomcat context reached
        // under many hostnames, so a deployment under a prefix (e.g. /pressroom)
        // has that prefix on the custom domain too. pathInfo was stripped of
        // it above (line ~139), so it has to be added back here rather than
        // assumed away the way the container-internal FORWARD below can.
        // Redirect precisely when the weblog has a hostname and THIS request
        // did not arrive on it. vhostHandle != null means the host already
        // resolved the weblog, i.e. we are on the canonical domain already.
        String canonicalHost = vhostHostFor(weblogHandle);
        if (vhostHandle == null && canonicalHost != null) {
            StringBuilder target = new StringBuilder("https://").append(canonicalHost);
            target.append(request.getContextPath());
            if (pathInfo != null) {
                target.append('/').append(pathInfo);
            }
            if (trailingSlash && (pathInfo == null || !pathInfo.endsWith("/"))) {
                target.append('/');
            }
            if (request.getQueryString() != null) {
                target.append('?').append(request.getQueryString());
            }
            response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            response.setHeader("Location", target.toString());
            return true;
        }

        log.debug("WEBLOG_URL {}", request.getServletPath());
        
        // parse the rest of the url and build forward url
        if(pathInfo != null) {
            WeblogPathInfo parsed = parsePathInfo(pathInfo);
            weblogLocale = parsed.locale();
            weblogRequestContext = parsed.context();
            weblogRequestData = parsed.data();
        }
        
        // special handling for trailing slash issue
        // we need this because by http standards the urls /foo and /foo/ are
        // supposed to be considered different, so we must enforce that
        if(weblogRequestContext == null && !trailingSlash) {
            // this means someone referred to a weblog index page with the
            // shortest form of url /<weblog> or /<weblog>/<locale> and we need
            // to do a redirect to /<weblog>/ or /<weblog>/<locale>/
            //
            // Two things here are easy to get wrong, and both were:
            //
            // 1. The context path has to be on the front. Unlike the FORWARD
            //    below -- which is dispatched inside the container and is
            //    therefore context-relative -- this location goes back to the
            //    browser, and a leading-slash location is resolved against the
            //    SERVER root, not the application root (Servlet 6.1,
            //    sendRedirect). Deployed under /pressroom, omitting it sent every
            //    reader of /pressroom/<weblog> to /<weblog>, which 404s. The bug
            //    is invisible at the root context, which is now the default --
            //    hence the test at both contexts.
            // 2. The locale has to survive. The comment above always claimed
            //    /<weblog>/<locale>/ was a redirect target, but the url was
            //    built from the handle alone, so /<weblog>/de quietly landed on
            //    the weblog's default-locale home instead of the German one.
            // 3. In vhost mode there is no handle segment at all -- the host
            //    already supplies it (see the "Host-first resolution" comment
            //    above) -- so appending weblogHandle here would leak the
            //    handle back into a user-visible url on exactly the host this
            //    feature exists to remove it from, and the resulting
            //    /<contextPath>/<handle>/<locale>/ url is not one this mapper
            //    (or WeblogRequestMapper in vhost mode) can ever resolve: it
            //    404s.
            StringBuilder redirectUrl = new StringBuilder(request.getContextPath()).append('/');
            if (vhostHandle == null) {
                redirectUrl.append(weblogHandle).append('/');
            }
            if(weblogLocale != null) {
                redirectUrl.append(weblogLocale).append('/');
            }
            if(request.getQueryString() != null) {
                redirectUrl.append('?').append(request.getQueryString());
            }

            response.sendRedirect(redirectUrl.toString());
            return true;
            
        } else if(weblogRequestContext != null &&
                "tags".equals(weblogRequestContext)) {
            // tags section can have an index page at /<weblog>/tags/ and
            // a tags query at /<weblog>/tags/tag1+tag2, buth that's it
            if((weblogRequestData == null && !trailingSlash) ||
                    (weblogRequestData != null && trailingSlash)) {
                // A decided 404 -- one of the consultation seams where a
                // redirect rule may answer instead (see RedirectResponder).
                if (answeredByRedirect(weblogHandle, pathInfo, request, response)) {
                    return true;
                }
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return true;
            }
        } else if(weblogRequestContext != null && trailingSlash) {
            // this means that someone has accessed a weblog url and included
            // a trailing slash, like /<weblog>/entry/<anchor>/ which is not
            // supported, so we need to offer up a 404 Not Found
            if (answeredByRedirect(weblogHandle, pathInfo, request, response)) {
                return true;
            }
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return true;
        }

        // calculate forward url
        String forwardUrl = calculateForwardUrl(request, weblogHandle, weblogLocale,
                weblogRequestContext, weblogRequestData);

        // if we don't have a forward url then the request was invalid somehow.
        // This decline is the seam that serves the site-migration case
        // (/2019/05/old-post.html under a handle or on a vhost): the weblog is
        // resolved by this point and nothing downstream serves anything under
        // a weblog's namespace -- the protected roots returned above before
        // ever reaching here -- so a declined weblog-shaped url is a 404 in
        // practice, and a redirect rule may answer it. The spec calls this the
        // one seam where "would 404" holds by argument rather than by
        // construction, which is why RedirectServingTest pins the no-rule
        // decline as well as the rule-answered 301.
        if(forwardUrl == null) {
            return answeredByRedirect(weblogHandle, pathInfo, request, response);
        }
        
        // dispatch to forward url
        log.debug("forwarding to {}", forwardUrl);
        RequestDispatcher dispatch = request.getRequestDispatcher(forwardUrl);
        dispatch.forward(request, response);
        
        // we dealt with this request ourselves, so return "true"
        return true;
    }

    
    /**
     * The servlet forward url for one parsed weblog request, or null when the
     * request is not one this mapper serves.
     *
     * <p>{@code handle} is always assumed valid; every other parameter may be
     * null. Which parts of the url survive is decided by {@link ForwardTarget},
     * not here.
     *
     * <p>Package private so the forward-url table can be exercised directly.
     * The alternative is driving every case through handleRequest, which needs
     * a weblog lookup and a virtual-host registry standing behind it to reach a
     * pure string-building decision.
     */
    String calculateForwardUrl(HttpServletRequest request,
                               String handle, String locale,
                               String context, String data) {

        log.debug("{},{},{},{}", handle, locale, context, data);

        // POST used to be routed here only for comment submission -- the
        // permalink, carrying a "content" param, forwarded to the comment
        // servlet. That servlet is gone with the comment subsystem, and
        // nothing else in the public url space accepts a POST, so every POST
        // is declined and falls through to the next filter/servlet.
        if ("POST".equals(request.getMethod())) {
            return null;
        }

        ForwardTarget target = (context == null) ? HOME : FORWARD_TARGETS.get(context);

        if (target == null) {
            // Every reserved first-segment word is a key in FORWARD_TARGETS, so
            // anything reaching here is either a static-page slug
            // (/<handle>/<slug>, nothing after it) or unsupported
            // (/<handle>/<foo>/<bar>). A second path segment is never part of a
            // page slug, so that second kind is not a weblog url at all.
            if (data != null) {
                return null;
            }
            target = PAGE_SLUG;
        }

        StringBuilder forwardUrl = new StringBuilder(64);
        forwardUrl.append(target.servlet()).append('/').append(handle);
        if (target.withLocale() && locale != null) {
            forwardUrl.append('/').append(locale);
        }
        if (target.withContext()) {
            forwardUrl.append('/').append(context);
        }
        if (target.withData() && data != null) {
            forwardUrl.append('/').append(data);
        }

        log.debug("FORWARD_URL {}", forwardUrl);

        return forwardUrl.toString();
    }
    
    
    /**
     * The registry on the bootstrapped tier, or null before bootstrap -- a
     * request that arrives that early (installation.type=manual, no database
     * yet) is simply not on a custom domain, and touching the lazy facade
     * would try to build the tier instead of answering.
     */
    private VirtualHostRegistry registry() {
        return provider.isBootstrapped() ? weblogger.getVirtualHostRegistry() : null;
    }

    private String vhostHandleFor(String hostHeader) {
        VirtualHostRegistry registry = registry();
        return registry == null ? null : registry.handleFor(hostHeader);
    }

    private String vhostHostFor(String handle) {
        VirtualHostRegistry registry = registry();
        return registry == null ? null : registry.hostFor(handle);
    }

    /**
     * Consult the redirect rules for a request this mapper has decided it
     * cannot serve. Called ONLY where a 404 (or the equivalent decline of a
     * resolved weblog url) is already decided -- adding a call site that has
     * not decided that is how a rule gets to shadow live content.
     *
     * @param pathInfo the weblog-relative path in this mapper's own form:
     *        after the handle on the site host, the whole path on a custom
     *        domain, no leading slash, {@code null} for the weblog root.
     */
    private boolean answeredByRedirect(String weblogHandle, String pathInfo,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            Weblog weblog = weblogger.getWeblogManager()
                    .getWeblogByHandle(weblogHandle);
            if (weblog == null) {
                return false;
            }
            String path = pathInfo == null ? "/" : "/" + pathInfo;
            return redirectResponder.answer(weblog, path, request, response);
        } catch (Exception ex) {
            // A favor that could not be looked up is not a favor owed: the
            // 404/decline this seam had already decided proceeds unchanged.
            log.warn("Could not consult redirect rules for weblog {}",
                    weblogHandle, ex);
            return false;
        }
    }

    /**
     * Serve {@code /<segment>} as a site page when the site space has a
     * PUBLISHED page with that slug. A draft holds its slug against blogs but
     * is not served: it declines here and 404s like any unknown path, rather
     * than reaching the servlet -- or, as the front door's page, answering a
     * permanent 301 to a root that shows the directory. The front door's own
     * page has one address, {@code /}, so its slug answers 301 there. POST is
     * never forwarded (see {@link #calculateForwardUrl}).
     *
     * <p>A lookup that fails is a decline -- the 404 this url was before site
     * pages -- never a 500.
     *
     * @return true when the request was forwarded or redirected
     */
    @SuppressFBWarnings(
            value = "UNVALIDATED_REDIRECT",
            justification = "The Location is the request's own context path plus '/', and the "
                    + "original query string after a '?': it never leaves this host or this "
                    + "application's root.")
    private boolean answeredAsSitePage(String segment, HttpServletRequest request,
            HttpServletResponse response) throws ServletException, IOException {

        if (!provider.isBootstrapped() || "POST".equals(request.getMethod())) {
            return false;
        }
        String slug;
        try {
            slug = URLDecoder.decode(segment, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        try {
            if (!FrontDoor.isPublishedSitePage(weblogger, slug)) {
                return false;
            }
        } catch (Exception ex) {
            log.warn("Could not look up site page {}; declining it", segment, ex);
            return false;
        }

        if (WebloggerRuntimeConfig.isFrontDoorPage(slug)) {
            StringBuilder root = new StringBuilder(request.getContextPath()).append('/');
            if (request.getQueryString() != null) {
                root.append('?').append(request.getQueryString());
            }
            response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            response.setHeader("Location", root.toString());
            return true;
        }

        // The segment as it came: WeblogPageRequest decodes it, as it does a
        // blog Page's slug.
        String forwardUrl = PAGE_SERVLET + '/' + Weblog.SITE_SPACE_HANDLE + '/' + segment;
        log.debug("forwarding site page to {}", forwardUrl);
        request.getRequestDispatcher(forwardUrl).forward(request, response);
        return true;
    }

    /**
     * Serve {@code /site-space/<context>/<data>} when the context is one of
     * the site space's asset contexts, forwarding exactly as a blog's asset
     * url is forwarded. Anything else under the handle -- the bare handle, a
     * slug, a locale, a trailing slash, any other context -- declines and
     * 404s: the handle is never a public address for the site's pages. The
     * servlets serve the site space only to this FORWARD dispatch.
     *
     * @return true when the request was forwarded
     */
    private boolean forwardedAsSiteAsset(String pathInfo, boolean trailingSlash,
            HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        if (!provider.isBootstrapped() || pathInfo == null || trailingSlash) {
            return false;
        }
        WeblogPathInfo parsed = parsePathInfo(pathInfo);
        if (parsed.locale() != null || parsed.data() == null
                || !SITE_ASSET_CONTEXTS.contains(parsed.context())) {
            return false;
        }
        String forwardUrl = calculateForwardUrl(request, Weblog.SITE_SPACE_HANDLE, null,
                parsed.context(), parsed.data());
        if (forwardUrl == null) {
            return false;
        }
        log.debug("forwarding site asset to {}", forwardUrl);
        request.getRequestDispatcher(forwardUrl).forward(request, response);
        return true;
    }

    /**
     * convenience method which determines if the given string is a valid
     * weblog handle.
     */
    private boolean isWeblog(String potentialHandle) {

        log.debug("checking weblog handle {}", potentialHandle);

        boolean isWeblog = false;

        // Before bootstrap there is no manager to ask, and the answer is the
        // same one the locator's IllegalStateException used to fall into below.
        if (!provider.isBootstrapped()) {
            return false;
        }

        try {
            Weblog weblog = weblogger.getWeblogManager()
                    .getWeblogByHandle(potentialHandle);
            
            // The site space is the home of the site's own pages, never a blog
            // to render at /site-space: it falls through to a 404.
            if(weblog != null && !weblog.isSiteSpace()) {
                isWeblog = true;
            }
        } catch(Exception ignored) {
            // Any exception here (bad handle format, a manager not yet
            // ready) means this is not a valid weblog handle -- isWeblog
            // stays false either way, which is all this caller checks.
        }
        
        return isWeblog;
    }
    
    
    /**
     * A request path with the deployment's context path and its outer slashes
     * removed, plus whether it had a trailing one.
     *
     * <p>The trailing slash is kept rather than discarded because http treats
     * {@code /foo} and {@code /foo/} as different resources, and this mapper
     * redirects the first to the second for a weblog home page.
     */
    record NormalizedPath(String path, boolean trailingSlash) {
    }

    /**
     * Strips the context path and the outer slashes from a request uri.
     *
     * <p>Extracted from handleRequest for its complexity. Pure and package
     * private so it can be tested at both context paths, which matters: this
     * repo has already shipped a bug from assuming the root context, and a
     * deployment under a prefix exercises the substring below rather than
     * skipping it.
     *
     * @return null when there is no uri to work with, which the caller treats
     *         as "not a request for this mapper"
     */
    static NormalizedPath normalizePath(String requestUri, String contextPath) {

        if (requestUri == null) {
            return null;
        }

        String path = requestUri;
        if (contextPath != null) {
            // the container guarantees the uri starts with the context path
            path = path.substring(contextPath.length());
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        boolean trailingSlash = path.endsWith("/");
        if (trailingSlash) {
            path = path.substring(0, path.length() - 1);
        }

        return new NormalizedPath(path, trailingSlash);
    }


    /**
     * The three things a weblog-relative path can carry, once the leading
     * locale segment (if any) has been told apart from the context.
     *
     * @param locale  the locale segment, or null when the path did not open
     *                with one
     * @param context the request context -- "entry", "feed", a page slug, and
     *                so on
     * @param data    everything after the context, reassembled with its
     *                separators intact, or null when there was none
     */
    record WeblogPathInfo(String locale, String context, String data) {
    }

    /**
     * Splits {@code [locale/]<context>/<data>} into its parts.
     *
     * <p>Extracted from handleRequest, which had this inline and was CC 42.
     * Pure and package private so the splitting can be tested for what it is:
     * whether a first segment is a locale decides what every later segment
     * means, and getting it wrong silently reinterprets the whole url.
     *
     * <p>Note the reassembly in the no-locale case. The split is capped at
     * three parts on the assumption that the first is a locale; when it turns
     * out not to be, the path has been split one time too many and the last
     * two parts have to be glued back together, separator included, or a
     * permalink like {@code entry/2005/my-post} loses its slash.
     */
    WeblogPathInfo parsePathInfo(String pathInfo) {

        // we expect [locale/]<context>/<extra>/<info>
        String[] urlPath = pathInfo.split("/", 3);

        if (LocaleSegment.isLocale(urlPath[0])) {
            String locale = urlPath[0];
            if (urlPath.length == 2) {
                return new WeblogPathInfo(locale, urlPath[1], null);
            }
            if (urlPath.length == 3) {
                return new WeblogPathInfo(locale, urlPath[1], urlPath[2]);
            }
            // a bare locale and nothing else
            return new WeblogPathInfo(locale, null, null);
        }

        if (urlPath.length == 2) {
            return new WeblogPathInfo(null, urlPath[0], urlPath[1]);
        }
        if (urlPath.length == 3) {
            return new WeblogPathInfo(null, urlPath[0], urlPath[1] + "/" + urlPath[2]);
        }
        return new WeblogPathInfo(null, urlPath[0], null);
    }


    
}
