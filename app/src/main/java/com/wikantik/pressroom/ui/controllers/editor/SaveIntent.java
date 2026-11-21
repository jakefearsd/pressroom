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

import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.ui.controllers.ContributorRules;
import com.wikantik.pressroom.util.PressroomConstants;
import java.sql.Timestamp;

/**
 * What the author asked a save to do. The one place that decides the status
 * a publish request becomes: POST permission publishes (or schedules, for a
 * pubTime more than a minute out), anything less submits for review, through
 * {@link ContributorRules#ceiling}, the rule the entries API applies too.
 */
enum SaveIntent {
    DRAFT,
    PUBLISH;

    /**
     * @param pubTime the author's chosen publication time, or null
     * @param mayPost whether the user holds {@code WeblogPermission.POST}
     * @param nowMillis the current time
     */
    PubStatus status(Timestamp pubTime, boolean mayPost, long nowMillis) {
        if (this == DRAFT) {
            return PubStatus.DRAFT;
        }
        PubStatus asked = pubTime != null && pubTime.getTime() > nowMillis + PressroomConstants.MIN_IN_MS
                ? PubStatus.SCHEDULED : PubStatus.PUBLISHED;
        return mayPost ? asked : ContributorRules.ceiling(asked);
    }
}
