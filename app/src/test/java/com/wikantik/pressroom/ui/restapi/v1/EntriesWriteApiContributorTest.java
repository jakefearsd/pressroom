package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogEntryRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static com.wikantik.pressroom.ui.restapi.v1.EntriesWriteApiFixture.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A contributor (weblog EDIT_DRAFT, no POST) writes over the API what the
 * JSP entry editor lets them write, and no more (spec 2026-11-21 owner
 * decisions, Task 3). The interceptor lets them reach every route of {@code
 * EntriesWriteApi}; the handler holds them to the editor's rules
 * ({@code ContributorRules}): a publish or a schedule is submitted for review
 * (PENDING), they change only their own drafts and pending entries, and
 * pinning or a strand stays a publisher's. The caller is the fixture's
 * {@code maiia}, with {@code mayPost} false.
 */
class EntriesWriteApiContributorTest {

    private static final String ENTRIES = "/v1/weblogs/myblog/entries";
    private static final String NEXT_WEEK = java.time.LocalDateTime.now().plusDays(7)
            .withNano(0).withSecond(0).toString();

    private EntriesWriteApiFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new EntriesWriteApiFixture();
        fx.mayPost = false;
    }

    private WeblogEntry stored(String creator, PubStatus status) throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("e1");
        entry.setWebsite(fx.weblog);
        entry.setCreatorUserName(creator);
        entry.setTitle("Stored");
        entry.setText("Stored text.");
        entry.setStatus(status);
        when(fx.entries.getWeblogEntry("e1")).thenReturn(entry);
        return entry;
    }

    private JsonNode forbidden(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return json(fx.send(request).andExpect(status().isForbidden()));
    }

    private void nothingSaved() throws Exception {
        verify(fx.entries, never()).saveWeblogEntry(any());
        verify(fx.entries, never()).saveWeblogEntry(any(), any());
        verify(fx.entries, never()).saveWeblogEntry(any(), any(), any());
    }

    // ------------------------------------------------------------ create

    @Test
    void aCreateWithNoStatusIsADraft() throws Exception {
        JsonNode created = json(fx.send(post(ENTRIES).content("{\"title\":\"T\",\"text\":\"x\"}"))
                .andExpect(status().isCreated()));

        assertEquals("DRAFT", created.get("status").asString());
    }

    @Test
    void aCreateAskingToPublishIsSubmittedForReview() throws Exception {
        JsonNode created = json(fx.send(post(ENTRIES)
                        .content("{\"title\":\"T\",\"text\":\"x\",\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isCreated()));

        assertEquals("PENDING", created.get("status").asString());
    }

    @Test
    void aCreateAskingToScheduleIsSubmittedForReview() throws Exception {
        JsonNode created = json(fx.send(post(ENTRIES).content("{\"title\":\"T\",\"text\":\"x\","
                        + "\"status\":\"SCHEDULED\",\"pubTime\":\"" + NEXT_WEEK + "\"}"))
                .andExpect(status().isCreated()));

        assertEquals("PENDING", created.get("status").asString());
    }

    /** As the editor's Save draft: a date on a draft is only a date. */
    @Test
    void aDraftWithAFutureDateStaysADraft() throws Exception {
        JsonNode created = json(fx.send(post(ENTRIES).content("{\"title\":\"T\",\"text\":\"x\","
                        + "\"status\":\"DRAFT\",\"pubTime\":\"" + NEXT_WEEK + "\"}"))
                .andExpect(status().isCreated()));

        assertEquals("DRAFT", created.get("status").asString());
    }

    @Test
    void aCreateThatPinsIsRefusedAndNothingSaved() throws Exception {
        JsonNode problem = forbidden(post(ENTRIES).content("{\"title\":\"T\",\"text\":\"x\",\"pinned\":true}"));

        assertEquals("entry.needsPublishRights", problem.get("code").asString());
        assertEquals("pinned", problem.get("errors").get(0).get("field").asString());
        nothingSaved();
    }

    @Test
    void aCreateThatJoinsAStrandIsRefusedAndNothingSaved() throws Exception {
        JsonNode problem = forbidden(post(ENTRIES).content("{\"title\":\"T\",\"text\":\"x\",\"strand\":\"s1\"}"));

        assertEquals("entry.needsPublishRights", problem.get("code").asString());
        assertEquals("strand", problem.get("errors").get(0).get("field").asString());
        nothingSaved();
    }

    /** A view read back and sent again carries pinned:false; changing nothing is no refusal. */
    @Test
    void sendingTheUnchangedPinIsNoRefusal() throws Exception {
        fx.send(post(ENTRIES).content("{\"title\":\"T\",\"text\":\"x\",\"pinned\":false}"))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------ update

    @Test
    void anUpdateOfTheirOwnDraftAskingToPublishIsSubmittedForReview() throws Exception {
        stored("maiia", PubStatus.DRAFT);

        JsonNode updated = json(fx.send(patch(ENTRIES + "/e1").content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk()));

        assertEquals("PENDING", updated.get("status").asString());
    }

    @Test
    void anUpdateOfTheirOwnPendingEntryMayTakeItBackToDraft() throws Exception {
        stored("maiia", PubStatus.PENDING);

        JsonNode updated = json(fx.send(patch(ENTRIES + "/e1").content("{\"status\":\"DRAFT\",\"text\":\"New\"}"))
                .andExpect(status().isOk()));

        assertEquals("DRAFT", updated.get("status").asString());
    }

    @Test
    void anUpdateOfSomeoneElsesDraftIsRefusedAndNothingSaved() throws Exception {
        WeblogEntry entry = stored("someone-else", PubStatus.DRAFT);

        JsonNode problem = forbidden(patch(ENTRIES + "/e1").content("{\"text\":\"Mine now\"}"));

        assertEquals("entry.notChangeable", problem.get("code").asString());
        assertEquals("Stored text.", entry.getText());
        nothingSaved();
    }

    @Test
    void anUpdateOfTheirOwnPublishedEntryIsRefusedAndNothingSaved() throws Exception {
        WeblogEntry entry = stored("maiia", PubStatus.PUBLISHED);

        JsonNode problem = forbidden(patch(ENTRIES + "/e1").content("{\"status\":\"DRAFT\"}"));

        assertEquals("entry.notChangeable", problem.get("code").asString());
        assertEquals(PubStatus.PUBLISHED, entry.getStatus());
        nothingSaved();
    }

    @Test
    void anUpdateThatPinsTheirDraftIsRefused() throws Exception {
        stored("maiia", PubStatus.DRAFT);

        JsonNode problem = forbidden(patch(ENTRIES + "/e1").content("{\"pinned\":true}"));

        assertEquals("pinned", problem.get("errors").get(0).get("field").asString());
        nothingSaved();
    }

    @Test
    void anUpdateTakingTheirDraftOutOfItsStrandIsRefused() throws Exception {
        stored("maiia", PubStatus.DRAFT).setStrandPageId("s1");

        JsonNode problem = forbidden(patch(ENTRIES + "/e1").content("{\"clear\":[\"strand\"]}"));

        assertEquals("strand", problem.get("errors").get(0).get("field").asString());
        nothingSaved();
    }

    /** Re-sending the strand the entry is already in changes nothing, as for a publisher. */
    @Test
    void reSendingTheirDraftsOwnStrandIsNoRefusal() throws Exception {
        stored("maiia", PubStatus.DRAFT).setStrandPageId("s1");

        fx.send(patch(ENTRIES + "/e1").content("{\"strand\":\"s1\",\"text\":\"New\"}")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------ trash

    @Test
    void theyMayTrashTheirOwnDraft() throws Exception {
        stored("maiia", PubStatus.DRAFT);

        fx.send(delete(ENTRIES + "/e1")).andExpect(status().isNoContent());

        verify(fx.entries).trashWeblogEntry(any());
    }

    @Test
    void theyMayNotTrashAPublishedEntry() throws Exception {
        stored("maiia", PubStatus.PUBLISHED);

        assertEquals("entry.notChangeable", forbidden(delete(ENTRIES + "/e1")).get("code").asString());
        verify(fx.entries, never()).trashWeblogEntry(any());
    }

    // ------------------------------------------------------------ revisions

    private WeblogEntryRevision revisionOf(WeblogEntry entry) throws Exception {
        WeblogEntryRevision revision = new WeblogEntryRevision();
        revision.setId("r1");
        revision.setWeblogEntry(entry);
        revision.setTitle("Then");
        revision.setText("Then text.");
        when(fx.entries.getRevision("r1")).thenReturn(revision);
        return revision;
    }

    @Test
    void theyMayNotRestoreARevisionOntoSomeoneElsesEntry() throws Exception {
        revisionOf(stored("someone-else", PubStatus.PENDING));

        assertEquals("entry.notChangeable",
                forbidden(post(ENTRIES + "/e1/revisions/r1/restore")).get("code").asString());
        verify(fx.entries, never()).restoreRevision(any(), any(), any());
    }

    @Test
    void theyMayRestoreARevisionOntoTheirOwnDraft() throws Exception {
        revisionOf(stored("maiia", PubStatus.DRAFT));

        fx.send(post(ENTRIES + "/e1/revisions/r1/restore")).andExpect(status().isOk());

        verify(fx.entries).restoreRevision(any(), any(), any(User.class));
    }

    @Test
    void theyMayNotKeepOrReleaseARevisionOfAPublishedEntry() throws Exception {
        revisionOf(stored("maiia", PubStatus.PUBLISHED));

        for (String action : List.of("keep", "unkeep")) {
            assertEquals("entry.notChangeable",
                    forbidden(post(ENTRIES + "/e1/revisions/r1/" + action)).get("code").asString(), action);
        }
        verify(fx.entries, never()).setRevisionKept(any(), anyBoolean());
    }

    // ------------------------------------------------------------ preview

    /** As the editor's preview: rendering text changes nothing, so any entry of the weblog will do. */
    @Test
    void theyPreviewAgainstAnEntryTheyCannotChange() throws Exception {
        stored("someone-else", PubStatus.PUBLISHED);
        when(fx.weblogger.getEntryRenderer()).thenReturn(new com.wikantik.pressroom.business.EntryRenderer(
                com.wikantik.pressroom.business.shortcodes.ShortcodeExpander.builtIn(fx.weblogger,
                        org.mockito.Mockito.mock(com.wikantik.pressroom.business.MediaFileManager.class)),
                org.mockito.Mockito.mock(com.wikantik.pressroom.business.plugins.PluginManager.class)));

        JsonNode preview = json(fx.send(post(ENTRIES + "/e1/preview").content("{\"text\":\"**Mine**\"}"))
                .andExpect(status().isOk()));

        assertEquals(true, preview.get("html").asString().contains("<strong>Mine</strong>"));
        nothingSaved();
    }
}
