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
package com.wikantik.pressroom.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The discovery generation dates every rendering that carries a discovery
 * pick; a cache keyed on it must never see it go backwards, or a page built
 * from an older snapshot would look newer than one built from a later one.
 */
class DiscoveryGenerationTest {

    @Test
    void advanceIsMonotonic() {
        // Just past wherever it is, never into the future: the generation is
        // process-wide, and one parked ahead of the clock would make every
        // cached page in later tests look stale.
        long later = Math.max(DiscoveryGeneration.current(), 1_000L) + 10L;

        DiscoveryGeneration.advance(later);
        assertEquals(later, DiscoveryGeneration.current());

        DiscoveryGeneration.advance(later - 5L);
        assertEquals(later, DiscoveryGeneration.current(), "an earlier value is ignored");

        DiscoveryGeneration.advance(later);
        assertEquals(later, DiscoveryGeneration.current(), "an equal value is ignored");

        DiscoveryGeneration.advance(later + 1);
        assertEquals(later + 1, DiscoveryGeneration.current());
    }
}
