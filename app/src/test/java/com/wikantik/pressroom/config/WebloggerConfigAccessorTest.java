package com.wikantik.pressroom.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the static config accessors answer, including when they are asked for
 * something that is not there or is not the type asked for.
 *
 * <p>The typed accessors disagree with each other about malformed input, and
 * that disagreement is pinned here deliberately rather than smoothed over: a
 * property that is not a boolean reads as {@code false} and says nothing,
 * while a property that is not an int throws out of whatever was starting up
 * at the time. Both are defensible on their own -- silently defaulting is
 * friendlier, failing fast is safer -- but a reader who knows one and assumes
 * the other will be wrong. Changing either is a behavioural decision, not a
 * cleanup, so these tests describe what is rather than what might be nicer.
 *
 * <p>The assertions establish their own preconditions from the live config
 * rather than hard-coding values from {@code pressroom.properties}, so that
 * retuning a default cannot quietly turn one of these into a test of nothing.
 */
class WebloggerConfigAccessorTest {

    private static final String ABSENT = "no.such.property.exists.anywhere";

    /** A property that is present and is definitely not "true". */
    private static final String NON_BOOLEAN = "authentication.method";

    /** A property that is present and is definitely not a number. */
    private static final String NON_NUMERIC = "authentication.method";

    // --- strings ----------------------------------------------------------

    @Test
    public void anAbsentPropertyIsNull() {
        assertNull(WebloggerConfig.getProperty(ABSENT),
                "A property nobody set reads as null, not as an empty string");
    }

    @Test
    public void anAbsentPropertyTakesTheDefault() {
        assertEquals("fallback", WebloggerConfig.getProperty(ABSENT, "fallback"));
    }

    @Test
    public void aPresentPropertyIgnoresTheDefault() {
        String direct = WebloggerConfig.getProperty(NON_BOOLEAN);
        assertNotNull(direct, NON_BOOLEAN + " must be set for this test to mean anything");
        assertEquals(direct, WebloggerConfig.getProperty(NON_BOOLEAN, "fallback"),
                "A property that is present must not be replaced by the default");
    }

    @Test
    public void bothOverloadsTrimTheSameWay() {
        // they are separate implementations, and only one of them trimming
        // would make "size = 400" work in one call site and not another
        assertEquals(WebloggerConfig.getProperty(NON_BOOLEAN),
                WebloggerConfig.getProperty(NON_BOOLEAN, "fallback"),
                "The one-argument and two-argument reads must agree on a present value");
    }

    // --- booleans ---------------------------------------------------------

    @Test
    public void anAbsentBooleanIsFalseUnlessToldOtherwise() {
        assertFalse(WebloggerConfig.getBooleanProperty(ABSENT));
        assertTrue(WebloggerConfig.getBooleanProperty(ABSENT, true),
                "and takes the supplied default when there is one");
    }

    @Test
    public void aPropertyThatIsNotABooleanReadsAsFalseRatherThanFailing() {
        String raw = WebloggerConfig.getProperty(NON_BOOLEAN);
        assertNotNull(raw, NON_BOOLEAN + " must be set for this test to mean anything");
        assertFalse("true".equalsIgnoreCase(raw),
                NON_BOOLEAN + " must not literally be \"true\" for this test to mean anything");

        assertFalse(WebloggerConfig.getBooleanProperty(NON_BOOLEAN),
                "Boolean.valueOf is the conversion, so anything that is not \"true\" is "
                        + "false -- including yes/on/1. An operator who writes "
                        + "\"...enabled=1\" turns the feature OFF and is told nothing");
    }

    @Test
    public void theDefaultDoesNotRescueAValueThatIsPresentButNotABoolean() {
        assertFalse(WebloggerConfig.getBooleanProperty(NON_BOOLEAN, true),
                "The default applies only when the property is absent. A present-but-"
                        + "unparseable value still reads false, defeating the default");
    }

    // --- ints -------------------------------------------------------------

    @Test
    public void anAbsentIntIsZeroUnlessToldOtherwise() {
        assertEquals(0, WebloggerConfig.getIntProperty(ABSENT));
        assertEquals(7, WebloggerConfig.getIntProperty(ABSENT, 7),
                "and takes the supplied default when there is one");
    }

    @Test
    public void aPropertyThatIsNotAnIntThrowsRatherThanTakingTheDefault() {
        String raw = WebloggerConfig.getProperty(NON_NUMERIC);
        assertNotNull(raw, NON_NUMERIC + " must be set for this test to mean anything");

        assertThrows(NumberFormatException.class,
                () -> WebloggerConfig.getIntProperty(NON_NUMERIC, 7),
                "Unlike the boolean accessor, a present-but-unparseable int is not "
                        + "defaulted -- Integer.parseInt throws, and it throws out of "
                        + "whatever was reading config at the time, which is usually "
                        + "startup. A typo in a numeric property is a failed boot, not a "
                        + "silently wrong setting");
    }

    // --- enums ------------------------------------------------------------

    @Test
    public void theAuthenticationMethodResolvesToAnEnum() {
        assertNotNull(WebloggerConfig.getAuthMethod(),
                "authentication.method must resolve; an unsupported value is meant to fail "
                        + "loudly at startup rather than silently behave like db");
    }
}
