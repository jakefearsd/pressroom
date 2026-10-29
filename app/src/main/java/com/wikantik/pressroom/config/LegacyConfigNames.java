package com.wikantik.pressroom.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Startup guard: Pressroom reads {@code PRESSROOM_*} environment variables and
 * {@code pressroom.*} system properties, and refuses to start when an old
 * (Roller) name is still set, so a stale deploy fails loudly instead of
 * silently running on defaults. Only names are ever reported, never values.
 */
final class LegacyConfigNames {

    private static final String OLD_ENV_PREFIX = "ROLLER_"; // rebrand:keep
    private static final String OLD_PROPERTY_PREFIX = "roller."; // rebrand:keep

    private LegacyConfigNames() {}

    /**
     * The old names present, sorted: environment variables with the exact
     * prefix {@code ROLLER_} and system properties with the exact prefix
     * {@code roller.}. Never values.
     */
    static List<String> found(Map<String, String> env, Properties systemProperties) {
        List<String> names = new ArrayList<>();
        for (String name : env.keySet()) {
            if (name.startsWith(OLD_ENV_PREFIX)) {
                names.add(name);
            }
        }
        for (String name : systemProperties.stringPropertyNames()) {
            if (name.startsWith(OLD_PROPERTY_PREFIX)) {
                names.add(name);
            }
        }
        Collections.sort(names);
        return names;
    }

    /** Throws {@link IllegalStateException} naming every old name present; returns normally when none is. */
    static void refuse(Map<String, String> env, Properties systemProperties) {
        List<String> old = found(env, systemProperties);
        if (!old.isEmpty()) {
            throw new IllegalStateException("Pressroom no longer reads Roller configuration names. " // rebrand:keep
                    + "Rename each to its PRESSROOM_ / pressroom. form and restart: " + String.join(", ", old));
        }
    }
}
