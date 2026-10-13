package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Anchors chosen at create (spec 2026-10-13, section 2; criteria 6-9). */
class EntriesWriteApiAnchorTest {

    private EntriesWriteApiFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new EntriesWriteApiFixture();
    }

    private WeblogEntry holder(WeblogEntry.PubStatus status) {
        WeblogEntry holder = new WeblogEntry();
        holder.setId("holder");
        holder.setWebsite(fx.weblog);
        holder.setAnchor("torrey-pines");
        holder.setStatus(status);
        return holder;
    }

    @Test
    void aChosenAnchorIsStoredAndThePermalinkEndsWithIt() throws Exception {
        when(fx.urls.getWeblogEntryURL(fx.weblog, null, "torrey-pines", true))
                .thenReturn("https://blog.example.com/myblog/entry/torrey-pines");
        ArgumentCaptor<WeblogEntry> saved = ArgumentCaptor.forClass(WeblogEntry.class);

        JsonNode view = EntriesWriteApiFixture.json(fx.send(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"Torrey Pines State Natural Reserve\",\"text\":\"x\",\"anchor\":\"torrey-pines\"}"))
                .andExpect(status().isCreated()));

        verify(fx.entries).saveWeblogEntry(saved.capture());
        assertEquals("torrey-pines", saved.getValue().getAnchor());
        assertTrue(view.get("permalink").asString().endsWith("/torrey-pines"));
    }

    @Test
    void withoutAnAnchorTheManagerGeneratesOneAsBefore() throws Exception {
        ArgumentCaptor<WeblogEntry> saved = ArgumentCaptor.forClass(WeblogEntry.class);

        fx.send(post("/v1/weblogs/myblog/entries").content("{\"title\":\"t\",\"text\":\"x\"}"))
                .andExpect(status().isCreated());

        verify(fx.entries).saveWeblogEntry(saved.capture());
        assertNull(saved.getValue().getAnchor());
        verify(fx.entries, never()).getWeblogEntryByAnchor(any(), any());
    }

    @Test
    void aMalformedAnchorIsA400BeforeAnyLookup() throws Exception {
        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"anchor\":\"Torrey Pines\"}"));

        assertEquals(List.of("anchor"), EntriesWriteApiFixture.fields(problem));
        verify(fx.entries, never()).getWeblogEntryByAnchor(any(), any());
        verify(fx.entries, never()).saveWeblogEntry(any());
    }

    @Test
    void anAnchorALiveEntryHoldsIsA409() throws Exception {
        when(fx.entries.getWeblogEntryByAnchor(fx.weblog, "torrey-pines"))
                .thenReturn(holder(WeblogEntry.PubStatus.PUBLISHED));

        JsonNode problem = EntriesWriteApiFixture.json(fx.send(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"anchor\":\"torrey-pines\"}")).andExpect(status().isConflict()));

        assertEquals(List.of("anchor"), EntriesWriteApiFixture.fields(problem));
        assertEquals("anchor 'torrey-pines' is already used by another entry.", problem.get("detail").asString());
        verify(fx.entries, never()).saveWeblogEntry(any());
    }

    @Test
    void anAnchorATrashedEntryHoldsIsA409ThatSaysHowToFreeIt() throws Exception {
        when(fx.entries.getWeblogEntryByAnchor(fx.weblog, "torrey-pines"))
                .thenReturn(holder(WeblogEntry.PubStatus.TRASHED));

        JsonNode problem = EntriesWriteApiFixture.json(fx.send(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"anchor\":\"torrey-pines\"}")).andExpect(status().isConflict()));

        assertTrue(problem.get("detail").asString().contains("in the trash"));
        assertTrue(problem.get("detail").asString().contains("delete it forever"));
    }

    @Test
    void theConflictIsReportedOnlyOnceEveryOtherFieldIsValid() throws Exception {
        when(fx.entries.getWeblogEntryByAnchor(fx.weblog, "torrey-pines"))
                .thenReturn(holder(WeblogEntry.PubStatus.PUBLISHED));

        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"anchor\":\"torrey-pines\",\"category\":\"Travle\"}"));

        assertEquals(List.of("category"), EntriesWriteApiFixture.fields(problem));
        verify(fx.entries, never()).getWeblogEntryByAnchor(any(), any());
    }

    private WeblogEntry stored() throws Exception {
        WeblogEntry stored = new WeblogEntry();
        stored.setId("e1");
        stored.setWebsite(fx.weblog);
        stored.setTitle("Torrey Pines");
        stored.setAnchor("torrey-pines");
        stored.setStatus(WeblogEntry.PubStatus.DRAFT);
        when(fx.entries.getWeblogEntry("e1")).thenReturn(stored);
        return stored;
    }

    /** Review Focus 1: an entry sent back as it was read carries its own anchor. */
    @Test
    void aPatchCarryingTheStoredAnchorSucceeds() throws Exception {
        WeblogEntry stored = stored();

        fx.send(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"anchor\":\"torrey-pines\",\"summary\":\"new\"}")).andExpect(status().isOk());

        assertEquals("new", stored.getSummary());
        assertEquals("torrey-pines", stored.getAnchor());
    }

    @Test
    void aPatchCarryingAnotherAnchorIsA400AndChangesNothing() throws Exception {
        WeblogEntry stored = stored();

        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"anchor\":\"torrey\",\"summary\":\"new\"}"));

        assertEquals(List.of("anchor"), EntriesWriteApiFixture.fields(problem));
        assertEquals("torrey-pines", stored.getAnchor());
        assertNull(stored.getSummary());
        verify(fx.entries, never()).saveWeblogEntry(any());
    }
}
