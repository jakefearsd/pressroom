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
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a member without publish rights -- a contributor, holding the weblog's
 * {@code EDIT_DRAFT} but not {@code POST} -- may do to an entry, as one rule
 * for the entry editor ({@code EntryEditController}, {@code
 * EntryRemoveController}, through {@code BaseController.lookupChangeableEntry}
 * and {@code SaveIntent}) and the entries API ({@code EntriesWriteApi}; spec
 * 2026-11-21 owner decisions, Task 3). Both surfaces are gated on {@code
 * EDIT_DRAFT} and decide the rest here, from the caller's permission.
 */
public final class ContributorRules {

    private static final Logger log = LoggerFactory.getLogger(ContributorRules.class);

    private ContributorRules() {
    }

    /**
     * Whether someone without {@code POST} may change {@code entry}: only
     * their own, and only while it is a draft or pending review. Saving over
     * someone else's post, or any published one, would rewrite it or demote it
     * off the blog (code review, 2026-11-03). A holder of {@code POST} is not
     * asked this.
     */
    public static boolean mayChange(WeblogEntry entry, User user) {
        boolean own = user != null && user.getUserName() != null
                && user.getUserName().equals(entry.getCreatorUserName());
        return own && (entry.getStatus() == PubStatus.DRAFT || entry.getStatus() == PubStatus.PENDING);
    }

    /**
     * The status a save by someone without {@code POST} lands in: publishing
     * or scheduling is submitted for review ({@code PENDING}), as the editor's
     * Publish button does for them; anything else stands.
     */
    public static PubStatus ceiling(PubStatus requested) {
        return requested == PubStatus.PUBLISHED || requested == PubStatus.SCHEDULED ? PubStatus.PENDING : requested;
    }

    /**
     * Whether {@code user} holds {@code POST} on {@code weblog}. A check that
     * cannot be answered is a no, so the caller is held to a contributor's
     * rules rather than handed a publisher's.
     */
    public static boolean mayPublish(UserManager users, Weblog weblog, User user) {
        try {
            return users.checkPermission(new WeblogPermission(weblog, user, List.of(WeblogPermission.POST)), user);
        } catch (WebloggerException e) {
            log.error("Could not check publish rights on weblog {}; treating the caller as a contributor",
                    weblog.getHandle(), e);
            return false;
        }
    }

    /**
     * Whether {@code user} is a site administrator, the one who may pin an entry to the main page. A check that
     * cannot be answered is a no.
     */
    public static boolean isSiteAdmin(UserManager users, User user) {
        try {
            return users.checkPermission(new GlobalPermission(List.of(GlobalPermission.ADMIN)), user);
        } catch (WebloggerException e) {
            log.error("Could not check whether {} is a site administrator; treating them as not", user.getUserName(), e);
            return false;
        }
    }
}
