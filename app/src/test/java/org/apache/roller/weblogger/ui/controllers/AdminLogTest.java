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
package org.apache.roller.weblogger.ui.controllers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.apache.roller.testing.AdminLogCapture;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spec 2026-10-13, section 6: one INFO line on roller.admin per event, naming the target and by=. */
class AdminLogTest {

    @Test
    void anEventIsOneLineNamingWhoDidIt() {
        User jake = new User();
        jake.setUserName("jake");
        try (AdminLogCapture capture = AdminLogCapture.start()) {
            AdminLog.record("account.disabled", jake, "account=bot");
            AdminLog.record("token.revoked", null, "id=t1");

            assertEquals(List.of("account.disabled account=bot by=jake", "token.revoked id=t1 by=-"),
                    capture.lines());
        }
        assertEquals("roller.admin", AdminLog.NAME);
    }

    @Test
    void aTokenIsNamedByEverythingButItsSecret() {
        User bot = new User();
        bot.setUserName("bot");
        ApiToken token = new ApiToken();
        token.setId("t1");
        token.setLabel("publisher");
        token.setUser(bot);
        token.setScopeRole(ApiToken.Role.POST);
        token.setTokenSha256("digest-is-not-logged");

        assertEquals("id=t1 label=publisher owner=bot role=POST weblog=* expires=never",
                AdminLog.tokenDetails(token));

        token.setScopeWeblog("travel");
        token.setExpiresAt(Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")));
        assertEquals("id=t1 label=publisher owner=bot role=POST weblog=travel expires=2027-01-01T00:00:00Z",
                AdminLog.tokenDetails(token));
    }
}
