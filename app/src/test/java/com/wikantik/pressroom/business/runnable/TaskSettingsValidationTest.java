package com.wikantik.pressroom.business.runnable;

import java.lang.reflect.Field;
import java.util.Properties;

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Every leasing task rejects a non-positive or unparsable interval or lease
 * time and keeps its default (as {@code AnalyticsSyncTask} always did).
 */
class TaskSettingsValidationTest {

    @FunctionalInterface
    private interface ThrowingCheck {
        void run() throws Exception;
    }

    private static String override(String name, String value) throws Exception {
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

    private static void withConfig(String name, String key, String value, ThrowingCheck check) throws Exception {
        String full = "tasks." + name + "." + key;
        String previous = override(full, value);
        try {
            check.run();
        } finally {
            override(full, previous);
        }
    }

    private static void scheduledEntries(String name, String key, String value, int interval, int lease)
            throws Exception {
        withConfig(name, key, value, () -> {
            ScheduledEntriesTask task = new ScheduledEntriesTask();
            initQuietly(task, name);
            assertEquals(interval, task.getInterval(), key + "=" + value);
            assertEquals(lease, task.getLeaseTime(), key + "=" + value);
        });
    }

    private static void trashPurge(String name, String key, String value, int interval, int lease)
            throws Exception {
        withConfig(name, key, value, () -> {
            TrashPurgeTask task = new TrashPurgeTask();
            initQuietly(task, name);
            assertEquals(interval, task.getInterval(), key + "=" + value);
            assertEquals(lease, task.getLeaseTime(), key + "=" + value);
        });
    }

    private static void initQuietly(PressroomTask task, String name) {
        try {
            task.init(mock(Weblogger.class), name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void scheduledEntriesRejectsNonPositiveNumbers() throws Exception {
        for (String bad : new String[] {"0", "-5"}) {
            scheduledEntries("seBadInterval" + bad, "interval", bad, 1,
                    PressroomTaskWithLeasing.DEFAULT_LEASE_MINS);
            scheduledEntries("seBadLease" + bad, "leaseTime", bad, 1,
                    PressroomTaskWithLeasing.DEFAULT_LEASE_MINS);
        }
    }

    @Test
    void trashPurgeRejectsNonPositiveNumbers() throws Exception {
        for (String bad : new String[] {"0", "-5"}) {
            trashPurge("tpBadInterval" + bad, "interval", bad, PressroomTask.DEFAULT_INTERVAL_MINS,
                    PressroomTaskWithLeasing.DEFAULT_LEASE_MINS);
            trashPurge("tpBadLease" + bad, "leaseTime", bad, PressroomTask.DEFAULT_INTERVAL_MINS,
                    PressroomTaskWithLeasing.DEFAULT_LEASE_MINS);
        }
    }

    @Test
    void popularityRefreshRejectsNonPositiveLease() throws Exception {
        for (String bad : new String[] {"0", "-5", "soon"}) {
            withConfig("prBadLease" + bad, "leaseTime", bad, () -> {
                PopularityRefreshTask task = new PopularityRefreshTask();
                initQuietly(task, "prBadLease" + bad);
                assertEquals(PressroomTaskWithLeasing.DEFAULT_LEASE_MINS, task.getLeaseTime());
            });
        }
    }

    @Test
    void aValidNumberStillApplies() throws Exception {
        scheduledEntries("seGood", "interval", " 7 ", 7, PressroomTaskWithLeasing.DEFAULT_LEASE_MINS);
    }
}
