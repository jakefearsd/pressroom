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
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.util.cache.CachedContent;

/**
 * {@link RenderCache} over {@link WeblogPageCache}, for an ordinary weblog's
 * rendered pages.
 *
 * <p>This side does honour {@code lastModified}: the page cache registers no
 * CacheHandler, so nothing ever invalidates it eagerly and a cached page is
 * withheld only once the weblog's own {@code lastModified} moves past it.
 * Saving a template or an entry bumps that field, and that is the entire
 * eviction mechanism.
 */
final class WeblogPageRenderCache implements RenderCache<WeblogPageRequest> {

    private final WeblogPageCache cache;

    WeblogPageRenderCache(WeblogPageCache cache) {
        this.cache = cache;
    }

    @Override
    public String generateKey(WeblogPageRequest request) {
        return cache.generateKey(request);
    }

    @Override
    public CachedContent get(String key, long lastModified) {
        return (CachedContent) cache.get(key, lastModified);
    }

    @Override
    public void put(String key, CachedContent content) {
        cache.put(key, content);
    }

    @Override
    public long lastModified(Weblog weblog) {
        return RenderCaches.lastModifiedOfPage(weblog);
    }

    @Override
    public void clear() {
        cache.clear();
    }
}
