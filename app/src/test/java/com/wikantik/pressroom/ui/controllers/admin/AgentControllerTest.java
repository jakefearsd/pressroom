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

import java.sql.Timestamp;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.testing.AdminLogCapture;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.TokenFormController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** New agent and the agent page (spec 2026-10-13, sections 1, 3 and 4; criteria 2, 3, 6, 8, 9, 11, 14). */
class AgentControllerTest {

    private static final String ALLOWED_CHARS = "username.allowedChars";

    private MockWeblogger weblogger;
    private AgentController controller;
    private ExtendedModelMap model;
    private RedirectAttributesModelMap redirect;
    private String previousAllowedChars;
    private User admin;
    private User agent;
    private Weblog travel;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        previousAllowedChars = ControllerTestFixture.setConfigProperty(ALLOWED_CHARS, "A-Za-z0-9");
        controller = ControllerTestFixture.withMessages(new AgentController());
        model = new ExtendedModelMap();
        redirect = new RedirectAttributesModelMap();
        admin = new User();
        admin.setUserName("admin");
        agent = new User();
        agent.setUserName("bot1");
        agent.setScreenName("Publisher");
        agent.setFullName("Publisher");
        agent.setKind(User.Kind.AGENT);
        travel = new Weblog();
        travel.setHandle("travel");
        travel.setName("Travel");
        when(weblogger.weblogManager().getWeblogByHandle("travel", null)).thenReturn(travel);
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
        ControllerTestFixture.restoreConfigProperty(ALLOWED_CHARS, previousAllowedChars);
    }

    private HttpServletRequest asAdmin() {
        return ControllerTestFixture.requestFor(admin);
    }

    private static AgentBean newAgent(String userName, String name) {
        AgentBean bean = new AgentBean();
        bean.setUserName(userName);
        bean.setName(name);
        return bean;
    }

    private void stored() throws WebloggerException {
        when(weblogger.userManager().getUserByUserName("bot1", null)).thenReturn(agent);
    }

    private void siteAdmin(boolean on) throws WebloggerException {
        when(weblogger.userManager().checkPermission(
                argThat(p -> p instanceof GlobalPermission g && g.hasAction(GlobalPermission.ADMIN)), eq(agent)))
                .thenReturn(on);
    }

    private ApiToken tokenOf(User owner, String id) {
        ApiToken token = new ApiToken();
        token.setId(id);
        token.setUser(owner);
        token.setLabel("publisher");
        token.setScopeRole(ApiToken.Role.POST);
        return token;
    }

    @Test
    void theAgentPagesAreAdminOnlyAndNeedNoWeblog() {
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
        assertEquals("admin", controller.getDesiredMenu());
        assertEquals("agent", controller.getActionName());
    }

    @Test
    void theCreateFormStartsEmpty() {
        assertEquals(".AgentCreate", controller.createForm(asAdmin(), model));
        assertNull(((AgentBean) model.getAttribute("bean")).getUserName());
        assertEquals("agent.create.title", model.getAttribute("pageTitle"));
    }

    /** Criterion 2: an agent is stored as one, with its name and no password, and its page opens. */
    @Test
    void creatingAnAgentStoresItAndOpensItsPage() throws Exception {
        try (AdminLogCapture log = AdminLogCapture.start()) {
            String view = controller.create(asAdmin(), model, redirect, newAgent("bot1", "  Publisher "));

            assertEquals("redirect:/pressroom-ui/admin/agent.rol", view);
            assertEquals("bot1", redirect.asMap().get("userName"));
            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(weblogger.userManager()).addAgent(saved.capture(), eq(false));
            User stored = saved.getValue();
            assertEquals("bot1", stored.getUserName());
            assertEquals("Publisher", stored.getScreenName());
            assertEquals("Publisher", stored.getFullName());
            assertNull(stored.getEmailAddress(), "a blank email is the manager's to fill: bot1@agents.invalid");
            assertNull(stored.getPassword());
            assertEquals(1, weblogger.flushCount());
            assertEquals(List.of("agent.created[bot1]"), ControllerTestFixture.flashMessages(redirect));
            assertEquals(List.of("agent.created agent=bot1 siteAdmin=false by=admin"), log.lines());
        }
    }

    /** Criterion 2: the Site administrator switch reaches the store. */
    @Test
    void theSiteAdministratorSwitchIsPassedThrough() throws Exception {
        AgentBean bean = newAgent("bot1", "Publisher");
        bean.setSiteAdmin(true);

        controller.create(asAdmin(), model, redirect, bean);

        verify(weblogger.userManager()).addAgent(any(User.class), eq(true));
    }

    /** Final review I1: a grep for siteAdmin.granted must find an agent created as a site admin. */
    @Test
    void anAgentCreatedAsSiteAdminAlsoLogsTheGrant() throws Exception {
        AgentBean bean = newAgent("bot1", "Publisher");
        bean.setSiteAdmin(true);

        try (AdminLogCapture log = AdminLogCapture.start()) {
            controller.create(asAdmin(), model, redirect, bean);

            assertEquals(List.of("agent.created agent=bot1 siteAdmin=true by=admin",
                    "siteAdmin.granted account=bot1 by=admin"), log.lines());
        }
    }

    /** Criterion 3: the username follows username.allowedChars. */
    @Test
    void anAgentUserNameFollowsTheAllowedCharacters() throws Exception {
        String view = controller.create(asAdmin(), model, redirect, newAgent("bot one", "Publisher"));

        assertEquals(".AgentCreate", view);
        assertEquals(List.of("error.add.user.badUserName"), ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_userName"), ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addAgent(any(), eq(false));
    }

    /** Criterion 3: a taken username is a field error on the username. */
    @Test
    void aTakenUserNameIsAFieldErrorOnTheUserName() throws Exception {
        stored();

        controller.create(asAdmin(), model, redirect, newAgent("bot1", "Publisher"));

        assertEquals(List.of("error.add.user.userNameInUse"), ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_userName"), ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addAgent(any(), eq(false));
    }

    /** Final review M5: "Bot" beside an existing "bot" is the same field error, not "check the logs". */
    @Test
    void aUserNameTakenInAnotherCaseIsAFieldErrorOnTheUserName() throws Exception {
        stored();

        controller.create(asAdmin(), model, redirect, newAgent("BOT1", "Publisher"));

        assertEquals(List.of("error.add.user.userNameInUse"), ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_userName"), ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addAgent(any(), eq(false));
    }

    @Test
    void aNameIsRequiredAndAGivenEmailMustBeValid() throws Exception {
        AgentBean bean = newAgent("bot2", "  ");
        bean.setEmailAddress("nope");

        controller.create(asAdmin(), model, redirect, bean);

        assertEquals(List.of("agent.error.nameRequired", "personForm.error.email"), ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_name", "bean_emailAddress"), ControllerTestFixture.invalidFields(model));
    }

    /** A store failure keeps the form and is reported; nothing is announced. */
    @Test
    void aStoreFailureWhileCreatingKeepsTheForm() throws Exception {
        doThrow(new WebloggerException("db down")).when(weblogger.userManager()).addAgent(any(), eq(false));

        String view = controller.create(asAdmin(), model, redirect, newAgent("bot3", "Publisher"));

        assertEquals(".AgentCreate", view);
        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
        assertEquals(0, weblogger.flushCount());
        assertTrue(ControllerTestFixture.flashMessages(redirect).isEmpty());
    }

    @Test
    void notesLongerThanTheColumnAreRefused() throws Exception {
        AgentBean bean = newAgent("bot2", "Publisher");
        bean.setNotes("x".repeat(1001));

        controller.create(asAdmin(), model, redirect, bean);

        assertEquals(List.of("agent.error.notesTooLong"), ControllerTestFixture.errors(model));
    }

    /** Criterion 6 and the page's sections: details, status, weblogs and tokens; no password anywhere. */
    @Test
    void thePageShowsDetailsStatusWeblogsAndTokens() throws Exception {
        stored();
        siteAdmin(false);
        agent.setNotes("Publishes the travel guides");
        ApiToken live = tokenOf(agent, "t1");
        when(weblogger.apiTokenManager().getLiveTokens(agent)).thenReturn(List.of(live));

        String view = controller.page(asAdmin(), model, "bot1");

        assertEquals(".Agent", view);
        AgentBean bean = (AgentBean) model.getAttribute("bean");
        assertEquals("bot1", bean.getUserName());
        assertEquals("Publisher", bean.getName());
        assertEquals("Publishes the travel guides", bean.getNotes());
        assertFalse(bean.isSiteAdmin());
        assertEquals(Boolean.TRUE, model.getAttribute("agentEnabled"));
        assertEquals(List.of(live), model.getAttribute("tokens"));
        assertEquals("/pressroom-ui/admin/agent!mintToken.rol", model.getAttribute("tokenFormAction"));
        assertEquals("/pressroom-ui/admin/agent!revokeToken.rol", model.getAttribute("tokenRevokeAction"));
        assertEquals("bot1", model.getAttribute("tokenFormOwner"));
        assertEquals("bot1", model.getAttribute("weblogsAccount"));
        assertNotNull(model.getAttribute("permissions"));
    }

    private void roleLookupFails() throws WebloggerException {
        when(weblogger.userManager().checkPermission(
                argThat(p -> p instanceof GlobalPermission g && g.hasAction(GlobalPermission.ADMIN)), eq(agent)))
                .thenThrow(new WebloggerException("db down"));
    }

    /**
     * Final review M7: a Site administrator box drawn unticked because the role could not be read would let
     * the next Save of the name silently remove admin. No form is better than a form that lies.
     */
    @Test
    void aRoleThatCannotBeReadShowsAnErrorInsteadOfTheDetailsForm() throws Exception {
        stored();
        roleLookupFails();

        String view = controller.page(asAdmin(), model, "bot1");

        assertEquals(".UserAdmin", view);
        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
        assertNull(model.getAttribute("bean"));
    }

    @Test
    void aRefusedMintAlsoShowsNoFormWhenTheRoleCannotBeRead() throws Exception {
        stored();
        roleLookupFails();

        String view = controller.mint(asAdmin(), model, redirect, "bot1", "", "POST", "", "90");

        assertEquals(".UserAdmin", view);
        assertTrue(ControllerTestFixture.errors(model).contains("generic.error.check.logs"));
        assertNull(model.getAttribute("bean"));
    }

    @Test
    void aPersonOrAnUnknownNameIsNotAnAgentPage() throws Exception {
        User person = new User();
        person.setUserName("jake");
        when(weblogger.userManager().getUserByUserName("jake", null)).thenReturn(person);

        assertEquals(".UserAdmin", controller.page(asAdmin(), model, "jake"));
        assertEquals(List.of("userAdmin.error.userNotFound"), ControllerTestFixture.errors(model));

        ExtendedModelMap unknown = new ExtendedModelMap();
        assertEquals(".UserAdmin", controller.page(asAdmin(), unknown, "nobody"));
    }

    /** Criterion 8: disabling records enabled = false and logs who did it. */
    @Test
    void disablingRecordsItAndLogsWhoDidIt() throws Exception {
        stored();

        try (AdminLogCapture log = AdminLogCapture.start()) {
            String view = controller.setEnabled(asAdmin(), redirect, "bot1", "false");

            assertEquals("redirect:/pressroom-ui/admin/agent.rol", view);
            assertEquals("bot1", redirect.asMap().get("userName"));
            assertEquals(Boolean.FALSE, agent.getEnabled());
            verify(weblogger.userManager()).saveUser(agent);
            assertEquals(1, weblogger.flushCount());
            assertEquals(List.of("agent.disabled"), ControllerTestFixture.flashMessages(redirect));
            assertEquals(List.of("account.disabled account=bot1 by=admin"), log.lines());
        }
    }

    /** Final review M3: a double submit changes nothing, so the audit log says nothing. */
    @Test
    void settingTheStateItAlreadyHasLogsNothing() throws Exception {
        stored();

        try (AdminLogCapture log = AdminLogCapture.start()) {
            controller.setEnabled(asAdmin(), redirect, "bot1", "true");
            agent.setEnabled(Boolean.FALSE);
            controller.setEnabled(asAdmin(), redirect, "bot1", "false");

            assertEquals(List.of(), log.lines());
        }
    }

    @Test
    void enablingItAgainIsRecordedAndLoggedToo() throws Exception {
        stored();
        agent.setEnabled(Boolean.FALSE);

        try (AdminLogCapture log = AdminLogCapture.start()) {
            controller.setEnabled(asAdmin(), redirect, "bot1", "true");

            assertEquals(Boolean.TRUE, agent.getEnabled());
            assertEquals(List.of("account.enabled account=bot1 by=admin"), log.lines());
        }
    }

    /** Spec section 6: the site-admin switch is granted and removed, each logged. */
    @Test
    void savingTheDetailsRenamesAndGrantsOrRemovesSiteAdmin() throws Exception {
        stored();
        when(weblogger.userManager().hasRole("admin", agent)).thenReturn(false);
        AgentBean bean = newAgent("bot1", "Publisher Two");
        bean.setNotes("Now also food");
        bean.setSiteAdmin(true);

        try (AdminLogCapture log = AdminLogCapture.start()) {
            assertEquals("redirect:/pressroom-ui/admin/agent.rol", controller.save(asAdmin(), model, redirect, bean));

            assertEquals("Publisher Two", agent.getScreenName());
            assertEquals("Publisher Two", agent.getFullName());
            assertEquals("Now also food", agent.getNotes());
            verify(weblogger.userManager()).saveUser(agent);
            verify(weblogger.userManager()).grantRole("admin", agent);
            assertEquals(List.of("siteAdmin.granted account=bot1 by=admin"), log.lines());
        }

        when(weblogger.userManager().hasRole("admin", agent)).thenReturn(true);
        controller.save(asAdmin(), new ExtendedModelMap(), new RedirectAttributesModelMap(),
                newAgent("bot1", "Publisher Two"));
        verify(weblogger.userManager()).revokeRole("admin", agent);
    }

    /** Criterion 9: a mint from the agent page stores the choices, the default expiry and the issuer, then reveals. */
    @Test
    void mintingForTheAgentStoresTheChoicesAndRedirectsToTheReveal() throws Exception {
        stored();
        siteAdmin(false);
        when(weblogger.userManager().getWeblogPermission(travel, agent))
                .thenReturn(new WeblogPermission(travel, agent, List.of(WeblogPermission.POST)));
        when(weblogger.apiTokenManager().issueToken(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(new ApiTokenManager.Issued("prs_agent", tokenOf(agent, "t1")));
        long before = System.currentTimeMillis();

        String view = controller.mint(asAdmin(), model, redirect, "bot1", "publisher", "POST", "travel", null);

        assertEquals("redirect:/pressroom-ui/profileTokens!reveal.rol", view);
        ArgumentCaptor<Timestamp> expires = ArgumentCaptor.forClass(Timestamp.class);
        verify(weblogger.apiTokenManager()).issueToken(eq(agent), eq("publisher"), eq("travel"),
                eq(ApiToken.Role.POST), expires.capture(), eq(admin));
        long ninetyDays = 90L * 24 * 60 * 60 * 1000;
        long expiry = expires.getValue().getTime();
        assertTrue(expiry >= before + ninetyDays - 60_000L
                && expiry <= System.currentTimeMillis() + ninetyDays + 60_000L, "the default expiry is 90 days");
        assertEquals("prs_agent", redirect.getFlashAttributes().get(TokenFormController.REVEALED_TOKEN));
        assertEquals("bot1", redirect.getFlashAttributes().get(TokenFormController.REVEALED_FOR_AGENT));
    }

    /** Criterion 11: a pin to a weblog the agent is not in is a field error, and the page comes back. */
    @Test
    void aPinToAWeblogTheAgentIsNotInIsRefused() throws Exception {
        stored();
        siteAdmin(false);

        String view = controller.mint(asAdmin(), model, redirect, "bot1", "publisher", "POST", "travel", "90");

        assertEquals(".Agent", view);
        assertEquals(List.of("apiTokens.error.pin"), ControllerTestFixture.errors(model));
        assertEquals(List.of("tokenPin"), ControllerTestFixture.invalidFields(model));
        assertEquals("bot1", model.getAttribute("tokenFormOwner"));
        verify(weblogger.apiTokenManager(), never()).issueToken(any(), anyString(), any(), any(), any(), any());
    }

    /** Criterion 11: a site-admin agent may pin any existing weblog. */
    @Test
    void aSiteAdminAgentMayPinAnyExistingWeblog() throws Exception {
        stored();
        siteAdmin(true);
        when(weblogger.apiTokenManager().issueToken(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(new ApiTokenManager.Issued("prs_ops", tokenOf(agent, "t2")));

        assertEquals("redirect:/pressroom-ui/profileTokens!reveal.rol",
                controller.mint(asAdmin(), model, redirect, "bot1", "ops", "ADMIN", "travel", "never"));
    }

    @Test
    void revokingTheAgentsTokenWorks() throws Exception {
        stored();
        ApiToken token = tokenOf(agent, "t1");
        when(weblogger.apiTokenManager().getToken("t1")).thenReturn(token);

        assertEquals("redirect:/pressroom-ui/admin/agent.rol", controller.revoke(asAdmin(), redirect, "bot1", "t1"));

        verify(weblogger.apiTokenManager()).revoke(token);
        assertEquals(List.of("apiTokens.revoked"), ControllerTestFixture.flashMessages(redirect));
    }

    /** Criterion 14: from an agent's page, a token of another account is "no such token". */
    @Test
    void revokingAnotherAccountsTokenFromThisPageIsNoSuchToken() throws Exception {
        stored();
        when(weblogger.apiTokenManager().getToken("t9")).thenReturn(tokenOf(admin, "t9"));

        controller.revoke(asAdmin(), redirect, "bot1", "t9");

        assertEquals(List.of("apiTokens.error.noSuchToken"), ControllerTestFixture.flashErrors(redirect));
        verify(weblogger.apiTokenManager(), never()).revoke(any(ApiToken.class));
    }
}
