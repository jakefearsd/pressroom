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
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;


/**
 * Cache for weblog page content.
 */
public final class WeblogPageCache extends LazyExpiringRenderCache {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogPageCache.class);
    
    // a unique identifier for this cache, this is used as the prefix for
    // pressroom config properties that apply to this cache
    public static final String CACHE_ID = "cache.weblogpage";
    
    // reference to our singleton instance
    private static final WeblogPageCache singletonInstance = new WeblogPageCache();
    
    
    private WeblogPageCache() {
        this(WebloggerConfig.getBooleanProperty(CACHE_ID+".enabled"));
    }


    /**
     * Private -- construction with an explicit flag is for tests only, via
     * {@link #newForTest(boolean)}. The singleton itself reads the flag once,
     * when the class is loaded, and running with the page cache disabled is a
     * supported (and, in development, the usual) configuration.
     */
    private WeblogPageCache(boolean cacheEnabled) {
        super(CACHE_ID, cacheEnabled, log);
    }
    
    
    public static WeblogPageCache getInstance() {
        return singletonInstance;
    }


    /**
     * Package private for unit tests: builds an instance with caching forced
     * on or off, bypassing the singleton and the config-file read its
     * constructor otherwise does.
     */
    static WeblogPageCache newForTest(boolean cacheEnabled) {
        return new WeblogPageCache(cacheEnabled);
    }


    // get/put/remove/clear now live on LazyExpiringRenderCache, which both
    // per-weblog render caches share. The CPD-OFF that used to bracket them
    // here is gone with the duplication it excused.


    /**
     * Generate a cache key from a parsed weblog page request.
     * This generates a key of the form ...
     *
     * <handle>[/entry/<anchor>][/<language>][/page=<num>][/user=<user>]
     *   or
     * <handle>[/pageslug/<slug>][/page/<weblogPage>][/date/<date>][/cat/<category>][/tags/<tags>][/<language>][/page=<num>][/user=<user>][/qp=<params>]
     *
     *
     * examples ...
     *
     * cache.weblogpage:foo/en/page=0
     * cache.weblogpage:foo/entry/entry_anchor/en
     * cache.weblogpage:foo/pageslug/about/en/page=0
     * cache.weblogpage:foo/date/20051110/en/page=0
     * cache.weblogpage:foo/cat/MyCategory/en/page=0/user=myname
     *
     * Every segment built from request data is labelled and escaped: a category
     * called "en" must not produce the key of the English homepage.
     */
    // CPD-OFF -- This key builder is duplicated in SiteWideCache and
    // WeblogPageCache. Note that WeblogPageCache *does* now sit on a shared
    // base (LazyExpiringRenderCache) -- just not one shared with SiteWideCache,
    // and that is the distinction this marker records. The two caches' expiry
    // contracts genuinely differ: SiteWideCache is the one render cache
    // registered as a CacheHandler (constructCache(this, ...)), so CacheManager
    // invalidates it eagerly and wholesale; WeblogPageCache registers none
    // (constructCache(null, ...)) and a rendered page is only ever expired
    // lazily against weblog.lastModified. Giving SiteWideCache that base would
    // hand it an expiry contract it does not have -- a behavioural change
    // wearing cleanup's clothes. Callers that just want "the cache for this
    // request" have RenderCache, which adapts all three without merging any.
    // See CLAUDE.md, Templates.
    public String generateKey(WeblogPageRequest pageRequest) {

        StringBuilder key = new StringBuilder(128);

        key.append(CACHE_ID).append(':');
        key.append(pageRequest.getWeblogHandle());

        if(pageRequest.getWeblogAnchor() != null) {
            // may contain spaces or other bad chars
            key.append("/entry/").append(CacheKeys.encode(pageRequest.getWeblogAnchor()));
        } else {

            if(pageRequest.getPageSlug() != null) {
                // the raw path segment of a bare-slug page (/<handle>/about),
                // read here rather than the WeblogPage it resolves to, so
                // that generating a key never queries the database. Without
                // this segment every bare-slug page on a weblog -- and the
                // weblog's homepage -- shared one cache key, so whichever
                // page rendered first was served for all of them.
                key.append("/pageslug/").append(CacheKeys.encode(pageRequest.getPageSlug()));
            }

            if(pageRequest.getWeblogPageName() != null) {
                // comes straight off the url path, so it may contain slashes
                key.append("/page/").append(CacheKeys.encode(pageRequest.getWeblogPageName()));
            }

            if(pageRequest.getWeblogDate() != null) {
                key.append("/date/").append(pageRequest.getWeblogDate());
            }

            if(pageRequest.getWeblogCategoryName() != null) {
                // may contain spaces or other bad chars
                key.append("/cat/").append(CacheKeys.encode(pageRequest.getWeblogCategoryName()));
            }

            if("tags".equals(pageRequest.getContext())
                    && pageRequest.getTags() != null && !pageRequest.getTags().isEmpty()) {
                key.append("/tags/").append(CacheKeys.tags(pageRequest.getTags()));
            }
        }

        if(pageRequest.getLocale() != null) {
            key.append('/').append(pageRequest.getLocale());
        }

        // add page number when applicable
        if(pageRequest.getWeblogAnchor() == null) {
            key.append("/page=").append(pageRequest.getPageNum());
        }

        // add login state
        if(pageRequest.getAuthenticUser() != null) {
            key.append("/user=").append(pageRequest.getAuthenticUser());
        }

        // we allow for arbitrary query params for custom pages
        if(pageRequest.getWeblogPageName() != null && !pageRequest.getCustomParams().isEmpty()) {
            key.append("/qp=").append(CacheKeys.params(pageRequest.getCustomParams()));
        }

        return key.toString();
    }
    // CPD-ON
}
