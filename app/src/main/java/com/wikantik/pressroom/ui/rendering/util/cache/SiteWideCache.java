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

import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.util.PressroomConstants;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.WeblogFeedRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.util.cache.Cache;
import com.wikantik.pressroom.util.cache.CacheHandler;
import com.wikantik.pressroom.util.cache.CacheManager;
import com.wikantik.pressroom.util.cache.ExpiringCacheEntry;


/**
 * Cache for site-wide weblog content.
 */
public final class SiteWideCache implements CacheHandler {
    
    private static final Logger log = LoggerFactory.getLogger(SiteWideCache.class);
    
    // a unique identifier for this cache, this is used as the prefix for
    // pressroom config properties that apply to this cache
    public static final String CACHE_ID = "cache.sitewide";
    
    // keep cached content
    private boolean cacheEnabled = true;
    private Cache contentCache = null;
    
    // keep a cached version of last expired time
    private ExpiringCacheEntry lastUpdateTime = null;

    /**
     * The real answer to "is this the site-wide weblog?", which needs a
     * bootstrapped Weblogger and a database behind it.
     */
    static final Predicate<String> SITE_WIDE_WEBLOG = WebloggerRuntimeConfig::isSiteWideWeblog;

    /**
     * How this cache decides whether a changed category or template belongs
     * to the site-wide weblog. Held behind a seam so that the invalidation
     * rules can be tested for what they are -- rules -- without standing up
     * the runtime config they normally ask.
     */
    private Predicate<String> siteWideWeblog = SITE_WIDE_WEBLOG;

    // reference to our singleton instance
    private static final SiteWideCache singletonInstance = new SiteWideCache();

    
    private SiteWideCache() {
        this(WebloggerConfig.getBooleanProperty(CACHE_ID+".enabled"));
    }


    /**
     * Private -- construction with an explicit flag is for tests only, via
     * {@link #newForTest(boolean)}. The singleton itself reads the flag once,
     * when the class is loaded, and running with the site-wide cache disabled
     * is a supported (and, in development, the usual) configuration.
     */
    private SiteWideCache(boolean cacheEnabled) {

        this.cacheEnabled = cacheEnabled;

        Map<String, String> cacheProps = new HashMap<>();
        cacheProps.put("id", CACHE_ID);
        Enumeration<Object> allProps = WebloggerConfig.keys();
        String prop;
        while(allProps.hasMoreElements()) {
            prop = (String) allProps.nextElement();
            
            // we are only interested in props for this cache
            if(prop.startsWith(CACHE_ID+".")) {
                cacheProps.put(prop.substring(CACHE_ID.length()+1), 
                        WebloggerConfig.getProperty(prop));
            }
        }
        
        log.info("{}", cacheProps);
        
        if(cacheEnabled) {
            contentCache = CacheManager.constructCache(this, cacheProps);
        } else {
            log.warn("Caching has been DISABLED");
        }
    }
    
    
    public static SiteWideCache getInstance() {
        return singletonInstance;
    }


    /**
     * Package private for unit tests: builds an instance with caching forced
     * on or off, bypassing the singleton and the config-file read its
     * constructor otherwise does. A static factory rather than a
     * package-private constructor so the constructor itself can stay
     * private (SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR).
     */
    static SiteWideCache newForTest(boolean cacheEnabled) {
        return new SiteWideCache(cacheEnabled);
    }
    
    
    public Object get(String key) {
        
        if (!cacheEnabled) {
            return null;
        }
        
        Object entry = contentCache.get(key);
        
        if(entry == null) {
            log.debug("MISS {}", key);
        } else {
            log.debug("HIT {}", key);
        }
        
        return entry;
    }
    
    
    public void put(String key, Object value) {
        
        if (!cacheEnabled) {
            return;
        }
        
        contentCache.put(key, value);
        log.debug("PUT {}", key);
    }

    
    public void remove(String key) {
        
        if (!cacheEnabled) {
            return;
        }
        
        contentCache.remove(key);
        log.debug("REMOVE {}", key);
    }
    
    
    public void clear() {
        
        if (!cacheEnabled) {
            return;
        }
        
        contentCache.clear();
        this.lastUpdateTime = null;
        log.debug("CLEAR");
    }
    
    
    public Date getLastModified() {
        
        Date lastModified = null;
        
        // first try our cached version
        if(this.lastUpdateTime != null) {
            lastModified = (Date) this.lastUpdateTime.getValue();
        }
        
        // still null, we need to get a fresh value
        if(lastModified == null) {
            lastModified = new Date();
            this.lastUpdateTime = new ExpiringCacheEntry(lastModified, PressroomConstants.FIFTEEN_MIN_IN_MS);
        }
        
        return lastModified;
    }
    
    
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
    /**
     * Generate a cache key from a parsed weblog page request.
     * This generates a key of the form ...
     *
     * page/<handle>[/entry/<anchor>][/<language>][/page=<num>][/user=<user>][/qp=<params>]
     *   or
     * page/<handle>[/pageslug/<slug>][/page/<weblogPage>][/date/<date>][/cat/<category>][/tags/<tags>][/<language>][/page=<num>][/user=<user>][/qp=<params>]
     *
     *
     * examples ...
     *
     * cache.sitewide:page/foo/en/page=0
     * cache.sitewide:page/foo/entry/entry_anchor/en
     * cache.sitewide:page/foo/pageslug/about/en/page=0
     * cache.sitewide:page/foo/date/20051110/en/page=0
     * cache.sitewide:page/foo/cat/MyCategory/en/page=0/user=myname
     *
     * Every segment built from request data is labelled and escaped: a category
     * called "en" must not produce the key of the English front page.
     */
    public String generateKey(WeblogPageRequest pageRequest) {

        StringBuilder key = new StringBuilder(128);

        key.append(CACHE_ID).append(':');
        key.append("page/");
        key.append(pageRequest.getWeblogHandle());

        if(pageRequest.getWeblogAnchor() != null) {
            // may contain spaces or other bad chars
            key.append("/entry/").append(CacheKeys.encode(pageRequest.getWeblogAnchor()));
        } else {

            if(pageRequest.getPageSlug() != null) {
                // the raw path segment of a bare-slug page (/<handle>/about),
                // read here rather than the WeblogPage it resolves to, so
                // that generating a key never queries the database.
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
        if(!pageRequest.getCustomParams().isEmpty()) {
            key.append("/qp=").append(CacheKeys.params(pageRequest.getCustomParams()));
        }

        return key.toString();
    }
    // CPD-ON
    
    
    /**
     * Generate a cache key from a parsed weblog feed request.
     * This generates a key of the form ...
     *
     * feed/<handle>/<type>/<format>[/search/<term>][/cat/<category>][/language][/excerpts][/tags/<tags>]
     *
     * examples ...
     *
     * cache.sitewide:feed/foo/entries/atom/en
     * cache.sitewide:feed/foo/entries/atom/cat/MyCategory/en
     * cache.sitewide:feed/foo/entries/atom/en/excerpts
     *
     * Every segment built from request data is labelled and escaped: a search
     * for "foo/en" must not produce the key of the English feed for "foo".
     */
    public String generateKey(WeblogFeedRequest feedRequest) {
        
        StringBuilder key = new StringBuilder(128);
        
        key.append(CACHE_ID).append(':');
        key.append("feed/");
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

        if(feedRequest.getLocale() != null) {
            key.append('/').append(feedRequest.getLocale());
        }

        if(feedRequest.isExcerpts()) {
            key.append("/excerpts");
        }

        if(feedRequest.getTags() != null && !feedRequest.getTags().isEmpty()) {
            key.append("/tags/").append(CacheKeys.tags(feedRequest.getTags()));
        }

        return key.toString();
    }
    
    
    /**
     * A weblog entry has changed.
     */
    @Override
    public void invalidate(WeblogEntry entry) {
        
        if (!cacheEnabled) {
            return;
        }
        
        this.contentCache.clear();
        this.lastUpdateTime = null;
    }
    
    
    /**
     * A weblog has changed.
     */
    @Override
    public void invalidate(Weblog website) {
        
        if (!cacheEnabled) {
            return;
        }
        
        this.contentCache.clear();
        this.lastUpdateTime = null;
    }
    
    
    /**
     * A user profile has changed.
     */
    @Override
    public void invalidate(User user) {
        // ignored
    }


    /**
     * A category has changed.
     */
    @Override
    public void invalidate(WeblogCategory category) {
        if(siteWideWeblog.test(category.getWeblog().getHandle())) {
            invalidate(category.getWeblog());
        }
    }


    /**
     * A weblog template has changed.
     */
    @Override
    public void invalidate(WeblogTemplate template) {
        if(siteWideWeblog.test(template.getWeblog().getHandle())) {
            invalidate(template.getWeblog());
        }
    }


    /**
     * Replace the site-wide weblog check. Package private, for tests only; pass
     * {@link #SITE_WIDE_WEBLOG} back in to restore the real one.
     */
    void setSiteWideWeblogCheck(Predicate<String> check) {
        this.siteWideWeblog = check;
    }

}
