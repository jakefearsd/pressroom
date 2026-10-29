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

package com.wikantik.pressroom.util.cache;

import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.cache.SiteWideCache;
import com.wikantik.pressroom.ui.rendering.util.cache.WeblogFeedCache;
import com.wikantik.pressroom.ui.rendering.util.cache.WeblogPageCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins which of the three render caches is registered as a {@link CacheHandler},
 * because prose got this wrong and stayed wrong.
 *
 * <p>A comment repeated in all three cache files said that
 * {@code WeblogPageCache} alone expired lazily "while its siblings are
 * invalidated through CacheManager". {@code WeblogFeedCache} is not such a
 * sibling: it passes {@code constructCache(null, ...)} exactly as the page
 * cache does, registers nothing, and expires lazily against
 * {@code weblog.lastModified}. {@code SiteWideCache} is the only render cache
 * on the eager side. The claim went unchallenged for as long as it did because
 * nothing executed it -- so this test does.
 *
 * <p>It lives in {@code com.wikantik.pressroom.util.cache} rather than
 * beside the render caches because {@link CacheManager#handlers()} is
 * package-private to this package.
 *
 * <p>If you are here because this test failed, one of two things happened: a
 * render cache gained or lost a CacheHandler, or the caches were collapsed
 * into a shared base. Either is a real change to how rendered content expires
 * -- update {@code CLAUDE.md}'s Templates section and the {@code CPD-OFF}
 * notes with it, rather than relaxing the assertion.
 */
public class RenderCacheHandlerRegistrationTest {

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
    public void constructEveryRenderCacheAndEmptyIt() {
        // registration happens in the constructor, so a cache that no test has
        // touched yet would be absent from the registry for reasons that have
        // nothing to do with what is being asserted here
        SiteWideCache.getInstance().clear();
        WeblogPageCache.getInstance().clear();
        WeblogFeedCache.getInstance().clear();
    }

    @Test
    public void siteWideCacheIsTheOnlyRenderCacheRegisteredAsAHandler() {
        assertTrue(CacheManager.handlers().contains(SiteWideCache.getInstance()),
                "SiteWideCache registers itself (constructCache(this, ...)) and is the one "
                        + "render cache CacheManager can invalidate");

        assertFalse(CacheManager.handlers().stream().anyMatch(WeblogPageCache.class::isInstance),
                "WeblogPageCache passes constructCache(null, ...) and must never appear in "
                        + "the handler registry -- it expires lazily instead");

        assertFalse(CacheManager.handlers().stream().anyMatch(WeblogFeedCache.class::isInstance),
                "WeblogFeedCache does the same. This is the assertion the retired comment "
                        + "would have failed: it called the feed cache a CacheManager-"
                        + "invalidated sibling of the site-wide cache, and it is not one");
    }

    @Test
    public void invalidatingAWeblogDropsSiteWideContentAndLeavesTheLazyCachesAlone() {
        SiteWideCache siteWide = SiteWideCache.getInstance();
        WeblogPageCache pageCache = WeblogPageCache.getInstance();
        WeblogFeedCache feedCache = WeblogFeedCache.getInstance();

        siteWide.put("cache.sitewide:probe", "site-wide content");
        pageCache.put("cache.weblogpage:probe", "page content");
        feedCache.put("cache.weblogfeed:probe", "feed content");

        CacheManager.invalidate(weblog());

        assertNull(siteWide.get("cache.sitewide:probe"),
                "CacheManager reaches the site-wide cache, which drops everything it holds");

        assertNotNull(pageCache.get("cache.weblogpage:probe", 0L),
                "but it never reaches the page cache, so a rendered page survives an "
                        + "invalidation and waits for weblog.lastModified to move past it");
        assertNotNull(feedCache.get("cache.weblogfeed:probe", 0L),
                "and it never reaches the feed cache either -- the fact the retired comment "
                        + "denied");
    }

    @Test
    public void theLazyCachesStillExpireTheirOwnContentAgainstTheWeblog() {
        WeblogPageCache pageCache = WeblogPageCache.getInstance();
        WeblogFeedCache feedCache = WeblogFeedCache.getInstance();

        pageCache.put("cache.weblogpage:probe", "page content");
        feedCache.put("cache.weblogfeed:probe", "feed content");

        long afterCaching = System.currentTimeMillis() + 60_000L;

        assertNull(pageCache.get("cache.weblogpage:probe", afterCaching),
                "Not being reachable by CacheManager does not mean never expiring: the page "
                        + "cache withholds content once the weblog has changed");
        assertNull(feedCache.get("cache.weblogfeed:probe", afterCaching),
                "and the feed cache expires by exactly the same rule, which is the whole "
                        + "point -- these two caches are identical in contract");
    }

    @Test
    public void theTwoLazyCachesAgreeOnEveryExpiryDecision() {
        WeblogPageCache pageCache = WeblogPageCache.getInstance();
        WeblogFeedCache feedCache = WeblogFeedCache.getInstance();

        // the claim under test is "identical contract", so assert it as one:
        // for the same lastModified, both caches make the same call
        for (long lastModified : new long[]{0L, 1L, System.currentTimeMillis() + 60_000L}) {
            pageCache.put("cache.weblogpage:probe", "content");
            feedCache.put("cache.weblogfeed:probe", "content");

            boolean pageServed = pageCache.get("cache.weblogpage:probe", lastModified) != null;
            boolean feedServed = feedCache.get("cache.weblogfeed:probe", lastModified) != null;

            assertEquals(pageServed, feedServed,
                    "The page and feed caches must agree for lastModified=" + lastModified
                            + "; they share an expiry contract, and the CPD-OFF note on their "
                            + "duplicated accessor block now says so");
        }
    }

    private static Weblog weblog() {
        Weblog weblog = new Weblog();
        weblog.setHandle("myblog");
        return weblog;
    }
}
