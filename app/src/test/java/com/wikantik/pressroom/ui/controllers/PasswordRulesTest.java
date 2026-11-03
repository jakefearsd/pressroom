package com.wikantik.pressroom.ui.controllers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one password rule every path that sets a password applies: creating a
 * person, an admin changing one, the profile page and the emailed reset link.
 * Each path once carried its own copy, and two of them carried none.
 */
class PasswordRulesTest {

    @Test
    void eightCharactersIsTheFloor() {
        assertEquals(8, PasswordRules.MIN_LENGTH);
        assertFalse(PasswordRules.isLongEnough("1234567"));
        assertTrue(PasswordRules.isLongEnough("12345678"));
    }

    @Test
    void noPasswordIsNotLongEnough() {
        assertFalse(PasswordRules.isLongEnough(null));
        assertFalse(PasswordRules.isLongEnough(""));
    }
}
