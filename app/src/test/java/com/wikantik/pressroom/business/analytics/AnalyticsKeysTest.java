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
package com.wikantik.pressroom.business.analytics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The three analytics cache tables have composite keys mapped as JPA
 * {@code id-class}es (AnalyticsCache.orm.xml), looked up with a freshly built
 * key ({@code em.find(AnalyticsSummary.class, new Key(...))}). The JPA
 * contract for an id class is value equality over every key column: two keys
 * naming the same row are equal and hash alike, and a key differing in any
 * one column names another row.
 */
class AnalyticsKeysTest {

    /** Asserts value equality: {@code same} matches {@code key}; each of {@code others} differs in one column. */
    private static void assertValueKey(Object key, Object same, List<Object> others) {
        assertEquals(key, key);
        assertEquals(key, same);
        assertEquals(key.hashCode(), same.hashCode());
        Map<Object, String> rows = new HashMap<>();
        rows.put(key, "row");
        assertEquals("row", rows.get(same), "a fresh key finds the row");
        for (Object other : others) {
            assertNotEquals(key, other, "differs in one column: " + other);
            assertNotEquals(other, key);
        }
        assertNotEquals(key, "w1");
        assertNotEquals(null, key);
    }

    @Test
    void aSummaryKeyIsTheWeblogAndThePeriod() {
        AnalyticsSummary.Key key = new AnalyticsSummary.Key("w1", "30d");

        assertValueKey(key, new AnalyticsSummary.Key("w1", "30d"), List.of(
                new AnalyticsSummary.Key("w2", "30d"),
                new AnalyticsSummary.Key("w1", "7d"),
                new AnalyticsSummary.Key(null, "30d"),
                new AnalyticsSummary.Key()));
        assertEquals("w1", key.getWeblogId());
        assertEquals("30d", key.getPeriod());
        assertEquals(new AnalyticsSummary.Key(), new AnalyticsSummary.Key(), "JPA's empty key equals itself");
    }

    @Test
    void aBreakdownKeyIsTheWeblogThePeriodAndTheKind() {
        AnalyticsBreakdown.Key key = new AnalyticsBreakdown.Key("w1", "30d", "referrer");

        assertValueKey(key, new AnalyticsBreakdown.Key("w1", "30d", "referrer"), List.of(
                new AnalyticsBreakdown.Key("w2", "30d", "referrer"),
                new AnalyticsBreakdown.Key("w1", "7d", "referrer"),
                new AnalyticsBreakdown.Key("w1", "30d", "country"),
                new AnalyticsBreakdown.Key()));
        assertEquals(List.of("w1", "30d", "referrer"), List.of(key.getWeblogId(), key.getPeriod(), key.getKind()));
    }

    @Test
    void aPathViewsKeyIsTheWeblogThePeriodAndThePath() {
        AnalyticsPathViews.Key key = new AnalyticsPathViews.Key("w1", "30d", "/blog/entry/a");

        assertValueKey(key, new AnalyticsPathViews.Key("w1", "30d", "/blog/entry/a"), List.of(
                new AnalyticsPathViews.Key("w2", "30d", "/blog/entry/a"),
                new AnalyticsPathViews.Key("w1", "7d", "/blog/entry/a"),
                new AnalyticsPathViews.Key("w1", "30d", "/blog/entry/b"),
                new AnalyticsPathViews.Key()));
        assertEquals(List.of("w1", "30d", "/blog/entry/a"), List.of(key.getWeblogId(), key.getPeriod(), key.getPath()));
    }
}
