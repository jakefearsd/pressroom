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

/**
 * The recency factor a result's score is multiplied by: 1.25 for a post
 * published now, falling linearly to 1.0 at two years, 1.0 after. It breaks
 * near-ties toward a blog that is still publishing and never lifts a passing
 * mention over a real match. The constants live here, and only here.
 */
final class RecencyBoost {

    static final double MAX_BONUS = 0.25;
    static final long FULL_DECAY_DAYS = 730;
    private static final double MILLIS_PER_DAY = 24.0 * 60 * 60 * 1000;

    private RecencyBoost() {
    }

    static double factor(long publishedMillis, long nowMillis) {
        double ageDays = (nowMillis - publishedMillis) / MILLIS_PER_DAY;
        double fresh = 1.0 - ageDays / FULL_DECAY_DAYS;
        return 1.0 + MAX_BONUS * Math.max(0.0, Math.min(1.0, fresh));
    }
}
