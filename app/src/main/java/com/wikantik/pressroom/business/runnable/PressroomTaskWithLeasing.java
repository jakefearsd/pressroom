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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;


/**
 * An abstract class representing a scheduled task in Pressroom that will always
 * attempt to acquire a lease before doing its work.
 */
public abstract class PressroomTaskWithLeasing extends PressroomTask {
    private static final Logger log = LoggerFactory.getLogger(PressroomTaskWithLeasing.class);
    protected static final int DEFAULT_LEASE_MINS = 30;
    
    /**
     * Run the task.
     */
    public abstract void runTask() throws WebloggerException;
    
    
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
            
            // always release Pressroom session
            weblogger().release();
        }
        
    }
    
}
