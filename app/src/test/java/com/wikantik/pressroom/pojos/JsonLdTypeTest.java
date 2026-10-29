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
package com.wikantik.pressroom.pojos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the lenient parsing contract of {@link JsonLdType#fromString}: the
 * jsonld_type column and the editor form both carry free strings, and a value
 * this build does not recognize must degrade to the BLOG_POSTING default
 * rather than break rendering or editing.
 */
class JsonLdTypeTest {

    @ParameterizedTest
    @EnumSource(JsonLdType.class)
    void everyEnumNameRoundTripsThroughFromString(JsonLdType type) {
        assertEquals(type, JsonLdType.fromString(type.name()),
                "The stored column value is the enum name, so the name must parse back "
                        + "to the same constant");
    }

    @Test
    void nullMeansTheBlogPostingDefault() {
        // Every entry persisted before this field existed has a null column;
        // null must read as "the default", never throw.
        assertEquals(JsonLdType.BLOG_POSTING, JsonLdType.fromString(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankMeansTheBlogPostingDefaultToo(String blank) {
        assertEquals(JsonLdType.BLOG_POSTING, JsonLdType.fromString(blank));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HikingTrail", "TOURIST", "BLOG-POSTING", "42"})
    void anUnknownValueFallsBackToBlogPostingInsteadOfThrowing(String unknown) {
        // A hand-edited row or a value written by a newer Roller must not
        // take down the page or the editor that encounters it.
        assertEquals(JsonLdType.BLOG_POSTING, JsonLdType.fromString(unknown));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tourist_attraction", "Tourist_Attraction", " TOURIST_ATTRACTION "})
    void parsingIsCaseInsensitiveAndTrimsWhitespace(String variant) {
        assertEquals(JsonLdType.TOURIST_ATTRACTION, JsonLdType.fromString(variant));
    }
}
