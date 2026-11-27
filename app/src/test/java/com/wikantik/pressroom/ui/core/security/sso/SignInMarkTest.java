package com.wikantik.pressroom.ui.core.security.sso;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The icon on every reader sign-in button: Google's multicolour G for Google,
 * a neutral mark for any other provider.
 */
class SignInMarkTest {

    private static final SsoSettings GOOGLE = new SsoSettings("id", "s", SsoSettings.GOOGLE_DISCOVERY, "Google");
    private static final SsoSettings KEYCLOAK = new SsoSettings("id", "s", "http://kc.example/realms/x", "Keycloak");

    @Test
    void googleGetsTheFourColourG() {
        String mark = SignInMark.forProvider(GOOGLE);
        for (String colour : new String[] {"#4285F4", "#34A853", "#FBBC05", "#EA4335"}) {
            assertTrue(mark.contains(colour), colour + " missing: " + mark);
        }
        assertTrue(mark.startsWith("<svg class=\"g-signin-mark\""), mark);
        assertTrue(mark.contains("aria-hidden=\"true\""), "the label names the button; the mark is decoration");
    }

    @Test
    void anyOtherProviderGetsANeutralMark() {
        String mark = SignInMark.forProvider(KEYCLOAK);
        assertNotEquals(SignInMark.forProvider(GOOGLE), mark);
        assertFalse(mark.contains("#4285F4"), "no Google colours for another provider: " + mark);
        assertTrue(mark.contains("currentColor"), "the neutral mark takes the button's ink: " + mark);
        assertTrue(mark.startsWith("<svg class=\"g-signin-mark\""), mark);
        assertTrue(mark.contains("aria-hidden=\"true\""));
    }

    /** It is spliced into a single-quoted JavaScript string and a Velocity page. */
    @Test
    void neitherMarkCarriesASingleQuoteOrANewline() {
        for (SsoSettings sso : new SsoSettings[] {GOOGLE, KEYCLOAK}) {
            String mark = SignInMark.forProvider(sso);
            assertFalse(mark.contains("'") || mark.contains("\n") || mark.contains("\\"), mark);
        }
    }
}
