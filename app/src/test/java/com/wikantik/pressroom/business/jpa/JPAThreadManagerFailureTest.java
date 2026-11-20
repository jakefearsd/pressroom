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
package com.wikantik.pressroom.business.jpa;

import java.sql.Timestamp;
import java.util.Date;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.TestTask;
import com.wikantik.pressroom.pojos.TaskLock;
import com.wikantik.pressroom.util.DateUtil;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lease paths of {@link JPAThreadManagerImpl} over a store that fails or
 * refuses: a task runs only when its lease was certainly taken, so every
 * failure answers "no lease" (and "not released"). The happy paths against
 * the real database are {@link JPAThreadManagerLeaseTest}.
 */
class JPAThreadManagerFailureTest {

    private JPAPersistenceStrategy strategy;
    private JPAThreadManagerImpl manager;
    private TypedQuery<TaskLock> lookup;
    private Query update;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        strategy = mock(JPAPersistenceStrategy.class);
        manager = new JPAThreadManagerImpl(MockWeblogger.create().weblogger(), strategy);
        lookup = mock(TypedQuery.class);
        update = mock(Query.class);
        TaskLock lock = new TaskLock();
        lock.setName(TestTask.NAME);
        lock.setTimeAcquired(new Date(0));
        when(lookup.getSingleResult()).thenReturn(lock);
        when(strategy.getNamedQuery("TaskLock.getByName", TaskLock.class)).thenReturn(lookup);
        when(strategy.getNamedUpdate(anyString())).thenReturn(update);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    private static TestTask task(String startTimeDesc) throws WebloggerException {
        TestTask task = new TestTask("TestTaskClientId", startTimeDesc);
        task.init(MockWeblogger.create().weblogger());
        return task;
    }

    @Test
    void aLockRowThatCannotBeReadIsNoLeaseAndNoRelease() throws Exception {
        when(strategy.getNamedQuery("TaskLock.getByName", TaskLock.class))
                .thenThrow(new WebloggerException("db down"));

        assertFalse(manager.registerLease(task("immediate")));
        assertFalse(manager.unregisterLease(task("immediate")));
        verify(strategy, never()).getNamedUpdate(anyString());
    }

    /** A failed update is read as a lost race: the task does not run this tick. */
    @Test
    void anUpdateThatFailsIsNoLeaseAndNoRelease() throws Exception {
        when(update.executeUpdate()).thenThrow(new PersistenceException("db down"));

        assertFalse(manager.registerLease(task("immediate")));
        assertFalse(manager.unregisterLease(task("immediate")));
        verify(strategy, never()).flush();
    }

    @Test
    void anUpdateThatMatchesNoRowIsNoLeaseAndNoRelease() throws Exception {
        when(update.executeUpdate()).thenReturn(0);

        assertFalse(manager.registerLease(task("immediate")), "another node holds the lease");
        assertFalse(manager.unregisterLease(task("immediate")), "another node holds the lease");
        verify(strategy, never()).flush();
    }

    @Test
    void aLeaseTakenAndReleasedIsFlushed() throws Exception {
        when(update.executeUpdate()).thenReturn(1);

        assertTrue(manager.registerLease(task("immediate")));
        assertTrue(manager.unregisterLease(task("immediate")));
        verify(update).setParameter(1, 0);
        verify(update).setParameter(2, TestTask.NAME);
        verify(update).setParameter(3, "TestTaskClientId");
    }

    /** The recorded run time is the slot the task is due in: the day, the hour, or else the minute. */
    @Test
    void theRunTimeRecordedIsTheStartOfTheTasksSlot() throws Exception {
        when(update.executeUpdate()).thenReturn(1);

        assertSlot("startOfDay", DateUtil::getStartOfDay);
        assertSlot("startOfHour", DateUtil::getStartOfHour);
        assertSlot("immediate", DateUtil::getStartOfMinute);
    }

    /** Brackets the call so a slot boundary crossed mid-test cannot fail it. */
    private void assertSlot(String desc, java.util.function.UnaryOperator<Date> slotOf) throws Exception {
        long before = slotOf.apply(new Date()).getTime();
        long recorded = recordedRunTime(desc);
        long after = slotOf.apply(new Date()).getTime();
        assertTrue(recorded == before || recorded == after, desc + " recorded " + new Date(recorded));
    }

    private long recordedRunTime(String desc) throws Exception {
        update = mock(Query.class);
        when(update.executeUpdate()).thenReturn(1);
        when(strategy.getNamedUpdate(anyString())).thenReturn(update);
        assertTrue(manager.registerLease(task(desc)));
        ArgumentCaptor<Timestamp> runTime = ArgumentCaptor.forClass(Timestamp.class);
        verify(update).setParameter(eq(4), runTime.capture());
        verify(update).setParameter(eq(3), anyInt());
        verify(update, never()).setParameter(eq(8), any());
        return runTime.getValue().getTime();
    }
}
