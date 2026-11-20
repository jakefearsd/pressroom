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
package com.wikantik.pressroom.business.runnable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.business.discovery.DiscoverySettings;

/**
 * Rebuilds the front-page discovery snapshot on a schedule, under the
 * cluster lease so one node does it per interval.
 *
 * <p>Unlike its siblings the interval is not fixed at {@code init}: the
 * scheduler asks {@link #getInterval()} on every tick, and the answer is the
 * runtime setting {@code site.discovery.refreshSeconds} rounded up to whole
 * minutes, so a Global Config change takes effect without a restart. Advancing
 * the snapshot also advances the discovery generation, which is what expires
 * cached pages (see {@code RenderCaches}).
 */
public class PopularityRefreshTask extends PressroomTaskWithLeasing {
    private static final Logger log = LoggerFactory.getLogger(PopularityRefreshTask.class);

    public static final String NAME = "PopularityRefreshTask";

    private static final double SECONDS_PER_MINUTE = 60.0;

    /** Minutes between runs, read from the runtime setting on every call. */
    @Override
    public int getInterval() {
        return Math.max(1, (int) Math.ceil(DiscoverySettings.refreshSeconds() / SECONDS_PER_MINUTE));
    }

    @Override
    public void runTask() {
        log.debug("task started");
        try {
            weblogger().getDiscoveryManager().refresh();
        } catch (Exception e) {
            log.error("Unexpected exception running popularity refresh", e);
        }
        log.debug("task completed");
    }
}
