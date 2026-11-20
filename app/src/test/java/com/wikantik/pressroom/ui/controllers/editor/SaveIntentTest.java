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
package com.wikantik.pressroom.ui.controllers.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.util.PressroomConstants;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;

class SaveIntentTest {

    private static final long NOW = 1_000_000_000L;

    @Test
    void draftIsDraftWhateverThePermission() {
        assertEquals(PubStatus.DRAFT, SaveIntent.DRAFT.status(null, true, NOW));
        assertEquals(PubStatus.DRAFT, SaveIntent.DRAFT.status(null, false, NOW));
    }

    @Test
    void publishWithoutPostSubmitsForReview() {
        assertEquals(PubStatus.PENDING, SaveIntent.PUBLISH.status(null, false, NOW));
        assertEquals(PubStatus.PENDING,
                SaveIntent.PUBLISH.status(new Timestamp(NOW + 10 * PressroomConstants.MIN_IN_MS), false, NOW));
    }

    @Test
    void publishWithPostPublishesOrSchedules() {
        assertEquals(PubStatus.PUBLISHED, SaveIntent.PUBLISH.status(null, true, NOW));
        assertEquals(PubStatus.PUBLISHED, SaveIntent.PUBLISH.status(new Timestamp(NOW - 5), true, NOW));
        // Exactly one minute out is still "now"; a second more is scheduled.
        assertEquals(PubStatus.PUBLISHED,
                SaveIntent.PUBLISH.status(new Timestamp(NOW + PressroomConstants.MIN_IN_MS), true, NOW));
        assertEquals(PubStatus.SCHEDULED,
                SaveIntent.PUBLISH.status(new Timestamp(NOW + PressroomConstants.MIN_IN_MS + 1000), true, NOW));
    }
}
