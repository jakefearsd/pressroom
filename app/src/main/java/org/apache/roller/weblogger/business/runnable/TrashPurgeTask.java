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

package org.apache.roller.weblogger.business.runnable;

import java.util.Date;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.Weblog;


/**
 * Hard-deletes weblog entries that have sat in the trash (see
 * {@link org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus#TRASHED})
 * longer than the configured retention.
 *
 * <p>Retention lives in the runtime property {@code entry.trash.retention.days}
 * (read fresh on every sweep -- see below), and is interpreted exactly as
 * {@link WeblogEntryManager#purgeTrash(Weblog, int)} interprets it: -1 keeps
 * trash forever and purges nothing, 0 purges everything currently trashed,
 * and a positive n purges anything trashed more than n days ago.
 *
 * <p>Modeled directly on {@link ScheduledEntriesTask} -- same base class,
 * same {@code tasks.<name>.*} configuration shape, same registration via
 * {@code tasks.enabled} in {@code roller.properties} -- because this project
 * has exactly one way of running a scheduled task.
 */
public class TrashPurgeTask extends RollerTaskWithLeasing {
    private static final Logger log = LoggerFactory.getLogger(TrashPurgeTask.class);

    public static final String NAME = "TrashPurgeTask";

    /** The runtime property read fresh on every sweep -- see {@link #runTask()}. */
    public static final String RETENTION_PROPERTY = "entry.trash.retention.days";


    // a unique id for this specific task instance
    // this is meant to be unique for each client in a clustered environment
    private String clientId = null;

    // a String description of when to start this task
    private String startTimeDesc = "startOfDay";

    // interval at which the task is run, default is once per day.
    // volatile: written by init() on the bootstrap thread, read via
    // getInterval() from the scheduler thread TaskScheduler runs on.
    private volatile int interval = DEFAULT_INTERVAL_MINS;

    // lease time given to task lock, default is 30 minutes.
    // volatile: same cross-thread shape as interval above.
    private volatile int leaseTime = DEFAULT_LEASE_MINS;


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
        return this.interval;
    }

    @Override
    public int getLeaseTime() {
        return this.leaseTime;
    }


    // Qualified deliberately, not shortened: this class is modeled directly
    // on ScheduledEntriesTask (see class javadoc) and the two init() bodies
    // are otherwise byte-identical boilerplate inherited from
    // RollerTaskWithLeasing. Shortening this call to the bare "NAME" the
    // other PMD.UnnecessaryFullyQualifiedName sites in this file were
    // shortened to would make this method text-identical to
    // ScheduledEntriesTask's, extending an already-large shared-boilerplate
    // span past CPD's 110-token minimum. Keeping one side qualified breaks
    // that match without reintroducing behavior risk.
    //
    // This is a scaffold, not the answer, and it has a scheduled end: the
    // "Follow-up (not this wave)" section of
    // docs/superpowers/specs/2026-08-18-static-analysis-quality-gates-design.md
    // (search that file for "ScheduledEntriesTask") records the honest fix as
    // hoisting the shared getters and init(String) parsing up into
    // RollerTaskWithLeasing, which removes the duplication these two classes
    // carry at its source and deletes this suppression along with it.
    @SuppressWarnings("PMD.UnnecessaryFullyQualifiedName")
    public void init(Weblogger weblogger) throws WebloggerException {
    // CPD-OFF -- The two scheduled tasks share their configuration-reading
    // shape: same properties, same defaults, same parse-and-validate order.
    // Collapsing it means a shared base for tasks that otherwise have nothing
    // in common, and the shape is what a third task would be copied from
    // anyway. Left as a known duplicate rather than an unexamined one.
        this.init(weblogger, TrashPurgeTask.NAME);
    }

    @Override
    public void init(Weblogger weblogger, String name) throws WebloggerException {
        super.init(weblogger, name);

        // get relevant props
        Properties props = this.getTaskProperties();

        // extract clientId
        String client = props.getProperty("clientId");
        if (client != null) {
            this.clientId = client;
        }

        // extract start time
        String startTimeStr = props.getProperty("startTime");
        if (startTimeStr != null) {
            this.startTimeDesc = startTimeStr;
        }

        // extract interval
        String intervalStr = props.getProperty("interval");
        if (intervalStr != null) {
            try {
                this.interval = Integer.parseInt(intervalStr);
            } catch (NumberFormatException ex) {
                log.warn("Invalid interval: {}", intervalStr);
            }
        }

        // extract lease time
        String leaseTimeStr = props.getProperty("leaseTime");
        if (leaseTimeStr != null) {
            try {
                this.leaseTime = Integer.parseInt(leaseTimeStr);
            } catch (NumberFormatException ex) {
                log.warn("Invalid leaseTime: {}", leaseTimeStr);
            }
        }
    }


    /**
     * Execute the task: purge each weblog's trash past its retention window.
     *
     * <p>The retention value is read fresh here, on every sweep, rather than
     * cached on the instance in {@link #init} -- an admin changing
     * {@code entry.trash.retention.days} on the Admin Settings page must take
     * effect on the very next sweep, with no restart.
     *
     * <p>One weblog's purge failing must not stop the others', and must not
     * take the task runner down -- this runs unattended off the scheduler,
     * with nobody watching to retry it. Failures are therefore caught and
     * logged per weblog, the same granularity {@link ScheduledEntriesTask}
     * uses for the sweep as a whole; a single misbehaving weblog costs that
     * weblog's purge for this sweep, not every other weblog's.
     */
    @Override
    public void runTask() {

        log.debug("task started");

        try {
    // CPD-ON
            WeblogManager wmgr = weblogger().getWeblogManager();
            WeblogEntryManager wemgr = weblogger().getWeblogEntryManager();

            int retentionDays = WebloggerRuntimeConfig.getIntProperty(RETENTION_PROPERTY);

            List<Weblog> weblogs = wmgr.getWeblogs(true, null, null, null, 0, -1);
            log.debug("purging trash for {} weblogs at retention {} days",
                    weblogs.size(), retentionDays);

            for (Weblog weblog : weblogs) {
                try {
                    int purged = wemgr.purgeTrash(weblog, retentionDays);
                    if (purged > 0) {
                        log.info("purged {} trashed entries from weblog {}",
                                purged, weblog.getHandle());
                    }
                } catch (WebloggerException e) {
                    log.error("Error purging trash for weblog {}", weblog.getHandle(), e);
                } catch (Exception e) {
                    log.error("Unexpected exception purging trash for weblog {}",
                            weblog.getHandle(), e);
                }
            }

            // commit the changes
            weblogger().flush();

        } catch (WebloggerException e) {
            log.error("Error running trash purge task", e);
        } catch (Exception e) {
            log.error("Unexpected exception running task", e);
        } finally {
            // always release
            weblogger().release();
        }

        log.debug("task completed");

    }


}
