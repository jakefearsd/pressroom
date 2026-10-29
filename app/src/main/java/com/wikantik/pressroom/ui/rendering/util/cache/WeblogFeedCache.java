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

package com.wikantik.pressroom.ui.rendering.util.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.ui.rendering.util.WeblogFeedRequest;


/**
 * Cache for weblog feed content.
 */
public final class WeblogFeedCache extends LazyExpiringRenderCache {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogFeedCache.class);
    
    // a unique identifier for this cache, this is used as the prefix for
    // roller config properties that apply to this cache
    public static final String CACHE_ID = "cache.weblogfeed";
    
    // reference to our singleton instance
    private static final WeblogFeedCache singletonInstance = new WeblogFeedCache();
    
    
    private WeblogFeedCache() {
        this(WebloggerConfig.getBooleanProperty(CACHE_ID+".enabled"));
    }


    /**
     * Private -- construction with an explicit flag is for tests only, via
     * {@link #newForTest(boolean)}. The singleton itself reads the flag once,
     * when the class is loaded, and running with the feed cache disabled is a
     * supported (and, in development, the usual) configuration.
     */
    private WeblogFeedCache(boolean cacheEnabled) {
        super(CACHE_ID, cacheEnabled, log);
    }
    
    
    public static WeblogFeedCache getInstance() {
        return singletonInstance;
    }


    /**
     * Package private for unit tests: builds an instance with caching forced
     * on or off, bypassing the singleton and the config-file read its
     * constructor otherwise does.
     */
    static WeblogFeedCache newForTest(boolean cacheEnabled) {
        return new WeblogFeedCache(cacheEnabled);
    }


    // get/put/remove/clear now live on LazyExpiringRenderCache, which both
    // per-weblog render caches share. The CPD-OFF that used to bracket them
    // here is gone with the duplication it excused.


    /**
     * Generate a cache key from a parsed weblog feed request.
     * This generates a key of the form ...
     *
     * <handle>/<type>/<format>[/search/<term>][/cat/<category>][/tags/<tags>][/language][/excerpts]
     *
     * examples ...
     *
     * cache.weblogfeed:foo/entries/atom/en
     * cache.weblogfeed:foo/entries/atom/cat/MyCategory/en
     * cache.weblogfeed:foo/entries/atom/en/excerpts
     *
     * Every segment built from request data is labelled and escaped: a search
     * for "foo/en" must not produce the key of the English feed for "foo".
     */
    public String generateKey(WeblogFeedRequest feedRequest) {

        StringBuilder key = new StringBuilder(128);

        key.append(CACHE_ID).append(':');
        key.append(feedRequest.getWeblogHandle());

        key.append('/').append(feedRequest.getType());
        key.append('/').append(feedRequest.getFormat());

        if (feedRequest.getTerm() != null) {
            // arbitrary text straight off the query string
            key.append("/search/").append(CacheKeys.encode(feedRequest.getTerm()));
        }

        if(feedRequest.getWeblogCategoryName() != null) {
            key.append("/cat/").append(CacheKeys.encode(feedRequest.getWeblogCategoryName()));
        }

        if(feedRequest.getTags() != null && !feedRequest.getTags().isEmpty()) {
            key.append("/tags/").append(CacheKeys.tags(feedRequest.getTags()));
        }

        if(feedRequest.getLocale() != null) {
            key.append('/').append(feedRequest.getLocale());
        }

        if(feedRequest.isExcerpts()) {
            key.append("/excerpts");
        }

        return key.toString();
    }

}
