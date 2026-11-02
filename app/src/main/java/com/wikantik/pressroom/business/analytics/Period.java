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
package com.wikantik.pressroom.business.analytics;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reporting window: {@code 24h}, or {@code <N>d} for 1 to 365 days.
 *
 * @param key    the text form, as accepted by {@link #of(String)}
 * @param length how long the window is
 */
public record Period(String key, Duration length) {

    private static final Pattern DAYS = Pattern.compile("([1-9][0-9]{0,2})d");
    private static final int MAX_DAYS = 365;

    /** Parses a period key, or throws {@link IllegalArgumentException}. */
    public static Period of(String key) {
        if ("24h".equals(key)) {
            return new Period(key, Duration.ofHours(24));
        }
        if (key != null) {
            Matcher m = DAYS.matcher(key);
            if (m.matches()) {
                int days = Integer.parseInt(m.group(1));
                if (days <= MAX_DAYS) {
                    return new Period(key, Duration.ofDays(days));
                }
            }
        }
        throw new IllegalArgumentException("not a period: " + key);
    }

    /** The instant this window starts, when it ends at {@code end}. */
    public Instant startBefore(Instant end) {
        return end.minus(length);
    }
}
