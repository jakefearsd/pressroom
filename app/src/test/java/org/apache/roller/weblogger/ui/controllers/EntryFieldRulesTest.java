package org.apache.roller.weblogger.ui.controllers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterisation test: it pins behaviour that already existed in
 * {@code EntryBean} before this extraction, it does not specify new
 * behaviour. The escaping asymmetry these rules encode is the sharpest trap
 * in the codebase: an ENTRY title is stored escaped (so themes emit it
 * bare), while a PAGE title is stored raw (so templates must escape at
 * render). Re-deriving either rule at a second call site is how that
 * asymmetry becomes stored XSS, which is why both live here and nowhere
 * else.
 */
class EntryFieldRulesTest {

    @Test
    void titlesAreStoredHtmlEscaped() {
        assertEquals("Cats &amp; Dogs", EntryFieldRules.escapeTitle("Cats & Dogs"));
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;",
                EntryFieldRules.escapeTitle("<script>alert(1)</script>"));
    }

    @Test
    void escapingIsNullSafe() {
        assertNull(EntryFieldRules.escapeTitle(null));
    }

    @Test
    void aBlankPubTimeMeansPublishNow() {
        assertNull(EntryFieldRules.parsePubTime(null, TimeZone.getTimeZone("UTC")));
        assertNull(EntryFieldRules.parsePubTime("", TimeZone.getTimeZone("UTC")));
        assertNull(EntryFieldRules.parsePubTime("   ", TimeZone.getTimeZone("UTC")));
    }

    /**
     * pubTimeLocal is wall-clock time in the WEBLOG's zone -- not the server's
     * and not the browser's. The same string in two zones is two instants.
     */
    @Test
    void theWallClockStringIsReadInTheWeblogsZone() {
        String wall = "2026-03-01T09:30";
        Timestamp utc = EntryFieldRules.parsePubTime(wall, TimeZone.getTimeZone("UTC"));
        Timestamp tokyo = EntryFieldRules.parsePubTime(wall, TimeZone.getTimeZone("Asia/Tokyo"));

        assertNotNull(utc);
        assertNotNull(tokyo);
        assertNotEquals(utc.getTime(), tokyo.getTime(),
                "identical wall clocks in different zones are different instants");
        assertEquals(9L * 3600_000L, utc.getTime() - tokyo.getTime(),
                "Tokyo is UTC+9, so its 09:30 happened nine hours earlier");
    }

    /**
     * A mistyped pubtime must block the save rather than silently publishing
     * "now" -- that was the old dateString parser's failure mode.
     */
    @Test
    void anUnparseableValueThrowsRatherThanDefaultingToNow() {
        assertThrows(DateTimeParseException.class,
                () -> EntryFieldRules.parsePubTime("not a date", TimeZone.getTimeZone("UTC")));
    }

    @Test
    void aParsedValueRoundTripsToTheSameLocalDateTime() {
        TimeZone zone = TimeZone.getTimeZone("America/New_York");
        Timestamp parsed = EntryFieldRules.parsePubTime("2026-07-04T13:45", zone);
        assertNotNull(parsed);
        LocalDateTime back = LocalDateTime.ofInstant(parsed.toInstant(), zone.toZoneId());
        assertEquals(LocalDateTime.of(2026, 7, 4, 13, 45), back);
    }

    private static final TimeZone BERLIN = TimeZone.getTimeZone("Europe/Berlin");

    @Test
    void anEventTimeIsWallClockInTheWeblogsZone() {
        assertEquals(Instant.parse("2026-08-02T17:30:00Z"),
                EntryFieldRules.parseEventTime("2026-08-02T19:30", BERLIN).toInstant());
        assertEquals(Instant.parse("2026-08-02T17:30:00Z"),
                EntryFieldRules.parseEventTime("2026-08-02T19:30:00", BERLIN).toInstant(), "seconds are accepted");
        assertNull(EntryFieldRules.parseEventTime("  ", BERLIN));
        assertThrows(DateTimeParseException.class,
                () -> EntryFieldRules.parseEventTime("2026-08-02T19:30+02:00", BERLIN));
    }

    @Test
    void formatWallClockIsTheInverseToTheMinute() {
        Timestamp start = Timestamp.from(Instant.parse("2026-08-02T17:30:00Z"));
        assertEquals("2026-08-02T19:30", EntryFieldRules.formatWallClock(start, BERLIN));
        assertEquals("", EntryFieldRules.formatWallClock(null, BERLIN));
    }

    @Test
    void endsBeforeStartOnlyWhenBothAreSet() {
        Timestamp a = Timestamp.from(Instant.parse("2026-08-02T17:30:00Z"));
        Timestamp b = Timestamp.from(Instant.parse("2026-08-02T20:00:00Z"));
        assertTrue(EntryFieldRules.endsBeforeStart(b, a));
        assertFalse(EntryFieldRules.endsBeforeStart(a, b));
        assertFalse(EntryFieldRules.endsBeforeStart(a, a));
        assertFalse(EntryFieldRules.endsBeforeStart(null, a));
        assertFalse(EntryFieldRules.endsBeforeStart(a, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "torrey-pines", "torrey_pines", "a", "2026-berlin-wall", "x1-y2_z3"})
    void lowerCaseWordsJoinedByOneSeparatorAreAValidAnchor(String anchor) {
        assertTrue(EntryFieldRules.isValidAnchor(anchor));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Torrey", "torrey pines", "torrey--pines", "-x", "x-", "", "stra\u00dfe", "a/b", "a.b", "a-_b"})
    void anythingElseIsNot(String anchor) {
        assertFalse(EntryFieldRules.isValidAnchor(anchor));
    }

    @Test
    void anAnchorMayBeAsLongAsTheColumnAndNoLonger() {
        assertTrue(EntryFieldRules.isValidAnchor("a".repeat(255)));
        assertFalse(EntryFieldRules.isValidAnchor("a".repeat(256)));
        assertFalse(EntryFieldRules.isValidAnchor(null));
    }
}
