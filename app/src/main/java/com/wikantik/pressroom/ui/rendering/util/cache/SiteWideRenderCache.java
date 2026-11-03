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

import java.util.function.Function;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.util.DiscoveryGeneration;
import com.wikantik.pressroom.util.cache.CachedContent;

/**
 * {@link RenderCache} over {@link SiteWideCache}, for the weblog that is
 * serving as the site-wide front page.
 *
 * <p>One class covers both page and feed requests because {@code SiteWideCache}
 * itself has a {@code generateKey} overload for each; which one to call is
 * supplied as {@code keyFunction} rather than branched on here.
 *
 * <p>No weblog's {@code lastModified} reaches this side: site-wide content is
 * dropped wholesale through {@code CacheManager}, never expired against one
 * weblog, and a rendering is stored with the cache's invalidation version as
 * the render read it, so a render that straddles a clear is not kept. The
 * other part of the basis is the discovery generation, for the pages side
 * only ({@code followsDiscovery}; feeds carry no picks): an entry rendered
 * from an older one is a miss, and {@link #lastModified} is never earlier
 * than it, so a browser holding a page from before a reshuffle is not
 * answered 304.
 */
final class SiteWideRenderCache<R> implements RenderCache<R> {

    private final SiteWideCache cache;
    private final Function<R, String> keyFunction;
    private final boolean followsDiscovery;

    SiteWideRenderCache(SiteWideCache cache, Function<R, String> keyFunction, boolean followsDiscovery) {
        this.cache = cache;
        this.keyFunction = keyFunction;
        this.followsDiscovery = followsDiscovery;
    }

    @Override
    public String generateKey(R request) {
        return keyFunction.apply(request);
    }

    /**
     * The cache's own invalidation version, never the weblog's, and the
     * generation for a page (0 for a feed, which carries no picks).
     */
    @Override
    public Basis basis(Weblog weblog) {
        return new Basis(cache.version(), followsDiscovery ? DiscoveryGeneration.current() : 0L);
    }

    @Override
    public CachedContent get(String key, Basis current) {
        // the cache withholds an entry from before its last invalidation; the
        // business tier cannot clear it when the discovery snapshot rebuilds,
        // so an entry rendered from an older generation is a miss here
        return RenderedPage.servable(cache.get(key), followsDiscovery ? current.generation() : 0L);
    }

    @Override
    public void put(String key, CachedContent content, Basis basis) {
        cache.put(key, new RenderedPage(content, basis.generation()), basis.modified());
    }

    @Override
    public long lastModified(Weblog weblog) {
        long own = cache.getLastModified().getTime();
        return followsDiscovery ? Math.max(own, DiscoveryGeneration.current()) : own;
    }

    @Override
    public void clear() {
        cache.clear();
    }
}
