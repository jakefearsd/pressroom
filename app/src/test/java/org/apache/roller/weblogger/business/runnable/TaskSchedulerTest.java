/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.business.runnable;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.TaskLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TaskScheduler}'s launch decision: which tasks run on a given pass,
 * and that one task's failure does not stop the others. The clock is passed
 * in ({@code runTasks(Date)}), so no test waits for a minute boundary.
 *
 * <p>A task that must NOT run is proven by launching a sentinel after it and
 * waiting for the sentinel: the pool is a cached one, and both were submitted
 * by the same loop in order.
 */
class TaskSchedulerTest {

    private Weblogger weblogger;
    private ThreadManager threadManager;

    @BeforeEach
    void setUp() {
        weblogger = mock(Weblogger.class);
        threadManager = mock(ThreadManager.class);
        when(weblogger.getThreadManager()).thenReturn(threadManager);
    }

    /** A task whose run() only counts, with the schedule shape under test. */
    private static final class CountingTask extends RollerTask {
        private final String startDesc;
        private final int interval;
        private final CountDownLatch ran = new CountDownLatch(1);
        private final AtomicInteger runs = new AtomicInteger();

        CountingTask(Weblogger weblogger, String name, String startDesc, int interval)
                throws WebloggerException {
            this.startDesc = startDesc;
            this.interval = interval;
            init(weblogger, name);
        }

        @Override public String getClientId() { return "test"; }
        @Override public Date getStartTime(Date currentTime) { return currentTime; }
        @Override public String getStartTimeDesc() { return startDesc; }
        @Override public int getInterval() { return interval; }
        @Override public int getLeaseTime() { return 1; }
        @Override public void run() {
            runs.incrementAndGet();
            ran.countDown();
        }

        boolean launched() throws InterruptedException {
            return ran.await(5, TimeUnit.SECONDS);
        }
    }

    private static Date at(int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(2026, Calendar.MARCH, 10, hour, minute, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private static TaskLock lockLastRun(String name, Date lastRun) {
        TaskLock lock = new TaskLock();
        lock.setName(name);
        lock.setLastRun(lastRun);
        return lock;
    }

    /** Runs one pass over {@code tasks}, then a sentinel, and returns once the sentinel ran. */
    private void passWithSentinel(Date now, CountingTask... tasks) throws Exception {
        CountingTask sentinel = new CountingTask(weblogger, "sentinel", "immediate", 1);
        when(threadManager.getTaskLockByName("sentinel")).thenReturn(lockLastRun("sentinel", null));
        TaskScheduler scheduler = new TaskScheduler(weblogger,
                java.util.stream.Stream.concat(java.util.Arrays.stream(tasks),
                        java.util.stream.Stream.of(sentinel)).map(RollerTask.class::cast).toList());
        scheduler.runTasks(now);
        assertTrue(sentinel.launched(), "the sentinel after the task under test must run");
    }

    @Test
    void aTaskWithNoLockRecordIsSkippedAndTheNextTaskStillRuns() throws Exception {
        CountingTask unlocked = new CountingTask(weblogger, "unlocked", "immediate", 1);
        when(threadManager.getTaskLockByName("unlocked")).thenReturn(null);

        passWithSentinel(at(10, 30), unlocked);

        assertEquals(0, unlocked.runs.get(), "no tasklock row means the task was never registered");
    }

    @Test
    void anImmediateTaskThatIsDueIsLaunched() throws Exception {
        CountingTask due = new CountingTask(weblogger, "due", "immediate", 5);
        // last ran 30 minutes ago, interval 5: due, and "missed" but immediate tasks never wait
        when(threadManager.getTaskLockByName("due")).thenReturn(lockLastRun("due", at(10, 0)));

        passWithSentinel(at(10, 30), due);

        assertTrue(due.launched());
    }

    @Test
    void aTaskWhoseNextRunIsStillAheadIsNotLaunched() throws Exception {
        CountingTask early = new CountingTask(weblogger, "early", "immediate", 60);
        when(threadManager.getTaskLockByName("early")).thenReturn(lockLastRun("early", at(10, 0)));

        passWithSentinel(at(10, 30), early);

        assertEquals(0, early.runs.get(), "next allowed run is 11:00; 10:30 is too early");
    }

    private static Date daysBefore(Date from, int days) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(from);
        cal.add(Calendar.DAY_OF_MONTH, -days);
        return cal.getTime();
    }

    @Test
    void aMissedDailyTaskWaitsUnlessWithinTheFirstMinutesOfTheDay() throws Exception {
        CountingTask lateDaily = new CountingTask(weblogger, "lateDaily", "startOfDay", 1440);
        // last ran three days ago, so a slot was missed; at 10:30 a daily job waits for midnight
        when(threadManager.getTaskLockByName("lateDaily"))
                .thenReturn(lockLastRun("lateDaily", daysBefore(at(0, 0), 3)));

        passWithSentinel(at(10, 30), lateDaily);

        assertEquals(0, lateDaily.runs.get());
    }

    @Test
    void aMissedDailyTaskRunsInTheFirstTwoMinutesOfTheDay() throws Exception {
        CountingTask daily = new CountingTask(weblogger, "daily", "startOfDay", 1440);
        when(threadManager.getTaskLockByName("daily"))
                .thenReturn(lockLastRun("daily", daysBefore(at(0, 0), 3)));

        passWithSentinel(at(0, 1), daily);

        assertTrue(daily.launched());
    }

    @Test
    void aMissedHourlyTaskWaitsUnlessWithinTheFirstMinutesOfTheHour() throws Exception {
        CountingTask lateHourly = new CountingTask(weblogger, "lateHourly", "startOfHour", 60);
        when(threadManager.getTaskLockByName("lateHourly"))
                .thenReturn(lockLastRun("lateHourly", at(7, 0)));
        passWithSentinel(at(10, 30), lateHourly);
        assertEquals(0, lateHourly.runs.get(), "10:30 is past the first two minutes of the hour");
    }

    @Test
    void aMissedHourlyTaskRunsInTheFirstTwoMinutesOfTheHour() throws Exception {
        CountingTask hourly = new CountingTask(weblogger, "hourly", "startOfHour", 60);
        when(threadManager.getTaskLockByName("hourly")).thenReturn(lockLastRun("hourly", at(7, 0)));

        passWithSentinel(at(10, 1), hourly);

        assertTrue(hourly.launched());
    }

    @Test
    void aFailureLookingUpOneTaskDoesNotStopTheOthers() throws Exception {
        CountingTask broken = new CountingTask(weblogger, "broken", "immediate", 1);
        when(threadManager.getTaskLockByName("broken")).thenThrow(new WebloggerException("db down"));

        passWithSentinel(at(10, 30), broken);

        assertEquals(0, broken.runs.get());
    }

    @Test
    void interruptingTheSchedulerThreadStopsItWithoutRunningAnyTaskOnTheFirstPass() throws Exception {
        CountingTask task = new CountingTask(weblogger, "never", "immediate", 1);
        TaskScheduler scheduler = new TaskScheduler(weblogger, List.of(task));
        Thread thread = new Thread(scheduler, "test-scheduler");
        thread.start();
        thread.interrupt();
        thread.join(10_000);

        assertFalse(thread.isAlive(), "an interrupted scheduler must leave its loop");
        assertEquals(0, task.runs.get(), "the first pass only delays the start");
        verify(weblogger, never()).release();
    }
}
