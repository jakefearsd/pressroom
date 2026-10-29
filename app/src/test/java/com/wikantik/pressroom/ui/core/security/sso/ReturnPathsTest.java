package com.wikantik.pressroom.ui.core.security.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Spec criterion 6: a return value that is not a path on this site falls back to the site root. */
class ReturnPathsTest {

    @ParameterizedTest
    @CsvSource(value = {
        "/roller/blog/entry/x|/roller|/roller/blog/entry/x",
        "/blog/|''|/blog/",
        "https://evil.example/|/roller|/roller/",
        "//evil.example/|''|/",
        "/\\evil.example|''|/",
        "/other/x|/roller|/roller/",
        "/roller|/roller|/roller/",
        "''|/roller|/roller/"}, delimiter = '|')
    void sanitize(String raw, String ctx, String expected) {
        assertEquals(expected, ReturnPaths.sanitize(raw, ctx));
    }

    /**
     * Kept out of the CSV above: JUnit's CSV parser treats a raw CR as the end of
     * the record, so the case would arrive with one column instead of three.
     */
    @Test
    void aCarriageReturnOrLineFeedFallsBack() {
        assertEquals("/roller/", ReturnPaths.sanitize("/roller/a\rb", "/roller"));
        assertEquals("/roller/", ReturnPaths.sanitize("/roller/a\nb", "/roller"));
        assertEquals("/roller/", ReturnPaths.sanitize("/roller/a\r\nSet-Cookie: x=y", "/roller"));
    }

    @Test
    void nullAndOverlongFallBack() {
        assertEquals("/", ReturnPaths.sanitize(null, ""));
        assertEquals("/", ReturnPaths.sanitize("/" + "a".repeat(2000), ""));
        assertEquals("/" + "a".repeat(1999), ReturnPaths.sanitize("/" + "a".repeat(1999), ""),
                "2000 characters is still accepted");
    }

    @Test
    void everyControlCharacterFallsBack() {
        assertEquals("/", ReturnPaths.sanitize("/a\tb", ""));
        assertEquals("/", ReturnPaths.sanitize("/a\u0000b", ""));
        assertEquals("/", ReturnPaths.sanitize("/a\u007Fb", ""));
        assertEquals("/", ReturnPaths.sanitize("/a\u0085b", ""), "C1 controls are control characters too");
        assertEquals("/café/", ReturnPaths.sanitize("/café/", ""), "other non-ASCII is a path");
    }

    @Test
    void aNullContextPathIsTheRoot() {
        assertEquals("/blog/", ReturnPaths.sanitize("/blog/", null));
        assertEquals("/", ReturnPaths.sanitize("//evil.example/", null));
    }
}
