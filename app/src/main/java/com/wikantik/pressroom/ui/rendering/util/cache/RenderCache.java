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

import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.util.cache.CachedContent;

/**
 * The one cache a rendering servlet talks to for a given request.
 *
 * <p>A weblog is either the site-wide front page or it is not, and that answer
 * decides which cache holds its rendered output, how a cached copy expires, and
 * what its key looks like. The servlets used to re-ask that question at every
 * cache operation -- {@code if (isSiteWide) siteWideCache.x() else
 * weblogPageCache.x()}, four or five times down one method -- which put the
 * same decision in several places and made it possible for them to disagree.
 * Asking once, at the top of the request, and holding the answer as a
 * {@code RenderCache} is the whole point of this interface.
 *
 * <p>It is deliberately an <em>adapter</em> over the three cache classes rather
 * than an interface they implement. Their expiry contracts genuinely differ --
 * see the note in {@link SiteWideCache} -- and collapsing them into a shared
 * base would be a behavioural change wearing cleanup's clothes. Nothing here
 * changes how any cache behaves; this only gives the servlets one name for
 * "the cache for this request".
 *
 * @param <R> the parsed request this cache builds keys from, which is what
 *            differs between the page and feed servlets
 */
public interface RenderCache<R> {

    /**
     * The cache key for this request. Keys carry their cache's id, so the
     * site-wide and per-weblog copies of one page can never be served for
     * each other.
     */
    String generateKey(R request);

    /**
     * The cached rendering for {@code key}, or null when there is none to
     * serve.
     *
     * @param lastModified when the weblog last changed. The per-weblog caches
     *                     expire an entry against this; the site-wide cache
     *                     has no such notion and ignores it, because it is
     *                     dropped wholesale through {@code CacheManager}
     *                     instead. Passing it on both sides keeps the caller
     *                     free of that distinction, which is the point --
     *                     it does not give the site-wide cache an expiry it
     *                     does not have.
     */
    CachedContent get(String key, long lastModified);

    /** Stores a rendering under {@code key}. */
    void put(String key, CachedContent content);

    /**
     * When the content behind this request last changed, for the
     * {@code Last-Modified} header and the 304 check.
     *
     * <p>The site-wide answer comes from the cache itself; the per-weblog
     * answer comes from the weblog, falling back to now when it has never
     * been recorded.
     */
    long lastModified(Weblog weblog);

    /** Drops everything, used when a theme is reloaded in development. */
    void clear();
}
