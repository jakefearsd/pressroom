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
import java.util.List;

/**
 * How analytics stands, for the Maintenance page.
 *
 * @param configured    whether an analytics service is configured at all
 * @param reachable     whether it answered the last sync; null before the first
 * @param registered    blogs (site space included) confirmed registered
 * @param total         blogs, site space included
 * @param failures      blogs whose last registration attempt failed
 * @param lastRead      when traffic was last read successfully, or null
 * @param lastReadError why the last read failed, or null
 */
public record AnalyticsStatus(boolean configured, Boolean reachable, int registered, int total,
                              List<SiteFailure> failures, Instant lastRead, String lastReadError) {

    public AnalyticsStatus {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /** Nothing configured: nothing registered, nothing read, nothing to report. */
    public static AnalyticsStatus notConfigured() {
        return new AnalyticsStatus(false, null, 0, 0, List.of(), null, null);
    }

    /**
     * One blog that is not where it should be.
     *
     * @param handle the blog's handle
     * @param error  what the last attempt answered
     * @param at     when that attempt was made
     */
    public record SiteFailure(String handle, String error, Instant at) {
    }
}
