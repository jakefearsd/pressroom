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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests the bounded LRU map that every Roller cache is built on.
 *
 * The size bound is the only thing stopping a long-running server from growing
 * its rendering caches without limit, and "least recently used" is the only
 * thing stopping the bound from evicting the pages that are actually being
 * read. Both are easy to break without noticing -- dropping the access-order
 * flag on the backing LinkedHashMap silently turns this into a FIFO cache --
 * so they are pinned here rather than assumed.
 */
public class LRUCacheImplTest {

    @Test
    public void cachedValueComesBackOutAgain() {
        LRUCacheImpl cache = new LRUCacheImpl("test", 10);

        cache.put("key", "value");

        assertEquals("value", cache.get("key"),
                "A value put in the cache must come back out unchanged");
        assertEquals("test", cache.getId(),
                "getId() must report the id the cache was constructed with; "
                        + "CacheManager files caches under it");
    }

    @Test
    public void unknownKeyIsAMiss() {
        LRUCacheImpl cache = new LRUCacheImpl("test", 10);

        assertNull(cache.get("never-cached"),
                "A key that was never cached must read as null, not as anything else");
        assertEquals(1.0, cache.getStats().get("misses"),
                "A read of an uncached key must be counted as a miss");
    }

    @Test
    public void cacheHoldsExactlyItsStatedSize() {
        LRUCacheImpl cache = new LRUCacheImpl("test", 3);

        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.put("k3", "v3");

        // if the eviction check were off by one this third entry would already
        // be gone, and the cache would quietly hold one less than configured
        assertNotNull(cache.get("k1"), "A cache of size 3 must still hold the 1st of 3 entries");
        assertNotNull(cache.get("k2"), "A cache of size 3 must still hold the 2nd of 3 entries");
        assertNotNull(cache.get("k3"), "A cache of size 3 must still hold the 3rd of 3 entries");
    }

    @Test
    public void evictsLeastRecentlyUsedRatherThanOldest() {
        LRUCacheImpl cache = new LRUCacheImpl("test", 3);

        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.put("k3", "v3");

        // reading k1 makes k2 the least recently used, even though k1 is oldest
        assertNotNull(cache.get("k1"), "precondition: k1 is still cached before it is read");

        cache.put("k4", "v4");

        assertNull(cache.get("k2"),
                "The least recently used entry (k2) must be the one evicted");
        assertNotNull(cache.get("k1"),
                "Reading an entry must count as using it -- k1 was read and must survive. "
                        + "If k1 was evicted the backing map is in insertion order (FIFO), "
                        + "not access order: check the accessOrder flag on LRULinkedHashMap.");
        assertNotNull(cache.get("k3"), "k3 was cached after k2 and must survive");
        assertNotNull(cache.get("k4"), "The entry that caused the eviction must itself be cached");
    }

    @Test
    public void overwritingAnEntryCountsAsUsingIt() {
        LRUCacheImpl cache = new LRUCacheImpl("test", 3);

        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.put("k3", "v3");

        // re-rendering a page and re-caching it must refresh its position,
        // otherwise the hottest page in the system is first out
        cache.put("k1", "v1-refreshed");
        cache.put("k4", "v4");

        assertNull(cache.get("k2"), "k2 was the least recently used and must be evicted");
        assertEquals("v1-refreshed", cache.get("k1"),
                "Re-putting an entry must refresh both its value and its position");
    }

    @Test
    public void sizeZeroKeepsNothing() {
        // the config allows a size of 0, which is how an admin turns a cache
        // off without touching code; it must not blow up on construction
        LRUCacheImpl cache = new LRUCacheImpl("test", 0);

        cache.put("k1", "v1");

        assertNull(cache.get("k1"), "A cache with size 0 must not retain anything");
    }

    @Test
    public void removedEntryIsGone() {
        LRUCacheImpl cache = new LRUCacheImpl("test", 10);
        cache.put("k1", "v1");
        cache.put("k2", "v2");

        cache.remove("k1");

        assertNull(cache.get("k1"), "A removed entry must no longer be readable");
        assertEquals("v2", cache.get("k2"), "Removing one entry must not disturb the others");
        assertEquals(1.0, cache.getStats().get("removes"), "The removal must be counted");
    }

    @Test
    public void removingAnUncachedKeyIsHarmless() {
        LRUCacheImpl cache = new LRUCacheImpl("test", 10);
        cache.put("k1", "v1");

        cache.remove("never-cached");

        assertEquals("v1", cache.get("k1"),
                "Removing a key that was never cached must leave the cache alone");
    }

    @Test
    public void clearEmptiesTheCacheAndResetsTheCounters() {
        MutableClock clock = new MutableClock(1_000L);
        LRUCacheImpl cache = new LRUCacheImpl("test", 10, clock);
        cache.put("k1", "v1");
        cache.get("k1");
        cache.get("nope");
        cache.remove("k1");

        clock.now = 60_000L;
        cache.clear();

        assertNull(cache.get("k1"), "clear() must drop the cached entries");
        Map<String, Object> stats = cache.getStats();
        // the read above is the only thing that may be counted after the clear
        assertEquals(0.0, stats.get("hits"), "clear() must reset the hit counter");
        assertEquals(1.0, stats.get("misses"), "Only the read after the clear may be counted");
        assertEquals(0.0, stats.get("puts"), "clear() must reset the put counter");
        assertEquals(0.0, stats.get("removes"), "clear() must reset the remove counter");
        assertEquals(new java.util.Date(60_000L), stats.get("startTime"),
                "clear() must restart the stats period, so that the counters reported "
                        + "alongside it describe the time since the flush, not since startup");
    }

    @Test
    public void statsReportWhenTheCurrentCountingPeriodBegan() {
        MutableClock clock = new MutableClock(123_456L);

        LRUCacheImpl cache = new LRUCacheImpl("test", 10, clock);

        assertEquals(new java.util.Date(123_456L), cache.getStats().get("startTime"),
                "A fresh cache must report the moment it was created as the start of "
                        + "its counting period");
    }

    @Test
    public void statsReportEfficiencyAsAPercentageOfReadsThatHit() {
        LRUCacheImpl cache = new LRUCacheImpl("test", 10);
        cache.put("k1", "v1");

        cache.get("k1");
        cache.get("k1");
        cache.get("k1");
        cache.get("nope");

        Map<String, Object> stats = cache.getStats();
        assertEquals(3.0, stats.get("hits"), "Three reads of a cached key are three hits");
        assertEquals(1.0, stats.get("misses"), "One read of an uncached key is one miss");
        assertEquals(1.0, stats.get("puts"), "One write is one put");
        assertEquals(75.0, stats.get("efficiency"),
                "Efficiency is the percentage of reads that hit: 3 of 4 is 75%");
    }

    @Test
    public void efficiencyIsOmittedWhenThereIsNothingToDivide() {
        LRUCacheImpl cache = new LRUCacheImpl("test", 10);

        // no reads at all: reporting 0% or NaN efficiency would be a lie about
        // a cache nobody has asked anything of yet
        assertFalse(cache.getStats().containsKey("efficiency"),
                "A cache that has never been read must not report an efficiency");
    }

    @Test
    public void efficiencyIsOmittedWhenTheMissesAreExplainedByRemovals() {
        LRUCacheImpl cache = new LRUCacheImpl("test", 10);
        cache.put("k1", "v1");
        cache.get("k1");
        cache.remove("k1");
        cache.remove("k1");

        // the efficiency is only reported when there were more misses than
        // removals -- a cache that is mostly being invalidated would otherwise
        // report a hit rate that says more about the invalidations than about
        // the cache
        assertFalse(cache.getStats().containsKey("efficiency"),
                "A cache with more removals than misses must not report an efficiency");
    }

    @Test
    public void aNullValueIsIndistinguishableFromAMiss() {
        LRUCacheImpl cache = new LRUCacheImpl("test", 10);

        cache.put("k1", null);

        // worth knowing and worth pinning: callers cannot cache "there is no
        // such page" by caching null, they will re-render it every time
        assertNull(cache.get("k1"), "A cached null reads back as null");
        assertEquals(1.0, cache.getStats().get("misses"),
                "Reading a cached null is counted as a miss, not a hit -- caching null "
                        + "does not save any work");
    }

    @Test
    public void aNullKeyIsAcceptedAndReadableAgain() {
        LRUCacheImpl cache = new LRUCacheImpl("test", 10);

        cache.put(null, "value");

        // a key generator with a bug can hand us a null; losing the entry is
        // acceptable, throwing on every request for that page is not
        assertEquals("value", cache.get(null), "A null key must not throw and must round-trip");
    }

    @Test
    public void defaultSizedCacheHoldsAHundredEntries() {
        LRUCacheImpl cache = new LRUCacheImpl("test");

        for (int i = 0; i < 100; i++) {
            cache.put("k" + i, "v" + i);
        }

        assertNotNull(cache.get("k0"),
                "The default cache must hold 100 entries; the oldest of exactly 100 "
                        + "is still there");

        // reading k0 above made k1 the least recently used
        cache.put("k100", "v100");

        assertNull(cache.get("k1"), "Entry 101 must evict the least recently used of the 100");
        assertNotNull(cache.get("k0"), "The entry read a moment ago must survive");
    }

    /** A clock the test moves by hand, so no assertion here depends on wall time. */
    static final class MutableClock implements java.util.function.LongSupplier {
        long now;

        MutableClock(long now) {
            this.now = now;
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }
}
