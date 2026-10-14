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

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** What the Weblogs section offers (spec 2026-10-13, section 4). */
class UserWeblogsModelTest {

    private MockWeblogger tier;
    private final User bot = new User();

    @BeforeEach
    void setUp() {
        tier = MockWeblogger.attached();
        bot.setUserName("bot");
    }

    @AfterEach
    void tearDown() {
        tier.detach();
    }

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        return weblog;
    }

    @Test
    void theAddControlOffersOnlyWeblogsTheAccountIsNotInSortedByHandle() throws Exception {
        Weblog travel = weblog("travel");
        Weblog food = weblog("food");
        Weblog art = weblog("art");
        when(tier.userManager().getWeblogPermissions(bot))
                .thenReturn(List.of(new WeblogPermission(travel, bot, List.of(WeblogPermission.POST))));
        when(tier.weblogManager().getWeblogs(null, null, null, null, 0, -1)).thenReturn(List.of(travel, food, art));
        ExtendedModelMap model = new ExtendedModelMap();

        UserWeblogsModel.populate(tier.weblogger(), model, bot);

        assertEquals(List.of(art, food), model.getAttribute("availableWeblogs"));
        assertEquals("bot", model.getAttribute("weblogsAccount"));
        assertEquals(Boolean.TRUE, model.getAttribute("groupBloggingEnabled"));
    }

    /** Criterion 18: the section says granting is off instead of offering the control. */
    @Test
    void groupBloggingOffIsReported() throws Exception {
        when(tier.propertiesManager().getProperty("groupblogging.enabled"))
                .thenReturn(new RuntimeConfigProperty("groupblogging.enabled", "false"));
        ExtendedModelMap model = new ExtendedModelMap();

        UserWeblogsModel.populate(tier.weblogger(), model, bot);

        assertEquals(Boolean.FALSE, model.getAttribute("groupBloggingEnabled"));
    }

    /** A display path: a store that cannot list offers no weblog. */
    @Test
    void aStoreThatCannotListOffersNothing() throws Exception {
        when(tier.userManager().getWeblogPermissions(bot)).thenThrow(new WebloggerException("db down"));
        ExtendedModelMap model = new ExtendedModelMap();

        UserWeblogsModel.populate(tier.weblogger(), model, bot);

        assertEquals(List.of(), model.getAttribute("availableWeblogs"));
    }
}
