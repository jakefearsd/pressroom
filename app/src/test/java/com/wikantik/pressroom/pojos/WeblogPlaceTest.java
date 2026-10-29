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
package com.wikantik.pressroom.pojos;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Place coordinates are kept to two decimals (about 1 km), HALF_UP. */
class WeblogPlaceTest {

    @Test
    void latitudeIsRoundedToTwoDecimals() {
        Weblog w = new Weblog();
        w.setPlaceLat(new BigDecimal("38.71234"));
        assertEquals(new BigDecimal("38.71"), w.getPlaceLat());
    }

    @Test
    void longitudeRoundsHalfUpAwayFromZero() {
        Weblog w = new Weblog();
        w.setPlaceLng(new BigDecimal("-9.145"));
        assertEquals(new BigDecimal("-9.15"), w.getPlaceLng());
    }

    @Test
    void nullStaysNull() {
        Weblog w = new Weblog();
        w.setPlaceLat(new BigDecimal("1.234"));
        w.setPlaceLat(null);
        w.setPlaceLng(null);
        assertNull(w.getPlaceLat());
        assertNull(w.getPlaceLng());
    }

    /**
     * Defence in depth behind the settings form's plain-decimal rule: a value
     * whose scale is absurd would make the two-place rounding compute an
     * enormous power of ten, so the setter refuses it instead. Bounded, so a
     * regression fails rather than hanging the build.
     */
    @Test
    void anAbsurdScaleIsRefusedRatherThanRounded() {
        Weblog w = new Weblog();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertThrows(IllegalArgumentException.class,
                    () -> w.setPlaceLat(new BigDecimal("1E-100000000")));
            assertThrows(IllegalArgumentException.class,
                    () -> w.setPlaceLng(new BigDecimal("1E+100000000")));
            assertThrows(IllegalArgumentException.class,
                    () -> w.setPlaceLat(new BigDecimal("1E-21")));
            assertThrows(IllegalArgumentException.class,
                    () -> w.setPlaceLng(new BigDecimal("1E+11")));
        });
        assertNull(w.getPlaceLat());
        assertNull(w.getPlaceLng());
    }

    @Test
    void scalesAtTheEdgesOfTheAllowedRangeAreRounded() {
        Weblog w = new Weblog();
        w.setPlaceLat(new BigDecimal("1E+1"));
        w.setPlaceLng(new BigDecimal("0.00000000000000000001"));
        assertEquals(new BigDecimal("10.00"), w.getPlaceLat());
        assertEquals(new BigDecimal("0.00"), w.getPlaceLng());
    }
}
