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
 * Tests the wrapper that gives a cached object a lifetime.
 *
 * This is what the expiring caches and the XSRF salt cache lean on: a salt that
 * outlives its window lets a stale form post through, and a page that outlives
 * its window is served to readers after it should have been re-rendered. The
 * exact boundary therefore matters, so these tests state the creation time
 * rather than reading it off the wall clock.
 */
public class ExpiringCacheEntryTest {

    private static final long CACHED_AT = 1_000_000L;
    private static final long ONE_MINUTE = 60_000L;

    @Test
    public void valueIsServedRightUpToTheExpiryInstant() {
        ExpiringCacheEntry entry = new ExpiringCacheEntry("value", ONE_MINUTE, CACHED_AT);

        assertEquals("value", entry.getValue(CACHED_AT),
                "The entry must be readable the instant it is cached");
        assertEquals("value", entry.getValue(CACHED_AT + ONE_MINUTE),
                "The entry is still valid at exactly cachedAt + timeout: expiry begins "
                        + "after the timeout has passed, not on its last tick");
        assertFalse(entry.hasExpired(CACHED_AT + ONE_MINUTE),
                "and it must not consider itself expired at that instant either");
    }

    @Test
    public void valueIsWithheldOnceTheTimeoutHasPassed() {
        ExpiringCacheEntry entry = new ExpiringCacheEntry("value", ONE_MINUTE, CACHED_AT);

        assertTrue(entry.hasExpired(CACHED_AT + ONE_MINUTE + 1),
                "One millisecond past the timeout the entry has expired");
        assertNull(entry.getValue(CACHED_AT + ONE_MINUTE + 1),
                "An expired entry must hand back null rather than stale content -- "
                        + "callers treat null as 'not cached' and render afresh");
    }

    @Test
    public void aNegativeTimeoutIsClampedRatherThanBackdated() {
        ExpiringCacheEntry entry = new ExpiringCacheEntry("value", -ONE_MINUTE, CACHED_AT);

        assertEquals(0L, entry.getTimeout(),
                "A negative timeout must be clamped to zero. Left alone it would place "
                        + "the expiry a minute before the entry was created, which is "
                        + "merely strange here but is arithmetic no caller expects.");
        assertEquals("value", entry.getValue(CACHED_AT),
                "A zero timeout still lets the entry be read within the same instant");
        assertTrue(entry.hasExpired(CACHED_AT + 1), "and expires as soon as time moves on");
    }

    @Test
    public void reportsWhenItWasCachedAndForHowLong() {
        ExpiringCacheEntry entry = new ExpiringCacheEntry("value", ONE_MINUTE, CACHED_AT);

        // the admin cache page reports these; they must describe this entry and
        // not, say, the moment they were asked for
        assertEquals(CACHED_AT, entry.getTimeCached(), "getTimeCached() reports when it was cached");
        assertEquals(ONE_MINUTE, entry.getTimeout(), "getTimeout() reports its configured lifetime");
    }

    @Test
    public void publicAccessorsReadTheSystemClock() {
        // no injected clock here: this is the constructor and the accessors
        // that production code actually calls
        ExpiringCacheEntry fresh = new ExpiringCacheEntry("value", ONE_MINUTE);
        ExpiringCacheEntry stale =
                new ExpiringCacheEntry("value", 0L, System.currentTimeMillis() - ONE_MINUTE);

        assertFalse(fresh.hasExpired(),
                "An entry cached now with a minute to live cannot already be expired");
        assertEquals("value", fresh.getValue(), "so its value is served");

        assertTrue(stale.hasExpired(),
                "An entry stamped a minute ago with no lifetime is long expired");
        assertNull(stale.getValue(), "so its value is withheld");
    }
}
