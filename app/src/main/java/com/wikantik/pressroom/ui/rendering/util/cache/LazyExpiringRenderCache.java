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
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.util.cache.Cache;
import com.wikantik.pressroom.util.cache.CacheManager;
import com.wikantik.pressroom.util.cache.LazyExpiringCacheEntry;

/**
 * The half of a per-weblog render cache that {@link WeblogPageCache} and
 * {@link WeblogFeedCache} have always had in common: reading their own
 * configuration, holding a {@link Cache}, and expiring an entry lazily against
 * the weblog's {@code lastModified}.
 *
 * <p>These two caches are identical in contract -- neither registers a
 * {@link com.wikantik.pressroom.util.cache.CacheHandler}, so
 * {@code CacheManager} never reaches either, and a cached rendering is only
 * ever withheld once the weblog has changed under it. What differs is what
 * they cache and how they build a key, which is why {@code generateKey} stays
 * on the subclasses and is not modelled here.
 *
 * <p>{@link SiteWideCache} is deliberately <em>not</em> part of this
 * hierarchy. It is the one render cache registered as a CacheHandler, it is
 * invalidated eagerly and wholesale, and its {@code get} takes no timestamp
 * because it has no per-weblog expiry to apply. Giving it this base would mean
 * giving it an expiry contract it does not have. Callers that want to treat
 * all three uniformly have {@link RenderCache}, which adapts them without
 * merging them.
 *
 * <p>The subclass supplies its own {@link Logger} rather than inheriting one,
 * so that every line these methods emit is still attributed to the concrete
 * cache. An operator reading {@code HIT}/{@code MISS} lines needs to know
 * which cache produced them, and a base-class logger would have quietly
 * relabelled every one of them.
 */
abstract class LazyExpiringRenderCache {

    private final Logger log;
    private final boolean cacheEnabled;
    private final Cache contentCache;

    /**
     * @param cacheId      the cache's own property prefix; every
     *                     {@code <cacheId>.*} property is passed to the cache
     *                     factory, so reading the wrong prefix here would
     *                     silently resize the cache
     * @param cacheEnabled false makes every operation a no-op, and leaves no
     *                     backing Cache to touch -- which is why each method
     *                     checks it before dereferencing anything
     * @param log          the concrete cache's logger
     */
    protected LazyExpiringRenderCache(String cacheId, boolean cacheEnabled, Logger log) {

        this.log = log;
        this.cacheEnabled = cacheEnabled;

        Map<String, String> cacheProps = new HashMap<>();
        cacheProps.put("id", cacheId);
        Enumeration<Object> allProps = WebloggerConfig.keys();
        String prop;
        while (allProps.hasMoreElements()) {
            prop = (String) allProps.nextElement();

            // we are only interested in props for this cache
            if (prop.startsWith(cacheId + ".")) {
                cacheProps.put(prop.substring(cacheId.length() + 1),
                        WebloggerConfig.getProperty(prop));
            }
        }

        log.info("{}", cacheProps);

        if (cacheEnabled) {
            // null: these caches register no CacheHandler, which is what makes
            // lazy expiry their only eviction path
            this.contentCache = CacheManager.constructCache(null, cacheProps);
        } else {
            this.contentCache = null;
            log.warn("Caching has been DISABLED");
        }
    }

    /**
     * The cached rendering for {@code key}, or null when there is none to
     * serve -- either because nothing was cached under it, or because the
     * weblog has changed since it was.
     */
    public Object get(String key, long lastModified) {

        if (!cacheEnabled) {
            return null;
        }

        Object entry = null;

        LazyExpiringCacheEntry lazyEntry = (LazyExpiringCacheEntry) this.contentCache.get(key);
        if (lazyEntry != null) {
            entry = lazyEntry.getValue(lastModified);

            if (entry != null) {
                log.debug("HIT {}", key);
            } else {
                log.debug("HIT-EXPIRED {}", key);
            }

        } else {
            log.debug("MISS {}", key);
        }

        return entry;
    }

    public void put(String key, Object value) {

        if (!cacheEnabled) {
            return;
        }

        contentCache.put(key, new LazyExpiringCacheEntry(value));
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
        log.debug("CLEAR");
    }
}
