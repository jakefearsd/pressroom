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
package org.apache.roller.weblogger.ui.controllers.core;

import java.util.List;

import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.business.startup.MockMailProvider;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.UserToken;
import org.apache.roller.weblogger.ui.core.RollerLoginSessionManager;
import org.apache.roller.weblogger.ui.core.RollerSession;
import org.apache.roller.weblogger.TestUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link PasswordResetController}: the enumeration-proof
 * forgot-password flow and the tokened reset-password flow.
 */
class PasswordResetControllerTest {

    private MockWeblogger mocks;
    private MockMailProvider mail;
    private PasswordResetController controller;
    private ExtendedModelMap model;
    private Object previousPasswordEncoder;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(mocks.weblogger());
        mail = MockMailProvider.install();
        previousPasswordEncoder = ControllerTestFixture.installBcryptPasswordEncoder();
        controller = ControllerTestFixture.withMessages(new PasswordResetController());
        model = new ExtendedModelMap();

        // resetMailReady() needs both a transport (MockMailProvider, above)
        // and a non-blank site.adminemail; give every test a sane default so
        // individual tests can override it to exercise the "unready" branch.
        when(mocks.getPropertiesManager().getProperty("site.adminemail"))
                .thenReturn(new RuntimeConfigProperty("site.adminemail", "admin@example.invalid"));

        // The controller hands the lookup-issue-mail sequence to
        // ThreadManager.executeInBackground; run it inline by default so
        // most tests can assert on its effects synchronously. Tests
        // exercising the deferral itself override this per-test.
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mocks.getThreadManager()).executeInBackground(any());
    }

    @AfterEach
    void tearDown() {
        ControllerTestFixture.restorePasswordEncoder(previousPasswordEncoder);
        MockMailProvider.uninstall();
        mocks.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    // ------------------------------------------------------------- forgot

    @Test
    void aKnownUsernameIssuesATokenAndAttemptsEmail() throws Exception {
        User user = user("dana", "dana@example.invalid");
        when(mocks.getUserManager().getUserByUserName("dana", Boolean.TRUE)).thenReturn(user);
        when(mocks.getUserTokenManager().issueToken(user, UserToken.Purpose.PASSWORD_RESET))
                .thenReturn("raw-token-123");

        String view = controller.forgotSend(request(), model, "dana");

        assertEquals(".ForgotPassword", view);
        assertEquals(List.of("forgotPassword.confirmation"), ControllerTestFixture.messages(model));
        verify(mocks.getUserTokenManager()).issueToken(user, UserToken.Purpose.PASSWORD_RESET);
        verify(mocks.weblogger()).release();

        jakarta.mail.internet.MimeMessage sent = mail.onlyMessage();
        assertEquals("dana@example.invalid", sent.getAllRecipients()[0].toString());
        assertTrue(sent.getContent().toString().contains("raw-token-123"),
                "the email must carry the raw token in the reset URL");
    }

    @Test
    void anUnknownUsernameOrEmailGetsTheIdenticalMessageAndNoTokenOrEmail() throws Exception {
        // Neither lookup finds anyone -- unstubbed mocks return null, as the
        // real manager would for an identifier that names nobody.
        String view = controller.forgotSend(request(), model, "nobody");

        assertEquals(".ForgotPassword", view);
        assertEquals(List.of("forgotPassword.confirmation"), ControllerTestFixture.messages(model));
        verify(mocks.getUserTokenManager(), never()).issueToken(any(), any());
        assertTrue(mail.sent().isEmpty());
        // Even the "nobody found" path runs the lookup on the worker thread
        // (this test's default stub runs the background runnable inline) and
        // must still release the EntityManager/connection it opened doing so.
        verify(mocks.weblogger()).release();
    }

    /**
     * The regression this pins: the background runnable does JPA work
     * ({@code issueToken}, {@code weblogger.flush()}) on a pooled thread that
     * never passes through {@code PersistenceSessionFilter}, so nothing else
     * releases that thread's EntityManager/connection.
     * {@code issueAndMailBestEffort} must release it itself, in a
     * {@code finally}, on the worker thread -- exactly like
     * {@code AddEntryOperation} does for the same reason.
     */
    @Test
    void theBackgroundWorkReleasesItsOwnWeblogSessionOnTheWorkerThread() throws Exception {
        User user = user("dana", "dana@example.invalid");
        when(mocks.getUserManager().getUserByUserName("dana", Boolean.TRUE)).thenReturn(user);

        controller.forgotSend(request(), model, "dana");

        verify(mocks.weblogger()).release();
    }

    @Test
    void theKnownAndUnknownIdentifierResponsesAreWordForWordIdentical() throws Exception {
        User user = user("dana", "dana@example.invalid");
        when(mocks.getUserManager().getUserByUserName("dana", Boolean.TRUE)).thenReturn(user);
        when(mocks.getUserTokenManager().issueToken(user, UserToken.Purpose.PASSWORD_RESET))
                .thenReturn("raw-token-123");

        ExtendedModelMap knownModel = new ExtendedModelMap();
        controller.forgotSend(request(), knownModel, "dana");

        ExtendedModelMap unknownModel = new ExtendedModelMap();
        controller.forgotSend(request(), unknownModel, "nobody-at-all");

        assertEquals(ControllerTestFixture.messages(knownModel), ControllerTestFixture.messages(unknownModel),
                "an enumeration attack must not be able to tell an existing account from an unknown one");
    }

    @Test
    void aDisabledUserGetsTheIdenticalGenericConfirmationAndNoToken() throws Exception {
        // getUserByUserName(identifier, TRUE) is how the real manager filters
        // to enabled accounts -- for a disabled user it returns null, exactly
        // like an unknown one.
        when(mocks.getUserManager().getUserByUserName("dana", Boolean.TRUE)).thenReturn(null);
        when(mocks.getUserManager().getUserByEmail("dana")).thenReturn(null);

        String view = controller.forgotSend(request(), model, "dana");

        assertEquals(".ForgotPassword", view);
        assertEquals(List.of("forgotPassword.confirmation"), ControllerTestFixture.messages(model));
        verify(mocks.getUserTokenManager(), never()).issueToken(any(), any());
        assertTrue(mail.sent().isEmpty());
    }

    @Test
    void aBlankIdentifierGetsTheIdenticalConfirmationAndIssuesNoToken() throws Exception {
        String view = controller.forgotSend(request(), model, "");

        assertEquals(".ForgotPassword", view);
        assertEquals(List.of("forgotPassword.confirmation"), ControllerTestFixture.messages(model));
        verify(mocks.getUserManager(), never()).getUserByUserName(any(), any());
        verify(mocks.getUserTokenManager(), never()).issueToken(any(), any());
    }

    /**
     * A failure inside the background lookup-issue-mail sequence (this test's
     * default stub runs it inline) must be logged and swallowed, not thrown
     * -- the response was already decided before this work ever started.
     */
    @Test
    void aFailureInTheBackgroundWorkIsSwallowedAndDoesNotAffectTheResponse() throws Exception {
        User user = user("dana", "dana@example.invalid");
        when(mocks.getUserManager().getUserByUserName("dana", Boolean.TRUE)).thenReturn(user);
        when(mocks.getUserTokenManager().issueToken(any(), any()))
                .thenThrow(new org.apache.roller.weblogger.WebloggerException("db down"));

        String view = controller.forgotSend(request(), model, "dana");

        assertEquals(".ForgotPassword", view);
        assertEquals(List.of("forgotPassword.confirmation"), ControllerTestFixture.messages(model));
        assertTrue(mail.sent().isEmpty(), "a failed token issue must not attempt to mail a token that was never made");
        // The finally that releases the session must run on the exception
        // path too, or a failing background attempt leaks a connection.
        verify(mocks.weblogger()).release();
    }

    /**
     * {@code executeInBackground} declares {@code InterruptedException}; if
     * scheduling itself is interrupted, the controller must restore the
     * interrupt flag and let the response go out rather than propagating.
     */
    @Test
    void anInterruptedSchedulingAttemptReInterruptsAndStillReturnsTheConfirmation() throws Exception {
        doThrow(new InterruptedException("shutting down"))
                .when(mocks.getThreadManager()).executeInBackground(any());
        User user = user("dana", "dana@example.invalid");
        when(mocks.getUserManager().getUserByUserName("dana", Boolean.TRUE)).thenReturn(user);

        try {
            String view = controller.forgotSend(request(), model, "dana");

            assertEquals(".ForgotPassword", view);
            assertEquals(List.of("forgotPassword.confirmation"), ControllerTestFixture.messages(model));
            assertTrue(Thread.currentThread().isInterrupted(),
                    "the interrupt must be restored on the thread, not silently dropped");
            // Scheduling itself failed -- the runnable never ran, so nothing
            // opened a session that would need releasing.
            verify(mocks.weblogger(), never()).release();
        } finally {
            // Clear the flag this test just set, so it cannot bleed into
            // whatever the test runner schedules on this thread next.
            Thread.interrupted();
        }
    }

    @Test
    void mailNotConfiguredShowsThePlainMessageAndIssuesNoToken() throws Exception {
        MockMailProvider.uninstall();
        try {
            User user = user("dana", "dana@example.invalid");
            when(mocks.getUserManager().getUserByUserName("dana", Boolean.TRUE)).thenReturn(user);

            String view = controller.forgotSend(request(), model, "dana");

            assertEquals(".ForgotPassword", view);
            assertEquals(List.of("forgotPassword.mailNotConfigured"), ControllerTestFixture.messages(model));
            verify(mocks.getUserTokenManager(), never()).issueToken(any(), any());
        } finally {
            mail = MockMailProvider.install();
        }
    }

    @Test
    void mailConfiguredButNoSiteAdminEmailShowsThePlainMessageAndIssuesNoToken() throws Exception {
        // Transport is configured (setUp's MockMailProvider), but there is
        // nowhere to send from -- resetMailReady() must catch this too, not
        // just an absent transport.
        when(mocks.getPropertiesManager().getProperty("site.adminemail"))
                .thenReturn(new RuntimeConfigProperty("site.adminemail", "   "));
        User user = user("dana", "dana@example.invalid");
        when(mocks.getUserManager().getUserByUserName("dana", Boolean.TRUE)).thenReturn(user);

        String view = controller.forgotSend(request(), model, "dana");

        assertEquals(".ForgotPassword", view);
        assertEquals(List.of("forgotPassword.mailNotConfigured"), ControllerTestFixture.messages(model));
        verify(mocks.getUserTokenManager(), never()).issueToken(any(), any());
        assertTrue(mail.sent().isEmpty());
    }

    @Test
    void theLookupIssueAndMailSequenceIsHandedToTheBackgroundThreadManager() throws Exception {
        User user = user("dana", "dana@example.invalid");
        when(mocks.getUserManager().getUserByUserName("dana", Boolean.TRUE)).thenReturn(user);

        controller.forgotSend(request(), model, "dana");

        verify(mocks.getThreadManager()).executeInBackground(any());
    }

    /**
     * The timing side channel this closes: an existing identifier used to
     * cost a synchronous SMTP send in-request, an unknown one only two
     * SELECTs -- a caller could literally time the difference. With a thread
     * manager that genuinely defers (unlike this test class's default inline
     * stub) the response must come back identical either way, having done
     * none of the user-dependent work itself.
     */
    @Test
    void whenTheThreadManagerActuallyDefersTheResponseDoesNoSynchronousLookupOrMailWork() throws Exception {
        doNothing().when(mocks.getThreadManager()).executeInBackground(any());
        User user = user("dana", "dana@example.invalid");
        when(mocks.getUserManager().getUserByUserName("dana", Boolean.TRUE)).thenReturn(user);

        String view = controller.forgotSend(request(), model, "dana");

        assertEquals(".ForgotPassword", view);
        assertEquals(List.of("forgotPassword.confirmation"), ControllerTestFixture.messages(model));
        verify(mocks.getUserTokenManager(), never()).issueToken(any(), any());
        assertTrue(mail.sent().isEmpty(), "no mail work may happen synchronously in-request");
        // release() belongs to the worker thread's runnable, which never ran
        // here -- the request thread itself must not have called it either.
        verify(mocks.weblogger(), never()).release();
    }

    @Test
    void anIdentifierAlreadyOverTheThrottleStillGetsTheGenericConfirmationButNoFurtherToken() throws Exception {
        User user = user("dana", "dana@example.invalid");
        when(mocks.getUserManager().getUserByUserName("dana", Boolean.TRUE)).thenReturn(user);
        when(mocks.getUserTokenManager().issueToken(user, UserToken.Purpose.PASSWORD_RESET))
                .thenReturn("raw-token-123");

        // A threshold of zero makes the second hit against the same
        // identifier abusive. Two different client addresses isolate the
        // per-identifier half of the throttle from the per-address half --
        // neither address alone has been seen before.
        String previous = ControllerTestFixture.setConfigProperty("passwordreset.throttle.threshold", "0");
        try {
            ExtendedModelMap firstModel = new ExtendedModelMap();
            controller.forgotSend(request("10.0.0.1"), firstModel, "dana");

            ExtendedModelMap secondModel = new ExtendedModelMap();
            controller.forgotSend(request("10.0.0.2"), secondModel, "dana");

            assertEquals(ControllerTestFixture.messages(firstModel), ControllerTestFixture.messages(secondModel),
                    "a throttled request must read exactly like a normal one -- a distinct "
                            + "response would itself reveal that throttling engaged");
            verify(mocks.getUserTokenManager(), times(1))
                    .issueToken(eq(user), eq(UserToken.Purpose.PASSWORD_RESET));
        } finally {
            ControllerTestFixture.restoreConfigProperty("passwordreset.throttle.threshold", previous);
        }
    }

    @Test
    void aRemoteAddressAlreadyOverTheThrottleStillGetsTheGenericConfirmationButNoFurtherToken() throws Exception {
        User dana = user("dana", "dana@example.invalid");
        User pat = user("pat", "pat@example.invalid");
        when(mocks.getUserManager().getUserByUserName("dana", Boolean.TRUE)).thenReturn(dana);
        when(mocks.getUserManager().getUserByUserName("pat", Boolean.TRUE)).thenReturn(pat);

        String previous = ControllerTestFixture.setConfigProperty("passwordreset.throttle.threshold", "0");
        try {
            controller.forgotSend(request(), new ExtendedModelMap(), "dana");

            // Same client address, a different identifier this time: the
            // per-address half of the throttle must catch it too.
            controller.forgotSend(request(), new ExtendedModelMap(), "pat");

            verify(mocks.getUserTokenManager(), times(1)).issueToken(any(), any());
        } finally {
            ControllerTestFixture.restoreConfigProperty("passwordreset.throttle.threshold", previous);
        }
    }

    @Test
    void theForgotPasswordFormPageRendersWithNoParameters() {
        String view = controller.forgotForm(request(), model);

        assertEquals(".ForgotPassword", view);
        assertEquals("forgotPassword.title", model.getAttribute("pageTitle"));
        assertEquals(List.of(), ControllerTestFixture.messages(model),
                "no notice when mail is actually ready to send");
    }

    @Test
    void theForgotPasswordFormShowsANoticeWhenMailIsNotReady() throws Exception {
        when(mocks.getPropertiesManager().getProperty("site.adminemail")).thenReturn(null);

        String view = controller.forgotForm(request(), model);

        assertEquals(".ForgotPassword", view);
        assertEquals(List.of("forgotPassword.mailNotConfigured"), ControllerTestFixture.messages(model));
    }

    // -------------------------------------------------------- reset (GET)

    @Test
    void aValidTokenShowsTheResetForm() throws Exception {
        UserToken token = new UserToken();
        when(mocks.getUserTokenManager().validate("good-token")).thenReturn(token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.resetForm(request(), response, model, "good-token");

        assertEquals(".ResetPassword", view);
        assertEquals(Boolean.TRUE, model.getAttribute("resetTokenValid"));
        assertEquals("good-token", model.getAttribute("resetToken"));
        assertEquals("private, no-store", response.getHeader("Cache-Control"));
    }

    @Test
    void aGarbageExpiredOrUsedTokenShowsTheGenericInvalidState() throws Exception {
        when(mocks.getUserTokenManager().validate("bad-token")).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        String view = controller.resetForm(request(), response, model, "bad-token");

        assertEquals(".ResetPassword", view);
        assertEquals(Boolean.FALSE, model.getAttribute("resetTokenValid"));
        assertEquals("private, no-store", response.getHeader("Cache-Control"));
    }

    // ------------------------------------------------------- reset (POST)

    @Test
    void aValidTokenWithMatchingPasswordsChangesThePasswordConsumesTheTokenAndEndsSessions()
            throws Exception {
        User user = user("dana", "dana@example.invalid");
        user.setPassword(TestUtils.TEST_PASSWORD_HASH);
        UserToken token = new UserToken();
        when(mocks.getUserTokenManager().validate("good-token")).thenReturn(token);
        when(mocks.getUserTokenManager().consume("good-token")).thenReturn(user);

        registerSessionFor("dana");
        assertNotNull(RollerLoginSessionManager.getInstance().get("dana"), "precondition");

        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.resetSave(request(), new MockHttpServletResponse(), model,
                redirectAttributes, "good-token", "newpassword1", "newpassword1");

        assertEquals("redirect:/roller-ui/login.rol", view);
        assertTrue(new BCryptPasswordEncoder().matches(
                        "newpassword1", user.getPassword().substring("{bcrypt}".length())),
                "the reset password should verify against the stored hash");
        verify(mocks.getUserManager()).saveUser(user);
        assertEquals(List.of("resetPassword.done"), ControllerTestFixture.flashMessages(redirectAttributes));
        assertNull(RollerLoginSessionManager.getInstance().get("dana"),
                "a password reset must force any signed-in session to re-authenticate");
    }

    @Test
    void aRacedOrExpiredTokenAtConsumeTimeShowsTheInvalidState() throws Exception {
        UserToken token = new UserToken();
        when(mocks.getUserTokenManager().validate("good-token")).thenReturn(token);
        // validate() said yes but consume() says no -- another request won
        // the race between the two calls.
        when(mocks.getUserTokenManager().consume("good-token")).thenReturn(null);

        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.resetSave(request(), new MockHttpServletResponse(), model,
                redirectAttributes, "good-token", "newpassword1", "newpassword1");

        assertEquals(".ResetPassword", view);
        assertEquals(Boolean.FALSE, model.getAttribute("resetTokenValid"));
        verify(mocks.getUserManager(), never()).saveUser(any());
    }

    @Test
    void mismatchedPasswordsAreRefusedAndTheTokenIsNotConsumed() throws Exception {
        UserToken token = new UserToken();
        when(mocks.getUserTokenManager().validate("good-token")).thenReturn(token);

        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.resetSave(request(), new MockHttpServletResponse(), model,
                redirectAttributes, "good-token", "newpassword1", "somethingelse1");

        assertEquals(".ResetPassword", view);
        assertEquals(List.of("resetPassword.mismatch"), ControllerTestFixture.errors(model));
        assertEquals(Boolean.TRUE, model.getAttribute("resetTokenValid"));
        assertEquals("good-token", model.getAttribute("resetToken"));
        verify(mocks.getUserTokenManager(), never()).consume(any());
    }

    @Test
    void aPasswordShorterThanEightCharactersIsRefused() throws Exception {
        UserToken token = new UserToken();
        when(mocks.getUserTokenManager().validate("good-token")).thenReturn(token);

        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.resetSave(request(), new MockHttpServletResponse(), model,
                redirectAttributes, "good-token", "short1", "short1");

        assertEquals(".ResetPassword", view);
        assertEquals(List.of("resetPassword.tooShort"), ControllerTestFixture.errors(model));
        verify(mocks.getUserTokenManager(), never()).consume(any());
    }

    @Test
    void anAlreadyInvalidTokenAtSaveTimeShowsTheInvalidStateWithoutTouchingPasswords() throws Exception {
        when(mocks.getUserTokenManager().validate("bad-token")).thenReturn(null);

        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.resetSave(request(), new MockHttpServletResponse(), model,
                redirectAttributes, "bad-token", "newpassword1", "newpassword1");

        assertEquals(".ResetPassword", view);
        assertEquals(Boolean.FALSE, model.getAttribute("resetTokenValid"));
        verify(mocks.getUserTokenManager(), never()).consume(any());
        verify(mocks.getUserManager(), never()).saveUser(any());
    }

    // --------------------------------------------------------------- security

    @Test
    void bothFlowsAreReachableByAnonymousVisitors() {
        assertFalse(controller.isUserRequired());
        assertFalse(controller.isWeblogRequired());
        assertTrue(controller.requiredGlobalPermissionActions().isEmpty());
        assertTrue(controller.requiredWeblogPermissionActions().isEmpty());
    }

    // --------------------------------------------------------------- support

    private static User user(String userName, String email) {
        User user = new User();
        user.setUserName(userName);
        user.setEmailAddress(email);
        user.setEnabled(Boolean.TRUE);
        return user;
    }

    private static MockHttpServletRequest request() {
        return request("127.0.0.1");
    }

    private static MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private static RollerSession registerSessionFor(String userName) {
        RollerSession session = Mockito.mock(RollerSession.class);
        RollerLoginSessionManager.getInstance().register(userName, session);
        return session;
    }

    private static User boundReader(String userName) {
        User reader = user(userName, "reader@example.invalid");
        reader.setSsoIssuer("https://accounts.example");
        reader.setSsoSubject("sub-1");
        return reader;
    }

    @Test
    void forgotPasswordByAReaderUsernameSendsNothing() throws Exception {
        User reader = boundReader("reader-abc1234567");
        when(mocks.getUserManager().getUserByUserName("reader-abc1234567", Boolean.TRUE)).thenReturn(reader);

        String view = controller.forgotSend(request(), model, "reader-abc1234567");

        assertEquals(".ForgotPassword", view);
        assertEquals(List.of("forgotPassword.confirmation"), ControllerTestFixture.messages(model));
        verify(mocks.getUserTokenManager(), never()).issueToken(any(), any());
        assertTrue(mail.sent().isEmpty());
    }

    @Test
    void forgotPasswordByASharedEmailReachesTheLocalAccount() throws Exception {
        User local = user("dana", "shared@example.invalid");
        when(mocks.getUserManager().getUserByEmail("shared@example.invalid")).thenReturn(local);
        when(mocks.getUserTokenManager().issueToken(local, UserToken.Purpose.PASSWORD_RESET))
                .thenReturn("raw-token-9");

        controller.forgotSend(request(), model, "shared@example.invalid");

        verify(mocks.getUserTokenManager()).issueToken(local, UserToken.Purpose.PASSWORD_RESET);
    }

    @Test
    void resetSaveRefusesABoundAccount() throws Exception {
        User reader = boundReader("reader-abc1234567");
        reader.setPassword(User.SSO_PASSWORD_MARKER);
        when(mocks.getUserTokenManager().validate("good-token")).thenReturn(new UserToken());
        when(mocks.getUserTokenManager().consume("good-token")).thenReturn(reader);

        String view = controller.resetSave(request(), new MockHttpServletResponse(), model,
                new RedirectAttributesModelMap(), "good-token", "newpassword1", "newpassword1");

        assertEquals(".ResetPassword", view);
        assertEquals(Boolean.FALSE, model.getAttribute("resetTokenValid"));
        verify(mocks.getUserManager(), never()).saveUser(any());
        assertEquals(User.SSO_PASSWORD_MARKER, reader.getPassword());
    }
}
