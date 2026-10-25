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

import jakarta.mail.MessagingException;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.business.startup.MockMailProvider;
import org.apache.roller.weblogger.pojos.FormSubmission;
import org.apache.roller.weblogger.pojos.RollerEvent;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ContactController}, the public contact endpoint.
 *
 * <p>Uses {@link MockWeblogger} rather than a real business tier -- the
 * behaviour under test is entirely the controller's own layered defences
 * (throttle, honeypot, timing, validation) and its persist-first ordering,
 * none of which need a database. {@code MailUtil} is never statically
 * mocked: {@link RecordingContactController} overrides the controller's own
 * package-private mail seam, {@link ContactController#sendNotification}, the
 * only call the controller makes into {@code MailUtil}.
 */
class ContactControllerTest {

    private MockWeblogger mocks;
    private MockMailProvider mail;
    private RecordingContactController controller;

    @BeforeEach
    void setUp() {
        mocks = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(mocks.weblogger());
        mail = MockMailProvider.install();
        controller = ControllerTestFixture.withMessages(new RecordingContactController());
    }

    @AfterEach
    void tearDown() {
        MockMailProvider.uninstall();
        mocks.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    // --------------------------------------------------------- happy path

    @Test
    void aValidSubmissionPersistsRecordsAnEventAttemptsNotificationAndReturns204() throws Exception {
        Weblog weblog = weblog("contactblog");
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);

        ResponseEntity<Void> response = controller.submit(
                payload("contactblog", "Dana", "dana@example.invalid", "Question",
                        "Hello there", "", 5000L, "/contactblog/page/about"),
                request());

        assertEquals(204, response.getStatusCode().value());

        ArgumentCaptor<FormSubmission> captor = ArgumentCaptor.forClass(FormSubmission.class);
        verify(mocks.getFormSubmissionManager()).save(captor.capture());
        FormSubmission saved = captor.getValue();
        assertEquals("Dana", saved.getName());
        assertEquals("dana@example.invalid", saved.getEmail());
        assertEquals("Hello there", saved.getMessage());
        assertEquals("127.0.0.1", saved.getClientIp());
        assertEquals("about", saved.getPageSlug(), "the source path's last segment must label the submission");

        ArgumentCaptor<RollerEvent> eventCaptor = ArgumentCaptor.forClass(RollerEvent.class);
        verify(mocks.getEventManager()).record(eventCaptor.capture());
        assertEquals(RollerEvent.EventType.FORM_SUBMITTED, eventCaptor.getValue().getEventType());
        assertEquals(weblog, eventCaptor.getValue().getWeblog());

        assertEquals(1, controller.notifyCount, "the owner notification must be attempted");
        assertEquals("dana@example.invalid", controller.notifiedSubmission.getEmail());
    }

    /**
     * Persist-first: if SMTP is down the lead must not be lost. The
     * notification failing must not change the response the caller sees,
     * because the submission already survived.
     */
    @Test
    void whenMailSendThrowsTheSubmissionIsStillSavedAndTheResponseIsStill204() throws Exception {
        Weblog weblog = weblog("contactblog");
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);
        controller.throwOnNotify = true;

        ResponseEntity<Void> response = controller.submit(
                payload("contactblog", "Dana", "dana@example.invalid", "Question",
                        "Hello there", "", 5000L, null),
                request());

        assertEquals(204, response.getStatusCode().value());
        verify(mocks.getFormSubmissionManager()).save(any());
        assertEquals(1, controller.notifyCount, "the notification must still have been attempted");
    }

    // ---------------------------------------------------- silent bot drops

    @Test
    void aFilledHoneypotReturns204ButPersistsNothing() throws Exception {
        Weblog weblog = weblog("contactblog");
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);

        ResponseEntity<Void> response = controller.submit(
                payload("contactblog", "Bot", "bot@example.invalid", "Spam",
                        "buy now", "http://spam.example", 5000L, null),
                request());

        assertEquals(204, response.getStatusCode().value(),
                "a detected bot must see exactly the same response as success");
        verify(mocks.getFormSubmissionManager(), never()).save(any());
        verify(mocks.getEventManager(), never()).record(any());
        assertEquals(0, controller.notifyCount);
    }

    @Test
    void elapsedMsBelowTheConfiguredMinimumReturns204AndPersistsNothing() throws Exception {
        Weblog weblog = weblog("contactblog");
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);

        // default contact.form.min.seconds is 3 -- 100ms is well under it
        ResponseEntity<Void> response = controller.submit(
                payload("contactblog", "Too Fast", "fast@example.invalid", "Hi",
                        "instant message", "", 100L, null),
                request());

        assertEquals(204, response.getStatusCode().value(),
                "a too-fast submission must be indistinguishable from success");
        verify(mocks.getFormSubmissionManager(), never()).save(any());
        verify(mocks.getEventManager(), never()).record(any());
    }

    // -------------------------------------------------------------- 400s

    @Test
    void aMissingOrBlankRequiredFieldReturns400WithNoPersist() throws Exception {
        Weblog weblog = weblog("contactblog");
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);

        assertBadRequestNoPersist(payload("contactblog", "", "dana@example.invalid",
                "Question", "Hello there", "", 5000L, null), "blank name");
        assertBadRequestNoPersist(payload("contactblog", "Dana", " ",
                "Question", "Hello there", "", 5000L, null), "blank email");
        assertBadRequestNoPersist(payload("contactblog", "Dana", "dana@example.invalid",
                "Question", "", "", 5000L, null), "blank message");
    }

    @Test
    void anEmailWithoutAnAtSignReturns400() throws Exception {
        Weblog weblog = weblog("contactblog");
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);

        assertBadRequestNoPersist(payload("contactblog", "Dana", "not-an-email",
                "Question", "Hello there", "", 5000L, null), "email without @");
    }

    @Test
    void anOverLengthFieldReturns400BeforeEverReachingTheManager() throws Exception {
        Weblog weblog = weblog("contactblog");
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);

        String tooLongName = "x".repeat(256); // FormSubmissionManager.MAX_NAME is 255
        assertBadRequestNoPersist(payload("contactblog", tooLongName, "dana@example.invalid",
                "Question", "Hello there", "", 5000L, null), "over-length name");
    }

    private void assertBadRequestNoPersist(ContactController.ContactPayload payload, String why)
            throws Exception {
        ResponseEntity<Void> response = controller.submit(payload, request());
        assertEquals(400, response.getStatusCode().value(), why);
        verify(mocks.getFormSubmissionManager(), never()).save(any());
    }

    @Test
    void anOverLengthSubjectReturns400() throws Exception {
        Weblog weblog = weblog("contactblog");
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);

        String tooLongSubject = "x".repeat(256); // FormSubmissionManager.MAX_SUBJECT is 255
        assertBadRequestNoPersist(payload("contactblog", "Dana", "dana@example.invalid",
                tooLongSubject, "Hello there", "", 5000L, null), "over-length subject");
    }

    // ------------------------------------------------------------- 404/429

    @Test
    void anUnknownWeblogHandleReturns404() throws Exception {
        // getWeblogByHandle is unstubbed -- the mock returns null, as it would
        // for a handle that names no weblog.
        ResponseEntity<Void> response = controller.submit(
                payload("no-such-weblog", "Dana", "dana@example.invalid", "Question",
                        "Hello there", "", 5000L, null),
                request());

        assertEquals(404, response.getStatusCode().value());
        verify(mocks.getFormSubmissionManager(), never()).save(any());
    }

    @Test
    void theSiteSpaceHandleIsRefusedLikeAnUnknownWeblog() throws Exception {
        Weblog site = weblog(Weblog.SITE_SPACE_HANDLE);
        site.setSiteSpace(true);
        when(mocks.getWeblogManager().getWeblogByHandle(Weblog.SITE_SPACE_HANDLE)).thenReturn(site);

        ResponseEntity<Void> response = controller.submit(
                payload(Weblog.SITE_SPACE_HANDLE, "Dana", "dana@example.invalid", "Question",
                        "Hello there", "", 5000L, null),
                request());

        assertEquals(404, response.getStatusCode().value());
        verify(mocks.getFormSubmissionManager(), never()).save(any());
    }

    @Test
    void aBlankWeblogHandleReturns404WithoutConsultingTheManager() throws Exception {
        ResponseEntity<Void> response = controller.submit(
                payload("", "Dana", "dana@example.invalid", "Question",
                        "Hello there", "", 5000L, null),
                request());

        assertEquals(404, response.getStatusCode().value());
        verify(mocks.getWeblogManager(), never()).getWeblogByHandle(any());
    }

    @Test
    void aWeblogLookupFailureDegradesTo404RatherThanAnError() throws Exception {
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog"))
                .thenThrow(new WebloggerException("lookup failed"));

        ResponseEntity<Void> response = controller.submit(
                payload("contactblog", "Dana", "dana@example.invalid", "Question",
                        "Hello there", "", 5000L, null),
                request());

        assertEquals(404, response.getStatusCode().value());
        verify(mocks.getFormSubmissionManager(), never()).save(any());
    }

    @Test
    void whenTheThrottleReportsAbusiveReturns429AndNothingPersists() throws Exception {
        Weblog weblog = weblog("contactblog");
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);

        // A threshold of zero makes the second hit from the same client
        // abusive, so this needs no long loop to prove the gate works.
        String previous = ControllerTestFixture.setConfigProperty("contact.throttle.threshold", "0");
        try {
            ResponseEntity<Void> first = controller.submit(
                    payload("contactblog", "Dana", "dana@example.invalid", "Question",
                            "Hello there", "", 5000L, null),
                    request());
            assertEquals(204, first.getStatusCode().value(), "the first hit must not be refused");

            ResponseEntity<Void> second = controller.submit(
                    payload("contactblog", "Dana", "dana@example.invalid", "Question",
                            "Hello there", "", 5000L, null),
                    request());
            assertEquals(429, second.getStatusCode().value(),
                    "a client already over the threshold must be refused");

            verify(mocks.getFormSubmissionManager(), times(1)).save(any());
        } finally {
            ControllerTestFixture.restoreConfigProperty("contact.throttle.threshold", previous);
        }
    }

    /**
     * A mistyped number in the properties file must not take the endpoint
     * down. The throttle falls back to its default rather than letting a
     * NumberFormatException escape on the first submission anyone makes.
     */
    @Test
    void aMalformedThrottleSettingFallsBackInsteadOfFailing() throws Exception {
        Weblog weblog = weblog("contactblog");
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);
        String previous = ControllerTestFixture.setConfigProperty("contact.throttle.threshold", "ten");
        try {
            RecordingContactController misconfigured =
                    ControllerTestFixture.withMessages(new RecordingContactController());

            ResponseEntity<Void> response = misconfigured.submit(
                    payload("contactblog", "Dana", "dana@example.invalid", "Question",
                            "Hello there", "", 5000L, null),
                    request());

            assertEquals(204, response.getStatusCode().value(),
                    "a bad threshold must degrade to the default, not throw");
        } finally {
            ControllerTestFixture.restoreConfigProperty("contact.throttle.threshold", previous);
        }
    }

    // ------------------------------------------------------- best-effort event

    @Test
    void theEventRecordFailingDoesNotFailTheRequest() throws Exception {
        Weblog weblog = weblog("contactblog");
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);
        doThrow(new WebloggerException("event insert failed"))
                .when(mocks.getEventManager()).record(any());

        ResponseEntity<Void> response = controller.submit(
                payload("contactblog", "Dana", "dana@example.invalid", "Question",
                        "Hello there", "", 5000L, null),
                request());

        assertEquals(204, response.getStatusCode().value(),
                "an event-recording failure must not fail an already-persisted submission");
        verify(mocks.getFormSubmissionManager()).save(any());
    }

    // ---------------------------------------------------------------- 500

    @Test
    void aPersistFailureReturns500() throws Exception {
        Weblog weblog = weblog("contactblog");
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);
        doThrow(new WebloggerException("db down")).when(mocks.getFormSubmissionManager()).save(any());

        ResponseEntity<Void> response = controller.submit(
                payload("contactblog", "Dana", "dana@example.invalid", "Question",
                        "Hello there", "", 5000L, null),
                request());

        assertEquals(500, response.getStatusCode().value());
        verify(mocks.getEventManager(), never()).record(any());
        assertEquals(0, controller.notifyCount, "no notification without a persisted submission");
    }

    // ------------------------------------------------------- source labelling

    /**
     * {@code source} is client-controlled URL-path text, parsed for display
     * only. This covers the parsing edges: a query string must not leak into
     * the label, a trailing slash must not produce a blank segment, a source
     * with nothing to label must leave both fields null, and an
     * {@code /entry/} path must label the anchor rather than the page slug.
     */
    @Test
    void sourceParsingHandlesEntryAnchorsQueryStringsTrailingSlashesAndBlankSources() throws Exception {
        Weblog weblog = weblog("contactblog");
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);

        submitValid("/contactblog/entry/my-anchor?utm=1");
        submitValid("/contactblog/page/about/");
        submitValid("?x=1");

        ArgumentCaptor<FormSubmission> captor = ArgumentCaptor.forClass(FormSubmission.class);
        verify(mocks.getFormSubmissionManager(), times(3)).save(captor.capture());
        var saved = captor.getAllValues();

        assertEquals("my-anchor", saved.get(0).getEntryAnchor(), "the query string must be stripped");
        assertNull(saved.get(0).getPageSlug());

        assertEquals("about", saved.get(1).getPageSlug(), "a trailing slash must not produce a blank segment");
        assertNull(saved.get(1).getEntryAnchor());

        assertNull(saved.get(2).getEntryAnchor(), "a source with no path segment labels nothing");
        assertNull(saved.get(2).getPageSlug());
    }

    private ResponseEntity<Void> submitValid(String source) throws Exception {
        return controller.submit(
                payload("contactblog", "Dana", "dana@example.invalid", "Question",
                        "Hello there", "", 5000L, source),
                request());
    }

    // ------------------------------------------------------- notify guards

    @Test
    void notifyIsSkippedWhenTheWeblogHasNoEmailAddress() throws Exception {
        Weblog weblog = weblog("contactblog");
        weblog.setEmailAddress(null);
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);

        ResponseEntity<Void> response = controller.submit(
                payload("contactblog", "Dana", "dana@example.invalid", "Question",
                        "Hello there", "", 5000L, null),
                request());

        assertEquals(204, response.getStatusCode().value());
        verify(mocks.getFormSubmissionManager()).save(any());
        assertEquals(0, controller.notifyCount, "no owner address means nowhere to send the notification");
    }

    @Test
    void notifyIsSkippedWhenMailIsNotConfigured() throws Exception {
        MockMailProvider.uninstall();
        try {
            Weblog weblog = weblog("contactblog");
            when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);

            ResponseEntity<Void> response = controller.submit(
                    payload("contactblog", "Dana", "dana@example.invalid", "Question",
                            "Hello there", "", 5000L, null),
                    request());

            assertEquals(204, response.getStatusCode().value());
            verify(mocks.getFormSubmissionManager()).save(any());
            assertEquals(0, controller.notifyCount);
        } finally {
            MockMailProvider.install();
        }
    }

    /**
     * The only test that exercises {@link ContactController#sendNotification}
     * for real rather than through {@link RecordingContactController}'s
     * override -- proof that the composed subject/body/Reply-To are what
     * actually reaches {@code MailUtil}, not just what the seam records.
     */
    @Test
    void theRealNotificationCarriesTheComposedSubjectBodyAndReplyTo() throws Exception {
        Weblog weblog = weblog("contactblog");
        when(mocks.getWeblogManager().getWeblogByHandle("contactblog")).thenReturn(weblog);
        ContactController plainController = ControllerTestFixture.withMessages(new ContactController());

        ResponseEntity<Void> response = plainController.submit(
                payload("contactblog", "Dana", "dana@example.invalid", "Question",
                        "Hello there", "", 5000L, null),
                request());

        assertEquals(204, response.getStatusCode().value());
        jakarta.mail.internet.MimeMessage message = mail.onlyMessage();
        assertEquals("[contactblog] contact: Question", message.getSubject());
        assertTrue(message.getContent().toString().contains("Hello there"));
        assertEquals("dana@example.invalid", message.getReplyTo()[0].toString());
        assertEquals("contactblog-owner@example.invalid", message.getFrom()[0].toString());
    }

    // --------------------------------------------------------------- security

    @Test
    void theContactEndpointIsReachableByAnonymousVisitors() {
        assertFalse(controller.isUserRequired(), "the contact form is for anonymous readers");
        assertFalse(controller.isWeblogRequired(), "the weblog arrives in the JSON body, not a request attribute");
        assertTrue(controller.requiredGlobalPermissionActions().isEmpty());
    }

    // --------------------------------------------------------------- JSON

    /**
     * {@code ContactPayload} is bound from the request body via Jackson, not
     * {@code @RequestParam}/{@code @PathVariable} -- so it is not one of the
     * bare-parameter-name traps {@code ControllerMetadataTest} guards
     * against. But since this build compiles without {@code -parameters},
     * this is exactly the kind of binding that could silently fall back to
     * positional matching (or fail) if record components ever stopped
     * carrying their names in the class file. They do unconditionally (the
     * {@code Record} attribute is part of every record's bytecode, unlike
     * ordinary method parameter names), and this pins that: keys arrive out
     * of declaration order, exactly as the browser's {@code JSON.stringify}
     * of an object literal would not guarantee otherwise.
     */
    @Test
    void thePayloadRecordBindsFromJsonByNameNotByDeclarationOrder() {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{"
                + "\"source\":\"/contactblog/page/about\","
                + "\"elapsedMs\":5000,"
                + "\"website\":\"\","
                + "\"message\":\"Hello there\","
                + "\"subject\":\"Question\","
                + "\"email\":\"dana@example.invalid\","
                + "\"name\":\"Dana\","
                + "\"weblog\":\"contactblog\""
                + "}";

        ContactController.ContactPayload payload =
                mapper.readValue(json, ContactController.ContactPayload.class);

        assertEquals("contactblog", payload.weblog());
        assertEquals("Dana", payload.name());
        assertEquals("dana@example.invalid", payload.email());
        assertEquals("Question", payload.subject());
        assertEquals("Hello there", payload.message());
        assertEquals("", payload.website());
        assertEquals(5000L, payload.elapsedMs());
        assertEquals("/contactblog/page/about", payload.source());
    }

    // --------------------------------------------------------------- support

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setEmailAddress(handle + "-owner@example.invalid");
        return weblog;
    }

    private static ContactController.ContactPayload payload(String weblog, String name, String email,
            String subject, String message, String website, long elapsedMs, String source) {
        return new ContactController.ContactPayload(
                weblog, name, email, subject, message, website, elapsedMs, source);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    /**
     * A {@link ContactController} whose only override is the package-private
     * mail seam, so tests can observe or fail the notification attempt
     * without static-mocking {@code MailUtil} or standing up a mail
     * transport.
     */
    private static class RecordingContactController extends ContactController {
        private boolean throwOnNotify;
        private int notifyCount;
        private FormSubmission notifiedSubmission;

        @Override
        void sendNotification(Weblog weblog, FormSubmission s, String subject, String body) throws Exception {
            notifyCount++;
            notifiedSubmission = s;
            if (throwOnNotify) {
                throw new MessagingException("smtp down");
            }
        }
    }
}
