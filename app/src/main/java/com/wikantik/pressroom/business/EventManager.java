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

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.PressroomEvent;
import com.wikantik.pressroom.pojos.Weblog;

/**
 * Interface to first-party event bookkeeping.
 *
 * <p>A {@link PressroomEvent} records an outcome the analytics tier cannot see
 * from traffic alone -- a form submitted, a newsletter subscription, an entry
 * published.
 *
 * <p><strong>Callers must treat {@link #record} as best-effort bookkeeping:</strong>
 * wrap it in a try/catch and log on failure. An event insert must never fail
 * the business operation that produced it. This manager stays simple and
 * throws on error; it is every call site's responsibility to decide that
 * policy.
 */
public interface EventManager {

    /**
     * Record an event.
     */
    void record(PressroomEvent event) throws WebloggerException;

    /**
     * The events belonging to a weblog, newest first, capped at {@code max}.
     */
    List<PressroomEvent> getEvents(Weblog weblog, int max) throws WebloggerException;

    /**
     * Remove all events belonging to a weblog.
     */
    void removeEvents(Weblog weblog) throws WebloggerException;
}
