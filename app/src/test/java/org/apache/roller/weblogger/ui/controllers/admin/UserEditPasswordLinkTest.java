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
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.List;

import jakarta.mail.internet.MimeMessage;

import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.business.startup.MockMailProvider;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.UserToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link UserEditController}'s admin "send set-password link"
 * action, and for the create-user validation it relaxes: when mail is
 * configured, an admin creating a user may leave the password blank and let
 * a set-password link do the rest instead of inventing a password to hand
 * over out of band.
 */
class UserEditPasswordLinkTest {

    private static final String ALLOWED_CHARS = "username.allowedChars";

    private MockWeblogger weblogger;
    private MockMailProvider mail;
    private UserEditController controller;
    private ExtendedModelMap model;
    private Object previousPasswordEncoder;
    private String previousAllowedChars;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        mail = MockMailProvider.install();
        previousPasswordEncoder = ControllerTestFixture.installBcryptPasswordEncoder();
        previousAllowedChars = ControllerTestFixture.setConfigProperty(ALLOWED_CHARS, "A-Za-z0-9");
        controller = ControllerTestFixture.withMessages(new UserEditController(
                org.mockito.Mockito.mock(org.apache.roller.weblogger.business.comments.CommentService.class)));
        model = new ExtendedModelMap();

        // Mail-ready by default (a transport, via MockMailProvider above, AND
        // a non-blank site.adminemail); tests exercising the "not ready"
        // branch override this per-test, mirroring PasswordResetControllerTest.
        when(weblogger.getPropertiesManager().getProperty("site.adminemail"))
                .thenReturn(new RuntimeConfigProperty("site.adminemail", "admin@example.invalid"));
    }

    @AfterEach
    void tearDown() {
        MockMailProvider.uninstall();
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
        ControllerTestFixture.restorePasswordEncoder(previousPasswordEncoder);
        ControllerTestFixture.restoreConfigProperty(ALLOWED_CHARS, previousAllowedChars);
    }

    // --- sending a link for an existing user ---

    @Test
    void sendingForAnExistingUserIssuesAPasswordSetTokenAndMailsTheResetUrl() throws Exception {
        User user = user("jake", "jake@example.com");
        when(weblogger.getUserManager().getUserByUserName("jake", null)).thenReturn(user);
        when(weblogger.getUserTokenManager().issueToken(user, UserToken.Purpose.PASSWORD_SET))
                .thenReturn("raw-token-abc");

        String view = controller.sendPasswordLink(ControllerTestFixture.requestFor(null), model, "jake");

        assertEquals(".UserEdit", view);
        assertEquals(List.of("userAdmin.passwordLinkSent[jake@example.com]"),
                ControllerTestFixture.messages(model));
        verify(weblogger.getUserTokenManager()).issueToken(user, UserToken.Purpose.PASSWORD_SET);
        verify(weblogger.weblogger()).flush();

        MimeMessage sent = mail.onlyMessage();
        assertEquals("jake@example.com", sent.getAllRecipients()[0].toString());
        assertTrue(sent.getContent().toString().contains("raw-token-abc"),
                "the email must carry the raw token in the reset URL");
    }

    @Test
    void sendingWhenMailIsNotConfiguredShowsAnErrorAndIssuesNoToken() throws Exception {
        MockMailProvider.uninstall();
        try {
            User user = user("jake", "jake@example.com");
            when(weblogger.getUserManager().getUserByUserName("jake", null)).thenReturn(user);

            String view = controller.sendPasswordLink(ControllerTestFixture.requestFor(null), model, "jake");

            assertEquals(".UserEdit", view);
            assertEquals(List.of("userAdmin.mailNotConfigured"), ControllerTestFixture.errors(model));
            verify(weblogger.getUserTokenManager(), never()).issueToken(any(), any());
        } finally {
            mail = MockMailProvider.install();
        }
    }

    @Test
    void sendingWithATransportButNoSiteAdminEmailShowsTheSameError() throws Exception {
        // Transport is configured (setUp's MockMailProvider) but there is
        // nowhere to send from -- the guard must catch this too, not just an
        // absent transport.
        when(weblogger.getPropertiesManager().getProperty("site.adminemail"))
                .thenReturn(new RuntimeConfigProperty("site.adminemail", "   "));
        User user = user("jake", "jake@example.com");
        when(weblogger.getUserManager().getUserByUserName("jake", null)).thenReturn(user);

        String view = controller.sendPasswordLink(ControllerTestFixture.requestFor(null), model, "jake");

        assertEquals(".UserEdit", view);
        assertEquals(List.of("userAdmin.mailNotConfigured"), ControllerTestFixture.errors(model));
        verify(weblogger.getUserTokenManager(), never()).issueToken(any(), any());
        assertTrue(mail.sent().isEmpty());
    }

    @Test
    void sendingForAnUnknownUserReportsItInsteadOfEmailingAnything() throws Exception {
        when(weblogger.getUserManager().getUserByUserName("ghost", null)).thenReturn(null);

        String view = controller.sendPasswordLink(ControllerTestFixture.requestFor(null), model, "ghost");

        assertEquals(".UserAdmin", view);
        assertEquals(List.of("userAdmin.error.userNotFound"), ControllerTestFixture.errors(model));
        verify(weblogger.getUserTokenManager(), never()).issueToken(any(), any());
        assertTrue(mail.sent().isEmpty());
    }

    // --- create: blank password, mail configured ---

    @Test
    void creatingWithABlankPasswordWhileMailIsConfiguredSetsARandomPasswordAndMailsALink() throws Exception {
        CreateUserBean bean = beanFor("newbie", "");
        bean.setEmailAddress("newbie@example.com");
        when(weblogger.getUserTokenManager().issueToken(any(User.class), eq(UserToken.Purpose.PASSWORD_SET)))
                .thenReturn("raw-token-xyz");

        String view = controller.createUserSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserAdmin", view, "account creation itself must still succeed");
        assertEquals(List.of(), ControllerTestFixture.errors(model));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(weblogger.getUserManager()).addUser(saved.capture());
        User user = saved.getValue();
        assertEquals(Boolean.TRUE, user.getEnabled(), "criterion 17: the account is created enabled");
        assertNotNull(user.getPassword(), "a usable (if never-disclosed) password must still be set");
        assertFalse(user.getPassword().isEmpty(), "the password must not be the empty string");

        verify(weblogger.getUserTokenManager()).issueToken(user, UserToken.Purpose.PASSWORD_SET);
        MimeMessage sent = mail.onlyMessage();
        assertTrue(sent.getContent().toString().contains("raw-token-xyz"),
                "the new-user email must carry the raw token in the reset URL");
        assertEquals(List.of("userAdmin.userCreatedLinkSent"), ControllerTestFixture.messages(model));
    }

    @Test
    void creatingWithABlankPasswordWhileMailIsNotConfiguredStillFailsTheOldValidation() throws Exception {
        MockMailProvider.uninstall();
        try {
            CreateUserBean bean = beanFor("newbie", "");

            String view = controller.createUserSave(ControllerTestFixture.requestFor(null), model, bean);

            assertEquals(".UserEdit", view, "a rejected form must be redisplayed, not confirmed");
            assertEquals(List.of("error.add.user.missingPassword"), ControllerTestFixture.errors(model));
            verify(weblogger.getUserManager(), never()).addUser(any());
        } finally {
            mail = MockMailProvider.install();
        }
    }

    /** Criterion 17: the emailed link is offered only while mail is configured. */
    @Test
    void theLinkIsOfferedOnlyWhenMailIsConfigured() throws Exception {
        controller.createUserExecute(ControllerTestFixture.requestFor(null), model);
        assertEquals(Boolean.TRUE, model.getAttribute("mailConfigured"));

        MockMailProvider.uninstall();
        try {
            ExtendedModelMap noMail = new ExtendedModelMap();
            controller.createUserExecute(ControllerTestFixture.requestFor(null), noMail);
            assertEquals(Boolean.FALSE, noMail.getAttribute("mailConfigured"));
        } finally {
            mail = MockMailProvider.install();
        }
    }

    // --- helpers ---

    private static User user(String userName, String email) {
        User user = new User();
        user.setUserName(userName);
        user.setEmailAddress(email);
        user.setEnabled(Boolean.TRUE);
        return user;
    }

    private static CreateUserBean beanFor(String userName, String password) {
        CreateUserBean bean = new CreateUserBean();
        bean.setUserName(userName);
        bean.setPassword(password);
        bean.setScreenName("Screen " + userName);
        bean.setFullName("Full " + userName);
        bean.setEmailAddress(userName + "@example.com");
        // "Email a set-password link" is the choice these tests exercise.
        bean.setPasswordMode(CreateUserBean.PASSWORD_LINK);
        bean.setEnabled(Boolean.TRUE);
        return bean;
    }
}
