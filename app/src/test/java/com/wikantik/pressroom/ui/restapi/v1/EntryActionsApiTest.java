package com.wikantik.pressroom.ui.restapi.v1;

import java.io.IOException;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.EntryRenderer;
import com.wikantik.pressroom.business.ListmonkClient;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST .../entries/{id}/duplicate} and {@code .../send-newsletter}: the entries list's duplicate action and
 * the editor's "Send as newsletter", through the services both screens share
 * ({@code EntryDuplication}, {@code EntryNewsletter}). The JSP controllers' own tests pin the services'
 * behaviour; these pin what the routes make of it: the status, the Location, and each refusal as a problem.
 */
class EntryActionsApiTest {

    private static final String LIST_UUID = "2f0f1b0c-1111-2222-3333-444455556666";

    private final Weblogger weblogger = mock(Weblogger.class);
    private final WeblogEntryManager entries = mock(WeblogEntryManager.class);
    private final ListmonkClient listmonk = mock(ListmonkClient.class);
    private final Weblog weblog = new Weblog();
    private final EntryActionsApi api = new EntryActionsApi();
    private WeblogEntry original;

    @BeforeEach
    void setUp() throws Exception {
        weblog.setId("w1");
        weblog.setHandle("myblog");
        weblog.setNewsletterListUuid(LIST_UUID);
        URLStrategy urls = mock(URLStrategy.class);
        when(urls.getWeblogEntryURL(any(), any(), any(), anyBoolean())).thenReturn("https://x.test/myblog/entry/a");
        EntryRenderer renderer = mock(EntryRenderer.class);
        when(renderer.transformedText(any())).thenReturn("<p>Body</p>");
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(weblogger.getEntryRenderer()).thenReturn(renderer);
        api.weblogger = weblogger;
        api.setListmonkClient(listmonk);

        original = new WeblogEntry();
        original.setId("e1");
        original.setWebsite(weblog);
        original.setTitle("Rome &amp; Florence");
        original.setText("text");
        original.setAnchor("rome-and-florence");
        original.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        original.setCategory(new WeblogCategory(weblog, "Travel", null, null));
        when(entries.getWeblogEntry("e1")).thenReturn(original);
    }

    private ResultActions send(String path) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new ApiExceptionHandler()).build();
        User user = new User();
        user.setUserName("maiia");
        return mvc.perform(post("/v1/weblogs/myblog/entries/" + path)
                .requestAttr("actionWeblog", weblog).requestAttr("authenticatedUser", user)
                .contentType(MediaType.APPLICATION_JSON));
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return new ObjectMapper().readTree(result.andReturn().getResponse().getContentAsString());
    }

    // ------------------------------------------------------------ duplicate

    @Test
    void duplicatingSavesADraftCopyAndAnswers201WithItsLocation() throws Exception {
        ResultActions result = send("e1/duplicate").andExpect(status().isCreated());

        ArgumentCaptor<WeblogEntry> saved = ArgumentCaptor.forClass(WeblogEntry.class);
        verify(entries).saveWeblogEntry(saved.capture());
        WeblogEntry copy = saved.getValue();
        assertEquals("Copy of Rome &amp; Florence", copy.getTitle());
        assertEquals(WeblogEntry.PubStatus.DRAFT, copy.getStatus());
        assertNull(copy.getAnchor());
        assertFalse(copy.getId().equals("e1"));
        verify(weblogger).flush();
        assertEquals("http://localhost/v1/weblogs/myblog/entries/" + copy.getId(),
                result.andReturn().getResponse().getHeader("Location"));
        JsonNode body = json(result);
        assertEquals(copy.getId(), body.get("id").asString());
        assertEquals("Copy of Rome & Florence", body.get("title").asString(), "the view speaks plain text");
        assertEquals("DRAFT", body.get("status").asString());
        // The original is untouched.
        assertEquals(WeblogEntry.PubStatus.PUBLISHED, original.getStatus());
        assertEquals("rome-and-florence", original.getAnchor());
    }

    @Test
    void duplicatingATrashedEntryIsRefusedAndNothingIsSaved() throws Exception {
        original.setStatus(WeblogEntry.PubStatus.TRASHED);

        JsonNode problem = json(send("e1/duplicate").andExpect(status().isConflict()));

        assertEquals("entry.trashed", problem.get("code").asString());
        verify(entries, never()).saveWeblogEntry(any(WeblogEntry.class));
    }

    @Test
    void duplicatingAnotherWeblogsEntryOrAnUnknownIdIsA404() throws Exception {
        Weblog other = new Weblog();
        other.setId("w2");
        original.setWebsite(other);

        send("e1/duplicate").andExpect(status().isNotFound());
        send("nope/duplicate").andExpect(status().isNotFound());
        verify(entries, never()).saveWeblogEntry(any(WeblogEntry.class));
    }

    // ------------------------------------------------------ send-newsletter

    @Test
    void sendingAPublishedEntryMailsTheListStampsItAndAnswersTheEntry() throws Exception {
        when(listmonk.isCampaignConfigured()).thenReturn(true);

        ResultActions result = send("e1/send-newsletter").andExpect(status().isOk());

        verify(listmonk).sendCampaign(eq(LIST_UUID), eq("Rome &amp; Florence"), anyString());
        assertNotNull(original.getNewsletterSentAt());
        verify(entries).saveWeblogEntry(original);
        verify(weblogger).flush();
        JsonNode body = json(result);
        assertEquals("e1", body.get("id").asString());
        assertNotNull(body.get("newsletterSentAt"), "the view says when it went");
    }

    /** Carried over from the entry screen's newsletter test: the campaign is the rendered body plus a link back. */
    @Test
    void theHtmlSentCarriesTheTransformedTextAndThePermalink() throws Exception {
        when(listmonk.isCampaignConfigured()).thenReturn(true);

        send("e1/send-newsletter").andExpect(status().isOk());

        org.mockito.ArgumentCaptor<String> html = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(listmonk).sendCampaign(anyString(), anyString(), html.capture());
        org.junit.jupiter.api.Assertions.assertTrue(html.getValue().contains("<p>Body</p>"), html.getValue());
        org.junit.jupiter.api.Assertions.assertTrue(html.getValue().contains("https://x.test/myblog/entry/a"),
                html.getValue());
    }

    @Test
    void anEntryThatIsNotPublishedIsRefusedBeforeTheClientIsTouched() throws Exception {
        original.setStatus(WeblogEntry.PubStatus.DRAFT);

        JsonNode problem = json(send("e1/send-newsletter").andExpect(status().isConflict()));

        assertEquals("newsletter.notPublished", problem.get("code").asString());
        verify(listmonk, never()).sendCampaign(any(), any(), any());
    }

    @Test
    void anEntryAlreadySentIsRefusedAndNeverSentTwice() throws Exception {
        original.setNewsletterSentAt(new java.sql.Timestamp(1000L));

        JsonNode problem = json(send("e1/send-newsletter").andExpect(status().isConflict()));

        assertEquals("newsletter.alreadySent", problem.get("code").asString());
        verify(listmonk, never()).sendCampaign(any(), any(), any());
    }

    @Test
    void aWeblogWithNoListAndASiteWithNoCredentialsAreEachRefused() throws Exception {
        weblog.setNewsletterListUuid(" ");
        assertEquals("newsletter.noList",
                json(send("e1/send-newsletter").andExpect(status().isConflict())).get("code").asString());

        weblog.setNewsletterListUuid(LIST_UUID);
        when(listmonk.isCampaignConfigured()).thenReturn(false);
        assertEquals("newsletter.notConfigured",
                json(send("e1/send-newsletter").andExpect(status().isConflict())).get("code").asString());
        verify(listmonk, never()).sendCampaign(any(), any(), any());
    }

    @Test
    void aFailedSendIsA502LeavingNothingStampedSoItCanBeRetried() throws Exception {
        when(listmonk.isCampaignConfigured()).thenReturn(true);
        doThrow(new IOException("listmonk is down")).when(listmonk).sendCampaign(any(), any(), any());

        JsonNode problem = json(send("e1/send-newsletter").andExpect(status().isBadGateway()));

        assertEquals("newsletter.sendFailed", problem.get("code").asString());
        assertTrue(problem.get("detail").asString().contains("listmonk is down"), problem.toString());
        assertNull(original.getNewsletterSentAt());
        verify(entries, never()).saveWeblogEntry(any(WeblogEntry.class));
    }

    @Test
    void aSendThatWentButCouldNotBeRecordedSaysNotToSendAgain() throws Exception {
        when(listmonk.isCampaignConfigured()).thenReturn(true);
        doThrow(new WebloggerException("db gone")).when(weblogger).flush();

        JsonNode problem = json(send("e1/send-newsletter").andExpect(status().isConflict()));

        assertEquals("newsletter.sentButNotRecorded", problem.get("code").asString());
        assertTrue(problem.get("detail").asString().contains("Do NOT send again"), problem.toString());
        verify(listmonk).sendCampaign(any(), any(), any());
    }

    @Test
    void bothRoutesAreAPublishersAsTheJspScreensAre() {
        assertEquals(List.of(WeblogPermission.POST), api.requiredWeblogPermissionActions());
    }

    @Test
    void theListmonkClientIsBuiltOnFirstUseWhenNoneWasInjected() {
        assertNotNull(new EntryActionsApi().listmonkClient());
    }

    @Test
    void aFailureWithNoMessageStillAnswersWithoutTheWordNull() throws Exception {
        when(listmonk.isCampaignConfigured()).thenReturn(true);
        doThrow(new IOException()).when(listmonk).sendCampaign(any(), any(), any());

        JsonNode problem = json(send("e1/send-newsletter").andExpect(status().isBadGateway()));

        assertEquals("newsletter.sendFailed", problem.get("code").asString());
        assertFalse(problem.get("detail").asString().contains("null"), problem.toString());
    }

    @Test
    void theRoutesNeedASignedInUserAndNoGlobalPermission() {
        assertTrue(api.isUserRequired());
        assertTrue(api.isWeblogRequired());
        assertEquals(List.of(), api.requiredGlobalPermissionActions());
    }
}
