package org.apache.roller.weblogger.ui.restapi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Review round 1, Important 2: a patchable string that overflows its
 * backing varchar column reaches JPA's INSERT/UPDATE and Postgres answers
 * "value too long for type character varying(N)", wrapped in a bare
 * WebloggerException -- an unguarded 500. These limits are sourced from
 * {@code bin/db/migrations/V002__baseline_schema.sql}.
 */
class ColumnLimitsTest {

    @Test
    void aValueAtTheLimitIsAccepted() {
        assertDoesNotThrow(() -> ColumnLimits.requireMaxLength("screenName", "x".repeat(255), 255));
    }

    @Test
    void aValueOneOverTheLimitIsRejected() {
        assertThrows(ApiException.class,
                () -> ColumnLimits.requireMaxLength("screenName", "x".repeat(256), 255));
    }

    @Test
    void aNullValueIsNotRejectedByLengthAlone() {
        // Blank/null handling is each caller's own business (PATCH
        // "unchanged" semantics vs. POST "required" semantics differ) --
        // ColumnLimits only ever answers the length question.
        assertDoesNotThrow(() -> ColumnLimits.requireMaxLength("screenName", null, 255));
    }

    @Test
    void theNamedFieldAppearsInTheRejectionMessage() {
        ApiException ex = assertThrows(ApiException.class,
                () -> ColumnLimits.requireMaxLength("locale", "x".repeat(21), 20));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("locale"));
    }

    @Test
    void aRefusalNamesItsField() {
        ApiException ex = assertThrows(ApiException.class,
                () -> ColumnLimits.requireMaxLength("metaTitle", "x".repeat(256), 255));

        assertEquals("'metaTitle' must be 255 characters or fewer.", ex.getMessage());
        assertEquals("metaTitle", ex.getFieldErrors().get(0).field());
    }
}
