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

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Review focus 5; criteria 11, 12 and 15: who may pin a token to which weblog. */
class TokenPinsTest {

    private final MockWeblogger tier = MockWeblogger.create();
    private final User owner = new User();
    private final Weblog travel = weblog("travel");
    private final Weblog food = weblog("food");

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setName(handle);
        return weblog;
    }

    @BeforeEach
    void setUp() throws Exception {
        owner.setUserName("owner");
        when(tier.weblogManager().getWeblogByHandle("travel", null)).thenReturn(travel);
        when(tier.weblogManager().getWeblogByHandle("food", null)).thenReturn(food);
    }

    private void siteAdmin(boolean admin) throws WebloggerException {
        when(tier.userManager().checkPermission(
                argThat(p -> p instanceof GlobalPermission g && g.hasAction(GlobalPermission.ADMIN)), eq(owner)))
                .thenReturn(admin);
    }

    @Test
    void noPinIsAlwaysAllowed() throws Exception {
        siteAdmin(false);

        assertTrue(TokenPins.allows(tier.weblogger(), owner, null));
        assertTrue(TokenPins.allows(tier.weblogger(), owner, "  "));
    }

    @Test
    void aMemberMayPinOnlyTheirOwnWeblogs() throws Exception {
        siteAdmin(false);
        when(tier.userManager().getWeblogPermission(travel, owner))
                .thenReturn(new WeblogPermission(travel, owner, List.of(WeblogPermission.POST)));

        assertTrue(TokenPins.allows(tier.weblogger(), owner, "travel"));
        assertFalse(TokenPins.allows(tier.weblogger(), owner, "food"), "a weblog they are not in");
        assertFalse(TokenPins.allows(tier.weblogger(), owner, "nosuch"), "a weblog that does not exist");
    }

    @Test
    void aSiteAdminMayPinAnyExistingWeblogButNoneThatDoesNotExist() throws Exception {
        siteAdmin(true);

        assertTrue(TokenPins.allows(tier.weblogger(), owner, "food"));
        assertFalse(TokenPins.allows(tier.weblogger(), owner, "nosuch"));
    }

    /** A handle the weblog store would reject is "no such weblog", not an error that leaks out as a 500. */
    @Test
    void aMalformedHandleIsRefusedNotThrown() throws Exception {
        siteAdmin(true);
        // The real store throws for a handle it cannot hold; the mock must too, or this proves nothing.
        when(tier.weblogManager().getWeblogByHandle("not-a-handle", null))
                .thenThrow(new WebloggerException("Invalid handle: 'not-a-handle'"));
        when(tier.weblogManager().getWeblogByHandle("has space", null))
                .thenThrow(new WebloggerException("Invalid handle: 'has space'"));

        assertFalse(TokenPins.allows(tier.weblogger(), owner, "not-a-handle"));
        assertFalse(TokenPins.allows(tier.weblogger(), owner, "has space"));
    }

    @Test
    void theChoicesAreTheMembershipsOrEveryWeblogForASiteAdmin() throws Exception {
        siteAdmin(false);
        when(tier.userManager().getWeblogPermissions(owner))
                .thenReturn(List.of(new WeblogPermission(travel, owner, List.of(WeblogPermission.POST))));
        assertEquals(List.of(travel), TokenPins.choices(tier.weblogger(), owner));

        siteAdmin(true);
        when(tier.weblogManager().getWeblogs(null, null, null, null, 0, -1)).thenReturn(List.of(travel, food));
        assertEquals(List.of(food, travel), TokenPins.choices(tier.weblogger(), owner), "sorted by handle");
    }
}
