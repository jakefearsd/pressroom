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

import java.util.Properties;

/**
 * The scheduling settings every leased task reads from its
 * {@code tasks.<name>.*} properties, parsed once.
 *
 * @param clientId the cluster member id ({@code null} when none is configured)
 * @param startTimeDesc the raw {@code startTime} value, kept for admin display
 * @param startWindow {@code startTimeDesc} parsed
 * @param interval minutes between runs
 * @param leaseTime minutes the task leases its lock for
 */
public record TaskSettings(String clientId, String startTimeDesc, StartWindow startWindow,
        int interval, int leaseTime) {

    /** The settings a task has before (or without) any properties. */
    public static TaskSettings of(String clientId, StartWindow window, int interval, int leaseTime) {
        return new TaskSettings(clientId, window.description(), window, interval, leaseTime);
    }

    /** These defaults with whatever {@code props} sets; invalid numbers keep the default. */
    public TaskSettings overriddenBy(Properties props) {
        String desc = props.getProperty("startTime", startTimeDesc);
        return new TaskSettings(
                props.getProperty("clientId", clientId),
                desc,
                StartWindow.parse(desc),
                PressroomTaskWithLeasing.positiveMinutes(props, "interval", interval),
                PressroomTaskWithLeasing.positiveMinutes(props, "leaseTime", leaseTime));
    }
}
