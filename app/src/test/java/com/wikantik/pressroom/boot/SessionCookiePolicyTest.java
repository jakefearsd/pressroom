package com.wikantik.pressroom.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Pins the session cookie's attributes in {@code application.properties}. Boot
 * applies them straight from the keys, so there is no Java-config seam to test
 * instead (see {@link RemoteIpValveConfigTest} for the same shape).
 */
class SessionCookiePolicyTest {

    private static Properties applicationProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = SessionCookiePolicyTest.class.getResourceAsStream("/application.properties")) {
            assertNotNull(in, "application.properties must be on the test classpath");
            properties.load(in);
        }
        return properties;
    }

    /**
     * Reader sign-in spec criterion 9. Lax, never Strict: the return from the
     * sign-in provider is a cross-site navigation, and Strict drops the session
     * that holds the OAuth2 state.
     */
    @Test
    void theSessionCookieIsSameSiteLax() throws IOException {
        assertEquals("lax", applicationProperties().getProperty("server.servlet.session.cookie.same-site"));
    }

    @Test
    void theSessionCookieIsHttpOnly() throws IOException {
        assertEquals("true", applicationProperties().getProperty("server.servlet.session.cookie.http-only"));
    }
}
