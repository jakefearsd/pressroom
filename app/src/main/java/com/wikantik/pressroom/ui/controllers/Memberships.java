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

package com.wikantik.pressroom.ui.controllers;

import java.util.List;

import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;

/**
 * The rules both membership screens share: a weblog's Members page
 * ({@code MembersController}) and the Weblogs section of a person's or an
 * agent's page ({@code UserWeblogsController}). One level per member, from
 * {@link #LEVELS} only, and every change recorded with
 * {@link AdminLog#record} in the same words.
 */
public final class Memberships {

    /** The levels a member may hold, most to least: Admin, Author, Limited. */
    public static final List<String> LEVELS =
            List.of(WeblogPermission.ADMIN, WeblogPermission.POST, WeblogPermission.EDIT_DRAFT);

    private Memberships() {
    }

    /** The admin-log details naming a membership: {@code account=<name> weblog=<handle>}. */
    public static String details(User account, Weblog weblog) {
        return "account=" + account.getUserName() + " weblog=" + weblog.getHandle();
    }
}
