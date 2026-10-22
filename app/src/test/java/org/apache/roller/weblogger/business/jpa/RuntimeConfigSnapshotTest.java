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
package org.apache.roller.weblogger.business.jpa;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The remembered runtime property values, without a database: loaded once,
 * dropped by an invalidation, and never kept when an invalidation lands while
 * they were being loaded.
 */
class RuntimeConfigSnapshotTest {

    private final RuntimeConfigSnapshot snapshot = new RuntimeConfigSnapshot();
    private final AtomicInteger loads = new AtomicInteger();

    private Map<String, String> load(String value) {
        loads.incrementAndGet();
        return Map.of("site.name", value);
    }

    @Test
    void valuesAreLoadedOnceAndRemembered() throws Exception {
        assertEquals("one", snapshot.values(() -> load("one")).get("site.name"));
        assertEquals("one", snapshot.values(() -> load("two")).get("site.name"));
        assertEquals(1, loads.get());
    }

    @Test
    void anInvalidationDropsThem() throws Exception {
        snapshot.values(() -> load("one"));
        snapshot.invalidate();
        assertEquals("two", snapshot.values(() -> load("two")).get("site.name"));
    }

    /**
     * The race a save and a concurrent first read run: the read loads the
     * values the save is about to replace, the save commits and invalidates,
     * and only then does the read finish. What it loaded answers that one
     * read, and is not remembered.
     */
    @Test
    void valuesLoadedAcrossAnInvalidationAreNotRemembered() throws Exception {
        assertEquals("stale", snapshot.values(() -> {
            Map<String, String> before = load("stale");
            snapshot.invalidate(); // the save commits mid-load
            return before;
        }).get("site.name"));

        assertEquals("fresh", snapshot.values(() -> load("fresh")).get("site.name"));
    }

    /** A row may hold a null value; it is remembered as null, not refused. */
    @Test
    void aNullValueIsHeld() throws Exception {
        Map<String, String> withNull = new HashMap<>();
        withNull.put("site.name", null);
        assertTrue(snapshot.values(() -> withNull).containsKey("site.name"));
    }
}
