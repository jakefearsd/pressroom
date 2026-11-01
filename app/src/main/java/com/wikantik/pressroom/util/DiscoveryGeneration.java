/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Which discovery snapshot is current, as the epoch milliseconds it was built
 * at. Random picks are seeded from it, so they hold for one generation and
 * reshuffle when it moves; a rendering that carries a pick is as new as the
 * generation it was built from.
 *
 * <p>Like {@link ServingSince}, a process-wide fact rather than a bean: the
 * caches that date renderings by it are static too. It only ever moves
 * forward, so a rendering dated by it can never look older than one built
 * before it.
 */
public final class DiscoveryGeneration {

    private static final AtomicLong CURRENT = new AtomicLong();

    private DiscoveryGeneration() {
    }

    /** The current generation; 0 until the first snapshot is built. */
    public static long current() {
        return CURRENT.get();
    }

    /** Moves the generation to {@code millis}; a value not after the current one is ignored. */
    public static void advance(long millis) {
        CURRENT.accumulateAndGet(millis, Math::max);
    }
}
