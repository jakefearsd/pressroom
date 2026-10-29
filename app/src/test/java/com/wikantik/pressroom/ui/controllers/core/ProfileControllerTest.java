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
package com.wikantik.pressroom.ui.controllers.core;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.comments.CommentService;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.TestUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link ProfileController}, the page where a signed-in user edits
 * their own account.
 *
 * <p>The controller only ever writes the user attached to the request, so the
 * tests concentrate on the validation in front of that write: a mistyped
 * password confirmation, and locale or time zone values that would be stored
 * and then fail to resolve everywhere they are used.
 */
class ProfileControllerTest {

    private MockWeblogger weblogger;
    private CommentService comments;
    private ProfileController controller;
    private ExtendedModelMap model;
    private RedirectAttributes redirectAttributes;
    private Object previousPasswordEncoder;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        previousPasswordEncoder = ControllerTestFixture.installBcryptPasswordEncoder();
        comments = org.mockito.Mockito.mock(CommentService.class);
        controller = ControllerTestFixture.withMessages(new ProfileController(comments));
        model = new ExtendedModelMap();
        redirectAttributes = new RedirectAttributesModelMap();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
        ControllerTestFixture.restorePasswordEncoder(previousPasswordEncoder);
    }

    @Test
    void theFormOpensOnTheSignedInUsersOwnDetails() {
        User user = user("jake");
        user.setScreenName("Jake");
        user.setEmailAddress("jake@example.com");
        user.setLocale("en_GB");
        user.setTimeZone("Europe/Berlin");
        ProfileBean bean = new ProfileBean();

        String view = controller.execute(ControllerTestFixture.requestFor(user), model, bean);

        assertEquals(".Profile", view);
        assertEquals("jake", bean.getUserName());
        assertEquals("Jake", bean.getScreenName());
        assertEquals("jake@example.com", bean.getEmailAddress());
        assertEquals("en_GB", bean.getLocale());
        assertEquals("Europe/Berlin", bean.getTimeZone());
        assertFalse(((List<?>) model.getAttribute("localesList")).isEmpty());
        assertFalse(((List<?>) model.getAttribute("timeZonesList")).isEmpty());
        assertEquals("yourProfile.title", model.getAttribute("pageTitle"));
        assertEquals(user, model.getAttribute("authenticatedUser"));
    }

    @Test
    void savingWritesTheChangesAndReturnsToTheMenuWithConfirmation() throws Exception {
        User user = user("jake");
        ProfileBean bean = new ProfileBean();
        bean.setScreenName("Jake Renamed");
        bean.setEmailAddress("new@example.com");
        bean.setLocale("en_US");
        bean.setTimeZone("America/New_York");

        String view = controller.save(ControllerTestFixture.requestFor(user), model, bean, redirectAttributes);

        assertEquals("redirect:/roller-ui/menu.rol", view);
        verify(weblogger.userManager()).saveUser(user);
        verify(weblogger.weblogger()).flush();
        assertEquals("Jake Renamed", user.getScreenName());
        assertEquals("new@example.com", user.getEmailAddress());
        assertEquals(List.of("generic.changes.saved"),
                ControllerTestFixture.flashMessages(redirectAttributes));
    }

    /**
     * M8: an owner's screen name signs their comments and replies, and rendered
     * pages expire only through the weblog. A rename here expires every weblog
     * showing the account's published comments, after the save; any other edit
     * leaves them alone.
     */
    @Test
    void renamingExpiresTheWeblogsShowingTheAccountsComments() throws Exception {
        User user = user("jake");
        user.setScreenName("Jake");
        ProfileBean bean = new ProfileBean();
        bean.setScreenName("Jake Renamed");

        controller.save(ControllerTestFixture.requestFor(user), model, bean, redirectAttributes);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(weblogger.weblogger(), comments);
        order.verify(weblogger.weblogger()).flush();
        order.verify(comments).expireWhereShown(user);
    }

    @Test
    void keepingTheNameExpiresNothing() throws Exception {
        User user = user("jake");
        user.setScreenName("Jake");
        ProfileBean bean = new ProfileBean();
        bean.setScreenName("Jake");
        bean.setEmailAddress("new@example.com");

        controller.save(ControllerTestFixture.requestFor(user), model, bean, redirectAttributes);

        verify(weblogger.userManager()).saveUser(user);
        verify(comments, never()).expireWhereShown(any());
    }

    @Test
    void aMistypedPasswordConfirmationIsRefusedWithAMessageTheUserCanRead() throws Exception {
        // The confirmation field exists precisely to catch a typo; accepting the
        // mismatch would lock the user out of their own account.
        User user = user("jake");
        ProfileBean bean = new ProfileBean();
        bean.setPasswordText("newpassword");
        bean.setPasswordConfirm("newpasswrod");

        String view = controller.save(ControllerTestFixture.requestFor(user), model, bean, redirectAttributes);

        assertEquals(".Profile", view);
        assertEquals(List.of("yourProfile.passwordsNotSame"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).saveUser(any());
        // The form is redisplayed, so it still needs everything it renders with.
        assertEquals("yourProfile.title", model.getAttribute("pageTitle"));
        assertFalse(((List<?>) model.getAttribute("localesList")).isEmpty());
        assertFalse(((List<?>) model.getAttribute("timeZonesList")).isEmpty());
    }

    @Test
    void aConfirmedPasswordIsStoredEncoded() throws Exception {
        User user = user("jake");
        user.setPassword(TestUtils.TEST_PASSWORD_HASH);
        ProfileBean bean = new ProfileBean();
        bean.setPasswordText("newpassword");
        bean.setPasswordConfirm("newpassword");

        String view = controller.save(ControllerTestFixture.requestFor(user), model, bean, redirectAttributes);

        assertEquals("redirect:/roller-ui/menu.rol", view);
        assertTrue(new BCryptPasswordEncoder().matches(
                        "newpassword", user.getPassword().substring("{bcrypt}".length())),
                "the new password should verify against the stored hash");
        verify(weblogger.userManager()).saveUser(user);
    }

    @Test
    void leavingBothPasswordFieldsEmptyKeepsTheCurrentPassword() throws Exception {
        // Saving a profile change must not be a way to blank out a password.
        User user = user("jake");
        user.setPassword(TestUtils.TEST_PASSWORD_HASH);
        ProfileBean bean = new ProfileBean();
        bean.setScreenName("Jake");

        controller.save(ControllerTestFixture.requestFor(user), model, bean, redirectAttributes);

        assertEquals(TestUtils.TEST_PASSWORD_HASH, user.getPassword());
        verify(weblogger.userManager()).saveUser(user);
    }

    @Test
    void aTimeZoneThatDoesNotExistIsRefused() throws Exception {
        User user = user("jake");
        ProfileBean bean = new ProfileBean();
        bean.setTimeZone("Mars/Phobos");

        String view = controller.save(ControllerTestFixture.requestFor(user), model, bean, redirectAttributes);

        assertEquals(".Profile", view);
        assertEquals(List.of("error.add.user.invalid.timezone"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).saveUser(any());
    }

    @Test
    void aLocaleThatDoesNotExistIsRefused() throws Exception {
        User user = user("jake");
        ProfileBean bean = new ProfileBean();
        bean.setLocale("xx_YY");

        String view = controller.save(ControllerTestFixture.requestFor(user), model, bean, redirectAttributes);

        assertEquals(".Profile", view);
        assertEquals(List.of("error.add.user.invalid.locale"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).saveUser(any());
    }

    @Test
    void realLocalesAndTimeZonesPassValidation() throws Exception {
        User user = user("jake");
        ProfileBean bean = new ProfileBean();
        bean.setLocale("en_US");
        bean.setTimeZone("America/New_York");

        controller.save(ControllerTestFixture.requestFor(user), model, bean, redirectAttributes);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        assertEquals("en_US", user.getLocale());
        assertEquals("America/New_York", user.getTimeZone());
    }

    @Test
    void anUnsetLocaleOrTimeZoneIsNotTreatedAsInvalid() throws Exception {
        // Both fields are optional; empty means "use the site default".
        User user = user("jake");
        ProfileBean bean = new ProfileBean();
        bean.setLocale("");
        bean.setTimeZone("");

        String view = controller.save(ControllerTestFixture.requestFor(user), model, bean, redirectAttributes);

        assertEquals("redirect:/roller-ui/menu.rol", view);
        assertEquals(List.of(), ControllerTestFixture.errors(model));
    }

    @Test
    void aFailedWriteKeepsTheUserOnTheFormWithoutClaimingSuccess() throws Exception {
        User user = user("jake");
        doThrow(new WebloggerException("database down")).when(weblogger.userManager()).saveUser(any());

        String view = controller.save(ControllerTestFixture.requestFor(user), model, new ProfileBean(), redirectAttributes);

        assertEquals(".Profile", view);
        assertFalse(ControllerTestFixture.errors(model).isEmpty(), "the failure has to be visible to the user");
        assertEquals(List.of(), ControllerTestFixture.flashMessages(redirectAttributes),
                "a failed save must not be announced as saved");
    }

    @Test
    void theProfilePageNeedsNoWeblogAndSuppliesItsOwnFormBean() {
        // Profile editing is not weblog-scoped; requiring one would break the
        // page for a user who has not created a blog yet.
        assertFalse(controller.isWeblogRequired());
        assertNotNull(controller.getBean());
        assertEquals("yourProfile.title", controller.getPageTitle());
    }

    private static User user(String userName) {
        User user = new User();
        user.setUserName(userName);
        user.setEnabled(Boolean.TRUE);
        return user;
    }

    // --- errors point at the field they name (B8) ---

    @Test
    void aMistypedConfirmationMarksTheConfirmationFieldRatherThanThePasswordField() throws Exception {
        // The typo is in the confirmation box by definition -- the first
        // password is whatever the user meant to type.
        User user = user("jake");
        ProfileBean bean = new ProfileBean();
        bean.setPasswordText("newpassword");
        bean.setPasswordConfirm("newpasswrod");

        controller.save(ControllerTestFixture.requestFor(user), model, bean, redirectAttributes);

        assertEquals(List.of("passwordConfirm"), ControllerTestFixture.invalidFields(model));
    }

    @Test
    void anUnknownTimeZoneMarksTheTimeZoneSelect() throws Exception {
        User user = user("jake");
        ProfileBean bean = new ProfileBean();
        bean.setTimeZone("Mars/Phobos");

        controller.save(ControllerTestFixture.requestFor(user), model, bean, redirectAttributes);

        assertEquals(List.of("timeZone"), ControllerTestFixture.invalidFields(model));
    }

    @Test
    void aBoundAccountCannotChangeItsPassword() throws Exception {
        User user = user("reader-abc1234567");
        user.setPassword(User.SSO_PASSWORD_MARKER);
        user.setSsoIssuer("https://accounts.example");
        user.setSsoSubject("sub-1");
        ProfileBean bean = new ProfileBean();
        bean.setPasswordText("newpassword");
        bean.setPasswordConfirm("newpassword");

        String view = controller.save(ControllerTestFixture.requestFor(user), model, bean, redirectAttributes);

        assertEquals(".Profile", view);
        assertEquals(List.of("userSettings.error.ssoPassword"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).saveUser(any());
        assertEquals(User.SSO_PASSWORD_MARKER, user.getPassword());

        ExtendedModelMap getModel = new ExtendedModelMap();
        controller.execute(ControllerTestFixture.requestFor(user), getModel, new ProfileBean());
        assertEquals(Boolean.TRUE, getModel.getAttribute("ssoBound"));
    }
}
