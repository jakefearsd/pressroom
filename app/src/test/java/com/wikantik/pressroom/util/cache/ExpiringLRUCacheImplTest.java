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

import java.util.Map;
import com.wikantik.pressroom.util.PressroomConstants;
import com.wikantik.pressroom.util.cache.LRUCacheImplTest.MutableClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests the expiring variant of the LRU cache, which is the default for every
 * cache in Roller (see cache.defaultFactory in pressroom.properties).
 *
 * The timeout is what bounds how stale a rendered page can get when nothing
 * invalidates it explicitly, so "still served at the timeout, gone just after"
 * is the behaviour that matters. The cache reads the time through an injected
 * clock precisely so that this can be checked at the boundary instead of by
 * sleeping.
 */
public class ExpiringLRUCacheImplTest {

    private static final int MAX_SIZE = 10;

    /** 15 seconds, expressed the way the constructor wants it. */
    private static final long TIMEOUT_SECONDS = 15;

    @Test
    public void entryIsServedUntilItsTimeoutHasPassed() {
        MutableClock clock = new MutableClock(1_000L);
        ExpiringLRUCacheImpl cache =
                new ExpiringLRUCacheImpl("test", MAX_SIZE, TIMEOUT_SECONDS, clock);

        cache.put("key", "value");

        assertEquals("value", cache.get("key"), "A just-cached entry must be served");

        clock.now = 1_000L + 15_000L;
        assertEquals("value", cache.get("key"),
                "An entry is still fresh at exactly its timeout; expiry starts after it. "
                        + "If this fails the timeout is off by one and every cache in "
                        + "Roller expires a fraction early.");

        clock.now = 1_000L + 15_001L;
        assertNull(cache.get("key"),
                "Once the timeout has passed the entry must not be served, however "
                        + "recently it was read");
    }

    @Test
    public void timeoutIsGivenInSecondsAndAppliedInMilliseconds() {
        ExpiringLRUCacheImpl cache = new ExpiringLRUCacheImpl("test", MAX_SIZE, 1800);

        assertEquals(1800L * PressroomConstants.SEC_IN_MS, cache.getTimeoutMillis(),
                "Cache timeouts are configured in seconds (cache.weblogpage.timeout=3600 "
                        + "means an hour); mixing up the unit would expire entries 1000x "
                        + "too early or hold them 1000x too long");
    }

    @Test
    public void expiredEntryIsDroppedRatherThanLeftToRot() {
        MutableClock clock = new MutableClock(0L);
        ExpiringLRUCacheImpl cache =
                new ExpiringLRUCacheImpl("test", MAX_SIZE, TIMEOUT_SECONDS, clock);
        cache.put("key", "value");

        clock.now = 100_000L;
        assertNull(cache.get("key"), "precondition: the entry has expired");

        Map<String, Object> stats = cache.getStats();
        assertEquals(1.0, stats.get("removes"),
                "Reading an expired entry must evict it, otherwise dead entries occupy "
                        + "the cache until they are pushed out by the size limit");
        assertEquals(0.0, stats.get("hits"),
                "An expired read is not a hit: the caller got nothing and had to render "
                        + "the page itself");
    }

    @Test
    public void expiryIsNotContagious() {
        MutableClock clock = new MutableClock(0L);
        ExpiringLRUCacheImpl cache =
                new ExpiringLRUCacheImpl("test", MAX_SIZE, TIMEOUT_SECONDS, clock);

        cache.put("early", "value");
        clock.now = 10_000L;
        cache.put("late", "value");

        // each entry carries its own timestamp, so the second one has 10 more
        // seconds to live than the first
        clock.now = 20_000L;
        assertNull(cache.get("early"), "The entry cached at 0s must be gone 20s later");
        assertEquals("value", cache.get("late"),
                "The entry cached at 10s must still be fresh 20s later; entries expire "
                        + "individually, not as a generation");
    }

    @Test
    public void sizeLimitStillEvictsLeastRecentlyUsed() {
        MutableClock clock = new MutableClock(0L);
        ExpiringLRUCacheImpl cache = new ExpiringLRUCacheImpl("test", 2, TIMEOUT_SECONDS, clock);

        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.get("k1");
        cache.put("k3", "v3");

        assertNull(cache.get("k2"), "The size limit still applies: k2 was least recently used");
        assertEquals("v1", cache.get("k1"), "An unexpired, recently used entry must be kept");
        assertEquals("v3", cache.get("k3"), "The newest entry must be kept");
    }

    @Test
    public void unknownKeyIsAMissRatherThanAnExpiry() {
        MutableClock clock = new MutableClock(0L);
        ExpiringLRUCacheImpl cache =
                new ExpiringLRUCacheImpl("test", MAX_SIZE, TIMEOUT_SECONDS, clock);

        assertNull(cache.get("never-cached"), "An uncached key reads as null");
        assertEquals(1.0, cache.getStats().get("misses"), "and is counted as a miss");
        assertEquals(0.0, cache.getStats().get("removes"),
                "with nothing to evict -- a miss must not be mistaken for an expiry");
    }

    @Test
    public void nullValuesCannotBeCached() {
        MutableClock clock = new MutableClock(0L);
        ExpiringLRUCacheImpl cache =
                new ExpiringLRUCacheImpl("test", MAX_SIZE, TIMEOUT_SECONDS, clock);

        cache.put("key", null);

        // the wrapper cannot tell "cached null" from "expired", so the entry is
        // treated as expired on the very first read; callers must not try to
        // cache the absence of something
        assertNull(cache.get("key"), "A cached null reads back as null");
        assertEquals(1.0, cache.getStats().get("removes"),
                "and is dropped immediately, so it never occupies the cache");
    }

    @Test
    public void defaultConstructorExpiresAfterAnHour() {
        ExpiringLRUCacheImpl cache = new ExpiringLRUCacheImpl("test");

        assertEquals(PressroomConstants.HOUR_IN_MS, cache.getTimeoutMillis(),
                "The no-timeout constructor documents an hour as the default lifetime");
        cache.put("key", "value");
        assertNotNull(cache.get("key"), "and an entry cached now is nowhere near that old");
    }

    @Test
    public void aNonPositiveTimeoutMeansEntriesDoNotSurviveTheClockMoving() {
        MutableClock clock = new MutableClock(0L);
        ExpiringLRUCacheImpl cache = new ExpiringLRUCacheImpl("test", MAX_SIZE, -5, clock);

        assertEquals(0L, cache.getTimeoutMillis(),
                "A negative timeout is meaningless and must not be turned into a "
                        + "negative lifetime; it is clamped to zero");

        cache.put("key", "value");
        assertEquals("value", cache.get("key"), "An entry is readable within the same instant");

        clock.now = 1L;
        assertNull(cache.get("key"), "and gone as soon as any time has passed");
    }
}
