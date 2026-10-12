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
package org.apache.roller.weblogger.business.comments;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * M11, ruling R9: the comment rate limit is configuration, read as allowed
 * counts, and a value that is missing or unusable falls back to the strict
 * default rather than to no limit.
 */
class CommentLimitsTest {

    @Test
    void theThreePropertiesAreReadAsAllowedCounts() {
        Map<String, String> config = Map.of(
                "comments.throttle.window.limit", "1000",
                "comments.throttle.window.seconds", "60",
                "comments.throttle.daily.limit", " 10000 ");

        assertEquals(new CommentLimits(1000, 60, 10000), CommentLimits.from(config::get));
    }

    @Test
    void nothingConfiguredIsTheDefault() {
        assertEquals(new CommentLimits(5, 600, 30), CommentLimits.from(name -> null));
    }

    @Test
    void anUnusableValueFallsBackToItsDefaultAlone() {
        Map<String, String> config = new HashMap<>();
        config.put("comments.throttle.window.limit", "lots");
        config.put("comments.throttle.window.seconds", "0");
        config.put("comments.throttle.daily.limit", "-4");
        assertEquals(CommentLimits.DEFAULTS, CommentLimits.from(config::get));

        config.put("comments.throttle.window.limit", "7");
        config.put("comments.throttle.daily.limit", "");
        assertEquals(new CommentLimits(7, 600, 30), CommentLimits.from(config::get));

        config.put("comments.throttle.window.seconds", "86401");
        assertEquals(new CommentLimits(7, 600, 30), CommentLimits.from(config::get),
                "a window longer than a day is the daily limit's job");
        config.put("comments.throttle.window.seconds", "86400");
        assertEquals(new CommentLimits(7, 86_400, 30), CommentLimits.from(config::get));
    }

    @Test
    void aLimitIsNeverZeroOrNegative() {
        assertThrows(IllegalArgumentException.class, () -> new CommentLimits(0, 600, 30));
        assertThrows(IllegalArgumentException.class, () -> new CommentLimits(5, 0, 30));
        assertThrows(IllegalArgumentException.class, () -> new CommentLimits(5, 600, -1));
        assertThrows(IllegalArgumentException.class, () -> new CommentLimits(5, 86_401, 30));
    }
}
