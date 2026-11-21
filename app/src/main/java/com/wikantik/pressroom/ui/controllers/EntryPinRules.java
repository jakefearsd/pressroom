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

package com.wikantik.pressroom.ui.controllers;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.EntryPins;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ProblemCode;

/**
 * Whether a save may pin an entry (spec
 * 2026-11-20-jsp-rules-and-view-models-design, Task 4): {@link EntryPins}'s
 * limit, read against the blog's pinned posts, as one rule for the entry
 * editor and the entries API. It sits here, beside the other rules that
 * return a {@link RuleViolation}, rather than in the business tier's
 * {@code EntryPins}, which must not import a controller type.
 */
public final class EntryPinRules {

    /** The API's name for the pin field. */
    public static final String PINNED = "pinned";

    private EntryPinRules() {
    }

    /**
     * A refusal when saving the entry as {@code wanted} would take {@code weblog}
     * past {@link EntryPins#MAX_PER_WEBLOG}; null when it would not. Only a new
     * pin can: re-saving a pinned entry and unpinning never count. A count that
     * cannot be read throws: it cannot vouch for the limit, and a caller that
     * reads null as "room" must not be handed one.
     */
    public static RuleViolation roomProblem(WeblogEntryManager entries, Weblog weblog, boolean wasPinned,
            boolean wanted) throws WebloggerException {
        if (!wanted || wasPinned) {
            return null;
        }
        long pinned = entries.getPinnedEntryCount(weblog);
        if (EntryPins.wouldExceed(wasPinned, wanted, pinned)) {
            return RuleViolation.invalid(PINNED, "weblogEdit.error.pinLimit", List.of(EntryPins.MAX_PER_WEBLOG),
                    ProblemCode.PINNED_TOO_MANY, Integer.toString(EntryPins.MAX_PER_WEBLOG));
        }
        return null;
    }
}
