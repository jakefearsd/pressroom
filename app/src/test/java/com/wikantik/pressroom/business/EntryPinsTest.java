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
package com.wikantik.pressroom.business;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pin limit: a blog shows at most three pinned posts, so a fourth pin is
 * refused, while re-saving or unpinning a post that is already pinned never is.
 */
class EntryPinsTest {

    @Test
    void aBlogPinsAtMostThreePosts() {
        assertEquals(3, EntryPins.MAX_PER_WEBLOG);
    }

    @Test
    void aNewPinWhenThreeArePinnedWouldExceedTheLimit() {
        assertTrue(EntryPins.wouldExceed(false, true, 3));
    }

    @Test
    void aNewPinWhenTwoArePinnedIsAllowed() {
        assertFalse(EntryPins.wouldExceed(false, true, 2));
    }

    /** One of the three, saved again: it is counted already, so nothing grows. */
    @Test
    void resavingAPinnedPostWhenThreeArePinnedIsAllowed() {
        assertFalse(EntryPins.wouldExceed(true, true, 3));
    }

    @Test
    void unpinningIsAlwaysAllowed() {
        assertFalse(EntryPins.wouldExceed(true, false, 3));
    }

    @Test
    void leavingAPostUnpinnedIsAlwaysAllowed() {
        assertFalse(EntryPins.wouldExceed(false, false, 3));
    }
}
