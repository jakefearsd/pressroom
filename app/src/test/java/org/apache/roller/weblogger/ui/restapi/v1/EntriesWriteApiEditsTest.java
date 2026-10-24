package org.apache.roller.weblogger.ui.restapi.v1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.apache.roller.weblogger.business.EntryRenderer;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

import static org.apache.roller.weblogger.ui.restapi.v1.EntriesWriteApiFixture.fields;
import static org.apache.roller.weblogger.ui.restapi.v1.EntriesWriteApiFixture.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code edits} and {@code contentHash}/{@code expectedContentHash} on entry
 * writes (spec 2026-10-24, sections 3 and 4; acceptance criteria 3 and 4).
 * The tier is mocked, so "one revision written" is one saveWeblogEntry call:
 * the manager records the revision inside it.
 */
class EntriesWriteApiEditsTest {

    private static final String STORED = "The pier. The beach. The beach.";

    private EntriesWriteApiFixture fx;
    private WeblogEntry stored;

    @BeforeEach
    void setUp() throws Exception {
        fx = new EntriesWriteApiFixture();
        stored = new WeblogEntry();
        stored.setId("e1");
        stored.setWebsite(fx.weblog);
        stored.setTitle("Harbour");
        stored.setText(STORED);
        stored.setStatus(WeblogEntry.PubStatus.DRAFT);
        when(fx.entries.getWeblogEntry("e1")).thenReturn(stored);
    }

    /** An oracle independent of the code under test. */
    private static String sha256Hex(String text) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions patchEntry(String body) throws Exception {
        return fx.send(patch("/v1/weblogs/myblog/entries/e1").content(body));
    }

    private static String edits(String... findReplace) {
        StringBuilder json = new StringBuilder("\"edits\":[");
        for (int i = 0; i < findReplace.length; i += 2) {
            json.append(i == 0 ? "" : ",").append("{\"find\":\"").append(findReplace[i])
                    .append("\",\"replace\":\"").append(findReplace[i + 1]).append("\"}");
        }
        return json.append(']').toString();
    }

    // ------------------------------------------------------------ criterion 3

    @Test
    void twoEditsOneMatchingTwiceIsOne400OnTheSecondAndChangesNothing() throws Exception {
        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e1").content(
                "{" + edits("The pier.", "The jetty.", "The beach.", "The sand.") + "}"));

        assertEquals(List.of("edits[1]"), fields(problem));
        assertEquals("edits[1] found 2 times; include more surrounding text so it matches once.",
                problem.get("errors").get(0).get("message").asString());
        assertFalse(problem.has("currentContentHash"), "only entry-changed carries the extension member");
        assertEquals(STORED, stored.getText());
        verify(fx.entries, never()).saveWeblogEntry(any());
    }

    @Test
    void twoEditsMatchingOnceAreAppliedInOrderAndSavedAsOneRevision() throws Exception {
        JsonNode view = json(patchEntry("{" + edits("The pier.", "The jetty.", "jetty. The beach. The",
                "jetty, then the beach, then the") + "}").andExpect(status().isOk()));

        String expected = "The jetty, then the beach, then the beach.";
        assertEquals(expected, stored.getText());
        assertEquals(expected, view.get("text").asString());
        verify(fx.entries, times(1)).saveWeblogEntry(stored);
    }

    @Test
    void editsWithTextAreA400AndSaveNothing() throws Exception {
        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"text\":\"whole\"," + edits("The pier.", "The jetty.") + "}"));

        assertEquals(List.of("edits"), fields(problem));
        assertEquals(STORED, stored.getText());
        verify(fx.entries, never()).saveWeblogEntry(any());
    }

    @Test
    void editsOnCreateAreA400() throws Exception {
        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\"," + edits("x", "y") + "}"));

        assertEquals(List.of("edits"), fields(problem));
        verify(fx.entries, never()).saveWeblogEntry(any());
    }

    @Test
    void fiftyOneEditsAreA400() throws Exception {
        String[] many = new String[(EntryEdits.MAX_EDITS + 1) * 2];
        for (int i = 0; i < many.length; i++) {
            many[i] = "The pier.";
        }
        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e1").content("{" + edits(many) + "}"));

        assertEquals(List.of("edits"), fields(problem));
        verify(fx.entries, never()).saveWeblogEntry(any());
    }

    @Test
    void anEmptyFindIsA400() throws Exception {
        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e1").content("{" + edits("", "x") + "}"));

        assertEquals(List.of("edits[0]"), fields(problem));
    }

    /** Edits are reported with the write's other field problems, in one 400. */
    @Test
    void aFailingEditIsReportedWithTheOtherBadFields() throws Exception {
        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"status\":\"NOPE\"," + edits("nowhere", "x") + "}"));

        assertEquals(List.of("edits[0]", "status"), fields(problem));
    }

    // ------------------------------------------------------------ criterion 4

    @Test
    void getReturnsTheSha256HexOfTheStoredText() throws Exception {
        stored.setText("Café ünd ☕");
        EntriesApi reads = new EntriesApi();
        reads.weblogger = fx.weblogger;

        JsonNode view = json(MockMvcBuilders.standaloneSetup(reads).setControllerAdvice(new ApiExceptionHandler())
                .build().perform(get("/v1/weblogs/myblog/entries/e1").requestAttr("actionWeblog", fx.weblog))
                .andExpect(status().isOk()));

        assertEquals(sha256Hex("Café ünd ☕"), view.get("contentHash").asString());
    }

    @Test
    void theHashIsTheKnownSha256VectorAndAbsentWithoutText() {
        WeblogEntry entry = new WeblogEntry();
        entry.setText("abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                EntryDtos.toView(entry, null).contentHash());

        entry.setText(null);
        assertNull(EntryDtos.toView(entry, null).contentHash());
    }

    @Test
    void aStaleExpectedContentHashIsA409EntryChangedCarryingTheStoredHash() throws Exception {
        JsonNode problem = json(patchEntry("{\"title\":\"New\",\"expectedContentHash\":\""
                + sha256Hex("what the caller read") + "\"}").andExpect(status().isConflict()));

        assertTrue(problem.get("type").asString().endsWith("/entry-changed"), problem.toString());
        assertEquals(409, problem.get("status").asInt());
        assertEquals(sha256Hex(STORED), problem.get("currentContentHash").asString());
        assertEquals("Harbour", stored.getTitle());
        verify(fx.entries, never()).saveWeblogEntry(any());
    }

    /** Checked first: a stale caller learns the entry changed, not what else it got wrong. */
    @Test
    void theHashIsCheckedBeforeAnyOtherValidation() throws Exception {
        patchEntry("{\"status\":\"NOPE\"," + edits("nowhere", "x") + ",\"expectedContentHash\":\""
                + sha256Hex("stale") + "\"}").andExpect(status().isConflict());
    }

    @Test
    void aMatchingHashSavesAndTheResponseCarriesTheNewTextsHash() throws Exception {
        // either case: a hash is a hex number, and Wikantik's agents may send it upper-case
        String expected = sha256Hex(STORED).toUpperCase(Locale.ROOT);

        JsonNode view = json(patchEntry("{" + edits("The pier.", "The jetty.") + ",\"expectedContentHash\":\""
                + expected + "\"}").andExpect(status().isOk()));

        assertEquals("The jetty. The beach. The beach.", stored.getText());
        assertEquals(sha256Hex("The jetty. The beach. The beach."), view.get("contentHash").asString());
        verify(fx.entries, times(1)).saveWeblogEntry(stored);
    }

    @Test
    void expectedContentHashOnCreateIsA400() throws Exception {
        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries").content(
                "{\"title\":\"t\",\"text\":\"x\",\"expectedContentHash\":\"" + sha256Hex("x") + "\"}"));

        assertEquals(List.of("expectedContentHash"), fields(problem));
        verify(fx.entries, never()).saveWeblogEntry(any());
    }

    /** Not a hash at all is the caller's mistake, not a sign the entry changed. */
    @Test
    void anExpectedContentHashThatIsNotAHashIsA400() throws Exception {
        JsonNode problem = fx.refused(patch("/v1/weblogs/myblog/entries/e1").content(
                "{\"expectedContentHash\":\"2026-10-24T09:00:00Z\"}"));

        assertEquals(List.of("expectedContentHash"), fields(problem));
        verify(fx.entries, never()).saveWeblogEntry(any());
    }

    // ------------------------------------------------------------ preview

    private EntryRenderer echoingRenderer() {
        EntryRenderer renderer = mock(EntryRenderer.class);
        when(renderer.transformedText(any())).thenAnswer(inv -> "<p>" + ((WeblogEntry) inv.getArgument(0)).getText()
                + "</p>");
        when(fx.weblogger.getEntryRenderer()).thenReturn(renderer);
        return renderer;
    }

    @Test
    void previewWithIdAndEditsRendersTheEditedStoredTextAndSavesNothing() throws Exception {
        echoingRenderer();

        JsonNode view = json(fx.send(post("/v1/weblogs/myblog/entries/e1/preview").content(
                "{" + edits("The pier.", "The jetty.") + "}")).andExpect(status().isOk()));

        assertEquals("<p>The jetty. The beach. The beach.</p>", view.get("html").asString());
        verify(fx.entries, never()).saveWeblogEntry(any());
        verify(fx.weblogger, never()).flush();
    }

    @Test
    void previewEditsThatDoNotMatchOnceAreA400() throws Exception {
        echoingRenderer();

        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries/e1/preview").content(
                "{" + edits("The beach.", "x") + "}"));

        assertEquals(List.of("edits[0]"), fields(problem));
    }

    @Test
    void previewEditsWithoutAnIdAreA400() throws Exception {
        echoingRenderer();

        JsonNode problem = fx.refused(post("/v1/weblogs/myblog/entries/preview").content(
                "{" + edits("The pier.", "The jetty.") + "}"));

        assertEquals(List.of("edits"), fields(problem));
    }
}
