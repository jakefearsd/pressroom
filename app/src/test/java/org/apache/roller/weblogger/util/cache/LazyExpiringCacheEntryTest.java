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

package org.apache.roller.weblogger.util.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the entry wrapper behind the weblog page and feed caches.
 *
 * Rather than hunting through the cache for the entries belonging to a weblog
 * whenever that weblog changes, those caches record when the weblog was last
 * modified and compare it against the entry on the way out. That comparison is
 * the whole invalidation mechanism for every blog page Roller serves: if it
 * goes the wrong way, readers keep seeing a deleted post, or every page misses
 * the cache forever.
 */
public class LazyExpiringCacheEntryTest {

    @Test
    public void entryCachedAfterTheLastChangeIsStillGood() {
        LazyExpiringCacheEntry entry = new LazyExpiringCacheEntry("rendered page");
        long cachedAt = entry.getTimeCached();

        assertFalse(entry.isInvalid(cachedAt - 1),
                "An entry rendered after the last change to the weblog is still current");
        assertEquals("rendered page", entry.getValue(cachedAt - 1),
                "so its content is served");
    }

    @Test
    public void entryCachedBeforeTheLastChangeIsStale() {
        LazyExpiringCacheEntry entry = new LazyExpiringCacheEntry("rendered page");
        long cachedAt = entry.getTimeCached();

        assertTrue(entry.isInvalid(cachedAt + 1),
                "An entry rendered before the weblog last changed is stale");
        assertNull(entry.getValue(cachedAt + 1),
                "and must be withheld -- serving it is how readers end up looking at "
                        + "a post that was already deleted");
    }

    @Test
    public void entryCachedAtTheInstantOfTheChangeIsStale() {
        LazyExpiringCacheEntry entry = new LazyExpiringCacheEntry("rendered page");
        long cachedAt = entry.getTimeCached();

        // Within one millisecond the order of "the render read the weblog" and
        // "the change was saved" is unknowable, so a same-millisecond change
        // counts as newer. That costs one extra render; the other way round a
        // reader is served the pre-change page until the cache times out.
        assertTrue(entry.isInvalid(cachedAt),
                "An entry cached in the same millisecond as the change is stale");
        assertNull(entry.getValue(cachedAt), "and is withheld");
    }

    @Test
    public void neverInvalidatedWeblogsServeTheirCachedPages() {
        LazyExpiringCacheEntry entry = new LazyExpiringCacheEntry("rendered page");

        // 0 is what the caches pass for a weblog with no recorded change
        assertEquals("rendered page", entry.getValue(0L),
                "A weblog that has never been modified must serve its cached pages");
    }

    @Test
    public void timeCachedIsStampedFromTheClockAtConstruction() {
        long before = System.currentTimeMillis();

        LazyExpiringCacheEntry entry = new LazyExpiringCacheEntry("rendered page");

        long after = System.currentTimeMillis();
        assertTrue(entry.getTimeCached() >= before && entry.getTimeCached() <= after,
                "The entry must be stamped with the time it was created (" + before + ".."
                        + after + "), since every freshness decision is made against it");
    }
}
