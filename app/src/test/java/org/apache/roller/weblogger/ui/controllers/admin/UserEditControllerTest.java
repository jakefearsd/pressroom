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
import java.util.Locale;
import java.util.TimeZone;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.business.comments.CommentService;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.core.RollerLoginSessionManager;
import org.apache.roller.weblogger.ui.core.RollerSession;
import org.apache.roller.testing.AdminLogCapture;
import org.apache.roller.weblogger.TestUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ExtendedModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.apache.roller.weblogger.ui.controllers.WeblogPermissionView;

/**
 * Tests for {@link UserEditController}, the only screen in Roller that can mint
 * an account or hand out the admin role. Every test here asserts on a decision
 * the controller made -- which view, which message, and above all whether the
 * user manager was actually asked to write anything -- because "the form came
 * back" is equally true when a rejected account was created anyway.
 */
class UserEditControllerTest {

    private static final String ALLOWED_CHARS = "username.allowedChars";

    private MockWeblogger weblogger;
    private CommentService comments;
    private UserEditController controller;
    private ExtendedModelMap model;
    private Object previousPasswordEncoder;
    private String previousAllowedChars;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        previousPasswordEncoder = ControllerTestFixture.installBcryptPasswordEncoder();
        previousAllowedChars = ControllerTestFixture.setConfigProperty(ALLOWED_CHARS, "A-Za-z0-9");
        comments = org.mockito.Mockito.mock(CommentService.class);
        controller = ControllerTestFixture.withMessages(new UserEditController(comments));
        model = new ExtendedModelMap();
    }

    @AfterEach
    void tearDown() {
        // The factory holds its provider statically; leaving the mock in place
        // would hand it to every test that runs after this one.
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
        ControllerTestFixture.restorePasswordEncoder(previousPasswordEncoder);
        ControllerTestFixture.restoreConfigProperty(ALLOWED_CHARS, previousAllowedChars);
    }

    @Test
    void theUserEditorIsAdminOnlyAndNeedsNoWeblog() {
        // RollerHandlerInterceptor enforces these before the handler runs, so
        // they are the access control on account creation.
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertFalse(controller.isWeblogRequired());
        assertEquals("admin", controller.getDesiredMenu());
        assertNotNull(controller.getBean(), "Spring seeds the form object from this");
    }

    // --- create: the form ---

    @Test
    void createUserFormStartsEmptyAtServerLocaleAndTimeZone() {
        HttpServletRequest request = ControllerTestFixture.requestFor(null);

        String view = controller.createUserExecute(request, model);

        assertEquals(".UserEdit", view);
        assertEquals("createUser", model.getAttribute("actionName"));
        assertEquals("userAdmin.title.createNewUser", model.getAttribute("pageTitle"));
        assertEquals("admin", model.getAttribute("desiredMenu"), "the page renders inside the admin menu");

        CreateUserBean bean = (CreateUserBean) model.getAttribute("bean");
        assertNull(bean.getUserName(), "a blank create form must not pre-fill a username");
        // Defaults save the admin from picking these on every new account.
        assertEquals(Locale.getDefault().toString(), bean.getLocale());
        assertEquals(TimeZone.getDefault().getID(), bean.getTimeZone());

        assertFalse(((List<?>) model.getAttribute("localesList")).isEmpty());
        assertFalse(((List<?>) model.getAttribute("timeZonesList")).isEmpty());
    }

    // --- create: validation ---

    @Test
    void createUserWithoutUserNameOrPasswordCreatesNothing() throws Exception {
        CreateUserBean bean = new CreateUserBean();

        String view = controller.createUserSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserEdit", view, "a rejected form must be redisplayed, not confirmed");
        assertEquals(List.of("error.add.user.missingUserName", "personForm.error.screenName",
                        "personForm.error.fullName", "personForm.error.email", "error.add.user.missingPassword"),
                ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).addUser(any());
        // The redisplayed form still has to be a working page: menu, locale and
        // time zone pickers all have to be repopulated.
        assertEquals("admin", model.getAttribute("desiredMenu"));
        assertFalse(((List<?>) model.getAttribute("localesList")).isEmpty());
        assertFalse(((List<?>) model.getAttribute("timeZonesList")).isEmpty());
    }

    @Test
    void createUserRejectsUserNameWithCharactersOutsideTheAllowedSet() throws Exception {
        // username.allowedChars is A-Za-z0-9; a space or punctuation must not
        // reach the database, where the name becomes part of URLs and logins.
        CreateUserBean bean = beanFor("jake fear", "secret-password");

        String view = controller.createUserSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserEdit", view);
        assertEquals(List.of("error.add.user.badUserName"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    @Test
    void theAllowedCharacterSetIsWhateverTheDeploymentConfigured() throws Exception {
        // A site that wants underscores in usernames says so in
        // username.allowedChars, and the controller has to honour that rather
        // than its own built-in default.
        ControllerTestFixture.setConfigProperty(ALLOWED_CHARS, "A-Za-z0-9_");

        controller.createUserSave(ControllerTestFixture.requestFor(null), model, beanFor("jake_fear", "secret-password"));

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        verify(weblogger.userManager()).addUser(any());
    }

    @Test
    void withNoConfiguredCharacterSetTheBuiltInDefaultApplies() throws Exception {
        // Deleting the property must not accidentally allow everything.
        ControllerTestFixture.setConfigProperty(ALLOWED_CHARS, null);

        controller.createUserSave(ControllerTestFixture.requestFor(null), model, beanFor("jake_fear", "secret-password"));

        assertEquals(List.of("error.add.user.badUserName"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    @Test
    void anEmptyConfiguredCharacterSetFallsBackToTheBuiltInDefault() throws Exception {
        ControllerTestFixture.setConfigProperty(ALLOWED_CHARS, "   ");

        controller.createUserSave(ControllerTestFixture.requestFor(null), model, beanFor("jake_fear", "secret-password"));

        assertEquals(List.of("error.add.user.badUserName"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    @Test
    void createUserWithoutPasswordCreatesNothing() throws Exception {
        CreateUserBean bean = beanFor("newbie", null);

        String view = controller.createUserSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserEdit", view);
        assertEquals(List.of("error.add.user.missingPassword"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    // --- create: the happy path ---

    @Test
    void createUserStoresTheProfileAndNeverStoresTheRawPassword() throws Exception {
        CreateUserBean bean = beanFor("newbie", "secret-password");
        bean.setScreenName("New Bie");
        bean.setFullName("New B. Ie");
        bean.setEmailAddress("newbie@example.com");
        bean.setLocale("en_US");
        bean.setTimeZone("America/New_York");
        bean.setEnabled(Boolean.TRUE);

        String view = controller.createUserSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserAdmin", view);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(weblogger.userManager()).addUser(saved.capture());
        User user = saved.getValue();
        assertEquals("newbie", user.getUserName());
        assertEquals("New Bie", user.getScreenName());
        assertEquals("New B. Ie", user.getFullName());
        assertEquals("newbie@example.com", user.getEmailAddress());
        assertEquals("en_US", user.getLocale());
        assertEquals("America/New_York", user.getTimeZone());
        assertEquals(Boolean.TRUE, user.getEnabled());
        assertNotNull(user.getDateCreated(), "creation date is set by the controller, not the form");
        // Encoded by the configured PasswordEncoder -- the plaintext must not survive.
        assertTrue(new BCryptPasswordEncoder().matches(
                        "secret-password", user.getPassword().substring("{bcrypt}".length())),
                "the submitted password should verify against the stored hash");

        verify(weblogger.weblogger()).flush();
        assertEquals(List.of("createUser.add.success[newbie]"), ControllerTestFixture.messages(model));
        assertNull(((CreateUserBean) model.getAttribute("bean")).getUserName(),
                "the form is cleared after a save so a refresh cannot create the user twice");
    }

    @Test
    void createUserGrantsTheAdminRoleOnlyWhenTheBoxIsTicked() throws Exception {
        controller.createUserSave(ControllerTestFixture.requestFor(null), model, beanFor("plain", "secret-password"));
        verify(weblogger.userManager(), never()).grantRole(eq("admin"), any());

        CreateUserBean bean = beanFor("boss", "secret-password");
        bean.setAdministrator(true);
        ExtendedModelMap adminModel = new ExtendedModelMap();

        controller.createUserSave(ControllerTestFixture.requestFor(null), adminModel, bean);

        verify(weblogger.userManager()).grantRole(eq("admin"), any(User.class));
    }

    @Test
    void createUserKeepsTheFormWhenTheUserManagerRejectsTheAccount() throws Exception {
        // The manager throws this for a duplicate username, among other things.
        doThrow(new WebloggerException("error.add.user.userNameInUse"))
                .when(weblogger.userManager()).addUser(any());

        String view = controller.createUserSave(
                ControllerTestFixture.requestFor(null), model, beanFor("taken", "secret-password"));

        assertEquals(".UserEdit", view, "the admin must land back on the form, not on a success page");
        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
        assertEquals(List.of(), ControllerTestFixture.messages(model), "a failed create must not be announced");
        verify(weblogger.weblogger(), never()).flush();
    }

    @Test
    void cancellingCreateReturnsToTheUserList() {
        assertEquals("redirect:/roller-ui/admin/userAdmin.rol", controller.createUserCancel());
    }

    // --- modify: loading the form ---

    @Test
    void modifyUnknownUserReportsItInsteadOfShowingAnEmptyEditForm() throws Exception {
        CreateUserBean bean = new CreateUserBean();
        bean.setId("does-not-exist");
        when(weblogger.userManager().getUser("does-not-exist")).thenReturn(null);

        String view = controller.modifyUserExecute(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserAdmin", view);
        assertEquals(List.of("userAdmin.error.userNotFound"), ControllerTestFixture.errors(model));
        assertFalse(((List<?>) model.getAttribute("localesList")).isEmpty(),
                "the user-admin page is rendered from this model and still needs its pickers");
    }

    @Test
    void modifyLoadsTheStoredProfileAndItsWeblogPermissions() throws Exception {
        User stored = user("jake", "jake@example.com");
        stored.setScreenName("Jake");
        stored.setLocale("fr_FR");
        when(weblogger.userManager().getUser("u1")).thenReturn(stored);
        List<WeblogPermission> permissions = List.of();
        when(weblogger.userManager().getWeblogPermissions(stored)).thenReturn(permissions);
        // copyFrom asks the manager whether this user is an admin.
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(stored))).thenReturn(true);

        CreateUserBean bean = new CreateUserBean();
        bean.setId("u1");
        String view = controller.modifyUserExecute(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserEdit", view);
        assertEquals("userAdmin.title.editUser", model.getAttribute("pageTitle"));
        CreateUserBean loaded = (CreateUserBean) model.getAttribute("bean");
        assertEquals("jake", loaded.getUserName());
        assertEquals("Jake", loaded.getScreenName());
        assertEquals("fr_FR", loaded.getLocale());
        assertTrue(loaded.isAdministrator(), "the admin checkbox must reflect the role the user really holds");
        assertEquals(permissions, permissionsOf(model.getAttribute("permissions")));
        assertEquals("admin", model.getAttribute("desiredMenu"));
        assertFalse(((List<?>) model.getAttribute("localesList")).isEmpty());
        assertFalse(((List<?>) model.getAttribute("timeZonesList")).isEmpty());
    }

    @Test
    void aFailedPermissionLookupShowsTheProfileWithNoPermissionsRatherThanNoPage() throws Exception {
        User stored = user("jake", "jake@example.com");
        when(weblogger.userManager().getUser("u1")).thenReturn(stored);
        when(weblogger.userManager().getWeblogPermissions(stored))
                .thenThrow(new WebloggerException("database down"));
        CreateUserBean bean = new CreateUserBean();
        bean.setId("u1");

        String view = controller.modifyUserExecute(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserEdit", view);
        assertTrue(((List<?>) model.getAttribute("permissions")).isEmpty());
    }

    /**
     * Characterisation: the admin checkbox is decided by the controller now,
     * not by {@code CreateUserBean.copyFrom}, and it keeps the semantics the
     * bean had -- a role lookup that fails leaves the box unticked and the rest
     * of the profile loaded. Failing open here would offer to make somebody an
     * admin on the strength of a database error. Expected to pass on arrival;
     * it exists so moving the check out of the bean could not change it.
     */
    @Test
    void modifyLeavesTheAdminBoxUntickedWhenTheRoleLookupFails() throws Exception {
        User stored = user("jake", "jake@example.com");
        when(weblogger.userManager().getUser("u1")).thenReturn(stored);
        when(weblogger.userManager().getWeblogPermissions(stored)).thenReturn(List.of());
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(stored)))
                .thenThrow(new WebloggerException("database down"));
        CreateUserBean bean = new CreateUserBean();
        bean.setId("u1");

        String view = controller.modifyUserExecute(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserEdit", view);
        CreateUserBean loaded = (CreateUserBean) model.getAttribute("bean");
        assertFalse(loaded.isAdministrator(), "a failed role lookup must not tick the admin box");
        assertEquals("jake", loaded.getUserName(), "the rest of the profile is still loaded");
    }

    @Test
    void modifyFallsBackToUserNameLookupWhenTheFormCarriesNoId() throws Exception {
        CreateUserBean bean = new CreateUserBean();
        bean.setUserName("jake");
        when(weblogger.userManager().getUserByUserName("jake", null)).thenReturn(user("jake", "jake@example.com"));

        String view = controller.modifyUserExecute(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserEdit", view);
        verify(weblogger.userManager()).getUserByUserName("jake", null);
        verify(weblogger.userManager(), never()).getUser(any());
    }

    @Test
    void modifySurvivesALookupFailureByTreatingTheUserAsMissing() throws Exception {
        CreateUserBean bean = new CreateUserBean();
        bean.setId("u1");
        when(weblogger.userManager().getUser("u1")).thenThrow(new WebloggerException("database down"));

        String view = controller.modifyUserExecute(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserAdmin", view);
        assertEquals(List.of("userAdmin.error.userNotFound"), ControllerTestFixture.errors(model));
    }

    @Test
    void firstSaveOfANewUserConfirmsTheCreationOnTheEditForm() throws Exception {
        when(weblogger.userManager().getUserByUserName("newbie", null))
                .thenReturn(user("newbie", "newbie@example.com"));
        CreateUserBean bean = new CreateUserBean();
        bean.setUserName("newbie");

        String view = controller.modifyUserFirstSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserEdit", view);
        assertEquals(List.of("createUser.add.success[newbie]"), ControllerTestFixture.messages(model));
    }

    @Test
    void firstSaveEscapesTheUserNameItEchoesBack() throws Exception {
        // modifyUser!firstSave.rol is a GET, and it echoes bean.userName --
        // straight off the query string, through NO validation of its own --
        // into a message that messages.jsp renders unescaped. Unlike the
        // createUser path, where myValidate has already restricted the name to
        // username.allowedChars, nothing stands between the request and the
        // page here.
        when(weblogger.userManager().getUserByUserName("newbie", null))
                .thenReturn(user("newbie", "newbie@example.com"));
        CreateUserBean bean = new CreateUserBean();
        bean.setUserName("<img src=x onerror=alert(1)>");

        controller.modifyUserFirstSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(List.of("createUser.add.success[&lt;img src=x onerror=alert(1)&gt;]"),
                ControllerTestFixture.messages(model),
                "a reflected user name must be HTML-escaped before it reaches the banner");
    }

    // --- modify: saving ---    // --- modify: saving ---

    @Test
    void modifySaveWritesTheProfileAndAnnouncesIt() throws Exception {
        User stored = storedUser("jake");
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setScreenName("Jake Renamed");
        bean.setEmailAddress("new@example.com");

        String view = controller.modifyUserSave(ControllerTestFixture.requestFor(stored), model, bean);

        assertEquals(".UserAdmin", view);
        verify(weblogger.userManager()).saveUser(stored);
        verify(weblogger.weblogger()).flush();
        assertEquals("Jake Renamed", stored.getScreenName());
        assertEquals("new@example.com", stored.getEmailAddress());
        assertEquals(List.of("userAdmin.userSaved"), ControllerTestFixture.messages(model));
        assertNull(((CreateUserBean) model.getAttribute("bean")).getUserName(),
                "the form is cleared so the admin lands on a clean user list");
    }

    @Test
    void modifySaveOfAMissingUserWritesNothing() throws Exception {
        when(weblogger.userManager().getUser("gone")).thenReturn(null);
        CreateUserBean bean = new CreateUserBean();
        bean.setId("gone");

        String view = controller.modifyUserSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserAdmin", view);
        assertEquals(List.of("userAdmin.error.userNotFound"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).saveUser(any());
    }

    @Test
    void modifySaveRejectsAUserRecordWithNoUserName() throws Exception {
        // A row without a username cannot be edited safely: the save path keys
        // session invalidation and role changes off the name.
        User nameless = new User();
        when(weblogger.userManager().getUser("u1")).thenReturn(nameless);
        CreateUserBean bean = new CreateUserBean();
        bean.setId("u1");

        String view = controller.modifyUserSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserEdit", view);
        assertEquals(List.of("userAdmin.error.userNotFound"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).saveUser(any());
    }

    @Test
    void changingSomeoneElsesPasswordEncodesItAndKicksThemOut() throws Exception {
        User stored = storedUser("jake");
        RollerSession jakesSession = registerSessionFor("jake");
        assertSame(jakesSession, RollerLoginSessionManager.getInstance().get("jake"),
                "precondition: jake is signed in when the admin edits him");
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setPassword("newpass");
        // Left enabled on purpose: the new password alone has to end the
        // session, without help from the account being disabled too.
        bean.setEnabled(Boolean.TRUE);

        controller.modifyUserSave(ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

        assertTrue(new BCryptPasswordEncoder().matches(
                        "newpass", stored.getPassword().substring("{bcrypt}".length())),
                "the updated password should verify against the stored hash");
        assertNull(RollerLoginSessionManager.getInstance().get("jake"),
                "whoever is logged in with the old password must be forced to re-authenticate");
    }

    @Test
    void changingYourOwnPasswordDoesNotLogYouOut() throws Exception {
        User self = storedUser("jake");
        RollerSession ownSession = registerSessionFor("jake");
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setPassword("newpass");
        bean.setEnabled(Boolean.TRUE);

        try {
            controller.modifyUserSave(ControllerTestFixture.requestFor(self), model, bean);

            assertSame(ownSession, RollerLoginSessionManager.getInstance().get("jake"),
                    "an admin changing their own password should stay signed in");
        } finally {
            RollerLoginSessionManager.getInstance().invalidate("jake");
        }
    }

    @Test
    void disablingAUserEndsTheirSession() throws Exception {
        storedUser("jake");
        registerSessionFor("jake");
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setEnabled(Boolean.FALSE);

        controller.modifyUserSave(ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

        assertNull(RollerLoginSessionManager.getInstance().get("jake"),
                "a disabled account must not keep browsing on an existing session");
    }

    @Test
    void leavingAUserEnabledLeavesTheirSessionAlone() throws Exception {
        storedUser("jake");
        RollerSession session = registerSessionFor("jake");
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setEnabled(Boolean.TRUE);

        try {
            controller.modifyUserSave(ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

            assertSame(session, RollerLoginSessionManager.getInstance().get("jake"));
        } finally {
            RollerLoginSessionManager.getInstance().invalidate("jake");
        }
    }

    // --- modify: the admin role ---

    @Test
    void untickingAdministratorRevokesTheRole() throws Exception {
        User stored = storedUser("jake");
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(stored))).thenReturn(true);
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setAdministrator(false);
        bean.setEnabled(Boolean.TRUE);

        controller.modifyUserSave(ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

        verify(weblogger.userManager()).revokeRole("admin", stored);
        assertEquals(List.of(), ControllerTestFixture.errors(model));
    }

    @Test
    void anAdminCannotRevokeTheirOwnAdminRole() throws Exception {
        // Otherwise the last admin can lock the whole installation out of its
        // own admin screens.
        User self = storedUser("jake");
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(self))).thenReturn(true);
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setAdministrator(false);
        bean.setEnabled(Boolean.TRUE);

        controller.modifyUserSave(ControllerTestFixture.requestFor(self), model, bean);

        verify(weblogger.userManager(), never()).revokeRole(eq("admin"), any());
        assertEquals(List.of("userAdmin.cantChangeOwnRole"), ControllerTestFixture.errors(model));
    }

    @Test
    void tickingAdministratorGrantsTheRole() throws Exception {
        User stored = storedUser("jake");
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(stored))).thenReturn(false);
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setAdministrator(true);
        bean.setEnabled(Boolean.TRUE);

        controller.modifyUserSave(ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

        verify(weblogger.userManager()).grantRole("admin", stored);
    }

    // --- modify: a rename expires the pages showing the account's comments (M8) ---

    @Test
    void renamingAnAccountExpiresTheWeblogsShowingItsComments() throws Exception {
        User stored = storedUser("jake");
        stored.setScreenName("Jake");
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setScreenName("Jake Renamed");
        bean.setEnabled(Boolean.TRUE);

        controller.modifyUserSave(ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(weblogger.weblogger(), comments);
        order.verify(weblogger.weblogger()).flush();
        order.verify(comments).expireWhereShown(stored);
    }

    @Test
    void renamingABoundReaderExpiresTheWeblogsShowingItsComments() throws Exception {
        User stored = storedBoundUser("reader-abc1234567");
        stored.setScreenName("Cora");
        CreateUserBean bean = beanForExisting("u1", "reader-abc1234567");
        bean.setScreenName("Cora Renamed");
        bean.setEnabled(Boolean.TRUE);

        controller.modifyUserSave(ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

        assertEquals(List.of(), ControllerTestFixture.errors(model));
        verify(comments).expireWhereShown(stored);
    }

    @Test
    void anEditThatKeepsTheNameExpiresNothing() throws Exception {
        User stored = storedUser("jake");
        stored.setScreenName("Jake");
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setScreenName("Jake");
        bean.setEmailAddress("new@example.com");
        bean.setEnabled(Boolean.TRUE);

        controller.modifyUserSave(ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

        verify(weblogger.userManager()).saveUser(stored);
        verify(comments, never()).expireWhereShown(any());
    }

    @Test
    void anAlreadyAdminUserIsNotGrantedTheRoleAgain() throws Exception {
        User stored = storedUser("jake");
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(stored))).thenReturn(true);
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setAdministrator(true);
        bean.setEnabled(Boolean.TRUE);

        controller.modifyUserSave(ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

        verify(weblogger.userManager(), never()).grantRole(eq("admin"), any());
        verify(weblogger.userManager(), never()).revokeRole(eq("admin"), any());
    }

    @Test
    void modifySaveKeepsTheFormWhenTheWriteFails() throws Exception {
        User stored = storedUser("jake");
        doThrow(new WebloggerException("database down")).when(weblogger.userManager()).saveUser(stored);

        String view = controller.modifyUserSave(
                ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, beanForExisting("u1", "jake"));

        assertEquals(".UserEdit", view);
        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
        assertEquals(List.of(), ControllerTestFixture.messages(model));
        assertEquals("admin", model.getAttribute("desiredMenu"));
        assertFalse(((List<?>) model.getAttribute("localesList")).isEmpty(),
                "the edit form is redisplayed and still needs its pickers");
    }

    @Test
    void cancellingModifyReturnsToTheUserList() {
        assertEquals("redirect:/roller-ui/admin/userAdmin.rol", controller.modifyUserCancel());
    }

    // --- create a person (spec 2026-10-13, section 4) ---

    /** Criterion 16: required on the server too, each against its own field. */
    @Test
    void createUserRefusesAMissingNameFullNameOrEmailServerSide() throws Exception {
        CreateUserBean bean = beanFor("newbie", "long-enough");
        bean.setScreenName("  ");
        bean.setFullName(null);
        bean.setEmailAddress("");

        String view = controller.createUserSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(".UserEdit", view);
        assertEquals(List.of("personForm.error.screenName", "personForm.error.fullName", "personForm.error.email"),
                ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_screenName", "bean_fullName", "bean_email"),
                ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    /** Criterion 16: an email the server cannot accept is refused against the email field. */
    @Test
    void createUserRefusesAnInvalidEmail() throws Exception {
        CreateUserBean bean = beanFor("newbie", "long-enough");
        bean.setEmailAddress("not-an-address");

        controller.createUserSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(List.of("personForm.error.email"), ControllerTestFixture.errors(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    /**
     * An address ending {@code .invalid} is refused (it is not an IANA TLD),
     * so the browser ITs type {@code @example.com}; nobody should "fix" them
     * the other way.
     */
    @Test
    void anAddressEndingDotInvalidIsRefusedOnTheEmailField() throws Exception {
        CreateUserBean bean = beanFor("newbie", "long-enough");
        bean.setEmailAddress("alice@example.invalid");

        controller.createUserSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(List.of("personForm.error.email"), ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_email"), ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    /** Criterion 16: "Set a password now" needs at least 8 characters. */
    @Test
    void aPasswordShorterThanEightCharactersIsRefused() throws Exception {
        controller.createUserSave(ControllerTestFixture.requestFor(null), model, beanFor("newbie", "seven77"));

        assertEquals(List.of("personForm.error.passwordTooShort"), ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_password"), ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    /** Criterion 3's rule holds for a person too: a taken username is a field error on the username. */
    @Test
    void aTakenUserNameIsAFieldErrorOnTheUserName() throws Exception {
        when(weblogger.userManager().getUserByUserName("taken", null)).thenReturn(user("taken", "t@example.com"));

        controller.createUserSave(ControllerTestFixture.requestFor(null), model, beanFor("taken", "long-enough"));

        assertEquals(List.of("error.add.user.userNameInUse"), ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_userName"), ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    /** A check that could not run is not a check that passed (CLAUDE.md): a failed taken-check refuses. */
    @Test
    void aUserNameCheckThatCannotRunRefusesTheAccount() throws Exception {
        when(weblogger.userManager().getUserByUserName("newbie", null)).thenThrow(new WebloggerException("db down"));

        controller.createUserSave(ControllerTestFixture.requestFor(null), model, beanFor("newbie", "long-enough"));

        assertEquals(List.of("generic.error.check.logs"), ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_userName"), ControllerTestFixture.invalidFields(model));
        verify(weblogger.userManager(), never()).addUser(any());
    }

    /** Criterion 16: the form starts enabled as an Author, and a default save makes exactly that. */
    @Test
    void aNewPersonIsEnabledByDefaultAsAnAuthor() throws Exception {
        controller.createUserExecute(ControllerTestFixture.requestFor(null), model);
        CreateUserBean form = (CreateUserBean) model.getAttribute("bean");
        assertEquals(Boolean.TRUE, form.getEnabled());
        assertFalse(form.isAdministrator());

        controller.createUserSave(ControllerTestFixture.requestFor(null), new ExtendedModelMap(),
                beanFor("author", "long-enough"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(weblogger.userManager()).addUser(saved.capture());
        assertEquals(Boolean.TRUE, saved.getValue().getEnabled());
        verify(weblogger.userManager(), never()).grantRole(eq("admin"), any());
    }

    /** Spec 2026-10-13, section 6: the person editor's Enabled box and admin role are roller.admin events. */
    @Test
    void disablingAPersonAndGrantingAdminAreLogged() throws Exception {
        storedUser("jake");
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setEnabled(Boolean.FALSE);
        bean.setAdministrator(true);

        try (AdminLogCapture log = AdminLogCapture.start()) {
            controller.modifyUserSave(ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

            assertEquals(List.of("account.disabled account=jake by=admin", "siteAdmin.granted account=jake by=admin"),
                    log.lines());
        }
    }

    @Test
    void aSaveThatChangesNeitherLogsNothing() throws Exception {
        storedUser("jake");
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setEnabled(Boolean.TRUE);

        try (AdminLogCapture log = AdminLogCapture.start()) {
            controller.modifyUserSave(ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

            assertEquals(List.of(), log.lines());
        }
    }

    // --- helpers ---

    private static CreateUserBean beanFor(String userName, String password) {
        CreateUserBean bean = new CreateUserBean();
        bean.setUserName(userName);
        bean.setPassword(password);
        // Spec 2026-10-13, section 4: name, full name and a valid email are
        // required on the server, so a bean meant to pass carries them.
        bean.setScreenName("Screen " + userName);
        bean.setFullName("Full " + userName);
        bean.setEmailAddress("person@example.com");
        bean.setPasswordMode(CreateUserBean.PASSWORD_NOW);
        bean.setEnabled(Boolean.TRUE);
        return bean;
    }

    private static CreateUserBean beanForExisting(String id, String userName) {
        CreateUserBean bean = new CreateUserBean();
        bean.setId(id);
        bean.setUserName(userName);
        return bean;
    }

    private static User user(String userName, String email) {
        User user = new User();
        user.setUserName(userName);
        user.setEmailAddress(email);
        user.setEnabled(Boolean.TRUE);
        return user;
    }

    /** A user the manager will return for id {@code u1}. */
    private User storedUser(String userName) throws WebloggerException {
        User stored = user(userName, userName + "@example.com");
        when(weblogger.userManager().getUser("u1")).thenReturn(stored);
        return stored;
    }

    private static RollerSession registerSessionFor(String userName) {
        RollerSession session = org.mockito.Mockito.mock(RollerSession.class);
        RollerLoginSessionManager.getInstance().register(userName, session);
        return session;
    }

    /** The permissions behind the {@code WeblogPermissionView} rows a controller now hands the JSP. */
    private static List<WeblogPermission> permissionsOf(Object rows) {
        return ((List<?>) rows).stream()
                .map(row -> ((WeblogPermissionView) row).getPermission())
                .toList();
    }

    // --- errors point at the field they name (B8) ---

    @Test
    void aRefusedUserNameAndPasswordEachMarkTheirOwnField() throws Exception {
        CreateUserBean bean = new CreateUserBean();

        controller.createUserSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(List.of("bean_userName", "bean_screenName", "bean_fullName", "bean_email", "bean_password"),
                ControllerTestFixture.invalidFields(model));
    }

    @Test
    void aUserNameWithForbiddenCharactersMarksOnlyTheUserNameField() throws Exception {
        CreateUserBean bean = beanFor("jake fear", "secret-password");

        controller.createUserSave(ControllerTestFixture.requestFor(null), model, bean);

        assertEquals(List.of("bean_userName"), ControllerTestFixture.invalidFields(model));
    }

    private User storedBoundUser(String userName) throws WebloggerException {
        User stored = storedUser(userName);
        stored.setPassword(User.SSO_PASSWORD_MARKER);
        stored.setSsoIssuer("https://accounts.example");
        stored.setSsoSubject("sub-1");
        return stored;
    }

    @Test
    void savingAPasswordForABoundAccountIsRefused() throws Exception {
        User stored = storedBoundUser("reader-abc1234567");
        CreateUserBean bean = beanForExisting("u1", "reader-abc1234567");
        bean.setPassword("newpass");

        String view = controller.modifyUserSave(
                ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

        assertEquals(".UserEdit", view);
        assertEquals(List.of("userAdmin.error.ssoPassword"), ControllerTestFixture.errors(model));
        assertEquals(Boolean.TRUE, model.getAttribute("ssoBound"));
        verify(weblogger.userManager(), never()).saveUser(any());
        assertEquals(User.SSO_PASSWORD_MARKER, stored.getPassword());
    }

    @Test
    void sendPasswordLinkIsRefusedForABoundAccount() throws Exception {
        User stored = storedBoundUser("reader-abc1234567");
        when(weblogger.userManager().getUserByUserName("reader-abc1234567", null)).thenReturn(stored);

        String view = controller.sendPasswordLink(
                ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, "reader-abc1234567");

        assertEquals(".UserEdit", view);
        assertEquals(List.of("userAdmin.error.ssoPassword"), ControllerTestFixture.errors(model));
        assertEquals(Boolean.TRUE, model.getAttribute("ssoBound"));
        verify(weblogger.getUserTokenManager(), never()).issueToken(any(), any());
    }

    @Test
    void modifyUserExposesSsoBound() throws Exception {
        storedBoundUser("reader-abc1234567");
        CreateUserBean bean = new CreateUserBean();
        bean.setId("u1");

        controller.modifyUserExecute(ControllerTestFixture.requestFor(null), model, bean);
        assertEquals(Boolean.TRUE, model.getAttribute("ssoBound"));

        ExtendedModelMap localModel = new ExtendedModelMap();
        storedUser("jake");
        CreateUserBean localBean = new CreateUserBean();
        localBean.setId("u1");
        controller.modifyUserExecute(ControllerTestFixture.requestFor(null), localModel, localBean);
        assertEquals(Boolean.FALSE, localModel.getAttribute("ssoBound"));
    }

    @Test
    void aBoundAccountCannotBeMadeAdmin() throws Exception {
        User stored = storedBoundUser("reader-abc1234567");
        CreateUserBean bean = beanForExisting("u1", "reader-abc1234567");
        bean.setAdministrator(true);

        String view = controller.modifyUserSave(
                ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

        assertEquals(".UserEdit", view);
        assertEquals(List.of("userAdmin.error.ssoAdmin"), ControllerTestFixture.errors(model));
        assertEquals(Boolean.TRUE, model.getAttribute("ssoBound"));
        verify(weblogger.userManager(), never()).grantRole(eq("admin"), any());
        verify(weblogger.userManager(), never()).saveUser(any());
        assertEquals(User.SSO_PASSWORD_MARKER, stored.getPassword());
    }

    @Test
    void canWriteGrantsAndRevokesEditorOnABoundAccount() throws Exception {
        User stored = storedBoundUser("reader-abc1234567");
        CreateUserBean grant = beanForExisting("u1", "reader-abc1234567");
        grant.setWriter(true);

        controller.modifyUserSave(
                ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, grant);

        verify(weblogger.userManager()).grantRole("editor", stored);
        verify(weblogger.userManager(), never()).grantRole(eq("admin"), any());

        when(weblogger.userManager().hasRole("editor", stored)).thenReturn(true);
        CreateUserBean revoke = beanForExisting("u1", "reader-abc1234567");
        revoke.setWriter(false);

        controller.modifyUserSave(
                ControllerTestFixture.requestFor(user("admin", "a@example.com")), new ExtendedModelMap(), revoke);

        verify(weblogger.userManager()).revokeRole("editor", stored);
        verify(weblogger.userManager(), never()).revokeRole(eq("admin"), any());
    }

    @Test
    void canWriteIsIgnoredForLocalAccounts() throws Exception {
        User stored = storedUser("jake");
        when(weblogger.userManager().hasRole("editor", stored)).thenReturn(true);
        CreateUserBean bean = beanForExisting("u1", "jake");
        bean.setWriter(false);

        controller.modifyUserSave(
                ControllerTestFixture.requestFor(user("admin", "a@example.com")), model, bean);

        verify(weblogger.userManager(), never()).revokeRole(eq("editor"), any());
        verify(weblogger.userManager(), never()).grantRole(eq("editor"), any());
    }

    @Test
    void modifyUserPrefillsWriterFromTheEditorRole() throws Exception {
        User stored = storedBoundUser("reader-abc1234567");
        when(weblogger.userManager().hasRole("editor", stored)).thenReturn(true);
        CreateUserBean bean = new CreateUserBean();
        bean.setId("u1");

        controller.modifyUserExecute(ControllerTestFixture.requestFor(null), model, bean);

        assertTrue(((CreateUserBean) model.getAttribute("bean")).isWriter());
    }

    /** Task 10: a save lands on the Users list, so it must carry the list's rows. */
    @Test
    void aSuccessfulCreateLandsOnAUsersListWithItsRows() throws Exception {
        controller.createUserSave(ControllerTestFixture.requestFor(null), model, beanFor("listed", "secret-password"));

        assertEquals(List.of(), model.getAttribute("accounts"));
        assertEquals("", model.getAttribute("kindFilter"));
    }
}
