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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * Fills the slots of one front-page section: the top-ranked items first, then a
 * share of weighted random picks from the whole pool. Pure; no I/O.
 *
 * <pre>
 * SlotFill.of(ranked, pool).slots(5).sharePercent(20).weight(w)
 *         .capPerGroup(DiscoveryCandidate::weblogId, 2, alreadyShown)
 *         .into(taken, random);
 * </pre>
 *
 * <p>{@code slots}, {@code sharePercent} and {@code weight} are required;
 * {@code into} fails on a missing one rather than guess. Without
 * {@code capPerGroup} there is no cap.
 *
 * @param <T> the item picked
 */
public final class SlotFill<T> {

    private final List<T> ranked;
    private final List<T> pool;
    private Integer slots;
    private Integer sharePercent;
    private ToDoubleFunction<T> weight;
    private Function<T, ?> group = item -> item;
    private int perGroup = Integer.MAX_VALUE;
    private Map<?, Integer> alreadyShown = Map.of();

    private SlotFill(final List<T> ranked, final List<T> pool) {
        this.ranked = ranked;
        this.pool = pool;
    }

    /**
     * @param ranked score-descending items that have scores
     * @param pool every candidate
     */
    public static <T> SlotFill<T> of(final List<T> ranked, final List<T> pool) {
        return new SlotFill<>(ranked, pool);
    }

    /** The most items to pick. */
    public SlotFill<T> slots(final int count) {
        this.slots = count;
        return this;
    }

    /** The percentage of slots filled by weighted random picks ({@link DiscoverySlots#randomSlots}). */
    public SlotFill<T> sharePercent(final int percent) {
        this.sharePercent = percent;
        return this;
    }

    /** The odds of a random pick; zero and negative weights stay eligible. */
    public SlotFill<T> weight(final ToDoubleFunction<T> weigher) {
        this.weight = weigher;
        return this;
    }

    /**
     * At most {@code max} picks from any one group: a ranked item whose
     * group is full is passed over for the next-ranked of another group, and
     * random picks are drawn only from groups with room. The cap never costs
     * a slot: when no other group has an item left, the slots still empty go
     * to the items passed over, ranked ones first in rank order, then random.
     * Items already in {@code taken} are not counted.
     *
     * @param groupOf the group an item belongs to (a post's blog)
     * @param max the most picks from one group
     * @param shownElsewhere picks per group made outside this section that
     *                       count toward the cap (the lead story's blog: 1)
     */
    public SlotFill<T> capPerGroup(final Function<T, ?> groupOf, final int max,
            final Map<?, Integer> shownElsewhere) {
        this.group = groupOf;
        this.perGroup = max;
        this.alreadyShown = shownElsewhere;
        return this;
    }

    /** {@link #capPerGroup(Function, int, Map)} with nothing shown elsewhere. */
    public SlotFill<T> capPerGroup(final Function<T, ?> groupOf, final int max) {
        return capPerGroup(groupOf, max, Map.of());
    }

    /**
     * @param taken cross-section dedupe set; read, and every chosen item is added
     * @param random the draw source for the random picks
     * @return ranked picks first, then random picks; shorter only if the pool runs out
     */
    public List<T> into(final Set<T> taken, final Random random) {
        if (slots == null || sharePercent == null || weight == null) {
            throw new IllegalStateException("slots, sharePercent and weight are required");
        }
        final List<T> chosen = new ArrayList<>();
        final Map<Object, Integer> picked = new HashMap<>(alreadyShown);
        final Predicate<T> groupHasRoom = item -> picked.getOrDefault(group.apply(item), 0) < perGroup;
        final int rankedWanted = slots - DiscoverySlots.randomSlots(slots, sharePercent);
        for (final T item : ranked) {
            if (chosen.size() >= rankedWanted) {
                break;
            }
            if (!taken.contains(item) && !chosen.contains(item) && groupHasRoom.test(item)) {
                chosen.add(item);
                picked.merge(group.apply(item), 1, Integer::sum);
            }
        }
        final List<T> remaining = pool.stream()
                .filter(item -> !taken.contains(item) && !chosen.contains(item))
                .collect(Collectors.toCollection(ArrayList::new));
        while (chosen.size() < slots) {
            final List<T> open = remaining.stream().filter(groupHasRoom).toList();
            if (open.isEmpty()) {
                break;
            }
            final T pick = open.get(DiscoverySlots.drawIndex(open, random, weight));
            remaining.remove(pick);
            chosen.add(pick);
            picked.merge(group.apply(pick), 1, Integer::sum);
        }
        // every group with an item left is full: relax the cap rather than leave a slot empty
        for (final T item : ranked) {
            if (chosen.size() < slots && remaining.remove(item)) {
                chosen.add(item);
            }
        }
        while (chosen.size() < slots && !remaining.isEmpty()) {
            chosen.add(remaining.remove(DiscoverySlots.drawIndex(remaining, random, weight)));
        }
        taken.addAll(chosen);
        return chosen;
    }
}
