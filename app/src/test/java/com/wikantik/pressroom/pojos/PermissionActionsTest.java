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
package com.wikantik.pressroom.pojos;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the action-string handling in {@link PressroomPermission}.
 *
 * <p>Every authorization decision in Pressroom ends up going through these
 * methods: a permission's granted actions live in a single database column as
 * a comma-separated string, and {@code hasAction}/{@code implies} answer
 * "may this user do X?" by parsing that string. A parsing mistake here does
 * not fail loudly -- it silently grants or denies access.
 *
 * <p>The subclass used throughout is {@link WeblogPermission} because
 * {@link ObjectPermission} owns the actual {@code actions} field;
 * {@code PressroomPermission} is abstract and has no storage of its own.
 */
class PermissionActionsTest {

    private static WeblogPermission permissionWith(String actions) {
        WeblogPermission perm = new WeblogPermission();
        perm.setActions(actions);
        return perm;
    }

    @Test
    void actionsRoundTripThroughTheCommaSeparatedColumnValue() {
        WeblogPermission perm = new WeblogPermission();
        perm.setActionsAsList(List.of(WeblogPermission.EDIT_DRAFT, WeblogPermission.POST));

        assertEquals("edit_draft,post", perm.getActions(),
                "Actions are persisted as one comma-separated column value. If the "
                        + "separator changes, every permission row already in the database "
                        + "stops parsing -- keep it a bare comma with no spaces.");
        assertEquals(List.of("edit_draft", "post"), perm.getActionsAsList(),
                "setActionsAsList/getActionsAsList must be exact inverses");
    }

    @Test
    void emptyActionStringYieldsNoActionsRatherThanOneBlankAction() {
        // A permission stripped of its last action is stored as "" (see
        // PressroomPermission.removeActions). If that parsed back as a single
        // empty-string action the permission would look non-empty and
        // JPAUserManagerImpl.revokeWeblogPermission would keep a dead row.
        assertEquals(List.of(), permissionWith("").getActionsAsList(),
                "An empty actions column must parse to zero actions, not one blank one");
        assertTrue(permissionWith("").isEmpty(),
                "A permission with no actions must report isEmpty() so revoke can delete it");
    }

    @Test
    void nullActionsParseToNoActionsInsteadOfThrowing() {
        // pressroom_permission.actions is a nullable column (see V002__baseline_schema.sql)
        // and isEmpty() already anticipates null. getActionsAsList() must agree:
        // before this was fixed it threw NullPointerException, which would take
        // down every permission check for the affected row.
        WeblogPermission perm = permissionWith(null);

        assertEquals(List.of(), perm.getActionsAsList(),
                "A NULL actions column must parse to zero actions. If this throws, "
                        + "PressroomPermission.getActionsAsList() has lost its null guard and "
                        + "any permission row with a NULL actions column will crash "
                        + "UserManager.checkPermission().");
        assertFalse(perm.hasAction(WeblogPermission.ADMIN),
                "A permission with no actions grants nothing");
        assertTrue(perm.isEmpty(), "A NULL actions column means an empty permission");
    }

    @Test
    void actionListReturnedIsSafeToMutate() {
        // addActions/removeActions mutate the list returned by getActionsAsList
        // in place, so it must not be an immutable view.
        WeblogPermission perm = permissionWith(null);
        List<String> actions = perm.getActionsAsList();
        actions.add(WeblogPermission.POST);

        assertEquals(List.of("post"), actions,
                "getActionsAsList() must return a mutable list -- addActions() and "
                        + "removeActions() both add to and remove from it directly");
    }

    @Test
    void hasActionRequiresAnExactMatch() {
        WeblogPermission perm = permissionWith("admin");

        assertTrue(perm.hasAction(WeblogPermission.ADMIN));
        assertFalse(perm.hasAction("admi"),
                "A prefix of a granted action must not match -- otherwise 'post' would "
                        + "be satisfied by a permission granting only 'p'");
        assertFalse(perm.hasAction("administrator"),
                "A superstring of a granted action must not match either");
        assertFalse(perm.hasAction("ADMIN"),
                "Action matching is case sensitive; the constants are all lowercase");
    }

    @Test
    void hasActionsRequiresEveryRequestedAction() {
        WeblogPermission perm = permissionWith("edit_draft,post");

        assertTrue(perm.hasActions(List.of("post", "edit_draft")),
                "Order must not matter when checking a set of actions");
        assertFalse(perm.hasActions(List.of("post", "admin")),
                "hasActions is an AND, not an OR: one missing action denies the whole check");
        assertTrue(perm.hasActions(List.of()),
                "Asking for no actions is trivially satisfied");
    }

    @Test
    void addActionsAppendsOnlyActionsNotAlreadyGranted() {
        // JPAUserManagerImpl.grantWeblogPermission merges into an existing row,
        // so re-granting a permission the user already has must not duplicate it.
        WeblogPermission perm = permissionWith("edit_draft");
        perm.addActions(List.of("post", "edit_draft"));

        assertEquals("edit_draft,post", perm.getActions(),
                "Re-granting an action the user already holds must not duplicate it in "
                        + "the column, and existing actions must keep their position");
    }

    @Test
    void addActionsMergesTheActionsOfAnotherPermission() {
        WeblogPermission target = permissionWith("edit_draft");
        target.addActions(permissionWith("post,admin"));

        assertEquals(List.of("edit_draft", "post", "admin"), target.getActionsAsList(),
                "Merging one permission into another must union their actions");
    }

    @Test
    void removeActionsLeavesTheActionsNotNamed() {
        WeblogPermission perm = permissionWith("edit_draft,post,admin");
        perm.removeActions(List.of("admin"));

        assertEquals(List.of("edit_draft", "post"), perm.getActionsAsList(),
                "Revoking one action must leave the others intact");
        assertFalse(perm.isEmpty(),
                "A permission that still grants something is not empty, so revoke must "
                        + "update the row rather than delete it");
    }

    @Test
    void removingEveryActionEmptiesThePermission() {
        // This is the signal JPAUserManagerImpl.revokeWeblogPermission uses to
        // decide between updating the row and deleting it.
        WeblogPermission perm = permissionWith("edit_draft,post");
        perm.removeActions(new ArrayList<>(List.of("post", "edit_draft")));

        assertTrue(perm.isEmpty(),
                "Revoking every action must leave the permission empty so it gets deleted "
                        + "rather than left behind as a row granting nothing");
    }

    @Test
    void removingAnActionThatWasNeverGrantedIsANoOp() {
        WeblogPermission perm = permissionWith("post");
        perm.removeActions(List.of("admin"));

        assertEquals(List.of("post"), perm.getActionsAsList(),
                "Revoking an action the user never had must not disturb the ones they do");
    }

    @Test
    void isEmptyTreatsNullAndWhitespaceAsNoPermission() {
        assertTrue(permissionWith(null).isEmpty(), "NULL actions column means no permission");
        assertTrue(permissionWith("").isEmpty(), "Empty actions column means no permission");
        assertTrue(permissionWith("   ").isEmpty(),
                "A column holding only whitespace grants nothing and must not keep the row alive");
        assertFalse(permissionWith("post").isEmpty());
    }
}
