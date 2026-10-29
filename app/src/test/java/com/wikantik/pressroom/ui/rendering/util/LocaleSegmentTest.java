package com.wikantik.pressroom.ui.rendering.util;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one rule that decides whether a url segment is a locale.
 *
 * <p>It used to be two: WeblogRequest and WeblogRequestMapper each carried a
 * byte-for-byte identical copy, one deciding how a url is routed and the other
 * how it is parsed. They agreed only because nobody had edited either, and CPD
 * could not see it -- the block is around a hundred tokens against a
 * two-hundred-token gate. These tests exist so the rule has somewhere to be
 * asserted now that it has somewhere to live.
 */
class LocaleSegmentTest {

    @Test
    void aTwoLetterLanguageIsALocale() {
        assertTrue(LocaleSegment.isLocale("en"));
        assertTrue(LocaleSegment.isLocale("de"));
    }

    @Test
    void aLanguageAndCountryIsALocale() {
        assertTrue(LocaleSegment.isLocale("en_US"));
        assertTrue(LocaleSegment.isLocale("pt_BR"));
    }

    @Test
    void capitalisationIsNotChecked() {
        assertTrue(LocaleSegment.isLocale("EN_us"),
                "the rule is deliberately lax about case; Locale sorts that out later");
    }

    @Test
    void nothingIsNotALocale() {
        assertFalse(LocaleSegment.isLocale(null));
        assertFalse(LocaleSegment.isLocale(""));
    }

    @Test
    void onlyTwoOrFiveCharacterSegmentsAreConsidered() {
        assertFalse(LocaleSegment.isLocale("e"), "one character");
        assertFalse(LocaleSegment.isLocale("eng"), "a three-letter language code");
        assertFalse(LocaleSegment.isLocale("en_USA"), "six characters");
    }

    @Test
    void theSeparatorIsAnUnderscoreNotAHyphen() {
        assertFalse(LocaleSegment.isLocale("en-US"),
                "en-US is the http spelling; this url scheme uses en_US, and reading the "
                        + "hyphenated form as a locale would change what every later "
                        + "segment of the url means");
    }

    @Test
    void aFiveCharacterSegmentWithoutASeparatorIsNotALocale() {
        assertFalse(LocaleSegment.isLocale("about"),
                "five letters alone is a page slug, not a locale");
    }

    @Test
    void anEmptyHalfIsNotALocale() {
        assertFalse(LocaleSegment.isLocale("_a"), "no language");
        assertFalse(LocaleSegment.isLocale("a_"), "no country, and a one-letter language");
        assertFalse(LocaleSegment.isLocale("__"), "neither");
    }

    /**
     * The consequence worth knowing, rather than a rule of its own: because a
     * two-letter segment is read as a locale before anything else looks at it,
     * a static page whose slug is exactly two letters can never be reached.
     * {@code /myblog/de} is the weblog home in German.
     */
    @Test
    void aTwoLetterPageSlugCannotBeDistinguishedFromALocale() {
        assertTrue(LocaleSegment.isLocale("de"),
                "which is why a page slugged \"de\" is unreachable -- see "
                        + "WeblogPathInfoParsingTest");
    }

    // --- turning a locale string into a Locale ----------------------------

    /**
     * Three classes parsed this independently and disagreed about a three-part
     * string. The pagers handled it and carried a comment about the
     * NullPointerException that not handling it caused;
     * WeblogRequest.getLocaleInstance returned null for it -- the same bug that
     * comment describes, in the copy that never got the fix.
     */
    @Test
    void aThreePartLocaleKeepsItsVariant() {
        Locale locale = LocaleSegment.toLocale("en_US_POSIX");

        assertNotNull(locale, "null here reaches I18nMessages.getMessages, which "
                + "dereferences its argument unconditionally");
        assertEquals("en", locale.getLanguage());
        assertEquals("US", locale.getCountry());
        assertEquals("POSIX", locale.getVariant());
    }

    @Test
    void aLanguageAloneBecomesALanguageOnlyLocale() {
        Locale locale = LocaleSegment.toLocale("de");

        assertEquals("de", locale.getLanguage());
        assertEquals("", locale.getCountry());
    }

    @Test
    void aLanguageAndCountryBecomeBoth() {
        Locale locale = LocaleSegment.toLocale("pt_BR");

        assertEquals("pt", locale.getLanguage());
        assertEquals("BR", locale.getCountry());
    }

    @Test
    void moreThanThreePartsUsesTheFirstThree() {
        Locale locale = LocaleSegment.toLocale("en_US_POSIX_EXTRA");

        assertEquals("POSIX", locale.getVariant(),
                "a fourth part is dropped rather than throwing -- callers want a Locale, "
                        + "not an exception, from url text");
    }

    @Test
    void noLocaleStringMeansNoLocale() {
        assertNull(LocaleSegment.toLocale(null),
                "every caller reads null as \"fall back to the weblog's own locale\"");
    }
}
