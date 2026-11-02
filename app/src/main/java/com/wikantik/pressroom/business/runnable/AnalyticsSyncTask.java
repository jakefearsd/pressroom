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

import java.util.Date;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;

/**
 * Reads every registered blog's traffic into the local snapshots on a fixed
 * interval ({@code tasks.AnalyticsSyncTask.interval}, 15 minutes), under the
 * cluster lease so one node does it per interval.
 *
 * <p>It calls {@code AnalyticsManager.trySync()}, which reconciles first and
 * then reads, under the same lock as the manager's own background syncs (the
 * ones a blog save or "Sync now" requests), so a scheduled run and a
 * requested one never write one snapshot at once. When a sync is already
 * running (a slow Umami can make one outlast the interval) the tick is
 * skipped rather than parking another pool thread on the lock; the running
 * sync is doing the work. With no analytics service configured it returns at
 * once.
 */
public class AnalyticsSyncTask extends PressroomTaskWithLeasing {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsSyncTask.class);

    public static final String NAME = "AnalyticsSyncTask";

    private static final int DEFAULT_INTERVAL = 15;
    private static final int DEFAULT_LEASE = 10;

    private String clientId;
    private String startTimeDesc = "immediate";
    // volatile: written by init() on the bootstrap thread, read by the scheduler's
    private volatile int interval = DEFAULT_INTERVAL;
    private volatile int leaseTime = DEFAULT_LEASE;

    @Override
    public String getClientId() {
        return clientId;
    }

    @Override
    public Date getStartTime(Date currentTime) {
        return getAdjustedTime(currentTime, startTimeDesc);
    }

    @Override
    public String getStartTimeDesc() {
        return startTimeDesc;
    }

    @Override
    public int getInterval() {
        return interval;
    }

    @Override
    public int getLeaseTime() {
        return leaseTime;
    }

    @Override
    public void init(Weblogger weblogger, String name) throws WebloggerException {
        super.init(weblogger, name);
        Properties props = getTaskProperties();
        clientId = props.getProperty("clientId", clientId);
        startTimeDesc = props.getProperty("startTime", startTimeDesc);
        interval = minutes(props, "interval", interval);
        leaseTime = minutes(props, "leaseTime", leaseTime);
    }

    /** A whole number of minutes from the task's properties; the default when absent or not a positive number. */
    private static int minutes(Properties props, String key, int fallback) {
        String value = props.getProperty(key);
        if (value == null) {
            return fallback;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            parsed = 0;
        }
        if (parsed > 0) {
            return parsed;
        }
        log.warn("Invalid {}: {}", key, value);
        return fallback;
    }

    @Override
    public void runTask() {
        log.debug("task started");
        try {
            if (!weblogger().getAnalyticsManager().trySync()) {
                log.debug("an analytics sync is already running; tick skipped");
            }
        } catch (RuntimeException e) {
            log.error("Unexpected exception running the analytics sync", e);
        } finally {
            weblogger().release();
        }
        log.debug("task completed");
    }
}
