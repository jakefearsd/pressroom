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
package org.apache.roller.weblogger.business.jpa;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.TestTask;
import org.apache.roller.weblogger.business.runnable.ThreadManager;
import org.apache.roller.weblogger.pojos.TaskLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refusal paths of the database lease: a lease can only be taken or
 * released against an existing lock row, and only the client that holds it
 * can release it. {@code TaskLockTest} covers the happy cycle; these are the
 * cases that decide whether two nodes can run the same task.
 *
 * <p>The {@code TestTask} lock row is created at start-up (it is listed in
 * {@code tasks.enabled} for tests); every test ends with the lease released.
 */
class JPAThreadManagerLeaseTest {

    private ThreadManager manager;
    private TestTask holder;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        manager = TestUtils.weblogger().getThreadManager();
        holder = new TestTask();
        holder.init(TestUtils.weblogger());
    }

    @AfterEach
    void releaseLease() throws Exception {
        manager.unregisterLease(holder);
        TestUtils.endSession(false);
    }

    private TestTask taskNamed(String name, String clientId) throws Exception {
        TestTask task = new TestTask() {
            @Override
            public String getClientId() {
                return clientId;
            }
        };
        task.init(TestUtils.weblogger(), name);
        return task;
    }

    @Test
    void aLeaseCannotBeTakenOrReleasedForATaskWithNoLockRow() throws Exception {
        TestTask stranger = taskNamed("NoSuchLockRow", "x");

        assertNull(manager.getTaskLockByName("NoSuchLockRow"));
        assertFalse(manager.registerLease(stranger), "no row, no lease");
        assertFalse(manager.unregisterLease(stranger), "nothing to release");
    }

    @Test
    void onlyTheClientHoldingTheLeaseCanReleaseIt() throws Exception {
        assertTrue(manager.registerLease(holder));
        TestUtils.endSession(false);

        TestTask other = taskNamed(TestTask.NAME, "another-node");
        assertFalse(manager.unregisterLease(other), "another node must not release our lease");
        TestUtils.endSession(false);
        assertFalse(manager.registerLease(holder), "the lease is still held");
        TestUtils.endSession(false);

        TaskLock lock = manager.getTaskLockByName(TestTask.NAME);
        assertNotNull(lock);
        assertEquals("TestTaskClientId", lock.getClientId(), "the holder is unchanged");
        assertTrue(manager.unregisterLease(holder), "the holder can release it");
    }

    @Test
    void aTaskLockSavedThroughTheManagerIsFoundByName() throws Exception {
        TaskLock lock = manager.getTaskLockByName(TestTask.NAME);
        assertNotNull(lock);
        int leased = lock.getTimeLeased();

        manager.saveTaskLock(lock);
        TestUtils.endSession(true);

        assertEquals(leased, manager.getTaskLockByName(TestTask.NAME).getTimeLeased());
    }
}
