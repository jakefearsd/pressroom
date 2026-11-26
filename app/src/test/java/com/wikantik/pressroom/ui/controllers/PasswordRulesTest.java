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

    private static com.wikantik.pressroom.pojos.User account(String hash) {
        com.wikantik.pressroom.pojos.User u = new com.wikantik.pressroom.pojos.User();
        u.setUserName("dora");
        u.setPassword(hash);
        return u;
    }

    private static org.springframework.security.crypto.password.PasswordEncoder withEncoder(
            org.springframework.security.crypto.password.PasswordEncoder encoder) {
        var old = com.wikantik.pressroom.ui.core.PressroomContext.getPasswordEncoder();
        com.wikantik.pressroom.ui.core.PressroomContext.setPasswordEncoder(encoder);
        return old;
    }

    @Test
    void currentPasswordMatchesOnlyTheStoredOne() {
        var real = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
        var old = withEncoder(real);
        try {
            var dora = account(real.encode("correct horse"));
            assertTrue(PasswordRules.matchesCurrent(dora, "correct horse"));
            assertFalse(PasswordRules.matchesCurrent(dora, "wrong horse"));
        } finally {
            withEncoder(old);
        }
    }

    /** Fails closed: nothing to compare, nothing typed, or no account is never a match. */
    @Test
    void missingPiecesNeverMatch() {
        var yes = org.mockito.Mockito.mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        org.mockito.Mockito.when(yes.matches(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        var old = withEncoder(yes);
        try {
            assertTrue(PasswordRules.matchesCurrent(account("h"), "x"));
            assertFalse(PasswordRules.matchesCurrent(null, "x"));
            assertFalse(PasswordRules.matchesCurrent(account("h"), null));
            assertFalse(PasswordRules.matchesCurrent(account("h"), ""));
            assertFalse(PasswordRules.matchesCurrent(account(null), "x"));
        } finally {
            withEncoder(old);
        }
    }

    /** A stored hash the encoder cannot read (unknown {id}) is a no, not a 500. */
    @Test
    void anUnreadableStoredHashIsNotAMatch() {
        var broken = org.mockito.Mockito.mock(org.springframework.security.crypto.password.PasswordEncoder.class);
        org.mockito.Mockito.when(broken.matches(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("There is no PasswordEncoder mapped for the id \"zz\""));
        var old = withEncoder(broken);
        try {
            assertFalse(PasswordRules.matchesCurrent(account("{zz}abc"), "x"));
        } finally {
            withEncoder(old);
        }
    }
}
