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

package com.wikantik.pressroom.ui.rendering.servlets;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WeblogEditors#userMayEdit}, the check a page request's
 * {@code ?skipCache} must pass: both permissions, and every failure closed.
 */
class WeblogEditorsTest {

    private final Weblog weblog = new Weblog();
    private final User user = new User();
    private final UserManager users = mock(UserManager.class);

    WeblogEditorsTest() {
        weblog.setHandle("editorsblog");
    }

    @Test
    void anEditorWhoMaySignInMayEdit() throws Exception {
        when(users.checkPermission(any(GlobalPermission.class), any(User.class))).thenReturn(true);
        when(users.checkPermission(any(WeblogPermission.class), any(User.class))).thenReturn(true);

        assertTrue(WeblogEditors.userMayEdit(weblog, user, users));
    }

    @Test
    void eitherPermissionMissingIsANo() throws Exception {
        when(users.checkPermission(any(GlobalPermission.class), any(User.class))).thenReturn(true);
        when(users.checkPermission(any(WeblogPermission.class), any(User.class))).thenReturn(false);
        assertFalse(WeblogEditors.userMayEdit(weblog, user, users), "no permission on this weblog");

        when(users.checkPermission(any(GlobalPermission.class), any(User.class))).thenReturn(false);
        when(users.checkPermission(any(WeblogPermission.class), any(User.class))).thenReturn(true);
        assertFalse(WeblogEditors.userMayEdit(weblog, user, users), "a reader, demoted from author");
    }

    @Test
    void noUserOrAFailedLookupIsANo() throws Exception {
        assertFalse(WeblogEditors.userMayEdit(weblog, null, users));

        when(users.checkPermission(any(GlobalPermission.class), any(User.class)))
                .thenThrow(new WebloggerException("store unreachable"));
        assertFalse(WeblogEditors.userMayEdit(weblog, user, users), "a check that could not run is not a yes");
    }
}
