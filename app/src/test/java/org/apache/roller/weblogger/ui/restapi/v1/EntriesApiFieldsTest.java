package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import java.util.Set;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.apache.roller.weblogger.ui.restapi.v1.EntriesWriteApiFixture.fields;
import static org.apache.roller.weblogger.ui.restapi.v1.EntriesWriteApiFixture.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code fields} on every entry route that returns entries (spec 2026-10-24,
 * section 2; acceptance criterion 2): the named {@code EntryView} properties
 * and {@code id}, nothing else; an unknown name is a 400 listing the valid
 * ones, refused before anything is written; without it, the full view.
 */
class EntriesApiFieldsTest {

    private static final Set<String> ASKED = Set.of("id", "anchor", "searchDescription");

    private EntriesWriteApiFixture fx;
    private WeblogEntry stored;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        fx = new EntriesWriteApiFixture();
        stored = entry("e1", "harbour-walk", WeblogEntry.PubStatus.DRAFT);
        when(fx.entries.getWeblogEntry("e1")).thenReturn(stored);
        when(fx.entries.getWeblogEntryByAnchor(fx.weblog, "harbour-walk")).thenReturn(stored);
        EntriesApi reads = new EntriesApi();
        reads.weblogger = fx.weblogger;
        mvc = MockMvcBuilders.standaloneSetup(reads, fx.controller)
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private WeblogEntry entry(String id, String anchor, WeblogEntry.PubStatus status) {
        WeblogEntry e = new WeblogEntry();
        e.setId(id);
        e.setAnchor(anchor);
        e.setWebsite(fx.weblog);
        e.setTitle("Harbour walk");
        e.setText("The pier at dawn.");
        e.setSearchDescription("A walk along the harbour.");
        e.setMetaTitle("Harbour walk | myblog");
        e.setStatus(status);
        return e;
    }

    private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        User user = new User();
        user.setUserName("maiia");
        return mvc.perform(request.requestAttr("actionWeblog", fx.weblog)
                .requestAttr("authenticatedUser", user)
                .contentType(MediaType.APPLICATION_JSON));
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        return json(send(request).andExpect(status().is2xxSuccessful()));
    }

    private static MockHttpServletRequestBuilder asking(MockHttpServletRequestBuilder request) {
        return request.param("fields", "anchor").param("fields", "searchDescription");
    }

    private static Set<String> keys(JsonNode node) {
        return Set.copyOf(node.propertyNames());
    }

    /** A 400 on {@code fields} that names {@code nope} and lists every valid name. */
    private JsonNode refusedNope(MockHttpServletRequestBuilder request) throws Exception {
        JsonNode problem = json(send(request.param("fields", "nope")).andExpect(status().isBadRequest()));
        assertEquals(List.of("fields"), fields(problem));
        String message = problem.get("errors").get(0).get("message").asString();
        assertTrue(message.contains("'nope'"), message);
        for (java.lang.reflect.RecordComponent name : EntryDtos.EntryView.class.getRecordComponents()) {
            assertTrue(message.contains(name.getName()), name.getName() + " missing from: " + message);
        }
        return problem;
    }

    // ------------------------------------------------------------ the asked fields and id, nothing else

    @Test
    void updateWithFieldsReturnsExactlyIdAnchorAndSearchDescription() throws Exception {
        JsonNode view = ok(asking(patch("/v1/weblogs/myblog/entries/e1"))
                .content("{\"searchDescription\":\"A dawn walk along the harbour.\"}"));

        assertEquals(ASKED, keys(view));
        assertEquals("e1", view.get("id").asString());
        assertEquals("harbour-walk", view.get("anchor").asString());
        assertEquals("A dawn walk along the harbour.", view.get("searchDescription").asString());
        verify(fx.entries).saveWeblogEntry(eq(stored), any(User.class));
    }

    @Test
    void getByIdAndByAnchorReturnOnlyTheAskedFields() throws Exception {
        assertEquals(ASKED, keys(ok(asking(get("/v1/weblogs/myblog/entries/e1")))));
        assertEquals(ASKED, keys(ok(asking(get("/v1/weblogs/myblog/entries/by-anchor/harbour-walk")))));
    }

    @Test
    void createReturnsOnlyTheAskedFields() throws Exception {
        JsonNode view = ok(asking(post("/v1/weblogs/myblog/entries"))
                .content("{\"title\":\"New\",\"text\":\"Body.\",\"searchDescription\":\"New one.\"}"));

        assertEquals(Set.of("id", "searchDescription"), keys(view),
                "anchor is generated by the save, which the mocked tier skips, and a null is still omitted");
        assertEquals("New one.", view.get("searchDescription").asString());
    }

    @Test
    void restoreReturnsOnlyTheAskedFields() throws Exception {
        stored.setStatus(WeblogEntry.PubStatus.TRASHED);
        assertEquals(ASKED, keys(ok(asking(post("/v1/weblogs/myblog/entries/e1/restore")))));
        verify(fx.entries).restoreWeblogEntry(stored);
    }

    @Test
    void listProjectsEachItemAndKeepsTheEnvelope() throws Exception {
        when(fx.entries.getWeblogEntries(any(WeblogEntrySearchCriteria.class))).thenReturn(List.of(
                stored, entry("e2", "second-walk", WeblogEntry.PubStatus.PUBLISHED),
                entry("e3", "third-walk", WeblogEntry.PubStatus.PUBLISHED)));

        JsonNode page = ok(asking(get("/v1/weblogs/myblog/entries")).param("limit", "2"));

        assertEquals(Set.of("items", "offset", "limit", "hasMore"), keys(page));
        assertEquals(0, page.get("offset").asInt());
        assertEquals(2, page.get("limit").asInt());
        assertTrue(page.get("hasMore").asBoolean());
        assertEquals(2, page.get("items").size());
        for (JsonNode item : page.get("items")) {
            assertEquals(ASKED, keys(item));
        }
        assertEquals("second-walk", page.get("items").get(1).get("anchor").asString());
    }

    @Test
    void askingForIdAloneReturnsIdAlone() throws Exception {
        assertEquals(Set.of("id"), keys(ok(get("/v1/weblogs/myblog/entries/e1").param("fields", "id"))));
    }

    // ------------------------------------------------------------ an unknown name

    @Test
    void anUnknownNameOnUpdateIsA400ListingTheValidNamesAndSavesNothing() throws Exception {
        refusedNope(patch("/v1/weblogs/myblog/entries/e1").content("{\"title\":\"Changed\"}"));

        assertEquals("Harbour walk", stored.getTitle());
        verify(fx.entries, never()).saveWeblogEntry(any(WeblogEntry.class), any(User.class));
    }

    /** One 400 for the whole write: the bad name is reported with the body's own field problems. */
    @Test
    void anUnknownNameOnUpdateIsReportedWithTheWritesOtherFieldErrors() throws Exception {
        JsonNode problem = json(send(patch("/v1/weblogs/myblog/entries/e1").param("fields", "nope")
                .content("{\"status\":\"BANANA\"}")).andExpect(status().isBadRequest()));

        assertEquals(Set.of("fields", "status"), Set.copyOf(fields(problem)));
    }

    @Test
    void anUnknownNameOnCreateIsA400AndCreatesNothing() throws Exception {
        refusedNope(post("/v1/weblogs/myblog/entries").content("{\"title\":\"New\",\"text\":\"Body.\"}"));
        verify(fx.entries, never()).saveWeblogEntry(any(WeblogEntry.class));
    }

    @Test
    void anUnknownNameOnRestoreIsA400AndRestoresNothing() throws Exception {
        stored.setStatus(WeblogEntry.PubStatus.TRASHED);
        refusedNope(post("/v1/weblogs/myblog/entries/e1/restore"));
        verify(fx.entries, never()).restoreWeblogEntry(any());
    }

    @Test
    void anUnknownNameOnTheReadsIsA400() throws Exception {
        refusedNope(get("/v1/weblogs/myblog/entries/e1"));
        refusedNope(get("/v1/weblogs/myblog/entries/by-anchor/harbour-walk"));
        refusedNope(get("/v1/weblogs/myblog/entries"));
    }

    // ------------------------------------------------------------ without fields: the full view

    /** The response is the full view as the API has always written it, contentHash included. */
    @Test
    void withoutFieldsEveryRouteReturnsTheFullView() throws Exception {
        when(fx.entries.getWeblogEntries(any(WeblogEntrySearchCriteria.class))).thenReturn(List.of(stored));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode full = mapper.readTree(mapper.writeValueAsString(EntryDtos.toView(stored, null)));
        assertTrue(full.has("contentHash") && full.has("text") && full.has("metaTitle"), full.toString());

        assertEquals(full, ok(get("/v1/weblogs/myblog/entries/e1")));
        assertEquals(full, ok(get("/v1/weblogs/myblog/entries/by-anchor/harbour-walk")));
        assertEquals(full, ok(get("/v1/weblogs/myblog/entries")).get("items").get(0));
        assertEquals(full, ok(patch("/v1/weblogs/myblog/entries/e1").content("{}")));
    }

    /**
     * The projection is built from EntryView's record components, not from
     * its JSON, so this pins the two to one another: asking for every name
     * writes exactly the full view, times and nulls included. A rename by
     * annotation or a change to the view's null rule fails here.
     */
    @Test
    void askingForEveryFieldWritesExactlyTheFullView() throws Exception {
        stored.setPubTime(java.sql.Timestamp.from(java.time.Instant.parse("2026-03-01T08:30:00Z")));
        stored.setUpdateTime(java.sql.Timestamp.from(java.time.Instant.parse("2026-03-02T10:00:00.123Z")));
        stored.setGeoLatitude(32.9228);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode full = mapper.readTree(mapper.writeValueAsString(EntryDtos.toView(stored, null)));

        MockHttpServletRequestBuilder every = get("/v1/weblogs/myblog/entries/e1");
        for (java.lang.reflect.RecordComponent name : EntryDtos.EntryView.class.getRecordComponents()) {
            every.param("fields", name.getName());
        }
        assertEquals(full, ok(every));
    }

    /** An empty fields value is no projection, not a projection to id alone. */
    @Test
    void anEmptyFieldsValueIsTheFullView() throws Exception {
        assertTrue(ok(get("/v1/weblogs/myblog/entries/e1").param("fields", "")).has("text"));
    }
}
