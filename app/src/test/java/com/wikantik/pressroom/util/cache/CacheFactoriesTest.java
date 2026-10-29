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

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the two cache factories, which turn the string properties an admin
 * writes in pressroom.properties into a configured cache.
 *
 * Everything here comes in as text from a config file that nothing validates,
 * so the factories have to survive nonsense without taking the site down: a
 * mistyped cache size must cost a sensible default, not a failure to start.
 */
public class CacheFactoriesTest {

    @Test
    public void expiringFactoryAppliesTheConfiguredIdSizeAndTimeout() {
        Map<String, String> props = new HashMap<>();
        props.put("id", "cache.test");
        props.put("size", "2");
        props.put("timeout", "1800");

        Cache cache = new ExpiringLRUCacheFactoryImpl().constructCache(props);

        assertEquals("cache.test", cache.getId(), "The configured id must reach the cache");
        assertEquals(1_800_000L, ((ExpiringLRUCacheImpl) cache).getTimeoutMillis(),
                "The timeout is configured in seconds and must be applied in milliseconds");
        assertSizeIs(2, cache);
    }

    @Test
    public void expiringFactoryFallsBackToDefaultsWhenNothingIsConfigured() {
        Cache cache = new ExpiringLRUCacheFactoryImpl().constructCache(new HashMap<String, String>());

        assertEquals("unknown", cache.getId(),
                "A cache with no configured id still needs one to be filed under");
        assertEquals(900_000L, ((ExpiringLRUCacheImpl) cache).getTimeoutMillis(),
                "The documented default lifetime is 15 minutes");
        assertSizeIs(100, cache);
    }

    @Test
    public void expiringFactoryIgnoresPropertiesItCannotRead() {
        Map<String, Object> props = new HashMap<>();
        props.put("id", "cache.test");
        // a typo in pressroom.properties, and a value that is not even a String
        props.put("size", "one hundred");
        props.put("timeout", 1800);

        Cache cache = new ExpiringLRUCacheFactoryImpl().constructCache(props);

        assertEquals("cache.test", cache.getId(),
                "An unreadable size or timeout must not cost us the rest of the config");
        assertEquals(900_000L, ((ExpiringLRUCacheImpl) cache).getTimeoutMillis(),
                "An unreadable timeout falls back to the default rather than failing");
        assertSizeIs(100, cache);
    }

    @Test
    public void plainFactoryAppliesTheConfiguredIdAndSize() {
        Map<String, String> props = new HashMap<>();
        props.put("id", "cache.test");
        props.put("size", "2");

        Cache cache = new LRUCacheFactoryImpl().constructCache(props);

        assertEquals("cache.test", cache.getId(), "The configured id must reach the cache");
        assertTrue(cache instanceof LRUCacheImpl, "The plain factory builds an LRU cache");
        assertFalse(cache instanceof ExpiringLRUCacheImpl,
                "and specifically not an expiring one -- entries in this cache live until "
                        + "they are evicted or invalidated");
        assertSizeIs(2, cache);
    }

    @Test
    public void plainFactoryFallsBackToDefaultsWhenNothingIsConfigured() {
        Cache cache = new LRUCacheFactoryImpl().constructCache(new HashMap<String, String>());

        assertEquals("unknown", cache.getId(),
                "A cache with no configured id still needs one to be filed under");
        assertSizeIs(100, cache);
    }

    @Test
    public void plainFactoryIgnoresASizeItCannotRead() {
        Map<String, String> props = new HashMap<>();
        props.put("id", "cache.test");
        props.put("size", "");

        Cache cache = new LRUCacheFactoryImpl().constructCache(props);

        assertEquals("cache.test", cache.getId(),
                "An unreadable size must not cost us the rest of the config");
        assertSizeIs(100, cache);
    }

    /**
     * Asserts the cache holds exactly the given number of entries, by filling it
     * and watching the oldest fall out. The size limit is not readable from the
     * Cache interface, and it is the only thing bounding memory, so it is worth
     * establishing from the outside.
     */
    private static void assertSizeIs(int expectedSize, Cache cache) {
        for (int i = 0; i < expectedSize; i++) {
            cache.put("k" + i, "v" + i);
        }
        assertNotNull(cache.get("k0"),
                "A cache configured to hold " + expectedSize + " entries dropped one early");

        // k0 was just read, so k1 is now the least recently used
        cache.put("overflow", "v");
        assertNull(cache.get("k1"),
                "A cache configured to hold " + expectedSize + " entries kept " +
                        (expectedSize + 1));
    }
}
