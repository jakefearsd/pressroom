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
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.InitializationException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.TaskLock;
import com.wikantik.pressroom.util.Reflection;

import static com.wikantik.pressroom.util.PressroomConstants.GRACEFUL_SHUTDOWN_WAIT_IN_MILLISECONDS;
import static com.wikantik.pressroom.util.PressroomConstants.GRACEFUL_SHUTDOWN_WAIT_IN_SECONDS;

/**
 * Manage Roller's thread use.
 */
public abstract class ThreadManagerImpl implements ThreadManager {
    
    private static final Logger log = LoggerFactory.getLogger(ThreadManagerImpl.class);
    
    // our own scheduler thread
    private Thread schedulerThread = null;
    
    // a simple thread executor
    private final ExecutorService serviceScheduler;

    // the business tier handed to every task at init() and to the scheduler;
    // a @Lazy proxy in production (see WebloggerBeanConfig), resolved on first use
    private final Weblogger weblogger;
    
    
    public ThreadManagerImpl(Weblogger weblogger) {
        
        log.info("Instantiating Thread Manager");
        
        this.weblogger = weblogger;
        serviceScheduler = Executors.newCachedThreadPool();
    }
    
    
    @Override
    public void initialize() throws InitializationException {
                    
        // initialize tasks, making sure that each task has a tasklock record in the db
        List<PressroomTask> webloggerTasks = new ArrayList<>();
        String tasksStr = WebloggerConfig.getProperty("tasks.enabled");
        String[] tasks = StringUtils.stripAll(StringUtils.split(tasksStr, ","));
        for ( String taskName : tasks ) {
            
            String taskClassName = WebloggerConfig.getProperty("tasks."+taskName+".class");
            if(taskClassName != null) {
                log.info("Initializing task: {}", taskName);
                
                try {
                    PressroomTask task = (PressroomTask) Reflection.newInstance(taskClassName);
                    task.init(weblogger, taskName);
                    
                    // make sure there is a tasklock record in the db
                    TaskLock taskLock = getTaskLockByName(task.getName());
                    if (taskLock == null) {
                        log.debug("Task record does not exist, inserting empty record to start with");

                        // insert an empty record
                        taskLock = new TaskLock();
                        taskLock.setName(task.getName());
                        taskLock.setLastRun(new Date(0));
                        taskLock.setTimeAcquired(new Date(0));
                        taskLock.setTimeLeased(0);

                        // save it
                        this.saveTaskLock(taskLock);
                    }
                    
                    // add it to the list of configured tasks
                    webloggerTasks.add(task);
                    
                } catch (ClassCastException ex) {
                    log.warn("Task does not extend PressroomTask class", ex);
                } catch (WebloggerException ex) {
                    log.error("Error scheduling task", ex);
                } catch (ReflectiveOperationException ex) {
                    log.error("Error instantiating task", ex);
                }
            }
        }
        
        // create scheduler
        TaskScheduler scheduler = new TaskScheduler(weblogger, webloggerTasks);
        
        // start scheduler thread, but only if it's not already running
        if (schedulerThread == null) {
            log.debug("Starting scheduler thread");
            schedulerThread = new Thread(scheduler, "Roller Weblogger Task Scheduler");
            // set thread priority between MAX and NORM so we get slightly preferential treatment
            schedulerThread.setPriority((Thread.MAX_PRIORITY + Thread.NORM_PRIORITY)/2);
            schedulerThread.start();
        }
    }
    
    
    @Override
    public void executeInBackground(Runnable runnable)
            throws InterruptedException {
        // execute(), not submit(): the interface returns void, so nothing
        // has ever been able to consume the Future submit() would return --
        // this is fire-and-forget by design. submit() would silently
        // swallow any uncaught exception the runnable throws (most
        // IndexOperation.doRun() implementations only try/catch a narrow
        // WebloggerException section of their own work, not their whole
        // body); execute() instead lets it reach the pool thread's default
        // UncaughtExceptionHandler.
        serviceScheduler.execute(runnable);
    }
    
    
    @Override
    public void executeInForeground(Runnable runnable)
            throws InterruptedException {
        Future<?> task = serviceScheduler.submit(runnable);

        // Block until the work is done, and not a moment longer. This used to
        // poll isDone() with a half-second sleep, which made every search take
        // at least 500 ms however fast Lucene answered. What the work threw
        // stays here, as it always has: callers judge the outcome from the
        // operation itself (a search with no results set is a failed search).
        try {
            task.get();
        } catch (ExecutionException e) {
            log.error("Foreground operation {} failed", runnable.getClass().getName(), e.getCause());
        }
    }
    
    
    @Override
    public void shutdown() {
        
        log.debug("starting shutdown sequence");
        
        // trigger an immediate shutdown of any backgrounded tasks
        serviceScheduler.shutdownNow();
        try {
            serviceScheduler.awaitTermination(GRACEFUL_SHUTDOWN_WAIT_IN_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.debug(e.getMessage(), e);
        }

        // only stop if we are already running
        if(schedulerThread != null) {
            log.debug("Stopping scheduler");
            schedulerThread.interrupt();
            try {
                schedulerThread.join(GRACEFUL_SHUTDOWN_WAIT_IN_MILLISECONDS);
                log.debug("Scheduler was stopped successfully");
            } catch (InterruptedException e) {
                log.debug(e.getMessage(), e);
            }
        }
    }
    
    
    @Override
    public void release() {
        // no-op
    }
    
    
    /**
     * Default implementation of lease registration, always returns true.
     * 
     * Subclasses should override this method if they plan to run in an
     * environment that supports clustered deployments.
     */
    @Override
    public boolean registerLease(PressroomTask task) {
        return true;
    }
    
    
    /**
     * Default implementation of lease unregistration, always returns true.
     * 
     * Subclasses should override this method if they plan to run in an
     * environment that supports clustered deployments.
     */
    @Override
    public boolean unregisterLease(PressroomTask task) {
        return true;
    }
    
}
