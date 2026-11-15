package com.wikantik.pressroom.ui.restapi.v1;

import java.util.ArrayList;
import java.util.List;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.dto.PageDtos;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.wikantik.pressroom.ui.restapi.ErrorFieldMatchers.errorFieldIs;

class PagesApiTest {

    /**
     * A PAGE title is stored RAW -- the opposite of an entry title, which is
     * stored escaped. Templates escape page titles at render, so escaping
     * here would double-encode every one of them.
     */
    @Test
    void thePageTitleIsStoredRaw() {
        WeblogPage page = new WeblogPage();
        PageDtos.applyWrite(page, new PageDtos.PageWrite(null, "Cats & Dogs", null, null, null,
                null, null, null, null, null, null, null, null), java.util.Set.of());
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
            assertThrows(com.wikantik.pressroom.ui.restapi.ApiException.class,
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
        assertThrows(com.wikantik.pressroom.ui.restapi.ApiException.class,
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
        assertThrows(com.wikantik.pressroom.ui.restapi.ApiException.class,
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

    /** pressroom_weblogpage.slug/title are both varchar(255) NOT NULL (V014__weblog_pages.sql). */
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
        assertEquals(List.of(com.wikantik.pressroom.pojos.WeblogPermission.POST),
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
    void patchWithANegativeNavOrderIs400NamingNavOrder() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogPage page = aPage(weblog, "page-1", "about", "About");
        when(weblogger.getWeblogPageManager().getPage("page-1")).thenReturn(page);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/pages/{id}", "page-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"navOrder\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("navOrder"));
        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
    }

    @Test
    void patchAcceptsANavOrderAndReadsItBack() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogPage page = aPage(weblog, "page-1", "about", "About");
        when(weblogger.getWeblogPageManager().getPage("page-1")).thenReturn(page);

        String body = mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/pages/{id}", "page-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"navOrder\":2}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(2, new tools.jackson.databind.ObjectMapper().readTree(body).get("navOrder").asInt());
        assertEquals(2, page.getNavOrder());
    }

    @Test
    void postWithANegativeNavOrderIs400NamingNavOrder() throws Exception {
        mockMvc(controllerFor(mockedWeblogger()))
                .perform(post("/v1/weblogs/myblog/pages")
                        .requestAttr("actionWeblog", aWeblog("myblog"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"about\",\"title\":\"About\",\"navOrder\":-3}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("navOrder"));
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
    // -----------------------------------------------------------------
    // Strands (spec 2026-11-15, release 2): the page's strand flag, its
    // read-only parts, and PUT .../parts, which replaces the membership.
    // -----------------------------------------------------------------

    /** A strand page and a stateful stand-in for the manager's membership. */
    private static final class StrandFixture {
        final Weblogger weblogger = mock(Weblogger.class);
        final WeblogPageManager pageManager = mock(WeblogPageManager.class);
        final WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        final Weblog weblog = aWeblog("myblog");
        final WeblogPage strand = aPage(weblog, "strand-1", "series", "The Series");
        final List<WeblogEntry> members = new ArrayList<>();

        StrandFixture() throws Exception {
            when(weblogger.getWeblogPageManager()).thenReturn(pageManager);
            when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
            strand.setStrand(true);
            when(pageManager.getPage("strand-1")).thenReturn(strand);
            when(entryManager.getStrandParts(strand, false)).thenAnswer(inv -> List.copyOf(members));
            when(entryManager.getStrandPartCount(strand)).thenAnswer(inv -> (long) members.size());
            doAnswer(inv -> {
                List<WeblogEntry> parts = inv.getArgument(1);
                members.forEach(m -> {
                    m.setStrandPageId(null);
                    m.setStrandPosition(null);
                });
                members.clear();
                int position = 1;
                for (WeblogEntry part : parts) {
                    part.setStrandPageId("strand-1");
                    part.setStrandPosition(position++);
                    members.add(part);
                }
                return null;
            }).when(entryManager).setStrandParts(eq(strand), any());
        }

        WeblogEntry post(String id, Weblog of) throws Exception {
            WeblogEntry entry = new WeblogEntry();
            entry.setId(id);
            entry.setWebsite(of);
            entry.setAnchor("anchor-" + id);
            entry.setTitle("Title " + id);
            entry.setStatus(WeblogEntry.PubStatus.PUBLISHED);
            when(entryManager.getWeblogEntry(id)).thenReturn(entry);
            return entry;
        }

        PagesApi controller() {
            PagesApi controller = new PagesApi();
            controller.weblogger = weblogger;
            return controller;
        }
    }

    private tools.jackson.databind.JsonNode json(String body) {
        return new tools.jackson.databind.ObjectMapper().readTree(body);
    }

    private String putParts(StrandFixture f, String page, String json, int expectedStatus) throws Exception {
        org.springframework.mock.web.MockHttpServletResponse response = mockMvc(f.controller())
                .perform(put("/v1/weblogs/myblog/pages/{id}/parts", page)
                        .requestAttr("actionWeblog", f.weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse();
        assertEquals(expectedStatus, response.getStatus(), response.getContentAsString());
        return response.getContentAsString();
    }

    /** Criterion 18's read side: strand round-trips, parts list every member in order with its status. */
    @Test
    void aStrandPageShowsItsFlagAndEveryPartInOrder() throws Exception {
        StrandFixture f = new StrandFixture();
        WeblogEntry first = f.post("e1", f.weblog);
        WeblogEntry draft = f.post("e2", f.weblog);
        draft.setStatus(WeblogEntry.PubStatus.DRAFT);
        first.setStrandPosition(1);
        draft.setStrandPosition(3);
        f.members.addAll(List.of(first, draft));

        tools.jackson.databind.JsonNode view = json(mockMvc(f.controller())
                .perform(get("/v1/weblogs/myblog/pages/{id}", "strand-1").requestAttr("actionWeblog", f.weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertTrue(view.get("strand").asBoolean());
        tools.jackson.databind.JsonNode parts = view.get("parts");
        assertEquals(2, parts.size());
        assertEquals("e1", parts.get(0).get("id").asString());
        assertEquals("anchor-e1", parts.get(0).get("anchor").asString());
        assertEquals("Title e1", parts.get(0).get("title").asString());
        assertEquals("PUBLISHED", parts.get(0).get("status").asString());
        assertEquals(1, parts.get(0).get("position").asInt());
        assertEquals("DRAFT", parts.get(1).get("status").asString());
        assertEquals(3, parts.get(1).get("position").asInt());
    }

    /** A plain page reads strand false and an empty parts list, without asking for any. */
    @Test
    void aPlainPageShowsStrandFalseAndNoParts() throws Exception {
        StrandFixture f = new StrandFixture();
        f.strand.setStrand(false);

        tools.jackson.databind.JsonNode view = json(mockMvc(f.controller())
                .perform(get("/v1/weblogs/myblog/pages/{id}", "strand-1").requestAttr("actionWeblog", f.weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertFalse(view.get("strand").asBoolean());
        assertEquals(0, view.get("parts").size());
        verify(f.entryManager, never()).getStrandParts(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void strandIsSetOnCreateAndOnUpdate() throws Exception {
        StrandFixture f = new StrandFixture();
        f.strand.setStrand(false);

        String created = mockMvc(f.controller())
                .perform(post("/v1/weblogs/myblog/pages")
                        .requestAttr("actionWeblog", f.weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"walks\",\"title\":\"Walks\",\"strand\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertTrue(json(created).get("strand").asBoolean());

        mockMvc(f.controller())
                .perform(patch("/v1/weblogs/myblog/pages/{id}", "strand-1")
                        .requestAttr("actionWeblog", f.weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strand\":true}"))
                .andExpect(status().isOk());
        assertTrue(f.strand.getStrand());
        verify(f.pageManager).savePage(f.strand);
    }

    /** Unticking a strand that has parts is refused, on strand, and nothing is saved. */
    @Test
    void unsettingStrandWhileItHasPartsIs400OnStrand() throws Exception {
        StrandFixture f = new StrandFixture();
        WeblogEntry part = f.post("e1", f.weblog);
        part.setStatus(WeblogEntry.PubStatus.TRASHED);
        f.members.add(part);

        mockMvc(f.controller())
                .perform(patch("/v1/weblogs/myblog/pages/{id}", "strand-1")
                        .requestAttr("actionWeblog", f.weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strand\":false,\"title\":\"Renamed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("strand"));

        assertTrue(f.strand.getStrand());
        assertEquals("The Series", f.strand.getTitle());
        verify(f.pageManager, never()).savePage(any());
    }

    @Test
    void unsettingStrandWithNoPartsIsAllowed() throws Exception {
        StrandFixture f = new StrandFixture();

        mockMvc(f.controller())
                .perform(patch("/v1/weblogs/myblog/pages/{id}", "strand-1")
                        .requestAttr("actionWeblog", f.weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strand\":false}"))
                .andExpect(status().isOk());

        assertFalse(f.strand.getStrand());
    }

    /** As in the page editor: a site page is never a strand. */
    @Test
    void aSitePageCannotBecomeAStrand() throws Exception {
        StrandFixture f = new StrandFixture();
        f.weblog.setSiteSpace(true);
        f.strand.setStrand(false);

        mockMvc(f.controller())
                .perform(patch("/v1/weblogs/myblog/pages/{id}", "strand-1")
                        .requestAttr("actionWeblog", f.weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strand\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("strand"));

        assertFalse(f.strand.getStrand());
        verify(f.pageManager, never()).savePage(any());
    }

    /** Criterion 10/16 happy path: [C, A] become parts 1 and 2, in that order, and the view says so. */
    @Test
    void setPartsReplacesTheMembershipInTheGivenOrder() throws Exception {
        StrandFixture f = new StrandFixture();
        WeblogEntry a = f.post("a", f.weblog);
        f.post("b", f.weblog);
        WeblogEntry c = f.post("c", f.weblog);

        tools.jackson.databind.JsonNode view = json(putParts(f, "strand-1", "{\"entries\":[\"c\",\"a\"]}", 200));

        verify(f.entryManager).setStrandParts(f.strand, List.of(c, a));
        verify(f.weblogger).flush();
        tools.jackson.databind.JsonNode parts = view.get("parts");
        assertEquals(2, parts.size());
        assertEquals("c", parts.get(0).get("id").asString());
        assertEquals(1, parts.get(0).get("position").asInt());
        assertEquals("a", parts.get(1).get("id").asString());
        assertEquals(2, parts.get(1).get("position").asInt());
    }

    /** Review Focus 3: an empty list removes every part. */
    @Test
    void setPartsWithAnEmptyListRemovesEveryPart() throws Exception {
        StrandFixture f = new StrandFixture();
        WeblogEntry a = f.post("a", f.weblog);
        a.setStrandPageId("strand-1");
        a.setStrandPosition(1);
        f.members.add(a);

        tools.jackson.databind.JsonNode view = json(putParts(f, "strand-1", "{\"entries\":[]}", 200));

        verify(f.entryManager).setStrandParts(f.strand, List.of());
        assertEquals(0, view.get("parts").size());
        assertNull(a.getStrandPageId());
    }

    /**
     * Criterion 16 and Review Focus 3: a post of another blog, one in another
     * strand, a duplicate and an unknown id are each one errors entry on
     * entries naming the id, and nothing changes. Another blog's post reads
     * exactly as an unknown id does: the API does not say what exists elsewhere.
     */
    @Test
    void setPartsRefusesEveryBadIdByNameAndChangesNothing() throws Exception {
        StrandFixture f = new StrandFixture();
        f.post("a", f.weblog);
        f.post("foreign", aWeblog("elsewhere"));
        WeblogEntry taken = f.post("taken", f.weblog);
        taken.setStrandPageId("strand-2");
        taken.setStrandPosition(1);

        String body = putParts(f, "strand-1",
                "{\"entries\":[\"a\",\"foreign\",\"taken\",\"a\",\"ghost\"]}", 400);

        tools.jackson.databind.JsonNode errors = json(body).get("errors");
        assertEquals(4, errors.size(), body);
        String[] named = {"foreign", "taken", "a", "ghost"};
        for (int i = 0; i < named.length; i++) {
            assertEquals("entries", errors.get(i).get("field").asString());
            assertTrue(errors.get(i).get("message").asString().contains("'" + named[i] + "'"), body);
        }
        assertEquals(errors.get(0).get("message").asString().replace("foreign", "ghost"),
                errors.get(3).get("message").asString(), "another blog's post reads as unknown");
        assertTrue(errors.get(1).get("message").asString().contains("another strand"), body);
        assertTrue(errors.get(2).get("message").asString().contains("twice"), body);
        verify(f.entryManager, never()).setStrandParts(any(), any());
        verify(f.weblogger, never()).flush();
        assertEquals("strand-2", taken.getStrandPageId());
    }

    @Test
    void setPartsOnAPageThatIsNotAStrandIs400OnEntries() throws Exception {
        StrandFixture f = new StrandFixture();
        f.strand.setStrand(false);
        f.post("a", f.weblog);

        String body = putParts(f, "strand-1", "{\"entries\":[\"a\"]}", 400);

        assertEquals("entries", json(body).get("errors").get(0).get("field").asString(), body);
        verify(f.entryManager, never()).setStrandParts(any(), any());
    }

    /**
     * A list longer than any strand is refused whole before a single lookup:
     * 501 ids cost 501 queries otherwise, and nothing changes.
     */
    @Test
    void setPartsWithMoreThan500IdsIs400OnEntriesAndChangesNothing() throws Exception {
        StrandFixture f = new StrandFixture();
        StringBuilder json = new StringBuilder("{\"entries\":[");
        for (int i = 0; i < 501; i++) {
            json.append(i == 0 ? "" : ",").append("\"p").append(i).append('"');
        }
        json.append("]}");

        String body = putParts(f, "strand-1", json.toString(), 400);

        tools.jackson.databind.JsonNode error = json(body).get("errors").get(0);
        assertEquals("entries", error.get("field").asString(), body);
        assertEquals("At most 500 parts.", error.get("message").asString(), body);
        verify(f.entryManager, never()).getWeblogEntry(any());
        verify(f.entryManager, never()).setStrandParts(any(), any());
        verify(f.weblogger, never()).flush();
    }

    /** null never means a value: without entries there is nothing to set, so it is a 400, not "no parts". */
    @Test
    void setPartsWithoutEntriesIs400() throws Exception {
        StrandFixture f = new StrandFixture();

        String body = putParts(f, "strand-1", "{}", 400);

        assertEquals("entries", json(body).get("errors").get(0).get("field").asString());
        verify(f.entryManager, never()).setStrandParts(any(), any());
    }

    /** By-id ownership: another blog's page is the 404 an unknown id gets. */
    @Test
    void setPartsOnAnotherBlogsPageIs404() throws Exception {
        StrandFixture f = new StrandFixture();
        WeblogPage foreign = aPage(aWeblog("elsewhere"), "strand-9", "theirs", "Theirs");
        foreign.setStrand(true);
        when(f.pageManager.getPage("strand-9")).thenReturn(foreign);

        putParts(f, "strand-9", "{\"entries\":[]}", 404);

        verify(f.entryManager, never()).setStrandParts(any(), any());
    }
}
