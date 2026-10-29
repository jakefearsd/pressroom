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
package com.wikantik.pressroom.ui.controllers.editor;

import java.io.IOException;
import java.sql.Timestamp;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ListmonkClient;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntryEditController#entryEditSendNewsletter}, the
 * synchronous "Send as newsletter" action.
 *
 * <p>Synchronous is a deliberate spec deviation from a background job queue:
 * the human who clicked Send IS the retry mechanism, and
 * {@code newsletterSentAt} is stamped ONLY on a successful
 * {@link ListmonkClient#sendCampaign} call. That is the cannot-double-send
 * property these tests pin: an already-stamped entry is refused before the
 * client is ever touched, and a client failure leaves the stamp untouched so
 * the button stays available to retry.
 *
 * <p>Uses a Mockito mock of {@link ListmonkClient}, injected via the
 * controller's package-private setter -- the same seam shape
 * {@code NewsletterControllerTest} uses for the public subscribe endpoint.
 */
class EntryEditNewsletterTest extends EditorControllerTestSupport {

    private EntryEditController controller;
    private Model model;
    private WeblogCategory category;
    private ListmonkClient listmonk;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new EntryEditController());
        model = newModel();
        listmonk = mock(ListmonkClient.class);
        controller.setListmonkClient(listmonk);

        category = new WeblogCategory();
        category.setId("cat-1");
        category.setName("Travel");
        category.setWeblog(weblog);
        weblog.getWeblogCategories().add(category);

        weblog.setNewsletterListUuid("2f0f1b0c-1111-2222-3333-444455556666");

        // The action gate: entryEditSendNewsletter requires WeblogPermission.POST
        // on top of the class-level EDIT_DRAFT gate. Every test in this class
        // exercises a user who holds it; the denial itself is pinned by
        // theEditDraftOnlyUserIsDeniedAndNeverTouchesTheClient below, which
        // overrides this stub to false.
        when(weblogger.getUserManager().checkPermission(any(), any())).thenReturn(true);

        // getPermalink() is built off the WeblogEntry's own anchor through
        // the URLStrategy the entry text below is checked against.
        when(weblogger.getUrlStrategy().getWeblogEntryURL(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn("http://example.com/testblog/entry/stored-title");

        // The editor builds a preview link for any entry that has an id
        // (EntryEditControllerTest sets up the same stub for the same reason).
        when(weblogger.getUrlStrategy().getPreviewURLStrategy(any()))
                .thenReturn(mock(com.wikantik.pressroom.business.URLStrategy.class));
    }

    // --------------------------------------------------------- happy path

    @Test
    void sendingAPublishedNeverSentEntrySendsTheCampaignAndStampsIt() throws Exception {
        WeblogEntry entry = publishedEntry();
        entry.setText("Some **markdown** body.");
        when(listmonk.isCampaignConfigured()).thenReturn(true);

        String view = controller.entryEditSendNewsletter("entry-1", request, model);

        assertEquals(".EntryEdit", view);
        verify(listmonk).sendCampaign(eq(weblog.getNewsletterListUuid()),
                eq("Stored title"), anyString());
        assertTrue(entry.getNewsletterSentAt() != null,
                "a successful send must stamp newsletterSentAt");
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(entry);
        assertEquals(1, weblogger.flushCount());
        assertTrue(messages(model).contains("newsletter.sent"), messages(model).toString());
    }

    @Test
    void theHtmlSentCarriesTheTransformedTextAndThePermalink() throws Exception {
        WeblogEntry entry = publishedEntry();
        entry.setText("Some **markdown** body.");
        when(listmonk.isCampaignConfigured()).thenReturn(true);

        controller.entryEditSendNewsletter("entry-1", request, model);

        org.mockito.ArgumentCaptor<String> htmlCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(listmonk).sendCampaign(anyString(), anyString(), htmlCaptor.capture());
        String html = htmlCaptor.getValue();
        assertTrue(html.contains(weblogger.entryRenderer().transformedText(entry)),
                "the sent html must carry the rendered entry body: " + html);
        String permalink = weblogger.urlStrategy()
                .getWeblogEntryURL(entry.getWebsite(), null, entry.getAnchor(), true);
        assertTrue(html.contains(permalink),
                "the sent html must link back to the permalink built from the injected strategy: " + html);
    }

    // ---------------------------------------------------- cannot-double-send

    @Test
    void anAlreadyStampedEntryIsRefusedAndNeverTouchesTheClient() throws Exception {
        WeblogEntry entry = publishedEntry();
        Timestamp sentAt = new Timestamp(System.currentTimeMillis() - 60_000);
        entry.setNewsletterSentAt(sentAt);

        String view = controller.entryEditSendNewsletter("entry-1", request, model);

        assertEquals(".EntryEdit", view);
        assertTrue(errors(model).contains("newsletter.alreadySent"), errors(model).toString());
        verify(listmonk, never()).sendCampaign(any(), any(), any());
        assertEquals(sentAt, entry.getNewsletterSentAt(),
                "an already-sent entry's stamp must not be touched");
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    // -------------------------------------------------------------- guards

    @Test
    void aDraftEntryIsRefused() throws Exception {
        WeblogEntry entry = existingEntry(PubStatus.DRAFT);

        String view = controller.entryEditSendNewsletter("entry-1", request, model);

        assertEquals(".EntryEdit", view);
        assertTrue(errors(model).contains("newsletter.notPublished"), errors(model).toString());
        verify(listmonk, never()).sendCampaign(any(), any(), any());
        assertNull(entry.getNewsletterSentAt());
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    @Test
    void aWeblogWithNoListUuidIsRefused() throws Exception {
        weblog.setNewsletterListUuid(null);
        publishedEntry();

        String view = controller.entryEditSendNewsletter("entry-1", request, model);

        assertEquals(".EntryEdit", view);
        assertTrue(errors(model).contains("newsletter.noList"), errors(model).toString());
        verify(listmonk, never()).sendCampaign(any(), any(), any());
    }

    @Test
    void unconfiguredCampaignCredentialsAreRefused() throws Exception {
        publishedEntry();
        when(listmonk.isCampaignConfigured()).thenReturn(false);

        String view = controller.entryEditSendNewsletter("entry-1", request, model);

        assertEquals(".EntryEdit", view);
        assertTrue(errors(model).contains("newsletter.notConfigured"), errors(model).toString());
        verify(listmonk, never()).sendCampaign(any(), any(), any());
    }

    // -------------------------------------------------------------- failure

    @Test
    void aClientIOExceptionIsReportedAndLeavesTheStampUntouchedForRetry() throws Exception {
        WeblogEntry entry = publishedEntry();
        when(listmonk.isCampaignConfigured()).thenReturn(true);
        doThrow(new IOException("Listmonk error while starting the newsletter campaign: HTTP 500"))
                .when(listmonk).sendCampaign(any(), any(), any());

        String view = controller.entryEditSendNewsletter("entry-1", request, model);

        assertEquals(".EntryEdit", view);
        assertTrue(errors(model).stream().anyMatch(m -> m.contains("newsletter.sendFailed")
                        || m.contains("HTTP 500")),
                "expected the failure message to surface, got: " + errors(model));
        assertNull(entry.getNewsletterSentAt(),
                "a failed send must NOT stamp -- the button must stay available to retry");
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    @Test
    void theSendFailureMessageEscapesTheExceptionTextItQuotes() throws Exception {
        // The quoted text is a remote service's response, not this
        // application's own prose, and messages.jsp renders an error
        // unescaped -- so whatever Listmonk answers with is markup in the
        // admin's banner.
        registerMessage("newsletter.sendFailed", "failed:{0}");
        publishedEntry();
        when(listmonk.isCampaignConfigured()).thenReturn(true);
        doThrow(new IOException("<img src=x onerror=alert(1)>"))
                .when(listmonk).sendCampaign(any(), any(), any());

        controller.entryEditSendNewsletter("entry-1", request, model);

        assertTrue(errors(model).stream().anyMatch(m -> m.contains("&lt;img src=x onerror=alert(1)&gt;")),
                "expected the exception text HTML-escaped, got: " + errors(model));
        assertTrue(errors(model).stream().noneMatch(m -> m.contains("<img")),
                "the raw tag must not survive into the banner: " + errors(model));
    }

    /**
     * A successful Listmonk send followed by a save failure is NOT an    /**
     * A successful Listmonk send followed by a save failure is NOT an
     * ordinary, retry-inviting failure: the campaign already went out to
     * every subscriber, so the generic "something went wrong, try again"
     * message would be actively dangerous here -- it reads exactly like a
     * failure where nothing was sent, and the button reappearing invites a
     * real second send. This must get its own distinctive message, and
     * {@code sendCampaign} must never be called a second time to "fix" it.
     */
    @Test
    void aSaveFailureAfterASuccessfulSendGetsItsOwnMessageNotTheGenericOne() throws Exception {
        publishedEntry();
        when(listmonk.isCampaignConfigured()).thenReturn(true);
        doThrow(new WebloggerException("constraint violation"))
                .when(weblogger.getWeblogEntryManager()).saveWeblogEntry(any());

        String view = controller.entryEditSendNewsletter("entry-1", request, model);

        assertEquals(".EntryEdit", view);
        assertTrue(errors(model).stream().anyMatch(m -> m.contains("newsletter.sentButNotRecorded")),
                "expected the distinctive post-send-failure message, got: " + errors(model));
        assertFalse(errors(model).contains("generic.error.check.logs"),
                "the generic message is indistinguishable from an unsent failure and must not be used here");
        assertEquals(0, weblogger.flushCount(), "a failed save must not be committed");
        verify(listmonk, org.mockito.Mockito.times(1)).sendCampaign(any(), any(), any());
    }

    // -------------------------------------------------------------- wiring

    /**
     * Without an injected mock the controller falls back to
     * {@code ListmonkClient.fromConfig()}, exactly as production Spring
     * wiring would leave it on first use. The shipped default config ships
     * blank Listmonk credentials, so that real client reports itself
     * unconfigured and the send is refused -- proving the lazy-init seam
     * itself works, not just the mocked path every other test in this class
     * exercises.
     */
    @Test
    void withNoClientInjectedTheRealLazyDefaultIsUnconfiguredAndRefuses() throws Exception {
        EntryEditController uninjected = prepare(new EntryEditController());
        publishedEntry();

        String view = uninjected.entryEditSendNewsletter("entry-1", request, model);

        assertEquals(".EntryEdit", view);
        assertTrue(errors(model).contains("newsletter.notConfigured"), errors(model).toString());
    }

    // ------------------------------------------------------------- authority

    /**
     * The class-level gate is {@code WeblogPermission.EDIT_DRAFT}; mailing
     * every subscriber needs strictly more than that -- {@code POST} -- the
     * same distinction {@code setPublishStatus} draws for publishing itself.
     * An EDIT_DRAFT-only contributor must be denied before the entry is even
     * looked at, and the Listmonk client must never be touched.
     */
    @Test
    void editDraftOnlyUserIsDeniedAndNeverTouchesTheClient() throws Exception {
        when(weblogger.getUserManager().checkPermission(any(), any())).thenAnswer(invocation -> {
            Object permission = invocation.getArgument(0);
            if (permission instanceof WeblogPermission weblogPermission) {
                return !weblogPermission.hasAction(WeblogPermission.POST);
            }
            return false;
        });
        publishedEntry();

        String view = controller.entryEditSendNewsletter("entry-1", request, model);

        assertEquals("redirect:/pressroom-ui/access-denied.rol", view);
        verify(listmonk, never()).sendCampaign(any(), any(), any());
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    // -------------------------------------------------------------- ownership

    @Test
    void aForeignEntryIdIsDenied() throws Exception {
        WeblogEntry foreign = publishedEntry();
        com.wikantik.pressroom.pojos.Weblog other = new com.wikantik.pressroom.pojos.Weblog();
        other.setId("weblog-2");
        other.setHandle("otherblog");
        foreign.setWebsite(other);

        String view = controller.entryEditSendNewsletter("entry-1", request, model);

        assertEquals("redirect:/pressroom-ui/menu.rol", view,
                "an entryId from another weblog must bounce, exactly like an unknown one");
        verify(listmonk, never()).sendCampaign(any(), any(), any());
    }

    // --------------------------------------------------------------- helpers

    private WeblogEntry publishedEntry() throws WebloggerException {
        return existingEntry(PubStatus.PUBLISHED);
    }

    private WeblogEntry existingEntry(PubStatus status) throws WebloggerException {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setTitle("Stored title");
        entry.setWebsite(weblog);
        entry.setCategory(category);
        entry.setStatus(status);
        entry.setCreatorUserName(USER_NAME);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);
        return entry;
    }
}
