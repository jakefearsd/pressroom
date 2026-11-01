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
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.util.DiscoveryGeneration;
import com.wikantik.pressroom.util.ServingSince;

/**
 * Chooses the {@link RenderCache} for a request.
 *
 * <p>This is the one place that turns "is this the site-wide weblog?" into a
 * cache. A rendering servlet asks once, at the top of the request, and then
 * has no further reason to care.
 */
public final class RenderCaches {

    private RenderCaches() {}

    /** The cache holding rendered pages for this request. */
    public static RenderCache<WeblogPageRequest> forPage(boolean siteWide) {
        return siteWide
                ? new SiteWideRenderCache<>(SiteWideCache.getInstance(),
                        SiteWideCache.getInstance()::generateKey)
                : new WeblogPageRenderCache(WeblogPageCache.getInstance());
    }

    /** The cache holding rendered feeds for this request. */
    public static RenderCache<WeblogFeedRequest> forFeed(boolean siteWide) {
        return siteWide
                ? new SiteWideRenderCache<>(SiteWideCache.getInstance(),
                        SiteWideCache.getInstance()::generateKey)
                : new WeblogFeedRenderCache(WeblogFeedCache.getInstance());
    }

    /**
     * When a weblog itself last changed, or this server started if that is
     * later ({@link ServingSince}: a deploy changes what every page renders
     * as), falling back to now when it has never been recorded -- the
     * per-weblog caches' notion of currency, and the value the servlets put
     * in {@code Last-Modified}. A page carrying discovery picks is also only
     * as new as the {@link DiscoveryGeneration} it was built from, so the
     * result is never earlier than that: each reshuffle expires every cached
     * page and 304s nothing older.
     */
    static long lastModifiedOf(Weblog weblog) {
        if (weblog != null && weblog.getLastModified() != null) {
            return Math.max(ServingSince.atLeast(weblog.getLastModified().getTime()),
                    DiscoveryGeneration.current());
        }
        return System.currentTimeMillis();
    }
}
