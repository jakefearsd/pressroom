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

package com.wikantik.pressroom.ui.rendering.model;

import java.util.Map;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.WeblogPreviewRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogRequest;

/**
 * Special subclass of URLModel which can change some of the urls which are
 * generated to make them work for previewing mode.
 */
public class PreviewURLModel extends URLModel {
    
    private Weblog weblog = null;

    private URLStrategy urlStrategy = null;
    
    
    @Override
    public void init(Map<String, Object> initData) throws WebloggerException {
        
        // need a weblog request so that we can know the weblog and locale
        WeblogRequest weblogRequest = (WeblogRequest) initData.get("parsedRequest");
        if(weblogRequest == null) {
            throw new WebloggerException("Expected 'weblogRequest' init param!");
        }
        
        // PreviewURLModel only works on preview requests, so cast weblogRequest
        // into a WeblogPreviewRequest and if it fails then throw exception
        if(!(weblogRequest instanceof WeblogPreviewRequest)) {
            throw new WebloggerException("weblogRequest is not a WeblogPreviewRequest."+
                    "  PreviewURLModel only supports preview requests.");
        }
        
        this.weblog = weblogRequest.getWeblog();
        this.locale = weblogRequest.getLocale();
        
        // required, no fallback: the preview servlet always installs its own
        urlStrategy = ModelLoader.requireUrlStrategy(initData);

        super.init(initData);
    }
    
    
    /**
     * We need resource urls to point to our custom PreviewResourceServlet
     * because when previewing a theme the ResourceServlet has no way of
     * knowing what theme you are previewing and thus couldn't find the
     * resources for that theme.
     */
    @Override
    public String resource(String filePath) {
        return urlStrategy.getWeblogResourceURL(weblog, filePath, true);
    }
    
}
