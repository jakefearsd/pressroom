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

package com.wikantik.pressroom.business;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.util.URLUtilities;


/**
 * A URLStrategy used by the preview rendering system.
 */
public class PreviewURLStrategy extends MultiWeblogURLStrategy {
    
    private final String previewTheme;
    private static final String PREVIEW_URL_SEGMENT = "/roller-ui/authoring/preview/";
    
    public PreviewURLStrategy(String theme) {
        previewTheme = theme;
    }
    
    
    /**
     * Previews live under the authoring servlet rather than at the weblog's own
     * address, and are never on a custom domain -- an author previewing a theme
     * is on the site host by definition.
     */
    @Override
    protected String weblogRoot(Weblog weblog, String locale, boolean absolute) {

        StringBuilder url = new StringBuilder(URL_BUFFER_SIZE);

        if (absolute) {
            url.append(WebloggerRuntimeConfig.getAbsoluteContextURL());
        } else {
            url.append(WebloggerRuntimeConfig.getRelativeContextURL());
        }

        url.append(PREVIEW_URL_SEGMENT).append(weblog.getHandle()).append('/');

        if (locale != null) {
            url.append(locale).append('/');
        }

        return url.toString();
    }

    /**
     * The preview root already names the weblog by handle, the site space
     * included, so an asset url needs no second handle segment.
     */
    @Override
    protected String assetRoot(Weblog weblog, String locale, boolean absolute) {
        return weblogRoot(weblog, locale, absolute);
    }

    /**
     * Every url built while previewing carries the theme being previewed, or a
     * link out of the preview would land on the live weblog.
     */
    @Override
    protected Map<String, String> commonParams() {
        return previewTheme == null
                ? Map.of()
                : Map.of("theme", previewTheme);
    }


    /**
     * Get url for a given *preview* weblog entry.  
     * Optionally for a certain locale.
     */
    @Override
    public String getWeblogEntryURL(Weblog weblog,
                                    String locale,
                                    String previewAnchor,
                                    boolean absolute) {
        
        if(weblog == null) {
            return null;
        }

        Map<String, String> params = new HashMap<>(commonParams());
        if (previewAnchor != null) {
            params.put("previewEntry", previewAnchor);
        }

        return weblogRoot(weblog, locale, absolute)
                + URLUtilities.getQueryString(params);
    }
    
    
    

    
    
    /**
     * Get a url to a *preview* resource on a given weblog.
     */
    @Override
    public String getWeblogResourceURL(Weblog weblog, String filePath, boolean absolute) {
        
        // filePath is checked for the same reason the parent checks it: this is
        // reached from templates, and a missing path must produce no url rather
        // than a NullPointerException on the startsWith below.
        if(weblog == null || StringUtils.isEmpty(filePath)) {
            return null;
        }
        
        StringBuilder url = new StringBuilder(URL_BUFFER_SIZE);
        
        if(absolute) {
            url.append(WebloggerRuntimeConfig.getAbsoluteContextURL());
        } else {
            url.append(WebloggerRuntimeConfig.getRelativeContextURL());
        }
        
        url.append("/roller-ui/authoring/previewresource/").append(weblog.getHandle()).append('/');
        
        // encodePath rather than a raw append, again matching the parent: it
        // escapes each segment and leaves '/' alone, so a file name with a
        // space or a '?' cannot break out of the path.
        if(filePath.startsWith("/")) {
            url.append(URLUtilities.encodePath(filePath.substring(1)));
        } else {
            url.append(URLUtilities.encodePath(filePath));
        }
        
        Map<String, String> params = Collections.emptyMap();
        if(previewTheme != null && !WeblogTheme.CUSTOM.equals(previewTheme)) {
            params = Map.of("theme", previewTheme);
        }
        
        return url.append(URLUtilities.getQueryString(params)).toString();
    }
    
}
