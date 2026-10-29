package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.wikantik.pressroom.ui.restapi.ErrorFieldMatchers.errorFieldIs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EntriesApiReadTest {

    /**
     * Titles are stored escaped, so the DTO carries them through unchanged.
     * Escaping again here would send "&amp;amp;" to every client.
     */
    @Test
    void theViewCarriesTheStoredTitleWithoutReEscaping() {
        WeblogEntry entry = new WeblogEntry();
        entry.setTitle("Cats &amp; Dogs");
        entry.setStatus(WeblogEntry.PubStatus.DRAFT);

        EntryDtos.EntryView view = EntryDtos.toView(entry, null);
        assertEquals("Cats &amp; Dogs", view.title());
    }

    /** TRASHED is reachable only through the explicit status filter. */
    @Test
    void trashedIsNotAWritableStatus() {
        assertThrows(com.wikantik.pressroom.ui.restapi.ApiException.class,
                () -> EntryDtos.parseWritableStatus("TRASHED"));
    }

    @Test
    void anUnknownStatusIsRejected() {
        assertThrows(com.wikantik.pressroom.ui.restapi.ApiException.class,
                () -> EntryDtos.parseWritableStatus("BANANA"));
    }

    @Test
    void theFourWritableStatusesAreAccepted() {
        assertEquals(WeblogEntry.PubStatus.DRAFT, EntryDtos.parseWritableStatus("draft"));
        assertEquals(WeblogEntry.PubStatus.PUBLISHED, EntryDtos.parseWritableStatus("PUBLISHED"));
        assertEquals(WeblogEntry.PubStatus.PENDING, EntryDtos.parseWritableStatus("Pending"));
        assertEquals(WeblogEntry.PubStatus.SCHEDULED, EntryDtos.parseWritableStatus("SCHEDULED"));
    }

    /**
     * Filtering by TRASHED is how the trash list is read, so the filter
     * parser accepts what the write parser refuses. Two parsers, not one with
     * a boolean, so a write check cannot be relaxed into a filter check by
     * flipping an argument.
     */
    @Test
    void theFilterParserAcceptsTrashedWhereTheWriteParserDoesNot() {
        assertEquals(WeblogEntry.PubStatus.TRASHED, EntryDtos.parseFilterStatus("TRASHED"));
        assertThrows(com.wikantik.pressroom.ui.restapi.ApiException.class,
                () -> EntryDtos.parseWritableStatus("TRASHED"));
    }

    @Test
    void theFilterParserRejectsAnUnknownStatus() {
        assertThrows(com.wikantik.pressroom.ui.restapi.ApiException.class,
                () -> EntryDtos.parseFilterStatus("BANANA"));
    }

    /**
     * Jackson v3 (tools.jackson.*) round-trip check for
     * {@code @JsonInclude(NON_NULL)} on a record -- flagged as unverified
     * twice in this wave (Task 2's deferred minor on {@code ApiProblem},
     * then again as this task's own concern), and the stakes are higher
     * than usual because the annotation lives in
     * {@code com.fasterxml.jackson.annotation} while serialization runs
     * through {@code tools.jackson.databind}, a package split nobody in
     * this wave had exercised end to end before now. One DTO settles it for
     * every DTO in the wave that carries the same annotation: if nulls were
     * NOT elided, every "absent when unset" contract documented on those
     * views would be silently false, and the wave's error-body contract
     * (which leans on the same annotation via {@code ApiProblem}) would be
     * suspect too.
     *
     * <p>Uses a bare {@code new ObjectMapper()} rather than a Spring-wired
     * bean deliberately: nothing in this codebase configures Jackson beyond
     * the default (no {@code spring.jackson.*} property, no {@code
     * ObjectMapper} {@code @Bean} override anywhere in {@code
     * com.wikantik.pressroom}), so a bare mapper here is exactly what
     * {@code ApiProblemWriter}'s Boot-autowired one resolves to at runtime.
     */
    @Test
    void nullFieldsAreElidedFromTheSerializedView() throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setAnchor("hello");
        entry.setTitle("Hello");
        // WeblogEntry itself defaults status to DRAFT and noindex to FALSE
        // (see WeblogEntry.java:97/107) -- null them explicitly so this test
        // measures Jackson's elision, not the entity's own field defaults.
        entry.setStatus(null);
        entry.setNoindex(null);
        // Everything else -- summary, text, category, pubTime, updateTime,
        // metaTitle, searchDescription, canonicalUrl, featuredImageId,
        // ogImageId -- is left unset (null) by construction.

        EntryDtos.EntryView view = EntryDtos.toView(entry, null);

        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        tools.jackson.databind.JsonNode node = mapper.readTree(mapper.writeValueAsString(view));

        // Present: non-null fields, plus permalink (explicitly null, but
        // still passed positionally -- see the next block for why that's
        // the more interesting assertion) and tags (an empty List, never
        // null, so its presence is expected and NOT itself proof of
        // elision).
        assertTrue(node.has("id"));
        assertTrue(node.has("anchor"));
        assertTrue(node.has("title"));
        assertTrue(node.has("tags"));
        assertEquals(0, node.get("tags").size());

        // Absent: every field left null on the source entry. If
        // @JsonInclude(NON_NULL) were silently ignored under Jackson v3,
        // every one of these would fail instead.
        assertFalse(node.has("summary"));
        assertFalse(node.has("text"));
        assertFalse(node.has("status"));
        assertFalse(node.has("category"));
        assertFalse(node.has("pubTime"));
        assertFalse(node.has("updateTime"));
        assertFalse(node.has("permalink"));
        assertFalse(node.has("metaTitle"));
        assertFalse(node.has("searchDescription"));
        assertFalse(node.has("canonicalUrl"));
        assertFalse(node.has("noindex"));
        assertFalse(node.has("featuredImageId"));
        assertFalse(node.has("ogImageId"));
    }

    // -----------------------------------------------------------------
    // Controller-level tests. The DTO tests above prove EntryDtos alone;
    // these prove routing, status codes, permission-independent business
    // logic (trash exclusion, pagination), and JSON shape -- none of which
    // a DTO-only test can see. actionWeblog is injected as a request
    // attribute rather than resolved by a real interceptor, matching
    // TokensApiTest's standalone style: this class is deliberately
    // interceptor-agnostic. EntriesApiDispatchTest is where the real
    // PressroomHandlerInterceptor wiring (weblog resolution + WeblogPermission
    // enforcement) is proven end to end.
    // -----------------------------------------------------------------

    private MockMvc mockMvc(EntriesApi controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Weblogger mockedWeblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        return weblogger;
    }

    private EntriesApi controllerFor(Weblogger weblogger) {
        EntriesApi controller = new EntriesApi();
        controller.weblogger = weblogger;
        return controller;
    }

    private static Weblog aWeblog() {
        Weblog weblog = new Weblog();
        weblog.setId("weblog-1");
        weblog.setHandle("myblog");
        return weblog;
    }

    /**
     * WeblogEntrySearchCriteria.includeTrashed defaults to false -- the
     * safety property CLAUDE.md documents. A caller that supplies no status
     * at all must get that default, not something EntriesApi has to
     * remember to set.
     */
    @Test
    void listExcludesTrashByDefault() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(List.of());

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries").requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isOk());

        ArgumentCaptor<WeblogEntrySearchCriteria> captor =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(weblogger.getWeblogEntryManager()).getWeblogEntries(captor.capture());
        assertNull(captor.getValue().getStatus());
        assertFalse(captor.getValue().isIncludeTrashed());
    }

    /**
     * The only way to see the trash: ask for status=TRASHED by name.
     */
    @Test
    void listWithStatusTrashedIncludesTheTrash() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(List.of());

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries")
                        .param("status", "TRASHED")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isOk());

        ArgumentCaptor<WeblogEntrySearchCriteria> captor =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(weblogger.getWeblogEntryManager()).getWeblogEntries(captor.capture());
        assertEquals(WeblogEntry.PubStatus.TRASHED, captor.getValue().getStatus());
        assertTrue(captor.getValue().isIncludeTrashed());
    }

    /** A status the filter parser rejects answers 400, not a 500 from a bad enum lookup. */
    @Test
    void listWithAnUnknownStatusIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries")
                        .param("status", "BANANA")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    /**
     * limit=-1 used to reach {@code found.subList(0, -1)} --
     * IndexOutOfBoundsException, a 500. Rejected before any criteria is
     * built: the manager is never even called.
     */
    @Test
    void aNegativeLimitOfMinusOneIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries")
                        .param("limit", "-1")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), org.mockito.Mockito.never()).getWeblogEntries(any());
    }

    /**
     * limit=-2 was the more dangerous of the two: {@code Math.min(-2, 200)}
     * is -2, so {@code maxResults} became -1, and
     * {@code JPAWeblogEntryManagerImpl.setFirstMax} treats -1 as NO LIMIT --
     * an unbounded read over every entry in the weblog, not merely an
     * exception. It still went on to throw on {@code subList(0, -2)}
     * afterwards, but the unbounded query would already have run. Rejected
     * up front the same as -1: the manager is never called at all, so
     * there is no unbounded read to run.
     */
    @Test
    void aNegativeLimitOfMinusTwoIsBadRequestRatherThanAnUnboundedQuery() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries")
                        .param("limit", "-2")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), org.mockito.Mockito.never()).getWeblogEntries(any());
    }

    /** limit=0 asks for a page of nothing, which is not a valid request either. */
    @Test
    void aZeroLimitIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries")
                        .param("limit", "0")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), org.mockito.Mockito.never()).getWeblogEntries(any());
    }

    /**
     * offset=-1 used to reach {@code criteria.setOffset(-1)} and then
     * {@code EntityManager.setFirstResult(-1)} -- an IllegalArgumentException
     * from JPA, a 500.
     */
    @Test
    void aNegativeOffsetIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries")
                        .param("offset", "-1")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), org.mockito.Mockito.never()).getWeblogEntries(any());
    }

    /** A range error names the field that is out of range, limit first when both are. */
    @Test
    void aRangeErrorNamesTheOffendingField() throws Exception {
        MockMvc mvc = mockMvc(controllerFor(mockedWeblogger()));

        mvc.perform(get("/v1/weblogs/myblog/entries").param("limit", "0")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("limit"));
        mvc.perform(get("/v1/weblogs/myblog/entries").param("offset", "-1")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("offset"));
        mvc.perform(get("/v1/weblogs/myblog/entries").param("limit", "0").param("offset", "-1")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(errorFieldIs("limit"));
    }

    /**
     * One extra row over the requested limit is how hasMore is decided
     * without a second count query -- the extra row itself must never leak
     * into the returned page.
     */
    @Test
    void listReportsHasMoreWithoutLeakingTheExtraRow() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog();
        WeblogEntry first = new WeblogEntry();
        first.setId("e1");
        first.setAnchor("first");
        first.setWebsite(weblog);
        WeblogEntry second = new WeblogEntry();
        second.setId("e2");
        second.setAnchor("second");
        second.setWebsite(weblog);
        when(weblogger.getUrlStrategy()).thenReturn(mock(com.wikantik.pressroom.business.URLStrategy.class));
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any()))
                .thenReturn(List.of(first, second));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries")
                        .param("limit", "1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.get("items").size());
        assertEquals("first", json.get("items").get(0).get("anchor").asString());
        assertTrue(json.get("hasMore").asBoolean());
        assertEquals(1, json.get("limit").asInt());
    }

    @Test
    void getReturnsTheEntryWhenItBelongsToTheActionWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog();
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setAnchor("hello-world");
        entry.setTitle("Hello World");
        entry.setWebsite(weblog);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);
        when(weblogger.getUrlStrategy()).thenReturn(mock(com.wikantik.pressroom.business.URLStrategy.class));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("Hello World", json.get("title").asString());
        assertEquals("hello-world", json.get("anchor").asString());
    }

    /**
     * The IDOR case: an id that belongs to a different weblog than the one
     * this request is scoped to is indistinguishable from an unknown id --
     * both answer 404 via WeblogOwnership.entry, never the entry's data.
     */
    @Test
    void getIsNotFoundWhenTheEntryBelongsToAnotherWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog();
        Weblog anotherWeblog = new Weblog();
        anotherWeblog.setId("weblog-2");
        anotherWeblog.setHandle("someoneelse");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(anotherWeblog);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", thisWeblog))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void getIsNotFoundWhenNoSuchEntryExistsAtAll() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        when(weblogger.getWeblogEntryManager().getWeblogEntry(anyString())).thenReturn(null);

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/entries/{id}", "no-such-id")
                        .requestAttr("actionWeblog", aWeblog()))
                .andExpect(status().isNotFound());
    }

    /**
     * EntriesApi declares UISecurityEnforced.requiredWeblogPermissionActions
     * as EDIT_DRAFT: reads are open to contributors, not just editors.
     */
    @Test
    void declaresEditDraftAsTheRequiredWeblogPermission() {
        assertEquals(List.of(com.wikantik.pressroom.pojos.WeblogPermission.EDIT_DRAFT),
                new EntriesApi().requiredWeblogPermissionActions());
        assertTrue(new EntriesApi().isUserRequired());
        assertTrue(new EntriesApi().isWeblogRequired());
    }
}
