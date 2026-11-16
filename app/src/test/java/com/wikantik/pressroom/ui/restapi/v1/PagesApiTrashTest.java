package com.wikantik.pressroom.ui.restapi.v1;

import java.sql.Timestamp;
import java.util.List;

import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.wikantik.pressroom.ui.restapi.ErrorFieldMatchers.errorFieldIs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The page trash through the API (spec 2026-11-15, Part 1), mirroring the
 * entry trash: DELETE trashes, restore and delete-forever are their own
 * POSTs, and a trashed page can be read only from the trash list. A write
 * to one is a 409 that names the trash; reading one by id is a 404.
 */
class PagesApiTrashTest {

    private Weblogger weblogger;
    private WeblogPageManager pages;
    private Weblog weblog;
    private Weblog other;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        weblogger = mock(Weblogger.class);
        pages = mock(WeblogPageManager.class);
        when(weblogger.getWeblogPageManager()).thenReturn(pages);
        when(weblogger.getWeblogEntryManager()).thenReturn(mock(WeblogEntryManager.class));
        weblog = weblog("myblog");
        other = weblog("someoneelse");
        PagesApi controller = new PagesApi();
        controller.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static Weblog weblog(String handle) {
        Weblog w = new Weblog();
        w.setId(handle + "-id");
        w.setHandle(handle);
        return w;
    }

    private WeblogPage page(Weblog owner, String id, String slug, WeblogPage.PubStatus status) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setId(id);
        page.setWeblog(owner);
        page.setSlug(slug);
        page.setTitle("Title of " + slug);
        page.setStatus(status);
        if (status == WeblogPage.PubStatus.TRASHED) {
            page.setTrashedAt(Timestamp.from(java.time.Instant.parse("2026-11-16T10:00:00Z")));
        }
        when(pages.getPage(id)).thenReturn(page);
        return page;
    }

    private MockHttpServletRequestBuilder on(MockHttpServletRequestBuilder request) {
        return request.requestAttr("actionWeblog", weblog);
    }

    private static tools.jackson.databind.JsonNode json(String body) {
        return new tools.jackson.databind.ObjectMapper().readTree(body);
    }

    // ---------------------------------------------------------------- DELETE

    @Test
    void deleteTrashesALivePage() throws Exception {
        WeblogPage live = page(weblog, "p1", "about", WeblogPage.PubStatus.PUBLISHED);

        mvc.perform(on(delete("/v1/weblogs/myblog/pages/{id}", "p1"))).andExpect(status().isNoContent());

        verify(pages).trashPage(live);
        verify(pages, never()).removePage(any());
        verify(weblogger).flush();
    }

    /** A retried DELETE must not move the purge clock. */
    @Test
    void deleteOfATrashedPageIsA409() throws Exception {
        page(weblog, "p1", "about", WeblogPage.PubStatus.TRASHED);

        String body = mvc.perform(on(delete("/v1/weblogs/myblog/pages/{id}", "p1")))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("already in the trash"), body);
        verify(pages, never()).trashPage(any());
        verify(pages, never()).removePage(any());
    }

    // ------------------------------------------------------------------ read

    @Test
    void getOfATrashedPageIsA404() throws Exception {
        page(weblog, "p1", "about", WeblogPage.PubStatus.TRASHED);

        mvc.perform(on(get("/v1/weblogs/myblog/pages/{id}", "p1"))).andExpect(status().isNotFound());
    }

    @Test
    void listWithStatusTrashedReturnsOnlyTrashWithItsStamp() throws Exception {
        WeblogPage trashed = page(weblog, "p1", "about", WeblogPage.PubStatus.TRASHED);
        when(pages.getTrashedPages(weblog)).thenReturn(List.of(trashed));

        String body = mvc.perform(on(get("/v1/weblogs/myblog/pages").param("status", "trashed")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode list = json(body);
        assertEquals(1, list.size());
        assertEquals("TRASHED", list.get(0).get("status").asString());
        assertEquals("2026-11-16T10:00:00Z", list.get(0).get("trashedAt").asString());
        verify(pages, never()).getPages(any());
    }

    /** Characterisation of the manager's contract as the API uses it: the plain list reads getPages, which holds no trash. */
    @Test
    void thePlainListNeverReadsTheTrash() throws Exception {
        WeblogPage live = page(weblog, "p1", "about", WeblogPage.PubStatus.DRAFT);
        when(pages.getPages(weblog)).thenReturn(List.of(live));

        String body = mvc.perform(on(get("/v1/weblogs/myblog/pages")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals(1, json(body).size());
        assertTrue(json(body).get(0).get("trashedAt") == null, "a live page carries no trashedAt");
        verify(pages, never()).getTrashedPages(any());
    }

    @Test
    void listWithAnyOtherStatusIsA400() throws Exception {
        for (String status : new String[] {"DRAFT", "PUBLISHED", "nonsense", ""}) {
            mvc.perform(on(get("/v1/weblogs/myblog/pages").param("status", status)))
                    .andExpect(status().isBadRequest())
                    .andExpect(errorFieldIs("status"));
        }
    }

    // ---------------------------------------------------------------- writes

    @Test
    void patchOfATrashedPageIsA409AndSavesNothing() throws Exception {
        WeblogPage trashed = page(weblog, "p1", "about", WeblogPage.PubStatus.TRASHED);

        String body = mvc.perform(on(patch("/v1/weblogs/myblog/pages/{id}", "p1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"New\",\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("restore it first"), body);
        assertEquals("Title of about", trashed.getTitle());
        assertEquals(WeblogPage.PubStatus.TRASHED, trashed.getStatus());
        verify(pages, never()).savePage(any());
    }

    @Test
    void partsOfATrashedPageIsA409() throws Exception {
        WeblogPage trashed = page(weblog, "p1", "series", WeblogPage.PubStatus.TRASHED);
        trashed.setStrand(true);

        mvc.perform(on(put("/v1/weblogs/myblog/pages/{id}/parts", "p1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"entries\":[]}"))
                .andExpect(status().isConflict());

        verify(weblogger.getWeblogEntryManager(), never()).setStrandParts(any(), any());
    }

    @Test
    void writingStatusTrashedIsA400OnCreateAndUpdate() throws Exception {
        page(weblog, "p1", "about", WeblogPage.PubStatus.DRAFT);

        mvc.perform(on(post("/v1/weblogs/myblog/pages"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"slug\":\"t\",\"status\":\"TRASHED\"}"))
                .andExpect(status().isBadRequest()).andExpect(errorFieldIs("status"));
        mvc.perform(on(patch("/v1/weblogs/myblog/pages/{id}", "p1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"trashed\"}"))
                .andExpect(status().isBadRequest()).andExpect(errorFieldIs("status"));

        verify(pages, never()).savePage(any());
    }

    @Test
    void aSlugHeldByTheTrashIsA409NamingTheTrash() throws Exception {
        WeblogPage holder = page(weblog, "p1", "about", WeblogPage.PubStatus.TRASHED);
        when(pages.getPageBySlug(weblog, "about")).thenReturn(holder);

        String body = mvc.perform(on(post("/v1/weblogs/myblog/pages"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"About\",\"slug\":\"about\"}"))
                .andExpect(status().isConflict()).andExpect(errorFieldIs("slug"))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("in the trash"), body);
        verify(pages, never()).savePage(any());
    }

    // --------------------------------------------------------------- restore

    @Test
    void restoreReturnsThePageAsADraft() throws Exception {
        WeblogPage trashed = page(weblog, "p1", "about", WeblogPage.PubStatus.TRASHED);
        org.mockito.Mockito.doAnswer(call -> {
            trashed.setStatus(WeblogPage.PubStatus.DRAFT);
            trashed.setTrashedAt(null);
            return null;
        }).when(pages).restorePage(trashed);

        String body = mvc.perform(on(post("/v1/weblogs/myblog/pages/{id}/restore", "p1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals("DRAFT", json(body).get("status").asString());
        verify(pages).restorePage(trashed);
        verify(weblogger).flush();
    }

    @Test
    void restoreOfALivePageIsA409() throws Exception {
        page(weblog, "p1", "about", WeblogPage.PubStatus.PUBLISHED);

        String body = mvc.perform(on(post("/v1/weblogs/myblog/pages/{id}/restore", "p1")))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("not trashed"), body);
        verify(pages, never()).restorePage(any());
    }

    // -------------------------------------------------------- delete forever

    @Test
    void deleteForeverRemovesATrashedPage() throws Exception {
        WeblogPage trashed = page(weblog, "p1", "about", WeblogPage.PubStatus.TRASHED);

        mvc.perform(on(post("/v1/weblogs/myblog/pages/{id}/delete-forever", "p1")))
                .andExpect(status().isNoContent());

        verify(pages).removePage(trashed);
        verify(weblogger).flush();
    }

    /** Deleting forever is a second step after trashing, never a way around it. */
    @Test
    void deleteForeverOfALivePageIsA409() throws Exception {
        page(weblog, "p1", "about", WeblogPage.PubStatus.DRAFT);

        String body = mvc.perform(on(post("/v1/weblogs/myblog/pages/{id}/delete-forever", "p1")))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("trash it first"), body);
        verify(pages, never()).removePage(any());
    }

    // ------------------------------------------------------------- ownership

    /** Another blog's page is a 404 on every route, trashed or live: what it holds is never said. */
    @Test
    void anotherBlogsPageIsA404OnEveryRouteTrashedOrNot() throws Exception {
        page(other, "theirs-trashed", "a", WeblogPage.PubStatus.TRASHED);
        page(other, "theirs-live", "b", WeblogPage.PubStatus.PUBLISHED);

        for (String id : new String[] {"theirs-trashed", "theirs-live", "no-such-page"}) {
            mvc.perform(on(get("/v1/weblogs/myblog/pages/{id}", id))).andExpect(status().isNotFound());
            mvc.perform(on(delete("/v1/weblogs/myblog/pages/{id}", id))).andExpect(status().isNotFound());
            mvc.perform(on(patch("/v1/weblogs/myblog/pages/{id}", id))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                    .andExpect(status().isNotFound());
            mvc.perform(on(put("/v1/weblogs/myblog/pages/{id}/parts", id))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"entries\":[]}"))
                    .andExpect(status().isNotFound());
            mvc.perform(on(post("/v1/weblogs/myblog/pages/{id}/restore", id))).andExpect(status().isNotFound());
            mvc.perform(on(post("/v1/weblogs/myblog/pages/{id}/delete-forever", id)))
                    .andExpect(status().isNotFound());
        }
        verify(pages, never()).trashPage(any());
        verify(pages, never()).restorePage(any());
        verify(pages, never()).removePage(any());
        verify(pages, never()).savePage(any());
    }
}
