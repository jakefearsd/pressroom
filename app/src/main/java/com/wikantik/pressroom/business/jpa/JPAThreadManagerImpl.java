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

package com.wikantik.pressroom.business.jpa;

import java.sql.Timestamp;
import java.util.Date;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.util.DateUtil;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.runnable.ThreadManagerImpl;
import com.wikantik.pressroom.business.runnable.PressroomTask;
import com.wikantik.pressroom.pojos.TaskLock;


/**
 * JPA implementation of the TaskLockManager interface.
 *
 * This implementation extends the base ThreadManagerImpl class and provides
 * locking abilities which are managed through the database.
 */
public class JPAThreadManagerImpl extends ThreadManagerImpl {

    private static final Logger log = LoggerFactory.getLogger(JPAThreadManagerImpl.class);

    private final JPAPersistenceStrategy strategy;


    public JPAThreadManagerImpl(Weblogger weblogger, JPAPersistenceStrategy strat) {
        super(weblogger);

        log.debug("Instantiating JPA Thread Manager");

        this.strategy = strat;
    }


    /**
     * Try to aquire a lock for a given PressroomTask.
     */
    @Override
    public boolean registerLease(PressroomTask task) {
        
        log.debug("Attempting to register lease for task - {}", task.getName());
        
        // keep a copy of the current time
        Date currentTime = new Date();
        
        // query for existing lease record first
        TaskLock taskLock;
        try {
            taskLock = getTaskLockByName(task.getName());
            if(taskLock == null) {
                log.warn("Cannot acquire lease when no tasklock record exists for task - {}", task.getName());
            }
        } catch (WebloggerException ex) {
            log.warn("Error getting TaskLock", ex);
            return false;
        }

        // try to acquire lease
        if(taskLock != null) {
            try {
                // calculate lease expiration time
                Date leaseExpiration = taskLock.getLeaseExpiration();

                // calculate run time for task, this is expected time, not actual time
                // i.e. if a task is meant to run daily at midnight this should
                // reflect 00:00:00 on the current day
                Date runTime;
                if("startOfDay".equals(task.getStartTimeDesc())) {
                    // start of today
                    runTime = DateUtil.getStartOfDay(currentTime);
                } else if("startOfHour".equals(task.getStartTimeDesc())) {
                    // start of this hour
                    runTime = DateUtil.getStartOfHour(currentTime);
                } else {
                    // start of this minute
                    runTime = DateUtil.getStartOfMinute(currentTime);
                }

                log.debug("last run = {}", taskLock.getLastRun());
                log.debug("new run time = {}", runTime);
                log.debug("last acquired = {}", taskLock.getTimeAcquired());
                log.debug("time leased = {}", taskLock.getTimeLeased());
                log.debug("lease expiration = {}", leaseExpiration);

                Query q = strategy.getNamedUpdate(
                        "TaskLock.updateClient&Timeacquired&Timeleased&LastRunByName&Timeacquired");
                q.setParameter(1, task.getClientId());
                q.setParameter(2, new Timestamp(currentTime.getTime()));
                q.setParameter(3, task.getLeaseTime());
                q.setParameter(4, new Timestamp(runTime.getTime()));
                q.setParameter(5, task.getName());
                q.setParameter(6, taskLock.getTimeAcquired());
                q.setParameter(7, new Timestamp(leaseExpiration.getTime()));
                int result = q.executeUpdate();

                if(result == 1) {
                    strategy.flush();
                    return true;
                }

            } catch (Exception e) {
                // A failed lease is read as a lost race: the task does not run this tick and tries again next
                // minute. Right for a real race; during a database outage tasks are skipped, and this WARN is
                // the only trace.
                log.warn("Error obtaining lease, assuming race condition.", e);
                return false;
            }
        }

        return false;
    }


    /**
     * Try to release the lock for a given PressroomTask.
     */
    @Override
    public boolean unregisterLease(PressroomTask task) {

        // query for existing lease record first
        TaskLock taskLock;
        try {
            taskLock = this.getTaskLockByName(task.getName());

            if(taskLock == null) {
                return false;
            }

        } catch (WebloggerException ex) {
            if (log.isDebugEnabled()) {
                log.debug("Error getting TaskLock", ex);
            } else {
                log.warn("Error getting TaskLock, enable debug for more info");
            }
            return false;
        }

        // try to release lease, just set lease time to 0
        try {
            Query q = strategy.getNamedUpdate(
                    "TaskLock.updateTimeLeasedByName&Client");
            q.setParameter(1, 0);
            q.setParameter(2, task.getName());
            q.setParameter(3, task.getClientId());
            int result = q.executeUpdate();
            
            if(result == 1) {
                strategy.flush();
                return true;
            }

        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error releasing lease", e);
            } else {
                log.warn("Error releasing lease, enable debug for more info");
            }
            return false;
        }

        return false;

    }
    
    
    /**
     * @inheritDoc
     */
    @Override
    public TaskLock getTaskLockByName(String name) throws WebloggerException {
        // do lookup
        TypedQuery<TaskLock> q = strategy.getNamedQuery("TaskLock.getByName", TaskLock.class);
        q.setParameter(1, name);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    
    /**
     * @inheritDoc
     */
    @Override
    public void saveTaskLock(TaskLock data) throws WebloggerException {
        this.strategy.store(data);
    }

}
