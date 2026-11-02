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
package com.wikantik.pressroom.business.analytics;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.runnable.AnalyticsSyncTask;
import com.wikantik.pressroom.business.runnable.ThreadManager;
import com.wikantik.pressroom.config.StaticConfigOverride;
import com.wikantik.pressroom.pojos.User;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AnalyticsSyncTask}: the scheduled read, under the cluster lease,
 * serialised with the manager's own background syncs.
 */
class AnalyticsSyncTaskTest {

    private static AnalyticsSyncTask task(Weblogger weblogger) throws Exception {
        AnalyticsSyncTask task = new AnalyticsSyncTask();
        task.init(weblogger, AnalyticsSyncTask.NAME);
        return task;
    }

    private static Weblogger leasing(AnalyticsManager analytics, boolean leaseGranted) {
        Weblogger weblogger = mock(Weblogger.class);
        ThreadManager threads = mock(ThreadManager.class);
        when(threads.registerLease(any())).thenReturn(leaseGranted);
        when(threads.unregisterLease(any())).thenReturn(true);
        when(weblogger.getThreadManager()).thenReturn(threads);
        when(weblogger.getAnalyticsManager()).thenReturn(analytics);
        return weblogger;
    }

    @Test
    void taskRunsSyncUnderLease() throws Exception {
        AnalyticsManager analytics = mock(AnalyticsManager.class);
        Weblogger weblogger = leasing(analytics, true);
        task(weblogger).run();

        InOrder order = inOrder(weblogger.getThreadManager(), analytics, weblogger);
        order.verify(weblogger.getThreadManager()).registerLease(any());
        order.verify(analytics).sync();
        order.verify(weblogger).release();
        order.verify(weblogger.getThreadManager()).unregisterLease(any());

        AnalyticsManager refused = mock(AnalyticsManager.class);
        task(leasing(refused, false)).run();
        verify(refused, never()).sync();

        // and against the real manager: a sync the executor is running holds the
        // task's back, so two writers never fill one snapshot at once
        AnalyticsTier tier = new AnalyticsTier();
        User user = TestUtils.setupUser("analyticstask");
        TestUtils.endSession(true);
        try {
            tier.addWeblogUnseen("tasklease", user);
            tier.service.hang = new CountDownLatch(1);
            tier.manager.requestSync();
            awaitCalls(tier, 1);

            Thread scheduled = new Thread(() -> {
                try {
                    task(leasing(tier.manager, true)).run();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, "analytics-task-test");
            scheduled.start();
            awaitBlocked(scheduled);
            assertEquals(1, tier.service.maxInFlight.get(), "the task waits outside while the requested sync runs");

            tier.service.release();
            scheduled.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(scheduled.isAlive(), "the task ran once the requested sync was done");
            tier.awaitQuiet();
            assertEquals(1, tier.service.maxInFlight.get(), "the two syncs never overlapped");
            assertTrue(tier.service.callers.contains(scheduled), "the task's sync did run");
        } finally {
            tier.tearDown();
            TestUtils.teardownUser(user.getUserName());
            TestUtils.endSession(true);
        }
    }

    @Test
    void aFailingSyncStillReleasesAndDoesNotPropagate() throws Exception {
        AnalyticsManager analytics = mock(AnalyticsManager.class);
        doThrow(new IllegalStateException("boom")).when(analytics).sync();
        Weblogger weblogger = leasing(analytics, true);

        assertDoesNotThrow(() -> task(weblogger).runTask());
        verify(weblogger).release();
    }

    @Test
    void taskIsEnabledEveryFifteenMinutes() throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/com/wikantik/pressroom/config/pressroom.properties")) {
            p.load(in);
        }
        assertTrue(List.of(p.getProperty("tasks.enabled").split("\\s*,\\s*")).contains(AnalyticsSyncTask.NAME));
        assertEquals(AnalyticsSyncTask.class.getName(), p.getProperty("tasks.AnalyticsSyncTask.class"));
        assertEquals("immediate", p.getProperty("tasks.AnalyticsSyncTask.startTime"));
        assertEquals("15", p.getProperty("tasks.AnalyticsSyncTask.interval"));
        assertEquals("10", p.getProperty("tasks.AnalyticsSyncTask.leaseTime"));

        AnalyticsSyncTask task = task(mock(Weblogger.class));
        assertEquals(15, task.getInterval());
        assertEquals(10, task.getLeaseTime());
        assertEquals("immediate", task.getStartTimeDesc());
    }

    @Test
    void anUnusableIntervalOrLeaseKeepsTheDefault() throws Exception {
        try (var _ = StaticConfigOverride.set("tasks.AnalyticsSyncTask.interval", "soon");
             var _ = StaticConfigOverride.set("tasks.AnalyticsSyncTask.leaseTime", "0")) {
            AnalyticsSyncTask task = task(mock(Weblogger.class));
            assertEquals(15, task.getInterval());
            assertEquals(10, task.getLeaseTime());
        }
    }

    private static void awaitCalls(AnalyticsTier tier, int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (tier.service.calls.get() < n) {
            assertTrue(System.nanoTime() < deadline, "no sync reached the service");
            Thread.sleep(10);
        }
    }

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.BLOCKED) {
            assertTrue(System.nanoTime() < deadline, "the task never waited: " + thread.getState());
            Thread.sleep(10);
        }
    }
}
