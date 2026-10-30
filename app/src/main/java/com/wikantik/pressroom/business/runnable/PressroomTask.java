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
import java.util.Enumeration;
import java.util.Properties;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.util.DateUtil;


/**
 * An abstract class representing a scheduled task in Pressroom.
 *
 * This class extends the java.util.TimerTask class and adds in some Pressroom
 * specifics.
 */
public abstract class PressroomTask implements Runnable {
    private String taskName = null;
    private Weblogger weblogger = null;
    protected static final int DEFAULT_INTERVAL_MINS = 1440;

    
    /**
     * Initialization. Run once before the task is started.
     *
     * <p>The {@link Weblogger} arrives here, not through a static locator:
     * tasks are instantiated reflectively from {@code tasks.<name>.class}, so
     * this hook is the only seam a collaborator can enter through.
     *
     * @param weblogger the business tier this task works against
     * @param name the task's registered name ({@code tasks.<name>.*})
     */
    public void init(Weblogger weblogger, String name) throws WebloggerException {
        this.weblogger = weblogger;
        this.taskName = name;
    }


    /**
     * The business tier this task was initialised with.
     *
     * @throws IllegalStateException if {@link #init(Weblogger, String)} has
     *         not run -- a task must never reach the tier before it is told
     *         which one it belongs to
     */
    protected final Weblogger weblogger() {
        if (weblogger == null) {
            throw new IllegalStateException(
                    "Task " + getClass().getSimpleName() + " used before init(weblogger, name)");
        }
        return weblogger;
    }


    /**
     * Get the unique name for this task.
     *
     * @return The unique name for this task.
     */
    public final String getName() {
        return taskName;
    }
    
    
    /**
     * Get the unique id representing a specific instance of a task.  This is
     * important for tasks being run in a clustered environment so that a 
     * lease can be associated with a single cluster member.
     *
     * @return The unique client identifier for this task instance.
     */
    public abstract String getClientId();
    
    
    /**
     * When should this task be started?  The task is given the current time
     * so that it may determine a start time relative to the current time, 
     * such as the end of the day or hour.
     *
     * It is acceptable to return the currentTime object passed in or any other
     * time after it.  If the return value is before currentTime then it will
     * be ignored and the task will be started at currentTime.
     *
     * @param currentTime The current time.
     * @return The Date when this task should be started.
     */
    public abstract Date getStartTime(Date currentTime);
    
    
    /**
     * Get a string description of the start time of the given task.
     * 
     * Should be one of ... 'immediate', 'startOfDay', 'startOfHour'
     * 
     * @return The start time description.
     */
    public abstract String getStartTimeDesc();
    
    
    /**
     * How often should the task run, in minutes.
     *
     * example: 60 means this task runs once every hour.
     *
     * @return The interval the task should be run at, in minutes.
     */
    public abstract int getInterval();
    
    
    /**
     * Get the time, in minutes, this task wants to be leased for.
     *
     * example: 5 means the task is allowed 5 minutes to run.
     *
     * @return The time this task should lease its lock for, in minutes.
     */
    public abstract int getLeaseTime();
    
    
    /**
     * Get the properties from WebloggerConfig which pertain to this task.
     * 
     * This extracts all properties from the WebloggerConfig of the type
     * task.<taskname>.<prop>=value and returns them in a properties object
     * where each item is keyed by <prop>.
     */
    protected Properties getTaskProperties() {
        
        String prefix = "tasks."+this.getName()+".";
        
        Properties taskProps = new Properties();
        
        String key;
        Enumeration<Object> keys = WebloggerConfig.keys();
        while(keys.hasMoreElements()) {
            key = (String) keys.nextElement();
            
            if(key.startsWith(prefix)) {
                taskProps.setProperty(key.substring(prefix.length()), 
                        WebloggerConfig.getProperty(key));
            }
        }
        
        // special addition for clientId property that applies to all tasks
        taskProps.setProperty("clientId", WebloggerConfig.getProperty("tasks.clientId"));
        
        return taskProps;
    }
    
    
    /**
     * A convenience method for calculating an adjusted time given an initial
     * Date to work from and a "changeFactor" which describes how the time 
     * should be adjusted.
     *
     * Allowed change factors are ...
     *   'immediate' - no change
     *   'startOfHour' - top of the hour, beginning with next hour
     *   'startOfDay' - midnight, beginning on the next day
     */
    protected Date getAdjustedTime(Date startTime, String changeFactor) {
        
        if(startTime == null || changeFactor == null) {
            return startTime;
        }
        
        Date adjustedTime = startTime;
        
        if("startOfDay".equals(changeFactor)) {
            adjustedTime = DateUtil.getEndOfDay(startTime);
        } else if("startOfHour".equals(changeFactor)) {
            adjustedTime = DateUtil.getEndOfHour(startTime);
        }
        
        return adjustedTime;
    }
    
}
