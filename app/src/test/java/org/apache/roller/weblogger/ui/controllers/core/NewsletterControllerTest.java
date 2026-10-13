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

import java.io.IOException;
import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.ListmonkClient;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.RollerEvent;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link NewsletterController}, the public {@code /newsletter/subscribe}
 * endpoint.
 *
 * <p>Uses {@link MockWeblogger} for the business tier and a Mockito mock of
 * {@link ListmonkClient} (injected via the controller's package-private
 * setter) so the controller's own layered defences -- throttle, the by-uuid
 * weblog lookup, honeypot/timing, validation, and its handling of Listmonk's
 * response -- can be tested without a real Listmonk instance.
 */
class NewsletterControllerTest {

    private static final String UUID = "2f0f1b0c-1111-2222-3333-444455556666";

    private MockWeblogger mocks;
    private NewsletterController controller;
    private ListmonkClient listmonk;

    @BeforeEach
    void setUp() {
        mocks = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(mocks.weblogger());
        controller = ControllerTestFixture.withMessages(new NewsletterController());
        listmonk = mock(ListmonkClient.class);
        controller.setListmonkClient(listmonk);
    }

    @AfterEach
    void tearDown() {
        mocks.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    // --------------------------------------------------------- happy path

    @Test
    void aValidSubscriptionForwardsAndReturnsListmonksStatusAndRecordsTheEvent() throws Exception {
        Weblog weblog = weblog("newsblog");
        when(mocks.getWeblogManager().getWeblogByNewsletterListUuid(UUID)).thenReturn(weblog);
        when(listmonk.subscribe("reader@example.com", UUID)).thenReturn(200);

        ResponseEntity<Void> response = controller.subscribe(
                payload("reader@example.com", UUID, "", 5000L), request());

        assertEquals(200, response.getStatusCode().value());
        verify(listmonk).subscribe("reader@example.com", UUID);

        ArgumentCaptor<RollerEvent> eventCaptor = ArgumentCaptor.forClass(RollerEvent.class);
        verify(mocks.getEventManager()).record(eventCaptor.capture());
        assertEquals(RollerEvent.EventType.NEWSLETTER_SUBSCRIBED, eventCaptor.getValue().getEventType());
        assertEquals(weblog, eventCaptor.getValue().getWeblog());
    }

    @Test
    void aConflictPassesThroughAsItsOwnStatusAndRecordsNothing() throws Exception {
        Weblog weblog = weblog("newsblog");
        when(mocks.getWeblogManager().getWeblogByNewsletterListUuid(UUID)).thenReturn(weblog);
        when(listmonk.subscribe("reader@example.com", UUID)).thenReturn(409);

        ResponseEntity<Void> response = controller.subscribe(
                payload("reader@example.com", UUID, "", 5000L), request());

        assertEquals(409, response.getStatusCode().value());
        verify(mocks.getEventManager(), never()).record(any());
    }

    // ------------------------------------------------------- open-relay guard

    @Test
    void aListUuidMatchingNoWeblogReturns404AndNeverForwards() throws Exception {
        // getWeblogByNewsletterListUuid is unstubbed -- the mock returns null,
        // exactly as it would for a uuid that names no weblog.
        ResponseEntity<Void> response = controller.subscribe(
                payload("reader@example.com", UUID, "", 5000L), request());

        assertEquals(404, response.getStatusCode().value());
        verify(listmonk, never()).subscribe(any(), any());
    }

    // -------------------------------------------------------------- 400

    @Test
    void aBadEmailReturns400() throws Exception {
        Weblog weblog = weblog("newsblog");
        when(mocks.getWeblogManager().getWeblogByNewsletterListUuid(UUID)).thenReturn(weblog);

        ResponseEntity<Void> response = controller.subscribe(
                payload("not-an-email", UUID, "", 5000L), request());

        assertEquals(400, response.getStatusCode().value());
        verify(listmonk, never()).subscribe(any(), any());
    }

    @Test
    void anOverLengthEmailReturns400BeforeEverReachingListmonk() throws Exception {
        Weblog weblog = weblog("newsblog");
        when(mocks.getWeblogManager().getWeblogByNewsletterListUuid(UUID)).thenReturn(weblog);

        // FormSubmissionManager.MAX_EMAIL is 255; this is 256 characters.
        String tooLongEmail = "a".repeat(251) + "@x.co";
        ResponseEntity<Void> response = controller.subscribe(
                payload(tooLongEmail, UUID, "", 5000L), request());

        assertEquals(400, response.getStatusCode().value());
        verify(listmonk, never()).subscribe(any(), any());
    }

    // ---------------------------------------------------- silent bot drops

    @Test
    void aFilledHoneypotReturns200WithNoForward() throws Exception {
        Weblog weblog = weblog("newsblog");
        when(mocks.getWeblogManager().getWeblogByNewsletterListUuid(UUID)).thenReturn(weblog);

        ResponseEntity<Void> response = controller.subscribe(
                payload("bot@example.com", UUID, "http://spam.example", 5000L), request());

        assertEquals(200, response.getStatusCode().value(),
                "a detected bot must see exactly the same response as success");
        verify(listmonk, never()).subscribe(any(), any());
    }

    @Test
    void elapsedMsBelowTheConfiguredMinimumReturns200WithNoForward() throws Exception {
        Weblog weblog = weblog("newsblog");
        when(mocks.getWeblogManager().getWeblogByNewsletterListUuid(UUID)).thenReturn(weblog);

        ResponseEntity<Void> response = controller.subscribe(
                payload("fast@example.com", UUID, "", 100L), request());

        assertEquals(200, response.getStatusCode().value(),
                "a too-fast submission must be indistinguishable from success");
        verify(listmonk, never()).subscribe(any(), any());
    }

    // ------------------------------------------------------------- 503/502

    @Test
    void anUnconfiguredClientReturns503() throws Exception {
        Weblog weblog = weblog("newsblog");
        when(mocks.getWeblogManager().getWeblogByNewsletterListUuid(UUID)).thenReturn(weblog);
        when(listmonk.isUnconfigured()).thenReturn(true);

        ResponseEntity<Void> response = controller.subscribe(
                payload("reader@example.com", UUID, "", 5000L), request());

        assertEquals(503, response.getStatusCode().value());
        verify(listmonk, never()).subscribe(any(), any());
    }

    @Test
    void anIOExceptionFromTheForwardReturns502() throws Exception {
        Weblog weblog = weblog("newsblog");
        when(mocks.getWeblogManager().getWeblogByNewsletterListUuid(UUID)).thenReturn(weblog);
        when(listmonk.subscribe("reader@example.com", UUID)).thenThrow(new IOException("connection refused"));

        ResponseEntity<Void> response = controller.subscribe(
                payload("reader@example.com", UUID, "", 5000L), request());

        assertEquals(502, response.getStatusCode().value());
        verify(mocks.getEventManager(), never()).record(any());
    }

    // -------------------------------------------------------------- 429

    @Test
    void whenTheThrottleReportsAbusiveReturns429() throws Exception {
        Weblog weblog = weblog("newsblog");
        when(mocks.getWeblogManager().getWeblogByNewsletterListUuid(UUID)).thenReturn(weblog);
        when(listmonk.subscribe("reader@example.com", UUID)).thenReturn(200);

        // A threshold of zero makes the second hit from the same client abusive.
        String previous = ControllerTestFixture.setConfigProperty(
                "newsletter.subscribe.throttle.threshold", "0");
        try {
            ResponseEntity<Void> first = controller.subscribe(
                    payload("reader@example.com", UUID, "", 5000L), request());
            assertEquals(200, first.getStatusCode().value(), "the first hit must not be refused");

            ResponseEntity<Void> second = controller.subscribe(
                    payload("reader@example.com", UUID, "", 5000L), request());
            assertEquals(429, second.getStatusCode().value(),
                    "a client already over the threshold must be refused");
        } finally {
            ControllerTestFixture.restoreConfigProperty("newsletter.subscribe.throttle.threshold", previous);
        }
    }

    // ------------------------------------------------------- best-effort event

    @Test
    void theEventRecordFailingDoesNotFailTheRequest() throws Exception {
        Weblog weblog = weblog("newsblog");
        when(mocks.getWeblogManager().getWeblogByNewsletterListUuid(UUID)).thenReturn(weblog);
        when(listmonk.subscribe("reader@example.com", UUID)).thenReturn(200);
        doThrow(new WebloggerException("event insert failed"))
                .when(mocks.getEventManager()).record(any());

        ResponseEntity<Void> response = controller.subscribe(
                payload("reader@example.com", UUID, "", 5000L), request());

        assertEquals(200, response.getStatusCode().value(),
                "an event-recording failure must not fail an already-forwarded subscription");
    }

    // --------------------------------------------------------------- security

    @Test
    void theSubscribeEndpointIsReachableByAnonymousVisitors() {
        assertFalse(controller.isUserRequired(), "the subscribe form is for anonymous readers");
        assertFalse(controller.isWeblogRequired(), "the weblog is resolved from the list uuid, not a request attribute");
        assertTrue(controller.requiredGlobalPermissionActions().isEmpty());
    }

    // ------------------------------------------------ requests with no usable list

    @Test
    void aPayloadWithNoListUuidsAtAllIs404WithoutALookup() throws Exception {
        for (List<String> uuids : java.util.Arrays.<List<String>>asList(null, List.of(), List.of(" "))) {
            ResponseEntity<Void> response = controller.subscribe(
                    new NewsletterController.SubscribePayload("reader@example.com", uuids, "", 5000L), request());

            assertEquals(404, response.getStatusCode().value(), String.valueOf(uuids));
        }
        verify(mocks.getWeblogManager(), never()).getWeblogByNewsletterListUuid(any());
        verify(listmonk, never()).subscribe(any(), any());
    }

    /** A lookup that cannot run must not be read as a weblog: nothing is forwarded. */
    @Test
    void aWeblogLookupThatFailsIs404AndNothingIsForwarded() throws Exception {
        when(mocks.getWeblogManager().getWeblogByNewsletterListUuid(UUID))
                .thenThrow(new WebloggerException("store down"));

        ResponseEntity<Void> response = controller.subscribe(
                payload("reader@example.com", UUID, "", 5000L), request());

        assertEquals(404, response.getStatusCode().value());
        verify(listmonk, never()).subscribe(any(), any());
    }

    @Test
    void aHoneypotHitCountsAgainstTheThrottleSoABotIsEventuallyRefused() throws Exception {
        when(mocks.getWeblogManager().getWeblogByNewsletterListUuid(UUID)).thenReturn(weblog("newsblog"));
        String previous = ControllerTestFixture.setConfigProperty(
                "newsletter.subscribe.throttle.threshold", "0");
        try {
            ResponseEntity<Void> first = controller.subscribe(
                    payload("bot@example.com", UUID, "http://spam.example", 5000L), request());
            ResponseEntity<Void> second = controller.subscribe(
                    payload("bot@example.com", UUID, "http://spam.example", 5000L), request());

            assertEquals(200, first.getStatusCode().value(), "a detected bot is answered like a success");
            assertEquals(429, second.getStatusCode().value(), "but its hits still add up");
        } finally {
            ControllerTestFixture.restoreConfigProperty("newsletter.subscribe.throttle.threshold", previous);
        }
        verify(listmonk, never()).subscribe(any(), any());
    }

    @Test
    void anUnreadableThrottleSettingFallsBackToTheDefaultRatherThanBreakingTheForm() throws Exception {
        when(mocks.getWeblogManager().getWeblogByNewsletterListUuid(UUID)).thenReturn(weblog("newsblog"));
        when(listmonk.subscribe("reader@example.com", UUID)).thenReturn(200);
        String previous = ControllerTestFixture.setConfigProperty(
                "newsletter.subscribe.throttle.threshold", "not-a-number");
        try {
            ResponseEntity<Void> response = controller.subscribe(
                    payload("reader@example.com", UUID, "", 5000L), request());

            assertEquals(200, response.getStatusCode().value());
        } finally {
            ControllerTestFixture.restoreConfigProperty("newsletter.subscribe.throttle.threshold", previous);
        }
    }

    /** With no client injected the controller builds one from config; none is configured here, so 503. */
    @Test
    void withNoNewsletterServiceConfiguredTheLazilyBuiltClientAnswers503() throws Exception {
        NewsletterController fresh = ControllerTestFixture.withMessages(new NewsletterController());
        when(mocks.getWeblogManager().getWeblogByNewsletterListUuid(UUID)).thenReturn(weblog("newsblog"));

        ResponseEntity<Void> response = fresh.subscribe(payload("reader@example.com", UUID, "", 5000L), request());

        assertEquals(503, response.getStatusCode().value());
    }

    // --------------------------------------------------------------- support

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        return weblog;
    }

    private static NewsletterController.SubscribePayload payload(
            String email, String uuid, String website, long elapsedMs) {
        return new NewsletterController.SubscribePayload(email, List.of(uuid), website, elapsedMs);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
