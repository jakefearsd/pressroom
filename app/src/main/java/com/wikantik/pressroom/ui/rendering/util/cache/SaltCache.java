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

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.util.PressroomConstants;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.util.cache.Cache;
import com.wikantik.pressroom.util.cache.CacheManager;
import com.wikantik.pressroom.util.cache.ExpiringCacheEntry;

/**
 * Cache for XSRF salt values. This cache is part of XSRF protection wherein 
 * each HTTP POST must be accompanied by a valid salt value, i.e. one generated 
 * by Pressroom. If you're running distributed, then you must use a distributed 
 * cache, e.g. memcached
 */
public final class SaltCache {
    
    private static final Logger log = LoggerFactory.getLogger(SaltCache.class);
    
    // a unique identifier for this cache, this is used as the prefix for
    // pressroom config properties that apply to this cache
    public static final String CACHE_ID = "cache.salt";
    
    private Cache contentCache;
    
    // reference to our singleton instance
    private static final SaltCache singletonInstance = new SaltCache();

    private SaltCache() {
        
        Map<String, String> cacheProps = new HashMap<>();
        cacheProps.put("id", CACHE_ID);
        Enumeration<Object> allProps = WebloggerConfig.keys();
        String prop;
        while(allProps.hasMoreElements()) {
            prop = (String) allProps.nextElement();
            
            // we are only interested in props for this cache
            if(prop.startsWith(CACHE_ID+".")) {
                cacheProps.put(prop.substring(CACHE_ID.length()+1), WebloggerConfig.getProperty(prop));
            }
        }
        
        log.info("{}", cacheProps);
        contentCache = CacheManager.constructCache(null, cacheProps);
    }
    
    public static SaltCache getInstance() {
        return singletonInstance;
    }
    
    
    public String get(String key) {
        Object entry = null;

        ExpiringCacheEntry lazyEntry = (ExpiringCacheEntry) this.contentCache.get(key);
        if(lazyEntry != null) {
            entry = lazyEntry.getValue();
            if(entry != null) {
                log.debug("HIT {}", key);
            } else {
                log.debug("HIT-EXPIRED {}", key);
            }
            
        } else {
            log.debug("MISS {}", key);
        }
        
        return entry != null ? entry.toString() : null;
    }
    
    
    public void put(String key, String value) {
		// expire after 60 minutes
        contentCache.put(key, new ExpiringCacheEntry(value, PressroomConstants.HOUR_IN_MS));
        log.debug("PUT {}", key);
    }
    
    
    public void remove(String key) {
        contentCache.remove(key);
        log.debug("REMOVE {}", key);
    }
    
    
    public void clear() {
        contentCache.clear();
        log.debug("CLEAR");
    }

}
