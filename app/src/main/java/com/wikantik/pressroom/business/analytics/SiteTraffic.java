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
import com.wikantik.pressroom.business.analytics.BlogTraffic.PostRow;

import java.time.Instant;
import java.util.List;

/**
 * Every blog's traffic for one period, from the local snapshots only.
 *
 * <p>There is deliberately no visitor total (spec, AC 12): each blog is its
 * own website, the service has no cross-website count of distinct visitors,
 * and a sum would count a reader of two blogs twice. Views and visits add.
 *
 * @param state       NOT_CONFIGURED, NO_DATA when no blog has a snapshot, STALE when any blog's last read
 *                    failed, otherwise OK
 * @param asOf        the oldest snapshot's read time, or null when there is none
 * @param blogs       one row per blog, the site space included: those with a snapshot first, most
 *                    viewed first, then those without one ({@link BlogRow.Standing}), by handle
 * @param totalViews  views summed across the blogs
 * @param totalVisits visits summed across the blogs
 * @param topPosts    the most viewed entries across the blogs, most viewed first
 * @param topSources  sources across the blogs; a source several blogs share shows the largest single
 *                    blog's visitors, never their sum, so the value is a lower bound
 */
public record SiteTraffic(TrafficState state, Instant asOf, List<BlogRow> blogs, long totalViews,
                          long totalVisits, List<PostRow> topPosts, List<KeyCount> topSources) {

    public SiteTraffic {
        blogs = blogs == null ? List.of() : List.copyOf(blogs);
        topPosts = topPosts == null ? List.of() : List.copyOf(topPosts);
        topSources = topSources == null ? List.of() : List.copyOf(topSources);
    }

    /**
     * One blog's line.
     *
     * @param weblogId     the blog
     * @param handle       its handle
     * @param name         its name
     * @param visitors     that blog's distinct visitors in the period
     * @param views        that blog's views in the period
     * @param visits       that blog's visits in the period
     * @param change       views against the previous period, as a fraction (0.25 is a quarter more);
     *                     {@link Double#NaN} when the previous period had no views
     * @param topPostTitle the title, as stored, of the blog's most viewed entry, or null
     * @param standing     whether the blog has numbers for the period, and if not, why not
     */
    public record BlogRow(String weblogId, String handle, String name, long visitors, long views, long visits,
                          double change, String topPostTitle, Standing standing) {

        /** Whether a blog's line has numbers, and if not, why not. */
        public enum Standing {
            /** The blog has a snapshot for the period. */
            READ,
            /** No snapshot yet: registered (or not yet tried), and not read for this period. */
            NO_DATA,
            /** No snapshot, and the blog's registration with the service failed. */
            NOT_REGISTERED
        }

        /** A blog with a snapshot for the period. */
        public BlogRow(String weblogId, String handle, String name, long visitors, long views, long visits,
                       double change, String topPostTitle) {
            this(weblogId, handle, name, visitors, views, visits, change, topPostTitle, Standing.READ);
        }

        /** A blog with no snapshot for the period: no numbers, and never counted in a total. */
        public static BlogRow unread(String weblogId, String handle, String name, Standing standing) {
            return new BlogRow(weblogId, handle, name, 0, 0, 0, Double.NaN, null, standing);
        }
    }
}
