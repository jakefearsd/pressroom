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
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Fills the slots of one front-page section: the top-ranked items first, then a
 * share of weighted random picks from the whole pool. Pure; no I/O.
 */
public final class DiscoverySlots {

    /** Weight given to a zero or negative weight, so such items stay eligible. */
    private static final double TINY_WEIGHT = 1e-9;

    private DiscoverySlots() {
    }

    /**
     * Number of slots reserved for random picks: none at 0%, otherwise at least one
     * once there are three or more slots, never more than {@code slots}.
     */
    public static int randomSlots(final int slots, final int sharePercent) {
        if (sharePercent <= 0) {
            return 0;
        }
        final int floor = slots >= 3 ? 1 : 0;
        final int wanted = Math.max(floor, Math.round(slots * sharePercent / 100f));
        return Math.min(slots, wanted);
    }

    /**
     * Fills up to {@code slots} slots.
     *
     * @param ranked score-descending items that have scores
     * @param pool every candidate
     * @param taken cross-section dedupe set; read, and every chosen item is added
     * @return ranked picks first, then random picks; shorter only if the pool runs out
     */
    public static <T> List<T> fill(final List<T> ranked, final List<T> pool, final int slots,
            final int sharePercent, final Set<T> taken, final Random random,
            final ToDoubleFunction<T> weight) {
        final List<T> chosen = new ArrayList<>();
        final int rankedWanted = slots - randomSlots(slots, sharePercent);
        for (final T item : ranked) {
            if (chosen.size() >= rankedWanted) {
                break;
            }
            if (!taken.contains(item) && !chosen.contains(item)) {
                chosen.add(item);
            }
        }
        final List<T> remaining = new ArrayList<>();
        for (final T item : pool) {
            if (!taken.contains(item) && !chosen.contains(item)) {
                remaining.add(item);
            }
        }
        while (chosen.size() < slots && !remaining.isEmpty()) {
            chosen.add(remaining.remove(drawIndex(remaining, random, weight)));
        }
        taken.addAll(chosen);
        return chosen;
    }

    private static <T> int drawIndex(final List<T> items, final Random random, final ToDoubleFunction<T> weight) {
        final double[] cumulative = new double[items.size()];
        double total = 0;
        for (int i = 0; i < cumulative.length; i++) {
            final double w = weight.applyAsDouble(items.get(i));
            total += w > 0 ? w : TINY_WEIGHT;
            cumulative[i] = total;
        }
        final double target = random.nextDouble() * total;
        for (int i = 0; i < cumulative.length; i++) {
            if (target < cumulative[i]) {
                return i;
            }
        }
        return cumulative.length - 1;
    }
}
