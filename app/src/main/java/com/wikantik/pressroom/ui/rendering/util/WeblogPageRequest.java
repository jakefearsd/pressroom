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

package com.wikantik.pressroom.ui.rendering.util;

import java.util.Collections;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.ReservedSlugs;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.util.Utilities;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a request for a Roller weblog page.
 * 
 * any url from ... /pressroom-ui/rendering/page/*
 * 
 * We use this class as a helper to parse an incoming url and sort out the
 * information embedded in the url for later use.
 */
public class WeblogPageRequest extends WeblogRequest {

    private static final Logger log = LoggerFactory.getLogger(WeblogPageRequest.class);

    private static final String PAGE_SERVLET = "/pressroom-ui/rendering/page";

    // lightweight attributes
    private String context = null;
    private String weblogAnchor = null;
    private String weblogPageName = null;
    private String weblogCategoryName = null;
    private String weblogDate = null;
    private List<String> tags = null;
    private int pageNum = 0;
    private Map<String, String[]> customParams = Collections.emptyMap();

    // lightweight: the raw, unresolved path segment for a candidate bare-slug
    // page request (/<handle>/<slug>), set during parsing with no database
    // access. null on every other kind of request.
    private String pageSlug = null;

    // heavyweight attributes
    private WeblogEntry weblogEntry = null;
    private ThemeTemplate weblogPage = null;
    private WeblogCategory weblogCategory = null;
    private WeblogPage weblogPageContent = null;
    private boolean weblogPageContentResolved = false;

    // Page hits
    private boolean websitePageHit = false;
    private boolean otherPageHit = false;

    public WeblogPageRequest() {
    }

    /** A request object that can look things up but parsed nothing. */
    public WeblogPageRequest(Weblogger weblogger) {
        super(weblogger);
    }

    /**
     * Construct the WeblogPageRequest by parsing the incoming url
     */
    public WeblogPageRequest(Weblogger weblogger, HttpServletRequest request)
            throws InvalidRequestException {

        // let our parent take care of their business first
        // parent determines weblog handle and locale if specified; a page
        // request is the one kind that may name the site space
        super(weblogger, request, true);

        String servlet = request.getServletPath();

        // we only want the path info left over from after our parents parsing
        String pathInfo = this.getPathInfo();

        // parse the request object and figure out what we've got
        log.debug("parsing path {}", pathInfo);

        // was this request bound for the right servlet?
        if (!isValidDestination(servlet)) {
            throw new InvalidRequestException(
                    "invalid destination for request, "
                            + request.getRequestURL());
        }

        parsePathInfo(pathInfo, request);
        parseQueryParameters(pathInfo, request);
        this.customParams = buildCustomParams(request);

        log.debug("context = {}", this.context);
        log.debug("weblogAnchor = {}", this.weblogAnchor);
        log.debug("weblogDate = {}", this.weblogDate);
        log.debug("weblogCategory = {}", this.weblogCategoryName);
        log.debug("tags = {}", this.tags);
        log.debug("weblogPage = {}", this.weblogPageName);
        log.debug("pageNum = {}", this.pageNum);
    }

    /**
     * Reads the view out of the path: which context was asked for, and the one
     * argument that context takes.
     *
     * <p>Extracted from the constructor, which was cyclomatic complexity 33
     * with all three parsing phases inline. The phases are independent -- this
     * one reads only the path, the next only the query string -- but written
     * end to end they read as one long decision.
     *
     * <p>We expect one of:
     * <pre>
     *   /entry/&lt;anchor&gt;      permalink
     *   /date/&lt;YYYYMMDD&gt;     date collection view
     *   /category/&lt;category&gt; category collection view
     *   /tags/&lt;tag&gt;+&lt;tag&gt;    tags
     *   /page/&lt;pagelink&gt;     custom page
     * </pre>
     * A null or blank path is the weblog homepage.
     */
    private void parsePathInfo(String pathInfo, HttpServletRequest request)
            throws InvalidRequestException {

        if (pathInfo == null || pathInfo.isBlank()) {
            // default page
            websitePageHit = true;
            return;
        }

        // all views use 2 path elements
        String[] pathElements = pathInfo.split("/", 2);

        // the first part of the path always represents the context
        this.context = pathElements[0];

        if (pathElements.length == 2) {
            parseContextArgument(pathElements[1], request);
        } else {
            parseSinglePathElement(request);
        }
    }

    /** The {@code /<context>/<argument>} forms. */
    private void parseContextArgument(String argument, HttpServletRequest request)
            throws InvalidRequestException {

        switch (this.context) {
            case "entry" -> {
                this.weblogAnchor = decodeOrReject(argument, request);
                otherPageHit = true;
            }
            case "date" -> {
                if (!this.isValidDateString(argument)) {
                    throw new InvalidRequestException("invalid date, "
                            + request.getRequestURL());
                }
                this.weblogDate = argument;
                otherPageHit = true;
            }
            case "category" -> {
                this.weblogCategoryName = decodeOrReject(argument, request);
                otherPageHit = true;
            }
            case "page" -> {
                this.weblogPageName = argument;
                String tagsString = request.getParameter("tags");
                if (tagsString != null) {
                    this.tags = parseTags(tagsString, request);
                }
                // we do not want css etc stuff so filter out
                if (!argument.contains(".")) {
                    otherPageHit = true;
                }
            }
            case "tags" -> {
                this.tags = parseTags(argument.replace('+', ' '), request);
                otherPageHit = true;
            }
            default -> throw new InvalidRequestException("context " + this.context
                    + "not supported, " + request.getRequestURL());
        }
    }

    /**
     * A single path element. It is either /tags (the one context that takes no
     * argument) or a page slug: /&lt;handle&gt;/about.
     *
     * <p>Reserved words are rejected here (a cheap string check, no database);
     * resolving the slug against the database is deferred to
     * getWeblogPageContent() -- see its javadoc -- so that a cache hit never
     * pays for a lookup that its answer doesn't need. A slug that turns out not
     * to name a published page 404s at the servlet, not here.
     */
    private void parseSinglePathElement(HttpServletRequest request)
            throws InvalidRequestException {

        if ("tags".equals(this.context)) {
            return;
        }

        // decodeOrReject mirrors the entry/category branches above: URLModel#staticPage
        // encodes the slug with URLUtilities.encode (a space becomes '+'), and the
        // servlet container's own path decoding never touches '+' -- only URLDecoder
        // does. Without this, a slug with a space round-trips as a literal '+' and 404s.
        String slug = decodeOrReject(this.context, request);
        if (ReservedSlugs.isReserved(slug)) {
            throw new InvalidRequestException("invalid index page, "
                    + request.getRequestURL());
        }
        this.pageSlug = slug;
        otherPageHit = true;
    }

    /**
     * Reads the query string.
     *
     * <p>Params are only honoured when the path carried no view of its own, or
     * on user-defined pages (for backwards compatibility). That is what keeps
     * path-based and query-param urls from being mixed.
     */
    private void parseQueryParameters(String pathInfo, HttpServletRequest request)
            throws InvalidRequestException {

        if (pathInfo == null || this.weblogPageName != null) {

            // "entry" wins outright when present -- including when it is
            // present but empty, which suppresses "anchor" rather than falling
            // through to it. Collapsing these into a first-non-empty-of-the-two
            // would quietly change that.
            String anchor = request.getParameter("entry") != null
                    ? request.getParameter("entry")
                    : request.getParameter("anchor");
            if (StringUtils.isNotEmpty(anchor)) {
                this.weblogAnchor = anchor;
            }

            // only check for other params if we didn't find an anchor above or tags
            if (this.weblogAnchor == null && this.tags == null) {
                String date = request.getParameter("date");
                if (date != null) {
                    if (!this.isValidDateString(date)) {
                        throw new InvalidRequestException("invalid date, "
                                + request.getRequestURL());
                    }
                    this.weblogDate = date;
                }

                String cat = request.getParameter("cat");
                if (cat != null) {
                    this.weblogCategoryName = decodeOrReject(cat, request);
                }
            }
        }

        // page request param is supported in all views
        String pageInt = request.getParameter("page");
        if (pageInt != null) {
            try {
                this.pageNum = Integer.parseInt(pageInt);
            } catch (NumberFormatException ignored) {
                // A malformed "page" parameter is routine, not a request
                // error -- crawlers and hand-edited URLs send these
                // constantly; parsing simply keeps the default page.
            }
        }
    }

    /**
     * Everything in the query string that this class did not claim, which is
     * what a template author sees.
     */
    private static Map<String, String[]> buildCustomParams(HttpServletRequest request) {
        Map<String, String[]> params = new HashMap<>(request.getParameterMap());
        params.remove("entry");
        params.remove("anchor");
        params.remove("date");
        params.remove("cat");
        params.remove("page");
        params.remove("tags");
        return params;
    }

    boolean isValidDestination(String servlet) {
        return servlet != null && PAGE_SERVLET.equals(servlet);
    }

    /**
     * The page for a bare slug on this weblog, or null. Drafts are invisible
     * here rather than at the servlet: an unpublished page must be
     * indistinguishable from one that does not exist.
     */
    private WeblogPage lookUpPage(String slug) {
        if (slug == null) {
            return null;
        }
        try {
            Weblog weblog = getWeblog();
            if (weblog == null) {
                return null;
            }
            WeblogPage page = weblogger()
                    .getWeblogPageManager().getPageBySlug(weblog, slug);
            return page != null && page.getStatus() == WeblogPage.PubStatus.PUBLISHED
                    ? page : null;
        } catch (WebloggerException ex) {
            log.error("Error looking up page {}", slug, ex);
            return null;
        }
    }

    /**
     * Decode and split a tag list, refusing lists longer than
     * tags.queries.maxIntersectionSize.
     *
     * The ceiling is a cost guard: each additional tag adds another join to the
     * entry intersection query. It applies to every way a tag list can enter a
     * page request -- the /tags/ path and the ?tags= parameter that custom
     * pages accept -- because both end up in the same query.
     */
    private static List<String> parseTags(String tagsString, HttpServletRequest request)
            throws InvalidRequestException {

        List<String> parsed = Utilities.splitStringAsTags(decodeOrReject(tagsString, request));

        int maxSize = WebloggerConfig.getIntProperty("tags.queries.maxIntersectionSize", 3);
        if (parsed.size() > maxSize) {
            throw new InvalidRequestException("max number of tags allowed is "
                    + maxSize + ", " + request.getRequestURL());
        }

        return parsed;
    }

    private boolean isValidDateString(String dateString) {
        // string must be all numeric and 6 or 8 characters
        return dateString != null && StringUtils.isNumeric(dateString) && (dateString
                .length() == 6 || dateString.length() == 8);
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public String getWeblogAnchor() {
        return weblogAnchor;
    }

    public void setWeblogAnchor(String weblogAnchor) {
        this.weblogAnchor = weblogAnchor;
    }

    public String getWeblogPageName() {
        return weblogPageName;
    }

    public void setWeblogPageName(String weblogPage) {
        this.weblogPageName = weblogPage;
    }

    public String getWeblogCategoryName() {
        return weblogCategoryName;
    }

    public void setWeblogCategoryName(String weblogCategory) {
        this.weblogCategoryName = weblogCategory;
    }

    public String getWeblogDate() {
        return weblogDate;
    }

    public void setWeblogDate(String weblogDate) {
        this.weblogDate = weblogDate;
    }

    public int getPageNum() {
        return pageNum;
    }

    public void setPageNum(int pageNum) {
        this.pageNum = pageNum;
    }

    public Map<String, String[]> getCustomParams() {
        return customParams;
    }

    public void setCustomParams(Map<String, String[]> customParams) {
        this.customParams = customParams;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public WeblogEntry getWeblogEntry() {

        if (weblogEntry == null && weblogAnchor != null) {
            try {
                WeblogEntryManager wmgr = weblogger()
                        .getWeblogEntryManager();
                weblogEntry = wmgr.getWeblogEntryByAnchor(getWeblog(),
                        weblogAnchor);
            } catch (WebloggerException ex) {
                log.error("Error getting weblog entry {}", weblogAnchor, ex);
            }
        }

        return weblogEntry;
    }

    public void setWeblogEntry(WeblogEntry weblogEntry) {
        this.weblogEntry = weblogEntry;
    }

    public ThemeTemplate getWeblogPage() {

        if (weblogPage == null && weblogPageName != null) {
            try {
                weblogPage = weblogger().getThemeManager().getTheme(getWeblog()).getTemplateByLink(
                        weblogPageName);
            } catch (WebloggerException ex) {
                log.error("Error getting weblog page {}", weblogPageName, ex);
            }
        }

        return weblogPage;
    }

    public void setWeblogPage(WeblogTemplate weblogPage) {
        this.weblogPage = weblogPage;
    }

    /**
     * The raw, unresolved path segment of a candidate bare-slug page request
     * (/<handle>/<slug>), or null on every other kind of request. Set during
     * parsing with no database access -- this is the field cache-key
     * generation reads, precisely so that generating a key never resolves
     * the page it might name.
     */
    public String getPageSlug() {
        return pageSlug;
    }

    public void setPageSlug(String pageSlug) {
        this.pageSlug = pageSlug;
    }

    /**
     * The published {@link WeblogPage} resolved from {@link #getPageSlug()},
     * or null on every other kind of request, or when the slug does not name
     * a published page. Resolved lazily, on first call, against the
     * database -- and memoized, including a null answer, so that repeat
     * calls (the servlet's template selection, then the model, on the same
     * request) cost one lookup rather than one each. A cache hit never
     * reaches this getter at all, since the servlet only calls it after a
     * cache miss.
     */
    public WeblogPage getWeblogPageContent() {
        if (!weblogPageContentResolved) {
            weblogPageContent = lookUpPage(pageSlug);
            weblogPageContentResolved = true;
        }
        return weblogPageContent;
    }

    public void setWeblogPageContent(WeblogPage weblogPageContent) {
        this.weblogPageContent = weblogPageContent;
        this.weblogPageContentResolved = true;
    }

    public WeblogCategory getWeblogCategory() {

        if (weblogCategory == null && weblogCategoryName != null) {
            try {
                WeblogEntryManager wmgr = weblogger()
                        .getWeblogEntryManager();
                weblogCategory = wmgr.getWeblogCategoryByName(getWeblog(),
                        weblogCategoryName);
            } catch (WebloggerException ex) {
                log.error("Error getting weblog category {}", weblogCategoryName, ex);
            }
        }

        return weblogCategory;
    }

    public void setWeblogCategory(WeblogCategory weblogCategory) {
        this.weblogCategory = weblogCategory;
    }

    /**
     * Checks if is website page hit.
     * 
     * @return true, if is website page hit
     */
    public boolean isWebsitePageHit() {
        return websitePageHit;
    }

    /**
     * Sets the website page hit.
     * 
     * @param websitePageHit
     *            the new website page hit
     */
    public void setWebsitePageHit(boolean websitePageHit) {
        this.websitePageHit = websitePageHit;
    }

    /**
     * Checks if is other page hit.
     * 
     * @return true, if is other page hit
     */
    public boolean isOtherPageHit() {
        return otherPageHit;
    }

    /**
     * Sets the other page hit.
     * 
     * @param otherPageHit
     *            the new other page hit
     */
    public void setOtherPageHit(boolean otherPageHit) {
        this.otherPageHit = otherPageHit;
    }

}
