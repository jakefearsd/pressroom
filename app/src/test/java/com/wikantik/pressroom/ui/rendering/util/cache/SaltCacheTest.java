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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests the cache of XSRF salt values.
 *
 * Unlike the other caches here this one is not an optimisation: a salt that
 * cannot be found again is a form post that gets rejected, and a salt that
 * outlives its window is one an attacker has longer to replay. It is also the
 * one cache that must be shared across a cluster, which is why it goes through
 * the CacheManager rather than a plain map.
 */
public class SaltCacheTest {

    private SaltCache cache;

    @BeforeEach
    public void startFromAnEmptyCache() {
        cache = SaltCache.getInstance();
        cache.clear();
    }

    @Test
    public void theInstanceIsShared() {
        assertSame(cache, SaltCache.getInstance(),
                "The salt written into a form and the salt checked on the way back in "
                        + "have to come from the same cache");
    }

    @Test
    public void aStoredSaltIsFoundAgain() {
        cache.put("salt-value", "/roller-ui/authoring/entryAdd.rol");

        assertEquals("/roller-ui/authoring/entryAdd.rol", cache.get("salt-value"),
                "A salt handed out with a form must be recognised when the form comes "
                        + "back, or every post is rejected as a forgery");
    }

    @Test
    public void anUnknownSaltIsNotRecognised() {
        assertNull(cache.get("never-issued"),
                "A salt Roller never issued must not be recognised -- this is the whole "
                        + "point of the cache");
    }

    @Test
    public void aUsedSaltCanBeTakenBackOut() {
        cache.put("salt-value", "/roller-ui/authoring/entryAdd.rol");

        cache.remove("salt-value");

        assertNull(cache.get("salt-value"),
                "Removing a salt must retire it, so it cannot be replayed");
    }

    @Test
    public void clearRetiresEverySalt() {
        cache.put("one", "/one.rol");
        cache.put("two", "/two.rol");

        cache.clear();

        assertNull(cache.get("one"), "clear() must retire every salt");
        assertNull(cache.get("two"), "including the last one issued");
    }
}
