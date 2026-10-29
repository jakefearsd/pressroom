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

import com.wikantik.pressroom.business.Weblogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A task reaches the business tier through the {@link Weblogger} it was
 * given at {@code init(weblogger, name)} -- not through a static locator.
 * This is the unit-level pin for the DI wave's Decision 3 (see
 * {@code docs/superpowers/specs/2026-08-22-retire-static-service-locator-design.md}):
 * a task constructed reflectively from {@code tasks.<name>.class} has no
 * constructor Spring controls, so the facade arrives through the existing
 * {@code init} hook and nothing else.
 */
class PressroomTaskWithLeasingTest {

    private Weblogger weblogger;
    private ThreadManager threadManager;

    @BeforeEach
    void setUp() {
        weblogger = mock(Weblogger.class);
        threadManager = mock(ThreadManager.class);
        when(weblogger.getThreadManager()).thenReturn(threadManager);
    }

    @Test
    void runAcquiresTheLeaseThroughTheGivenWebloggerRunsTheTaskAndReleasesTheSession() throws Exception {
        RecordingTask task = new RecordingTask();
        task.init(weblogger, "recording");
        when(threadManager.registerLease(task)).thenReturn(true);
        when(threadManager.unregisterLease(task)).thenReturn(true);

        task.run();

        assertTrue(task.ran, "runTask() must run once the lease is held");
        InOrder order = inOrder(threadManager, weblogger);
        order.verify(threadManager).registerLease(task);
        order.verify(threadManager).unregisterLease(task);
        order.verify(weblogger).release();
    }

    @Test
    void aTaskThatCannotAcquireTheLeaseDoesNotRunButStillReleasesTheSession() throws Exception {
        RecordingTask task = new RecordingTask();
        task.init(weblogger, "recording");
        when(threadManager.registerLease(task)).thenReturn(false);

        task.run();

        assertFalse(task.ran, "runTask() must not run without the lease");
        verify(threadManager, never()).unregisterLease(task);
        verify(weblogger).release();
    }

    @Test
    void initRecordsTheNameAndTheWeblogger() throws Exception {
        RecordingTask task = new RecordingTask();

        task.init(weblogger, "recording");

        assertEquals("recording", task.getName());
        assertEquals(weblogger, task.weblogger());
    }

    @Test
    void aTaskThatWasNeverInitialisedRefusesToHandOutAWeblogger() {
        RecordingTask task = new RecordingTask();

        assertThrows(IllegalStateException.class, task::weblogger,
                "a task must not reach the business tier before init(weblogger, name)");
    }

    /** The smallest leasing task: records whether it ran, nothing else. */
    private static final class RecordingTask extends PressroomTaskWithLeasing {
        private boolean ran;

        @Override
        public void runTask() {
            ran = true;
        }

        @Override
        public String getClientId() {
            return "recording-client";
        }

        @Override
        public Date getStartTime(Date currentTime) {
            return currentTime;
        }

        @Override
        public String getStartTimeDesc() {
            return "immediate";
        }

        @Override
        public int getInterval() {
            return 1;
        }

        @Override
        public int getLeaseTime() {
            return 1;
        }
    }
}
