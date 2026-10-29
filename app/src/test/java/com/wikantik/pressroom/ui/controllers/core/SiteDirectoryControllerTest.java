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
package com.wikantik.pressroom.ui.controllers.core;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SiteDirectoryController}: what {@code /} shows while no
 * weblog is the site's front page. It is the public home of a site, so it
 * must reach a reader who is not signed in.
 */
class SiteDirectoryControllerTest {

    private MockWeblogger weblogger;
    private SiteDirectoryController controller;
    private ExtendedModelMap model;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new SiteDirectoryController());
        model = new ExtendedModelMap();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    @Test
    void theDirectoryIsReachableWithoutBeingSignedIn() {
        assertFalse(controller.isUserRequired());
        assertFalse(controller.isWeblogRequired());
        assertEquals("siteDirectory.title", controller.getPageTitle());
    }

    @Test
    void itListsTheWeblogsMeantForListingsByName() throws Exception {
        // enabled = visible, active = "included in community listings"; the
        // manager returns newest first, a directory reads better by name.
        when(weblogger.weblogManager().getWeblogs(Boolean.TRUE, Boolean.TRUE, null, null, 0, -1))
                .thenReturn(List.of(weblog("zebra", "Zebra Notes"), weblog("alpha", "alpha trips"),
                        weblog("mid", "Middle Ground")));

        String view = controller.show(ControllerTestFixture.requestFor(null), model);

        assertEquals(".SiteDirectory", view);
        assertEquals(List.of("alpha", "mid", "zebra"), handles(model.getAttribute("weblogs")));
    }

    @Test
    void weblogsThatCannotBeReadLeaveAnEmptyDirectoryRatherThanAnError() throws Exception {
        // A display path: the site's home page must still render.
        when(weblogger.weblogManager().getWeblogs(Boolean.TRUE, Boolean.TRUE, null, null, 0, -1))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.show(ControllerTestFixture.requestFor(null), model);

        assertEquals(".SiteDirectory", view);
        assertEquals(List.of(), model.getAttribute("weblogs"));
    }

    @Test
    void onlyASignedInAdminIsOfferedTheFrontPageChoice() throws Exception {
        User admin = new User();
        admin.setUserName("admin");
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(admin))).thenReturn(true);

        controller.show(ControllerTestFixture.requestFor(admin), model);

        assertEquals(true, model.getAttribute("userIsAdmin"));
    }

    @Test
    void aReaderWhoIsNotSignedInIsNotAnAdmin() {
        controller.show(ControllerTestFixture.requestFor(null), model);

        assertEquals(false, model.getAttribute("userIsAdmin"));
    }

    private static Weblog weblog(String handle, String name) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setName(name);
        return weblog;
    }

    @SuppressWarnings("unchecked")
    private static List<String> handles(Object weblogs) {
        return ((List<Weblog>) weblogs).stream().map(Weblog::getHandle).toList();
    }
}
