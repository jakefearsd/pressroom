package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryRevision;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.apache.roller.weblogger.ui.restapi.v1.EntriesWriteApiFixture.fields;
import static org.apache.roller.weblogger.ui.restapi.v1.EntriesWriteApiFixture.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code revisionNote} on entry writes and {@code POST .../revisions/{revisionId}/restore}
 * (spec 2026-10-24, section 5), against a mocked tier. The manager records a
 * revision inside the save, so what is checked here is which save is called
 * and with what: an update saves through the three-argument form (editor and
 * note), a revision restore through the two-argument one, and "nothing saved"
 * checks all three forms.
 */
class EntriesWriteApiRevisionsTest {

    private static final String RESTORE = "/v1/weblogs/myblog/entries/e1/revisions/r1/restore";

    private EntriesWriteApiFixture fx;
    private WeblogEntry stored;
    private WeblogEntryRevision revision;

    @BeforeEach
    void setUp() throws Exception {
        fx = new EntriesWriteApiFixture();
        stored = new WeblogEntry();
        stored.setId("e1");
        stored.setWebsite(fx.weblog);
        stored.setTitle("Harbour, now");
        stored.setSummary("Now.");
        stored.setText("The text now.");
        stored.setStatus(WeblogEntry.PubStatus.DRAFT);
        when(fx.entries.getWeblogEntry("e1")).thenReturn(stored);

        revision = new WeblogEntryRevision();
        revision.setId("r1");
        revision.setWeblogEntry(stored);
        revision.setTitle("Harbour, then");
        revision.setSummary("Then.");
        revision.setText("The text then.");
        when(fx.entries.getRevision("r1")).thenReturn(revision);
    }

    // ------------------------------------------------------------ revisionNote

    @Test
    void anUpdatesNoteGoesToTheSaveThatLeavesTheRevision() throws Exception {
        String note = "n".repeat(255);
        fx.send(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"text\":\"New text.\",\"revisionNote\":\"" + note + "\"}")).andExpect(status().isOk());

        verify(fx.entries).saveWeblogEntry(eq(stored), argThat((User u) -> "maiia".equals(u.getUserName())),
                eq(note));
    }

    @Test
    void anUpdateWithoutANoteSavesWithNone() throws Exception {
        fx.send(patch("/v1/weblogs/myblog/entries/e1").content("{\"text\":\"New text.\"}"))
                .andExpect(status().isOk());

        verify(fx.entries).saveWeblogEntry(eq(stored), any(User.class), isNull());
    }

    /** A blank note says nothing, so it is no note. */
    @Test
    void aBlankNoteIsNoNote() throws Exception {
        fx.send(patch("/v1/weblogs/myblog/entries/e1").content("{\"text\":\"New text.\",\"revisionNote\":\"  \"}"))
                .andExpect(status().isOk());

        verify(fx.entries).saveWeblogEntry(eq(stored), any(User.class), isNull());
    }

    @Test
    void aNoteOver255CharactersIsA400AndNothingIsSaved() throws Exception {
        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"text\":\"New text.\",\"revisionNote\":\"" + "n".repeat(256) + "\"}"));

        assertEquals(List.of("revisionNote"), fields(problem));
        assertEquals("The text now.", stored.getText());
        nothingSaved();
    }

    /** A create replaces nothing, so it leaves no revision for a note to label. */
    @Test
    void aNoteOnACreateIsA400() throws Exception {
        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"T\",\"text\":\"x\",\"revisionNote\":\"First draft\"}"));

        assertEquals(List.of("revisionNote"), fields(problem));
        nothingSaved();
    }

    // ------------------------------------------------------------ restore

    @Test
    void aRestorePutsTheSnapshotBackThroughTheEditorsSave() throws Exception {
        JsonNode view = json(fx.send(post(RESTORE)).andExpect(status().isOk()));

        assertEquals("Harbour, then", stored.getTitle());
        assertEquals("Then.", stored.getSummary());
        assertEquals("The text then.", stored.getText());
        assertEquals("The text then.", view.get("text").asString());
        verify(fx.entries).saveWeblogEntry(eq(stored), argThat((User u) -> "maiia".equals(u.getUserName())));
        verify(fx.index).removeEntryIndexOperation(stored);
    }

    @Test
    void aRestoreWithAStaleContentHashIs409AndSavesNothing() throws Exception {
        JsonNode problem = json(fx.send(post(RESTORE).content(
                "{\"expectedContentHash\":\"" + "0".repeat(64) + "\"}")).andExpect(status().isConflict()));

        assertTrue(problem.get("type").asString().endsWith("/entry-changed"), problem.toString());
        assertEquals(EntryDtos.contentHash("The text now."), problem.get("currentContentHash").asString());
        assertEquals("The text now.", stored.getText());
        nothingSaved();
    }

    @Test
    void aRestoreWithTheCurrentContentHashRestores() throws Exception {
        fx.send(post(RESTORE).content(
                "{\"expectedContentHash\":\"" + EntryDtos.contentHash("The text now.") + "\"}"))
                .andExpect(status().isOk());

        assertEquals("The text then.", stored.getText());
    }

    @Test
    void aRestoreHonoursFields() throws Exception {
        JsonNode view = json(fx.send(post(RESTORE + "?fields=title")).andExpect(status().isOk()));

        assertEquals(List.of("id", "title"), List.copyOf(view.propertyNames()));
        assertEquals("Harbour, then", view.get("title").asString());
    }

    @Test
    void aRevisionOfAnotherEntryIsNotFoundAndNothingIsRestored() throws Exception {
        WeblogEntry other = new WeblogEntry();
        other.setId("e2");
        other.setWebsite(fx.weblog);
        revision.setWeblogEntry(other);

        fx.send(post(RESTORE)).andExpect(status().isNotFound());

        assertEquals("The text now.", stored.getText());
        nothingSaved();
    }

    @Test
    void anUnknownRevisionIsNotFound() throws Exception {
        fx.send(post("/v1/weblogs/myblog/entries/e1/revisions/nope/restore")).andExpect(status().isNotFound());
        nothingSaved();
    }

    /** As an update: a trashed entry is restored from the trash first, never edited there. */
    @Test
    void aTrashedEntrysRevisionIsNotRestored() throws Exception {
        stored.setStatus(WeblogEntry.PubStatus.TRASHED);

        fx.send(post(RESTORE)).andExpect(status().isConflict());

        assertEquals("The text now.", stored.getText());
        nothingSaved();
    }

    private void nothingSaved() throws Exception {
        verify(fx.entries, never()).saveWeblogEntry(any());
        verify(fx.entries, never()).saveWeblogEntry(any(), any());
        verify(fx.entries, never()).saveWeblogEntry(any(), any(), any());
    }
}
