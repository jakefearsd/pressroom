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

import java.security.BasicPermission;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down {@link WeblogPermission#implies}, the per-weblog half of Pressroom's
 * authorization model.
 *
 * <p>{@code JPAUserManagerImpl.checkPermission} loads the permission row the
 * user holds on a weblog and asks it whether it implies the permission the
 * caller is demanding. The three actions form a strict ladder --
 * {@code admin} &gt; {@code post} &gt; {@code edit_draft} -- and every
 * "can this user edit/publish/administer this blog?" decision reduces to a
 * single {@code implies} call. A wrong answer here is a silent authorization
 * failure in either direction, so each rung of the ladder is asserted
 * explicitly rather than through a loop.
 */
class WeblogPermissionTest {

    private static final String HANDLE = "testblog";

    private static WeblogPermission held(String... actions) {
        WeblogPermission perm = new WeblogPermission();
        perm.setActionsAsList(List.of(actions));
        perm.setObjectId(HANDLE);
        return perm;
    }

    private static WeblogPermission demanded(String... actions) {
        return held(actions);
    }

    @Test
    void adminImpliesEveryWeblogAction() {
        WeblogPermission admin = held(WeblogPermission.ADMIN);

        assertTrue(admin.implies(demanded(WeblogPermission.ADMIN)),
                "A weblog admin must imply admin");
        assertTrue(admin.implies(demanded(WeblogPermission.POST)),
                "A weblog admin must imply post");
        assertTrue(admin.implies(demanded(WeblogPermission.EDIT_DRAFT)),
                "A weblog admin must imply edit_draft");
        assertTrue(admin.implies(demanded(WeblogPermission.ALL_ACTIONS.toArray(new String[0]))),
                "A weblog admin must imply all three actions demanded together");
    }

    @Test
    void postImpliesDraftEditingButNotAdministration() {
        WeblogPermission author = held(WeblogPermission.POST);

        assertTrue(author.implies(demanded(WeblogPermission.POST)));
        assertTrue(author.implies(demanded(WeblogPermission.EDIT_DRAFT)),
                "Being able to publish must imply being able to edit a draft");
        assertFalse(author.implies(demanded(WeblogPermission.ADMIN)),
                "An author must NOT be able to administer the weblog -- that would let "
                        + "any author add and remove the blog's other members");
        assertFalse(author.implies(demanded(WeblogPermission.POST, WeblogPermission.ADMIN)),
                "A demand that includes admin must be denied even when the other actions "
                        + "in it are held");
    }

    @Test
    void editDraftImpliesOnlyDraftEditing() {
        WeblogPermission limited = held(WeblogPermission.EDIT_DRAFT);

        assertTrue(limited.implies(demanded(WeblogPermission.EDIT_DRAFT)));
        assertFalse(limited.implies(demanded(WeblogPermission.POST)),
                "A limited (draft-only) member must NOT be able to publish -- this is the "
                        + "check that keeps contributions in the moderation queue");
        assertFalse(limited.implies(demanded(WeblogPermission.ADMIN)),
                "A limited member must NOT be able to administer the weblog");
    }

    @Test
    void aPermissionGrantingNothingImpliesNothing() {
        // pressroom_permission.actions is nullable and revoking the last action
        // leaves "". Before this was fixed, implies() fell through every branch
        // and returned true, so a permission row granting *nothing* implied
        // *everything*, including weblog administration.
        for (WeblogPermission empty : List.of(held(), permissionWithActions(null), permissionWithActions(""))) {
            assertFalse(empty.implies(demanded(WeblogPermission.EDIT_DRAFT)),
                    "A permission that grants no actions must not imply edit_draft. "
                            + "WeblogPermission.implies() needs the same empty-actions guard "
                            + "GlobalPermission.implies() has, otherwise an empty or NULL "
                            + "actions column silently grants full access.");
            assertFalse(empty.implies(demanded(WeblogPermission.POST)),
                    "A permission that grants no actions must not imply post");
            assertFalse(empty.implies(demanded(WeblogPermission.ADMIN)),
                    "A permission that grants no actions must not imply admin");
        }
    }

    private static WeblogPermission permissionWithActions(String actions) {
        WeblogPermission perm = new WeblogPermission();
        perm.setActions(actions);
        return perm;
    }

    @Test
    void aWeblogPermissionNeverImpliesAPermissionOfAnotherKind() throws Exception {
        // GlobalPermission is deliberately not satisfiable by a per-weblog grant:
        // owning a blog must never confer site-wide rights.
        WeblogPermission admin = held(WeblogPermission.ADMIN);

        assertFalse(admin.implies(new GlobalPermission(List.of(GlobalPermission.ADMIN))),
                "Weblog admin must NOT imply site-wide admin");
        assertFalse(admin.implies(new BasicPermission("something") { }),
                "An unrelated java.security.Permission must never be implied");
    }

    @Test
    void constructorDerivesUserAndWeblogFromTheObjectsPassedIn() {
        Weblog weblog = new Weblog();
        weblog.setHandle(HANDLE);
        User user = new User();
        user.setUserName("bob");

        WeblogPermission perm = new WeblogPermission(weblog, user, WeblogPermission.POST);

        assertEquals(HANDLE, perm.getObjectId(),
                "The permission is keyed on the weblog handle, not its id -- the named "
                        + "queries in WeblogPermission.orm.xml look it up by handle");
        assertEquals("bob", perm.getUserName());
        assertEquals("post", perm.getActions());
    }

    @Test
    void weblogOnlyConstructorLeavesThePermissionUnassigned() {
        // Used when asking "does anyone hold this permission on this weblog?",
        // so it must not accidentally look like it belongs to a user.
        Weblog weblog = new Weblog();
        weblog.setHandle(HANDLE);

        WeblogPermission perm = new WeblogPermission(weblog, List.of(WeblogPermission.ADMIN));

        assertNull(perm.getUserName(),
                "A weblog-only permission has no user; leaving a stale username here "
                        + "would make it match a real grant");
        assertEquals(HANDLE, perm.getObjectId());
        assertEquals("admin", perm.getActions(),
                "The actions asked for must actually be recorded, or the permission "
                        + "grants nothing and implies nothing");
    }

    @Test
    void toStringNamesThePermissionTypeAndItsActions() {
        // checkPermission() logs the permission when a check fails. It used to
        // label a WeblogPermission "GlobalPermission", which sends whoever reads
        // the log looking at the wrong half of the authorization model.
        String rendered = held(WeblogPermission.POST).toString();

        assertTrue(rendered.startsWith("WeblogPermission"),
                "A weblog permission must not describe itself as a GlobalPermission: " + rendered);
        assertTrue(rendered.contains(WeblogPermission.POST),
                "toString() must name the actions so a denied permission check is diagnosable");
    }

    @Test
    void equalityIsByUserWeblogAndActions() {
        Weblog weblog = new Weblog();
        weblog.setHandle(HANDLE);
        User user = new User();
        user.setUserName("bob");

        WeblogPermission perm = new WeblogPermission(weblog, user, "post");
        WeblogPermission same = new WeblogPermission(weblog, user, "post");
        WeblogPermission escalated = new WeblogPermission(weblog, user, "admin");

        assertEquals(perm, same, "Two grants of the same actions to the same user on the "
                + "same weblog are the same permission");
        assertEquals(perm.hashCode(), same.hashCode());
        assertNotEquals(perm, escalated,
                "Permissions differing only in their actions must not compare equal -- "
                        + "otherwise a set of permissions could silently swallow an escalation");
    }
}
