package com.wikantik.pressroom.ui.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import com.wikantik.pressroom.config.WebloggerConfig;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * Lazy upgrade of password hashes written by old Roller versions: a stored
 * MD5 or SHA digest (no {@code {id}} prefix) must still authenticate while the
 * operator has asked for it, must not authenticate when they have not, and an
 * unsupported source algorithm must stop startup rather than be ignored.
 */
class PressroomContextLazyUpgradeTest {

    private static PasswordEncoder encoderWith(String lazyUpgradeFrom) {
        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class, CALLS_REAL_METHODS)) {
            config.when(() -> WebloggerConfig.getProperty("passwds.encryption.lazyUpgradeFrom"))
                    .thenReturn(lazyUpgradeFrom);
            config.when(() -> WebloggerConfig.getProperty("passwds.encryption.algorithm")).thenReturn("bcrypt");
            config.when(() -> WebloggerConfig.getProperty("passwds.encryption.enabled")).thenReturn(null);
            return PressroomContext.createPasswordEncoder();
        }
    }

    private static String hex(String algorithm, String raw) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance(algorithm).digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void anOldMd5HashStillAuthenticatesWhenMd5UpgradeIsConfigured() throws Exception {
        PasswordEncoder encoder = encoderWith("MD5");
        String legacy = hex("MD5", "old-password");

        assertTrue(encoder.matches("old-password", legacy));
        assertFalse(encoder.matches("wrong-password", legacy));
    }

    @Test
    void anOldShaHashStillAuthenticatesWhenShaUpgradeIsConfigured() throws Exception {
        PasswordEncoder encoder = encoderWith("SHA");
        String legacy = hex("SHA-1", "old-password");

        assertTrue(encoder.matches("old-password", legacy));
        assertFalse(encoder.matches("wrong-password", legacy));
    }

    /** With upgrade off, an unprefixed stored string has no encoder: it must not authenticate. */
    @Test
    void anUnprefixedHashDoesNotAuthenticateWhenNoUpgradeIsConfigured() throws Exception {
        String legacy = hex("MD5", "old-password");

        for (String off : new String[] {null, ""}) {
            PasswordEncoder encoder = encoderWith(off);
            boolean authenticated;
            try {
                authenticated = encoder.matches("old-password", legacy);
            } catch (IllegalArgumentException refused) {
                authenticated = false;
            }
            assertFalse(authenticated, "upgrade setting '" + off + "'");
        }
    }

    @Test
    void anUpgradeSourceThatIsNotSupportedStopsStartup() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> encoderWith("plaintext"));

        assertTrue(e.getMessage().contains("lazyUpgradeFrom=plaintext"), e.getMessage());
    }
}
