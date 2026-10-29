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
import com.wikantik.pressroom.pojos.Theme;
import com.wikantik.pressroom.pojos.WeblogEntry;


/**
 * Represents a request for a weblog preview.
 */
public class WeblogPreviewRequest extends WeblogPageRequest {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogPreviewRequest.class);
    
    private static final String PREVIEW_SERVLET = "/pressroom-ui/authoring/preview";
    
    // lightweight attributes
    private String themeName = null;
    private String previewEntry = null;
    private String type = "standard";
    
    // heavyweight attributes
    private Theme theme = null;
    private WeblogEntry weblogEntry = null;
    
    public WeblogPreviewRequest(Weblogger weblogger, HttpServletRequest request)
            throws InvalidRequestException {

        // let parent go first
        super(weblogger, request);
        
        // we may have a specific theme to preview
        if(request.getParameter("theme") != null) {
            this.themeName = request.getParameter("theme");
        }

        //we may need to know the type of page we are going to preview
         if(request.getParameter("type") != null) {
             this.setType(request.getParameter("type"));
         }
        
        // we may also have a specific entry to preview
        if(request.getParameter("previewEntry") != null) {
            this.previewEntry = decodeOrReject(request.getParameter("previewEntry"), request);
        }

        log.debug("theme = {}", this.themeName);
    }
    
    
    @Override
    boolean isValidDestination(String servlet) {
        return servlet != null && PREVIEW_SERVLET.equals(servlet);
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

    public String getPreviewEntry() {
        return previewEntry;
    }

    public void setPreviewEntry(String previewEntry) {
        this.previewEntry = previewEntry;
    }
    
    // if we have a preview entry we would prefer to return that
    @Override
    public WeblogEntry getWeblogEntry() {
        
        if(weblogEntry == null && 
                (previewEntry != null || super.getWeblogAnchor() != null)) {
            
            String anchor = previewEntry;
            if(previewEntry == null) {
                anchor = super.getWeblogAnchor();
            }
            
            try {
                WeblogEntryManager wmgr = weblogger().getWeblogEntryManager();
                weblogEntry = wmgr.getWeblogEntryByAnchor(getWeblog(), anchor);
            } catch (WebloggerException ex) {
                log.error("Error getting weblog entry {}", anchor, ex);
            }
        }
        
        return weblogEntry;
    }
    
    @Override
    public void setWeblogEntry(WeblogEntry weblogEntry) {
        this.weblogEntry = weblogEntry;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
