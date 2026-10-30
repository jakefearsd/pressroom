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

import org.junit.jupiter.api.Test;

import java.text.ParseException;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the ISO 8601 parsing used for feed and Dublin Core dates.
 *
 * <p>These strings cross the wire, so both directions have to agree with the
 * spec exactly: an instant that is off by an hour (or, as it was, by the whole
 * minutes field) silently misorders entries in every aggregator that reads the
 * feed, and nothing in Pressroom notices.
 */
public class ISO8601DateParserTest {

    /** 2004-06-14T19:20:30Z, the example from the class javadoc. */
    private static final Date JUNE_14_2004_1920_30_UTC = Date.from(Instant.parse("2004-06-14T19:20:30Z"));

    @Test
    public void parsesAndRendersTheSameWhateverTheServerLocaleIs() {
        // Under a Thai default locale SimpleDateFormat switches to the Buddhist
        // calendar, so an unpinned formatter wrote 2547 into the feed.
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("th-TH"));
            assertEquals(JUNE_14_2004_1920_30_UTC, assertParsed("2004-06-14T19:20:30Z"));
            assertEquals("2004-06-14T19:20:30+00:00", ISO8601DateParser.toString(JUNE_14_2004_1920_30_UTC));
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    @Test
    public void parsesUtcDesignator() {
        assertEquals(JUNE_14_2004_1920_30_UTC, assertParsed("2004-06-14T19:20:30Z"));
    }

    @Test
    public void parsesPositiveAndNegativeOffsets() {
        // 19:20:30+01:00 is 18:20:30 UTC; if the offset were ignored the
        // instant would land an hour out.
        assertEquals(Date.from(Instant.parse("2004-06-14T18:20:30Z")),
                assertParsed("2004-06-14T19:20:30+01:00"));
        assertEquals(Date.from(Instant.parse("2004-06-14T20:20:30Z")),
                assertParsed("2004-06-14T19:20:30-01:00"));
    }

    @Test
    public void rendersAnInstantAsUtcWithEveryFieldIntact() {
        // Regression guard: the formatter used to slice fixed-width chunks out
        // of a zone-formatted string and lost the minutes, turning 19:20:30
        // into 19:30 -- a valid looking timestamp 10 minutes in the future.
        assertEquals("2004-06-14T19:20:30+00:00", ISO8601DateParser.toString(JUNE_14_2004_1920_30_UTC));
    }

    @Test
    public void renderedOutputCanBeParsedBackToTheSameInstant() {
        Date original = Date.from(Instant.parse("1999-12-31T23:59:59Z"));
        assertEquals(original, assertParsed(ISO8601DateParser.toString(original)));
    }

    @Test
    public void rendersMidnightAndSingleDigitFieldsZeroPadded() {
        assertEquals("2004-01-02T03:04:05+00:00",
                ISO8601DateParser.toString(Date.from(Instant.parse("2004-01-02T03:04:05Z"))));
    }

    @Test
    public void rejectsInputThatIsNotADateTime() {
        assertThrows(ParseException.class, () -> ISO8601DateParser.parse("not-a-date-at-all"));
    }

    @Test
    public void rejectsATruncatedTimestamp() {
        // A date-only value ("1997-07-16") is legal ISO 8601 but this parser
        // only handles the full form; it must fail loudly rather than return
        // some arbitrary instant.
        assertThrows(Exception.class, () -> ISO8601DateParser.parse("1997-07-16"));
    }

    private static Date assertParsed(String input) {
        try {
            return ISO8601DateParser.parse(input);
        } catch (ParseException e) {
            throw new AssertionError("Could not parse the ISO 8601 value '" + input
                    + "'. Feeds and Dublin Core metadata use exactly this form, so a parse "
                    + "failure here means those dates are dropped.", e);
        }
    }
}
