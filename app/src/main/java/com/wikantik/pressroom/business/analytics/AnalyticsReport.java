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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One period's numbers for one website, with the immediately preceding
 * period of the same length for comparison.
 *
 * <p>{@code breakdowns} always has exactly the keys {@code sources, places,
 * devices, events, campaigns, speed}; a tab with nothing to show maps to an
 * empty list. {@code pathsCappedAt} is 0 when {@code paths} is every path the
 * period had, else the page size the service cut them at: the paths are then
 * the top ones only, and a path not among them is unknown, not zero.
 */
public record AnalyticsReport(Instant asOf, Summary current, Summary previous,
                              List<SeriesPoint> series, List<PathCount> paths,
                              Map<String, List<KeyCount>> breakdowns, int pathsCappedAt) {

    /** A report whose {@code paths} are every path the period had. */
    public AnalyticsReport(Instant asOf, Summary current, Summary previous, List<SeriesPoint> series,
                           List<PathCount> paths, Map<String, List<KeyCount>> breakdowns) {
        this(asOf, current, previous, series, paths, breakdowns, 0);
    }

    /** The headline numbers; visitors are distinct sessions and are never summed across anything. */
    public record Summary(long visitors, long visits, long views, long bounces, long totalTimeSeconds) {
    }

    /**
     * One bucket of the series, in the site time zone and zero-filled. For a
     * {@code 24h} period buckets are hours: {@code hour} is 0 to 23 on
     * {@code day}. For day periods {@code hour} is {@link #NO_HOUR}.
     */
    public record SeriesPoint(LocalDate day, int hour, long visitors, long views) {

        /** {@code hour} of a day bucket. */
        public static final int NO_HOUR = -1;

        /** A day bucket. */
        public SeriesPoint(LocalDate day, long visitors, long views) {
            this(day, NO_HOUR, visitors, views);
        }
    }

    /** A page and its numbers; {@code visitors} is -1 when the API cannot say. */
    public record PathCount(String path, long views, long visitors) {
    }

    /** One row of a breakdown. */
    public record KeyCount(String key, long value) {
    }
}
