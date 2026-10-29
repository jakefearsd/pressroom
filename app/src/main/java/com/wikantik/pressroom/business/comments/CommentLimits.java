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
package com.wikantik.pressroom.business.comments;

import java.util.function.UnaryOperator;

import com.wikantik.pressroom.config.WebloggerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-account rate limit on comments and replies (ruling R9), as ALLOWED
 * counts: at most {@code windowLimit} in any {@code windowSeconds}, and at most
 * {@code dailyLimit} in a day. {@link CommentService} turns them into
 * {@code GenericThrottle} thresholds in one place.
 *
 * <p>Read once, at startup, from pressroom.properties (or a {@code PRESSROOM_*}
 * environment override): {@value #WINDOW_LIMIT}, {@value #WINDOW_SECONDS} and
 * {@value #DAILY_LIMIT}. A missing, malformed or out-of-range value falls back
 * to its default, which is the strict one, and says so in the log. The window
 * is at most a day ({@value #MAX_WINDOW_SECONDS} seconds): the daily limit
 * covers anything longer.
 *
 * @param windowLimit   comments allowed in one window
 * @param windowSeconds the window's length, in seconds
 * @param dailyLimit    comments allowed in a day
 */
public record CommentLimits(int windowLimit, int windowSeconds, int dailyLimit) {

    /** Five in ten minutes, thirty a day: the spec's limit. */
    public static final CommentLimits DEFAULTS = new CommentLimits(5, 600, 30);

    static final String WINDOW_LIMIT = "comments.throttle.window.limit";
    static final String WINDOW_SECONDS = "comments.throttle.window.seconds";
    static final String DAILY_LIMIT = "comments.throttle.daily.limit";

    /** The longest window, a day; it also keeps the window's milliseconds inside an int. */
    static final int MAX_WINDOW_SECONDS = 86_400;

    private static final Logger log = LoggerFactory.getLogger("roller.comments");

    /** @throws IllegalArgumentException unless every value is at least 1 and the window at most a day */
    public CommentLimits {
        if (windowLimit < 1 || windowSeconds < 1 || windowSeconds > MAX_WINDOW_SECONDS || dailyLimit < 1) {
            throw new IllegalArgumentException("comment limits must be positive: " + windowLimit + "/"
                    + windowSeconds + "s, " + dailyLimit + "/day");
        }
    }

    /** The limits pressroom.properties configures. */
    public static CommentLimits fromConfig() {
        return from(WebloggerConfig::getProperty);
    }

    /** The limits {@code config} (a property lookup, null when unset) configures. */
    static CommentLimits from(UnaryOperator<String> config) {
        return new CommentLimits(
                bounded(config, WINDOW_LIMIT, DEFAULTS.windowLimit(), Integer.MAX_VALUE),
                bounded(config, WINDOW_SECONDS, DEFAULTS.windowSeconds(), MAX_WINDOW_SECONDS),
                bounded(config, DAILY_LIMIT, DEFAULTS.dailyLimit(), Integer.MAX_VALUE));
    }

    private static int bounded(UnaryOperator<String> config, String name, int fallback, int max) {
        String value = config.apply(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= 1 && parsed <= max) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            log.debug("{} is not a number", name, e);
        }
        log.warn("{} must be a whole number from 1 to {}; using {}", name, max, fallback);
        return fallback;
    }
}
