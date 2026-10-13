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

package org.apache.roller.weblogger.ui.rendering.servlets;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.StaticTemplate;
import org.apache.roller.weblogger.pojos.TemplateRendition.TemplateLanguage;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.rendering.util.WeblogFeedRequest;
import org.apache.roller.weblogger.util.cache.CachedContent;
import org.apache.roller.weblogger.ui.rendering.util.cache.RenderCache;
import org.apache.roller.weblogger.ui.rendering.util.cache.RenderCaches;
import org.apache.roller.weblogger.ui.rendering.util.ModDateHeaderUtil;


/**
 * Responsible for rendering weblog feeds.
 */
public class FeedServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(FeedServlet.class);

    private transient RenderCache<WeblogFeedRequest> siteWideRenderCache = null;
    private transient RenderCache<WeblogFeedRequest> weblogRenderCache = null;


    private final transient Weblogger weblogger;

    /**
     * Constructed by {@code ServletRegistrationConfig} with the (lazily
     * resolved) business-tier facade; there is no default constructor on
     * purpose, so the dependency is visible at the one place this servlet is
     * built.
     */
    public FeedServlet(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    /**
     * Init method for this servlet
     */
    @Override
    public void init(ServletConfig servletConfig) throws ServletException {

        super.init(servletConfig);

        log.info("Initializing FeedServlet");

        // one RenderCache per side of the site-wide question; which one a
        // request uses is decided once, in doGet
        this.siteWideRenderCache = RenderCaches.forFeed(true);
        this.weblogRenderCache = RenderCaches.forFeed(false);
    }


    /**
     * Handle GET requests for weblog feeds.
     */
    // Two CachedContent sites here are false positives for CloseResource:
    // the cache-hit object (siteWideCache/weblogFeedCache.get(...)) is owned
    // by the render cache and may still be serving other concurrent
    // requests -- this method must not close it. The cache-miss object
    // (RenderingServletUtils.renderAndFlush(...)) is already flushed and closed
    // internally before it is returned; there is nothing left to close here.
    @SuppressWarnings("PMD.CloseResource")
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        log.debug("Entering");

        Weblog weblog;
        boolean isSiteWide;

        WeblogFeedRequest feedRequest;
        try {
            // parse the incoming request and extract the relevant data
            feedRequest = new WeblogFeedRequest(weblogger, request);

            weblog = feedRequest.getWeblog();
            if (weblog == null) {
                throw new WebloggerException("unable to lookup weblog: "
                        + feedRequest.getWeblogHandle());
            }

            // is this the site-wide weblog?
            isSiteWide = WebloggerRuntimeConfig.isSiteWideWeblog(feedRequest
                    .getWeblogHandle());

        } catch (Exception e) {
            // invalid feed request format or weblog doesn't exist
            log.debug("error creating weblog feed request", e);
            RenderingServletUtils.sendNotFound(response);
            return;
        }

        // the site-wide question is asked once, here, and answered by holding
        // the cache it selects for the rest of the request
        RenderCache<WeblogFeedRequest> renderCache =
                isSiteWide ? siteWideRenderCache : weblogRenderCache;

        // determine the lastModified date for this content
        long lastModified = renderCache.lastModified(weblog);

        // Respond with 304 Not Modified if it is not modified.
        if (ModDateHeaderUtil.respondIfNotModified(request, response,
                lastModified)) {
            return;
        }

        // set last-modified date
        ModDateHeaderUtil.setLastModifiedHeader(response, lastModified);

        String contentType = negotiateContentType(request, feedRequest);
        if (contentType != null) {
            response.setContentType(contentType);
        }

        // generate cache key
        String cacheKey = renderCache.generateKey(feedRequest);

        if (servedFromCache(response, renderCache, cacheKey, lastModified)) {
            return;
        }

        if (!isServable(feedRequest, weblog, isSiteWide, weblogger.getWeblogEntryManager())) {
            RenderingServletUtils.sendNotFound(response);
            return;
        }

        // Multi-locale weblogs are gone: a weblog's feed always shows every
        // locale now, so forcing feedRequest.getLocale() to the weblog's own
        // locale here -- the old showAllLangs=false behaviour -- no longer
        // has anything to trigger it.

        // looks like we need to render content
        // Recomputed from the resolved weblog rather than reusing isSiteWide
        // above, which asks the same question of the handle parsed out of the
        // url. They agree for every request that gets this far; keeping the
        // original expression avoids making that an assumption.
        boolean siteWide = WebloggerRuntimeConfig.isSiteWideWeblog(weblog.getHandle());
        String pageId = feedTemplateId(feedRequest, siteWide);

        Map<String, Object> model;
        try {
            model = buildModel(feedRequest, siteWide);
        } catch (WebloggerException ex) {
            RenderingServletUtils.sendServerError(response, "ERROR building the rendering model for feed", ex);
            return;
        }

        // The missing-renderer log is deliberately suppressed here (null below).
        // Feed template ids are built straight from request data and are often
        // bunk, so this fires for ordinary bad input rather than for anything
        // an operator can act on. Better input validation would let it come
        // back; until then it would only fill the logs.
        CachedContent rendererOutput = RenderingServletUtils.renderAndFlush(
                new StaticTemplate(pageId, TemplateLanguage.VELOCITY), model,
                RollerConstants.TWENTYFOUR_KB_IN_BYTES, null,
                "feed " + pageId, null, response);
        if (rendererOutput == null) {
            return;
        }

        // cache rendered content. only cache if user is not logged in?
        log.debug("PUT {}", cacheKey);
        renderCache.put(cacheKey, rendererOutput);

        log.debug("Exiting");
    }

    /**
     * The Velocity template that renders this feed. The site-wide front page
     * has its own family of feed templates; an ordinary weblog uses the
     * weblog-* set.
     */
    private static String feedTemplateId(WeblogFeedRequest feedRequest, boolean siteWide) {
        String prefix = siteWide ? "site-" : "weblog-";
        return prefix + feedRequest.getType() + "-" + feedRequest.getFormat() + ".vm";
    }

    /**
     * The model the feed template renders against, or null when building it
     * failed and a 500 has already been sent.
     */
    private Map<String, Object> buildModel(WeblogFeedRequest feedRequest, boolean siteWide)
            throws WebloggerException {

        Map<String, Object> model = new HashMap<>();
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", feedRequest);
        initData.put("urlStrategy", weblogger.getUrlStrategy());

        RenderingServletUtils.loadModels("rendering.feedModels", model, initData, siteWide,
                weblogger);
        return model;

    }

    /**
     * Writes the cached feed if there is one. Unlike the page cache there is no
     * owner-versus-reader distinction here: a feed renders the same for
     * everybody, so anything cached is servable to anybody.
     *
     * @return true when the response has been completed from cache
     */
    // False positive for CloseResource: the object read here is owned by the
    // render cache and may still be serving other concurrent requests.
    @SuppressWarnings("PMD.CloseResource")
    private boolean servedFromCache(HttpServletResponse response,
                                    RenderCache<WeblogFeedRequest> renderCache,
                                    String cacheKey, long lastModified) throws IOException {

        CachedContent cachedContent = renderCache.get(cacheKey, lastModified);
        if (cachedContent == null) {
            log.debug("MISS {}", cacheKey);
            return false;
        }

        log.debug("HIT {}", cacheKey);
        response.setContentLength(cachedContent.getContent().length);
        response.getOutputStream().write(cachedContent.getContent());
        return true;
    }

    /**
     * The content type a feed should be served as, or null to leave whatever
     * the container defaults to.
     *
     * <p>The browser case is the interesting one: a browser asking for a feed
     * gets text/xml so it renders in the window, rather than the correct feed
     * type, which makes it offer a download instead. That is a deliberate
     * lie told to browsers and only to browsers, and only when the site has
     * styled feeds turned on.
     */
    private static String negotiateContentType(HttpServletRequest request,
                                               WeblogFeedRequest feedRequest) {

        String accepts = request.getHeader("Accept");
        String userAgent = request.getHeader("User-Agent");

        if (WebloggerRuntimeConfig.getBooleanProperty("site.newsfeeds.styledFeeds")
                && accepts != null && accepts.contains("*/*")
                && userAgent != null && userAgent.startsWith("Mozilla")) {
            return "text/xml";
        }
        if ("atom".equals(feedRequest.getFormat())) {
            return "application/atom+xml; charset=utf-8";
        }
        return null;
    }

    /**
     * Whether this feed request names something that can actually be served.
     *
     * <p>Everything here 404s rather than degrading to a wider feed, and that
     * is the point: silently serving the unfiltered feed for a category that
     * does not exist gives a subscriber a feed they did not ask for and no
     * indication that anything went wrong.
     *
     * <ul>
     *   <li>A locale: multi-locale weblogs are gone, so a feed request naming
     *       one is never servable.</li>
     *   <li>A search term: search feeds are gone (W2, Atom only). The template
     *       and model that served them were deleted, so a "q" must 404 rather
     *       than fall through to the unfiltered feed.</li>
     *   <li>A category that does not exist, or a tag combination nothing
     *       carries.</li>
     * </ul>
     */
    private static boolean isServable(WeblogFeedRequest feedRequest, Weblog weblog,
                                      boolean isSiteWide, WeblogEntryManager wmgr) {

        if (feedRequest.getLocale() != null || feedRequest.getTerm() != null) {
            return false;
        }

        if (feedRequest.getWeblogCategoryName() != null) {
            return feedRequest.getWeblogCategory() != null;
        }

        if (feedRequest.getTags() != null && !feedRequest.getTags().isEmpty()) {
            try {
                return wmgr.getTagComboExists(feedRequest.getTags(),
                                isSiteWide ? null : weblog);
            } catch (WebloggerException ex) {
                log.debug("Tag lookup failed for feed request", ex);
                return false;
            }
        }

        return true;
    }
}
