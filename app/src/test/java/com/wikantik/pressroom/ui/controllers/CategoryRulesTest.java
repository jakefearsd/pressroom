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

import java.util.ArrayList;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.ui.controllers.RuleViolation.Kind;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link CategoryRules} with no screen and no API behind it (spec 2026-11-20-jsp-rules-and-view-models-design, Task 3). */
class CategoryRulesTest {

    private WeblogEntryManager entries;
    private Weblog weblog;
    private WeblogCategory travel;

    @BeforeEach
    void setUp() throws Exception {
        entries = mock(WeblogEntryManager.class);
        weblog = new Weblog();
        weblog.setWeblogCategories(new ArrayList<>());
        travel = category("c1", "Travel");
    }

    private WeblogCategory category(String id, String name) throws Exception {
        WeblogCategory made = new WeblogCategory();
        made.setId(id);
        made.setName(name);
        made.setWeblog(weblog);
        weblog.getWeblogCategories().add(made);
        when(entries.getWeblogCategoryByName(weblog, name)).thenReturn(made);
        return made;
    }

    @Test
    void aBlankNameIsTheInvalidNameErrorAndTheApisBlank() {
        for (String blank : new String[] {null, "", "   "}) {
            RuleViolation v = CategoryRules.nameShapeProblem(blank);
            assertEquals("categoryForm.error.invalidName", v.messageKey());
            assertEquals(ProblemCode.FIELD_BLANK, v.code());
            assertEquals(List.of("name"), v.codeArgs());
            assertEquals(Kind.INVALID, v.kind());
        }
    }

    @Test
    void aNameOver255IsTooLongAndOneOf255IsNot() {
        RuleViolation v = CategoryRules.nameShapeProblem("n".repeat(256));
        assertEquals("categoryForm.error.nameTooLong", v.messageKey());
        assertEquals(ProblemCode.FIELD_TOO_LONG, v.code());
        assertEquals(List.of("name", "255"), v.codeArgs());
        assertNull(CategoryRules.nameShapeProblem("n".repeat(255)));
    }

    @Test
    void aNameHtmlEscapingWouldChangeIsInvalidButAnApostropheIsNot() {
        for (String bad : new String[] {"a&b", "a<b", "a>b", "a\"b"}) {
            RuleViolation v = CategoryRules.nameShapeProblem(bad);
            assertEquals(ProblemCode.CATEGORY_NAME_INVALID, v.code(), bad);
            assertEquals("categoryForm.error.invalidName", v.messageKey());
            assertEquals(Kind.INVALID, v.kind());
        }
        assertNull(CategoryRules.nameShapeProblem("Can't miss"));
    }

    @Test
    void theLengthIsJudgedBeforeTheEscaping() {
        assertEquals(ProblemCode.FIELD_TOO_LONG, CategoryRules.nameShapeProblem("&".repeat(256)).code());
    }

    @Test
    void aTakenNameIsAConflictNamingItAndTheCategoryBeingRenamedIsExcluded() throws Exception {
        RuleViolation v = CategoryRules.nameTaken(weblog, "Travel", null, entries);
        assertEquals(Kind.CONFLICT, v.kind());
        assertEquals(ProblemCode.CATEGORY_NAME_IN_USE, v.code());
        assertEquals(List.of("Travel"), v.codeArgs());
        assertEquals(List.of((Object) "Travel"), v.args());
        assertEquals("categoryForm.error.duplicateName", v.messageKey());
        assertNull(CategoryRules.nameTaken(weblog, "Travel", "c1", entries));
        assertNull(CategoryRules.nameTaken(weblog, "Food", null, entries));
        assertEquals(ProblemCode.CATEGORY_NAME_IN_USE, CategoryRules.nameTaken(weblog, "Travel", "c2", entries).code());
    }

    @Test
    void aFailedLookupThrowsFromTheTakenCheck() throws Exception {
        when(entries.getWeblogCategoryByName(weblog, "Food")).thenThrow(new WebloggerException("down"));
        assertThrows(WebloggerException.class, () -> CategoryRules.nameTaken(weblog, "Food", null, entries));
    }

    @Test
    void theCompositeRefusesWhenTheLookupCannotRun() throws Exception {
        when(entries.getWeblogCategoryByName(weblog, "Food")).thenThrow(new WebloggerException("down"));
        RuleViolation v = CategoryRules.nameProblem(weblog, "Food", null, entries);
        assertEquals("generic.error.check.logs", v.messageKey());
        assertEquals(ProblemCode.SERVER_INTERNAL_ERROR, v.code());
    }

    @Test
    void theCompositeJudgesTheShapeBeforeLookingAnythingUp() throws Exception {
        assertEquals(ProblemCode.CATEGORY_NAME_INVALID, CategoryRules.nameProblem(weblog, "a&b", null, entries).code());
        verify(entries, never()).getWeblogCategoryByName(weblog, "a&b");
        assertEquals(ProblemCode.CATEGORY_NAME_IN_USE, CategoryRules.nameProblem(weblog, "Travel", null, entries).code());
        assertNull(CategoryRules.nameProblem(weblog, "Food", null, entries));
    }

    @Test
    void aDescriptionIsAtMost255() {
        assertNull(CategoryRules.descriptionProblem(null));
        assertNull(CategoryRules.descriptionProblem("d".repeat(255)));
        RuleViolation v = CategoryRules.descriptionProblem("d".repeat(256));
        assertEquals("description", v.field());
        assertEquals(ProblemCode.FIELD_TOO_LONG, v.code());
        assertEquals(List.of("description", "255"), v.codeArgs());
    }

    @Test
    void theLastCategoryIsAConflict() throws Exception {
        RuleViolation v = CategoryRules.removalProblem(weblog, travel, null, entries);
        assertEquals(ProblemCode.CATEGORY_LAST_ONE, v.code());
        assertEquals(Kind.CONFLICT, v.kind());
        assertEquals("categoryForm.error.lastCategory", v.messageKey());
        assertEquals(v.code(), CategoryRules.lastCategoryProblem(weblog).code());
        category("c2", "Food");
        assertNull(CategoryRules.lastCategoryProblem(weblog));
        // the last-category refusal comes before any count is taken
        verify(entries, never()).isWeblogCategoryInUse(travel);
    }

    @Test
    void aMoveToNamingTheCategoryItselfIsRefusedOnMoveTo() throws Exception {
        category("c2", "Food");
        RuleViolation v = CategoryRules.removalProblem(weblog, travel, "c1", entries);
        assertEquals("moveTo", v.field());
        assertEquals(ProblemCode.CATEGORY_MOVE_TO_SELF, v.code());
        assertEquals(Kind.INVALID, v.kind());
    }

    @Test
    void aMoveToElsewhereIsLeftToTheCallersLookupAndCountsNothing() throws Exception {
        category("c2", "Food");
        assertNull(CategoryRules.removalProblem(weblog, travel, "c2", entries));
        verify(entries, never()).isWeblogCategoryInUse(travel);
    }

    @Test
    void aCategoryInUseWithNoMoveToIsAConflictAndABlankMoveToIsNone() throws Exception {
        category("c2", "Food");
        when(entries.isWeblogCategoryInUse(travel)).thenReturn(true);
        for (String none : new String[] {null, "", "  "}) {
            RuleViolation v = CategoryRules.removalProblem(weblog, travel, none, entries);
            assertEquals(ProblemCode.CATEGORY_HAS_ENTRIES, v.code());
            assertEquals(Kind.CONFLICT, v.kind());
        }
        when(entries.isWeblogCategoryInUse(travel)).thenReturn(false);
        assertNull(CategoryRules.removalProblem(weblog, travel, null, entries));
    }

    @Test
    void aCountThatCannotBeReadThrows() throws Exception {
        category("c2", "Food");
        when(entries.isWeblogCategoryInUse(travel)).thenThrow(new WebloggerException("down"));
        assertThrows(WebloggerException.class, () -> CategoryRules.removalProblem(weblog, travel, null, entries));
    }
}
