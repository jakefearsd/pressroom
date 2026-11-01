/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package com.wikantik.pressroom.business.discovery;

import java.util.Optional;

import com.wikantik.pressroom.config.WebloggerRuntimeConfig;

/**
 * The three site-wide settings behind the discovery front page: how many days
 * of traffic count toward popularity, what share of slots is filled at random,
 * and how often the popularity snapshot refreshes (and random picks reshuffle).
 *
 * <p>Reads are display-safe: a missing, unparsable or out-of-range stored
 * value answers the default, so a bad row can never break the front page.
 * Writes are checked by {@link #rangeError} in Global Config and in
 * {@code PATCH /v1/admin/config}.
 */
public final class DiscoverySettings {

    /** Days of traffic counted toward popularity. */
    public static final String WINDOW_DAYS = "site.discovery.windowDays";
    /** Percent of front-page slots filled at random. */
    public static final String SHARE_PERCENT = "site.discovery.sharePercent";
    /** Snapshot refresh interval, stored in seconds. */
    public static final String REFRESH_SECONDS = "site.discovery.refreshSeconds";

    /**
     * The form field Global Config posts instead of the seconds property: the
     * interval is edited in whole minutes and stored in seconds.
     */
    public static final String REFRESH_MINUTES_FIELD = "discoveryRefreshMinutes";

    /** Message key for an out-of-range value; arguments are label, min, max. */
    public static final String RANGE_ERROR_KEY = "configForm.error.discoveryRange";

    /** The refresh interval is edited in minutes and stored in seconds. */
    public static final int SECONDS_PER_MINUTE = 60;

    private static final int DEFAULT_WINDOW_DAYS = 30;
    private static final int DEFAULT_SHARE_PERCENT = 20;
    private static final int DEFAULT_REFRESH_SECONDS = 3600;

    private DiscoverySettings() {
    }

    /** Days of traffic counted, 1 to 365; default 30. */
    public static int windowDays() {
        return read(WINDOW_DAYS, DEFAULT_WINDOW_DAYS);
    }

    /** Share of slots filled at random, 0 to 100; default 20. */
    public static int sharePercent() {
        return read(SHARE_PERCENT, DEFAULT_SHARE_PERCENT);
    }

    /** Snapshot refresh interval in seconds, 60 to 86400; default 3600. */
    public static int refreshSeconds() {
        return read(REFRESH_SECONDS, DEFAULT_REFRESH_SECONDS);
    }

    /**
     * The message key to report when {@code value} is outside the allowed
     * range for {@code name}; empty when it is acceptable or the name is not
     * one of these settings.
     */
    public static Optional<String> rangeError(String name, int value) {
        if (!isDiscoverySetting(name)) {
            return Optional.empty();
        }
        return value < min(name) || value > max(name) ? Optional.of(RANGE_ERROR_KEY) : Optional.empty();
    }

    /** Smallest allowed value of one of the settings, in its stored unit. */
    public static int min(String name) {
        return switch (name) {
            case WINDOW_DAYS -> 1;
            case SHARE_PERCENT -> 0;
            case REFRESH_SECONDS -> SECONDS_PER_MINUTE;
            default -> throw new IllegalArgumentException(name);
        };
    }

    /** Largest allowed value of one of the settings, in its stored unit. */
    public static int max(String name) {
        return switch (name) {
            case WINDOW_DAYS -> 365;
            case SHARE_PERCENT -> 100;
            case REFRESH_SECONDS -> 24 * 60 * SECONDS_PER_MINUTE;
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static boolean isDiscoverySetting(String name) {
        return WINDOW_DAYS.equals(name) || SHARE_PERCENT.equals(name) || REFRESH_SECONDS.equals(name);
    }

    /**
     * The refresh interval in whole minutes for a raw stored value; a missing,
     * unparsable or out-of-range value shows the default. Never throws, so a
     * hand-edited row cannot lock the admin out of the page that fixes it.
     */
    public static int refreshMinutesFrom(String storedSeconds) {
        int seconds = DEFAULT_REFRESH_SECONDS;
        if (storedSeconds != null) {
            try {
                int parsed = Integer.parseInt(storedSeconds.trim());
                if (parsed >= min(REFRESH_SECONDS) && parsed <= max(REFRESH_SECONDS)) {
                    seconds = parsed;
                }
            } catch (NumberFormatException e) {
                seconds = DEFAULT_REFRESH_SECONDS;
            }
        }
        return seconds / SECONDS_PER_MINUTE;
    }

    private static int read(String name, int fallback) {
        int value = WebloggerRuntimeConfig.getIntProperty(name);
        return value < min(name) || value > max(name) ? fallback : value;
    }
}
