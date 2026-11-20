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
package com.wikantik.pressroom.business.jpa;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The key-to-id cache behind the entry, weblog and user lookups. */
class IdLookupCacheTest {

    /** name to value pairs standing in for rows: key is the name, id is "id-" + name. */
    private record Row(String id, String name) { }

    private final Map<String, Row> rowsById = new HashMap<>();
    private final AtomicInteger queries = new AtomicInteger();

    private IdLookupCache<String, Row> cache(int capacity) {
        return new IdLookupCache<>(capacity, rowsById::get, Row::name, Row::id);
    }

    private Row query(String name) {
        queries.incrementAndGet();
        return rowsById.values().stream().filter(r -> r.name().equals(name)).findFirst().orElse(null);
    }

    private Row find(IdLookupCache<String, Row> cache, String name) throws Exception {
        return cache.find(name, this::query);
    }

    private Row row(String name) {
        Row r = new Row("id-" + name, name);
        rowsById.put(r.id(), r);
        return r;
    }

    @Test
    void aSecondLookupIsServedFromTheMapping() throws Exception {
        Row a = row("a");
        IdLookupCache<String, Row> cache = cache(10);
        assertEquals(a, find(cache, "a"));
        assertEquals(a, find(cache, "a"));
        assertEquals(1, queries.get());
    }

    @Test
    void aMissIsNotRemembered() throws Exception {
        IdLookupCache<String, Row> cache = cache(10);
        assertNull(find(cache, "a"));
        Row a = row("a");
        assertEquals(a, find(cache, "a"));
        assertEquals(2, queries.get());
    }

    @Test
    void aMappingToAGoneRowIsDroppedAndTheKeyLookedUpAfresh() throws Exception {
        Row a = row("a");
        IdLookupCache<String, Row> cache = cache(10);
        find(cache, "a");
        rowsById.remove(a.id());
        assertNull(find(cache, "a"));
        Row again = row("a");
        assertEquals(again, find(cache, "a"));
        assertEquals(3, queries.get());
    }

    @Test
    void aHitWhoseRowNowCarriesAnotherKeyIsNotReturned() throws Exception {
        Row a = row("a");
        IdLookupCache<String, Row> cache = cache(10);
        find(cache, "a");
        // the row behind the cached id was renamed: the old key must not resolve to it
        rowsById.put(a.id(), new Row(a.id(), "renamed"));
        assertNull(find(cache, "a"));
    }

    @Test
    void evictForgetsTheMapping() throws Exception {
        row("a");
        IdLookupCache<String, Row> cache = cache(10);
        find(cache, "a");
        cache.evict("a");
        find(cache, "a");
        assertEquals(2, queries.get());
    }

    @Test
    void theLeastRecentlyUsedMappingGoesWhenTheCapIsReached() throws Exception {
        row("a");
        row("b");
        row("c");
        IdLookupCache<String, Row> cache = cache(2);
        find(cache, "a");
        find(cache, "b");
        find(cache, "a");
        find(cache, "c");
        assertEquals(3, queries.get());
        find(cache, "a");
        find(cache, "c");
        assertEquals(3, queries.get(), "a and c are cached");
        find(cache, "b");
        assertEquals(4, queries.get(), "b was evicted");
    }

    @Test
    void aCapBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> cache(0));
    }
}
