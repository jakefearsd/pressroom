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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import com.wikantik.pressroom.config.WebloggerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHARACTERISATION TEST -- written before {@code WeblogPageCache} and
 * {@code WeblogFeedCache} were given a shared base, and expected to pass
 * immediately against the two independent classes as they stand. It is not a
 * specification of new behaviour.
 *
 * <p>It exists to state, as an executable claim, the thing that makes a shared
 * base legitimate at all: <em>these two caches behave identically</em>. Both
 * register no CacheHandler, both expire lazily against {@code lastModified},
 * both no-op entirely when disabled, and both size themselves from their own
 * {@code CACHE_ID}-prefixed configuration. Every assertion below runs the same
 * scenario against both caches and requires the same answer.
 *
 * <p>The two classes are unrelated types today, so the scenarios reach them
 * through lambdas rather than a common interface -- which is the point. If
 * extracting a base ever changes one cache and not the other, or quietly makes
 * one of them read the other's configuration, this fails.
 *
 * <p>Written as part of collapsing the duplication that the {@code CPD-OFF}
 * note on their accessor block describes.
 */
public class LazyRenderCacheEquivalenceCharacterisationTest {

    /** One cache, reached generically: the four operations plus its id. */
    private record Probe(String cacheId,
                         Consumer<String> put,
                         PutGet get,
                         Consumer<String> remove,
                         Runnable clear) {
    }

    @FunctionalInterface
    private interface PutGet {
        Object get(String key, long lastModified);
    }

    private static Probe page(WeblogPageCache c) {
        return new Probe(WeblogPageCache.CACHE_ID,
                k -> c.put(k, "content"), c::get, c::remove, c::clear);
    }

    private static Probe feed(WeblogFeedCache c) {
        return new Probe(WeblogFeedCache.CACHE_ID,
                k -> c.put(k, "content"), c::get, c::remove, c::clear);
    }

    private static List<Probe> enabledCaches() {
        return List.of(page(WeblogPageCache.newForTest(true)),
                       feed(WeblogFeedCache.newForTest(true)));
    }

    private static List<Probe> disabledCaches() {
        return List.of(page(WeblogPageCache.newForTest(false)),
                       feed(WeblogFeedCache.newForTest(false)));
    }

    @Test
    public void bothCachesServeWhatTheyWereGiven() {
        for (Probe cache : enabledCaches()) {
            cache.put().accept("k");
            assertNotNull(cache.get().get("k", 0L),
                    cache.cacheId() + " must serve content back while unexpired");
        }
    }

    @Test
    public void bothCachesExpireOnTheSameRule() {
        long future = System.currentTimeMillis() + 60_000L;

        // the answers are collected and compared rather than asserted
        // separately, so a divergence reads as "these two disagreed"
        List<Boolean> served = new ArrayList<>();
        for (Probe cache : enabledCaches()) {
            cache.put().accept("k");
            served.add(cache.get().get("k", future) != null);
        }

        assertEquals(List.of(false, false), served,
                "Both caches withhold content once lastModified moves past the cached "
                        + "copy. They share one expiry contract -- which is the premise a "
                        + "shared base rests on");
    }

    @Test
    public void bothCachesForgetWhatWasRemoved() {
        for (Probe cache : enabledCaches()) {
            cache.put().accept("k");
            cache.remove().accept("k");
            assertNull(cache.get().get("k", 0L),
                    cache.cacheId() + " must not serve a removed entry");
        }
    }

    @Test
    public void bothCachesDropEverythingOnClear() {
        for (Probe cache : enabledCaches()) {
            cache.put().accept("a");
            cache.put().accept("b");
            cache.clear().run();
            assertNull(cache.get().get("a", 0L), cache.cacheId() + " must drop everything");
            assertNull(cache.get().get("b", 0L), cache.cacheId() + " must drop everything");
        }
    }

    @Test
    public void bothCachesReadAsMissForAKeyNeverStored() {
        for (Probe cache : enabledCaches()) {
            assertNull(cache.get().get("never-stored", 0L),
                    cache.cacheId() + " must read an unknown key as a miss, not an error");
        }
    }

    @Test
    public void neitherCacheDoesAnythingWhenDisabled() {
        for (Probe cache : disabledCaches()) {
            cache.put().accept("k");
            assertNull(cache.get().get("k", 0L),
                    cache.cacheId() + " is disabled and must never serve content");

            // the other two operations must be no-ops rather than throwing --
            // with caching off there is no backing Cache object for them to
            // touch, so this is exactly where a careless base class would NPE
            cache.remove().accept("k");
            cache.clear().run();
        }
    }

    @Test
    public void eachCacheSizesItselfFromItsOwnConfiguration() {
        // the constructor's per-cache property scan is the thing under test; if
        // a shared base ever read one prefix for both, one cache would silently
        // take the other's size
        for (String cacheId : List.of(WeblogPageCache.CACHE_ID, WeblogFeedCache.CACHE_ID)) {
            assertTrue(WebloggerConfig.getIntProperty(cacheId + ".size") > 1,
                    cacheId + ".size must be configured for this test to mean anything");
        }

        for (Probe cache : enabledCaches()) {
            int size = WebloggerConfig.getIntProperty(cache.cacheId() + ".size");
            for (int i = 0; i < size; i++) {
                cache.put().accept("key" + i);
            }
            assertNotNull(cache.get().get("key0", 0L),
                    cache.cacheId() + " must hold as many entries as its own .size property "
                            + "allows; reading the wrong prefix would resize it silently");

            // The boundary is the half that actually detects a wrong prefix: a
            // cache handed the *larger* sibling's size still holds key0, so
            // asserting only that proves nothing. It has to evict at its own
            // limit too. (An earlier version of this test stopped one line
            // above and survived exactly that mutation.)
            cache.put().accept("one-too-many");
            assertNull(cache.get().get("key1", 0L),
                    cache.cacheId() + " must also evict at its own configured limit -- "
                            + "key0 was just read, so key1 is least recently used");
        }
    }
}
