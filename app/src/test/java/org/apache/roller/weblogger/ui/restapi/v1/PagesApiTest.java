package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.business.WeblogPageManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.apache.roller.weblogger.ui.restapi.dto.PageDtos;
import org.junit.jupiter.api.Test;
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

class PagesApiTest {

    /**
     * A PAGE title is stored RAW -- the opposite of an entry title, which is
     * stored escaped. Templates escape page titles at render, so escaping
     * here would double-encode every one of them.
     */
    @Test
    void thePageTitleIsStoredRaw() {
        WeblogPage page = new WeblogPage();
        PageDtos.applyWrite(page, new PageDtos.PageWrite(null, "Cats & Dogs", null, null, null));
        assertEquals("Cats & Dogs", page.getTitle());
    }

    /**
     * ReservedSlugs is the single source of truth shared by the save
     * validator and the request parser, so a slug that would collide can
     * never be stored in the first place.
     */
    @Test
    void aReservedSlugIsRefused() {
        for (String slug : new String[] {"entry", "category", "tags", "feed", "ai", "en_US"}) {
            assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                    () -> PageDtos.requireUsableSlug(slug),
                    slug + " must be refused");
        }
    }

    @Test
    void anOrdinarySlugIsAccepted() {
        assertDoesNotThrow(() -> PageDtos.requireUsableSlug("about"));
    }

    @Test
    void aBlankSlugIsRefused() {
        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> PageDtos.requireUsableSlug("  "));
    }

    /**
     * JPAWeblogPageManagerImpl.savePage separately refuses a slug containing
     * '/' -- a check ReservedSlugs.isReserved does not make, since it only
     * tests membership in a fixed set of whole names. Without mirroring this
     * here, a slug like "foo/bar" reaches savePage, which throws a bare
     * WebloggerException the generic handler can only render as an opaque
     * 500 -- the same class of gap requireUsableSlug already closes for
     * blank and reserved slugs.
     */
    @Test
    void aSlugContainingASlashIsRefused() {
        assertThrows(org.apache.roller.weblogger.ui.restapi.ApiException.class,
                () -> PageDtos.requireUsableSlug("foo/bar"));
    }

    // -----------------------------------------------------------------
    // Controller-level tests: routing, status codes, the ownership check
    // and JSON shape -- none of which the unit tests above can see. These
    // run against a Mockito-mocked Weblogger, matching CategoriesApiTest's
    // standalone MockMvc style; actionWeblog is injected as a request
    // attribute rather than resolved by a real interceptor.
    // -----------------------------------------------------------------

    private MockMvc mockMvc(PagesApi controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Weblogger mockedWeblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogPageManager pageManager = mock(WeblogPageManager.class);
        when(weblogger.getWeblogPageManager()).thenReturn(pageManager);
        return weblogger;
    }

    private PagesApi controllerFor(Weblogger weblogger) {
        PagesApi controller = new PagesApi();
        controller.weblogger = weblogger;
        return controller;
    }

    private static Weblog aWeblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setId(handle + "-id");
        weblog.setHandle(handle);
        return weblog;
    }

    private static WeblogPage aPage(Weblog weblog, String id, String slug, String title) {
        WeblogPage page = new WeblogPage();
        page.setId(id);
        page.setWeblog(weblog);
        page.setSlug(slug);
        page.setTitle(title);
        return page;
    }

    @Test
    void listReturnsPageViews() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogPage page = aPage(weblog, "page-1", "about", "About Us");
        when(weblogger.getWeblogPageManager().getPages(weblog)).thenReturn(List.of(page));

        String body = mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/pages").requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(1, json.size());
        assertEquals("about", json.get(0).get("slug").asString());
        assertEquals("About Us", json.get(0).get("title").asString());
    }

    @Test
    void getIsNotFoundWhenThePageBelongsToAnotherWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        Weblog anotherWeblog = aWeblog("someoneelse");
        WeblogPage foreign = aPage(anotherWeblog, "page-1", "about", "About");
        when(weblogger.getWeblogPageManager().getPage("page-1")).thenReturn(foreign);

        mockMvc(controllerFor(weblogger))
                .perform(get("/v1/weblogs/myblog/pages/{id}", "page-1")
                        .requestAttr("actionWeblog", thisWeblog))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void postCreatesAPage() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/pages")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"about\",\"title\":\"About Us & More\",\"text\":\"Hello\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        verify(weblogger.getWeblogPageManager()).savePage(any());
        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("about", json.get("slug").asString());
        // Stored raw: no escaping happened on the way in.
        assertEquals("About Us & More", json.get("title").asString());
        verify(weblogger).flush();
    }

    /** roller_weblogpage.slug/title are both varchar(255) NOT NULL (V014__weblog_pages.sql). */
    @Test
    void postWithASlugLongerThanTheColumnIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(256);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/pages")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"" + tooLong + "\",\"title\":\"About\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
        verify(weblogger, never()).flush();
    }

    @Test
    void postWithATitleLongerThanTheColumnIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        String tooLong = "a".repeat(256);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/pages")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"about\",\"title\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
        verify(weblogger, never()).flush();
    }

    @Test
    void postWithABlankTitleIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/pages")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"about\",\"title\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
        verify(weblogger, never()).flush();
    }

    /**
     * A reserved slug must be a 400 from the controller, not a bare
     * WebloggerException bubbling out of savePage as an opaque 500 --
     * exercised end to end through the real HTTP dispatch this time, not
     * just PageDtos.requireUsableSlug in isolation.
     */
    @Test
    void postWithAReservedSlugIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/pages")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"category\",\"title\":\"Nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
    }

    /**
     * savePage does not itself duplicate-check a slug -- see PagesApi's
     * requireSlugAvailable javadoc. Without this guard a colliding slug
     * would reach the database's unique constraint and bubble out as a bare
     * WebloggerException, an opaque 500.
     */
    @Test
    void postWithADuplicateSlugIsConflict() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogPage existing = aPage(weblog, "page-1", "about", "About");
        when(weblogger.getWeblogPageManager().getPageBySlug(weblog, "about")).thenReturn(existing);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/pages")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"about\",\"title\":\"Second About\"}"))
                .andExpect(status().isConflict())
                .andExpect(errorFieldIs("slug"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
    }

    /**
     * The controller-level counterpart to aSlugContainingASlashIsRefused --
     * exercised end to end through real HTTP dispatch, proving requireUsableSlug
     * is actually wired into create, not just correct in isolation.
     */
    @Test
    void postWithASlashInTheSlugIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/pages")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"foo/bar\",\"title\":\"Nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
    }

    @Test
    void patchUpdatesAPageFoundThroughOwnership() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogPage page = aPage(weblog, "page-1", "about", "Old Title");
        when(weblogger.getWeblogPageManager().getPage("page-1")).thenReturn(page);

        String body = mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/pages/{id}", "page-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"New Title\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("New Title", json.get("title").asString());
        // The slug was never present on the request, so it must not have
        // been re-validated or re-looked-up: absent means absent.
        verify(weblogger.getWeblogPageManager(), never()).getPageBySlug(any(), any());
        verify(weblogger.getWeblogPageManager()).savePage(page);
        verify(weblogger).flush();
    }

    @Test
    void patchIsNotFoundWhenThePageBelongsToAnotherWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        Weblog anotherWeblog = aWeblog("someoneelse");
        WeblogPage foreign = aPage(anotherWeblog, "page-1", "about", "About");
        when(weblogger.getWeblogPageManager().getPage("page-1")).thenReturn(foreign);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/pages/{id}", "page-1")
                        .requestAttr("actionWeblog", thisWeblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Hijacked\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
    }

    @Test
    void patchChangingSlugToADuplicateIsConflict() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogPage page = aPage(weblog, "page-1", "about", "About");
        WeblogPage taken = aPage(weblog, "page-2", "contact", "Contact");
        when(weblogger.getWeblogPageManager().getPage("page-1")).thenReturn(page);
        when(weblogger.getWeblogPageManager().getPageBySlug(weblog, "contact")).thenReturn(taken);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/pages/{id}", "page-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"contact\"}"))
                .andExpect(status().isConflict())
                .andExpect(errorFieldIs("slug"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
        assertEquals("about", page.getSlug(), "the in-memory page must be untouched on refusal");
    }

    /**
     * A PATCH re-submitting a page's OWN unchanged slug must not be refused
     * as colliding with itself -- getPageBySlug legitimately finds the page
     * being edited, and requireSlugAvailable's excludingId parameter exists
     * precisely to tell that apart from a genuine collision.
     */
    @Test
    void patchKeepingTheSameSlugIsNotAConflict() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogPage page = aPage(weblog, "page-1", "about", "About");
        when(weblogger.getWeblogPageManager().getPage("page-1")).thenReturn(page);
        when(weblogger.getWeblogPageManager().getPageBySlug(weblog, "about")).thenReturn(page);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/pages/{id}", "page-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"about\"}"))
                .andExpect(status().isOk());

        verify(weblogger.getWeblogPageManager()).savePage(page);
    }

    @Test
    void patchWithASlashInTheSlugIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogPage page = aPage(weblog, "page-1", "about", "About");
        when(weblogger.getWeblogPageManager().getPage("page-1")).thenReturn(page);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/pages/{id}", "page-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"foo/bar\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
        assertEquals("about", page.getSlug(), "the in-memory page must be untouched on refusal");
    }

    @Test
    void patchWithABlankTitleIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogPage page = aPage(weblog, "page-1", "about", "About");
        when(weblogger.getWeblogPageManager().getPage("page-1")).thenReturn(page);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/pages/{id}", "page-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
    }

    @Test
    void deleteRemovesAPageFoundThroughOwnership() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogPage page = aPage(weblog, "page-1", "about", "About");
        when(weblogger.getWeblogPageManager().getPage("page-1")).thenReturn(page);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/pages/{id}", "page-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isNoContent());

        verify(weblogger.getWeblogPageManager()).removePage(page);
        verify(weblogger).flush();
    }

    @Test
    void deleteIsNotFoundWhenThePageBelongsToAnotherWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        Weblog anotherWeblog = aWeblog("someoneelse");
        WeblogPage foreign = aPage(anotherWeblog, "page-1", "about", "About");
        when(weblogger.getWeblogPageManager().getPage("page-1")).thenReturn(foreign);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/pages/{id}", "page-1")
                        .requestAttr("actionWeblog", thisWeblog))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogPageManager(), never()).removePage(any());
        verify(weblogger, never()).flush();
    }

    /**
     * Wave-level gap (Finding 3, see CategoriesApiTest): a malformed JSON
     * body must be a 400, not an opaque 500 from the generic handler.
     */
    @Test
    void aMalformedRequestBodyIsBadRequestNotAnOpaque500() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/pages")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
    }

    /**
     * PagesApi declares UISecurityEnforced.requiredWeblogPermissionActions
     * as POST: pages are blog-wide structure, the same level CategoriesApi
     * requires.
     */
    @Test
    void declaresPostAsTheRequiredWeblogPermission() {
        assertEquals(List.of(org.apache.roller.weblogger.pojos.WeblogPermission.POST),
                new PagesApi().requiredWeblogPermissionActions());
        assertTrue(new PagesApi().isUserRequired());
        assertTrue(new PagesApi().isWeblogRequired());
    }

    @Test
    void postWithABlankTitleNamesTheTitleField() throws Exception {
        mockMvc(controllerFor(mockedWeblogger()))
                .perform(post("/v1/weblogs/myblog/pages")
                        .requestAttr("actionWeblog", aWeblog("myblog"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"about\",\"title\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("title"));
    }

    @Test
    void postWithAReservedSlugNamesTheSlugField() throws Exception {
        mockMvc(controllerFor(mockedWeblogger()))
                .perform(post("/v1/weblogs/myblog/pages")
                        .requestAttr("actionWeblog", aWeblog("myblog"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"category\",\"title\":\"Nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("slug"));
    }

    @Test
    void postWithASlashInTheSlugNamesTheSlugField() throws Exception {
        mockMvc(controllerFor(mockedWeblogger()))
                .perform(post("/v1/weblogs/myblog/pages")
                        .requestAttr("actionWeblog", aWeblog("myblog"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"foo/bar\",\"title\":\"Nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("slug"));
    }

    @Test
    void postWithAnUnknownStatusNamesTheStatusField() throws Exception {
        mockMvc(controllerFor(mockedWeblogger()))
                .perform(post("/v1/weblogs/myblog/pages")
                        .requestAttr("actionWeblog", aWeblog("myblog"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"about\",\"title\":\"About\",\"status\":\"bogus\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("status"));
    }

    @Test
    void patchWithABlankTitleNamesTheTitleField() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogPage page = aPage(weblog, "page-1", "about", "About");
        when(weblogger.getWeblogPageManager().getPage("page-1")).thenReturn(page);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/pages/{id}", "page-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("title"));
    }
}
