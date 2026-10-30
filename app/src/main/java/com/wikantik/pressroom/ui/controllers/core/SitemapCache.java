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
package com.wikantik.pressroom.ui.controllers.core;

import java.util.Map;

import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.util.cache.Cache;
import com.wikantik.pressroom.util.cache.CacheManager;

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
 * {@code lastModified} -- and the size of any one entry by
 * {@code cache.sitemap.maxBytes}: a count alone does not bound memory when a
 * 50,000-url sitemap is ~15 MB. A larger document is still served (with
 * {@code Last-Modified} and 304s), just rebuilt on every miss.
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

    /** About 2 MB: a weblog of several thousand entries. */
    static final int DEFAULT_MAX_BYTES = 2 * 1024 * 1024;

    /** Null when the cache is disabled; every operation is then a miss. */
    private final Cache cache;

    /**
     * The largest document held, measured in characters: a sitemap is ASCII
     * (percent-encoded urls, ISO dates), so that is its size in bytes.
     */
    private final int maxBytes;

    SitemapCache(Cache cache, int maxBytes) {
        this.cache = cache;
        this.maxBytes = maxBytes;
    }

    /** The cache as {@code cache.sitemap.*} in the Pressroom properties configures it. */
    static SitemapCache fromConfig() {
        if (!WebloggerConfig.getBooleanProperty(CACHE_ID + ".enabled", true)) {
            return new SitemapCache(null, 0);
        }
        return new SitemapCache(CacheManager.constructCache(null, Map.of(
                "id", CACHE_ID,
                "size", WebloggerConfig.getProperty(CACHE_ID + ".size", "100"),
                "timeout", WebloggerConfig.getProperty(CACHE_ID + ".timeout", "3600"))),
                WebloggerConfig.getIntProperty(CACHE_ID + ".maxBytes", DEFAULT_MAX_BYTES));
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

    /**
     * Holds the document, unless it is over {@code maxBytes}: then the slot is
     * emptied instead, so the version it supersedes does not linger either.
     */
    void put(String handle, String weblogURL, long lastModified, String xml) {
        if (cache == null) {
            return;
        }
        if (xml.length() > maxBytes) {
            cache.remove(key(handle, weblogURL));
        } else {
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
