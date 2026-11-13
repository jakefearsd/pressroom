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

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.WeblogCategory;


/**
 * Represents a request for a weblog preview.
 */
public class WeblogSearchRequest extends WeblogRequest {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogSearchRequest.class);
    
    public static final String SEARCH_SERVLET = "/pressroom-ui/rendering/search";
    
    /** The most of a query a search reads, in code points; the forms' maxlength says the same. */
    public static final int MAX_QUERY_CODE_POINTS = 255;

    /**
     * The last page there can be: 500 hits are considered, 10 a page. A
     * larger page overflowed the offset into a negative index (final review).
     */
    static final int LAST_PAGE = 49;

    // lightweight attributes
    private String query = null;
    private int pageNum = 0;
    private String weblogCategoryName = null;
    private boolean scopeBlog = false;
    
    // heavyweight attributes
    private WeblogCategory weblogCategory = null;
    
    
    public WeblogSearchRequest() {}
    
    
    public WeblogSearchRequest(Weblogger weblogger, HttpServletRequest request)
            throws InvalidRequestException {

        // let our parent take care of their business first
        // parent determines weblog handle and locale if specified. The site
        // space's handle is admitted: the site's search (/search) is its
        // search, and the servlet refuses it unless WeblogRequestMapper
        // forwarded the request.
        super(weblogger, request, true);
        
        String servlet = request.getServletPath();
        
        // we only want the path info left over from after our parents parsing
        String pathInfo = this.getPathInfo();
        
        // was this request bound for the search servlet?
        if (!isValidDestination(servlet)) {
            throw new InvalidRequestException("not a weblog search request, "+
                    request.getRequestURL());
        }

        parsePathInfo(pathInfo, request);
        parseQueryParameters(request);
    }

    /**
     * Searches are served only from the search servlet. Named to match the same
     * check on {@link WeblogPageRequest} and {@link WeblogFeedRequest}.
     */
    boolean isValidDestination(String servlet) {
        return SEARCH_SERVLET.equals(servlet);
    }

    /**
     * A search carries nothing in its path -- the query travels as a parameter,
     * so anything after the weblog handle names a request this servlet cannot
     * serve and is refused rather than ignored.
     */
    private void parsePathInfo(String pathInfo, HttpServletRequest request)
            throws InvalidRequestException {

        if (pathInfo != null) {
            throw new InvalidRequestException("invalid path info, "+
                    request.getRequestURL());
        }
    }

    /**
     * Reads the query string: the search terms, which page of results, and an
     * optional category to narrow to.
     */
    private void parseQueryParameters(HttpServletRequest request)
            throws InvalidRequestException {

        if (request.getParameter("q") != null && !request.getParameter("q").isBlank()) {
            this.query = bounded(request.getParameter("q"));
        }

        if (request.getParameter("page") != null) {
            try {
                this.pageNum = Math.clamp(Integer.parseInt(request.getParameter("page")), 0, LAST_PAGE);
            } catch (NumberFormatException ignored) {
                // A malformed "page" parameter is routine, not a request
                // error -- crawlers and hand-edited search URLs send these
                // constantly; parsing simply keeps the default page.
            }
        }

        // "scope=blog" asks for this blog's matches alone; any other value is ignored
        this.scopeBlog = "blog".equals(request.getParameter("scope"));

        if (request.getParameter("cat") != null && !request.getParameter("cat").isBlank()) {
            this.weblogCategoryName =
                    decodeOrReject(request.getParameter("cat"), request);
        }
    }

    /**
     * The reader's words, trimmed and cut to {@link #MAX_QUERY_CODE_POINTS}
     * code points (never inside a surrogate pair): the anonymous endpoint's
     * own bound, whatever a form allowed.
     */
    static String bounded(String words) {
        String trimmed = words.strip();
        if (trimmed.codePointCount(0, trimmed.length()) <= MAX_QUERY_CODE_POINTS) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_QUERY_CODE_POINTS));
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    /**
     * True when only this blog's matches are wanted: {@code scope=blog}, or
     * any category, which belongs to one blog.
     */
    public boolean isBlogOnly() {
        return scopeBlog || weblogCategoryName != null;
    }

    public void setScopeBlog(boolean scopeBlog) {
        this.scopeBlog = scopeBlog;
    }

    public int getPageNum() {
        return pageNum;
    }

    public void setPageNum(int pageNum) {
        this.pageNum = pageNum;
    }

    public String getWeblogCategoryName() {
        return weblogCategoryName;
    }

    public void setWeblogCategoryName(String weblogCategory) {
        this.weblogCategoryName = weblogCategory;
    }

    public WeblogCategory getWeblogCategory() {
        
        if(weblogCategory == null && weblogCategoryName != null) {
            try {
                WeblogEntryManager wmgr = weblogger().getWeblogEntryManager();
                weblogCategory = wmgr.getWeblogCategoryByName(getWeblog(), weblogCategoryName);
            } catch (WebloggerException ex) {
                log.error("Error getting weblog category {}", weblogCategoryName, ex);
            }
        }
        
        return weblogCategory;
    }

    public void setWeblogCategory(WeblogCategory weblogCategory) {
        this.weblogCategory = weblogCategory;
    }
    
}
