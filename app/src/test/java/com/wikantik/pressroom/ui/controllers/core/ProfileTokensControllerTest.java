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

import java.sql.Timestamp;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.testing.AdminLogCapture;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.TokenFormController;
import com.wikantik.pressroom.ui.controllers.TokenFormValues;
import com.wikantik.pressroom.ui.core.security.sso.SsoLogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Profile → API tokens (spec 2026-10-13, section 3; criteria 9, 10, 14, 15):
 * the token form, the one-time reveal and revocation, as a person sees them.
 */
class ProfileTokensControllerTest {

    private MockWeblogger mocks;
    private ProfileTokensController controller;
    private ExtendedModelMap model;
    private RedirectAttributesModelMap redirect;
    private User me;
    private Weblog travel;
    private Weblog food;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(mocks.weblogger());
        controller = ControllerTestFixture.withMessages(new ProfileTokensController());
        model = new ExtendedModelMap();
        redirect = new RedirectAttributesModelMap();
        me = new User();
        me.setUserName("jake");
        travel = weblog("travel");
        food = weblog("food");
        when(mocks.weblogManager().getWeblogByHandle("travel", null)).thenReturn(travel);
        when(mocks.weblogManager().getWeblogByHandle("food", null)).thenReturn(food);
        WeblogPermission mine = new WeblogPermission(travel, me, List.of(WeblogPermission.POST));
        when(mocks.userManager().getWeblogPermission(travel, me)).thenReturn(mine);
        when(mocks.userManager().getWeblogPermissions(me)).thenReturn(List.of(mine));
        when(mocks.propertiesManager().getProperty("site.absoluteurl"))
                .thenReturn(new RuntimeConfigProperty("site.absoluteurl", "https://blog.example/"));
    }

    @AfterEach
    void tearDown() {
        mocks.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setName(handle);
        return weblog;
    }

    private HttpServletRequest request() {
        return ControllerTestFixture.requestFor(me);
    }

    private void siteAdmin(boolean admin) throws WebloggerException {
        when(mocks.userManager().checkPermission(
                argThat(p -> p instanceof GlobalPermission g && g.hasAction(GlobalPermission.ADMIN)), eq(me)))
                .thenReturn(admin);
    }

    private ApiTokenManager.Issued issued(String raw, String label) {
        ApiToken token = new ApiToken();
        token.setId("t1");
        token.setUser(me);
        token.setLabel(label);
        token.setScopeRole(ApiToken.Role.POST);
        return new ApiTokenManager.Issued(raw, token);
    }

    private void mintsAs(String raw, String label) throws WebloggerException {
        when(mocks.apiTokenManager().issueToken(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(issued(raw, label));
    }

    @Test
    void thePageNeedsASignedInAuthorAndNoWeblog() {
        assertFalse(controller.isWeblogRequired());
        assertTrue(controller.isUserRequired());
        assertEquals(List.of(GlobalPermission.LOGIN), controller.requiredGlobalPermissionActions());
    }

    /** Criterion 15: my live tokens, and pin choices limited to my own weblogs. */
    @Test
    void thePageListsMyLiveTokensAndOffersOnlyMyWeblogs() throws Exception {
        ApiToken mine = issued("prs_x", "ci").token();
        when(mocks.apiTokenManager().getLiveTokens(me)).thenReturn(List.of(mine));

        String view = controller.list(request(), model);

        assertEquals(".ProfileTokens", view);
        assertEquals(List.of(mine), model.getAttribute("tokens"));
        assertEquals(List.of(travel), model.getAttribute("pinChoices"));
        assertEquals("/pressroom-ui/profileTokens!create.rol", model.getAttribute("tokenFormAction"));
        assertEquals("/pressroom-ui/profileTokens!revoke.rol", model.getAttribute("tokenRevokeAction"));
        assertNull(model.getAttribute("tokenFormOwner"));
        TokenFormValues values = (TokenFormValues) model.getAttribute("tokenFormValues");
        assertEquals("POST", values.getRole());
        assertEquals("90", values.getExpiry());
    }

    /** Criteria 9 and 15: the mint stores the choices and the issuer, then goes to the reveal. */
    @Test
    void mintingStoresTheChoicesRecordsTheIssuerAndRedirectsToTheReveal() throws Exception {
        mintsAs("prs_secret", "ci");
        long before = System.currentTimeMillis();

        String view = controller.create(request(), model, redirect, "  ci ", "POST", "travel", null);

        assertEquals("redirect:/pressroom-ui/profileTokens!reveal.rol", view);
        ArgumentCaptor<Timestamp> expires = ArgumentCaptor.forClass(Timestamp.class);
        verify(mocks.apiTokenManager()).issueToken(eq(me), eq("ci"), eq("travel"), eq(ApiToken.Role.POST),
                expires.capture(), eq(me));
        long ninetyDays = 90L * 24 * 60 * 60 * 1000;
        long expiry = expires.getValue().getTime();
        assertTrue(expiry >= before + ninetyDays - 60_000L
                && expiry <= System.currentTimeMillis() + ninetyDays + 60_000L, "the default expiry is 90 days");
        assertEquals(1, mocks.flushCount());
        assertEquals("prs_secret", redirect.getFlashAttributes().get(TokenFormController.REVEALED_TOKEN));
        assertEquals("ci", redirect.getFlashAttributes().get(TokenFormController.REVEALED_LABEL));
        assertTrue(redirect.asMap().isEmpty(), "nothing rides in the redirect's query: the secret never reaches a URL");
    }

    /** Criteria 11 and 15; review focus 5: a pin outside my weblogs is a field error and nothing is minted. */
    @Test
    void aPinOutsideMyWeblogsIsAFieldError() throws Exception {
        siteAdmin(false);

        String view = controller.create(request(), model, redirect, "ci", "POST", "food", "30");

        assertEquals(".ProfileTokens", view);
        assertEquals(List.of("apiTokens.error.pin"), ControllerTestFixture.errors(model));
        assertEquals(List.of("tokenPin"), ControllerTestFixture.invalidFields(model));
        verify(mocks.apiTokenManager(), never()).issueToken(any(), anyString(), any(), any(), any(), any());
        assertEquals("food", ((TokenFormValues) model.getAttribute("tokenFormValues")).getPin(),
                "the form comes back as it was sent");
    }

    /** Criterion 15; review focus 5: a site admin may pin any existing weblog. */
    @Test
    void aSiteAdminMayPinAnyExistingWeblog() throws Exception {
        siteAdmin(true);
        mintsAs("prs_ops", "ops");

        assertEquals("redirect:/pressroom-ui/profileTokens!reveal.rol",
                controller.create(request(), model, redirect, "ops", "ADMIN", "food", "365"));
    }

    /** Review focus 5: a pin check that cannot run refuses the pin. */
    @Test
    void aPinCheckThatCannotRunRefuses() throws Exception {
        when(mocks.weblogManager().getWeblogByHandle("travel", null)).thenThrow(new WebloggerException("db down"));

        controller.create(request(), model, redirect, "ci", "POST", "travel", "90");

        assertEquals(List.of("apiTokens.error.pin"), ControllerTestFixture.errors(model));
        verify(mocks.apiTokenManager(), never()).issueToken(any(), anyString(), any(), any(), any(), any());
    }

    @Test
    void aBlankLabelAnUnknownRoleAndAnUnknownExpiryAreFieldErrors() throws Exception {
        controller.create(request(), model, redirect, "   ", "ROOT", null, "7");

        assertEquals(List.of("apiTokens.error.labelRequired", "apiTokens.error.role", "apiTokens.error.expiry"),
                ControllerTestFixture.errors(model));
        assertEquals(List.of("tokenLabel", "tokenRole", "tokenExpiry"), ControllerTestFixture.invalidFields(model));
    }

    @Test
    void aLabelLongerThanTheColumnIsAFieldError() throws Exception {
        controller.create(request(), model, redirect, "x".repeat(256), "POST", null, "90");

        assertEquals(List.of("apiTokens.error.labelTooLong"), ControllerTestFixture.errors(model));
    }

    @Test
    void neverMeansNoExpiryAndABlankPinMeansAllItsWeblogs() throws Exception {
        mintsAs("prs_n", "forever");

        controller.create(request(), model, redirect, "forever", "READ", "", "never");

        verify(mocks.apiTokenManager()).issueToken(eq(me), eq("forever"), isNull(), eq(ApiToken.Role.READ),
                isNull(), eq(me));
    }

    /** Criterion 9: the reveal shows the secret once, is never cached, and carries an MCP snippet. */
    @Test
    void theRevealShowsTheSecretOnceWithNoStoreAndAnMcpSnippet() {
        model.addAttribute(TokenFormController.REVEALED_TOKEN, "prs_secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.reveal(request(), response, model);

        assertEquals(".TokenReveal", view);
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("https://blog.example/api/mcp", model.getAttribute("mcpUrl"));
        String snippet = (String) model.getAttribute("mcpSnippet");
        assertTrue(snippet.contains("https://blog.example/api/mcp"), snippet);
        assertTrue(snippet.contains("Authorization: Bearer prs_secret"), snippet);
    }

    /** Criterion 10: loading the reveal again has no secret to show, and is still not cached. */
    @Test
    void aSecondVisitHasNoSecretToShow() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.reveal(request(), response, model);

        assertNull(model.getAttribute(TokenFormController.REVEALED_TOKEN));
        assertNull(model.getAttribute("mcpSnippet"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    /** Criterion 10; review focus 1: no log line a mint writes carries the secret, on any logger, at any level. */
    @Test
    void noLogLineWrittenByAMintContainsTheSecret() throws Exception {
        mintsAs("prs_neverLogged", "ci");

        try (SsoLogCapture everything = SsoLogCapture.everyLogger();
             AdminLogCapture admin = AdminLogCapture.start()) {
            controller.create(request(), model, redirect, "ci", "POST", null, null);

            assertEquals(1, admin.lines().size(), admin.lines().toString());
            String line = admin.lines().get(0);
            assertTrue(line.startsWith("token.minted id=t1 label=ci owner=jake"), line);
            assertTrue(line.endsWith("by=jake"), line);
            assertFalse(line.contains("prs_neverLogged"), line);
            assertTrue(everything.everything().stream().noneMatch(l -> l.contains("prs_neverLogged")),
                    everything.everything().toString());
        }
    }

    /** Criterion 14: I can revoke my own token, and revoking it again succeeds and changes nothing. */
    @Test
    void revokingMyOwnTokenWorksAndTwiceIsHarmless() throws Exception {
        ApiToken mine = issued("prs_a", "ci").token();
        when(mocks.apiTokenManager().getToken("t1")).thenReturn(mine);

        try (AdminLogCapture admin = AdminLogCapture.start()) {
            assertEquals("redirect:/pressroom-ui/profileTokens.rol", controller.revoke(request(), redirect, "t1"));
            assertEquals(List.of("apiTokens.revoked"), ControllerTestFixture.flashMessages(redirect));

            mine.setRevokedAt(new Timestamp(System.currentTimeMillis()));
            RedirectAttributesModelMap again = new RedirectAttributesModelMap();
            controller.revoke(request(), again, "t1");
            assertEquals(List.of("apiTokens.revoked"), ControllerTestFixture.flashMessages(again));

            verify(mocks.apiTokenManager(), times(2)).revoke(mine);
            assertEquals(1, admin.lines().size(), "one revocation, one line");
        }
    }

    /** Criterion 14: another person's token id answers "no such token", and nothing is revoked. */
    @Test
    void anotherPersonsTokenIsNoSuchToken() throws Exception {
        User someoneElse = new User();
        someoneElse.setUserName("other");
        ApiToken theirs = issued("prs_b", "theirs").token();
        theirs.setUser(someoneElse);
        when(mocks.apiTokenManager().getToken("t2")).thenReturn(theirs);

        controller.revoke(request(), redirect, "t2");

        assertEquals(List.of("apiTokens.error.noSuchToken"), ControllerTestFixture.flashErrors(redirect));
        verify(mocks.apiTokenManager(), never()).revoke(any(ApiToken.class));
    }

    @Test
    void anUnknownTokenIsNoSuchToken() {
        controller.revoke(request(), redirect, "nosuch");

        assertEquals(List.of("apiTokens.error.noSuchToken"), ControllerTestFixture.flashErrors(redirect));
    }

    /** A store that fails mid-mint is a visible error and the form comes back; nothing is revealed. */
    @Test
    void aStoreFailureWhileMintingIsAnErrorNotAReveal() throws Exception {
        when(mocks.apiTokenManager().issueToken(any(), anyString(), any(), any(), any(), any()))
                .thenThrow(new WebloggerException("db down"));

        String view = controller.create(request(), model, redirect, "ci", "POST", null, null);

        assertEquals(".ProfileTokens", view);
        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
        assertTrue(redirect.getFlashAttributes().isEmpty());
    }

    /** Ruling 10: a store that fails mid-revoke is a visible failure, never "no such token" for a live token. */
    @Test
    void aStoreFailureWhileRevokingIsAFailureNotNoSuchToken() throws Exception {
        when(mocks.apiTokenManager().getToken("t1")).thenThrow(new WebloggerException("db down"));

        controller.revoke(request(), redirect, "t1");

        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.flashErrors(redirect));
        assertTrue(ControllerTestFixture.flashMessages(redirect).isEmpty());
    }

    /** Ruling 10: the revoke itself failing is the same visible failure. */
    @Test
    void aStoreFailureInTheRevokeItselfIsAFailure() throws Exception {
        ApiToken mine = issued("prs_a", "ci").token();
        when(mocks.apiTokenManager().getToken("t1")).thenReturn(mine);
        org.mockito.Mockito.doThrow(new WebloggerException("db down")).when(mocks.apiTokenManager()).revoke(mine);

        controller.revoke(request(), redirect, "t1");

        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.flashErrors(redirect));
    }
}
