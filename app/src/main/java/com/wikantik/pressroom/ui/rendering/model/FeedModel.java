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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import com.wikantik.pressroom.pojos.wrapper.WeblogWrapper;
import com.wikantik.pressroom.ui.rendering.pagers.Pager;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogEntriesListPager;
import com.wikantik.pressroom.ui.rendering.util.WeblogFeedRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogRequest;


/**
 * Model which provides information needed to render a feed.
 */
public class FeedModel implements Model {
    
    /**
     * How many entries a feed carries, read fresh on every request.
     *
     * <p>This used to be a {@code static final} field initialised when the class
     * was first loaded. That made the value depend on whether the runtime
     * properties table happened to be reachable at class-load time, and meant an
     * administrator changing the setting in the admin UI saw no effect until the
     * application was restarted. {@code ConfigModel.getFeedSize()} has always
     * read it live; this keeps the two consistent.
     */
    private static int defaultEntries() {
        return WebloggerRuntimeConfig.getIntProperty("site.newsfeeds.defaultEntries");
    }

    private WeblogFeedRequest feedRequest = null;
    private URLStrategy urlStrategy = null;
    private Weblogger weblogger = null;
    private Weblog weblog = null;
    
    
    @Override
    public void init(Map<String, Object> initData) throws WebloggerException {
        
        // we expect the init data to contain a weblogRequest object
        WeblogRequest weblogRequest = (WeblogRequest) initData.get("parsedRequest");
        if(weblogRequest == null) {
            throw new WebloggerException("expected weblogRequest from init data");
        }
        
        // PageModel only works on page requests, so cast weblogRequest
        // into a WeblogPageRequest and if it fails then throw exception
        if(weblogRequest instanceof WeblogFeedRequest) {
            this.feedRequest = (WeblogFeedRequest) weblogRequest;
        } else {
            throw new WebloggerException("weblogRequest is not a WeblogFeedRequest."+
                    "  FeedModel only supports feed requests.");
        }
        
        // Both are required -- no fallback; the pager this model builds takes
        // the facade from here.
        weblogger = ModelLoader.requireWeblogger(initData);
        urlStrategy = ModelLoader.requireUrlStrategy(initData);

        // extract weblog object
        weblog = feedRequest.getWeblog();
    }
    
    
    /** Template context name to be used for model */
    @Override
    public String getModelName() {
        return "model";
    }
    
    
    /**
     * Get the weblog locale used to render this page, null if no locale.
     */
    public String getLocale() {
        return feedRequest.getLocale();
    }
    
    
    /**
     * Get weblog being displayed.
     */
    public WeblogWrapper getWeblog() {
        return WeblogWrapper.wrap(weblog, urlStrategy, weblogger);
    }
    
    
    /**
     * Get category path or name specified by request.
     */
    public boolean getExcerpts() {
        return feedRequest.isExcerpts();
    }
    
    
    /**
     * Get category path or name specified by request.
     */
    public String getCategoryName() {
        return feedRequest.getWeblogCategoryName();
    }
    
    /**
     * Gets most recent entries limited by: weblog and category specified in 
     * request plus the weblog.entryDisplayCount.
     */
    public Pager<WeblogEntryWrapper> getWeblogEntriesPager() {
        return new FeedEntriesPager(feedRequest);        
    }
    
    
    /**
     * Returns the list of tags specified in the request /?tags=foo+bar
     * @return
     */
    public List<String> getTags() {
        return feedRequest.getTags();
    }

    /**
     * Add the filters this feed was requested with to a set of URL parameters,
     * so that a link built from them stays inside the same slice of the blog.
     *
     * <p>Returns a copy rather than mutating the argument: {@code AbstractPager}
     * builds its next/previous links from {@code Map.of("page", ...)}, which is
     * immutable, so writing into it threw {@code UnsupportedOperationException}
     * and broke paging on every filtered feed.
     */
    private static Map<String, String> withFeedFilters(WeblogFeedRequest feedRequest,
            Map<String, String> params) {

        Map<String, String> withFilters = new HashMap<>(params);

        // RAW values: URLUtilities.getQueryString encodes what it is given, so
        // encoding here too would double-encode -- a category "Tech News"
        // would reach the next request as the literal "Tech+News". The tags
        // parameter is space-separated for the same reason: Utilities
        // .splitStringAsTags splits the DECODED value on spaces, and the '+'
        // that used to be written here by hand is just form encoding for one.
        List<String> tags = feedRequest.getTags();
        if(tags != null && !tags.isEmpty()) {
            withFilters.put("tags", String.join(" ", tags));
        }
        String category = feedRequest.getWeblogCategoryName();
        if(category != null && !category.isBlank()) {
            withFilters.put("cat", category);
        }
        if(feedRequest.isExcerpts()) {
            withFilters.put("excerpts", "true");
        }

        return withFilters;
    }

    public class FeedEntriesPager extends WeblogEntriesListPager {
        
        private final WeblogFeedRequest feedRequest;
        
        public FeedEntriesPager(WeblogFeedRequest feedRequest) {
            super(urlStrategy, weblogger, urlStrategy.getWeblogFeedURL(feedRequest.getWeblog(), 
                    feedRequest.getLocale(), feedRequest.getType(),
                    feedRequest.getFormat(), null, null, null, false, true), 
                    feedRequest.getWeblog(), null, feedRequest.getWeblogCategoryName(), feedRequest.getTags(),
                    feedRequest.getLocale(), -1, feedRequest.getPage(), defaultEntries());
            this.feedRequest = feedRequest;
        }
        
        @Override
        protected String createURL(String url, Map<String, String> params) {
            return super.createURL(url, withFeedFilters(feedRequest, params));
        }

        @Override
        public String getUrl() {
            return createURL(super.getUrl(), new HashMap<>());
        }
    }

}
