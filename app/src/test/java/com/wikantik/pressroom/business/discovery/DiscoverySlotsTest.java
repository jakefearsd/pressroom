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

package com.wikantik.pressroom.business.discovery;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static com.wikantik.pressroom.business.discovery.DiscoverySlots.fill;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Slot filling: ranked picks first, then a seeded weighted random share. */
class DiscoverySlotsTest {

    private static List<String> pool(final String from, final String to) {
        final List<String> out = new ArrayList<>();
        for (char c = from.charAt(0); c <= to.charAt(0); c++) {
            out.add(String.valueOf(c));
        }
        return out;
    }

    @Test void zeroShareHasNoRandomSlots()      { assertEquals(0, DiscoverySlots.randomSlots(4, 0)); }
    @Test void twentyPercentOfFourIsOne()       { assertEquals(1, DiscoverySlots.randomSlots(4, 20)); }
    @Test void floorAppliesOnlyFromThreeSlots() {
        assertEquals(1, DiscoverySlots.randomSlots(3, 5));
        assertEquals(0, DiscoverySlots.randomSlots(2, 5));
    }
    @Test void fullShareIsAllRandom()           { assertEquals(4, DiscoverySlots.randomSlots(4, 100)); }
    @Test void shareIsCappedAtSlots()           { assertEquals(2, DiscoverySlots.randomSlots(2, 500)); }

    @Test void rankedSlotsFollowScoreOrder() {
        final List<String> got = fill(List.of("a", "b", "c", "d", "e"), pool("a", "j"), 4, 20,
                new HashSet<>(), new Random(1), x -> 1);
        assertEquals(List.of("a", "b", "c"), got.subList(0, 3));
        assertFalse(List.of("a", "b", "c").contains(got.get(3)));
    }

    @Test void gapsFillRandomlyWhenFewRanked() {
        final List<String> got = fill(List.of("a"), pool("a", "j"), 4, 0, new HashSet<>(), new Random(1), x -> 1);
        assertEquals(4, got.size());
        assertEquals("a", got.get(0));
        assertEquals(4, new HashSet<>(got).size());
    }

    @Test void takenItemsAreSkippedAndRecorded() {
        final Set<String> taken = new HashSet<>(Set.of("a"));
        final List<String> got = fill(List.of("a", "b"), pool("a", "e"), 3, 0, taken, new Random(1), x -> 1);
        assertFalse(got.contains("a"));
        assertTrue(taken.containsAll(got));
    }

    @Test void smallPoolNeverDuplicates() {
        final List<String> got = fill(List.of(), List.of("a", "b"), 4, 20, new HashSet<>(), new Random(1), x -> 1);
        assertEquals(Set.of("a", "b"), new HashSet<>(got));
        assertEquals(2, got.size());
    }

    @Test void sameSeedSamePicks() {
        assertEquals(fill(List.of(), pool("a", "j"), 3, 100, new HashSet<>(), new Random(7), x -> 1),
                fill(List.of(), pool("a", "j"), 3, 100, new HashSet<>(), new Random(7), x -> 1));
    }

    @Test void weightingFavoursHeavierItems() {
        int heavy = 0;
        int light = 0;
        for (int seed = 0; seed < 2000; seed++) {
            final List<String> got = fill(List.of(), List.of("heavy", "light"), 1, 100,
                    new HashSet<>(), new Random(seed), x -> "heavy".equals(x) ? 10 : 1);
            if ("heavy".equals(got.get(0))) {
                heavy++;
            } else {
                light++;
            }
        }
        assertTrue(light * 3 < heavy, "light=" + light + " heavy=" + heavy);
    }

    @Test void nonPositiveWeightIsNeverExcluded() {
        final List<String> got = fill(List.of(), List.of("a", "b"), 2, 100, new HashSet<>(), new Random(3), x -> 0);
        assertEquals(Set.of("a", "b"), new HashSet<>(got));
        final List<String> neg = fill(List.of(), List.of("a", "b"), 2, 100, new HashSet<>(), new Random(3), x -> -5);
        assertEquals(2, neg.size());
    }
}
