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
import com.wikantik.pressroom.ui.rendering.util.WeblogFeedRequest;
import com.wikantik.pressroom.util.cache.CachedContent;

/**
 * {@link RenderCache} over {@link WeblogFeedCache}, for an ordinary weblog's
 * rendered feeds.
 *
 * <p>Expiry works exactly as it does for {@link WeblogPageRenderCache}, and
 * that is worth stating plainly because a long-standing comment in these
 * files said otherwise: {@code WeblogFeedCache} registers no CacheHandler
 * either ({@code constructCache(null, ...)}), and expires lazily against the
 * weblog. Only {@code SiteWideCache} is invalidated through
 * {@code CacheManager}.
 */
final class WeblogFeedRenderCache implements RenderCache<WeblogFeedRequest> {

    private final WeblogFeedCache cache;

    WeblogFeedRenderCache(WeblogFeedCache cache) {
        this.cache = cache;
    }

    @Override
    public String generateKey(WeblogFeedRequest request) {
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
        return RenderCaches.lastModifiedOf(weblog);
    }

    @Override
    public void clear() {
        cache.clear();
    }
}
