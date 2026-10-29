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
import java.util.Comparator;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;

/**
 * Which weblogs a token may be pinned to (spec 2026-10-13, section 3): the
 * weblogs its owner is a member of, or every weblog for a site admin. The one
 * home for the rule, shared by the token form (agent page and Profile) and
 * {@code POST /api/v1/tokens}, like {@link EntryFieldRules}.
 */
public final class TokenPins {

    private TokenPins() {
    }

    /** The weblogs a token for {@code owner} may be pinned to, sorted by handle. */
    public static List<Weblog> choices(Weblogger weblogger, User owner) throws WebloggerException {
        List<Weblog> weblogs = new ArrayList<>();
        if (isSiteAdmin(weblogger, owner)) {
            weblogs.addAll(weblogger.getWeblogManager().getWeblogs(null, null, null, null, 0, -1));
        } else {
            for (WeblogPermission permission : weblogger.getUserManager().getWeblogPermissions(owner)) {
                Weblog weblog = permission.getObjectId() == null ? null
                        : weblogger.getWeblogManager().getWeblogByHandle(permission.getObjectId(), null);
                if (weblog != null) {
                    weblogs.add(weblog);
                }
            }
        }
        weblogs.sort(Comparator.comparing(Weblog::getHandle));
        return weblogs;
    }

    /**
     * Whether a token for {@code owner} may be pinned to {@code handle}. A
     * blank handle -- no pin, "all its weblogs" -- always may; otherwise the
     * weblog must exist and the owner be a site admin or a member of it. A
     * handle the weblog store would reject cannot name a weblog, so it is
     * refused here rather than left to throw out of the lookup.
     */
    public static boolean allows(Weblogger weblogger, User owner, String handle) throws WebloggerException {
        if (handle == null || handle.isBlank()) {
            return true;
        }
        if (!isHandle(handle)) {
            return false;
        }
        Weblog weblog = weblogger.getWeblogManager().getWeblogByHandle(handle, null);
        if (weblog == null) {
            return false;
        }
        return isSiteAdmin(weblogger, owner)
                || weblogger.getUserManager().getWeblogPermission(weblog, owner) != null;
    }

    /** The character rule the weblog store applies to a handle: letters, digits and underscore. */
    private static boolean isHandle(String handle) {
        return handle.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
    }

    private static boolean isSiteAdmin(Weblogger weblogger, User owner) throws WebloggerException {
        return weblogger.getUserManager().checkPermission(
                new GlobalPermission(List.of(GlobalPermission.ADMIN)), owner);
    }
}
