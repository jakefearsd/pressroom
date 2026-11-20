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

import java.util.Calendar;
import java.util.Date;
import com.wikantik.pressroom.util.DateUtil;

/**
 * When in its day or hour a task starts: the parsed form of the
 * {@code tasks.<name>.startTime} property ({@code immediate}, {@code startOfDay},
 * {@code startOfHour}).
 *
 * <p>One zone rule for both consumers (the task's first run and the
 * scheduler's missed-window check): the window is computed in the zone of the
 * {@link Calendar} passed in. {@code TaskScheduler} passes its configured zone,
 * the JVM default when none is set, and that default is also the zone
 * {@code JPAThreadManagerImpl.registerLease} records a run's start of day in.
 * Nothing here reads a zone of its own.
 */
public enum StartWindow {
    /** No window: the task runs whenever it is due. */
    IMMEDIATE("immediate"),
    /** Midnight, beginning on the next day. */
    START_OF_DAY("startOfDay"),
    /** The top of the hour, beginning with the next hour. */
    START_OF_HOUR("startOfHour");

    private final String description;

    StartWindow(String description) {
        this.description = description;
    }

    /** The property value for this window, as {@code getStartTimeDesc()} reports it. */
    public String description() {
        return description;
    }

    /** The window a property value names; anything unrecognised is {@link #IMMEDIATE}. */
    public static StartWindow parse(String value) {
        for (StartWindow window : values()) {
            if (window.description.equals(value)) {
                return window;
            }
        }
        return IMMEDIATE;
    }

    /**
     * The first time a task given {@code start} may run: unchanged for
     * {@link #IMMEDIATE}, else the last instant of the current day or hour in
     * {@code cal}'s zone.
     */
    public Date firstRun(Date start, Calendar cal) {
        if (start == null) {
            return null;
        }
        return switch (this) {
            case IMMEDIATE -> start;
            case START_OF_DAY -> DateUtil.getEndOfDay(start, cal);
            case START_OF_HOUR -> DateUtil.getEndOfHour(start, cal);
        };
    }

    /**
     * The start of the day or hour containing {@code time} in {@code cal}'s
     * zone; {@code time} itself for {@link #IMMEDIATE}, which has no window.
     */
    public Date windowStart(Date time, Calendar cal) {
        return switch (this) {
            case IMMEDIATE -> time;
            case START_OF_DAY -> DateUtil.getStartOfDay(time, cal);
            case START_OF_HOUR -> DateUtil.getStartOfHour(time, cal);
        };
    }
}
