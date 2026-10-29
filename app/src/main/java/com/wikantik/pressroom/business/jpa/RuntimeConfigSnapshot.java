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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.wikantik.pressroom.WebloggerException;

/**
 * Every runtime property's committed value, loaded in one read and held
 * until an {@linkplain #invalidate() invalidation}.
 *
 * <p>Each load is tagged with the generation it began in, and an
 * invalidation starts a new one. A load that an invalidation overtook -- it
 * read the rows a save was about to replace, and finished after that save
 * committed and invalidated -- answers the read that ran it but is never
 * served again, since its generation is no longer current. Lock-free: a
 * stale load may still overwrite a current one, which costs one extra load.
 */
final class RuntimeConfigSnapshot {

    private record Values(long generation, Map<String, String> byName) {
    }

    /** Reads every property's committed value. */
    @FunctionalInterface
    interface Loader {
        Map<String, String> load() throws WebloggerException;
    }

    private final AtomicLong generation = new AtomicLong();
    private volatile Values current;

    /** The remembered values, loaded through {@code loader} when there are none. */
    Map<String, String> values(Loader loader) throws WebloggerException {
        Values held = current;
        long now = generation.get();
        if (held != null && held.generation() == now) {
            return held.byName();
        }
        // A HashMap copy: a property's value may be null, which Map.copyOf refuses.
        Map<String, String> loaded = Collections.unmodifiableMap(new HashMap<>(loader.load()));
        current = new Values(now, loaded);
        return loaded;
    }

    /** Drops the values; the next read loads them again. */
    void invalidate() {
        generation.incrementAndGet();
        current = null;
    }
}
