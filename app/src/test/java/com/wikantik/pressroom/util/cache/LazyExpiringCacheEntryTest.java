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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the entry wrapper behind the render caches.
 *
 * Rather than hunting through the cache for the entries belonging to a weblog
 * whenever that weblog changes, those caches store each entry with the stamp
 * its render read (the weblog's lastModified) and compare it with the stamp
 * current on the way out. That comparison is the whole invalidation mechanism
 * for every blog page Pressroom serves: if it goes the wrong way, readers keep
 * seeing a deleted post, or every page misses the cache forever.
 */
public class LazyExpiringCacheEntryTest {

    private static final long READ = 1_700_000_000_000L;

    @Test
    public void anEntryBuiltFromTheCurrentStampIsServed() {
        LazyExpiringCacheEntry entry = new LazyExpiringCacheEntry("rendered page", READ);

        assertFalse(entry.isInvalid(READ),
                "The render read the weblog as it still is: current, whatever the clock says");
        assertEquals("rendered page", entry.getValue(READ), "so its content is served");
        assertEquals("rendered page", entry.getValue(READ - 1), "and against an older stamp too");
    }

    @Test
    public void anEntryBuiltFromAnOlderStampIsStale() {
        LazyExpiringCacheEntry entry = new LazyExpiringCacheEntry("rendered page", READ);

        assertTrue(entry.isInvalid(READ + 1),
                "The weblog changed after the render read it: stale, however late it was stored");
        assertNull(entry.getValue(READ + 1),
                "and withheld -- serving it is how readers end up looking at a post that "
                        + "was already deleted");
    }

    @Test
    public void neverInvalidatedWeblogsServeTheirCachedPages() {
        LazyExpiringCacheEntry entry = new LazyExpiringCacheEntry("rendered page", READ);

        assertEquals("rendered page", entry.getValue(0L),
                "A weblog that has never been modified must serve its cached pages");
    }

    @Test
    public void theEntryKeepsTheStampItWasBuiltFrom() {
        assertEquals(READ, new LazyExpiringCacheEntry("rendered page", READ).getBasis(),
                "every freshness decision is made against the stamp the render read, never "
                        + "against the time the entry was stored");
    }
}
