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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import com.wikantik.pressroom.business.runnable.ThreadManager;

class UnitOfWorkTest {

    @Test
    void releasesTheUnitOfWorkAfterTheWorkRuns() {
        Weblogger weblogger = mock(Weblogger.class);
        List<String> events = new ArrayList<>();

        UnitOfWork.releasing(weblogger, () -> events.add("work")).run();

        assertEquals(List.of("work"), events);
        verify(weblogger).release();
    }

    @Test
    void releasesEvenWhenTheWorkThrowsAndLetsItPropagate() {
        Weblogger weblogger = mock(Weblogger.class);

        assertThrows(IllegalStateException.class, UnitOfWork.releasing(weblogger, () -> {
            throw new IllegalStateException("boom");
        })::run);

        verify(weblogger).release();
    }

    @Test
    void nothingRunsOrReleasesUntilTheWrapperIsRun() {
        Weblogger weblogger = mock(Weblogger.class);
        UnitOfWork.releasing(weblogger, () -> { });
        verify(weblogger, never()).release();
    }

    @Test
    void backgroundExecutorAdaptsExecuteInBackground() throws Exception {
        ThreadManager manager = mock(ThreadManager.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        Runnable job = () -> { };

        Executor executor = manager.background();
        executor.execute(job);

        verify(manager).executeInBackground(job);
    }

    @Test
    void backgroundExecutorTurnsAnInterruptedSchedulingIntoARejection() throws Exception {
        ThreadManager manager = mock(ThreadManager.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.doThrow(new InterruptedException("stop")).when(manager)
                .executeInBackground(org.mockito.ArgumentMatchers.any());

        try {
            assertThrows(RejectedExecutionException.class, () -> manager.background().execute(() -> { }));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is restored");
        } finally {
            Thread.interrupted();
        }
    }
}
