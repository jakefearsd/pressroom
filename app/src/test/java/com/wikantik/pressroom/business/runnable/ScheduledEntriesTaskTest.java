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
import java.sql.Timestamp;
import java.util.Date;
import java.util.Properties;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduledEntriesTask} promoting {@code SCHEDULED} entries whose
 * pubtime has passed, run directly (no scheduler) against the real database;
 * plus its failure contract and configuration reading against a mocked
 * facade.
 *
 * <p>Characterisation tests: written against the existing behaviour and
 * expected to pass immediately.
 *
 * <p>An entry can only reach {@code SCHEDULED} with a past pubtime by time
 * passing; the fixture writes that state directly (status SCHEDULED, pubtime
 * set) because {@code saveWeblogEntry} only ever turns PUBLISHED into
 * SCHEDULED, never the reverse -- which is exactly the task's job.
 */
class ScheduledEntriesTaskTest {

    private static final long HOUR_MS = 60L * 60 * 1000;

    private User user;
    private Weblog blog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("schedTaskUser");
        blog = TestUtils.setupWeblog("schedtaskblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(blog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------- promotion

    @Test
    void aScheduledEntryWhosePubTimeHasPassedIsPublished() throws Exception {
        String id = scheduledEntry("due-entry", new Date(System.currentTimeMillis() - HOUR_MS));

        runTask();

        assertEquals(PubStatus.PUBLISHED, entries().getWeblogEntry(id).getStatus());
    }

    @Test
    void aScheduledEntryStillInTheFutureIsLeftScheduled() throws Exception {
        String id = scheduledEntry("future-entry", new Date(System.currentTimeMillis() + HOUR_MS));

        runTask();

        assertEquals(PubStatus.SCHEDULED, entries().getWeblogEntry(id).getStatus());
    }

    /** Only SCHEDULED is promoted: a past-dated draft is not published by the clock. */
    @Test
    void aPastDatedDraftIsNotPublished() throws Exception {
        WeblogEntry draft = TestUtils.setupWeblogEntry("past-draft", blog, user, PubStatus.DRAFT);
        TestUtils.endSession(true);

        runTask();

        assertEquals(PubStatus.DRAFT, entries().getWeblogEntry(draft.getId()).getStatus());
    }

    /**
     * Rendered pages expire only through {@code weblog.lastModified}; a
     * promotion that did not bump it would leave the entry invisible on
     * cached pages until something else touched the weblog.
     */
    @Test
    void promotionBumpsTheWeblogsLastModifiedSoRenderedPagesExpire() throws Exception {
        // Read before the entry exists: whichever run promotes it (this one,
        // or the test JVM's own background scheduler) bumps after this.
        Date before = TestUtils.weblogger().getWeblogManager()
                .getWeblog(blog.getId()).getLastModified();
        TestUtils.endSession(true);
        Thread.sleep(10);
        String id = scheduledEntry("bump-entry", new Date(System.currentTimeMillis() - HOUR_MS));

        runTask();

        assertEquals(PubStatus.PUBLISHED, entries().getWeblogEntry(id).getStatus());
        Date after = TestUtils.weblogger().getWeblogManager()
                .getWeblog(blog.getId()).getLastModified();
        assertTrue(after.after(before),
                "promotion must bump weblog.lastModified (before " + before + ", after " + after + ")");
    }

    // ------------------------------------------------------ failure contract

    /**
     * The task runs unattended off the scheduler: a failed lookup must not
     * escape {@code runTask()}, and the facade is released regardless.
     */
    @Test
    void aFailedLookupDoesNotEscapeAndTheWebloggerIsReleased() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(entryManager.getWeblogEntries(any())).thenThrow(new WebloggerException("db down"));

        ScheduledEntriesTask task = new ScheduledEntriesTask();
        task.init(weblogger);

        assertDoesNotThrow(task::runTask);
        verify(weblogger).release();
    }

    @Test
    void anUncheckedFailureDoesNotEscapeEitherAndTheWebloggerIsReleased() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(entryManager.getWeblogEntries(any())).thenThrow(new IllegalStateException("boom"));

        ScheduledEntriesTask task = new ScheduledEntriesTask();
        task.init(weblogger);

        assertDoesNotThrow(task::runTask);
        verify(weblogger).release();
    }

    // ---------------------------------------------------------- configuration

    /**
     * The one-arg {@code init} reads this task's own shipped
     * {@code tasks.ScheduledEntriesTask.*} block: once a minute, starting
     * immediately.
     */
    @Test
    void initReadsTheShippedScheduleForThisTask() throws Exception {
        ScheduledEntriesTask task = new ScheduledEntriesTask();
        task.init(mock(Weblogger.class));

        assertEquals("defaultClientId", task.getClientId());
        assertEquals("immediate", task.getStartTimeDesc());
        assertEquals(1, task.getInterval());
        assertEquals(30, task.getLeaseTime());
        Date now = new Date();
        assertEquals(now, task.getStartTime(now), "'immediate' means start now, unadjusted");
    }

    /**
     * A mistyped override in {@code roller-custom.properties} must not stop
     * the task being constructed, and must leave the default in place rather
     * than half-applying a bad value.
     */
    @Test
    void malformedIntervalAndLeaseTimeLeaveTheDefaultsInPlace() throws Exception {
        String name = "scheduledEntriesBadNumbersTest";
        String intervalKey = "tasks." + name + ".interval";
        String leaseKey = "tasks." + name + ".leaseTime";
        String previousInterval = overrideConfigProperty(intervalKey, "every-minute");
        String previousLease = overrideConfigProperty(leaseKey, "half-an-hour");
        try {
            ScheduledEntriesTask task = new ScheduledEntriesTask();

            assertDoesNotThrow(() -> task.init(mock(Weblogger.class), name));
            assertEquals(1, task.getInterval());
            assertEquals(PressroomTaskWithLeasing.DEFAULT_LEASE_MINS, task.getLeaseTime());
        } finally {
            overrideConfigProperty(intervalKey, previousInterval);
            overrideConfigProperty(leaseKey, previousLease);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static WeblogEntryManager entries() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    /** Runs the task the way the scheduler would, against the real tier. */
    private static void runTask() throws Exception {
        ScheduledEntriesTask task = new ScheduledEntriesTask();
        task.init(TestUtils.weblogger());
        task.runTask();
        TestUtils.endSession(true);
    }

    /**
     * A SCHEDULED entry with the given pubtime, persisted and detached.
     * Created as a DRAFT and moved to SCHEDULED in one save, so the test
     * JVM's own background scheduler can never see a half-built fixture.
     */
    private String scheduledEntry(String anchor, Date pubTime) throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry(anchor, blog, user, PubStatus.DRAFT);
        created.setStatus(PubStatus.SCHEDULED);
        created.setPubTime(new Timestamp(pubTime.getTime()));
        entries().saveWeblogEntry(created);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return created.getId();
    }

    /**
     * Overrides a startup property (standing in for
     * {@code roller-custom.properties}) and returns the previous value.
     * Same reflective access as {@code TrashPurgeTaskTest}.
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
}
