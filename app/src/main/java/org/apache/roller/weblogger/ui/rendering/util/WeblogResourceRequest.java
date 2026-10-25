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

package org.apache.roller.weblogger.ui.rendering.util;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.roller.weblogger.business.Weblogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Represents a request for a weblog resource file.
 *
 * /roller-ui/rendering/resources/*
 */
public class WeblogResourceRequest extends WeblogRequest {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogResourceRequest.class);
        
    // lightweight attributes
    private String resourcePath = null;
    
    
    public WeblogResourceRequest() {}
    
    
    /**
     * Construct the WeblogResourceRequest by parsing the incoming url
     */
    public WeblogResourceRequest(Weblogger weblogger, HttpServletRequest request) 
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
         * any path is okay ...
         *
         * /<path>/<to>/<resource>
         */
        if(pathInfo != null && pathInfo.trim().length() > 1) {
            
            this.resourcePath = pathInfo;
            if(pathInfo.startsWith("/")) {
                this.resourcePath = pathInfo.substring(1);
            }
            
            // Fix for ROL-1065: even though a + should mean space in a URL, folks
            // who upload files with plus signs expect them to work without
            // escaping. This is essentially what other systems do (e.g. JIRA) to
            // enable this.
            this.resourcePath = this.resourcePath.replace("+", "%2B");
            
            // now we really decode the URL
            this.resourcePath = decodeOrReject(this.resourcePath, request);
        
        } else {
            throw new InvalidRequestException("invalid resource path info, "+
                    request.getRequestURL());
        }
        
        log.debug("resourcePath = {}", this.resourcePath);
    }
    
    public String getResourcePath() {
        return resourcePath;
    }

    public void setResourcePath(String resourcePath) {
        this.resourcePath = resourcePath;
    }
            
    @Override
    protected boolean isLocale(String potentialLocale) {
        // We don't support locales in the resource Servlet so we've got to 
        // keep parent from treating upload sub-directory name as a locale.
        return false;
    }
}
