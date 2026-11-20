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

import java.util.Date;
import java.util.Properties;
import java.util.Calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;


/**
 * An abstract class representing a scheduled task in Pressroom that will always
 * attempt to acquire a lease before doing its work.
 */
public abstract class PressroomTaskWithLeasing extends PressroomTask {
    private static final Logger log = LoggerFactory.getLogger(PressroomTaskWithLeasing.class);
    protected static final int DEFAULT_LEASE_MINS = 30;
    
    // volatile: written by init() on the bootstrap thread, read by the scheduler's
    private volatile TaskSettings settings;

    /**
     * Run the task.
     */
    public abstract void runTask() throws WebloggerException;

    /** The default {@code interval}, in minutes; a task with another rhythm overrides this. */
    protected int defaultInterval() {
        return DEFAULT_INTERVAL_MINS;
    }

    /** The default {@code leaseTime}, in minutes. */
    protected int defaultLeaseMinutes() {
        return DEFAULT_LEASE_MINS;
    }

    /** The default {@code startTime} window. */
    protected StartWindow defaultStartWindow() {
        return StartWindow.IMMEDIATE;
    }

    /** The default {@code clientId}: none, until {@code tasks.clientId} supplies one. */
    protected String defaultClientId() {
        return null;
    }

    private TaskSettings defaults() {
        return TaskSettings.of(defaultClientId(), defaultStartWindow(), defaultInterval(), defaultLeaseMinutes());
    }

    /** Parses the task's properties once, over the subclass's defaults. */
    @Override
    public void init(Weblogger weblogger, String name) throws WebloggerException {
        super.init(weblogger, name);
        settings = defaults().overriddenBy(getTaskProperties());
    }

    private TaskSettings settings() {
        TaskSettings current = settings;
        return current != null ? current : defaults();
    }

    @Override
    public final String getClientId() {
        return settings().clientId();
    }

    @Override
    public final String getStartTimeDesc() {
        return settings().startTimeDesc();
    }

    @Override
    public final Date getStartTime(Date currentTime) {
        return settings().startWindow().firstRun(currentTime, Calendar.getInstance());
    }

    /** Minutes between runs; non-final only because a task may follow a runtime setting instead. */
    @Override
    public int getInterval() {
        return settings().interval();
    }

    @Override
    public final int getLeaseTime() {
        return settings().leaseTime();
    }
    
    
    /**
     * A whole number of minutes from the task's properties: the fallback when
     * the key is absent, unparsable or not positive (with a warning), so every
     * task validates its interval and lease time the same way.
     */
    static int positiveMinutes(Properties props, String key, int fallback) {
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

    /**
     * The run() method as called by our thread manager.
     *
     * This method is purposely defined as "final" so that any tasks that are
     * defined may not override it and remove any of its functionality.  It is
     * setup to provide some basic functionality to the running of all tasks,
     * such as lease acquisition and releasing.
     *
     * Pressroom tasks should put their logic in the runTask() method.
     */
    @Override
    public final void run() {
        
        ThreadManager mgr = weblogger().getThreadManager();
        
        boolean lockAcquired = false;
        try {
            log.debug("{}: Attempting to acquire lease", getName());

            lockAcquired = mgr.registerLease(this);

            // now if we have a lock then run the task
            if(lockAcquired) {
                log.debug("{}: Lease acquired, running task", getName());
                this.runTask();
            } else {
                log.debug("{}: Lease NOT acquired, cannot continue", getName());
            }

        } catch (Exception ex) {
            log.error("{}: Unexpected exception", getName(), ex);
        } finally {

            if(lockAcquired) {

                log.debug("{}: Attempting to release lease", getName());

                boolean lockReleased = mgr.unregisterLease(this);

                if(lockReleased) {
                    log.debug("{}: Lease released, task finished", getName());
                } else {
                    log.debug("{}: Lease NOT released, some kind of problem", getName());
                }
            }
            
            // the one release of the run's session; tasks do not repeat it in
            // runTask() (they release only between per-item transactions)
            weblogger().release();
        }
        
    }
    
}
