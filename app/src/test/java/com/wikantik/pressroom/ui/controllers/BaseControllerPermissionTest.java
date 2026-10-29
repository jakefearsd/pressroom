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
package com.wikantik.pressroom.ui.controllers;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The permission helpers every admin controller inherits. They used to be
 * {@code Weblog.hasUserPermission(User, String)} and
 * {@code User.hasGlobalPermission(String)} -- entity methods that located the
 * user manager statically (plan Task 16 moved them here). The contract is
 * unchanged: demand exactly the action named, scoped to the weblog named, and
 * read a check that cannot be answered as a denial.
 */
class BaseControllerPermissionTest {

    /** The smallest concrete controller; the helpers are what is under test. */
    private static final class Probe extends BaseController {
        Probe(Weblogger weblogger) {
            this.weblogger = weblogger;
        }
    }

    private Weblog weblog;
    private User user;
    private UserManager userManager;
    private Probe controller;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setHandle("testblog");
        user = new User();
        user.setUserName("alice");

        userManager = mock(UserManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getUserManager()).thenReturn(userManager);
        controller = new Probe(weblogger);
    }

    @Test
    void weblogPermissionCheckAsksForTheActionsRequested() throws Exception {
        ArgumentCaptor<WeblogPermission> captor = ArgumentCaptor.forClass(WeblogPermission.class);
        when(userManager.checkPermission(any(), eq(user))).thenReturn(true);

        assertTrue(controller.hasWeblogAction(weblog, user, WeblogPermission.POST));

        verify(userManager).checkPermission(captor.capture(), eq(user));
        WeblogPermission demanded = captor.getValue();
        assertEquals(List.of(WeblogPermission.POST), demanded.getActionsAsList(),
                "The check must demand exactly the action the caller named");
        assertEquals("testblog", demanded.getObjectId(),
                "and must be scoped to this weblog, not to weblogs in general");
        assertEquals("alice", demanded.getUserName());
    }

    @Test
    void weblogPermissionCheckReportsADenialAsADenial() throws Exception {
        when(userManager.checkPermission(any(), eq(user))).thenReturn(false);

        assertFalse(controller.hasWeblogAction(weblog, user, WeblogPermission.ADMIN));
    }

    @Test
    void weblogPermissionCheckDeniesWhenItCannotBeAnswered() throws Exception {
        when(userManager.checkPermission(any(), eq(user)))
                .thenThrow(new WebloggerException("database down"));

        assertFalse(controller.hasWeblogAction(weblog, user, WeblogPermission.POST),
                "An unanswerable permission check must deny rather than allow");
    }

    @Test
    void globalAdminCheckAsksForTheAdminAction() throws Exception {
        ArgumentCaptor<GlobalPermission> captor = ArgumentCaptor.forClass(GlobalPermission.class);
        when(userManager.checkPermission(any(), eq(user))).thenReturn(true);

        assertTrue(controller.isGlobalAdmin(user));

        verify(userManager).checkPermission(captor.capture(), eq(user));
        assertEquals(List.of(GlobalPermission.ADMIN), captor.getValue().getActionsAsList());
    }

    @Test
    void globalAdminCheckDeniesOnDenialAndOnFailure() throws Exception {
        when(userManager.checkPermission(any(), eq(user))).thenReturn(false);
        assertFalse(controller.isGlobalAdmin(user));

        when(userManager.checkPermission(any(), eq(user)))
                .thenThrow(new WebloggerException("database down"));
        assertFalse(controller.isGlobalAdmin(user), "An unanswerable check must deny");
    }
}
