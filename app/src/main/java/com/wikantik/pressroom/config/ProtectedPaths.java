package com.wikantik.pressroom.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;

/**
 * The first path segments that belong to the application and can never be a
 * weblog handle or a site page slug: {@code rollerProtectedUrls} plus
 * {@code userProtectedUrls}, trimmed and lower-cased. One parse, used by weblog
 * creation and by the site-page slug check, so the two cannot drift.
 */
public final class ProtectedPaths {

    private ProtectedPaths() {
    }

    /** The protected paths, lower-cased; a fresh read of the configuration each call. */
    public static Set<String> site() {
        Set<String> reserved = new LinkedHashSet<>();
        Stream.of("rendering.weblogMapper.rollerProtectedUrls", "rendering.weblogMapper.userProtectedUrls")
                .map(WebloggerConfig::getProperty)
                .filter(StringUtils::isNotBlank)
                .flatMap(list -> Arrays.stream(list.split(",")))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .forEach(reserved::add);
        return Set.copyOf(reserved);
    }
}
