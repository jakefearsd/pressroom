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
package org.apache.roller.weblogger.ui.controllers.core;

import java.util.Map;

import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.util.cache.Cache;
import org.apache.roller.weblogger.util.cache.CacheManager;

/**
 * Finished per-weblog sitemap documents, one slot per weblog and absolute
 * weblog url, each stamped with the {@code weblog.lastModified} it was built
 * at.
 *
 * <p><b>What changes a sitemap, and how each reaches this cache.</b> Its
 * entries, pages and featured images change only through saves that bump
 * {@code weblog.lastModified} (entry publish, update, unpublish, trash and
 * delete; page save and delete; a media directory's privacy toggle), so a
 * stamp that no longer matches is a miss. Its urls also depend on things
 * that are not the weblog's data at all -- {@code site.absoluteurl}, the
 * address the first request captured, the context path, the weblog's custom
 * domain -- so the slot is keyed by the weblog's absolute url, which is built
 * from exactly those. A changed url is a different slot, never a wrong hit.
 *
 * <p>Why a slot validated by a stamp rather than a key that includes the
 * stamp: superseded versions are replaced in place instead of piling up
 * until the LRU evicts them, so the cache holds at most one document per
 * weblog and host however often a weblog is edited. Entry count is bounded by
 * {@code cache.sitemap.size} (LRU), age by {@code cache.sitemap.timeout} --
 * the backstop for any change that one day reaches a sitemap without moving
 * {@code lastModified}.
 *
 * <p>Thread safety: the backing {@link Cache} synchronizes every operation
 * and the stored value is immutable. Two concurrent misses both build the
 * document and the later put wins; both builds read the same stamp, so
 * either result is correct for it. A weblog without a {@code lastModified}
 * is never cached, since nothing could ever tell its entry stale.
 *
 * <p>Built with no CacheHandler, like the per-weblog render caches:
 * {@code CacheManager.invalidate(...)} does not reach it, by design --
 * expiry is the stamp's job.
 */
final class SitemapCache {

    static final String CACHE_ID = "cache.sitemap";

    /** Null when the cache is disabled; every operation is then a miss. */
    private final Cache cache;

    SitemapCache(Cache cache) {
        this.cache = cache;
    }

    /** The cache as {@code cache.sitemap.*} in the Roller properties configures it. */
    static SitemapCache fromConfig() {
        if (!WebloggerConfig.getBooleanProperty(CACHE_ID + ".enabled", true)) {
            return new SitemapCache(null);
        }
        return new SitemapCache(CacheManager.constructCache(null, Map.of(
                "id", CACHE_ID,
                "size", WebloggerConfig.getProperty(CACHE_ID + ".size", "100"),
                "timeout", WebloggerConfig.getProperty(CACHE_ID + ".timeout", "3600"))));
    }

    /**
     * The document built for this weblog at this url when its
     * {@code lastModified} was {@code lastModified}, or null.
     */
    String get(String handle, String weblogURL, long lastModified) {
        if (cache == null) {
            return null;
        }
        Object value = cache.get(key(handle, weblogURL));
        if (value instanceof Version version && version.lastModified() == lastModified) {
            return version.xml();
        }
        return null;
    }

    void put(String handle, String weblogURL, long lastModified, String xml) {
        if (cache != null) {
            cache.put(key(handle, weblogURL), new Version(lastModified, xml));
        }
    }

    /** A handle is alphanumeric, so the first space always ends it. */
    private static String key(String handle, String weblogURL) {
        return handle + ' ' + weblogURL;
    }

    private record Version(long lastModified, String xml) {
    }
}
