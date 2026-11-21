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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.ui.restapi.ProblemCode;

/**
 * What a category name, description and removal must satisfy, shared by the
 * category screens and the category API (spec
 * 2026-11-20-jsp-rules-and-view-models-design, Task 3). The rules touch no
 * screen: each returns a {@link RuleViolation}, or null when the value passes,
 * and the screen or the API turns it into its own answer. The API keeps its
 * own required/blank handling (a missing name on create is {@code
 * field.required}, a blank one on rename {@code field.blank}) and calls these
 * with a name it has trimmed; the screens pass what was posted.
 */
public final class CategoryRules {

    private static final Logger log = LoggerFactory.getLogger(CategoryRules.class);

    /** The API's name for the name field (the screen's field id is its own). */
    public static final String NAME = "name";
    public static final String DESCRIPTION = "description";
    public static final String MOVE_TO = "moveTo";

    private CategoryRules() {
    }

    /**
     * The first rule {@code name} breaks on its own, without the store: not
     * blank, at most {@link ColumnWidths#CATEGORY_NAME} characters, and
     * unchanged by HTML escaping (a category name is rendered into pages and
     * the category list; HTML 4 escaping leaves an apostrophe alone, so it
     * passes). Null when it passes.
     */
    public static RuleViolation nameShapeProblem(String name) {
        if (StringUtils.isBlank(name)) {
            return RuleViolation.invalid(NAME, "categoryForm.error.invalidName", List.of(), ProblemCode.FIELD_BLANK,
                    NAME);
        }
        if (name.length() > ColumnWidths.CATEGORY_NAME) {
            return RuleViolation.invalid(NAME, "categoryForm.error.nameTooLong", List.of(),
                    ProblemCode.FIELD_TOO_LONG, NAME, Integer.toString(ColumnWidths.CATEGORY_NAME));
        }
        if (!name.equals(StringEscapeUtils.escapeHtml4(name))) {
            return RuleViolation.invalid(NAME, "categoryForm.error.invalidName", List.of(),
                    ProblemCode.CATEGORY_NAME_INVALID);
        }
        return null;
    }

    /**
     * A conflict when another category of {@code weblog} (not the one with
     * {@code excludingId}, null for none) already has {@code name}, by the
     * manager's by-name lookup. A lookup that fails throws: a caller that
     * reads null as "free" must not be handed one.
     */
    public static RuleViolation nameTaken(Weblog weblog, String name, String excludingId, WeblogEntryManager entries)
            throws WebloggerException {
        WeblogCategory holder = entries.getWeblogCategoryByName(weblog, name);
        if (holder != null && !holder.getId().equals(excludingId)) {
            return RuleViolation.conflict(NAME, "categoryForm.error.duplicateName", List.of(name),
                    ProblemCode.CATEGORY_NAME_IN_USE, name);
        }
        return null;
    }

    /**
     * {@link #nameShapeProblem} then {@link #nameTaken}, for a caller with no
     * way to let a failure through (the screens): a uniqueness check that
     * could not run refuses, with {@code generic.error.check.logs}, rather
     * than reading as "free".
     */
    public static RuleViolation nameProblem(Weblog weblog, String name, String excludingId,
            WeblogEntryManager entries) {
        RuleViolation shape = nameShapeProblem(name);
        if (shape != null) {
            return shape;
        }
        try {
            return nameTaken(weblog, name, excludingId, entries);
        } catch (WebloggerException e) {
            log.error("Error checking category name uniqueness for {}", name, e);
            return RuleViolation.invalid(NAME, "generic.error.check.logs", List.of(),
                    ProblemCode.SERVER_INTERNAL_ERROR);
        }
    }

    /** The description is at most {@link ColumnWidths#CATEGORY_DESCRIPTION} characters; null when it is (or absent). */
    public static RuleViolation descriptionProblem(String description) {
        if (description != null && description.length() > ColumnWidths.CATEGORY_DESCRIPTION) {
            return RuleViolation.invalid(DESCRIPTION, "categoryForm.error.descriptionTooLong", List.of(),
                    ProblemCode.FIELD_TOO_LONG, DESCRIPTION, Integer.toString(ColumnWidths.CATEGORY_DESCRIPTION));
        }
        return null;
    }

    /**
     * A weblog keeps at least one category (an entry saved with none falls
     * back to "the first category found", so a weblog with none can no
     * longer save an entry). A conflict when {@code weblog} has one or none.
     */
    public static RuleViolation lastCategoryProblem(Weblog weblog) {
        if (weblog.getWeblogCategories().size() <= 1) {
            return RuleViolation.conflict(NAME, "categoryForm.error.lastCategory", List.of(),
                    ProblemCode.CATEGORY_LAST_ONE);
        }
        return null;
    }

    /**
     * Whether {@code category} may be removed, moving its entries to
     * {@code moveToId} when that is given: not the weblog's last category;
     * {@code moveToId} (blank means none) not naming the category itself;
     * with no {@code moveToId}, no entries left behind. A count that cannot
     * be read throws. Whether {@code moveToId} names one of the weblog's own
     * categories is the caller's by-id lookup, not this rule's.
     */
    public static RuleViolation removalProblem(Weblog weblog, WeblogCategory category, String moveToId,
            WeblogEntryManager entries) throws WebloggerException {
        RuleViolation last = lastCategoryProblem(weblog);
        if (last != null) {
            return last;
        }
        if (StringUtils.isNotBlank(moveToId)) {
            return moveToId.equals(category.getId())
                    ? RuleViolation.invalid(MOVE_TO, "categoryForm.error.moveToSelf", List.of(),
                            ProblemCode.CATEGORY_MOVE_TO_SELF)
                    : null;
        }
        if (entries.isWeblogCategoryInUse(category)) {
            return RuleViolation.conflict(NAME, "categoryForm.error.hasEntries", List.of(),
                    ProblemCode.CATEGORY_HAS_ENTRIES);
        }
        return null;
    }
}
