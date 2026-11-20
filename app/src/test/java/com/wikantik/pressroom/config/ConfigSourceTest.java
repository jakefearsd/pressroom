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

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one reading of a config value as an int, long or boolean, whichever store it comes from. */
class ConfigSourceTest {

    private static ConfigSource of(Map<String, String> values) {
        return values::get;
    }

    @Test
    void anUnsetOrBlankIntIsEmpty() {
        ConfigSource source = of(Map.of("blank", "  "));
        assertEquals(OptionalInt.empty(), source.intValue("missing"));
        assertEquals(OptionalInt.empty(), source.intValue("blank"));
    }

    @Test
    void aSetIntIsParsedTrimmed() {
        assertEquals(OptionalInt.of(42), of(Map.of("n", " 42 ")).intValue("n"));
    }

    @Test
    void aSetButUnparseableIntThrowsRatherThanReadAsUnset() {
        assertThrows(NumberFormatException.class, () -> of(Map.of("n", "forty")).intValue("n"));
    }

    @Test
    void intOrTakesTheDefaultForUnsetUnparseableAndOutOfRange() {
        ConfigSource source = of(Map.of("typo", "abc", "big", "500", "ok", "7"));
        assertEquals(9, source.intOr("missing", 9, v -> true));
        assertEquals(9, source.intOr("typo", 9, v -> true));
        assertEquals(9, source.intOr("big", 9, v -> v <= 100));
        assertEquals(7, source.intOr("ok", 9, v -> v <= 100));
    }

    @Test
    void longOrFollowsTheSameRules() {
        ConfigSource source = of(Map.of("typo", "abc", "neg", "-1", "ok", "5000000000"));
        assertEquals(3L, source.longOr("missing", 3L, v -> true));
        assertEquals(3L, source.longOr("typo", 3L, v -> true));
        assertEquals(3L, source.longOr("neg", 3L, v -> v > 0));
        assertEquals(5_000_000_000L, source.longOr("ok", 3L, v -> v > 0));
    }

    @Test
    void boolOrTakesTheDefaultOnlyWhenUnset() {
        ConfigSource source = of(Map.of("yes", " true ", "no", "false", "junk", "maybe"));
        assertTrue(source.boolOr("yes", false));
        assertFalse(source.boolOr("no", true));
        assertTrue(source.boolOr("missing", true));
        assertFalse(source.boolOr("junk", true), "a set value that is not true is false, as Boolean.parseBoolean reads it");
    }

    @Test
    void layeredReadsTheFirstStoreAndFallsThroughWhenUnsetOrBlank() {
        Map<String, String> runtime = new HashMap<>(Map.of("blank", " ", "set", "1"));
        ConfigSource layered = ConfigSource.layered(of(runtime), of(Map.of("blank", "2", "set", "3", "only", "4")));
        assertEquals(2, layered.intOr("blank", 0, v -> true));
        assertEquals(1, layered.intOr("set", 0, v -> true));
        assertEquals(4, layered.intOr("only", 0, v -> true));
        assertEquals(0, layered.intOr("neither", 0, v -> true));
    }

    @Test
    void theStaticAndRuntimeStoresAreTheOnesTheWrappersRead() {
        assertEquals(WebloggerConfig.getProperty("site.name"), ConfigSource.staticConfig().raw("site.name"));
        assertEquals(WebloggerRuntimeConfig.getProperty("site.name"), ConfigSource.runtime().raw("site.name"));
    }
}
