package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.fetch.FetchedImage;
import com.wikantik.pressroom.business.fetch.ImageKind;
import com.wikantik.pressroom.business.fetch.RemoteImageFetcher;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogEntryRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.jackson.databind.JsonNode;

import static com.wikantik.pressroom.ui.restapi.v1.EntriesWriteApiFixture.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
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

    /** As the editor's Publish with a blank time: submitted for review, not a 400 for the missing pubTime. */
    @Test
    void aCreateAskingToScheduleWithNoPubTimeIsSubmittedForReview() throws Exception {
        JsonNode created = json(fx.send(post(ENTRIES)
                        .content("{\"title\":\"T\",\"text\":\"x\",\"status\":\"SCHEDULED\"}"))
                .andExpect(status().isCreated()));

        assertEquals("PENDING", created.get("status").asString());
    }

    // ------------------------------------------------------------ images from other sites (fix round 1)

    private static final String FAR_IMAGE = "https://img.example.com/pier.jpg";
    private static final String TEXT_WITH_FAR_IMAGE = "A pier. ![pier](" + FAR_IMAGE + ")";

    /**
     * Stands up an import that would succeed: uploads on, the image served,
     * a media library to store it in. A contributor must still fetch nothing:
     * adding media takes POST on the JSP side, and the editor's save imports
     * nothing.
     */
    private RemoteImageFetcher importsWouldSucceed() throws Exception {
        RemoteImageFetcher fetcher = org.mockito.Mockito.mock(RemoteImageFetcher.class);
        fx.controller.imageFetcher = fetcher;
        MediaFileManager media = org.mockito.Mockito.mock(MediaFileManager.class);
        when(fx.weblogger.getMediaFileManager()).thenReturn(media);
        when(media.getDefaultMediaFileDirectory(fx.weblog))
                .thenReturn(new MediaFileDirectory(fx.weblog, "default", null));
        when(fx.urls.getWeblogURL(fx.weblog, null, true)).thenReturn("https://blog.example.com/myblog/");
        when(fx.urls.getMediaFileURL(eq(fx.weblog), any(String.class), eq(true)))
                .thenAnswer(inv -> "https://blog.example.com/myblog/mediaresource/" + inv.getArgument(1));
        when(fetcher.fetch(eq(FAR_IMAGE), anyLong(), any())).thenReturn(new FetchedImage("pier.jpg", ImageKind.JPEG,
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3}));
        return fetcher;
    }

    private static MockedStatic<WebloggerRuntimeConfig> uploadsOn() {
        MockedStatic<WebloggerRuntimeConfig> runtime = mockStatic(WebloggerRuntimeConfig.class);
        runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")).thenReturn(true);
        runtime.when(() -> WebloggerRuntimeConfig.getProperty("uploads.file.maxsize")).thenReturn("2.00");
        runtime.when(WebloggerRuntimeConfig::getAbsoluteContextURL).thenReturn("https://blog.example.com");
        return runtime;
    }

    /** The default import is a no-op for a contributor: their text keeps linking the other site's image. */
    @Test
    void aContributorsTextKeepsExternalImagesLinkedAndNothingIsFetched() throws Exception {
        MockedStatic<WebloggerRuntimeConfig> runtime = uploadsOn();
        try {
            RemoteImageFetcher fetcher = importsWouldSucceed();
            stored("maiia", PubStatus.DRAFT);

            JsonNode created = json(fx.send(post(ENTRIES).content("{\"title\":\"T\",\"text\":\""
                    + TEXT_WITH_FAR_IMAGE + "\"}")).andExpect(status().isCreated()));
            JsonNode updated = json(fx.send(patch(ENTRIES + "/e1").content("{\"text\":\""
                    + TEXT_WITH_FAR_IMAGE + "\"}")).andExpect(status().isOk()));

            assertEquals(TEXT_WITH_FAR_IMAGE, created.get("text").asString());
            assertEquals(TEXT_WITH_FAR_IMAGE, updated.get("text").asString());
            verify(fetcher, never()).fetch(any(), anyLong(), any());
        } finally {
            runtime.close();
        }
    }

    /** Asking to import is asking to add media: refused, naming the field, before anything is fetched. */
    @Test
    void aContributorAskingToImportIsRefusedNamingTheField() throws Exception {
        MockedStatic<WebloggerRuntimeConfig> runtime = uploadsOn();
        try {
            RemoteImageFetcher fetcher = importsWouldSucceed();
            stored("maiia", PubStatus.DRAFT);
            String featured = "\"featuredImageUrl\":\"" + FAR_IMAGE + "\"";
            String explicit = "\"importExternalImages\":true";

            for (String[] write : List.of(
                    new String[] {"featuredImageUrl", "{\"title\":\"T\",\"text\":\"x\"," + featured + "}"},
                    new String[] {"importExternalImages", "{\"title\":\"T\",\"text\":\""
                            + TEXT_WITH_FAR_IMAGE + "\"," + explicit + "}"})) {
                JsonNode created = forbidden(post(ENTRIES).content(write[1]));
                assertEquals("entry.needsPublishRights", created.get("code").asString(), write[0]);
                assertEquals(write[0], created.get("errors").get(0).get("field").asString());
            }
            for (String[] write : List.of(new String[] {"featuredImageUrl", "{" + featured + "}"},
                    new String[] {"importExternalImages", "{" + explicit + "}"})) {
                JsonNode updated = forbidden(patch(ENTRIES + "/e1").content(write[1]));
                assertEquals("entry.needsPublishRights", updated.get("code").asString(), write[0]);
                assertEquals(write[0], updated.get("errors").get(0).get("field").asString());
            }
            verify(fetcher, never()).fetch(any(), anyLong(), any());
            nothingSaved();
        } finally {
            runtime.close();
        }
    }

    /** Saying no to the import changes nothing a contributor may not change. */
    @Test
    void aContributorMayTurnTheImportOff() throws Exception {
        fx.send(post(ENTRIES).content("{\"title\":\"T\",\"text\":\"" + TEXT_WITH_FAR_IMAGE
                + "\",\"importExternalImages\":false}")).andExpect(status().isCreated());
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
