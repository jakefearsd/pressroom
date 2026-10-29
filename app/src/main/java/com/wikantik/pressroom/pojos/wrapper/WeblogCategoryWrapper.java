/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package com.wikantik.pressroom.pojos.wrapper;

import java.util.List;
import java.util.stream.Collectors;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;


/**
 * Pojo safety wrapper for WeblogCategory objects.
 */
public final class WeblogCategoryWrapper {
    
    // keep a reference to the wrapped pojo
    private final WeblogCategory pojo;
    
    // url strategy to use for any url building
    private final URLStrategy urlStrategy;
    
    
    // this is private so that we can force the use of the .wrap(pojo) method
    // the business tier, for the lookups the template API needs
    private final Weblogger weblogger;

    private WeblogCategoryWrapper(WeblogCategory toWrap, URLStrategy strat, Weblogger weblogger) {
        this.pojo = toWrap;
        this.urlStrategy = strat;
        this.weblogger = weblogger;
    }
    
    
    // wrap the given pojo if it is not null
    public static WeblogCategoryWrapper wrap(WeblogCategory toWrap, URLStrategy strat, Weblogger weblogger) {
        if (toWrap != null) {
            return new WeblogCategoryWrapper(toWrap, strat, weblogger);
        }
        
        return null;
    }
    
    
    public String getId() {
        return this.pojo.getId();
    }
    
    
    public String getName() {
        return this.pojo.getName();
    }
    
    
    public String getDescription() {
        return this.pojo.getDescription();
    }
    
    
    public String getImage() {
        return this.pojo.getImage();
    }

    public WeblogWrapper getWebsite() {
        return WeblogWrapper.wrap(this.pojo.getWeblog(), urlStrategy, weblogger);
    }

    /**
     * Retrieve all weblog entries in this category.
     *
     * @param publishedOnly True if desired to return only published entries;
     *                      false is the "all, trash included" form (see
     *                      {@link WeblogEntrySearchCriteria#forCategory})
     */
    public List<WeblogEntryWrapper> retrieveWeblogEntries(boolean publishedOnly) throws WebloggerException {
        return weblogger.getWeblogEntryManager()
                .getWeblogEntries(WeblogEntrySearchCriteria.forCategory(this.pojo, publishedOnly))
                .stream()
                .map(entry -> WeblogEntryWrapper.wrap(entry, urlStrategy, weblogger))
                .collect(Collectors.toList());
    }
    
    
    /**
     * Returns true if category is in use.
     */
    public boolean isInUse() {
        try {
            return weblogger.getWeblogEntryManager().isWeblogCategoryInUse(this.pojo);
        } catch (WebloggerException e) {
            throw new RuntimeException(e);
        }
    }
    
}
