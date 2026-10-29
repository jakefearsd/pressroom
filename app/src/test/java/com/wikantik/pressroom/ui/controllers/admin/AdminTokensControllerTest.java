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
package com.wikantik.pressroom.ui.controllers.admin;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.testing.AdminLogCapture;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Admin → API tokens (spec 2026-10-13, section 3; criteria 13 and 14). */
class AdminTokensControllerTest {

    private MockWeblogger weblogger;
    private AdminTokensController controller;
    private ExtendedModelMap model;
    private RedirectAttributesModelMap redirect;
    private User admin;
    private User jake;
    private User bot;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new AdminTokensController());
        model = new ExtendedModelMap();
        redirect = new RedirectAttributesModelMap();
        admin = account("admin", User.Kind.PERSON);
        jake = account("jake", User.Kind.PERSON);
        bot = account("bot", User.Kind.AGENT);
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    private static User account(String userName, User.Kind kind) {
        User user = new User();
        user.setUserName(userName);
        user.setKind(kind);
        return user;
    }

    private static ApiToken token(String id, User owner, String issuedBy) {
        ApiToken token = new ApiToken();
        token.setId(id);
        token.setUser(owner);
        token.setLabel("label-" + id);
        token.setScopeRole(ApiToken.Role.POST);
        token.setIssuedBy(issuedBy);
        return token;
    }

    private HttpServletRequest asAdmin() {
        return ControllerTestFixture.requestFor(admin);
    }

    @SuppressWarnings("unchecked")
    private List<TokenRow> rows() {
        return (List<TokenRow>) model.getAttribute("tokenRows");
    }

    @Test
    void thePageIsAdminOnlyAndNeedsNoWeblog() {
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
        assertEquals("apiTokens", controller.getActionName());
    }

    /** Criterion 13: every live token of people and agents, with owner, kind and issuer. */
    @Test
    void listsEveryLiveTokenWithOwnerKindAndIssuer() throws Exception {
        ApiToken personToken = token("t1", jake, jake.getId());
        ApiToken agentToken = token("t2", bot, admin.getId());
        ApiToken legacy = token("t3", jake, null);
        when(weblogger.apiTokenManager().getAllTokens(false)).thenReturn(List.of(personToken, agentToken, legacy));
        when(weblogger.userManager().getUser(jake.getId())).thenReturn(jake);
        when(weblogger.userManager().getUser(admin.getId())).thenReturn(admin);

        String view = controller.list(asAdmin(), model, null);

        assertEquals(".ApiTokens", view);
        assertEquals(Boolean.FALSE, model.getAttribute("showAll"));
        List<TokenRow> rows = rows();
        assertEquals(3, rows.size());
        assertSame(personToken, rows.get(0).getToken());
        assertEquals("jake", rows.get(0).getIssuedBy());
        assertTrue(rows.get(1).getToken().getUser().isAgent());
        assertEquals("admin", rows.get(1).getIssuedBy());
        assertNull(rows.get(2).getIssuedBy(), "a token from before V033 has no issuer");
    }

    /** Criterion 13: the toggle adds revoked and expired tokens. */
    @Test
    void theToggleAddsRevokedAndExpiredTokens() throws Exception {
        controller.list(asAdmin(), model, "true");

        verify(weblogger.apiTokenManager()).getAllTokens(true);
        assertEquals(Boolean.TRUE, model.getAttribute("showAll"));
    }

    @Test
    void aStoreThatCannotListShowsNoRows() throws Exception {
        when(weblogger.apiTokenManager().getAllTokens(false)).thenThrow(new WebloggerException("db down"));

        assertEquals(".ApiTokens", controller.list(asAdmin(), model, null));
        assertTrue(rows().isEmpty());
    }

    /** Criterion 14: a site admin can revoke any token, and the revocation is logged. */
    @Test
    void aSiteAdminRevokesAnyToken() throws Exception {
        ApiToken jakes = token("t1", jake, jake.getId());
        when(weblogger.apiTokenManager().getToken("t1")).thenReturn(jakes);

        try (AdminLogCapture log = AdminLogCapture.start()) {
            String view = controller.revoke(asAdmin(), redirect, "t1", null);

            assertEquals("redirect:/roller-ui/admin/apiTokens.rol", view);
            verify(weblogger.apiTokenManager()).revoke(jakes);
            assertEquals(List.of("apiTokens.revoked"), ControllerTestFixture.flashMessages(redirect));
            assertEquals(1, log.lines().size());
            assertTrue(log.lines().get(0).startsWith("token.revoked id=t1 label=label-t1 owner=jake"),
                    log.lines().get(0));
            assertTrue(log.lines().get(0).endsWith("by=admin"), log.lines().get(0));
        }
    }

    @Test
    void revokingKeepsTheToggle() throws Exception {
        when(weblogger.apiTokenManager().getToken("t1")).thenReturn(token("t1", jake, null));

        controller.revoke(asAdmin(), redirect, "t1", "true");

        assertEquals("true", redirect.asMap().get("all"));
    }

    @Test
    void anUnknownIdIsNoSuchToken() throws Exception {
        controller.revoke(asAdmin(), redirect, "nosuch", null);

        assertEquals(List.of("apiTokens.error.noSuchToken"), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.apiTokenManager(), never()).revoke(any(ApiToken.class));
    }

    /** A store failure while revoking is not "no such token": the token may still be live. */
    @Test
    void aStoreFailureOnRevokeIsReportedAsSuch() throws Exception {
        when(weblogger.apiTokenManager().getToken("t1")).thenThrow(new WebloggerException("db down"));

        controller.revoke(asAdmin(), redirect, "t1", null);

        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.flashErrors(redirect));
    }
}
