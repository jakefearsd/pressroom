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

package com.wikantik.pressroom.business.runnable;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;


/**
 * Hard-deletes weblog entries that have sat in the trash (see
 * {@link com.wikantik.pressroom.pojos.WeblogEntry.PubStatus#TRASHED})
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
 * {@code tasks.enabled} in {@code pressroom.properties} -- because this project
 * has exactly one way of running a scheduled task.
 */
public class TrashPurgeTask extends PressroomTaskWithLeasing {
    private static final Logger log = LoggerFactory.getLogger(TrashPurgeTask.class);

    public static final String NAME = "TrashPurgeTask";

    /** The runtime property read fresh on every sweep -- see {@link #runTask()}. */
    public static final String RETENTION_PROPERTY = "entry.trash.retention.days";

    /**
     * The page trash's own retention, read fresh on every sweep like the
     * entries'. A page is the more damaging thing to lose and the easier to
     * notice gone, so it ships shorter (7 days against 30).
     */
    public static final String PAGE_RETENTION_PROPERTY = "page.trash.retention.days";


    @Override
    protected StartWindow defaultStartWindow() {
        return StartWindow.START_OF_DAY;
    }


    public void init(Weblogger weblogger) throws WebloggerException {
        this.init(weblogger, NAME);
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
     * logged per weblog, and each weblog's purge is committed (or rolled
     * back) on its own; a single misbehaving weblog costs that weblog's purge
     * for this sweep, not every other weblog's. Until 2026-11-03 the sweep
     * committed once at the end, so a commit-time failure undid them all.
     */
    @Override
    public void runTask() {

        log.debug("task started");

        try {
            WeblogManager wmgr = weblogger().getWeblogManager();
            WeblogEntryManager wemgr = weblogger().getWeblogEntryManager();

            int retentionDays = WebloggerRuntimeConfig.getIntProperty(RETENTION_PROPERTY);

            List<Weblog> weblogs = wmgr.getWeblogs(true, null, null, null, 0, -1);
            log.debug("purging trash for {} weblogs at retention {} days",
                    weblogs.size(), retentionDays);

            for (Weblog weblog : weblogs) {
                try {
                    int purged = wemgr.purgeTrash(weblog, retentionDays);
                    // committed per weblog: one commit for the whole sweep let
                    // a single weblog failing at commit undo every other's
                    weblogger().flush();
                    if (purged > 0) {
                        log.info("purged {} trashed entries from weblog {}",
                                purged, weblog.getHandle());
                    }
                } catch (WebloggerException e) {
                    log.error("Error purging trash for weblog {}", weblog.getHandle(), e);
                } catch (Exception e) {
                    log.error("Unexpected exception purging trash for weblog {}",
                            weblog.getHandle(), e);
                } finally {
                    // a failed purge or commit must not leak into the next weblog's
                    weblogger().release();
                }
            }

            purgePages(wmgr, weblogs);
            sweepRevisions(wemgr);

        } catch (WebloggerException e) {
            log.error("Error running trash purge task", e);
        } catch (Exception e) {
            log.error("Unexpected exception running task", e);
        }

        log.debug("task completed");

    }

    /**
     * The revision sweep, last: the purges' own removals have taken their
     * revisions with them by then. It rides this task's daily schedule and
     * lease instead of a task of its own. The sweep reports its failures and
     * never throws; the catch is for whatever it did not foresee, so that
     * nothing here can take the task runner down.
     */
    private void sweepRevisions(WeblogEntryManager wemgr) {
        try {
            wemgr.sweepRevisions(false);
        } catch (RuntimeException e) {
            log.error("Unexpected exception sweeping revisions", e);
        }
    }

    /**
     * The page pass: every weblog's page trash past
     * {@code page.trash.retention.days}, the site space's included (site
     * pages are pages; it has no entries, which is why the entry pass never
     * sees it). Run after every entry purge is committed, with the entry
     * pass's discipline: one transaction per weblog, so one weblog's failure
     * costs that weblog's page purge for this sweep and nothing else.
     */
    private void purgePages(WeblogManager wmgr, List<Weblog> blogs) {
        // -1 when missing or not a number, which the page manager reads as
        // "keep forever": a limit that cannot be read removes nothing.
        int retentionDays = WebloggerRuntimeConfig.getIntProperty(PAGE_RETENTION_PROPERTY);
        List<Weblog> weblogs = new ArrayList<>(blogs);
        try {
            Weblog site = wmgr.getSiteSpace();
            if (site != null && !weblogs.contains(site)) {
                weblogs.add(site);
            }
        } catch (WebloggerException e) {
            log.error("Error reading the site space; its page trash is not purged this sweep", e);
        }
        for (Weblog weblog : weblogs) {
            try {
                int purged = weblogger().getWeblogPageManager().purgeTrash(weblog, retentionDays);
                weblogger().flush();
                if (purged > 0) {
                    log.info("purged {} trashed pages from weblog {}", purged, weblog.getHandle());
                }
            } catch (WebloggerException e) {
                log.error("Error purging the page trash of weblog {}", weblog.getHandle(), e);
            } catch (Exception e) {
                log.error("Unexpected exception purging the page trash of weblog {}",
                        weblog.getHandle(), e);
            } finally {
                weblogger().release();
            }
        }
    }


}
