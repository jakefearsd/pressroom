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
import com.wikantik.pressroom.business.Weblogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Represents a request for a weblog resource file.
 *
 * /roller-ui/rendering/resources/*
 */
public class WeblogMediaResourceRequest extends WeblogRequest {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogMediaResourceRequest.class);
        
    // lightweight attributes
    private String resourceId = null;

    private boolean thumbnail = false;

    // requested responsive rendition width (?w=), or -1 if absent/invalid;
    // validated against the ladder set by the caller, not here
    private int width = -1;

    
    public WeblogMediaResourceRequest() {}
    
    
    /**
     * Construct the WeblogResourceRequest by parsing the incoming url
     */
    public WeblogMediaResourceRequest(Weblogger weblogger, HttpServletRequest request) 
            throws InvalidRequestException {
        
        // let our parent take care of their business first
        // parent determines weblog handle and locale if specified. The site
        // space's handle is admitted: its theme resources and media files are
        // served here, and the servlet refuses them unless WeblogRequestMapper
        // forwarded the request.
        super(weblogger, request, true);
        
        // we only want the path info left over from after our parents parsing
        String pathInfo = this.getPathInfo();
        
        // parse the request object and figure out what we've got
        log.debug("parsing path {}", pathInfo);
                
        
        /* 
         * any id is okay...
         */
        if (pathInfo != null && pathInfo.trim().length() > 1) {
            
            this.resourceId = pathInfo;
            if (pathInfo.startsWith("/")) {
                this.resourceId = pathInfo.substring(1);
            }
        
        } else {
            throw new InvalidRequestException("invalid resource path info, "+
                    request.getRequestURL());
        }

        if (request.getParameter("t") != null && "true".equals(request.getParameter("t"))) {
            thumbnail = true;
        }

        String widthParam = request.getParameter("w");
        if (widthParam != null) {
            try {
                width = Integer.parseInt(widthParam.trim());
            } catch (NumberFormatException e) {
                // not a number -- leave width unset, servlet falls back to the original
                width = -1;
            }
        }

        log.debug("resourceId = {}", this.resourceId);
    }
    
    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }
            
    @Override
    protected boolean isLocale(String potentialLocale) {
        // We don't support locales in the resource Servlet so we've got to 
        // keep parent from treating upload sub-directory name as a locale.
        return false;
    }

    /**
     * @return the thumbnail
     */
    public boolean isThumbnail() {
        return thumbnail;
    }

    /**
     * @param thumbnail the thumbnail to set
     */
    public void setThumbnail(boolean thumbnail) {
        this.thumbnail = thumbnail;
    }

    /**
     * @return the requested responsive rendition width from {@code ?w=}, or
     *         -1 if the parameter was absent or not a valid integer. The
     *         caller is responsible for validating this against the actual
     *         ladder width set.
     */
    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }
}
