package com.wikantik.pressroom.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Pins the startup guard that refuses the old (Roller) configuration names.
 * The old names appear here only as inputs the guard must catch.
 */
class LegacyConfigNamesTest {

    private static Properties sys(String... keysAndValues) {
        Properties p = new Properties();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            p.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return p;
    }

    @Test
    void namesEveryOldEnvironmentVariable() {
        Map<String, String> env = Map.of(
                "ROLLER_DATABASE_JDBC_PASSWORD", "s3cret", // rebrand:keep
                "ROLLER_EMPTY", "", // rebrand:keep
                "PRESSROOM_X", "y",
                "PATH", "/bin");

        assertEquals(List.of("ROLLER_DATABASE_JDBC_PASSWORD", "ROLLER_EMPTY"), // rebrand:keep
                LegacyConfigNames.found(env, new Properties()));
    }

    @Test
    void namesEveryOldSystemProperty() {
        Properties props = sys("roller.custom.config", "/a", // rebrand:keep
                "pressroom.custom.config", "/b", "java.version", "25");

        assertEquals(List.of("roller.custom.config"), // rebrand:keep
                LegacyConfigNames.found(Map.of(), props));
    }

    @Test
    void ignoresLookalikes() {
        Map<String, String> env = Map.of("ROLLERX", "1", "MY_ROLLER_X", "2"); // rebrand:keep
        Properties props = sys("rollerx.a", "1", "x.roller.y", "2"); // rebrand:keep

        assertTrue(LegacyConfigNames.found(env, props).isEmpty());
    }

    @Test
    void refusalNamesEveryOldNameAndNoValue() {
        Map<String, String> env = Map.of("ROLLER_DATABASE_JDBC_PASSWORD", "s3cret"); // rebrand:keep
        Properties props = sys("roller.custom.config", "/a"); // rebrand:keep

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LegacyConfigNames.refuse(env, props));

        assertTrue(e.getMessage().contains("ROLLER_DATABASE_JDBC_PASSWORD"), e.getMessage()); // rebrand:keep
        assertTrue(e.getMessage().contains("roller.custom.config"), e.getMessage()); // rebrand:keep
        assertFalse(e.getMessage().contains("s3cret"), e.getMessage());
    }

    @Test
    void passesWhenNoOldNameIsPresent() {
        assertDoesNotThrow(() -> LegacyConfigNames.refuse(Map.of("PRESSROOM_X", "y"), sys("a.b", "c")));
    }
}
