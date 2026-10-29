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
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.ui.rendering.util.WeblogFeedRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHARACTERISATION TEST -- written before the RenderCache adapters existed and
 * expected to pass immediately against the code as it stands. It describes what
 * the rendering servlets' {@code if (isSiteWide) ... else ...} cache branches
 * already do; it is not a specification of new behaviour, and a reader should
 * not mistake it for one.
 *
 * <p>It exists because those branches are about to be replaced by a single
 * {@code RenderCache} chosen once per request. The two sides of every branch
 * are <em>not</em> interchangeable, and the ways they differ are subtle enough
 * that an adapter could paper over one without any existing test noticing:
 *
 * <ul>
 *   <li>{@link SiteWideCache#put} stores the value raw and
 *       {@link SiteWideCache#get(String)} takes no timestamp -- it cannot
 *       expire an entry against a weblog at all.</li>
 *   <li>{@link WeblogPageCache#put} and {@link WeblogFeedCache#put} wrap the
 *       value in a {@code LazyExpiringCacheEntry}, and their two-argument
 *       {@code get} withholds it once the weblog has changed.</li>
 *   <li>Each cache stamps its own {@code CACHE_ID} onto every key, which is
 *       what stops the site-wide copy of a page and the per-weblog copy of the
 *       same page from being served for one another.</li>
 * </ul>
 *
 * <p>Note that {@code WeblogFeedCache} sits on the lazy-expiry side alongside
 * {@code WeblogPageCache}: it registers no CacheHandler
 * ({@code constructCache(null, ...)}) and expires against the weblog exactly
 * the way the page cache does. Only {@code SiteWideCache} is the odd one out.
 */
public class RenderCacheSelectionCharacterisationTest {

    private static final String HANDLE = "frontpage";

    private SiteWideCache siteWide;
    private WeblogPageCache pageCache;
    private WeblogFeedCache feedCache;

    @BeforeAll
    public static void requireCachingToBeOn() {
        assertTrue(WebloggerConfig.getBooleanProperty(SiteWideCache.CACHE_ID + ".enabled"),
                "These tests exercise the caching path, so " + SiteWideCache.CACHE_ID
                        + ".enabled must be true in the test configuration");
        assertTrue(WebloggerConfig.getBooleanProperty(WeblogPageCache.CACHE_ID + ".enabled"),
                WeblogPageCache.CACHE_ID + ".enabled must be true in the test configuration");
        assertTrue(WebloggerConfig.getBooleanProperty(WeblogFeedCache.CACHE_ID + ".enabled"),
                WeblogFeedCache.CACHE_ID + ".enabled must be true in the test configuration");
    }

    @BeforeEach
    public void startFromEmptyCaches() {
        siteWide = SiteWideCache.getInstance();
        pageCache = WeblogPageCache.getInstance();
        feedCache = WeblogFeedCache.getInstance();
        siteWide.clear();
        pageCache.clear();
        feedCache.clear();
    }

    // --- what the site-wide side of the branch does -----------------------

    @Test
    public void siteWideContentIsNotExpiredByAWeblogHavingChanged() {
        String key = siteWide.generateKey(pageRequest());
        siteWide.put(key, "rendered front page");

        // The servlet passes a lastModified to the per-weblog cache and cannot
        // pass one here -- get(String) has no such parameter. An adapter that
        // unified the two must therefore *ignore* the timestamp on this side.
        assertEquals("rendered front page", siteWide.get(key),
                "Site-wide content is dropped wholesale through CacheManager, never "
                        + "expired against a single weblog's lastModified");
    }

    @Test
    public void siteWideLastModifiedComesFromTheCacheItselfNotFromAWeblog() {
        Date lastModified = siteWide.getLastModified();

        assertNotNull(lastModified,
                "The servlet reads siteWideCache.getLastModified() for the 304 check on the "
                        + "site-wide weblog; it must always have an answer");
        assertEquals(lastModified, siteWide.getLastModified(),
                "and that answer is held for fifteen minutes, so two reads in one request "
                        + "agree -- a fresh Date each call would defeat conditional GET");
    }

    // --- what the per-weblog side of the branch does ----------------------

    @Test
    public void weblogPageContentIsServedUntilTheWeblogChanges() {
        String key = pageCache.generateKey(pageRequest());
        pageCache.put(key, "rendered page");

        assertEquals("rendered page", pageCache.get(key, 0L),
                "A cached page is served while the weblog's last change predates it");
    }

    @Test
    public void weblogPageContentIsWithheldOnceTheWeblogChanges() {
        String key = pageCache.generateKey(pageRequest());
        pageCache.put(key, "rendered page");

        long afterCaching = System.currentTimeMillis() + 60_000L;

        assertNull(pageCache.get(key, afterCaching),
                "Once the weblog has been modified more recently than the cached copy, the "
                        + "copy must be withheld -- this is the only thing that evicts it");
    }

    @Test
    public void weblogFeedContentExpiresTheSameLazyWayThePageCacheDoes() {
        String key = feedCache.generateKey(feedRequest());
        feedCache.put(key, "rendered feed");

        assertEquals("rendered feed", feedCache.get(key, 0L),
                "The feed cache serves a cached feed while the weblog is unchanged");

        long afterCaching = System.currentTimeMillis() + 60_000L;
        assertNull(feedCache.get(key, afterCaching),
                "and withholds it once the weblog has changed. WeblogFeedCache is on the "
                        + "lazy-expiry side of the branch, exactly like WeblogPageCache");
    }

    // --- what keeps the two sides' entries apart --------------------------

    @Test
    public void theTwoSidesOfTheBranchNeverShareAKeyForTheSamePageRequest() {
        WeblogPageRequest request = pageRequest();

        String siteWideKey = siteWide.generateKey(request);
        String perWeblogKey = pageCache.generateKey(request);

        assertNotEquals(siteWideKey, perWeblogKey,
                "The same page request must produce different keys on the two sides, or a "
                        + "weblog served as the front page and the same weblog served "
                        + "directly would be served each other's rendered output");
        assertTrue(siteWideKey.startsWith(SiteWideCache.CACHE_ID + ':'),
                "Site-wide keys carry the site-wide cache id");
        assertTrue(perWeblogKey.startsWith(WeblogPageCache.CACHE_ID + ':'),
                "Per-weblog page keys carry the page cache id");
    }

    @Test
    public void theTwoSidesOfTheBranchNeverShareAKeyForTheSameFeedRequest() {
        WeblogFeedRequest request = feedRequest();

        String siteWideKey = siteWide.generateKey(request);
        String perWeblogKey = feedCache.generateKey(request);

        assertNotEquals(siteWideKey, perWeblogKey,
                "The same feed request must produce different keys on the two sides");
        assertTrue(siteWideKey.startsWith(SiteWideCache.CACHE_ID + ':'),
                "Site-wide keys carry the site-wide cache id");
        assertTrue(perWeblogKey.startsWith(WeblogFeedCache.CACHE_ID + ':'),
                "Per-weblog feed keys carry the feed cache id");
    }

    // --- helpers ----------------------------------------------------------

    private static WeblogPageRequest pageRequest() {
        WeblogPageRequest request = new WeblogPageRequest();
        request.setWeblogHandle(HANDLE);
        return request;
    }

    private static WeblogFeedRequest feedRequest() {
        WeblogFeedRequest request = new WeblogFeedRequest();
        request.setWeblogHandle(HANDLE);
        request.setType("entries");
        request.setFormat("atom");
        return request;
    }
}
