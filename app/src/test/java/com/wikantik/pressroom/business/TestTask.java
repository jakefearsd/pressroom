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
package com.wikantik.pressroom.business;

import java.util.Properties;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.runnable.PressroomTaskWithLeasing;

public class TestTask extends PressroomTaskWithLeasing {
    public static final String NAME = "TestTask";

    private final String clientId;
    private final String startTimeDesc;

    public TestTask() {
        this("TestTaskClientId", "immediate");
    }

    /** A task whose client id and start window are fixed regardless of the global config. */
    public TestTask(String clientId, String startTimeDesc) {
        this.clientId = clientId;
        this.startTimeDesc = startTimeDesc;
    }

    @Override
    protected Properties getTaskProperties() {
        Properties props = super.getTaskProperties();
        props.setProperty("clientId", clientId);
        props.setProperty("startTime", startTimeDesc);
        return props;
    }

    public void init(Weblogger weblogger) throws WebloggerException {
        this.init(weblogger, TestTask.NAME);
    }

    @Override
    protected int defaultLeaseMinutes() {
        return 300;
    }

    @Override
    protected int defaultInterval() {
        return 1800;
    }

    @Override
    public void runTask() {
    }

}
