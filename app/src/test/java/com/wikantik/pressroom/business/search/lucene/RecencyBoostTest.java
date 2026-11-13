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
package com.wikantik.pressroom.business.search.lucene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecencyBoostTest {

    private static final long NOW = 1_800_000_000_000L;

    private static long days(long n) {
        return n * 24L * 60 * 60 * 1000;
    }

    @Test
    void aPostPublishedNowGetsTheFullFactor() {
        assertEquals(1.25, RecencyBoost.factor(NOW, NOW), 1e-9);
    }

    @Test
    void theFactorFallsLinearlyToOneAtTwoYears() {
        assertEquals(1.125, RecencyBoost.factor(NOW - days(365), NOW), 1e-9);
        assertEquals(1.0, RecencyBoost.factor(NOW - days(730), NOW), 1e-9);
    }

    @Test
    void anOlderPostStaysAtOne() {
        assertEquals(1.0, RecencyBoost.factor(NOW - days(5000), NOW), 1e-9);
    }

    @Test
    void aFutureDateIsClampedToTheFullFactor() {
        assertEquals(1.25, RecencyBoost.factor(NOW + days(3), NOW), 1e-9);
    }
}
