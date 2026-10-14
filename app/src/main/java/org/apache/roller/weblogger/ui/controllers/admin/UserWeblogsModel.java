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
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ui.Model;

/**
 * What {@code UserWeblogs.jsp} needs beyond the {@code permissions} rows, for
 * a person's page and an agent's page alike (spec 2026-10-13, section 4).
 */
final class UserWeblogsModel {

    private static final Logger log = LoggerFactory.getLogger(UserWeblogsModel.class);

    private UserWeblogsModel() {
    }

    /**
     * The account, whether granting is possible, and the weblogs the account
     * is not in. A display path: a store that cannot list offers no weblog.
     */
    static void populate(Weblogger weblogger, Model model, User account) {
        model.addAttribute("weblogsAccount", account.getUserName());
        model.addAttribute("groupBloggingEnabled", WebloggerRuntimeConfig.getBooleanProperty("groupblogging.enabled"));
        model.addAttribute("availableWeblogs", available(weblogger, account));
    }

    private static List<Weblog> available(Weblogger weblogger, User account) {
        try {
            Set<String> memberOf = new HashSet<>();
            for (WeblogPermission permission : weblogger.getUserManager().getWeblogPermissions(account)) {
                memberOf.add(permission.getObjectId());
            }
            List<Weblog> weblogs = new ArrayList<>();
            for (Weblog weblog : weblogger.getWeblogManager().getWeblogs(null, null, null, null, 0, -1)) {
                if (!memberOf.contains(weblog.getHandle())) {
                    weblogs.add(weblog);
                }
            }
            weblogs.sort(Comparator.comparing(Weblog::getHandle));
            return weblogs;
        } catch (WebloggerException e) {
            log.error("Could not list the weblogs {} could join", account.getUserName(), e);
            return List.of();
        }
    }
}
