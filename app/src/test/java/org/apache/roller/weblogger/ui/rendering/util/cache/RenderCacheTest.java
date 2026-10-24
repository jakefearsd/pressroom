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

package org.apache.roller.weblogger.ui.rendering.util.cache;

import java.util.Date;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.rendering.util.WeblogFeedRequest;
import org.apache.roller.weblogger.ui.rendering.util.WeblogPageRequest;
import org.apache.roller.weblogger.util.ServingSince;
import org.apache.roller.weblogger.util.cache.CachedContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@link RenderCache} adapters that the rendering servlets hold in
 * place of a repeated {@code if (isSiteWide)} branch.
 *
 * <p>The adapters add no behaviour of their own -- every assertion here should
 * agree with {@link RenderCacheSelectionCharacterisationTest}, which pinned the
 * same rules directly against the cache classes before the adapters existed.
 * What these tests protect is the <em>mapping</em>: that the site-wide side
 * really is wired to the cache with no per-weblog expiry, that the per-weblog
 * side really does expire against the weblog, and that neither one quietly
 * acquired the other's semantics on the way through the adapter.
 */
public class RenderCacheTest {

    private static final String HANDLE = "frontpage";

    @BeforeAll
    public static void requireCachingToBeOn() {
        assertTrue(WebloggerConfig.getBooleanProperty(SiteWideCache.CACHE_ID + ".enabled"),
                SiteWideCache.CACHE_ID + ".enabled must be true in the test configuration");
        assertTrue(WebloggerConfig.getBooleanProperty(WeblogPageCache.CACHE_ID + ".enabled"),
                WeblogPageCache.CACHE_ID + ".enabled must be true in the test configuration");
        assertTrue(WebloggerConfig.getBooleanProperty(WeblogFeedCache.CACHE_ID + ".enabled"),
                WeblogFeedCache.CACHE_ID + ".enabled must be true in the test configuration");
    }

    @BeforeEach
    public void startFromEmptyCaches() {
        SiteWideCache.getInstance().clear();
        WeblogPageCache.getInstance().clear();
        WeblogFeedCache.getInstance().clear();
    }

    // --- the site-wide side has no per-weblog expiry ----------------------

    @Test
    public void siteWidePagesSurviveAWeblogHavingChanged() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(true);
        String key = cache.generateKey(pageRequest());
        CachedContent content = content("front page");

        cache.put(key, content);

        assertNotNull(cache.get(key, System.currentTimeMillis() + 60_000L),
                "The site-wide cache has no notion of expiring against one weblog, so a "
                        + "lastModified far in the future must not evict anything");
    }

    @Test
    public void siteWideFeedsSurviveAWeblogHavingChanged() {
        RenderCache<WeblogFeedRequest> cache = RenderCaches.forFeed(true);
        String key = cache.generateKey(feedRequest());

        cache.put(key, content("front page feed"));

        assertNotNull(cache.get(key, System.currentTimeMillis() + 60_000L),
                "Same for the site-wide feed: dropped wholesale, never expired per weblog");
    }

    @Test
    public void siteWideLastModifiedIgnoresTheWeblogEntirely() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(true);

        Weblog ancient = new Weblog();
        ancient.setLastModified(new Date(0L));

        assertEquals(SiteWideCache.getInstance().getLastModified().getTime(),
                cache.lastModified(ancient),
                "The site-wide Last-Modified comes from the cache, not from whichever "
                        + "weblog happens to be serving as the front page");
    }

    // --- the per-weblog side expires against the weblog -------------------

    @Test
    public void weblogPagesAreWithheldOnceTheWeblogChanges() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);
        String key = cache.generateKey(pageRequest());

        cache.put(key, content("a page"));

        assertNotNull(cache.get(key, 0L),
                "served while the weblog is unchanged");
        assertNull(cache.get(key, System.currentTimeMillis() + 60_000L),
                "and withheld once the weblog has been modified more recently than the "
                        + "cached copy -- the only thing that evicts a weblog page");
    }

    @Test
    public void weblogFeedsAreWithheldOnceTheWeblogChanges() {
        RenderCache<WeblogFeedRequest> cache = RenderCaches.forFeed(false);
        String key = cache.generateKey(feedRequest());

        cache.put(key, content("a feed"));

        assertNotNull(cache.get(key, 0L),
                "served while the weblog is unchanged");
        assertNull(cache.get(key, System.currentTimeMillis() + 60_000L),
                "and withheld once the weblog has changed");
    }

    @Test
    public void weblogLastModifiedComesFromTheWeblog() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);

        long changed = ServingSince.started() + 1_000L;
        Weblog weblog = new Weblog();
        weblog.setLastModified(new Date(changed));

        assertEquals(changed, cache.lastModified(weblog),
                "A weblog's own lastModified is what dates its rendered pages");
    }

    @Test
    public void aWeblogUnchangedSinceThisServerStartedIsDatedFromTheStart() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);

        Weblog unchanged = new Weblog();
        unchanged.setLastModified(new Date(1_234_567_000L));

        assertEquals(ServingSince.started(), cache.lastModified(unchanged),
                "A deploy can change every page without touching the weblog; a reader's "
                        + "copy from before it must not be answered 304");
    }

    @Test
    public void weblogLastModifiedFallsBackToNowWhenNeverRecorded() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);

        // Weblog initialises the field (`private Date lastModified = new Date()`),
        // so it has to be nulled deliberately to reach the fallback at all. An
        // earlier version of this test used a bare `new Weblog()` and passed
        // through the *other* branch while claiming to cover this one -- the
        // assertion held either way, which is exactly why it went unnoticed
        // until JaCoCo showed the line uncovered.
        Weblog neverModified = new Weblog();
        neverModified.setLastModified(null);

        long before = System.currentTimeMillis();
        long answer = cache.lastModified(neverModified);
        long after = System.currentTimeMillis();

        assertTrue(answer >= before && answer <= after,
                "A weblog with no recorded modification is treated as current, which is "
                        + "what stops a null from being sent as Last-Modified: 1970");
    }

    @Test
    public void weblogLastModifiedSurvivesAMissingWeblog() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);

        long before = System.currentTimeMillis();
        long answer = cache.lastModified(null);
        long after = System.currentTimeMillis();

        assertTrue(answer >= before && answer <= after,
                "The servlets only reach this after a null-weblog check, but the guard is "
                        + "here so a future caller cannot turn a missing weblog into an NPE "
                        + "on the Last-Modified path");
    }

    // --- clear(), the theme-reload path ----------------------------------

    @Test
    public void clearEmptiesTheSiteWideCache() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(true);
        String key = cache.generateKey(pageRequest());
        cache.put(key, content("front page"));

        cache.clear();

        assertNull(cache.get(key, 0L),
                "Reloading a theme in development drops the rendered output, or the "
                        + "developer keeps being served the pre-edit page");
    }

    @Test
    public void clearEmptiesTheWeblogPageCache() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);
        String key = cache.generateKey(pageRequest());
        cache.put(key, content("a page"));

        cache.clear();

        assertNull(cache.get(key, 0L),
                "and the same on the per-weblog side, which is the branch SearchServlet "
                        + "takes when a theme reloads");
    }

    @Test
    public void clearEmptiesTheWeblogFeedCache() {
        RenderCache<WeblogFeedRequest> cache = RenderCaches.forFeed(false);
        String key = cache.generateKey(feedRequest());
        cache.put(key, content("a feed"));

        cache.clear();

        assertNull(cache.get(key, 0L),
                "and on the feed cache");
    }

    // --- the adapter delegates keys rather than inventing them ------------

    @Test
    public void keysAreTheUnderlyingCachesOwnKeys() {
        WeblogPageRequest page = pageRequest();
        WeblogFeedRequest feed = feedRequest();

        assertEquals(SiteWideCache.getInstance().generateKey(page),
                RenderCaches.forPage(true).generateKey(page),
                "The site-wide page adapter must produce exactly the site-wide cache's key");
        assertEquals(SiteWideCache.getInstance().generateKey(feed),
                RenderCaches.forFeed(true).generateKey(feed),
                "and likewise for feeds");
        assertEquals(WeblogPageCache.getInstance().generateKey(page),
                RenderCaches.forPage(false).generateKey(page),
                "The per-weblog page adapter must produce exactly the page cache's key");
        assertEquals(WeblogFeedCache.getInstance().generateKey(feed),
                RenderCaches.forFeed(false).generateKey(feed),
                "and the feed adapter the feed cache's key");
    }

    @Test
    public void contentGoesInAndComesBackUnchanged() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);
        String key = cache.generateKey(pageRequest());
        CachedContent content = content("a page");

        cache.put(key, content);

        assertSame(content, cache.get(key, 0L),
                "The adapter stores and returns the rendering itself, not a copy");
    }

    // --- helpers ----------------------------------------------------------

    private static CachedContent content(String body) {
        return new CachedContent(body.length() + 8, "text/html");
    }

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
