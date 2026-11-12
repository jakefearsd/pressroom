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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;

/**
 * The post page's "Keep reading" and "Nearby" picks (spec 2026-11-11,
 * criteria 5 to 11). Pure: entities in, an ordered list out.
 */
class RelatedPickerTest {

    private static final WeblogCategory FARMS = category("Farms");
    private static final WeblogCategory MINES = category("Mines");

    private static WeblogCategory category(String name) {
        WeblogCategory category = new WeblogCategory();
        category.setName(name);
        return category;
    }

    /** An entry published {@code day} days after a fixed date. */
    private static WeblogEntry entry(String id, WeblogCategory category, int day) {
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        entry.setCategory(category);
        entry.setPubTime(new Timestamp(1_700_000_000_000L + day * 86_400_000L));
        return entry;
    }

    private static WeblogEntry at(WeblogEntry entry, double lat, double lng) {
        entry.setGeoLatitude(lat);
        entry.setGeoLongitude(lng);
        return entry;
    }

    private static List<String> ids(List<WeblogEntry> entries) {
        return entries.stream().map(WeblogEntry::getId).toList();
    }

    @Test
    void relatedRanksSharedTagsAndCategoryThenNewest() {
        WeblogEntry current = entry("current", FARMS, 10);
        WeblogEntry sameCategory = entry("sameCategory", FARMS, 1);
        WeblogEntry twoTags = entry("twoTags", MINES, 2);
        WeblogEntry categoryAndTag = entry("categoryAndTag", FARMS, 3);
        WeblogEntry unrelatedNew = entry("unrelatedNew", MINES, 9);
        WeblogEntry oneTag = entry("oneTag", MINES, 4);

        List<WeblogEntry> picked = RelatedPicker.related(current,
                List.of(unrelatedNew, oneTag, categoryAndTag, twoTags, sameCategory, current),
                Map.of("twoTags", 2L, "categoryAndTag", 1L, "oneTag", 1L, "current", 5L),
                Set.of(), 4);

        // 5 (category + one tag), 4 (two tags), 3 (category), 2 (one tag)
        assertEquals(List.of("categoryAndTag", "twoTags", "sameCategory", "oneTag"), ids(picked));
    }

    @Test
    void relatedFillsWithTheNewestWhenTooFewScore() {
        WeblogEntry current = entry("current", FARMS, 10);
        WeblogEntry related = entry("related", FARMS, 1);
        WeblogEntry older = entry("older", MINES, 2);
        WeblogEntry newer = entry("newer", MINES, 8);

        List<WeblogEntry> picked = RelatedPicker.related(current,
                List.of(older, related, newer), Map.of(), Set.of(), 3);

        assertEquals(List.of("related", "newer", "older"), ids(picked));
    }

    @Test
    void relatedNeverReturnsThePostItselfOrAnExcludedPost() {
        WeblogEntry current = entry("current", FARMS, 10);
        WeblogEntry shownNearby = entry("shownNearby", FARMS, 9);
        WeblogEntry other = entry("other", MINES, 1);

        List<WeblogEntry> picked = RelatedPicker.related(current,
                List.of(current, shownNearby, other), Map.of(), Set.of("shownNearby"), 3);

        assertEquals(List.of("other"), ids(picked));
    }

    @Test
    void relatedIsEmptyWhenTheBlogHasNoOtherPost() {
        WeblogEntry current = entry("current", FARMS, 10);
        assertTrue(RelatedPicker.related(current, List.of(current), Map.of(), Set.of(), 3).isEmpty());
    }

    @Test
    void nearbyIsNearestFirstWithinRangeAndOnlyPostsWithCoordinates() {
        // Newcastle, CA; Auburn is about 9 km away, Grass Valley about 45 km,
        // San Diego about 730 km.
        WeblogEntry current = at(entry("newcastle", FARMS, 10), 38.874, -121.133);
        WeblogEntry auburn = at(entry("auburn", MINES, 1), 38.8966, -121.0769);
        WeblogEntry grassValley = at(entry("grassValley", MINES, 2), 39.2191, -121.0611);
        WeblogEntry sanDiego = at(entry("sanDiego", MINES, 3), 32.7157, -117.1611);
        WeblogEntry noPlace = entry("noPlace", FARMS, 4);
        WeblogEntry halfPlace = entry("halfPlace", FARMS, 5);
        halfPlace.setGeoLatitude(38.9);

        List<RelatedPicker.Near> picked = RelatedPicker.nearby(current,
                List.of(sanDiego, noPlace, grassValley, halfPlace, auburn, current), 3, 80.0);

        assertEquals(List.of("auburn", "grassValley"),
                picked.stream().map(near -> near.entry().getId()).toList());
        assertEquals(5.5, picked.get(0).km(), 1.0);
        assertEquals(38.9, picked.get(1).km(), 2.0);
    }

    @Test
    void nearbyKeepsOnlyTheNearestMax() {
        WeblogEntry current = at(entry("current", FARMS, 10), 52.52, 13.405);
        WeblogEntry a = at(entry("a", FARMS, 1), 52.53, 13.405);
        WeblogEntry b = at(entry("b", FARMS, 2), 52.54, 13.405);
        WeblogEntry c = at(entry("c", FARMS, 3), 52.55, 13.405);

        List<RelatedPicker.Near> picked = RelatedPicker.nearby(current, List.of(c, b, a), 2, 80.0);

        assertEquals(List.of("a", "b"), picked.stream().map(near -> near.entry().getId()).toList());
    }

    @Test
    void nearbyIsEmptyForAPostWithoutBothCoordinates() {
        WeblogEntry current = entry("current", FARMS, 10);
        current.setGeoLongitude(13.4);
        WeblogEntry a = at(entry("a", FARMS, 1), 52.53, 13.405);

        assertTrue(RelatedPicker.nearby(current, List.of(a), 3, 80.0).isEmpty());
    }

    // --------------------------------------------- other blogs fill (0.9.1)

    private static List<String> nearIds(List<RelatedPicker.Near> near) {
        return near.stream().map(n -> n.entry().getId()).toList();
    }

    /** 0.9.1 criterion 4: own posts first, then other blogs' nearest first. */
    @Test
    void otherBlogsFillThePlacesTheOwnBlogLeavesNearestFirst() {
        WeblogEntry current = at(entry("current", FARMS, 10), 38.874, -121.133);
        WeblogEntry ownAuburn = at(entry("ownAuburn", FARMS, 1), 38.8966, -121.0769);
        WeblogEntry otherNextDoor = at(entry("otherNextDoor", MINES, 2), 38.8745, -121.1335);
        WeblogEntry otherGrassValley = at(entry("otherGrassValley", MINES, 3), 39.2191, -121.0611);
        WeblogEntry otherSanDiego = at(entry("otherSanDiego", MINES, 4), 32.7157, -117.1611);
        WeblogEntry otherNoPlace = entry("otherNoPlace", MINES, 5);

        List<RelatedPicker.Near> near = RelatedPicker.nearby(current, List.of(ownAuburn, current),
                List.of(otherSanDiego, otherGrassValley, otherNoPlace, otherNextDoor), 3, 80);

        assertEquals(List.of("ownAuburn", "otherNextDoor", "otherGrassValley"), nearIds(near));
        assertTrue(near.get(1).km() < 1, "the distance comes with it: " + near.get(1).km());
    }

    @Test
    void otherBlogsNeverDisplaceAnOwnPostAndNeverPassTheMaximum() {
        WeblogEntry current = at(entry("current", FARMS, 10), 38.874, -121.133);
        List<WeblogEntry> own = List.of(
                at(entry("own1", FARMS, 1), 38.8966, -121.0769),
                at(entry("own2", FARMS, 2), 39.2191, -121.0611),
                at(entry("own3", FARMS, 3), 38.90, -121.07));
        List<WeblogEntry> others = List.of(
                at(entry("other1", MINES, 4), 38.8745, -121.1335),
                at(entry("other2", MINES, 5), 38.8746, -121.1336));

        assertEquals(Set.of("own1", "own2", "own3"),
                Set.copyOf(nearIds(RelatedPicker.nearby(current, own, others, 3, 80))),
                "a full own blog leaves no place, however near the others are");
        assertEquals(List.of("own1", "other1", "other2"),
                nearIds(RelatedPicker.nearby(current, own.subList(0, 1), others, 3, 80)));
        assertEquals(List.of("own1", "other1"),
                nearIds(RelatedPicker.nearby(current, own.subList(0, 1), others, 2, 80)));
        assertEquals(List.of(), RelatedPicker.nearby(entry("noPlace", FARMS, 1), own, others, 3, 80));
    }
}
