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
package com.wikantik.pressroom.ui.view;

import java.util.List;

import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link WeblogPermissionView} is what the admin JSPs iterate instead of raw
 * {@link WeblogPermission}s: the permission used to resolve its weblog and
 * user by name through the static service locator from inside getters
 * ({@code ${perms.weblog.name}}, {@code ${perm.user.id}}); the controller now
 * resolves them once, through the facade it holds, and hands the JSP a row.
 */
class WeblogPermissionViewTest {

    private Weblogger weblogger;
    private WeblogManager weblogs;
    private UserManager users;
    private Weblog weblog;
    private User alice;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = mock(Weblogger.class);
        weblogs = mock(WeblogManager.class);
        users = mock(UserManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogs);
        when(weblogger.getUserManager()).thenReturn(users);

        weblog = new Weblog();
        weblog.setHandle("testblog");
        alice = new User();
        alice.setUserName("alice");
        when(weblogs.getWeblogByHandle("testblog", null)).thenReturn(weblog);
        when(users.getUserByUserName("alice")).thenReturn(alice);
    }

    @Test
    void resolvesTheWeblogByHandleAndTheUserByNameThroughTheFacade() throws Exception {
        WeblogPermission perm = new WeblogPermission(weblog, alice, List.of(WeblogPermission.POST));

        WeblogPermissionView row = WeblogPermissionView.of(perm, weblogger);

        assertSame(perm, row.getPermission());
        assertSame(weblog, row.getWeblog());
        assertSame(alice, row.getUser());
        assertTrue(row.hasAction(WeblogPermission.POST));
        assertFalse(row.hasAction(WeblogPermission.ADMIN));
    }

    @Test
    void anUnassignedPermissionResolvesToNothingWithoutAskingTheTier() throws Exception {
        // A permission with no weblog handle and no user name -- the shape the
        // old getters answered with null for -- must not even ask the managers.
        WeblogPermission perm = new WeblogPermission();

        WeblogPermissionView row = WeblogPermissionView.of(perm, weblogger);

        assertNull(row.getWeblog());
        assertNull(row.getUser());
        verifyNoInteractions(weblogs, users);
    }

    @Test
    void resolveMapsEveryPermissionInOrder() throws Exception {
        WeblogPermission first = new WeblogPermission(weblog, alice, List.of("post"));
        WeblogPermission second = new WeblogPermission();

        List<WeblogPermissionView> rows = WeblogPermissionView.resolve(List.of(first, second), weblogger);

        assertEquals(2, rows.size());
        assertSame(first, rows.get(0).getPermission());
        assertSame(second, rows.get(1).getPermission());
    }
}
