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

import java.util.Collection;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;

/**
 * The rules both membership screens share: a weblog's Members page
 * ({@code MembersController}) and the Weblogs section of a person's or an
 * agent's page ({@code UserWeblogsController}). One level per member, from
 * {@link #LEVELS} only, and every change recorded with
 * {@link AdminLog#record} in the same words.
 *
 * <p>The decisions both screens make live here too (spec
 * 2026-11-20-jsp-rules-and-view-models-design, Task 2): whether a value is a
 * level, whether an account may hold a membership, whether group blogging
 * lets anyone new in, whether a change leaves a weblog with no Admin, and the
 * Members page's rule that nobody changes their own membership there. They
 * answer in message keys and booleans, and each screen words a refusal as it
 * always has: no API manages memberships yet, so no problem code is minted for
 * them (Tier 4 adds one with the members resource).
 */
public final class Memberships {

    /** The levels a member may hold, most to least: Admin, Author, Limited. */
    public static final List<String> LEVELS =
            List.of(WeblogPermission.ADMIN, WeblogPermission.POST, WeblogPermission.EDIT_DRAFT);

    /** The value a member's row on the Members page posts to remove them. */
    public static final String REMOVE = "-1";

    private Memberships() {
    }

    /**
     * The message key refusing {@code value} as a member's level, or null:
     * one of {@link #LEVELS}, or {@link #REMOVE} where the caller allows a
     * removal (the Members page's table). Never an action list, and no value
     * at all is not a level.
     */
    public static String levelProblem(String value, boolean removalAllowed) {
        boolean level = value != null && (LEVELS.contains(value) || removalAllowed && REMOVE.equals(value));
        return level ? null : "userWeblogs.error.level";
    }

    /**
     * Whether {@code account} may hold a membership at all: it holds the
     * global login action, which a reader account does not (a weblog
     * permission on one would be dead weight and a promotion path). A check
     * that fails throws, and the caller refuses.
     */
    public static boolean mayHold(UserManager users, User account) throws WebloggerException {
        return users.checkPermission(new GlobalPermission(List.of(GlobalPermission.LOGIN)), account);
    }

    /**
     * Whether {@code groupblogging.enabled} is off, so nobody new may be given
     * a membership (changes and removals stay open, so an operator can undo
     * the members the setting left behind). Each screen words the refusal
     * its own way.
     */
    public static boolean groupBloggingOff() {
        return !WebloggerRuntimeConfig.getBooleanProperty("groupblogging.enabled");
    }

    /**
     * The Members page's rule that only another admin may alter your role:
     * the message key refusing {@code postedValue} on {@code memberUserName}'s
     * row when that row is the actor's own and the value is not Admin, or null.
     */
    public static String selfChangeProblem(String actorUserName, String memberUserName, String postedValue) {
        boolean own = memberUserName.equals(actorUserName) && !WeblogPermission.ADMIN.equals(postedValue);
        return own ? "memberPermissions.noSelfModifications" : null;
    }

    /**
     * Whether the Members page's whole-table submission leaves the weblog
     * with no Admin: {@code postedLevels} holds what each row posted (a row
     * that posted nothing is not in it, and is not counted, whatever it
     * holds), and none of them is Admin.
     */
    public static boolean leavesNoAdmin(Collection<String> postedLevels) {
        return postedLevels.stream().noneMatch(WeblogPermission.ADMIN::equals);
    }

    /**
     * Whether one change on a person's page leaves the weblog with no Admin:
     * {@code current} is the membership changed and {@code newLevel} its new
     * level, or {@link #REMOVE}. Only taking Admin from a member who holds it
     * can, and then only from the weblog's only Admin, so only then are the
     * weblog's memberships counted. A count that fails throws, and the caller
     * refuses the change.
     */
    public static boolean leavesNoAdmin(UserManager users, Weblog weblog, WeblogPermission current, String newLevel)
            throws WebloggerException {
        if (WeblogPermission.ADMIN.equals(newLevel) || !current.hasAction(WeblogPermission.ADMIN)) {
            return false;
        }
        int admins = 0;
        for (WeblogPermission permission : users.getWeblogPermissions(weblog)) {
            if (permission.hasAction(WeblogPermission.ADMIN)) {
                admins++;
            }
        }
        return admins <= 1;
    }

    /** The admin-log details naming a membership: {@code account=<name> weblog=<handle>}. */
    public static String details(User account, Weblog weblog) {
        return "account=" + account.getUserName() + " weblog=" + weblog.getHandle();
    }
}
