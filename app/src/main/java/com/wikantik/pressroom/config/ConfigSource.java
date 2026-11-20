/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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

package com.wikantik.pressroom.config;

import java.util.OptionalInt;
import java.util.function.IntPredicate;
import java.util.function.LongPredicate;

/**
 * A store of config values read as text, and the one way to read them as
 * numbers and booleans, so the static (pressroom.properties and environment)
 * and runtime (database) stores cannot disagree about a bad value.
 *
 * <p>A value is <em>unset</em> when {@link #raw} is null or blank. Reads come
 * in two kinds, chosen by what the caller does with a bad value:
 * <ul>
 *   <li>{@link #intValue}: strict. Unset is empty; set but not a number
 *       throws, so garbage is never mistaken for "not configured".</li>
 *   <li>{@link #intOr}, {@link #longOr}, {@link #boolOr}: lenient. Unset,
 *       not a number, or rejected by the caller's range check all give the
 *       caller's default, and the last two are logged. Use these in a
 *       decision path, where the default is the safe answer.</li>
 * </ul>
 */
@FunctionalInterface
public interface ConfigSource {

    /** @return the stored text for the key, or null when there is none */
    String raw(String key);

    /** The static config: pressroom.properties, the custom file and the environment. */
    static ConfigSource staticConfig() {
        return WebloggerConfig::getProperty;
    }

    /** The runtime config: the properties table, null before the tier is bootstrapped. */
    static ConfigSource runtime() {
        return WebloggerRuntimeConfig::getProperty;
    }

    /** {@code first}, and {@code second} for what {@code first} leaves unset or blank. */
    static ConfigSource layered(ConfigSource first, ConfigSource second) {
        return key -> {
            String value = first.raw(key);
            return value == null || value.isBlank() ? second.raw(key) : value;
        };
    }

    /** The runtime config, then the static config for what it leaves unset. */
    static ConfigSource runtimeThenStatic() {
        return layered(runtime(), staticConfig());
    }

    /**
     * @return the value, empty when unset
     * @throws NumberFormatException when the value is set but is not an int
     */
    default OptionalInt intValue(String key) {
        String value = raw(key);
        return value == null || value.isBlank() ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(value.trim()));
    }

    /** The value when it is set, an int and accepted by {@code valid}; otherwise {@code fallback}. */
    default int intOr(String key, int fallback, IntPredicate valid) {
        String value = raw(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (valid.test(parsed)) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            ConfigSources.LOG.debug("{} is not a number", key, e);
        }
        ConfigSources.LOG.warn("{} has no usable value; using {}", key, fallback);
        return fallback;
    }

    /** {@link #intOr} for a long. */
    default long longOr(String key, long fallback, LongPredicate valid) {
        String value = raw(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (valid.test(parsed)) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            ConfigSources.LOG.debug("{} is not a number", key, e);
        }
        ConfigSources.LOG.warn("{} has no usable value; using {}", key, fallback);
        return fallback;
    }

    /** {@code fallback} when unset, otherwise whether the value reads as {@code true}. */
    default boolean boolOr(String key, boolean fallback) {
        String value = raw(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }
}
