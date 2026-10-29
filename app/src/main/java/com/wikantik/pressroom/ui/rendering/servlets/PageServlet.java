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

package com.wikantik.pressroom.ui.rendering.servlets;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.util.PressroomConstants;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.StaticThemeTemplate;
import com.wikantik.pressroom.pojos.TemplateRendition.TemplateLanguage;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.ui.core.PressroomContext;
import com.wikantik.pressroom.ui.rendering.RedirectResponder;
import com.wikantik.pressroom.ui.rendering.util.ModDateHeaderUtil;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.ui.rendering.util.cache.RenderCache;
import com.wikantik.pressroom.ui.rendering.util.cache.RenderCaches;
import com.wikantik.pressroom.util.cache.CachedContent;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.jsp.JspFactory;
import jakarta.servlet.jsp.PageContext;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides access to weblog pages.
 */
public class PageServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(PageServlet.class);

    /**
     * Request attributes a 404 decided inside a weblog leaves for the
     * container's error page (roller-ui/errors/404.jsp), which offers the
     * reader the way back to that weblog rather than only the site root.
     * The error dispatch is a forward within the same request, so they
     * survive it. The name is raw author input: the JSP escapes it.
     */
    static final String NOT_FOUND_WEBLOG_NAME = "roller.notFound.weblogName";
    static final String NOT_FOUND_WEBLOG_URL = "roller.notFound.weblogUrl";
    // for caching
    private boolean excludeOwnerPages = false;
    private transient RenderCache<WeblogPageRequest> siteWideRenderCache = null;
    private transient RenderCache<WeblogPageRequest> weblogRenderCache = null;

    // Development theme reloading
    Boolean themeReload = false;

    private final transient Weblogger weblogger;
    private final transient RedirectResponder redirectResponder;

    /**
     * Constructed by {@code ServletRegistrationConfig} with the (lazily
     * resolved) business-tier facade; there is no default constructor on
     * purpose, so the dependency is visible at the one place this servlet is
     * built.
     */
    public PageServlet(Weblogger weblogger) {
        this.weblogger = weblogger;
        this.redirectResponder = new RedirectResponder(weblogger);
    }

    /**
     * Consult the redirect rules for a request this servlet has decided to
     * 404. Called ONLY after that decision -- the ordering is what makes a
     * rule unable to shadow live content.
     */
    private boolean answeredByRedirect(WeblogPageRequest pageRequest, Weblog weblog,
            HttpServletRequest request, HttpServletResponse response) {
        String pathInfo = pageRequest.getPathInfo();
        String path = pathInfo == null ? "/" : "/" + pathInfo;
        return redirectResponder.answer(weblog, path, request, response);
    }

    /**
     * Init method for this servlet
     */
    @Override
    public void init(ServletConfig servletConfig) throws ServletException {

        super.init(servletConfig);

        log.info("Initializing PageServlet");

        this.excludeOwnerPages = WebloggerConfig
                .getBooleanProperty("cache.excludeOwnerEditPages");

        // one RenderCache per side of the site-wide question; which one a
        // request uses is decided once, in doGet
        this.siteWideRenderCache = RenderCaches.forPage(true);
        this.weblogRenderCache = RenderCaches.forPage(false);

        // Development theme reloading
        themeReload = WebloggerConfig.getBooleanProperty("themes.reload.mode");
    }

    /**
     * Handle GET requests for weblog pages.
     */
    // The CachedContent returned by RenderingServletUtils.renderAndFlush() is
    // already flushed and closed internally before it comes back -- nothing
    // left for this method to close. (The cache-hit object has its own
    // justification, on servedFromCache below.)
    @SuppressWarnings("PMD.CloseResource")
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        log.debug("Entering");

        Weblog weblog;
        boolean isSiteWide;

        WeblogPageRequest pageRequest;
        try {
            pageRequest = new WeblogPageRequest(weblogger, request);

            weblog = pageRequest.getWeblog();
            if (weblog == null) {
                throw new WebloggerException("unable to lookup weblog: "
                        + pageRequest.getWeblogHandle());
            }
            // The site space is reached only through a container forward --
            // WeblogRequestMapper's /<slug>, index.jsp's / -- never by its
            // internal handle at this servlet's own path.
            if (weblog.isSiteSpace() && request.getDispatcherType() != DispatcherType.FORWARD) {
                throw new WebloggerException("the site space is not served by its handle");
            }

            // is this the site-wide weblog?
            isSiteWide = WebloggerRuntimeConfig.isSiteWideWeblog(pageRequest
                    .getWeblogHandle());
        } catch (Exception e) {
            // some kind of error parsing the request or looking up weblog
            log.debug("error creating page request", e);
            RenderingServletUtils.sendNotFound(response);
            return;
        }

        // the site-wide question is asked once, here, and answered by holding
        // the cache it selects for the rest of the request
        RenderCache<WeblogPageRequest> renderCache =
                isSiteWide ? siteWideRenderCache : weblogRenderCache;

        // determine the lastModified date for this content
        long lastModified = renderCache.lastModified(weblog);

        // 304 Not Modified handling.
        // We skip this for logged in users to avoid the scenerio where a user
        // views their weblog, logs in, then gets a 304 without the 'edit' links
        if (!pageRequest.isLoggedIn()) {
            if (ModDateHeaderUtil.respondIfNotModified(request, response,
                    lastModified)) {
                return;
            } else {
                // set last-modified date
                ModDateHeaderUtil.setLastModifiedHeader(response, lastModified);
            }
        }

        // generate cache key
        String cacheKey = renderCache.generateKey(pageRequest);

        // Development only. Reload if theme has been modified
        if (themeReload
                && !WeblogTheme.CUSTOM.equals(weblog.getEditorTheme())
                && (pageRequest.getPathInfo() == null || pageRequest
                        .getPathInfo() != null
                        && !pageRequest.getPathInfo().endsWith(".css"))) {
            RenderingServletUtils.reloadThemeFromDisk(weblog, renderCache,
                    weblogger.getThemeManager());
        }

        if (servedFromCache(request, response, pageRequest, renderCache,
                cacheKey, lastModified)) {
            return;
        }

        log.debug("Looking for template to use for rendering");

        // figure out what template to use
        ThemeTemplate page = selectTemplate(pageRequest, weblog, weblogger.getThemeManager());
        if (page == null) {
            // A decided 404 -- one of the redirect consultation seams. The
            // ordering IS the safety property: a rule is only ever asked
            // about a request nothing serves, so it cannot shadow live
            // content (see RedirectResponder).
            if (answeredByRedirect(pageRequest, weblog, request, response)) {
                return;
            }
            sendNotFoundInWeblog(request, response, weblog);
            return;
        }

        log.debug("page found, dealing with it");

        // validation. make sure that request input makes sense.
        String rejection = rejectionReason(pageRequest, weblog, page, isSiteWide,
                weblogger.getWeblogEntryManager());
        if (rejection != null) {
            log.debug("page failed validation, bailing out: {}", rejection);
            if (answeredByRedirect(pageRequest, weblog, request, response)) {
                return;
            }
            sendNotFoundInWeblog(request, response, weblog);
            return;
        }

        // Multi-locale weblogs are gone (see Weblog.isEnableMultiLang's
        // removal above): a weblog's default view always shows every
        // locale now, so forcing pageRequest.getLocale() to the weblog's own
        // locale here -- the old showAllLangs=false behaviour -- no longer
        // has anything to trigger it.

        // hit counting used to gate on isWebsitePageHit()/isOtherPageHit()
        // here; Umami owns traffic counting now (see WeblogPageRequest).

        // looks like we need to render content
        // set the content deviceType
        String contentType = resolveContentType(page);

        Map<String, Object> model;
        try {
            model = buildModel(request, response, pageRequest, isSiteWide);
        } catch (WebloggerException ex) {
            RenderingServletUtils.sendServerError(response, "Error building the rendering model for page", ex);
            return;
        }

        CachedContent rendererOutput = RenderingServletUtils.renderAndFlush(page, model,
                PressroomConstants.TWENTYFOUR_KB_IN_BYTES, contentType,
                "page " + page.getId(),
                "Couldn't find renderer for page " + page.getId(), response);
        if (rendererOutput == null) {
            return;
        }

        cacheRendered(request, pageRequest, renderCache, cacheKey, rendererOutput);

        log.debug("Exiting");
    }


    /**
     * A 404 for a request inside a weblog: leaves the weblog's name and
     * address for the container's error page (see NOT_FOUND_WEBLOG_NAME),
     * then sends the 404.
     */
    private void sendNotFoundInWeblog(HttpServletRequest request, HttpServletResponse response,
                                      Weblog weblog) throws IOException {
        request.setAttribute(NOT_FOUND_WEBLOG_NAME, weblog.getName());
        request.setAttribute(NOT_FOUND_WEBLOG_URL,
                weblogger.getUrlStrategy().getWeblogURL(weblog, null, false));
        RenderingServletUtils.sendNotFound(response);
    }

    /**
     * The model the template renders against, or null when building it failed
     * and a 500 has already been sent.
     *
     * <p>Kept apart from doGet because it is the one step here that is about
     * Velocity rather than about http: everything else in the request decides
     * what to serve or whether to serve it, while this decides what the
     * template can see.
     */
    private Map<String, Object> buildModel(HttpServletRequest request,
                                           HttpServletResponse response,
                                           WeblogPageRequest pageRequest,
                                           boolean isSiteWide) throws WebloggerException {

        Map<String, Object> model = new HashMap<>();
        PageContext pageContext = JspFactory.getDefaultFactory()
                .getPageContext(this, request, response, "", false,
                        PressroomConstants.EIGHT_KB_IN_BYTES, true);

        // special hack for menu tag
        request.setAttribute("pageRequest", pageRequest);

        Map<String, Object> initData = new HashMap<>();
        initData.put("requestParameters", request.getParameterMap());
        initData.put("parsedRequest", pageRequest);
        initData.put("pageContext", pageContext);
        initData.put("urlStrategy", weblogger.getUrlStrategy());

        RenderingServletUtils.loadModels("rendering.pageModels", model, initData, isSiteWide,
                weblogger);
        return model;

    }


    /**
     * Writes the cached rendering of this request, if there is one to write.
     *
     * <p>The three conditions guarding the read are the caching policy, not an
     * optimisation: an owner viewing their own weblog must not be served a copy
     * rendered for anonymous readers (it would be missing their edit links),
     * and either "skipCache" is an explicit request for freshly rendered
     * output.
     *
     * @return true when the response has been completed from cache and the
     *         caller must return; false when the request has to be rendered
     */
    // False positive for CloseResource: the object read here is owned by the
    // render cache and may still be serving other concurrent requests. Closing
    // it would pull the content out from under them.
    @SuppressWarnings("PMD.CloseResource")
    private boolean servedFromCache(HttpServletRequest request, HttpServletResponse response,
                                    WeblogPageRequest pageRequest,
                                    RenderCache<WeblogPageRequest> renderCache,
                                    String cacheKey, long lastModified) throws IOException {

        if (!cacheReadable(request, pageRequest)) {
            return false;
        }

        CachedContent cachedContent = renderCache.get(cacheKey, lastModified);
        if (cachedContent == null) {
            log.debug("MISS {}", cacheKey);
            return false;
        }

        log.debug("HIT {}", cacheKey);

        // hit counting used to gate on isWebsitePageHit()/isOtherPageHit()
        // here; Umami owns traffic counting now (see WeblogPageRequest).

        response.setContentLength(cachedContent.getContent().length);
        response.setContentType(cachedContent.getContentType());
        response.getOutputStream().write(cachedContent.getContent());
        return true;
    }

    /**
     * Stores this rendering, unless the caching policy says not to.
     *
     * <p>Note the asymmetry with the read side: a "skipCache" query PARAMETER
     * suppresses the read but not the write, so a reader can force a fresh
     * render and have the result repopulate the cache for everyone behind them.
     * Only the request ATTRIBUTE -- set internally, for content that must never
     * be cached -- suppresses both.
     */
    private void cacheRendered(HttpServletRequest request, WeblogPageRequest pageRequest,
                               RenderCache<WeblogPageRequest> renderCache,
                               String cacheKey, CachedContent rendererOutput) {

        if ((!this.excludeOwnerPages || !pageRequest.isLoggedIn())
                && request.getAttribute("skipCache") == null) {
            log.debug("PUT {}", cacheKey);
            renderCache.put(cacheKey, rendererOutput);
        } else {
            log.debug("SKIPPED {}", cacheKey);
        }
    }


    /**
     * Whether this request may be answered from cache. The write side asks the
     * same question minus the "skipCache" query parameter, which suppresses a
     * read without suppressing the write that repopulates it.
     */
    private boolean cacheReadable(HttpServletRequest request, WeblogPageRequest pageRequest) {
        return (!this.excludeOwnerPages || !pageRequest.isLoggedIn())
                && request.getAttribute("skipCache") == null
                && request.getParameter("skipCache") == null;
    }


    /**
     * Whether {@code link} names the weblog's theme stylesheet. Under its
     * handle the site space serves that and no other template: its pages live
     * at /&lt;slug&gt;, and a theme's other linked templates (frontpage's
     * directory) are no site address. A lookup that fails is a no, so the
     * request 404s.
     */
    private static boolean isStylesheetLink(String link, Weblog weblog, ThemeManager themes) {
        try {
            ThemeTemplate stylesheet = themes.getTheme(weblog).getStylesheet();
            return stylesheet != null && link != null && link.equals(stylesheet.getLink());
        } catch (Exception e) {
            log.warn("Could not look up the stylesheet of weblog {}", weblog.getHandle(), e);
            return false;
        }
    }

    /**
     * The template that renders this request, or {@code null} when nothing
     * does and the answer is a 404.
     *
     * <p>Extracted from {@code doGet}, which reached 380 lines doing eight
     * separate jobs and could only be exercised end to end. The order of the
     * branches below is the behaviour: an explicitly named page never falls
     * through to the default (asking for a page that does not exist is a
     * 404, not the front page), while a tags index and a permalink fall back
     * to the default template when the theme has no template of their own.
     */
    static ThemeTemplate selectTemplate(WeblogPageRequest pageRequest, Weblog weblog, ThemeManager themes) {

        ThemeTemplate page = null;

        // A static page: getPageSlug() is the syntactic signal, set during
        // parsing with no database access, that this request's single path
        // segment looked like a page slug. Only inside this branch do we
        // resolve it -- getWeblogPageContent() is the deferred database
        // lookup -- so that a cache hit, or a request that never gets this
        // far, never pays for it. An unresolved slug (unknown, or a draft
        // this reader is not entitled to see) is a 404, not a fall-through
        // to the branches below: a page slug never doubles as a permalink or
        // the weblog's default view.
        if (pageRequest.getPageSlug() != null) {
            if (pageRequest.getWeblogPageContent() == null) {
                return null;
            }

            // The theme may override with a custom template named _page;
            // otherwise the shipped default renders it. Falling back rather
            // than 404ing means a theme does not have to know pages exist.
            ThemeTemplate template = null;
            try {
                template = themes.getTheme(weblog).getTemplateByName("_page");
            } catch (Exception e) {
                // Not simply "no _page override" -- getTemplateByName
                // returns null for that. An exception here means a real
                // lookup failure, so fall back to the default page template
                // (unchanged behavior) but leave a trace for whoever is
                // debugging why a themed page render looks generic.
                log.warn("Error looking up '_page' template for weblog {}", weblog.getHandle(), e);
            }
            return template != null ? template
                    : new StaticThemeTemplate("templates/weblog/page.vm",
                            TemplateLanguage.VELOCITY);
        }

        // If request specified the page, then go with that. No fallback: this
        // one 404s rather than quietly serving the default template.
        if ("page".equals(pageRequest.getContext())) {
            if (weblog.isSiteSpace() && !isStylesheetLink(pageRequest.getWeblogPageName(), weblog, themes)) {
                return null;
            }
            return pageRequest.getWeblogPage();
        }

        // A tags index uses the theme's tagsIndex template when it has one
        // and otherwise falls back to the default template below, whose pager
        // already filters by pageRequest.getTags(). No bundled theme ships a
        // tagsIndex template, and every theme's permalink links its tags here,
        // so without the fallback every tag link 404ed. An unknown tag
        // combination is still a 404, through rejectionReason.
        if ("tags".equals(pageRequest.getContext()) && pageRequest.getTags() != null) {
            try {
                page = themes.getTheme(weblog).getTemplateByAction(ComponentType.TAGSINDEX);
            } catch (Exception e) {
                log.error("Error getting weblog page for action 'tagsIndex'", e);
            }
        }

        // If this is a permalink then look for a permalink template
        if (pageRequest.getWeblogAnchor() != null) {
            try {
                page = themes.getTheme(weblog).getTemplateByAction(ComponentType.PERMALINK);
            } catch (Exception e) {
                log.error("Error getting weblog page for action 'permalink'", e);
            }
        }

        // if we haven't found a page yet then try our default page
        if (page == null) {
            try {
                page = themes.getTheme(weblog).getDefaultTemplate();
            } catch (Exception e) {
                log.error("Error getting default page for weblog = {}", weblog.getHandle(), e);
            }
        }

        return page;
    }

    /**
     * Why this request cannot be served, or {@code null} when it can.
     *
     * <p>Returns a reason rather than a boolean deliberately. Every one of
     * these ends in the same bare 404, and when one fires wrongly -- a
     * permalink readers say is missing, a locale view that will not open --
     * the log line used to read only "page failed validation", which is the
     * same sentence for eight different causes.
     */
    static String rejectionReason(WeblogPageRequest pageRequest, Weblog weblog,
            ThemeTemplate page, boolean isSiteWide, WeblogEntryManager wmgr) {

        if (pageRequest.getWeblogPageName() != null && page.isHidden()) {
            return "template is hidden";
        }

        // multi-locale weblogs are gone: a request naming a locale view is
        // never servable, the same outcome this check already produced for
        // every real weblog (the flag defaulted false and nothing could turn
        // it on for a bundled theme).
        if (pageRequest.getLocale() != null) {
            return "locale view requested but the weblog does not enable multiple languages";
        }

        if (pageRequest.getWeblogAnchor() != null) {
            // permalink specified. entry must exist and be published before
            // the current time. (A per-entry locale mismatch was checked
            // here too, but pageRequest.getLocale() != null already returned
            // above -- this branch is only ever reached with a null locale.)
            WeblogEntry entry = pageRequest.getWeblogEntry();
            if (entry == null) {
                return "no entry with that anchor";
            }
            if (!entry.isPublished()) {
                return "entry is not published";
            }
            if (new Date().before(entry.getPubTime())) {
                return "entry is scheduled for the future";
            }
            return null;
        }

        if (pageRequest.getWeblogCategoryName() != null) {
            // category specified. category must exist.
            if (pageRequest.getWeblogCategory() == null) {
                return "no category by that name";
            }
            return null;
        }

        if (pageRequest.getTags() != null && !pageRequest.getTags().isEmpty()) {
            try {
                // tags specified. make sure they exist.
                if (!wmgr.getTagComboExists(pageRequest.getTags(),
                        isSiteWide ? null : weblog)) {
                    return "no entries carry that combination of tags";
                }
            } catch (WebloggerException ex) {
                return "tag lookup failed: " + ex.getMessage();
            }
        }

        return null;
    }

    /**
     * The content type this template renders as: its own declaration, else the
     * type implied by its link's extension, else HTML.
     *
     * <p>The middle case is what lets a custom template named
     * {@code something.css} be served as a stylesheet rather than as markup a
     * browser refuses to apply.
     */
    static String resolveContentType(ThemeTemplate page) {
        if (StringUtils.isNotEmpty(page.getOutputContentType())) {
            return page.getOutputContentType() + "; charset=utf-8";
        }

        final String defaultContentType = "text/html; charset=utf-8";
        if (page.getLink() == null) {
            return defaultContentType;
        }

        String mimeType = PressroomContext.getServletContext().getMimeType(page.getLink());
        return mimeType != null ? mimeType + "; charset=utf-8" : defaultContentType;
    }

    /**
     * Handle POST requests the same as GET, with caching disabled.
     *
     * <p>Nothing in {@code WeblogRequestMapper} forwards a POST here anymore
     * (that was comment-submission forwarding, now removed with the comment
     * subsystem); this stays as a defensive fallback for a direct POST to
     * the servlet path.
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        // make sure caching is disabled
        request.setAttribute("skipCache", "true");

        // handle just like a GET request
        this.doGet(request, response);
    }
}
