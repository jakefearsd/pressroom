package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.TagStat;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.ui.controllers.WeblogOwnership;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.dto.CategoryDtos;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.apache.roller.weblogger.ui.restapi.ErrorFieldMatchers.errorFieldIs;

class CategoriesApiTest {

    private User user;
    private Weblog mine;
    private Weblog theirs;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("apicatuser");
        // NOTE: the brief's sample used hyphenated handles ("api-cat-mine" /
        // "api-cat-theirs"), which JPAWeblogManagerImpl rejects -- weblog
        // handles are validated alphanumeric. Substituted with alphanumeric
        // handles here; see the task report.
        mine = TestUtils.setupWeblog("apicatmine", user);
        theirs = TestUtils.setupWeblog("apicattheirs", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(mine.getId());
        TestUtils.teardownWeblog(theirs.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    /**
     * The move target is client input and getWeblogCategory is a global by-id
     * lookup. Without an ownership check on BOTH ids, a delete-with-move
     * silently re-files this weblog's entries into another weblog.
     *
     * <p>Characterisation test from the brief: this pins WeblogOwnership from
     * Task 7, which already exists, and is expected to PASS on arrival. It is
     * NOT this task's red step -- see CategoriesApiTest's controller-level
     * tests below for that.
     */
    @Test
    void aForeignMoveTargetIsRefused() throws Exception {
        var wem = TestUtils.weblogger().getWeblogEntryManager();
        WeblogCategory foreign = wem.getWeblogCategories(
                TestUtils.weblogger().getWeblogManager()
                        .getWeblogByHandle(theirs.getHandle())).get(0);

        assertNull(WeblogOwnership.category(TestUtils.weblogger(),
                        foreign.getId(),
                        TestUtils.weblogger().getWeblogManager()
                                .getWeblogByHandle(mine.getHandle())),
                "a category from another weblog must read as absent");
    }

    // -----------------------------------------------------------------
    // Controller-level tests: routing, status codes, the ownership checks
    // and JSON shape -- none of which the characterisation test above can
    // see. actionWeblog is injected as a request attribute rather than
    // resolved by a real interceptor, matching EntriesApiReadTest's
    // standalone style. These do not touch the DB fixtures created above;
    // they run against a Mockito-mocked Weblogger.
    // -----------------------------------------------------------------

    private MockMvc mockMvc(CategoriesApi controller) {
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

    private CategoriesApi controllerFor(Weblogger weblogger) {
        CategoriesApi controller = new CategoriesApi();
        controller.weblogger = weblogger;
        return controller;
    }

    private static Weblog aWeblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setId(handle + "-id");
        weblog.setHandle(handle);
        return weblog;
    }

    private static WeblogCategory aCategory(Weblog weblog, String name) {
        return new WeblogCategory(weblog, name, "desc-" + name, null);
    }

    @Test
    void listReturnsCategoryViewsWithEntryCounts() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogCategory category = aCategory(weblog, "Travel");
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog))
                .thenReturn(List.of(category));
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any(WeblogEntrySearchCriteria.class)))
                .thenReturn(List.of(new org.apache.roller.weblogger.pojos.WeblogEntry(),
                        new org.apache.roller.weblogger.pojos.WeblogEntry()));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/categories").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.size());
        assertEquals("Travel", json.get(0).get("name").asString());
        assertEquals("desc-Travel", json.get(0).get("description").asString());
        assertEquals(2, json.get(0).get("entryCount").asInt());
    }

    @Test
    void postCreatesACategory() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/categories")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Recipes\",\"description\":\"Food stuff\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        verify(weblogger.getWeblogEntryManager()).saveWeblogCategory(any());
        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("Recipes", json.get("name").asString());
        assertEquals("Food stuff", json.get("description").asString());
        assertTrue(weblog.hasCategory("Recipes"));
        verify(weblogger).flush();
    }

    /** weblogcategory.name/description are both varchar(255) (V002__baseline_schema.sql). */
    @Test
    void postWithANameLongerThanTheColumnIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(256);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/categories")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
        verify(weblogger, never()).flush();
    }

    @Test
    void postWithADescriptionLongerThanTheColumnIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(256);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/categories")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Recipes\",\"description\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
        verify(weblogger, never()).flush();
    }

    /** A duplicate name is a 409, never a 500 from a bubbled constraint violation. */
    @Test
    void postWithADuplicateNameIsConflict() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        aCategory(weblog, "Recipes");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/categories")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Recipes\"}"))
                .andExpect(status().isConflict())
                .andExpect(errorFieldIs("name"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
        verify(weblogger, never()).flush();
    }

    @Test
    void patchUpdatesACategoryFoundThroughOwnership() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogCategory category = aCategory(weblog, "Old Name");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(category);

        String body = mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/categories/{id}", "cat-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("New Name", json.get("name").asString());
        verify(weblogger.getWeblogEntryManager()).saveWeblogCategory(category);
        verify(weblogger).flush();
    }

    /** weblogcategory.name/description are both varchar(255) -- PATCH side. */
    @Test
    void patchWithANameLongerThanTheColumnIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogCategory category = aCategory(weblog, "Old Name");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(category);
        String tooLong = "a".repeat(256);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/categories/{id}", "cat-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
        verify(weblogger, never()).flush();
        assertEquals("Old Name", category.getName(), "the in-memory category must be untouched on refusal");
    }

    @Test
    void patchWithADescriptionLongerThanTheColumnIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogCategory category = aCategory(weblog, "Old Name");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(category);
        String tooLong = "a".repeat(256);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/categories/{id}", "cat-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
        verify(weblogger, never()).flush();
    }

    /**
     * The IDOR case for PATCH: a category id that belongs to another weblog
     * is indistinguishable from an unknown id -- WeblogOwnership.category
     * returns null for both, and the controller must answer 404 either way,
     * never leak or rewrite the foreign category.
     */
    @Test
    void patchIsNotFoundWhenTheCategoryBelongsToAnotherWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        Weblog anotherWeblog = aWeblog("someoneelse");
        WeblogCategory foreign = aCategory(anotherWeblog, "Foreign");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(foreign);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/categories/{id}", "cat-1")
                        .requestAttr("actionWeblog", thisWeblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
    }

    @Test
    void deleteWithoutMoveToRemovesAnUnusedCategory() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogCategory category = aCategory(weblog, "Doomed");
        aCategory(weblog, "Other"); // so the weblog is not left with zero categories
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(category);
        when(weblogger.getWeblogEntryManager().isWeblogCategoryInUse(category)).thenReturn(false);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/categories/{id}", "cat-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isNoContent());

        verify(weblogger.getWeblogEntryManager()).removeWeblogCategory(category);
        verify(weblogger.getWeblogEntryManager(), never()).moveWeblogCategoryContents(any(), any());
        verify(weblogger).flush();
    }

    @Test
    void deleteWithMoveToMovesContentsBeforeRemoving() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogCategory category = aCategory(weblog, "Doomed");
        WeblogCategory target = aCategory(weblog, "Target");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(category);
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-2")).thenReturn(target);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/categories/{id}", "cat-1")
                        .param("moveTo", "cat-2")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isNoContent());

        verify(weblogger.getWeblogEntryManager()).moveWeblogCategoryContents(category, target);
        verify(weblogger.getWeblogEntryManager()).removeWeblogCategory(category);
        // Two flush()es in this path -- one after the move, one after the
        // remove (see CategoriesApi.delete) -- so at least one call must be
        // observed; verify(times(1)) would over-specify an implementation
        // detail this test does not care about.
        verify(weblogger, org.mockito.Mockito.atLeastOnce()).flush();
    }

    /**
     * The regression this task exists to prevent: a delete-with-move whose
     * moveTo id names a category belonging to a DIFFERENT weblog must be
     * refused before anything is moved or removed. Both ids are ownership
     * checked, not just the one being deleted.
     */
    @Test
    void deleteWithAForeignMoveToIsRefusedAndNothingIsMovedOrRemoved() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        Weblog anotherWeblog = aWeblog("someoneelse");
        WeblogCategory category = aCategory(thisWeblog, "Doomed");
        aCategory(thisWeblog, "Other"); // so thisWeblog is not left with zero categories
        WeblogCategory foreignTarget = aCategory(anotherWeblog, "ForeignTarget");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(category);
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-2")).thenReturn(foreignTarget);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/categories/{id}", "cat-1")
                        .param("moveTo", "cat-2")
                        .requestAttr("actionWeblog", thisWeblog))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).moveWeblogCategoryContents(any(), any());
        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogCategory(any());
    }

    /**
     * The last-category guard, previously asserted only by construction
     * (every other DELETE test deliberately keeps a second category around
     * so this path never fires). saveWeblogEntry() falls back to "the first
     * category found" for an entry with none, so a weblog left with zero
     * categories can no longer accept a save at all.
     */
    @Test
    void deleteRefusesToRemoveTheWeblogsLastCategory() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogCategory category = aCategory(weblog, "OnlyOne");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(category);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/categories/{id}", "cat-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogCategory(any());
        verify(weblogger.getWeblogEntryManager(), never()).moveWeblogCategoryContents(any(), any());
        verify(weblogger, never()).flush();
    }

    /**
     * The in-use-without-moveTo guard, previously asserted only by
     * construction (the sibling test stubs isWeblogCategoryInUse false so
     * this path never fires). Without it, removeWeblogCategory would throw
     * a bare WebloggerException that ApiExceptionHandler can only turn into
     * an opaque 500.
     */
    @Test
    void deleteWithoutMoveToOnACategoryStillInUseIsConflict() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogCategory category = aCategory(weblog, "InUse");
        aCategory(weblog, "Other"); // so the last-category guard cannot also fire
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(category);
        when(weblogger.getWeblogEntryManager().isWeblogCategoryInUse(category)).thenReturn(true);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/categories/{id}", "cat-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogCategory(any());
    }

    /**
     * moveTo naming the SAME category being deleted: WeblogOwnership.category
     * happily resolves it (it genuinely belongs to the calling weblog),
     * moveWeblogCategoryContents would be a self-move no-op, and
     * removeWeblogCategory would then throw because the category still holds
     * its own entries -- the same opaque-500 shape the other two DELETE
     * guards exist to intercept. Refused before either manager call runs.
     */
    @Test
    void deleteWithMoveToNamingTheSameCategoryIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogCategory category = aCategory(weblog, "Doomed");
        aCategory(weblog, "Other"); // so the last-category guard cannot also fire
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(category);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/categories/{id}", "cat-1")
                        .param("moveTo", "cat-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).moveWeblogCategoryContents(any(), any());
        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogCategory(any());
    }

    /**
     * The PATCH-rename duplicate guard, previously asserted only by
     * construction (the ownership test renames to a name nothing else
     * holds). This one matters more than a typical "duplicate name" check:
     * JPAWeblogEntryManagerImpl.saveWeblogCategory only duplicate-checks
     * NEW categories (its own `exists` flag short-circuits the check for a
     * category that already has an id), so without this guard a rename
     * collision would not even 500 -- it would silently succeed, leaving two
     * categories sharing a name.
     */
    @Test
    void patchRenamingToAnExistingCategoryNameIsConflict() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogCategory category = aCategory(weblog, "Old Name");
        WeblogCategory taken = aCategory(weblog, "Taken");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(category);
        when(weblogger.getWeblogEntryManager().getWeblogCategoryByName(weblog, "Taken")).thenReturn(taken);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/categories/{id}", "cat-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Taken\"}"))
                .andExpect(status().isConflict())
                .andExpect(errorFieldIs("name"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
        assertEquals("Old Name", category.getName(), "the in-memory category must be untouched on refusal");
    }

    /**
     * Wave-level gap (Finding 3): a malformed JSON body throws
     * HttpMessageNotReadableException during argument resolution, before any
     * handler method runs. Before ApiExceptionHandler grew a dedicated
     * handler for it, this fell through to handleUnexpected and answered an
     * opaque 500 on every *Api controller in the wave, not just this one --
     * proven here through CategoriesApi's own real MockMvc dispatch rather
     * than only a unit test of the handler in isolation.
     */
    @Test
    void aMalformedRequestBodyIsBadRequestNotAnOpaque500() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/categories")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogCategory(any());
    }

    @Test
    void tagsReturnsTheWeblogsTagsWithCounts() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        TagStat stat = new TagStat();
        stat.setName("travel");
        stat.setCount(7);
        when(weblogger.getWeblogEntryManager().getTags(weblog, null, null, 0, -1))
                .thenReturn(List.of(stat));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/tags").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.size());
        assertEquals("travel", json.get(0).get("name").asString());
        assertEquals(7, json.get(0).get("count").asInt());
    }

    /**
     * CategoriesApi declares UISecurityEnforced.requiredWeblogPermissionActions
     * as POST: categories are blog-wide structure, the same level
     * CategoryEditController/CategoryRemoveController require.
     */
    @Test
    void declaresPostAsTheRequiredWeblogPermission() {
        assertEquals(List.of(org.apache.roller.weblogger.pojos.WeblogPermission.POST),
                new CategoriesApi().requiredWeblogPermissionActions());
        assertTrue(new CategoriesApi().isUserRequired());
        assertTrue(new CategoriesApi().isWeblogRequired());
    }

    @Test
    void deleteWithMoveToNamingTheSameCategoryNamesTheMoveToField() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogCategory category = aCategory(weblog, "Doomed");
        aCategory(weblog, "Other");
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(category);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/categories/{id}", "cat-1")
                        .param("moveTo", "cat-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("moveTo"));
    }
}
