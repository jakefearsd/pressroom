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
package com.wikantik.pressroom.business.related;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;

/**
 * Picks what a post page offers after the article: the nearest posts
 * ("Nearby": the same blog's, then other blogs' in the places left) and the
 * same blog's most related ones ("Keep reading"). Spec
 * {@code docs/superpowers/specs/2026-11-11-post-page-discovery-design.md}.
 *
 * <p>Pure: the caller loads the candidates and the shared-tag counts, so
 * this class reads no database and is tested without one.
 */
public final class RelatedPicker {

    /** Points for each tag a candidate shares with the current post. */
    static final int TAG_POINTS = 2;

    /** Points for being in the current post's category. */
    static final int CATEGORY_POINTS = 3;

    private static final double EARTH_RADIUS_KM = 6371.0088;

    /** A candidate and its distance from the current post, in kilometres. */
    public record Near(WeblogEntry entry, double km) { }

    private RelatedPicker() { }

    /**
     * The candidates nearest the current post, nearest first: at most
     * {@code max}, none farther than {@code maxKm}. Empty unless the current
     * post has both coordinates; a candidate missing either is passed over.
     */
    public static List<Near> nearby(WeblogEntry current, List<WeblogEntry> candidates,
            int max, double maxKm) {
        if (!hasPlace(current)) {
            return List.of();
        }
        return candidates.stream()
                .filter(candidate -> !isSame(current, candidate) && hasPlace(candidate))
                .map(candidate -> new Near(candidate, kilometres(current, candidate)))
                .filter(near -> near.km() <= maxKm)
                .sorted(Comparator.comparingDouble(Near::km))
                .limit(max)
                .toList();
    }

    /**
     * {@link #nearby(WeblogEntry, List, int, double)} over two sets of
     * candidates: the blog's own posts come first, nearest first, and other
     * blogs' posts fill only the places those leave, nearest first. So a
     * neighbouring blog's post never displaces one of the blog's own,
     * however close it is.
     */
    public static List<Near> nearby(WeblogEntry current, List<WeblogEntry> own,
            List<WeblogEntry> others, int max, double maxKm) {
        List<Near> ownNear = nearby(current, own, max, maxKm);
        if (ownNear.size() >= max || others.isEmpty()) {
            return ownNear;
        }
        List<Near> all = new ArrayList<>(ownNear);
        all.addAll(nearby(current, others, max - ownNear.size(), maxKm));
        return List.copyOf(all);
    }

    /**
     * The candidates most related to the current post: the highest score
     * first, the newest first among equals, at most {@code max}. A candidate
     * scoring nothing still ranks, after every one that scores, so the newest
     * posts fill the list on a blog with few tags and a post page never ends
     * without a way on.
     *
     * @param sharedTags how many of the current post's tags each entry id
     *                   carries; an absent id shares none
     * @param exclude    entry ids already shown elsewhere on the page
     */
    public static List<WeblogEntry> related(WeblogEntry current, List<WeblogEntry> candidates,
            Map<String, Long> sharedTags, Set<String> exclude, int max) {
        Comparator<WeblogEntry> byScore =
                Comparator.comparingLong(candidate -> score(current, candidate, sharedTags));
        Comparator<WeblogEntry> byNewest = Comparator.comparing(WeblogEntry::getPubTime,
                Comparator.nullsFirst(Comparator.naturalOrder()));
        return candidates.stream()
                .filter(candidate -> !isSame(current, candidate) && !exclude.contains(candidate.getId()))
                .sorted(byScore.thenComparing(byNewest).reversed())
                .limit(max)
                .toList();
    }

    private static long score(WeblogEntry current, WeblogEntry candidate, Map<String, Long> sharedTags) {
        long score = TAG_POINTS * sharedTags.getOrDefault(candidate.getId(), 0L);
        if (sameCategory(current.getCategory(), candidate.getCategory())) {
            score += CATEGORY_POINTS;
        }
        return score;
    }

    private static boolean sameCategory(WeblogCategory one, WeblogCategory other) {
        return one != null && other != null && one.getName() != null
                && one.getName().equals(other.getName());
    }

    private static boolean isSame(WeblogEntry current, WeblogEntry candidate) {
        return Objects.equals(current.getId(), candidate.getId());
    }

    private static boolean hasPlace(WeblogEntry entry) {
        return entry.getGeoLatitude() != null && entry.getGeoLongitude() != null;
    }

    /** Great-circle distance (haversine). */
    private static double kilometres(WeblogEntry from, WeblogEntry to) {
        double fromLat = Math.toRadians(from.getGeoLatitude());
        double toLat = Math.toRadians(to.getGeoLatitude());
        double dLat = toLat - fromLat;
        double dLng = Math.toRadians(to.getGeoLongitude() - from.getGeoLongitude());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(fromLat) * Math.cos(toLat) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
