package com.wikantik.pressroom.business.runnable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.Properties;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * The start window's one zone rule: the day or hour is that of the calendar
 * passed in, for the task's first run and the scheduler's window check alike.
 * Asia/Kolkata (UTC+5:30) is the zone where an hour boundary and a day
 * boundary both differ from UTC.
 */
class StartWindowTest {

    private static final TimeZone KOLKATA = TimeZone.getTimeZone("Asia/Kolkata");
    private static final TimeZone NEW_YORK = TimeZone.getTimeZone("America/New_York");

    private static Date at(String zone, int y, int mo, int d, int h, int mi) {
        return Date.from(ZonedDateTime.of(y, mo, d, h, mi, 30, 0, ZoneId.of(zone)).toInstant());
    }

    private static Calendar cal(TimeZone zone) {
        return Calendar.getInstance(zone);
    }

    @Test
    void parseMapsTheThreeDescriptionsAndFallsBackToImmediate() {
        assertEquals(StartWindow.START_OF_DAY, StartWindow.parse("startOfDay"));
        assertEquals(StartWindow.START_OF_HOUR, StartWindow.parse("startOfHour"));
        assertEquals(StartWindow.IMMEDIATE, StartWindow.parse("immediate"));
        assertEquals(StartWindow.IMMEDIATE, StartWindow.parse("whenever"));
        assertEquals(StartWindow.IMMEDIATE, StartWindow.parse(null));
        assertEquals("startOfHour", StartWindow.START_OF_HOUR.description());
    }

    @Test
    void windowStartFollowsTheCalendarZoneForBothWindows() {
        // 2026-11-19 01:45:30 Kolkata is 20:15:30 UTC the day before
        Date t = at("Asia/Kolkata", 2026, 11, 19, 1, 45);
        assertEquals(at("Asia/Kolkata", 2026, 11, 19, 0, 0).getTime() - 30_000,
                StartWindow.START_OF_DAY.windowStart(t, cal(KOLKATA)).getTime());
        assertEquals(at("Asia/Kolkata", 2026, 11, 19, 1, 0).getTime() - 30_000,
                StartWindow.START_OF_HOUR.windowStart(t, cal(KOLKATA)).getTime());
        // the same instant read in UTC is a different day and a half-hour-shifted hour
        assertEquals(Instant.parse("2026-11-18T00:00:00Z"),
                StartWindow.START_OF_DAY.windowStart(t, cal(TimeZone.getTimeZone("UTC"))).toInstant());
        assertEquals(Instant.parse("2026-11-18T20:00:00Z"),
                StartWindow.START_OF_HOUR.windowStart(t, cal(TimeZone.getTimeZone("UTC"))).toInstant());
    }

    @Test
    void firstRunIsTheEndOfTheZonesDayOrHour() {
        Date t = at("America/New_York", 2026, 11, 19, 22, 10);
        Date endOfDay = StartWindow.START_OF_DAY.firstRun(t, cal(NEW_YORK));
        assertEquals(ZonedDateTime.of(2026, 11, 19, 23, 59, 59, 999_000_000, ZoneId.of("America/New_York")).toInstant(),
                endOfDay.toInstant());
        Date endOfHour = StartWindow.START_OF_HOUR.firstRun(at("Asia/Kolkata", 2026, 11, 19, 1, 45), cal(KOLKATA));
        assertEquals(ZonedDateTime.of(2026, 11, 19, 1, 59, 59, 999_000_000, ZoneId.of("Asia/Kolkata")).toInstant(),
                endOfHour.toInstant());
    }

    @Test
    void immediateIsUnchangedAndNullStaysNull() {
        Date t = new Date();
        assertEquals(t, StartWindow.IMMEDIATE.firstRun(t, cal(KOLKATA)));
        assertEquals(t, StartWindow.IMMEDIATE.windowStart(t, cal(KOLKATA)));
        assertNull(StartWindow.START_OF_DAY.firstRun(null, cal(KOLKATA)));
    }

    @Test
    void settingsOverrideDefaultsAndParseTheWindowOnce() {
        TaskSettings defaults = TaskSettings.of("me", StartWindow.START_OF_DAY, 1440, 30);
        Properties props = new Properties();
        props.setProperty("startTime", "startOfHour");
        props.setProperty("interval", "oops");
        props.setProperty("leaseTime", "5");
        TaskSettings s = defaults.overriddenBy(props);
        assertEquals(StartWindow.START_OF_HOUR, s.startWindow());
        assertEquals("startOfHour", s.startTimeDesc());
        assertEquals("me", s.clientId());
        assertEquals(1440, s.interval());
        assertEquals(5, s.leaseTime());
    }
}
