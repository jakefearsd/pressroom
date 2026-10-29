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

package com.wikantik.pressroom.ui.rendering.util;

import java.security.Principal;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ParsedRequest}, the root of the request-parsing
 * hierarchy. It carries the login identity and resolves it to a
 * {@link User} lazily, through the {@link Weblogger} it was constructed
 * with -- never through a static locator (plan Task 12).
 */
class ParsedRequestTest {

    private static final class TestRequest extends ParsedRequest {
        TestRequest(Weblogger weblogger, HttpServletRequest request)
                throws InvalidRequestException {
            super(weblogger, request);
        }

        TestRequest() {
            super();
        }
    }

    private static HttpServletRequest requestFor(String username) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        if (username != null) {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn(username);
            when(request.getUserPrincipal()).thenReturn(principal);
        }
        return request;
    }

    private static Weblogger webloggerKnowing(String username, User user)
            throws WebloggerException {
        Weblogger weblogger = mock(Weblogger.class);
        UserManager userManager = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(userManager);
        when(userManager.getUserByUserName(username)).thenReturn(user);
        return weblogger;
    }

    @Test
    void anonymousRequestHasNoAuthenticUserAndIsNotLoggedIn() throws Exception {
        TestRequest request = new TestRequest(mock(Weblogger.class), requestFor(null));

        assertNull(request.getAuthenticUser());
        assertFalse(request.isLoggedIn(),
                "An anonymous reader must not be reported as logged in, or the "
                        + "templates emit edit links to the public");
    }

    @Test
    void authenticatedRequestTakesItsUsernameFromThePrincipal() throws Exception {
        TestRequest request = new TestRequest(mock(Weblogger.class), requestFor("ada"));

        assertEquals("ada", request.getAuthenticUser());
        assertTrue(request.isLoggedIn());
    }

    @Test
    void anonymousRequestNeverReachesTheUserManager() throws Exception {
        // Constructing a parsed request happens on every single page view,
        // including every crawler hit. Looking a user up for an anonymous
        // request would be one wasted query per hit.
        Weblogger weblogger = mock(Weblogger.class);
        TestRequest request = new TestRequest(weblogger, requestFor(null));

        assertNull(request.getUser(), "There is no user to resolve");
        verify(weblogger, never()).getUserManager();
    }

    @Test
    void authenticatedRequestResolvesTheUserThroughTheInjectedFacadeOnceAndCachesIt()
            throws Exception {
        User user = new User();
        Weblogger weblogger = webloggerKnowing("ada", user);
        TestRequest request = new TestRequest(weblogger, requestFor("ada"));

        assertEquals(user, request.getUser());
        assertEquals(user, request.getUser());

        verify(weblogger.getUserManager()).getUserByUserName("ada");
    }

    @Test
    void aFailedLookupIsSwallowedSoTheRequestCanStillRender() throws Exception {
        // A user row that cannot be read is not a reason to fail the page --
        // the reader simply gets the anonymous view.
        Weblogger weblogger = mock(Weblogger.class);
        UserManager userManager = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(userManager);
        when(userManager.getUserByUserName("ada"))
                .thenThrow(new WebloggerException("database is down"));
        TestRequest request = new TestRequest(weblogger, requestFor("ada"));

        assertNull(request.getUser(),
                "A lookup failure must degrade to no user, not propagate out of "
                        + "the getter and abort the render");
    }

    @Test
    void injectedUserIsUsedWithoutAnyLookup() throws Exception {
        // The servlets pre-populate this from the session to avoid a query.
        Weblogger weblogger = mock(Weblogger.class);
        TestRequest request = new TestRequest(weblogger, requestFor("ada"));
        User user = new User();
        request.setUser(user);

        assertEquals(user, request.getUser());
        verify(weblogger, never()).getUserManager();
    }

    @Test
    void authenticUserCanBeOverriddenAfterParsing() throws Exception {
        TestRequest request = new TestRequest(mock(Weblogger.class), requestFor(null));
        request.setAuthenticUser("ada");

        assertTrue(request.isLoggedIn(),
                "isLoggedIn() must follow the current authentic user, not the "
                        + "principal that was present at construction time");
    }

    @Test
    void aCarrierBuiltWithoutAFacadeRefusesToLookAnythingUp() {
        // The no-arg constructors exist for hand-built carriers whose heavy
        // values arrive through the setters. A lazy lookup on one of those is
        // a programming error and must say so, not reach for a locator.
        TestRequest request = new TestRequest();
        request.setAuthenticUser("ada");

        IllegalStateException ex = assertThrows(IllegalStateException.class, request::getUser);
        assertTrue(ex.getMessage().contains("Weblogger"), ex.getMessage());
    }
}
