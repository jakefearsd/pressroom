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

import java.lang.reflect.Field;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.RuntimeConfigAttachment;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TrashPurgeTask}'s own logic -- retention property re-read,
 * per-weblog looping, and per-weblog failure isolation -- exercised against
 * a fully mocked {@link Weblogger} facade rather than the real database.
 *
 * <p>{@code purgeTrash}'s own semantics (what "past retention" means, the
 * strict boundary comparison, -1 meaning "keep forever") are already covered
 * end to end against a real database by {@code WeblogEntryTrashOperationsTest}
 * -- this class exists to prove what the TASK does with that method, not to
 * re-prove what the method does.
 */
class TrashPurgeTaskTest {

    private static final String RETENTION_PROPERTY = "entry.trash.retention.days";

    private Weblogger weblogger;
    private WeblogManager weblogManager;
    private WeblogEntryManager entryManager;
    private PropertiesManager propertiesManager;
    private RuntimeConfigAttachment runtimeConfig;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = mock(Weblogger.class);
        weblogManager = mock(WeblogManager.class);
        entryManager = mock(WeblogEntryManager.class);
        propertiesManager = mock(PropertiesManager.class);

        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);

        // The task works against the Weblogger handed to init(weblogger, name)
        // above. runTask() reads entry.trash.retention.days through
        // WebloggerRuntimeConfig.getIntProperty, which reads the properties
        // manager attached to it (spec Decision 8 / plan Task 19) -- so that is
        // attached here, and nothing else: a task that regressed to locating
        // its managers statically would find nothing and fail.
        runtimeConfig = RuntimeConfigAttachment.of(propertiesManager);
    }

    @AfterEach
    void tearDown() {
        runtimeConfig.close();
    }

    /**
     * A task initialised the way production initialises it ({@code
     * ThreadManagerImpl.initialize()} calls {@code init(weblogger, name)}):
     * the facade it works against arrives through that hook, never through
     * a static locator.
     */
    private TrashPurgeTask task() throws Exception {
        TrashPurgeTask task = new TrashPurgeTask();
        task.init(weblogger, TrashPurgeTask.NAME);
        return task;
    }

    @Test
    void retentionOfMinusOneIsPassedThroughUnchanged() throws Exception {
        Weblog blog = weblog("onlyblog");
        givenWeblogs(blog);
        givenRetention(-1);

        task().runTask();

        verify(entryManager).purgeTrash(blog, -1);
    }

    @Test
    void retentionOfZeroIsPassedThroughUnchanged() throws Exception {
        Weblog blog = weblog("onlyblog");
        givenWeblogs(blog);
        givenRetention(0);

        task().runTask();

        verify(entryManager).purgeTrash(blog, 0);
    }

    @Test
    void aPositiveRetentionIsPassedThroughUnchanged() throws Exception {
        Weblog blog = weblog("onlyblog");
        givenWeblogs(blog);
        givenRetention(30);

        task().runTask();

        verify(entryManager).purgeTrash(blog, 30);
    }

    /**
     * The property is re-read on every call to {@code runTask()}, not latched
     * once -- the trap CLAUDE.md's Configuration scope section names for a
     * promoted-looking runtime property. Two sweeps with two different values
     * on the SAME task instance must use the value current at each sweep.
     */
    @Test
    void theRetentionPropertyIsReReadOnEverySweepNotLatched() throws Exception {
        Weblog blog = weblog("onlyblog");
        givenWeblogs(blog);
        TrashPurgeTask task = task();

        givenRetention(30);
        task.runTask();
        verify(entryManager).purgeTrash(blog, 30);

        givenRetention(7);
        task.runTask();
        verify(entryManager).purgeTrash(blog, 7);
    }

    /**
     * The failure granularity this task chose: per weblog. One weblog's
     * {@code purgeTrash} throwing must not stop the sweep for the others, and
     * must not propagate out of {@code runTask()} -- this runs unattended off
     * the scheduler (see {@link PressroomTaskWithLeasing#run()}), the same
     * contract {@code ScheduledEntriesTask} honours for the sweep as a whole.
     */
    @Test
    void oneWeblogsPurgeThrowingDoesNotStopTheOthers() throws Exception {
        Weblog failing = weblog("failingblog");
        Weblog first = weblog("firstblog");
        Weblog second = weblog("secondblog");
        givenWeblogs(first, failing, second);
        givenRetention(30);

        doThrow(new WebloggerException("boom")).when(entryManager).purgeTrash(eq(failing), anyInt());

        task().runTask();

        verify(entryManager).purgeTrash(first, 30);
        verify(entryManager).purgeTrash(failing, 30);
        verify(entryManager).purgeTrash(second, 30);
    }

    /**
     * Same isolation contract for an unchecked exception, not just the
     * declared {@code WebloggerException} -- {@code runTask()} itself has no
     * "unexpected exception" escape hatch of its own the way
     * {@code ScheduledEntriesTask.runTask()} does for its top-level body, so
     * the per-weblog catch must be the thing that actually stops a runtime
     * exception from taking the rest of the sweep down.
     */
    @Test
    void oneWeblogsPurgeThrowingAnUncheckedExceptionDoesNotStopTheOthers() throws Exception {
        Weblog failing = weblog("failingblog");
        Weblog other = weblog("otherblog");
        givenWeblogs(failing, other);
        givenRetention(30);

        doThrow(new RuntimeException("kaboom")).when(entryManager).purgeTrash(eq(failing), anyInt());

        task().runTask();

        verify(entryManager).purgeTrash(other, 30);
    }

    /**
     * The lease/release contract every {@code PressroomTaskWithLeasing} subclass
     * must keep: {@code weblogger().release()} always
     * runs, even when a purge blew up.
     */
    @Test
    void theWebloggerIsAlwaysReleasedEvenWhenAPurgeThrows() throws Exception {
        Weblog failing = weblog("failingblog");
        givenWeblogs(failing);
        givenRetention(30);

        doThrow(new WebloggerException("boom")).when(entryManager).purgeTrash(eq(failing), anyInt());

        task().runTask();

        verify(weblogger).release();
    }

    /**
     * No weblog to purge and no property configured yet (a fresh install
     * before the row is seeded) must not throw out of {@code runTask()}.
     */
    @Test
    void noWeblogsIsANoOp() throws Exception {
        givenWeblogs();
        givenRetention(30);

        task().runTask();

        verify(entryManager, never()).purgeTrash(any(), anyInt());
        verify(weblogger).release();
    }

    /**
     * A purge that actually removed entries (the {@code purged > 0} branch,
     * which logs a summary) must not treat that weblog any differently from
     * one with nothing to purge -- the sweep still has to visit every other
     * weblog. A stray early return or exception added to that branch would
     * silently truncate the sweep the moment any weblog actually had trash.
     */
    @Test
    void aWeblogWithSomethingActuallyPurgedDoesNotStopTheSweepForOthers() throws Exception {
        Weblog withTrash = weblog("hastrash");
        Weblog empty = weblog("notrash");
        givenWeblogs(withTrash, empty);
        givenRetention(30);
        when(entryManager.purgeTrash(withTrash, 30)).thenReturn(5);

        task().runTask();

        verify(entryManager).purgeTrash(withTrash, 30);
        verify(entryManager).purgeTrash(empty, 30);
    }

    /**
     * {@code runTask()} has its own outer try/catch around everything before
     * the per-weblog loop (getting the managers, reading the retention
     * property, listing the weblogs) -- separate from the per-weblog
     * isolation covered above. A checked failure listing the weblogs
     * themselves must not propagate: this runs unattended off the
     * scheduler, with nobody watching to catch an uncaught exception.
     */
    @Test
    void aFailureListingWeblogsIsCaughtAndDoesNotPropagate() throws Exception {
        when(weblogManager.getWeblogs(eq(true), isNull(), isNull(), isNull(), eq(0), eq(-1)))
                .thenThrow(new WebloggerException("db down"));
        givenRetention(30);

        assertDoesNotThrow(() -> task().runTask(),
                "a failure listing weblogs must not propagate out of runTask()");
        verify(weblogger).release();
    }

    /**
     * Same outer-catch contract, for an unchecked exception rather than the
     * declared {@code WebloggerException} -- the top-level body has a second
     * catch clause specifically so a runtime exception this early (before any
     * per-weblog try/catch is even reached) does not take the whole task
     * down either.
     */
    @Test
    void anUnexpectedRuntimeExceptionListingWeblogsIsCaughtAndDoesNotPropagate() throws Exception {
        when(weblogManager.getWeblogs(eq(true), isNull(), isNull(), isNull(), eq(0), eq(-1)))
                .thenThrow(new RuntimeException("boom"));
        givenRetention(30);

        assertDoesNotThrow(() -> task().runTask(),
                "an unchecked exception listing weblogs must not propagate out of runTask() either");
        verify(weblogger).release();
    }

    // ------------------------------------------------------- task scaffolding
    //
    // getClientId/getStartTime/getStartTimeDesc/getInterval/getLeaseTime,
    // init(...), and the malformed-number branches -- the PressroomTask
    // machinery this class shares with ScheduledEntriesTask, which has no
    // test of its own for this scaffolding either. Nothing here duplicates
    // runTask()'s behaviour, which is covered above and needs a real
    // Weblogger facade to exercise at all.

    /**
     * The four scheduling values a freshly constructed task reports, treated
     * as one contract rather than four separate getter tests -- they are a
     * real product decision, not incidental field values: a purge that ran
     * every minute would hammer the database for nothing, and one that ran
     * once a year would leave trash sitting long past a short configured
     * retention. Regressing any single default silently changes how often,
     * or how late in the day, production sweeps trash.
     */
    @Test
    void aFreshlyConstructedTaskReportsTheDocumentedSchedulingDefaults() {
        TrashPurgeTask task = new TrashPurgeTask();

        assertNull(task.getClientId(), "no clientId until init() reads one from config");
        assertEquals("startOfDay", task.getStartTimeDesc(),
                "trash purge is meant to run once a day, at day's end");
        assertEquals(PressroomTask.DEFAULT_INTERVAL_MINS, task.getInterval(),
                "default interval must stay the shared once-a-day default");
        assertEquals(PressroomTaskWithLeasing.DEFAULT_LEASE_MINS, task.getLeaseTime(),
                "default lease must stay the shared 30-minute lease");
    }

    /**
     * {@code getStartTime} delegates to the shared {@code getAdjustedTime}
     * helper using this task's {@code startTimeDesc} -- pinned against the
     * real adjustment {@code DateUtil} performs (end of the current day),
     * not just "returns some non-null date", since a broken delegation (e.g.
     * returning {@code currentTime} unchanged) would still return a date.
     */
    @Test
    void getStartTimeAdjustsToTheEndOfTheCurrentDayByDefault() {
        TrashPurgeTask task = new TrashPurgeTask();
        Date now = new Date();

        assertEquals(com.wikantik.pressroom.util.DateUtil.getEndOfDay(now), task.getStartTime(now));
    }

    /**
     * The one-arg {@code init(weblogger)} is the convenience entry point
     * for a task started by name -- it must delegate to
     * {@code init(weblogger, name)} using THIS task's own registered
     * name ({@code tasks.TrashPurgeTask.*} in {@code pressroom.properties}), not
     * silently no-op or read some other task's configuration. {@code
     * clientId} is the one field with no matching default (it starts {@code
     * null}), so seeing it become the shipped {@code tasks.clientId} value
     * is proof init() actually ran rather than coincidence.
     */
    @Test
    void noArgInitDelegatesToInitWithThisTasksOwnRegisteredName() throws Exception {
        TrashPurgeTask task = new TrashPurgeTask();

        task.init(weblogger);

        assertEquals("defaultClientId", task.getClientId(),
                "init() must have read the global tasks.clientId from pressroom.properties");
    }

    /**
     * {@code init(name)} is what turns {@code tasks.<name>.*} configuration
     * (plus the global {@code tasks.clientId}) into the four scheduling
     * fields. If this stopped reading any one of them, an operator's
     * {@code pressroom-custom.properties} override for that field would be
     * silently ignored and the shipped default would run in production
     * instead.
     */
    @Test
    void initReadsClientIdStartTimeIntervalAndLeaseTimeFromTaskProperties() throws Exception {
        String taskName = "trashPurgeConfigReadTest";
        String prefix = "tasks." + taskName + ".";

        String previousClientId = overrideConfigProperty("tasks.clientId", "node-7");
        String previousStart = overrideConfigProperty(prefix + "startTime", "startOfHour");
        String previousInterval = overrideConfigProperty(prefix + "interval", "60");
        String previousLease = overrideConfigProperty(prefix + "leaseTime", "5");
        try {
            TrashPurgeTask task = new TrashPurgeTask();
            task.init(weblogger, taskName);

            assertEquals("node-7", task.getClientId());
            assertEquals("startOfHour", task.getStartTimeDesc());
            assertEquals(60, task.getInterval());
            assertEquals(5, task.getLeaseTime());
        } finally {
            overrideConfigProperty("tasks.clientId", previousClientId);
            overrideConfigProperty(prefix + "startTime", previousStart);
            overrideConfigProperty(prefix + "interval", previousInterval);
            overrideConfigProperty(prefix + "leaseTime", previousLease);
        }
    }

    /**
     * The one branch in this scaffolding with real behaviour: a malformed
     * {@code interval} in config is caught and logged, not thrown, and must
     * leave the default interval in place rather than half-applying a bad
     * value. A mistyped override in {@code pressroom-custom.properties} must
     * not stop the task from being constructed at all.
     */
    @Test
    void aMalformedIntervalLeavesTheDefaultIntervalInPlaceAndDoesNotThrow() throws Exception {
        String taskName = "trashPurgeBadIntervalTest";
        String key = "tasks." + taskName + ".interval";

        String previous = overrideConfigProperty(key, "not-a-number");
        try {
            TrashPurgeTask task = new TrashPurgeTask();

            assertDoesNotThrow(() -> task.init(weblogger, taskName),
                    "a malformed interval must not stop the task from being constructed");
            assertEquals(PressroomTask.DEFAULT_INTERVAL_MINS, task.getInterval(),
                    "a malformed interval must leave the default interval in place");
        } finally {
            overrideConfigProperty(key, previous);
        }
    }

    /**
     * Same contract as the interval case, for {@code leaseTime}: a malformed
     * value is caught and logged, and the default lease survives.
     */
    @Test
    void aMalformedLeaseTimeLeavesTheDefaultLeaseTimeInPlaceAndDoesNotThrow() throws Exception {
        String taskName = "trashPurgeBadLeaseTimeTest";
        String key = "tasks." + taskName + ".leaseTime";

        String previous = overrideConfigProperty(key, "not-a-number");
        try {
            TrashPurgeTask task = new TrashPurgeTask();

            assertDoesNotThrow(() -> task.init(weblogger, taskName),
                    "a malformed leaseTime must not stop the task from being constructed");
            assertEquals(PressroomTaskWithLeasing.DEFAULT_LEASE_MINS, task.getLeaseTime(),
                    "a malformed leaseTime must leave the default lease in place");
        } finally {
            overrideConfigProperty(key, previous);
        }
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Overrides a value in the startup configuration (standing in for
     * {@code pressroom-custom.properties}) and returns what was there before, so
     * callers can put it back. The backing {@code Properties} is
     * process-global -- same reflective access pattern as
     * {@code PromotedRuntimePropertyTest}.
     */
    private static String overrideConfigProperty(String name, String value) throws Exception {
        Field field = WebloggerConfig.class.getDeclaredField("config");
        field.setAccessible(true);
        Properties config = (Properties) field.get(null);
        String previous = config.getProperty(name);
        if (value == null) {
            config.remove(name);
        } else {
            config.setProperty(name, value);
        }
        return previous;
    }

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        return weblog;
    }

    private void givenWeblogs(Weblog... weblogs) throws WebloggerException {
        when(weblogManager.getWeblogs(eq(true), isNull(), isNull(), isNull(), eq(0), eq(-1)))
                .thenReturn(List.of(weblogs));
    }

    private void givenRetention(int days) throws WebloggerException {
        when(propertiesManager.getProperty(RETENTION_PROPERTY))
                .thenReturn(new RuntimeConfigProperty(RETENTION_PROPERTY, Integer.toString(days)));
    }
}
