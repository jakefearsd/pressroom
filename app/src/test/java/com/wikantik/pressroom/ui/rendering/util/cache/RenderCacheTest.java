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
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.WeblogFeedRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.util.DiscoveryGeneration;
import com.wikantik.pressroom.util.ServingSince;
import com.wikantik.pressroom.util.cache.CachedContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    /** How long setUp waits for the clock to pass the discovery generation. */
    private static final long GENERATION_WAIT_MILLIS = 5_000L;

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
        // The discovery generation is process-wide: another test in this JVM may
        // have parked it at about now, and an entry cached before it is a miss.
        // A generation more than a few seconds ahead was advanced into the
        // future, which the testing rule forbids (docs/dev/discovery.md): fail
        // and say so rather than spin until the clock catches up.
        long deadline = System.currentTimeMillis() + GENERATION_WAIT_MILLIS;
        while (System.currentTimeMillis() <= DiscoveryGeneration.current()) {
            if (System.currentTimeMillis() > deadline) {
                fail("the discovery generation " + DiscoveryGeneration.current() + " is still ahead of the clock after "
                        + GENERATION_WAIT_MILLIS + " ms: some test advanced it into the future");
            }
            Thread.onSpinWait();
        }
        SiteWideCache.getInstance().clear();
        WeblogPageCache.getInstance().clear();
        WeblogFeedCache.getInstance().clear();
    }

    // --- discovery generation expires cached pages -------------------------

    @Test
    public void lastModifiedIncludesGeneration() {
        // Dated just after server start, so the weblog's own change (older) and
        // the start (older still than the generation) both lose to it.
        long generation = ServingSince.started() + 1;
        Weblog weblog = new Weblog();
        weblog.setLastModified(new Date(ServingSince.started() - 5000));
        DiscoveryGeneration.advance(generation);

        assertTrue(RenderCaches.lastModifiedOfPage(weblog) >= generation,
                "an entry cached before the generation must look stale once it moves");
    }

    @Test
    public void siteWideLastModifiedIncludesGeneration() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(true);
        // The cache's own date is a 15 minute "now"; a generation just ahead of
        // it must win, or a browser is answered 304 for a reshuffled page.
        long generation = SiteWideCache.getInstance().getLastModified().getTime() + 1;
        DiscoveryGeneration.advance(generation);

        assertTrue(cache.lastModified(null) >= generation);
    }

    @Test
    public void siteWideEntryCachedBeforeTheGenerationMisses() throws InterruptedException {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(true);
        String key = cache.generateKey(pageRequest());
        cache.put(key, content("front page"), cache.basis(null));
        assertNotNull(cache.get(key, at(0L)), "a fresh entry hits");

        DiscoveryGeneration.advance(System.currentTimeMillis() + 1);
        Thread.sleep(5);

        assertNull(cache.get(key, at(0L)), "an entry cached before the generation is a miss");
        cache.put(key, content("rebuilt"), cache.basis(null));
        assertNotNull(cache.get(key, at(0L)), "an entry cached after the generation hits");
    }

    /**
     * A feed carries no discovery picks, so a reshuffle must not expire it:
     * feed readers would refetch every feed on every rebuild.
     */
    @Test
    public void weblogFeedLastModifiedIgnoresTheGeneration() {
        Weblog weblog = new Weblog();
        weblog.setLastModified(new Date(ServingSince.started() - 5000));
        DiscoveryGeneration.advance(ServingSince.started() + 1);

        assertEquals(ServingSince.started(), RenderCaches.forFeed(false).lastModified(weblog),
                "a feed is dated by its weblog (and the server start), never by the generation");
        assertTrue(RenderCaches.forPage(false).lastModified(weblog) >= DiscoveryGeneration.current(),
                "a page still is");
    }

    @Test
    public void siteWideFeedLastModifiedIgnoresTheGeneration() {
        long cacheDate = SiteWideCache.getInstance().getLastModified().getTime();
        DiscoveryGeneration.advance(cacheDate + 1);

        assertEquals(SiteWideCache.getInstance().getLastModified().getTime(),
                RenderCaches.forFeed(true).lastModified(null));
        assertTrue(RenderCaches.forPage(true).lastModified(null) >= DiscoveryGeneration.current(),
                "the site-wide page still is");
    }

    @Test
    public void siteWideFeedCachedBeforeTheGenerationStillHits() throws InterruptedException {
        RenderCache<WeblogFeedRequest> cache = RenderCaches.forFeed(true);
        String key = cache.generateKey(feedRequest());
        cache.put(key, content("site feed"), cache.basis(null));

        DiscoveryGeneration.advance(System.currentTimeMillis() + 1);
        Thread.sleep(5);

        assertNotNull(cache.get(key, at(0L)), "a reshuffle does not expire a feed");
    }

    // --- the site-wide side has no per-weblog expiry ----------------------

    @Test
    public void siteWidePagesSurviveAWeblogHavingChanged() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(true);
        String key = cache.generateKey(pageRequest());
        CachedContent content = content("front page");

        cache.put(key, content, cache.basis(null));

        assertNotNull(cache.get(key, at(System.currentTimeMillis() + 60_000L)),
                "The site-wide cache has no notion of expiring against one weblog, so a "
                        + "lastModified far in the future must not evict anything");
    }

    @Test
    public void siteWideFeedsSurviveAWeblogHavingChanged() {
        RenderCache<WeblogFeedRequest> cache = RenderCaches.forFeed(true);
        String key = cache.generateKey(feedRequest());

        cache.put(key, content("front page feed"), cache.basis(null));

        assertNotNull(cache.get(key, at(System.currentTimeMillis() + 60_000L)),
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

        cache.put(key, content("a page"), cache.basis(null));

        assertNotNull(cache.get(key, at(0L)),
                "served while the weblog is unchanged");
        assertNull(cache.get(key, at(System.currentTimeMillis() + 60_000L)),
                "and withheld once the weblog has been modified more recently than the "
                        + "cached copy -- the only thing that evicts a weblog page");
    }

    @Test
    public void weblogFeedsAreWithheldOnceTheWeblogChanges() {
        RenderCache<WeblogFeedRequest> cache = RenderCaches.forFeed(false);
        String key = cache.generateKey(feedRequest());

        cache.put(key, content("a feed"), cache.basis(null));

        assertNotNull(cache.get(key, at(0L)),
                "served while the weblog is unchanged");
        assertNull(cache.get(key, at(System.currentTimeMillis() + 60_000L)),
                "and withheld once the weblog has changed");
    }

    @Test
    public void weblogLastModifiedComesFromTheWeblog() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);

        // Dated past the process-wide discovery generation too: another test in
        // this JVM may already have advanced it to about now.
        long changed = Math.max(Math.max(ServingSince.started(), DiscoveryGeneration.current()),
                System.currentTimeMillis()) + 1_000L;
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

        // The discovery generation is process-wide and only moves forward, so
        // another test in this JVM may have advanced it past the start.
        assertEquals(Math.max(ServingSince.started(),
                        DiscoveryGeneration.current()),
                cache.lastModified(unchanged),
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

    // --- a rendering is cached against what it read -----------------------
    //
    // Code review, 2026-11-03: entries were stamped with the time they were
    // stored. A render that missed at T0 read the old data, an author's save
    // stamped lastModified at T1, the render was stored at T2 > T1, and the
    // stale page passed as fresh until the next change or the timeout. These
    // drive that interleaving with controlled stamps: the weblog's change is
    // dated in the past, before the put.

    /** Waits until the clock is past {@code millis}. */
    private static long clockPast(long millis) {
        while (System.currentTimeMillis() <= millis) {
            Thread.onSpinWait();
        }
        return System.currentTimeMillis();
    }

    @Test
    public void aWeblogPageRenderedBeforeAChangeAndStoredAfterItIsNotServed() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);
        String key = cache.generateKey(pageRequest());
        long t0 = clockPast(ServingSince.started() + 2);
        Weblog weblog = new Weblog();
        weblog.setLastModified(new Date(t0 - 1));

        RenderCache.Basis renderStarted = cache.basis(weblog); // the miss reads the old weblog
        weblog.setLastModified(new Date(t0));                    // the author's save, T1
        clockPast(t0);
        cache.put(key, content("old page"), renderStarted);      // stored at T2 > T1

        assertNull(cache.get(key, cache.basis(weblog)),
                "a page rendered from the weblog as it was before the save is stale, "
                        + "however late it was stored");
    }

    @Test
    public void aWeblogFeedRenderedBeforeAChangeAndStoredAfterItIsNotServed() {
        RenderCache<WeblogFeedRequest> cache = RenderCaches.forFeed(false);
        String key = cache.generateKey(feedRequest());
        long t0 = clockPast(ServingSince.started() + 2);
        Weblog weblog = new Weblog();
        weblog.setLastModified(new Date(t0 - 1));

        RenderCache.Basis renderStarted = cache.basis(weblog);
        weblog.setLastModified(new Date(t0));
        clockPast(t0);
        cache.put(key, content("old feed"), renderStarted);

        assertNull(cache.get(key, cache.basis(weblog)), "the same for a feed");
    }

    /**
     * Stamps, not clocks: a render that read the weblog as it is now is
     * current, even when the change it read happened in the same millisecond.
     */
    @Test
    public void aRenderOfTheCurrentWeblogIsServed() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);
        String key = cache.generateKey(pageRequest());
        Weblog weblog = new Weblog();
        weblog.setLastModified(new Date(clockPast(ServingSince.started())));

        cache.put(key, content("page"), cache.basis(weblog));

        assertNotNull(cache.get(key, cache.basis(weblog)));
    }

    /**
     * The generation is compared on its own. Folded into the weblog's stamp
     * (the larger of the two), a reshuffle dated before the weblog's last
     * change was invisible to a page rendered from the old snapshot.
     */
    @Test
    public void aWeblogPageRenderedFromTheSnapshotBeforeAReshuffleIsNotServed() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);
        String key = cache.generateKey(pageRequest());
        long generation = DiscoveryGeneration.current();
        long t0 = clockPast(Math.max(ServingSince.started(), generation) + 2);
        Weblog weblog = new Weblog();
        weblog.setLastModified(new Date(t0));

        RenderCache.Basis renderStarted = cache.basis(weblog);
        DiscoveryGeneration.advance(generation + 1); // a rebuild, stamped before the weblog's change
        clockPast(t0);
        cache.put(key, content("old picks"), renderStarted);

        assertNull(cache.get(key, cache.basis(weblog)),
                "a page built from the previous snapshot is stale once the generation moves");
    }

    /**
     * The site-wide cache is cleared eagerly by an author's save; a render
     * that started before the clear and is stored after it must not survive
     * it.
     */
    @Test
    public void aSiteWidePageRenderedBeforeAnInvalidationAndStoredAfterItIsNotServed() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(true);
        String key = cache.generateKey(pageRequest());

        RenderCache.Basis renderStarted = cache.basis(null);
        SiteWideCache.getInstance().invalidate(new Weblog()); // CacheManager, after the save commits
        cache.put(key, content("old front page"), renderStarted);

        assertNull(cache.get(key, cache.basis(null)),
                "the eager clear ran before the stale render was stored");

        cache.put(key, content("new front page"), cache.basis(null));
        assertNotNull(cache.get(key, cache.basis(null)), "a render started after the clear is kept");
    }

    // --- clear(), the theme-reload path ----------------------------------

    @Test
    public void clearEmptiesTheSiteWideCache() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(true);
        String key = cache.generateKey(pageRequest());
        cache.put(key, content("front page"), cache.basis(null));

        cache.clear();

        assertNull(cache.get(key, at(0L)),
                "Reloading a theme in development drops the rendered output, or the "
                        + "developer keeps being served the pre-edit page");
    }

    @Test
    public void clearEmptiesTheWeblogPageCache() {
        RenderCache<WeblogPageRequest> cache = RenderCaches.forPage(false);
        String key = cache.generateKey(pageRequest());
        cache.put(key, content("a page"), cache.basis(null));

        cache.clear();

        assertNull(cache.get(key, at(0L)),
                "and the same on the per-weblog side, which is the branch SearchServlet "
                        + "takes when a theme reloads");
    }

    @Test
    public void clearEmptiesTheWeblogFeedCache() {
        RenderCache<WeblogFeedRequest> cache = RenderCaches.forFeed(false);
        String key = cache.generateKey(feedRequest());
        cache.put(key, content("a feed"), cache.basis(null));

        cache.clear();

        assertNull(cache.get(key, at(0L)),
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

        cache.put(key, content, cache.basis(null));

        assertSame(content, cache.get(key, at(0L)),
                "The adapter stores and returns the rendering itself, not a copy");
    }

    // --- helpers ----------------------------------------------------------

    /** A request's basis with this weblog stamp and the current generation. */
    private static RenderCache.Basis at(long modified) {
        return new RenderCache.Basis(modified, DiscoveryGeneration.current());
    }

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
