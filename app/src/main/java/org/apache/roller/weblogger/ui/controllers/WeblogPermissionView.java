/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.controllers;

import java.util.ArrayList;
import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A weblog permission as the admin JSPs see it: the permission plus the
 * weblog and user it names, resolved once by the controller through the
 * facade it holds.
 *
 * <p>{@code WeblogPermission} stores a weblog <em>handle</em> and a user
 * <em>name</em>. It used to resolve both lazily from inside getters
 * ({@code getWeblog()}, {@code getUser()}) through the static service
 * locator, which is how {@code ${perms.weblog.name}} and
 * {@code ${perm.user.id}} in {@code MainMenu.jsp}, {@code UserEdit.jsp} and
 * {@code Members.jsp} worked. The entity is data now; this row is what the
 * JSPs iterate instead, keeping the same {@code weblog} / {@code user} /
 * {@code hasAction(..)} property names so the pages did not have to change.
 *
 * <p>{@link #getEntryCount()} is what {@code ${perms.weblog.entryCount}} on
 * the main menu used to be -- a query behind a getter. It is asked of the
 * entry manager on first use and remembered, because only the main menu
 * shows it and a row built for the members page should not pay for a count
 * nobody reads.
 */
public final class WeblogPermissionView {

    private static final Logger log = LoggerFactory.getLogger(WeblogPermissionView.class);

    private final WeblogPermission permission;
    private final Weblog weblog;
    private final User user;
    private final Weblogger weblogger;
    private Long entryCount;
    private Long pendingCommentCount;

    private WeblogPermissionView(WeblogPermission permission, Weblog weblog, User user,
            Weblogger weblogger) {
        this.permission = permission;
        this.weblog = weblog;
        this.user = user;
        this.weblogger = weblogger;
    }

    /**
     * Resolves the weblog (by handle, {@code getWeblogByHandle(handle, null)}
     * -- inactive weblogs included, exactly as the entity's getter did) and the
     * user (by name). A permission naming neither resolves to nulls without
     * consulting the tier.
     */
    public static WeblogPermissionView of(WeblogPermission permission, Weblogger weblogger)
            throws WebloggerException {
        Weblog weblog = permission.getObjectId() != null
                ? weblogger.getWeblogManager().getWeblogByHandle(permission.getObjectId(), null)
                : null;
        User user = permission.getUserName() != null
                ? weblogger.getUserManager().getUserByUserName(permission.getUserName())
                : null;
        return new WeblogPermissionView(permission, weblog, user, weblogger);
    }

    /** {@link #of} over a list, order preserved. */
    public static List<WeblogPermissionView> resolve(List<WeblogPermission> permissions,
            Weblogger weblogger) throws WebloggerException {
        List<WeblogPermissionView> rows = new ArrayList<>(permissions.size());
        for (WeblogPermission permission : permissions) {
            rows.add(of(permission, weblogger));
        }
        return rows;
    }

    public WeblogPermission getPermission() {
        return permission;
    }

    public Weblog getWeblog() {
        return weblog;
    }

    public User getUser() {
        return user;
    }

    public boolean hasAction(String action) {
        return permission.hasAction(action);
    }

    public List<String> getActionsAsList() {
        return permission.getActionsAsList();
    }

    /**
     * Whether this member may moderate the weblog's comments: whether the row
     * implies the weblog {@code POST} permission, the check the Comments page's
     * interceptor makes. Never a lookup of the literal {@code "post"} action: a
     * weblog's creator holds {@code "admin"} alone, and the Members page grants
     * single-action rows.
     */
    public boolean isCanModerate() {
        WeblogPermission moderate = new WeblogPermission();
        moderate.setActionsAsList(List.of(WeblogPermission.POST));
        return permission.implies(moderate);
    }

    /**
     * The weblog's entry count, read once through the entry manager and
     * remembered; a count that cannot be read is 0 (logged), never a failed
     * page -- the main menu is the first thing a user sees after login.
     */
    public long getEntryCount() {
        if (entryCount == null) {
            long count = 0;
            if (weblog != null) {
                try {
                    count = weblogger.getWeblogEntryManager().getEntryCount(weblog);
                } catch (WebloggerException e) {
                    log.error("Error getting entry count for weblog {}", weblog.getHandle(), e);
                }
            }
            entryCount = count;
        }
        return entryCount;
    }

    /**
     * Comments awaiting approval on this weblog, read once and remembered; a
     * count that cannot be read is 0 (logged), never a failed main menu.
     */
    public long getPendingCommentCount() {
        if (pendingCommentCount == null) {
            long count = 0;
            if (weblog != null) {
                try {
                    count = weblogger.getCommentManager().countComments(weblog, ReaderComment.Status.PENDING);
                } catch (WebloggerException e) {
                    log.error("Error getting pending comment count for weblog {}", weblog.getHandle(), e);
                }
            }
            pendingCommentCount = count;
        }
        return pendingCommentCount;
    }
}
