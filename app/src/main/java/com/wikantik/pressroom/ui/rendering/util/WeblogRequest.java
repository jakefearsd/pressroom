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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;


/**
 * Represents a request to a weblog.
 * 
 * This is a fairly generic parsed request which is only trying to figure out
 * the elements of a weblog request which apply to all weblogs.  We try to 
 * determine the weblogHandle, if a locale was specified, and then what extra 
 * path info remains.  The basic format is like this ...
 * 
 * /<weblogHandle>[/locale][/extra/path/info]
 * 
 * All weblog urls require a weblogHandle, so we ensure that part of the url is
 * properly specified.  locale is always optional, so we do our best to see
 * if a locale is specified.  and path info is always optional.
 *
 * NOTE: this class purposely exposes a getPathInfo() method which provides the
 * path info specified by the request that has not been parsed by this
 * particular class.  this makes it relatively easy for subclasses to extend
 * this class and simply pick up where it left off in the parsing process.
 */
public class WeblogRequest extends ParsedRequest {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogRequest.class);
    
    // lightweight attributes
    private String weblogHandle = null;
    private String locale = null;
    private String pathInfo = null;
    
    // heavyweight attributes
    private Weblog weblog = null;
    private Locale localeInstance = null;
    
    
    public WeblogRequest() {}


    /** A request object that can look things up but parsed nothing. */
    public WeblogRequest(Weblogger weblogger) {
        super(weblogger);
    }


    public WeblogRequest(Weblogger weblogger, HttpServletRequest request)
            throws InvalidRequestException {
        this(weblogger, request, false);
    }


    /**
     * @param admitSiteSpace whether {@link Weblog#SITE_SPACE_HANDLE}, which no
     *        blog handle can be, is read as a handle. Only a page request
     *        says yes: site pages render through the page servlet and nowhere
     *        else, so a crafted feed, search or resource url for the site
     *        space parses as no weblog at all.
     */
    protected WeblogRequest(Weblogger weblogger, HttpServletRequest request,
            boolean admitSiteSpace) throws InvalidRequestException {

        // let our parent take care of their business first
        super(weblogger, request);
        
        String path = request.getPathInfo();
        
        log.debug("parsing path {}", path);
        
        // first, cleanup extra slashes and extract the weblog weblogHandle
        if(path != null && path.trim().length() > 1) {
            
            // strip off the leading slash
            path = path.substring(1);
            
            // strip off trailing slash if needed
            if(path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            
            String[] pathElements = path.split("/", 2);
            if(isValidWeblogHandle(pathElements[0])
                    || admitSiteSpace && Weblog.SITE_SPACE_HANDLE.equals(pathElements[0])) {
                this.weblogHandle = pathElements[0];
            } else {
                // no or invalid weblogHandle in path info
                throw new InvalidRequestException("not a valid weblog request: "+request.getRequestURL());
            }
            
            // if there is more left of the path info then hold onto it
            if(pathElements.length == 2) {
                path = pathElements[1];
            } else {
                path = null;
            }
        }
        
        // second, check if we have a locale, everything else is extra path info
        if(path != null && !path.isBlank()) {
            
            String[] pathElements = path.split("/", 2);
            if(this.isLocale(pathElements[0])) {
                this.locale = pathElements[0];
                
                // everything else is path info
                if(pathElements.length == 2) {
                    this.pathInfo = pathElements[1];
                }
            } else {
                // no locale, just extra path info
                this.pathInfo = path;
            }
        }
        
        log.debug("handle = {}", this.weblogHandle);
        log.debug("locale = {}", this.locale);
        log.debug("pathInfo = {}", this.pathInfo);
    }
    

    /**
     * Percent-decode a piece of a request that has already been decoded once by
     * the servlet container.
     *
     * The parsed-request classes deliberately decode a second time so that
     * sequences such as %2B survive as a literal '+'. The cost is that a
     * segment which legitimately decodes to a bare '%' -- for example a
     * category named "100%", requested as .../category/100%25 -- makes
     * URLDecoder throw an unchecked IllegalArgumentException. Callers of these
     * classes are documented to expect InvalidRequestException for any input
     * they cannot parse, so translate it here rather than letting an unchecked
     * exception escape the constructor.
     */
    static String decodeOrReject(String value, HttpServletRequest request)
            throws InvalidRequestException {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException("invalid URL encoding in request, "
                    + request.getRequestURL(), ex);
        }
    }


    /**
     * Whether the given string is a valid locale string.
     *
     * <p>Kept as a protected method because subclasses call it; the rule itself
     * lives in {@link LocaleSegment}, which WeblogRequestMapper shares.
     *
     * @see LocaleSegment#isLocale(String)
     */
    protected boolean isLocale(String potentialLocale) {
        return LocaleSegment.isLocale(potentialLocale);
    }
    
    
    /**
     * Accepts the same handles the business tier does.
     *
     * <p>This used to be {@code StringUtils.isAlphanumeric}, while
     * {@code JPAWeblogManagerImpl.isAlphanumeric} - whose javadoc reads "Returns
     * true if alphanumeric or '_'" - permits an underscore. A weblog whose handle
     * contained one could therefore be created and stored, yet every public URL
     * for it answered 404 forever. Widening the parser to match storage can only
     * make more weblogs reachable; it cannot break one that already worked.
     */
    private static boolean isValidWeblogHandle(String handle) {
        if (StringUtils.isEmpty(handle)) {
            return false;
        }
        for (int i = 0; i < handle.length(); i++) {
            char c = handle.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }


    public String getWeblogHandle() {
        return weblogHandle;
    }

    public void setWeblogHandle(String weblogHandle) {
        this.weblogHandle = weblogHandle;
    }
    
    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getPathInfo() {
        return pathInfo;
    }

    public void setPathInfo(String pathInfo) {
        this.pathInfo = pathInfo;
    }

    public Weblog getWeblog() {
        
        if(weblog == null && weblogHandle != null) {
            try {
                weblog = weblogger().getWeblogManager()
                        .getWeblogByHandle(weblogHandle, Boolean.TRUE);
            } catch (WebloggerException ex) {
                log.error("Error looking up weblog {}", weblogHandle, ex);
            }
        }
        
        return weblog;
    }

    public void setWeblog(Weblog weblog) {
        this.weblog = weblog;
    }
    
    
    /**
     * Get the Locale instance to be used for this request.
     *
     * The Locale is determined via these rules ...
     *   1. if a locale is explicitly specified, then it is used
     *   2. if no locale is specified, then use the weblog default locale
     */
    public Locale getLocaleInstance() {
        
        if(localeInstance == null && locale != null) {
            localeInstance = LocaleSegment.toLocale(locale);
        } else if(localeInstance == null) {
            localeInstance = getWeblog().getLocaleInstance();
        }
        
        return localeInstance;
    }

    public void setLocaleInstance(Locale localeInstance) {
        this.localeInstance = localeInstance;
    }
    
}
