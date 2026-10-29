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
import com.wikantik.pressroom.pojos.Theme;


/**
 *
 */
public class WeblogPreviewResourceRequest extends WeblogResourceRequest {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogPreviewResourceRequest.class);
        
    // lightweight attributes
    private String themeName = null;
    
    // heavyweight attributes
    private Theme theme = null;
    
    
    public WeblogPreviewResourceRequest() {}
    
    
    public WeblogPreviewResourceRequest(Weblogger weblogger, HttpServletRequest request) 
            throws InvalidRequestException {
        
        // let parent go first
        super(weblogger, request);
        
        // all we need to worry about is the query params
        // the only param we expect is "theme"
        if(request.getParameter("theme") != null) {
            this.themeName = request.getParameter("theme");
        }
        
        log.debug("theme = {}", this.themeName);
    }
    
    public String getThemeName() {
        return themeName;
    }

    public void setThemeName(String theme) {
        this.themeName = theme;
    }
    
    // override so that previews never show login status
    @Override
    public String getAuthenticUser() {
        return null;
    }
    
    // override so that previews never show login status
    @Override
    public boolean isLoggedIn() {
        return false;
    }

    public Theme getTheme() {
        
        // no theme named means nothing to look up -- and no facade needed
        if (theme == null && themeName != null) {
            theme = PreviewThemeLookup.byName(themeName, weblogger().getThemeManager());
        }

        return theme;
    }

    public void setTheme(Theme theme) {
        this.theme = theme;
    }
}
