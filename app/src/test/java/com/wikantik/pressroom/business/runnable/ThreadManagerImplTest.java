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
package com.wikantik.pressroom.business.runnable;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.StaticConfigOverride;
import com.wikantik.pressroom.pojos.TaskLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link ThreadManagerImpl}: task registration at start-up (what is
 * configured, what is refused, what gets a lock row), the background and
 * foreground executors, and shutdown. Uses an in-memory subclass, so none of
 * it touches the database; the JPA lease queries are in
 * {@code JPAThreadManagerLeaseTest}.
 */
class ThreadManagerImplTest {

    /** Public with a public no-arg constructor: {@code Reflection.newInstance} needs both. */
    public static final class GoodTask extends PressroomTask {
        @Override public String getClientId() { return "good"; }
        @Override public Date getStartTime(Date currentTime) { return currentTime; }
        @Override public String getStartTimeDesc() { return "immediate"; }
        @Override public int getInterval() { return 1; }
        @Override public int getLeaseTime() { return 1; }
        @Override public void run() {
            // never launched: the scheduler's first pass only delays its start
        }
    }

    private static final class InMemoryThreadManager extends ThreadManagerImpl {
        final Map<String, TaskLock> locks = new HashMap<>();

        InMemoryThreadManager() {
            super(mock(Weblogger.class));
        }

        @Override public TaskLock getTaskLockByName(String name) {
            return locks.get(name);
        }

        @Override public void saveTaskLock(TaskLock data) throws WebloggerException {
            locks.put(data.getName(), data);
        }
    }

    private InMemoryThreadManager manager;

    @AfterEach
    void stop() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    @Test
    void initializeRegistersOnlyTasksThatAreValidAndGivesEachANeverRunLockRow() throws Exception {
        manager = new InMemoryThreadManager();
        TaskLock existing = new TaskLock();
        existing.setName("Kept");
        existing.setLastRun(new Date(12345));
        manager.locks.put("Kept", existing);

        try (var _ = StaticConfigOverride.set("tasks.enabled", "Fresh, Kept, NoClass, Absent, NotATask");
             var _ = StaticConfigOverride.set("tasks.Fresh.class", GoodTask.class.getName());
             var _ = StaticConfigOverride.set("tasks.Kept.class", GoodTask.class.getName());
             var _ = StaticConfigOverride.set("tasks.Absent.class", "no.such.TaskClass");
             var _ = StaticConfigOverride.set("tasks.NotATask.class", "java.lang.StringBuilder")) {
            manager.initialize();
        }

        TaskLock fresh = manager.locks.get("Fresh");
        assertNotNull(fresh, "a configured task with no row gets an empty one");
        assertEquals(new Date(0), fresh.getLastRun(), "a new row means 'never ran', so it is due at once");
        assertEquals(new Date(0), fresh.getTimeAcquired());
        assertEquals(0, fresh.getTimeLeased());
        assertSame(existing, manager.locks.get("Kept"), "an existing row is left alone");
        assertEquals(new Date(12345), existing.getLastRun());
        assertNull(manager.locks.get("NoClass"), "a name with no class property is ignored");
        assertNull(manager.locks.get("Absent"), "a class that cannot be loaded is logged, not registered");
        assertNull(manager.locks.get("NotATask"), "a class that is not a PressroomTask is refused");
        assertEquals(2, manager.locks.size(), "exactly Fresh and Kept");
    }

    @Test
    void initializingTwiceDoesNotStartASecondSchedulerThread() throws Exception {
        manager = new InMemoryThreadManager();
        try (var _ = StaticConfigOverride.set("tasks.enabled", "")) {
            manager.initialize();
            long before = schedulerThreads();
            manager.initialize();
            assertEquals(before, schedulerThreads());
        }
    }

    private static long schedulerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> "Roller Weblogger Task Scheduler".equals(t.getName()) && t.isAlive())
                .count();
    }

    @Test
    void shutdownInterruptsBackgroundWorkAndStopsTheScheduler() throws Exception {
        manager = new InMemoryThreadManager();
        try (var _ = StaticConfigOverride.set("tasks.enabled", "")) {
            manager.initialize();
        }
        long running = schedulerThreads();
        assertTrue(running >= 1, "initialize starts the scheduler thread");

        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        manager.executeInBackground(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
            finished.countDown();
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        manager.shutdown();

        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertTrue(interrupted.get(), "shutdown must interrupt work still running in the background");
        assertEquals(running - 1, schedulerThreads(), "the scheduler thread must be stopped");
    }

    @Test
    void shutdownBeforeInitializeIsHarmless() {
        manager = new InMemoryThreadManager();
        assertDoesNotThrow(manager::shutdown);
    }

    @Test
    void executeInForegroundReturnsOnlyAfterTheWorkIsDone() throws Exception {
        manager = new InMemoryThreadManager();
        AtomicBoolean done = new AtomicBoolean();

        manager.executeInForeground(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            done.set(true);
        });

        assertTrue(done.get(), "the caller must not get control back before the work finishes");
    }

    /**
     * Every search runs through here, on a request thread. Waiting by polling
     * {@code isDone()} with a half-second sleep made each search take at
     * least 500 ms however fast Lucene answered (measured: 504 ms median,
     * under 5% of the CPU). The work here takes 50 ms, long enough to still
     * be running at the first check, so a polling wait cannot pass.
     */
    @Test
    void executeInForegroundReturnsAsSoonAsTheWorkIsDone() throws Exception {
        manager = new InMemoryThreadManager();

        long start = System.nanoTime();
        manager.executeInForeground(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < 300, "50 ms of work took " + elapsedMs + " ms to hand back");
    }

    /**
     * Characterisation test, passing against the polling version too: work
     * that throws does not throw out of here. The callers judge the outcome
     * themselves (a search with no results set is reported as a failed
     * search), so this must stay true when the wait changes.
     */
    @Test
    void executeInForegroundDoesNotRethrowWhatTheWorkThrows() {
        manager = new InMemoryThreadManager();

        assertDoesNotThrow(() -> manager.executeInForeground(() -> {
            throw new IllegalStateException("index unreadable");
        }));
    }

    @Test
    void theDefaultLeaseIsAlwaysGrantedAndReleased() {
        manager = new InMemoryThreadManager();
        GoodTask task = new GoodTask();

        assertTrue(manager.registerLease(task));
        assertTrue(manager.unregisterLease(task));
        manager.release();
    }
}
