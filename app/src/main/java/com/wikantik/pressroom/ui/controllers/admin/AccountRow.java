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

package com.wikantik.pressroom.ui.controllers.admin;

import java.util.Date;

import com.wikantik.pressroom.pojos.User;

/** One row of Admin → Users (spec 2026-10-13, section 4): an account and what the list says about it. */
public final class AccountRow {

    /** The account's role as the list names it: no login action, {@code editor}, or {@code admin}. */
    public enum Role { READER, AUTHOR, SITE_ADMIN }

    private final User user;
    private final Role role;
    private final int weblogCount;
    private final Date lastActive;

    public AccountRow(User user, Role role, int weblogCount, Date lastActive) {
        this.user = user;
        this.role = role;
        this.weblogCount = weblogCount;
        this.lastActive = lastActive == null ? null : (Date) lastActive.clone();
    }

    public User getUser() {
        return user;
    }

    public String getUserName() {
        return user.getUserName();
    }

    /** The screen name, which the list shows over the username. */
    public String getName() {
        return user.getScreenName();
    }

    public boolean isAgent() {
        return user.isAgent();
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(user.getEnabled());
    }

    public Role getRole() {
        return role;
    }

    public int getWeblogCount() {
        return weblogCount;
    }

    /** A person's last sign-in, or an agent's newest token use; null when never. */
    public Date getLastActive() {
        return lastActive == null ? null : (Date) lastActive.clone();
    }
}
