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

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.restapi.ProblemCode;

/**
 * The strand an entry is asked to join (spec
 * 2026-11-20-jsp-rules-and-view-models-design, Task 4), judged the same way
 * for the entry editor and the entries API: nothing to join when no strand
 * is named or the entry is already in it; otherwise the id must name one of
 * this blog's strand pages. Blank handling is the caller's: the editor's
 * blank choice means "take it out of its strand" and the API's means "not a
 * strand page", so a blank id reaches here only from the API.
 */
public final class StrandChoice {

    /** The API's name for the strand field. */
    public static final String STRAND = "strand";

    private StrandChoice() {
    }

    /**
     * What a request to join a strand comes to.
     *
     * @param page      the strand page to append the entry to; null when nothing is to be joined or it is refused
     * @param violation why the request is refused; null when it is not
     */
    public record Choice(WeblogPage page, RuleViolation violation) {
        static final Choice NO_CHANGE = new Choice(null, null);
    }

    /**
     * Resolves {@code strandId} for {@code entry} of {@code weblog}. Another
     * blog's page, a trashed one, an unknown id, a blank one, a page that is
     * not a strand and a lookup that fails are one refusal on {@code strand}
     * ({@link WeblogOwnership#page} is the ownership check and answers null
     * for the lookups that fail, so they refuse).
     */
    public static Choice resolve(Weblogger weblogger, Weblog weblog, WeblogEntry entry, String strandId) {
        if (strandId == null || strandId.equals(entry.getStrandPageId())) {
            return Choice.NO_CHANGE;
        }
        WeblogPage page = WeblogOwnership.page(weblogger, strandId, weblog);
        if (page == null || !Boolean.TRUE.equals(page.getStrand())) {
            return new Choice(null, RuleViolation.invalid(STRAND, "weblogEdit.error.notAStrand", List.of(),
                    ProblemCode.STRAND_ID_NOT_STRAND_PAGE));
        }
        return new Choice(page, null);
    }
}
