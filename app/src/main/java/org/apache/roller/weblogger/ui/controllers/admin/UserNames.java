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

package org.apache.roller.weblogger.ui.controllers.admin;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import org.apache.commons.lang3.CharSetUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.ui.controllers.util.UIUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The username rule for a new person or agent (spec 2026-10-13, criterion 3),
 * in one place for both create screens: present, inside
 * {@code username.allowedChars} (letters and digits by default), and not taken.
 */
final class UserNames {

    private static final Logger log = LoggerFactory.getLogger(UserNames.class);

    private UserNames() {
    }

    /**
     * The message key refusing {@code userName} for a new account, or null
     * when it is acceptable. A taken-check that cannot run refuses it: a
     * check that could not run is not a check that passed.
     */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Lower-cases a username only after the check above has confined it to "
                    + "username.allowedChars (A-Za-z0-9 unless the operator widens it), to find an "
                    + "existing account that differs only in case.")
    static String problem(String userName, UserManager users) {
        if (StringUtils.isEmpty(userName)) {
            return "error.add.user.missingUserName";
        }
        String allowed = WebloggerConfig.getProperty("username.allowedChars");
        if (StringUtils.isBlank(allowed)) {
            allowed = UIUtils.DEFAULT_ALLOWED_CHARS;
        }
        if (!CharSetUtils.keep(userName, allowed).equals(userName)) {
            return "error.add.user.badUserName";
        }
        try {
            // The store also refuses a name whose lower-case form exists (JPAUserManagerImpl.addUser/addAgent),
            // so the field error is raised here rather than as a generic failure after the save.
            boolean taken = users.getUserByUserName(userName, null) != null
                    || users.getUserByUserName(userName.toLowerCase(Locale.ROOT), null) != null;
            return taken ? "error.add.user.userNameInUse" : null;
        } catch (WebloggerException e) {
            log.error("Could not check whether the username {} is taken", userName, e);
            return "generic.error.check.logs";
        }
    }
}
