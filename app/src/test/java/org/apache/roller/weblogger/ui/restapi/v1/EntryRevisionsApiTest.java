package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.Set;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An entry's revisions through the API, against the real tier and database
 * (spec 2026-10-24, section 5; acceptance criterion 6): the list is newest
 * first and carries no text, one revision is its snapshot with a diff, a
 * restore puts the snapshot back and keeps what it replaced, and a revision
 * is reachable only through its own entry of the action weblog.
 */
class EntryRevisionsApiTest {

    private static final String SETUP_TEXT = "blah blah entry";

    private User user;
    private Weblog weblog;
    private Weblog theirs;
    private WeblogEntry entry;
    private WeblogEntry other;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("revapiuser");
        weblog = TestUtils.setupWeblog("revapiblog", user);
        theirs = TestUtils.setupWeblog("revapitheirs", user);
        entry = TestUtils.setupWeblogEntry("rev-api-entry", weblog, user, WeblogEntry.PubStatus.DRAFT);
        other = TestUtils.setupWeblogEntry("rev-api-other", weblog, user, WeblogEntry.PubStatus.DRAFT);
        TestUtils.endSession(true);

        EntriesApi reads = new EntriesApi();
        reads.weblogger = TestUtils.weblogger();
        EntriesWriteApi writes = new EntriesWriteApi();
        writes.weblogger = TestUtils.weblogger();
        mvc = MockMvcBuilders.standaloneSetup(reads, writes).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownWeblog(theirs.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void theListIsNewestFirstWithEachNoteAndNoText() throws Exception {
        patchText(entry, "Second text.", "First pass");
        patchText(entry, "Third text.", "Second pass");

        JsonNode list = revisions(entry);

        assertEquals(2, list.size(), list.toString());
        assertEquals("Second pass", list.get(0).get("note").asString(), "the second update's revision is newest");
        assertEquals("First pass", list.get(1).get("note").asString());
        for (JsonNode item : list) {
            assertEquals(Set.of("id", "created", "creator", "note", "title"), Set.copyOf(item.propertyNames()),
                    item.toString());
            assertEquals(user.getUserName(), item.get("creator").asString());
        }
    }

    @Test
    void aRevisionIsItsSnapshotWithADiffToTheCurrentText() throws Exception {
        patchText(entry, "one\ntwo\nthree", null);
        patchText(entry, "one\nTWO\nthree", null);
        String newest = revisions(entry).get(0).get("id").asString();

        JsonNode revision = json(send(get(base(entry) + "/revisions/" + newest)).andExpect(status().isOk()));

        assertEquals("one\ntwo\nthree", revision.get("text").asString());
        assertEquals(entry.getTitle(), revision.get("title").asString());
        assertEquals(user.getUserName(), revision.get("creator").asString());
        assertEquals("--- revision " + newest + "\n+++ current\n@@ -1,3 +1,3 @@\n one\n-two\n+TWO\n three\n",
                revision.get("diff").asString());
    }

    @Test
    void againstDiffsTwoRevisionsOfTheEntry() throws Exception {
        patchText(entry, "one\ntwo\nthree", null);
        patchText(entry, "one\nTWO\nthree", null);
        JsonNode list = revisions(entry);
        String newest = list.get(0).get("id").asString();
        String oldest = list.get(1).get("id").asString();

        String diff = json(send(get(base(entry) + "/revisions/" + newest + "?against=" + oldest))
                .andExpect(status().isOk())).get("diff").asString();

        assertEquals("--- revision " + newest + "\n+++ revision " + oldest + "\n@@ -1,3 +1 @@\n-one\n-two\n-three\n+"
                + SETUP_TEXT + "\n", diff);
    }

    @Test
    void aRestorePutsTheSnapshotBackAndKeepsWhatItReplacedAsARevision() throws Exception {
        patchText(entry, "Second text.", null);
        String revisionId = revisions(entry).get(0).get("id").asString();

        JsonNode restored = json(send(post(base(entry) + "/revisions/" + revisionId + "/restore"))
                .andExpect(status().isOk()));

        assertEquals(SETUP_TEXT, restored.get("text").asString());
        assertEquals(SETUP_TEXT, storedText(entry));
        JsonNode list = revisions(entry);
        assertEquals(2, list.size(), list.toString());
        String replaced = list.get(0).get("id").asString();
        JsonNode newest = json(send(get(base(entry) + "/revisions/" + replaced)).andExpect(status().isOk()));
        assertEquals("Second text.", newest.get("text").asString(), "the restore's own revision holds what it replaced");
        assertEquals(user.getUserName(), newest.get("creator").asString());
    }

    @Test
    void aRevisionIsReachableOnlyThroughItsOwnEntryOfTheActionWeblog() throws Exception {
        patchText(entry, "Second text.", null);
        patchText(other, "The other's second text.", null);
        String own = revisions(entry).get(0).get("id").asString();
        String foreign = revisions(other).get(0).get("id").asString();

        send(get(base(entry) + "/revisions/" + foreign)).andExpect(status().isNotFound());
        send(get(base(entry) + "/revisions/" + own + "?against=" + foreign)).andExpect(status().isNotFound());
        send(post(base(entry) + "/revisions/" + foreign + "/restore")).andExpect(status().isNotFound());
        send(get(base(entry) + "/revisions/no-such-revision")).andExpect(status().isNotFound());
        send(get("/v1/weblogs/revapiblog/entries/no-such-entry/revisions")).andExpect(status().isNotFound());
        send(theirs, get(base(entry) + "/revisions")).andExpect(status().isNotFound());
        send(theirs, get(base(entry) + "/revisions/" + own)).andExpect(status().isNotFound());
        assertEquals("Second text.", storedText(entry), "nothing was restored");
    }

    private String base(WeblogEntry target) {
        return "/v1/weblogs/revapiblog/entries/" + target.getId();
    }

    private void patchText(WeblogEntry target, String text, String note) throws Exception {
        String body = MAPPER.writeValueAsString(note == null
                ? java.util.Map.of("text", text) : java.util.Map.of("text", text, "revisionNote", note));
        send(patch(base(target)).content(body)).andExpect(status().isOk());
    }

    private JsonNode revisions(WeblogEntry target) throws Exception {
        JsonNode list = json(send(get(base(target) + "/revisions")).andExpect(status().isOk()));
        assertTrue(list.isArray(), list.toString());
        return list;
    }

    private String storedText(WeblogEntry target) throws Exception {
        String text = TestUtils.weblogger().getWeblogEntryManager().getWeblogEntry(target.getId()).getText();
        TestUtils.endSession(false);
        return text;
    }

    private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        return send(weblog, request);
    }

    /** As the interceptor would: {@code actionWeblog} and the token's user, then the session ends. */
    private ResultActions send(Weblog action, MockHttpServletRequestBuilder request) throws Exception {
        ResultActions result = mvc.perform(request
                .requestAttr("actionWeblog", TestUtils.getManagedWebsite(action))
                .requestAttr("authenticatedUser", TestUtils.getManagedUser(user))
                .contentType(MediaType.APPLICATION_JSON));
        TestUtils.endSession(true);
        return result;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
