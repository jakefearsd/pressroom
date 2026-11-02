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
 * serialised with the manager's own background syncs, and skipped when one is
 * already running.
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
        order.verify(analytics).trySync();
        order.verify(weblogger).release();
        order.verify(weblogger.getThreadManager()).unregisterLease(any());
        verify(analytics, never()).sync();

        AnalyticsManager refused = mock(AnalyticsManager.class);
        task(leasing(refused, false)).run();
        verify(refused, never()).trySync();
        verify(refused, never()).sync();
    }

    /**
     * A slow Umami can make a sync outlast the lease. A tick that finds a sync
     * already running skips rather than parking another pool thread behind it
     * (each such thread would wait for the lock and pile up); the requested
     * sync's change is not lost, since that sync is the one running.
     */
    @Test
    void aTickWhileASyncRunsSkipsInsteadOfWaiting() throws Exception {
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
            scheduled.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(scheduled.isAlive(), "the tick returned at once: " + scheduled.getState());
            assertFalse(tier.service.callers.contains(scheduled), "the skipped tick asked the service nothing");
            assertFalse(tier.manager.trySync(), "while a sync runs, trySync declines");

            tier.service.release();
            tier.awaitQuiet();
            assertEquals(1, tier.service.maxInFlight.get(), "never two syncs at once");
            assertTrue(tier.manager.trySync(), "with none running, it runs");
            TestUtils.endSession(false);
        } finally {
            tier.tearDown();
            TestUtils.teardownUser(user.getUserName());
            TestUtils.endSession(true);
        }
    }

    /** The requested-sync path still queues behind a running sync rather than skipping. */
    @Test
    void aRequestedSyncWaitsForTheRunningOneInsteadOfSkipping() throws Exception {
        AnalyticsTier tier = new AnalyticsTier();
        User user = TestUtils.setupUser("analyticsqueue");
        TestUtils.endSession(true);
        try {
            String blog = tier.addWeblogUnseen("taskqueue", user).getId();
            tier.service.hang = new CountDownLatch(1);
            Thread running = new Thread(() -> {
                tier.manager.sync();
                tier.weblogger.release();
            }, "analytics-running-sync");
            running.start();
            awaitCalls(tier, 1);

            tier.manager.requestSync();
            tier.service.release();
            running.join(TimeUnit.SECONDS.toMillis(30));
            tier.awaitQuiet();

            long reads = tier.service.reads.stream().filter(r -> blog.equals(r.websiteId())).count();
            assertEquals(2L * tier.manager.periodKeys().size(), reads,
                    "the running sync read the blog, then the requested one did again");
        } finally {
            tier.tearDown();
            TestUtils.teardownUser(user.getUserName());
            TestUtils.endSession(true);
        }
    }

    @Test
    void aFailingSyncStillReleasesAndDoesNotPropagate() throws Exception {
        AnalyticsManager analytics = mock(AnalyticsManager.class);
        doThrow(new IllegalStateException("boom")).when(analytics).trySync();
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
}
