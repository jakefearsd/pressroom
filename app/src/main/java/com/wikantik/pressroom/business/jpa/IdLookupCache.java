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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.wikantik.pressroom.WebloggerException;

/**
 * A bounded key-to-id map in front of a by-key query (entry by weblog and
 * anchor, weblog by handle, user by name). The map stores only the id: a hit
 * is loaded through {@code idLoader} (free while the row is in EclipseLink's
 * shared cache) and is returned only if the row still carries the key it was
 * mapped under. A gone row or a changed key evicts the mapping and the key is
 * looked up afresh through the caller's query. Bounded to an LRU of {@link #DEFAULT_CAPACITY} mappings
 * (a few hundred KB of short strings at most), so a crawl over many
 * anchors cannot grow it without limit.
 *
 * @param <K> the lookup key
 * @param <E> the entity
 */
final class IdLookupCache<K, E> {

    /** Mappings kept; the least recently used goes first. */
    static final int DEFAULT_CAPACITY = 10_000;

    /** Loads by id; null when the row is gone. */
    @FunctionalInterface
    interface Loader<A, R> {
        R apply(A argument) throws WebloggerException;
    }

    private final Map<K, String> ids;
    private final Loader<String, E> idLoader;
    private final Function<E, K> keyOf;
    private final Function<E, String> idOf;

    IdLookupCache(int capacity, Loader<String, E> idLoader, Function<E, K> keyOf,
            Function<E, String> idOf) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
        }
        this.ids = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, String> eldest) {
                return size() > capacity;
            }
        });
        this.idLoader = idLoader;
        this.keyOf = keyOf;
        this.idOf = idOf;
    }

    IdLookupCache(Loader<String, E> idLoader, Function<E, K> keyOf, Function<E, String> idOf) {
        this(DEFAULT_CAPACITY, idLoader, keyOf, idOf);
    }

    /**
     * The entity for the key, or null when there is none. {@code fallbackQuery}
     * is the by-key query, run when the mapping is absent or stale; it comes
     * with the call because it needs the caller's own context (the weblog
     * instance, not just its handle).
     */
    E find(K key, Loader<K, E> fallbackQuery) throws WebloggerException {
        String id = ids.get(key);
        if (id != null) {
            E cached = idLoader.apply(id);
            if (cached != null && key.equals(keyOf.apply(cached))) {
                return cached;
            }
            ids.remove(key);
        }
        E found = fallbackQuery.apply(key);
        if (found != null) {
            ids.put(key, idOf.apply(found));
        }
        return found;
    }

    /** Forgets the key's mapping. */
    void evict(K key) {
        ids.remove(key);
    }
}
