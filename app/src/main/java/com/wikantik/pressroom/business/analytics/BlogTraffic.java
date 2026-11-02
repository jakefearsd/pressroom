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
package com.wikantik.pressroom.business.analytics;

import com.wikantik.pressroom.business.analytics.AnalyticsReport.KeyCount;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.SeriesPoint;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.Summary;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One blog's traffic for one period, from the local snapshot only.
 *
 * <p>{@code current}, {@code previous} and {@code asOf} are null when there is
 * no snapshot ({@link TrafficState#NOT_CONFIGURED}, or {@link TrafficState#NO_DATA}
 * before the first read); the lists and map are then empty. {@code breakdowns}
 * otherwise has exactly the keys {@code sources, places, devices, events,
 * campaigns, speed}, in that order.
 *
 * @param state      what the page has to show, distinctly from zero
 * @param asOf       when the snapshot was read
 * @param current    the period's headline numbers
 * @param previous   the immediately preceding period of the same length
 * @param series     the period's buckets, zero-filled, in the site time zone
 * @param posts      one row per recorded path, most viewed first
 * @param breakdowns the tabs' rows, by tab
 * @param lastError  why this blog's last read (or registration) failed, or null
 */
public record BlogTraffic(TrafficState state, Instant asOf, Summary current, Summary previous,
                          List<SeriesPoint> series, List<PostRow> posts, Map<String, List<KeyCount>> breakdowns,
                          String lastError) {

    public BlogTraffic {
        series = series == null ? List.of() : List.copyOf(series);
        posts = posts == null ? List.of() : List.copyOf(posts);
        breakdowns = breakdowns == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(breakdowns));
    }

    /**
     * One recorded path. A path that is not an entry still counts toward the
     * blog's totals: it keeps its raw path, and {@code entryId} and
     * {@code title} are null.
     *
     * @param entryId  the entry the path names, or null
     * @param title    that entry's title as stored (entry titles are stored escaped), or null
     * @param path     the path as the tracker recorded it
     * @param views    page views in the period
     * @param visitors distinct visitors to this path in the period; -1 when the service cannot say
     * @param trend    this period's daily view rate against the next longer period's, minus one
     *                 (0 is steady, 1 is twice the usual rate); {@link Double#NaN} when no longer
     *                 period has the path to compare with
     */
    public record PostRow(String entryId, String title, String path, long views, long visitors, double trend) {
    }
}
