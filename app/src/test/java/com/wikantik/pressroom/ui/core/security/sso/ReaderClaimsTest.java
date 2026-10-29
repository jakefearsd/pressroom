package com.wikantik.pressroom.ui.core.security.sso;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReaderClaimsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Ada Lovelace|ada@example.com|Ada Lovelace",
        "'  Ada \t\n  Lovelace  '|ada@example.com|Ada Lovelace",
        "'\u0000\u0007'|ada@example.com|ada",
        "''|ada@example.com|ada",
        "'   '|@example.com|Reader"})
    void displayNameRule(String name, String email, String expected) {
        assertEquals(expected, new ReaderClaims("i", "s", email, true, name).displayName());
    }

    @Test
    void displayNameIsCutAt100CodePoints() {
        String longName = "😀".repeat(150);
        String shown = new ReaderClaims("i", "s", "a@b.c", true, longName).displayName();
        assertEquals(100, shown.codePointCount(0, shown.length()));
    }

    @Test
    void nullNameAndEmailFallBackToReader() {
        assertEquals("Reader", new ReaderClaims("i", "s", null, true, null).displayName());
    }

    @Test
    void multiValuedClaimsTakeTheFirstValue() {
        ReaderClaims c = ReaderClaims.from("i", "s", Map.of("email", List.of("a@b.c", "x@y.z"),
                "email_verified", List.of(Boolean.TRUE), "name", List.of("First", "Second")));
        assertEquals("a@b.c", c.email());
        assertTrue(c.emailVerified());
        assertEquals("First", c.name());
    }

    @Test
    void firstScalarOfEmptyCollectionIsNull() {
        assertNull(ReaderClaims.firstScalar(List.of()));
        assertEquals("x", ReaderClaims.firstScalar("x"));
    }

    @Test
    void usableClaimsPass() throws Exception {
        ReaderClaims.from("i", "s", Map.of("email", "a@b.c", "email_verified", true)).requireUsable();
    }

    @ParameterizedTest
    @MethodSource("refusedClaims")
    void unusableClaimsAreRefused(Map<String, Object> claims) {
        assertThrows(ReaderSignInRefused.class, () -> ReaderClaims.from("i", "s", claims).requireUsable());
    }

    static Stream<Map<String, Object>> refusedClaims() {
        return Stream.of(
            Map.of("email_verified", true),
            Map.of("email", "  ", "email_verified", true),
            Map.of("email", "a".repeat(251) + "@b.cd", "email_verified", true),
            Map.of("email", "a@b.c", "email_verified", false),
            Map.of("email", "a@b.c"),
            Map.of("email", "a@b.c", "email_verified", "true"));
    }
}
