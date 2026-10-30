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

package com.wikantik.pressroom.util;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the date arithmetic behind archive pages, feeds and entry queries.
 *
 * <p>The start/end-of-period helpers define the boundaries of every "entries
 * in June" style query, so an off-by-one millisecond here shows up as an entry
 * that is missing from one archive page and duplicated on the next. Every test
 * that could be affected by the machine's zone pins one explicitly -- these
 * helpers are used from request handling where the zone is the weblog's, not
 * the server's.
 */
public class DateUtilTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final TimeZone SYDNEY = TimeZone.getTimeZone("Australia/Sydney");

    private static Calendar utcCalendar() {
        return Calendar.getInstance(UTC, Locale.US);
    }

    /** A date built in a given zone, so assertions about it are zone-stable. */
    private static Date dateIn(TimeZone tz, int year, int month, int day, int hour, int min, int sec, int ms) {
        Calendar cal = Calendar.getInstance(tz, Locale.US);
        cal.clear();
        cal.set(year, month, day, hour, min, sec);
        cal.set(Calendar.MILLISECOND, ms);
        return cal.getTime();
    }

    private static String isoUtc(Date date) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        fmt.setTimeZone(UTC);
        return fmt.format(date);
    }

    @Nested
    class PeriodBoundaries {

        @Test
        public void startOfDayIsTheFirstMillisecondOfThatDayInTheGivenZone() {
            Date noon = dateIn(UTC, 2004, Calendar.JUNE, 14, 12, 30, 45, 123);
            assertEquals("2004-06-14 00:00:00.000", isoUtc(DateUtil.getStartOfDay(noon, utcCalendar())));
        }

        @Test
        public void endOfDayIsTheLastMillisecondSoNoEntryFallsBetweenTwoDays() {
            Date noon = dateIn(UTC, 2004, Calendar.JUNE, 14, 12, 30, 45, 123);
            assertEquals("2004-06-14 23:59:59.999", isoUtc(DateUtil.getEndOfDay(noon, utcCalendar())));
        }

        @Test
        public void dayBoundariesFollowTheCalendarsZoneNotTheServersZone() {
            // 2004-06-14T20:00Z is already the 15th in Sydney, so the start of
            // "that day" there is 2004-06-14T14:00Z. Getting this wrong shifts
            // a weblog's archive pages by one day for half the world.
            Date evening = Date.from(java.time.Instant.parse("2004-06-14T20:00:00Z"));
            Calendar sydney = Calendar.getInstance(SYDNEY, Locale.US);
            assertEquals("2004-06-14 14:00:00.000", isoUtc(DateUtil.getStartOfDay(evening, sydney)));
        }

        @Test
        public void startOfHourAndMinuteZeroOnlyTheSmallerFields() {
            Date d = dateIn(UTC, 2004, Calendar.JUNE, 14, 12, 30, 45, 123);
            assertEquals("2004-06-14 12:00:00.000", isoUtc(DateUtil.getStartOfHour(d, utcCalendar())));
            assertEquals("2004-06-14 12:30:00.000", isoUtc(DateUtil.getStartOfMinute(d, utcCalendar())));
        }

        @Test
        public void endOfHourAndMinuteFillTheSmallerFields() {
            Date d = dateIn(UTC, 2004, Calendar.JUNE, 14, 12, 30, 45, 123);
            assertEquals("2004-06-14 12:59:59.999", isoUtc(DateUtil.getEndOfHour(d, utcCalendar())));
            assertEquals("2004-06-14 12:30:59.999", isoUtc(DateUtil.getEndOfMinute(d, utcCalendar())));
        }

        @Test
        public void startOfMonthIsTheFirstMillisecondOfTheFirstDay() {
            Date d = dateIn(UTC, 2004, Calendar.JUNE, 14, 12, 30, 45, 123);
            assertEquals("2004-06-01 00:00:00.000", isoUtc(DateUtil.getStartOfMonth(d, utcCalendar())));
        }

        @Test
        public void endOfMonthLandsOnTheRealLastDayIncludingLeapFebruary() {
            // The implementation walks to the 1st, adds a month and steps back
            // a day, so month lengths and leap years come out right without a
            // table. February is the case that catches a naive "day 30/31".
            assertEquals("2004-02-29 23:59:59.999",
                    isoUtc(DateUtil.getEndOfMonth(dateIn(UTC, 2004, Calendar.FEBRUARY, 10, 8, 0, 0, 0), utcCalendar())));
            assertEquals("2003-02-28 23:59:59.999",
                    isoUtc(DateUtil.getEndOfMonth(dateIn(UTC, 2003, Calendar.FEBRUARY, 10, 8, 0, 0, 0), utcCalendar())));
            assertEquals("2004-12-31 23:59:59.999",
                    isoUtc(DateUtil.getEndOfMonth(dateIn(UTC, 2004, Calendar.DECEMBER, 10, 8, 0, 0, 0), utcCalendar())));
        }

        @Test
        public void noonOfDayIsMiddayExactly() {
            Date d = dateIn(UTC, 2004, Calendar.JUNE, 14, 23, 59, 59, 999);
            assertEquals("2004-06-14 12:00:00.000", isoUtc(DateUtil.getNoonOfDay(d, utcCalendar())));
        }

        @Test
        public void aNullDayMeansToday() {
            // Callers rely on this for "the current archive page".
            Calendar cal = utcCalendar();
            Date start = DateUtil.getStartOfDay(null, cal);
            assertNotNull(start);
            assertTrue(isoUtc(start).endsWith("00:00:00.000"),
                    "getStartOfDay(null) must still return the start of a day, got " + isoUtc(start));
            assertTrue(isoUtc(DateUtil.getEndOfDay(null, utcCalendar())).endsWith("23:59:59.999"));
            assertNotNull(DateUtil.getStartOfMonth(null, utcCalendar()));
            assertNotNull(DateUtil.getEndOfMonth(null, utcCalendar()));
            assertNotNull(DateUtil.getStartOfHour(null, utcCalendar()));
            assertNotNull(DateUtil.getStartOfMinute(null, utcCalendar()));
            assertNotNull(DateUtil.getNoonOfDay(null, utcCalendar()));
        }

        @Test
        public void theEndOfHourAndMinuteHelpersReturnNullForANullDayInsteadOfToday() {
            // Deliberate asymmetry with every other helper on this class, and
            // a trap for callers: these two hand back what they were given.
            assertNull(DateUtil.getEndOfHour(null, utcCalendar()));
            assertNull(DateUtil.getEndOfMinute(null, utcCalendar()));
            assertNull(DateUtil.getEndOfHour(null));
            assertNull(DateUtil.getEndOfMinute(null));
        }

        @Test
        public void theEndOfHourAndMinuteHelpersReturnTheDateUnchangedWithoutACalendar() {
            Date d = dateIn(UTC, 2004, Calendar.JUNE, 14, 12, 30, 45, 123);
            assertEquals(d, DateUtil.getEndOfHour(d, null));
            assertEquals(d, DateUtil.getEndOfMinute(d, null));
        }

        @Test
        public void theSingleArgumentHelpersUseTheDefaultZone() {
            // Same instant, expressed with the JVM's own calendar.
            Date d = new Date();
            assertEquals(DateUtil.getStartOfDay(d, Calendar.getInstance()), DateUtil.getStartOfDay(d));
            assertEquals(DateUtil.getEndOfDay(d, Calendar.getInstance()), DateUtil.getEndOfDay(d));
            assertEquals(DateUtil.getStartOfHour(d, Calendar.getInstance()), DateUtil.getStartOfHour(d));
            assertEquals(DateUtil.getEndOfHour(d, Calendar.getInstance()), DateUtil.getEndOfHour(d));
            assertEquals(DateUtil.getStartOfMinute(d, Calendar.getInstance()), DateUtil.getStartOfMinute(d));
            assertEquals(DateUtil.getEndOfMinute(d, Calendar.getInstance()), DateUtil.getEndOfMinute(d));
            assertEquals(DateUtil.getStartOfMonth(d, Calendar.getInstance()), DateUtil.getStartOfMonth(d));
            assertEquals(DateUtil.getEndOfMonth(d, Calendar.getInstance()), DateUtil.getEndOfMonth(d));
        }
    }

    @Nested
    class FormattingAndParsing {

        @Test
        public void formatReturnsEmptyStringRatherThanNpeOnMissingArguments() {
            // Templates call this with an entry's optional update-time.
            assertEquals("", DateUtil.format(null, new SimpleDateFormat("yyyy")));
            assertEquals("", DateUtil.format(new Date(), null));
            assertEquals("", DateUtil.format(null, null));
        }

        @Test
        public void parseReadsAValueBackWithTheGivenFormat() throws ParseException {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
            fmt.setTimeZone(UTC);
            assertEquals("2004-06-14 00:00:00.000", isoUtc(DateUtil.parse("20040614", fmt)));
        }

        @Test
        public void parseReturnsNullForMissingInputAndThrowsForGarbage() {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
            assertNull(assertParsedOrNull(null, fmt));
            assertNull(assertParsedOrNull("", fmt));
            assertNull(assertParsedOrNull("20040614", null));
            assertThrows(ParseException.class, () -> DateUtil.parse("not a date", fmt));
        }

        @Test
        public void parseIso8601ReadsAFeedTimestamp() throws Exception {
            // Delegates to ISO8601DateParser; this is the entry point the rest
            // of Pressroom calls, so it needs its own coverage.
            assertEquals("2004-06-14 19:20:30.000", isoUtc(DateUtil.parseIso8601("2004-06-14T19:20:30Z")));
        }

        private Date assertParsedOrNull(String value, SimpleDateFormat fmt) {
            try {
                return DateUtil.parse(value, fmt);
            } catch (ParseException e) {
                throw new AssertionError("DateUtil.parse should return null, not throw, for " + value, e);
            }
        }

        @Test
        public void theEightAndSixCharacterStampsRespectTheSuppliedZone() {
            // These build the /date/YYYYMMDD permalinks. 22:00Z on the 14th is
            // already the 15th in Sydney, so a weblog in that zone must get the
            // 15th or its own permalinks will not resolve.
            Date d = Date.from(java.time.Instant.parse("2004-06-14T22:00:00Z"));
            assertEquals("20040614", DateUtil.format8chars(d, UTC));
            assertEquals("20040615", DateUtil.format8chars(d, SYDNEY));

            // Same story for the month stamp, which needs a date near a month
            // boundary to show the zone actually being applied.
            Date endOfMonth = Date.from(java.time.Instant.parse("2004-06-30T22:00:00Z"));
            assertEquals("200406", DateUtil.format6chars(endOfMonth, UTC));
            assertEquals("200407", DateUtil.format6chars(endOfMonth, SYDNEY));
        }

        @Test
        public void theEightAndSixCharacterStampsUseTheDefaultZoneWhenNoneIsGiven() {
            Date d = new Date();
            assertEquals(DateUtil.format8chars(d, TimeZone.getDefault()), DateUtil.format8chars(d));
            assertEquals(DateUtil.format6chars(d, TimeZone.getDefault()), DateUtil.format6chars(d));
        }

        @Test
        public void iso8601GetsTheColonInsertedIntoItsZoneOffset() {
            // SimpleDateFormat writes "+0200"; ISO 8601 needs "+02:00", and
            // feed readers reject the unpunctuated form.
            String formatted = DateUtil.formatIso8601(new Date());
            assertTrue(formatted.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}"),
                    "formatIso8601 produced '" + formatted + "', which is not a valid ISO 8601 "
                            + "timestamp; feed consumers will drop the date.");
        }

        @Test
        public void iso8601OfNullIsEmptyNotAnException() {
            assertEquals("", DateUtil.formatIso8601(null));
        }

        @Test
        public void iso8601DayIsJustTheDate() {
            Date d = dateIn(TimeZone.getDefault(), 2004, Calendar.JUNE, 14, 12, 0, 0, 0);
            assertEquals("2004-06-14", DateUtil.formatIso8601Day(d));
        }

        @Test
        public void rfc822UsesEnglishNamesWhateverTheServerLocaleIs() {
            // ROL-725/ROL-628: on a server whose default locale is not English
            // the RSS pubDate came out as "Mo, 14 Jun ...", which is not valid
            // RFC 822 and breaks aggregators. The formatter therefore pins
            // Locale.US, and this test proves it by running under a different
            // default locale.
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.GERMANY);
                SimpleDateFormat fmt = DateUtil.getRfc822DateFormat();
                fmt.setTimeZone(UTC);
                assertEquals("Mon, 14 Jun 2004 12:00:00 +0000",
                        DateUtil.format(Date.from(java.time.Instant.parse("2004-06-14T12:00:00Z")), fmt));
            } finally {
                Locale.setDefault(original);
            }
        }

        @Test
        public void machineStampsDoNotFollowTheServerLocaleOrCalendar() {
            // A server whose default locale is Thai would otherwise write the
            // Buddhist year (2547) into /date/YYYYMMDD permalinks and ISO feed
            // dates, which no client can read back. Each of these formatters
            // is a wire format and is pinned to Locale.ROOT.
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("th-TH"));
                Date d = dateIn(TimeZone.getDefault(), 2004, Calendar.JUNE, 14, 12, 0, 0, 0);
                assertEquals("20040614", DateUtil.get8charDateFormat().format(d));
                assertEquals("200406", DateUtil.get6charDateFormat().format(d));
                assertEquals("2004-06-14", DateUtil.getIso8601DayDateFormat().format(d));
                assertTrue(DateUtil.getIso8601DateFormat().format(d).startsWith("2004-06-14T"));
                assertTrue(DateUtil.defaultTimestampFormat().format(d).startsWith("2004-06-14 "));
            } finally {
                Locale.setDefault(original);
            }
        }

        @Test
        public void rfc822OfARealDateIsAWellFormedPubDate() {
            // Whatever zone the server runs in, the shape has to be the one
            // RSS 2.0 requires for <pubDate>.
            String formatted = DateUtil.formatRfc822(new Date());
            assertTrue(formatted.matches(
                            "(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \\d{1,2} \\w{3} \\d{4} \\d{2}:\\d{2}:\\d{2} [+-]\\d{4}"),
                    "formatRfc822 produced '" + formatted + "', which is not a valid RFC 822 "
                            + "date; aggregators drop or misdate entries with such a pubDate.");
        }

        @Test
        public void rfc822OfANullDateIsEmpty() {
            assertEquals("", DateUtil.formatRfc822(null));
        }

        @Test
        public void theFriendlyFormatsKeepTheirDocumentedShape() {
            // These strings appear in the admin UI; the minimal form drops
            // leading zeroes and uses a two digit year.
            Date d = dateIn(TimeZone.getDefault(), 2004, Calendar.JUNE, 4, 21, 20, 30, 0);
            assertEquals("4.6.04", DateUtil.minimalDate(d));
            assertEquals("4.6.04", DateUtil.friendlyDate(d));
            assertEquals("04.06.2004", DateUtil.fullDate(d));
            assertEquals("04.06.2004", DateUtil.friendlyDate(d, false));
            assertEquals("04.06.2004 21:20:30", DateUtil.friendlyTimestamp(d));
            assertEquals("2004-06-04 21:20:30.000", DateUtil.defaultTimestamp(d));
        }

        @Test
        public void defaultDateIsTheMinimalFormatDespiteTheName() {
            // defaultDateFormat() delegates to friendlyDateFormat(true), so
            // "default" here means the short form, not "dd.MM.yyyy".
            Date d = dateIn(TimeZone.getDefault(), 2004, Calendar.JUNE, 4, 12, 0, 0, 0);
            assertEquals("4.6.04", DateUtil.defaultDate(d));
        }

        @Test
        public void everyFormatterHandsBackAFreshInstanceBecauseTheyAreNotThreadSafe() {
            // SimpleDateFormat is mutable and not thread safe; sharing one
            // between concurrent request threads corrupts the output. Each
            // accessor must therefore construct a new one.
            String why = "Date formatters must not be shared: SimpleDateFormat is not thread "
                    + "safe and Pressroom formats dates from concurrent request threads.";
            assertNotSame(DateUtil.get8charDateFormat(), DateUtil.get8charDateFormat(), why);
            assertNotSame(DateUtil.get6charDateFormat(), DateUtil.get6charDateFormat(), why);
            assertNotSame(DateUtil.getIso8601DateFormat(), DateUtil.getIso8601DateFormat(), why);
            assertNotSame(DateUtil.getIso8601DayDateFormat(), DateUtil.getIso8601DayDateFormat(), why);
            assertNotSame(DateUtil.getRfc822DateFormat(), DateUtil.getRfc822DateFormat(), why);
            assertNotSame(DateUtil.defaultTimestampFormat(), DateUtil.defaultTimestampFormat(), why);
            assertNotSame(DateUtil.friendlyTimestampFormat(), DateUtil.friendlyTimestampFormat(), why);
            assertNotSame(DateUtil.minimalDateFormat(), DateUtil.minimalDateFormat(), why);
            assertNotSame(DateUtil.fullDateFormat(), DateUtil.fullDateFormat(), why);
            assertNotSame(DateUtil.defaultDateFormat(), DateUtil.defaultDateFormat(), why);
        }
    }

    @Nested
    class WeblogUrlDates {

        @Test
        public void anEightCharacterStampParsesToThatDay() {
            // The stamp is parsed with the server's own calendar, so it is
            // read back with an ordinary default-zone formatter.
            Date parsed = DateUtil.parseWeblogURLDateString("20040614", UTC, Locale.US);
            assertEquals("2004-06-14", new SimpleDateFormat("yyyy-MM-dd").format(parsed));
        }

        @Test
        public void aSixCharacterStampParsesToTheFirstOfThatMonth() {
            Date parsed = DateUtil.parseWeblogURLDateString("200406", UTC, Locale.US);
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
            assertEquals("2004-06-01", fmt.format(parsed));
        }

        @Test
        public void aDateInTheFutureIsClampedToNow() {
            // /date/29990101 must not return an empty page dated in the year
            // 2999; it collapses to today so the archive still renders.
            Date parsed = DateUtil.parseWeblogURLDateString("29990101", UTC, Locale.US);
            assertTrue(parsed.getTime() <= System.currentTimeMillis() + 1000,
                    "A future date in the URL must be clamped to now, got " + parsed);
        }

        @Test
        public void anythingThatIsNotSixOrEightDigitsFallsBackToNow() {
            long before = System.currentTimeMillis() - 1000;
            for (String bad : new String[]{null, "", "2004-06", "2004", "200406140", "20040a14"}) {
                Date parsed = DateUtil.parseWeblogURLDateString(bad, UTC, Locale.US);
                assertTrue(parsed.getTime() >= before,
                        "Malformed URL date '" + bad + "' should fall back to the current "
                                + "date, not to the epoch; got " + parsed);
            }
        }
    }

    @Nested
    class DateRanges {

        private final Date early = new Date(1_000_000L);
        private final Date late = new Date(2_000_000L);

        @Test
        public void aRangeIsValidWhenTheEndIsAfterTheStart() {
            assertTrue(DateUtil.isValidDateRange(early, late));
            assertFalse(DateUtil.isValidDateRange(late, early));
        }

        @Test
        public void equalDatesAreValidUnlessTheCallerSaysOtherwise() {
            // The comment search form uses the strict form to reject a
            // zero-length window.
            assertTrue(DateUtil.isValidDateRange(early, new Date(early.getTime())));
            assertFalse(DateUtil.isValidDateRange(early, new Date(early.getTime()), false));
        }

        @Test
        public void aMissingEndpointIsNeverAValidRange() {
            assertFalse(DateUtil.isValidDateRange(null, late));
            assertFalse(DateUtil.isValidDateRange(early, null));
            assertFalse(DateUtil.isValidDateRange(null, null));
        }
    }

    @Test
    public void nowReturnsTheCurrentTimeAsASqlTimestamp() {
        long before = System.currentTimeMillis();
        java.sql.Timestamp now = DateUtil.now();
        assertTrue(now.getTime() >= before && now.getTime() <= System.currentTimeMillis(),
                "DateUtil.now() must be the current time; it is written straight into "
                        + "created/updated columns.");
    }

    @Test
    public void millisInDayMatchesAWholeDay() {
        // Used as a divisor in hit-count reset arithmetic.
        assertEquals(24L * 60 * 60 * 1000, DateUtil.MILLIS_IN_DAY);
    }
}
