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
package org.apache.roller.weblogger.ui.controllers.ajax;

import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The user-search endpoint behind the admin user lookup and the member-invite
 * autocomplete.
 *
 * <p>It hands back a list of accounts, and what is in that list depends on who
 * is asking: an administrator gets every user's <em>email address</em>, anyone
 * else gets screen names. Spring Security puts the endpoint behind
 * {@code /roller-ui/authoring/**}, so the caller is at least an editor -- but
 * an editor is not an administrator, and inverting this one branch would hand
 * every address in the installation to any of them. It had no test.
 *
 * <p>{@code UserAdminIT} drives this endpoint through the browser and proves
 * the search works for an admin. Nothing proved what a non-admin gets.
 */
class UserDataServletTest {

    private UserDataServlet servlet;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockWeblogger weblogger;

    private User caller;
    private User found;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = MockWeblogger.attached();
        servlet = new UserDataServlet(weblogger.weblogger());
        response = new MockHttpServletResponse();

        caller = new User();
        caller.setUserName("caller");
        caller.setEnabled(Boolean.TRUE);

        found = new User();
        found.setUserName("dana");
        found.setScreenName("Dana D");
        found.setEmailAddress("dana@example.invalid");
        found.setEnabled(Boolean.TRUE);

        when(weblogger.userManager().getUserByUserName("caller")).thenReturn(caller);
        when(weblogger.userManager().getUsersStartingWith(any(), any(), anyOffset(), anyLength()))
                .thenReturn(List.of(found));

        request = new MockHttpServletRequest("GET", "/roller-ui/authoring/userdata");
        request.setSession(new MockHttpSession());
        request.setUserPrincipal(() -> "caller");
    }

    private static int anyOffset() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private static int anyLength() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
    }

    /**
     * The disclosure boundary. An administrator is answering "who is this
     * account?", which needs the address; anyone else is picking a name off a
     * list and does not.
     */
    @Test
    void anAdministratorSeesEmailAddresses() throws Exception {
        callerIsAdmin(true);

        servlet.doGet(request, response);

        assertTrue(response.getContentAsString().contains("dana@example.invalid"),
                "an admin looking a user up needs their address: " + response.getContentAsString());
    }

    @Test
    void aNonAdministratorSeesScreenNamesAndNoEmailAddresses() throws Exception {
        callerIsAdmin(false);

        servlet.doGet(request, response);

        String body = response.getContentAsString();
        assertFalse(body.contains("dana@example.invalid"),
                "an editor must not be handed other users' email addresses: " + body);
        assertTrue(body.contains("Dana D"),
                "they get the screen name instead, which is what the picker displays: " + body);
    }

    /**
     * Both answers still name the account, because the caller has to be able to
     * act on it -- the invite form posts the username back.
     */
    @Test
    void everyAnswerCarriesTheUsername() throws Exception {
        callerIsAdmin(false);

        servlet.doGet(request, response);

        assertTrue(response.getContentAsString().startsWith("dana,"),
                "got: " + response.getContentAsString());
    }

    /**
     * A principal with no matching account is refused rather than served an
     * empty list, so a stale session cannot quietly enumerate users.
     */
    /**
     * Screen names are typed by their owners, and a reader's comes from the
     * Google account's name. The list must reach the browser as plain text: with
     * no content type at all, a name such as {@code <img src=x onerror=...>} was
     * one sniffing browser away from running in an administrator's session when
     * the endpoint is opened directly. UTF-8 is named too, so a name outside
     * Latin-1 is not written as question marks.
     */
    @Test
    void theListIsPlainUtf8TextNotMarkup() throws Exception {
        callerIsAdmin(false);
        found.setScreenName("<img src=x onerror=alert(1)> Zoë");

        servlet.doGet(request, response);

        assertEquals("text/plain;charset=UTF-8", String.valueOf(response.getContentType()).replace(" ", ""));
        assertTrue(response.getContentAsString().contains("Zoë"), response.getContentAsString());
    }

    @Test
    void aCallerWithNoAccountIsRefused() throws Exception {
        request.setUserPrincipal(() -> "ghost");
        when(weblogger.userManager().getUserByUserName("ghost")).thenReturn(null);

        servlet.doGet(request, response);

        assertEquals(404, response.getStatus());
        assertEquals("", response.getContentAsString());
    }

    @Test
    void aFailureResolvingTheCallerIsRefusedRatherThanThrown() throws Exception {
        request.setUserPrincipal(() -> "boom");
        when(weblogger.userManager().getUserByUserName("boom"))
                .thenThrow(new WebloggerException("nope"));

        servlet.doGet(request, response);

        assertEquals(404, response.getStatus());
    }

    @Test
    void aFailureListingUsersIsWrappedAsAServletExceptionCarryingItsCause() throws Exception {
        callerIsAdmin(false);
        WebloggerException cause = new WebloggerException("query failed");
        when(weblogger.userManager().getUsersStartingWith(any(), any(), anyOffset(), anyLength()))
                .thenThrow(cause);

        jakarta.servlet.ServletException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                jakarta.servlet.ServletException.class,
                () -> servlet.doGet(request, response));

        assertEquals(cause, thrown.getCause(),
                "the WebloggerException must survive as the cause, not just its message");
    }

    // ------------------------------------------------------- query parameters

    /**
     * The length parameter is a bound on how much of the user table one request
     * can drain. A caller that omits or mistypes it gets the default rather
     * than everything.
     */
    @Test
    void theResultLengthDefaultsWhenAbsentOrUnparseable() throws Exception {
        callerIsAdmin(false);
        request.setParameter("length", "not-a-number");

        servlet.doGet(request, response);

        verify(weblogger.userManager()).getUsersStartingWith(any(), any(), eq(0), eq(50));
    }

    @Test
    void offsetAndLengthArePassedThroughWhenGiven() throws Exception {
        callerIsAdmin(false);
        request.setParameter("offset", "20");
        request.setParameter("length", "5");
        request.setParameter("startsWith", "da");

        servlet.doGet(request, response);

        verify(weblogger.userManager()).getUsersStartingWith(eq("da"), any(), eq(20), eq(5));
    }

    /**
     * The enabled filter is tri-state: only the literal "true" and "false" mean
     * anything, and anything else means "do not filter". Collapsing the absent
     * case to FALSE would hide every active account from the picker.
     */
    @Test
    void theEnabledFilterIsTriState() throws Exception {
        callerIsAdmin(false);

        servlet.doGet(request, response);
        verify(weblogger.userManager()).getUsersStartingWith(any(), eq(null), anyOffset(), anyLength());

        request.setParameter("enabled", "true");
        servlet.doGet(request, new MockHttpServletResponse());
        verify(weblogger.userManager())
                .getUsersStartingWith(any(), eq(Boolean.TRUE), anyOffset(), anyLength());

        request.setParameter("enabled", "false");
        servlet.doGet(request, new MockHttpServletResponse());
        verify(weblogger.userManager())
                .getUsersStartingWith(any(), eq(Boolean.FALSE), anyOffset(), anyLength());
    }

    private void callerIsAdmin(boolean admin) throws Exception {
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(caller)))
                .thenReturn(admin);
    }
}
