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

package com.wikantik.pressroom.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the hit throttle that CommentServlet uses to shut out comment floods.
 *
 * <p>Two things matter and both are easy to get backwards: the threshold is
 * exclusive (a client is abusive only <em>after</em> exceeding it, so a
 * threshold of 25 must not block the 25th comment), and the count has to age
 * out, otherwise a busy shared IP is banned permanently.
 */
public class GenericThrottleTest {

    private static final int ONE_MINUTE = 60 * 1000;

    @Test
    public void aClientIsAbusiveOnlyAfterItPassesTheThreshold() {
        GenericThrottle throttle = new GenericThrottle(2, ONE_MINUTE, 100);

        assertFalse(throttle.processHit("1.2.3.4"), "the first hit must be allowed");
        assertFalse(throttle.processHit("1.2.3.4"), "hit two of a threshold of two must be allowed");
        assertTrue(throttle.processHit("1.2.3.4"),
                "The third hit exceeds a threshold of two and must be reported as abusive; "
                        + "an off-by-one here either blocks legitimate commenters or lets a "
                        + "flood through.");
    }

    @Test
    public void isAbusiveAgreesWithProcessHitButDoesNotCount() {
        // CommentServlet asks isAbusive() on the GET and processHit() on the
        // POST; if the query counted as a hit, merely loading a page twice
        // would ban the reader.
        GenericThrottle throttle = new GenericThrottle(1, ONE_MINUTE, 100);

        assertFalse(throttle.isAbusive("1.2.3.4"), "an unknown client is not abusive");
        throttle.processHit("1.2.3.4");
        assertFalse(throttle.isAbusive("1.2.3.4"));
        assertFalse(throttle.isAbusive("1.2.3.4"), "asking twice must not increment anything");

        throttle.processHit("1.2.3.4");
        assertTrue(throttle.isAbusive("1.2.3.4"));
    }

    @Test
    public void clientsAreCountedIndependently() {
        GenericThrottle throttle = new GenericThrottle(1, ONE_MINUTE, 100);

        throttle.processHit("1.2.3.4");
        throttle.processHit("1.2.3.4");

        assertTrue(throttle.isAbusive("1.2.3.4"));
        assertFalse(throttle.isAbusive("5.6.7.8"),
                "One noisy address must not lock out everybody else.");
    }

    @Test
    public void aNullClientIdIsNeverAbusiveAndNeverThrows() {
        // The servlet passes whatever getRemoteAddr() returned.
        GenericThrottle throttle = new GenericThrottle(0, ONE_MINUTE, 100);

        assertFalse(throttle.processHit(null));
        assertFalse(throttle.isAbusive(null));
    }

    @Test
    public void theCountAgesOutAfterTheInterval() throws InterruptedException {
        // The interval is what makes this a rate limit rather than a ban list.
        GenericThrottle throttle = new GenericThrottle(1, 100, 100);

        throttle.processHit("1.2.3.4");
        throttle.processHit("1.2.3.4");
        assertTrue(throttle.isAbusive("1.2.3.4"));

        Thread.sleep(250);

        assertFalse(throttle.isAbusive("1.2.3.4"),
                "The hit record should have expired after its interval; if it does not, a "
                        + "client that once tripped the limit stays blocked until restart.");
        assertFalse(throttle.processHit("1.2.3.4"), "counting must start over after expiry");
    }

    @Test
    public void aNegativeThresholdIsTreatedAsOneRatherThanBanningEveryone() {
        // A threshold below zero would make every first hit abusive, which is
        // why the constructor refuses it. Zero, on the other hand, is a legal
        // setting meaning "one hit is already too many".
        // -1 is the boundary the constructor checks against.
        GenericThrottle negative = new GenericThrottle(-1, ONE_MINUTE, 100);
        assertFalse(negative.processHit("1.2.3.4"), "the first hit must still be allowed");
        assertTrue(negative.processHit("1.2.3.4"),
                "with the fallback threshold of one, the second hit exceeds it");

        GenericThrottle zero = new GenericThrottle(0, ONE_MINUTE, 100);
        assertFalse(zero.processHit("1.2.3.4"), "the first hit creates the record");
        assertTrue(zero.processHit("1.2.3.4"));
    }

    @Test
    public void aNegativeCacheSizeIsRepairedInsteadOfBlowingUpAtStartup() {
        // The value comes from configuration (comment.throttle.maxentries); a
        // negative one would otherwise reach the cache and throw while the
        // servlet is initialising, taking comments out entirely.
        GenericThrottle throttle = new GenericThrottle(1, ONE_MINUTE, -1);

        assertFalse(throttle.processHit("1.2.3.4"));
    }

    @Test
    public void theOldestClientIsForgottenWhenTheCacheIsFull() {
        // maxEntries bounds the memory the throttle can consume; the cost is
        // that a client pushed out of the cache starts from zero again.
        GenericThrottle throttle = new GenericThrottle(1, ONE_MINUTE, 1);

        throttle.processHit("1.2.3.4");
        throttle.processHit("1.2.3.4");
        assertTrue(throttle.isAbusive("1.2.3.4"));

        throttle.processHit("5.6.7.8");

        assertFalse(throttle.isAbusive("1.2.3.4"),
                "With room for a single client the earlier one must have been evicted; if it "
                        + "were not, maxEntries would not bound memory use at all.");
    }
}
