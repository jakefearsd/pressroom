package com.wikantik.pressroom.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * Which Micrometer observations the app records. Spring Security wraps every
 * filter of its chain in an observation of its own when an
 * {@code ObservationRegistry} exists, and on a cached page that bookkeeping
 * was a third of the CPU: the front page went from 20.7k to 32.3k requests a
 * second without it, p99 7.2 to 3.0 ms (profiling, 2026-11-06). Nothing reads
 * those timers (only {@code health} is exposed). The per-request
 * {@code http.server.requests} observation, which a {@code /metrics}
 * endpoint would export, is a different prefix and stays on.
 */
class ObservationPolicyTest {

    private static Properties applicationProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = ObservationPolicyTest.class.getResourceAsStream("/application.properties")) {
            assertNotNull(in, "application.properties must be on the test classpath");
            properties.load(in);
        }
        return properties;
    }

    @Test
    void springSecurityFilterObservationsAreOff() throws IOException {
        assertEquals("false",
                applicationProperties().getProperty("management.observations.enable.spring.security"));
    }

    @Test
    void requestObservationsAreNotSwitchedOff() throws IOException {
        Properties properties = applicationProperties();
        assertEquals(null, properties.getProperty("management.observations.enable.http"));
        assertEquals(null, properties.getProperty("management.observations.enable.all"));
    }
}
