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
package com.wikantik.pressroom.ui.controllers.admin;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.comments.CommentService;
import com.wikantik.pressroom.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterisation tests (spec 2026-11-20-jsp-rules-and-view-models-design,
 * Task 2, criterion 1): written before the account rules moved out of
 * {@link UserEditController}, {@link AgentController} and {@code UserNames},
 * and passing on the code they replaced. Each pins a screen's whole answer to
 * a refused form -- every error's message key in order, the controls marked,
 * the view -- and that nothing was written, so the move (criterion 3) is seen
 * to change none of them.
 */
class AccountScreensCharacterisationTest {

    private static final String ALLOWED_CHARS = "username.allowedChars";

    private MockWeblogger weblogger;
    private UserEditController people;
    private AgentController agents;
    private ExtendedModelMap model;
    private RedirectAttributesModelMap redirect;
    private String previousAllowedChars;
    private Object previousPasswordEncoder;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        previousPasswordEncoder = ControllerTestFixture.installBcryptPasswordEncoder();
        previousAllowedChars = ControllerTestFixture.setConfigProperty(ALLOWED_CHARS, "A-Za-z0-9");
        people = ControllerTestFixture.withMessages(new UserEditController(mock(CommentService.class)));
        agents = ControllerTestFixture.withMessages(new AgentController());
        model = new ExtendedModelMap();
        redirect = new RedirectAttributesModelMap();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
        ControllerTestFixture.restorePasswordEncoder(previousPasswordEncoder);
        ControllerTestFixture.restoreConfigProperty(ALLOWED_CHARS, previousAllowedChars);
    }

    // --- new person ---

    @Test
    void anEmptyNewPersonNamesEveryRequiredFieldInFormOrder() throws Exception {
        assertNewPersonRefused(new CreateUserBean(),
                List.of("error.add.user.missingUserName", "personForm.error.screenName", "personForm.error.fullName",
                        "personForm.error.email", "error.add.user.missingPassword"),
                List.of("bean_userName", "bean_screenName", "bean_fullName", "bean_email", "bean_password"));
    }

    @Test
    void aNewPersonsUserNameOutsideTheAllowedCharactersIsRefused() throws Exception {
        assertNewPersonRefused(person("jake fear"), List.of("error.add.user.badUserName"), List.of("bean_userName"));
    }

    @Test
    void aNewPersonsBlankUserNameIsNotTrimmedSoItsSpacesAreBadCharacters() throws Exception {
        assertNewPersonRefused(person("   "), List.of("error.add.user.badUserName"), List.of("bean_userName"));
    }

    @Test
    void withNoConfiguredCharactersLettersAndDigitsAreTheRule() throws Exception {
        ControllerTestFixture.setConfigProperty(ALLOWED_CHARS, null);

        assertNewPersonRefused(person("jake_fear"), List.of("error.add.user.badUserName"), List.of("bean_userName"));
    }

    @Test
    void aNewPersonsUserNameTakenExactlyIsRefused() throws Exception {
        when(weblogger.userManager().getUserByUserName("jake", null)).thenReturn(new User());

        assertNewPersonRefused(person("jake"), List.of("error.add.user.userNameInUse"), List.of("bean_userName"));
    }

    @Test
    void aNewPersonsUserNameTakenInAnotherCaseIsRefused() throws Exception {
        when(weblogger.userManager().getUserByUserName("jake", null)).thenReturn(new User());

        assertNewPersonRefused(person("Jake"), List.of("error.add.user.userNameInUse"), List.of("bean_userName"));
    }

    @Test
    void aTakenCheckThatCannotRunRefusesTheNewPerson() throws Exception {
        when(weblogger.userManager().getUserByUserName("jake", null)).thenThrow(new WebloggerException("down"));

        assertNewPersonRefused(person("jake"), List.of("generic.error.check.logs"), List.of("bean_userName"));
    }

    @Test
    void aNewPersonsBlankScreenNameIsRefused() throws Exception {
        CreateUserBean bean = person("jake");
        bean.setScreenName(" \t");

        assertNewPersonRefused(bean, List.of("personForm.error.screenName"), List.of("bean_screenName"));
    }

    @Test
    void aNewPersonsBlankFullNameIsRefused() throws Exception {
        CreateUserBean bean = person("jake");
        bean.setFullName(" ");

        assertNewPersonRefused(bean, List.of("personForm.error.fullName"), List.of("bean_fullName"));
    }

    @Test
    void aNewPersonsAddressTheValidatorRefusesIsRefused() throws Exception {
        for (String address : List.of("a@b.c", "not-an-address", " ", "a b@example.com")) {
            model = new ExtendedModelMap();
            CreateUserBean bean = person("jake");
            bean.setEmailAddress(address);

            assertNewPersonRefused(bean, List.of("personForm.error.email"), List.of("bean_email"));
        }
    }

    @Test
    void aNewPersonsAddressIsJudgedTrimmed() throws Exception {
        CreateUserBean bean = person("jake");
        bean.setEmailAddress("  jake@example.com \t");

        assertEquals(".UserAdmin", people.createUserSave(asAdmin(), model, bean));

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        verify(weblogger.userManager()).addUser(any());
    }

    @Test
    void aNewPersonsShortPasswordIsRefused() throws Exception {
        CreateUserBean bean = person("jake");
        bean.setPassword("1234567");

        assertNewPersonRefused(bean, List.of("personForm.error.passwordTooShort"), List.of("bean_password"));
    }

    @Test
    void aSetPasswordLinkWithoutMailStillNeedsAPassword() throws Exception {
        CreateUserBean bean = person("jake");
        bean.setPassword(null);
        bean.setPasswordMode(CreateUserBean.PASSWORD_LINK);

        assertNewPersonRefused(bean, List.of("error.add.user.missingPassword"), List.of("bean_password"));
    }

    @Test
    void aPasswordOfEightCharactersIsEnough() throws Exception {
        CreateUserBean bean = person("jake");
        bean.setPassword("12345678");

        assertEquals(".UserAdmin", people.createUserSave(asAdmin(), model, bean));
        assertEquals(List.of(), ControllerTestFixture.errors(model));
    }

    @Test
    void aLongUserNameIsNotJudgedByTheScreenSoTheStoreDecides() throws Exception {
        assertEquals(".UserAdmin", people.createUserSave(asAdmin(), model, person("j".repeat(300))));
        verify(weblogger.userManager()).addUser(any());
    }

    // --- edit person ---

    @Test
    void anEditedPersonsShortPasswordIsRefused() throws Exception {
        stored("jake", false);
        CreateUserBean bean = existing("jake");
        bean.setPassword("short");

        assertEditRefused(bean, List.of("personForm.error.passwordTooShort"), List.of("bean_password"));
    }

    @Test
    void aBlankPasswordOnEditLeavesThePasswordAlone() throws Exception {
        User user = stored("jake", false);
        String hash = user.getPassword();
        CreateUserBean bean = existing("jake");
        bean.setPassword("");

        assertEquals(".UserAdmin", people.modifyUserSave(asAdmin(), model, bean));

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals(hash, user.getPassword());
    }

    /** Pinned so the move does not add them: the edit screen judges neither the address nor the name today. */
    @Test
    void theEditScreenJudgesNoAddressAndNoScreenName() throws Exception {
        User user = stored("jake", false);
        CreateUserBean bean = existing("jake");
        bean.setEmailAddress("a@b.c");
        bean.setScreenName(" ");

        assertEquals(".UserAdmin", people.modifyUserSave(asAdmin(), model, bean));

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("a@b.c", user.getEmailAddress());
    }

    @Test
    void aPasswordForAnSsoBoundPersonIsRefusedOnItsField() throws Exception {
        stored("reader-abc1234567", true);
        CreateUserBean bean = existing("reader-abc1234567");
        bean.setPassword("long-enough-password");

        assertEditRefused(bean, List.of("userAdmin.error.ssoPassword"), List.of("bean_password"));
    }

    @Test
    void aShortPasswordForAnSsoBoundPersonIsTheSsoRefusalAlone() throws Exception {
        stored("reader-abc1234567", true);
        CreateUserBean bean = existing("reader-abc1234567");
        bean.setPassword("short");

        assertEditRefused(bean, List.of("userAdmin.error.ssoPassword"), List.of("bean_password"));
    }

    @Test
    void anSsoBoundPersonMadeAdminIsRefusedBeforeAnyOtherCheck() throws Exception {
        stored("reader-abc1234567", true);
        CreateUserBean bean = existing("reader-abc1234567");
        bean.setAdministrator(true);
        bean.setPassword("short");

        assertEditRefused(bean, List.of("userAdmin.error.ssoAdmin"), List.of());
    }

    @Test
    void aSetPasswordLinkForAnSsoBoundPersonIsRefused() throws Exception {
        stored("reader-abc1234567", true);

        assertEquals(".UserEdit", people.sendPasswordLink(asAdmin(), model, "reader-abc1234567"));

        assertEquals(List.of("userAdmin.error.ssoPassword"), ControllerTestFixture.errors(model));
        assertEquals(List.of(), ControllerTestFixture.invalidFields(model));
        verify(weblogger.getUserTokenManager(), never()).issueToken(any(), any());
    }

    @Test
    void editingAnUnknownPersonIsNoSuchUser() throws Exception {
        assertEquals(".UserAdmin", people.modifyUserSave(asAdmin(), model, existing("ghost")));

        assertEquals(List.of("userAdmin.error.userNotFound"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).saveUser(any());
    }

    @Test
    void editingAnAgentAsAPersonIsRefused() throws Exception {
        User bot = stored("bot1", false);
        bot.setKind(User.Kind.AGENT);

        assertEquals(".UserAdmin", people.modifyUserSave(asAdmin(), model, existing("bot1")));

        assertEquals(List.of("agent.error.notAPerson"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).saveUser(any());
    }

    // --- new agent ---

    @Test
    void anEmptyNewAgentNamesItsUserNameAndName() throws Exception {
        assertNewAgentRefused(new AgentBean(), List.of("error.add.user.missingUserName", "agent.error.nameRequired"),
                List.of("bean_userName", "bean_name"));
    }

    @Test
    void aNewAgentsUserNameOutsideTheAllowedCharactersIsRefused() throws Exception {
        assertNewAgentRefused(agent("bot one"), List.of("error.add.user.badUserName"), List.of("bean_userName"));
    }

    @Test
    void aNewAgentsUserNameTakenInAnyCaseIsRefused() throws Exception {
        when(weblogger.userManager().getUserByUserName("bot1", null)).thenReturn(new User());

        assertNewAgentRefused(agent("BOT1"), List.of("error.add.user.userNameInUse"), List.of("bean_userName"));
    }

    @Test
    void aTakenCheckThatCannotRunRefusesTheNewAgent() throws Exception {
        when(weblogger.userManager().getUserByUserName("bot1", null)).thenThrow(new WebloggerException("down"));

        assertNewAgentRefused(agent("bot1"), List.of("generic.error.check.logs"), List.of("bean_userName"));
    }

    @Test
    void aNewAgentsAddressTheValidatorRefusesIsRefused() throws Exception {
        AgentBean bean = agent("bot1");
        bean.setEmailAddress(" a@b.c ");

        assertNewAgentRefused(bean, List.of("personForm.error.email"), List.of("bean_emailAddress"));
    }

    @Test
    void aNewAgentsBlankAddressIsLeftToTheStore() throws Exception {
        AgentBean bean = agent("bot1");
        bean.setEmailAddress("  ");

        assertEquals("redirect:/pressroom-ui/admin/agent.rol", agents.create(asAdmin(), model, redirect, bean));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(weblogger.userManager()).addAgent(saved.capture(), anyBoolean());
        assertEquals(null, saved.getValue().getEmailAddress());
    }

    @Test
    void aNewAgentsAddressIsStoredTrimmed() throws Exception {
        AgentBean bean = agent("bot1");
        bean.setEmailAddress("  bot@example.com ");

        agents.create(asAdmin(), model, redirect, bean);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(weblogger.userManager()).addAgent(saved.capture(), anyBoolean());
        assertEquals("bot@example.com", saved.getValue().getEmailAddress());
    }

    // --- helpers ---

    private void assertNewPersonRefused(CreateUserBean bean, List<String> errors, List<String> fields)
            throws WebloggerException {
        assertEquals(".UserEdit", people.createUserSave(asAdmin(), model, bean));
        assertEquals(errors, ControllerTestFixture.errors(model));
        assertEquals(fields, ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    private void assertEditRefused(CreateUserBean bean, List<String> errors, List<String> fields)
            throws WebloggerException {
        assertEquals(".UserEdit", people.modifyUserSave(asAdmin(), model, bean));
        assertEquals(errors, ControllerTestFixture.errors(model));
        assertEquals(fields, ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).saveUser(any());
    }

    private void assertNewAgentRefused(AgentBean bean, List<String> errors, List<String> fields)
            throws WebloggerException {
        assertEquals(".AgentCreate", agents.create(asAdmin(), model, redirect, bean));
        assertEquals(errors, ControllerTestFixture.errors(model));
        assertEquals(fields, ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addAgent(any(), anyBoolean());
    }

    private static HttpServletRequest asAdmin() {
        User admin = new User();
        admin.setUserName("admin");
        return ControllerTestFixture.requestFor(admin);
    }

    /** A new person whose every field passes, but for what a test changes. */
    private static CreateUserBean person(String userName) {
        CreateUserBean bean = new CreateUserBean();
        bean.setUserName(userName);
        bean.setPassword("long-enough-password");
        bean.setScreenName("Screen");
        bean.setFullName("Full Name");
        bean.setEmailAddress("person@example.com");
        bean.setPasswordMode(CreateUserBean.PASSWORD_NOW);
        bean.setEnabled(Boolean.TRUE);
        return bean;
    }

    /** The edit form as posted for {@code userName}, every field passing. */
    private static CreateUserBean existing(String userName) {
        CreateUserBean bean = new CreateUserBean();
        bean.setUserName(userName);
        bean.setScreenName("Screen");
        bean.setFullName("Full Name");
        bean.setEmailAddress("person@example.com");
        bean.setEnabled(Boolean.TRUE);
        return bean;
    }

    private User stored(String userName, boolean ssoBound) throws WebloggerException {
        User user = new User();
        user.setUserName(userName);
        user.setScreenName("Screen");
        user.setEmailAddress(userName + "@example.com");
        user.setEnabled(Boolean.TRUE);
        user.resetPassword("original-password");
        if (ssoBound) {
            user.setPassword(User.SSO_PASSWORD_MARKER);
            user.setSsoIssuer("https://accounts.example");
            user.setSsoSubject("sub-1");
        }
        when(weblogger.userManager().getUserByUserName(userName, null)).thenReturn(user);
        return user;
    }

    private static AgentBean agent(String userName) {
        AgentBean bean = new AgentBean();
        bean.setUserName(userName);
        bean.setName("Publisher");
        return bean;
    }
}
