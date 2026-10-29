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

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ui.Model;

/**
 * Builds Admin → Users (spec 2026-10-13, section 4) for every controller that
 * lands on it -- the list itself, and the user editor and agent page after a
 * save or a missing account -- so the page always has its rows.
 */
final class AccountRows {

    private static final Logger log = LoggerFactory.getLogger(AccountRows.class);

    private AccountRows() {
    }

    /**
     * Puts the list on the model and names its view. A display path: a store
     * that cannot list shows an empty list, logged.
     *
     * @param kind {@code people}, {@code agents}, or anything else for every account
     * @param text the filter as typed; blank for none
     */
    static String showList(Weblogger weblogger, Model model, String kind, String text) {
        String tab = "people".equals(kind) || "agents".equals(kind) ? kind : "";
        String filter = StringUtils.trimToEmpty(text);
        model.addAttribute("kindFilter", tab);
        model.addAttribute("textFilter", filter);
        model.addAttribute("accounts", rowsOrNone(weblogger, kindOf(tab), StringUtils.trimToNull(filter)));
        return ".UserAdmin";
    }

    static List<AccountRow> load(Weblogger weblogger, User.Kind kind, String text) throws WebloggerException {
        UserManager users = weblogger.getUserManager();
        List<AccountRow> rows = new ArrayList<>();
        for (User user : users.getAccounts(kind, text)) {
            rows.add(new AccountRow(user, roleOf(users.getRoles(user)),
                    users.getWeblogPermissions(user).size(), lastActive(weblogger, user)));
        }
        return rows;
    }

    private static List<AccountRow> rowsOrNone(Weblogger weblogger, User.Kind kind, String text) {
        try {
            return load(weblogger, kind, text);
        } catch (WebloggerException e) {
            log.error("Could not list the accounts", e);
            return List.of();
        }
    }

    private static User.Kind kindOf(String tab) {
        if ("people".equals(tab)) {
            return User.Kind.PERSON;
        }
        if ("agents".equals(tab)) {
            return User.Kind.AGENT;
        }
        return null;
    }

    private static AccountRow.Role roleOf(List<String> roles) {
        if (roles.contains("admin")) {
            return AccountRow.Role.SITE_ADMIN;
        }
        if (roles.contains("editor")) {
            return AccountRow.Role.AUTHOR;
        }
        return AccountRow.Role.READER;
    }

    /** A person's last sign-in; for an agent, which never signs in, its newest token use. */
    private static Date lastActive(Weblogger weblogger, User user) throws WebloggerException {
        if (!user.isAgent()) {
            return user.getLastLogin();
        }
        Date newest = null;
        for (ApiToken token : weblogger.getApiTokenManager().getTokens(user)) {
            Timestamp used = token.getLastUsedAt();
            if (used != null && (newest == null || used.after(newest))) {
                newest = used;
            }
        }
        return newest;
    }
}
