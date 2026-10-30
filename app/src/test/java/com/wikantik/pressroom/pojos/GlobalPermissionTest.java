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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down {@link GlobalPermission}, the site-wide half of Pressroom's
 * authorization model.
 *
 * <p>Its actions form a ladder -- {@code admin} &gt; {@code weblog} &gt;
 * {@code login} -- and {@code JPAUserManagerImpl.checkPermission} consults it
 * for every request that is not satisfied by a per-weblog grant. The two
 * behaviours worth guarding are (a) that the ladder only ever grants
 * downwards, and (b) that a global grant does <em>not</em> hand out per-weblog
 * rights unless the holder is a site admin.
 */
class GlobalPermissionTest {

    private static GlobalPermission held(String... actions) throws Exception {
        return new GlobalPermission(List.of(actions));
    }

    @Test
    void siteAdminImpliesEverySiteWideAction() throws Exception {
        GlobalPermission admin = held(GlobalPermission.ADMIN);

        assertTrue(admin.implies(held(GlobalPermission.ADMIN)));
        assertTrue(admin.implies(held(GlobalPermission.WEBLOG)));
        assertTrue(admin.implies(held(GlobalPermission.LOGIN)));
    }

    @Test
    void weblogRightsImplyLoginButNotSiteAdministration() throws Exception {
        GlobalPermission blogger = held(GlobalPermission.WEBLOG);

        assertTrue(blogger.implies(held(GlobalPermission.WEBLOG)));
        assertTrue(blogger.implies(held(GlobalPermission.LOGIN)),
                "Anyone allowed to blog is allowed to log in");
        assertFalse(blogger.implies(held(GlobalPermission.ADMIN)),
                "An ordinary blogger must NOT be granted site administration");
        assertFalse(blogger.implies(held(GlobalPermission.LOGIN, GlobalPermission.ADMIN)),
                "A demand that includes admin must be denied even when its other actions "
                        + "are held -- checking only the first action would let this through");
    }

    @Test
    void loginOnlyImpliesNothingBeyondLogin() throws Exception {
        GlobalPermission visitor = held(GlobalPermission.LOGIN);

        assertTrue(visitor.implies(held(GlobalPermission.LOGIN)));
        assertFalse(visitor.implies(held(GlobalPermission.WEBLOG)),
                "A login-only account must NOT be allowed to blog");
        assertFalse(visitor.implies(held(GlobalPermission.ADMIN)),
                "A login-only account must NOT be allowed to administer the site");
    }

    @Test
    void anUnsavedUserWithNoActionsImpliesNothing() throws Exception {
        // A user that has not been granted any role yet parses to zero actions.
        // Without the explicit guard in implies() the method would fall through
        // its ladder and return true, granting a brand new account everything.
        GlobalPermission newAccount = held();

        assertFalse(newAccount.implies(held(GlobalPermission.LOGIN)),
                "A permission granting no actions must imply nothing at all");
        assertFalse(newAccount.implies(held(GlobalPermission.ADMIN)));
        assertFalse(newAccount.implies(new WeblogPermission()),
                "A permission granting no actions must not imply a weblog permission either");
    }

    @Test
    void onlySiteAdminsAreHandedPerWeblogRightsGlobally() throws Exception {
        WeblogPermission weblogAdmin = new WeblogPermission();
        weblogAdmin.setActionsAsList(List.of(WeblogPermission.ADMIN));
        WeblogPermission weblogDraft = new WeblogPermission();
        weblogDraft.setActionsAsList(List.of(WeblogPermission.EDIT_DRAFT));

        assertTrue(held(GlobalPermission.ADMIN).implies(weblogAdmin),
                "A site admin must be able to administer any weblog -- this is the "
                        + "escape hatch checkPermission() relies on for the admin UI");
        assertTrue(held(GlobalPermission.ADMIN).implies(weblogDraft));

        assertFalse(held(GlobalPermission.WEBLOG).implies(weblogDraft),
                "Being allowed to blog somewhere must NOT grant rights on somebody "
                        + "else's weblog; that has to come from a WeblogPermission row");
        assertFalse(held(GlobalPermission.LOGIN).implies(weblogDraft));
    }

    @Test
    void permissionsOutsidePressroomsOwnHierarchyAreNeverImplied() throws Exception {
        assertFalse(held(GlobalPermission.ADMIN).implies(new BasicPermission("anything") { }),
                "Even a site admin must not imply an arbitrary java.security.Permission -- "
                        + "implies() has to stay closed over Pressroom's own permission types");
    }

    @Test
    void permissionForUserIsBuiltFromTheActionsTheirRolesImply() throws Exception {
        // The role -> action mapping lives in pressroom.properties as
        // role.action.<role>. Roles overlap (admin and editor both grant login
        // and weblog), so the union must not repeat an action.
        User user = new User();
        user.setUserName("bob");

        // The mapping is a pure function of the user's roles and the
        // role.action.<role> config; resolving the roles is the user manager's
        // job (JPAUserManagerImpl.globalPermissionOf) and no longer happens
        // inside a pojo constructor through the static service locator.
        GlobalPermission perm = GlobalPermission.impliedByRoles(user, List.of("admin", "editor"));

        List<String> actions = perm.getActionsAsList();
        assertTrue(actions.contains(GlobalPermission.ADMIN),
                "The 'admin' role must contribute the admin action; check "
                        + "role.action.admin in pressroom.properties");
        assertTrue(actions.contains(GlobalPermission.WEBLOG));
        assertTrue(actions.contains(GlobalPermission.LOGIN));
        assertEquals(actions.size(), actions.stream().distinct().count(),
                "Actions implied by more than one of the user's roles must appear once, "
                        + "not once per role: " + actions);
        assertTrue(perm.getName().contains("bob"),
                "The permission name identifies the user it was built for, which is what "
                        + "the debug logging in checkPermission() prints");
    }

    @Test
    void permissionForUserWithNoRecognisedRoleGrantsNothing() throws Exception {
        User user = new User();
        user.setUserName("nobody");

        GlobalPermission perm = GlobalPermission.impliedByRoles(user, List.of("role-with-no-mapping"));

        assertTrue(perm.isEmpty(),
                "A role with no role.action.<role> mapping must contribute no actions "
                        + "rather than being taken as its own action name");
    }

    @Test
    void equalityIsByActionsAlone() throws Exception {
        assertEquals(held(GlobalPermission.LOGIN), held(GlobalPermission.LOGIN));
        assertEquals(held(GlobalPermission.LOGIN).hashCode(), held(GlobalPermission.LOGIN).hashCode());
        assertNotEquals(held(GlobalPermission.LOGIN), held(GlobalPermission.ADMIN),
                "Permissions granting different actions must not compare equal");
    }

    @Test
    void toStringListsTheGrantedActions() throws Exception {
        // checkPermission() logs the permission on failure; if the actions were
        // dropped from the message the log entry would say nothing useful.
        assertTrue(held(GlobalPermission.WEBLOG, GlobalPermission.LOGIN).toString()
                        .contains(GlobalPermission.WEBLOG),
                "toString() must name the actions so a denied permission check is diagnosable");
    }

    private static GlobalPermission holder(String... actions) throws Exception {
        return held(actions);
    }

    private static GlobalPermission required(String... actions) throws Exception {
        return held(actions);
    }

    private static User user() {
        User u = new User();
        u.setUserName("reader-test");
        return u;
    }

    @Test
    void aHolderOfOnlyCommentImpliesNothingButComment() throws Exception {
        GlobalPermission reader = holder(GlobalPermission.COMMENT);
        assertTrue(reader.implies(required(GlobalPermission.COMMENT)));
        for (String a : List.of(GlobalPermission.LOGIN, GlobalPermission.WEBLOG, GlobalPermission.ADMIN)) {
            assertFalse(reader.implies(required(a)), a);
        }
        assertFalse(reader.implies(required(GlobalPermission.LOGIN, GlobalPermission.COMMENT)));
    }

    @Test
    void anUnknownActionIsImpliedOnlyByAdmin() throws Exception {
        assertFalse(holder(GlobalPermission.LOGIN, GlobalPermission.WEBLOG).implies(required("frobnicate")));
        assertFalse(holder(GlobalPermission.COMMENT).implies(required("frobnicate")));
        assertTrue(holder(GlobalPermission.ADMIN).implies(required("frobnicate")));
    }

    @Test
    void editorsAndAdminsHoldComment() throws Exception {
        // via role.action.* in the shipped pressroom.properties
        assertTrue(GlobalPermission.impliedByRoles(user(), List.of("editor"))
                .implies(required(GlobalPermission.COMMENT)));
        assertTrue(GlobalPermission.impliedByRoles(user(), List.of("admin"))
                .implies(required(GlobalPermission.COMMENT)));
        GlobalPermission reader = GlobalPermission.impliedByRoles(user(), List.of("reader"));
        assertTrue(reader.implies(required(GlobalPermission.COMMENT)));
        assertFalse(reader.implies(required(GlobalPermission.LOGIN)));
    }

    /**
     * Characterisation test: passes against the old code and the new. A
     * non-admin holder never implies a weblog permission, and an empty holder
     * implies no global action.
     */
    @Test
    void nonAdminHoldersNeverImplyAWeblogPermission() throws Exception {
        WeblogPermission wp = new WeblogPermission();
        wp.setActionsAsList(List.of(WeblogPermission.ADMIN));
        for (String a : List.of(GlobalPermission.LOGIN, GlobalPermission.WEBLOG, GlobalPermission.COMMENT)) {
            assertFalse(holder(a).implies(wp), a);
        }
        assertFalse(holder().implies(required(GlobalPermission.COMMENT)));
    }
}
