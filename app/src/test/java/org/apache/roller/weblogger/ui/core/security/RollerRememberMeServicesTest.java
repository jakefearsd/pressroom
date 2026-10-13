package org.apache.roller.weblogger.ui.core.security;

import org.apache.roller.weblogger.config.WebloggerConfig;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * The remember-me cookie signature and the refusal to run remember-me on the
 * well-known default key. Anyone who knows the default key can forge a cookie
 * for any user, so enabling the feature without choosing a secret must stop
 * startup rather than quietly work.
 */
class RollerRememberMeServicesTest {

    private static RollerRememberMeServices services(String key, boolean enabled) {
        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class)) {
            config.when(() -> WebloggerConfig.getProperty("rememberme.key", "springRocks")).thenReturn(key);
            config.when(() -> WebloggerConfig.getBooleanProperty("rememberme.enabled")).thenReturn(enabled);
            return new RollerRememberMeServices(mock(UserDetailsService.class));
        }
    }

    @Test
    void rememberMeEnabledOnTheDefaultKeyStopsStartup() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> services("springRocks", true));

        assertTrue(ex.getMessage().contains("rememberme.key"), ex.getMessage());
    }

    @Test
    void theDefaultKeyIsToleratedWhileRememberMeIsOff() {
        assertEquals("springRocks", services("springRocks", false).getKey());
    }

    @Test
    void aChosenKeyIsAcceptedWhenRememberMeIsOn() {
        assertEquals("a-real-secret", services("a-real-secret", true).getKey());
    }

    @Test
    void theSignatureIsTheSha512HexOfUserExpiryPasswordAndKey() throws Exception {
        RollerRememberMeServices svc = services("k1", false);
        String data = "alice:1700000000000:hash:k1";
        byte[] digest = java.security.MessageDigest.getInstance("SHA-512")
                .digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String expected = java.util.HexFormat.of().formatHex(digest);

        String signature = svc.makeTokenSignature(1700000000000L, "alice", "hash");

        assertEquals(expected, signature);
        assertEquals(128, signature.length());
    }

    @Test
    void everyInputAndTheKeyChangeTheSignature() {
        RollerRememberMeServices svc = services("k1", false);
        String base = svc.makeTokenSignature(1000L, "alice", "pw");

        assertNotEquals(base, svc.makeTokenSignature(1001L, "alice", "pw"), "expiry");
        assertNotEquals(base, svc.makeTokenSignature(1000L, "bob", "pw"), "user");
        assertNotEquals(base, svc.makeTokenSignature(1000L, "alice", "pw2"), "password: a changed password must void the cookie");
        assertNotEquals(base, services("k2", false).makeTokenSignature(1000L, "alice", "pw"), "key");
    }
}
