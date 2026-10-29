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
package com.wikantik.pressroom.pojos;

import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two date calculations on {@link TaskLock}.
 *
 * <p>These decide whether a scheduled task may run and when another node may
 * steal its lease. Both are measured in minutes, and both have a "never yet"
 * case that has to resolve to the distant past rather than to now -- otherwise
 * a task that has never run would be told to wait, and a lock that was never
 * acquired would look permanently held.
 */
class TaskLockLeaseTest {

    /**
     * A plain {@link Date}, not a {@code Timestamp}: {@code Timestamp.equals}
     * is asymmetric and refuses a {@code Date}, which would make every
     * assertion below fail for the wrong reason.
     */
    private static Date at(String yyyyMmDdHhMm) {
        return new Date(java.sql.Timestamp.valueOf(yyyyMmDdHhMm + ":00").getTime());
    }

    @Test
    void aTaskThatHasNeverRunIsAllowedToRunImmediately() {
        TaskLock lock = new TaskLock();
        lock.setName("ScheduledEntriesTask");
        lock.setLastRun(null);

        assertEquals(new Date(0), lock.getNextAllowedRun(30),
                "With no previous run the next allowed run is the epoch, i.e. 'now'. "
                        + "Returning the current time plus the interval would make a "
                        + "freshly deployed server wait a full interval before its first run.");
    }

    @Test
    void theNextAllowedRunIsTheLastRunPlusTheIntervalInMinutes() {
        TaskLock lock = new TaskLock();
        lock.setName("ScheduledEntriesTask");
        lock.setLastRun(at("2024-03-09 10:00"));

        assertEquals(at("2024-03-09 10:30"), lock.getNextAllowedRun(30),
                "The interval is in minutes; interpreting it as hours or seconds changes "
                        + "how often every scheduled task in Roller fires");
        assertEquals(at("2024-03-09 09:30"), lock.getNextAllowedRun(-30),
                "A negative interval moves the window backwards, which is how a task is "
                        + "forced to run again");
    }

    @Test
    void aLockThatWasNeverAcquiredExpiredAtTheEpoch() {
        TaskLock lock = new TaskLock();
        lock.setName("OtherTask");
        lock.setTimeAcquired(null);
        lock.setTimeLeased(5);

        Date expiration = lock.getLeaseExpiration();

        assertTrue(expiration.before(new Date()),
                "A lock nobody has taken must already be expired so the first node to "
                        + "ask can take it; a future expiry would deadlock the scheduler. "
                        + "Got: " + expiration);
        assertEquals(TimeUnit.MINUTES.toMillis(5), expiration.getTime(),
                "The expiry is measured from the epoch when there is no acquisition time");
    }

    @Test
    void theLeaseExpiresTheLeaseTimeAfterAcquisition() {
        TaskLock lock = new TaskLock();
        lock.setName("OtherTask");
        lock.setTimeAcquired(at("2024-03-09 10:00"));
        lock.setTimeLeased(90);

        assertEquals(at("2024-03-09 11:30"), lock.getLeaseExpiration(),
                "The lease time is in minutes. Reading it as hours would let one node "
                        + "hold a scheduled task for far longer than intended, and reading "
                        + "it as seconds would let two nodes run the same task at once.");
    }

    @Test
    void aZeroLengthLeaseExpiresTheInstantItIsTaken() {
        TaskLock lock = new TaskLock();
        lock.setName("OtherTask");
        lock.setTimeAcquired(at("2024-03-09 10:00"));
        lock.setTimeLeased(0);

        assertEquals(at("2024-03-09 10:00"), lock.getLeaseExpiration(),
                "A zero lease is already expired rather than being treated as 'forever'");
    }
}
