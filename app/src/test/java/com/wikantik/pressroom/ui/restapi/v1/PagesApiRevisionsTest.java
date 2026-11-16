package com.wikantik.pressroom.ui.restapi.v1;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.wikantik.pressroom.business.ContentHashes;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPageRevision;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.wikantik.pressroom.ui.restapi.ErrorFieldMatchers.errorFieldIs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Page revisions and the page stale-write guard through the API (spec
 * 2026-11-15, Part 2), mirroring the entry routes: list, read with a diff,
 * restore; contentHash on every view and expectedContentHash on a write.
 */
class PagesApiRevisionsTest {

    private Weblogger weblogger;
    private WeblogPageManager pages;
    private Weblog weblog;
    private User user;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        weblogger = mock(Weblogger.class);
        pages = mock(WeblogPageManager.class);
        when(weblogger.getWeblogPageManager()).thenReturn(pages);
        when(weblogger.getWeblogEntryManager()).thenReturn(mock(WeblogEntryManager.class));
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        user = new User();
        user.setUserName("alice");
        PagesApi controller = new PagesApi();
        controller.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static String hash(String content) {
        return ContentHashes.sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    private WeblogPage page(String id, String title, String content) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setId(id);
        page.setWeblog(weblog);
        page.setSlug(id);
        page.setTitle(title);
        page.setContent(content);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        when(pages.getPage(id)).thenReturn(page);
        when(pages.lockPageContent(page)).thenAnswer(call -> page.getContent());
        return page;
    }

    private WeblogPageRevision revision(String id, WeblogPage of, String title, String content) throws Exception {
        WeblogPageRevision revision = new WeblogPageRevision();
        revision.setId(id);
        revision.setPage(of);
        revision.setTitle(title);
        revision.setContent(content);
        revision.setCreator("bob");
        revision.setNote("why");
        revision.setCreated(Timestamp.from(Instant.parse("2026-11-16T10:00:00Z")));
        when(pages.getRevision(id)).thenReturn(revision);
        return revision;
    }

    private MockHttpServletRequestBuilder on(MockHttpServletRequestBuilder request) {
        return request.requestAttr("actionWeblog", weblog).requestAttr("authenticatedUser", user);
    }

    private static tools.jackson.databind.JsonNode json(String body) {
        return new tools.jackson.databind.ObjectMapper().readTree(body);
    }

    private String patchBody(String id, String body, int expected) throws Exception {
        var response = mvc.perform(on(patch("/v1/weblogs/myblog/pages/{id}", id))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString());
        return response.getContentAsString();
    }

    // ----------------------------------------------------------- contentHash

    @Test
    void aPageViewCarriesTheHashOfItsContent() throws Exception {
        page("p1", "About", "The body");

        String body = mvc.perform(on(get("/v1/weblogs/myblog/pages/{id}", "p1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals(hash("The body"), json(body).get("contentHash").asString());
    }

    @Test
    void aPageWithNoContentCarriesNoHash() throws Exception {
        page("p1", "About", null);

        String body = mvc.perform(on(get("/v1/weblogs/myblog/pages/{id}", "p1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertNull(json(body).get("contentHash"));
    }

    // ------------------------------------------------------ the stale write

    @Test
    void anUpdateWithTheCurrentHashSavesAsTheCaller() throws Exception {
        WeblogPage page = page("p1", "About", "v1");

        String body = patchBody("p1", "{\"text\":\"v2\",\"expectedContentHash\":\"" + hash("v1").toUpperCase(java.util.Locale.ROOT)
                + "\",\"revisionNote\":\"  tidy  \"}", 200);

        assertEquals("v2", page.getContent());
        assertEquals(hash("v2"), json(body).get("contentHash").asString());
        verify(pages).savePage(page, user, "tidy");
    }

    @Test
    void anUpdateWithAStaleHashIsA409PageChangedAndSavesNothing() throws Exception {
        WeblogPage page = page("p1", "About", "v2 by someone else");

        String body = patchBody("p1", "{\"text\":\"mine\",\"expectedContentHash\":\"" + hash("v1") + "\"}", 409);

        tools.jackson.databind.JsonNode problem = json(body);
        assertTrue(problem.get("type").asString().endsWith("/page-changed"), body);
        assertEquals(hash("v2 by someone else"), problem.get("currentContentHash").asString());
        assertEquals("v2 by someone else", page.getContent());
        verify(pages, never()).savePage(any(), any(), any());
        verify(pages, never()).savePage(any(), any(), any());
    }

    /** The content changed between the request's read and its write: caught under the row lock. */
    @Test
    void aChangeCommittedDuringTheRequestIsCaughtUnderTheLock() throws Exception {
        WeblogPage page = page("p1", "About", "v1");
        when(pages.lockPageContent(page)).thenReturn("v1 edited meanwhile");

        String body = patchBody("p1", "{\"text\":\"mine\",\"expectedContentHash\":\"" + hash("v1") + "\"}", 409);

        assertEquals(hash("v1 edited meanwhile"), json(body).get("currentContentHash").asString());
        verify(pages, never()).savePage(any(), any(), any());
    }

    @Test
    void aHashThatIsNotAHashIsA400NotAConflict() throws Exception {
        page("p1", "About", "v1");

        mvc.perform(on(patch("/v1/weblogs/myblog/pages/{id}", "p1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"x\",\"expectedContentHash\":\"nope\"}"))
                .andExpect(status().isBadRequest()).andExpect(errorFieldIs("expectedContentHash"));

        verify(pages, never()).savePage(any(), any(), any());
    }

    @Test
    void anUpdateWithoutAHashIsUnguardedAsBefore() throws Exception {
        WeblogPage page = page("p1", "About", "v1");

        patchBody("p1", "{\"text\":\"v2\"}", 200);

        verify(pages).savePage(page, user, null);
        verify(pages, never()).lockPageContent(any());
    }

    @Test
    void aRevisionNoteLongerThanItsColumnIsA400() throws Exception {
        page("p1", "About", "v1");

        mvc.perform(on(patch("/v1/weblogs/myblog/pages/{id}", "p1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"v2\",\"revisionNote\":\"" + "n".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(errorFieldIs("revisionNote"));

        verify(pages, never()).savePage(any(), any(), any());
    }

    @Test
    void aCreateRefusesTheTwoUpdateOnlyFields() throws Exception {
        mvc.perform(on(post("/v1/weblogs/myblog/pages"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"slug\":\"t\",\"revisionNote\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(errorFieldIs("revisionNote"));
        mvc.perform(on(post("/v1/weblogs/myblog/pages"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"slug\":\"t\",\"expectedContentHash\":\"" + hash("x") + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(errorFieldIs("expectedContentHash"));

        verify(pages, never()).savePage(any(), any(), any());
    }

    @Test
    void aCreateIsSavedAsTheCaller() throws Exception {
        mvc.perform(on(post("/v1/weblogs/myblog/pages"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"T\",\"slug\":\"t\"}"))
                .andExpect(status().isCreated());

        verify(pages).savePage(any(WeblogPage.class), eq(user), isNull());
    }

    // ------------------------------------------------------------ revisions

    @Test
    void theRevisionListIsSummariesWithoutContent() throws Exception {
        WeblogPage page = page("p1", "About", "v3");
        WeblogPageRevision newer = revision("r2", page, "About", "v2");
        WeblogPageRevision older = revision("r1", page, "Abou", "v1");
        when(pages.getRevisions(page)).thenReturn(List.of(newer, older));

        String body = mvc.perform(on(get("/v1/weblogs/myblog/pages/{id}/revisions", "p1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode list = json(body);
        assertEquals(2, list.size());
        assertEquals("r2", list.get(0).get("id").asString());
        assertEquals("bob", list.get(0).get("creator").asString());
        assertEquals("why", list.get(0).get("note").asString());
        assertEquals("About", list.get(0).get("title").asString());
        assertEquals("2026-11-16T10:00:00Z", list.get(0).get("created").asString());
        assertNull(list.get(0).get("text"), "the list carries no content");
        assertEquals("Abou", list.get(1).get("title").asString());
    }

    @Test
    void oneRevisionCarriesItsContentAndADiffToThePageNow() throws Exception {
        WeblogPage page = page("p1", "About", "line one\nline two changed\n");
        revision("r1", page, "Abou", "line one\nline two\n");

        String body = mvc.perform(on(get("/v1/weblogs/myblog/pages/{id}/revisions/{rid}", "p1", "r1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode view = json(body);
        assertEquals("Abou", view.get("title").asString());
        assertEquals("line one\nline two\n", view.get("text").asString());
        String diff = view.get("diff").asString();
        assertTrue(diff.contains("-line two") && diff.contains("+line two changed"), diff);
        assertTrue(diff.contains("revision r1") && diff.contains("current"), diff);
        assertNull(view.get("summary"), "a page has no summary");
    }

    @Test
    void againstDiffsToAnotherRevisionOfTheSamePage() throws Exception {
        WeblogPage page = page("p1", "About", "now");
        revision("r1", page, "T", "alpha\n");
        revision("r2", page, "T", "beta\n");

        String body = mvc.perform(on(get("/v1/weblogs/myblog/pages/{id}/revisions/{rid}", "p1", "r1")
                        .param("against", "r2")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String diff = json(body).get("diff").asString();
        assertTrue(diff.contains("-alpha") && diff.contains("+beta") && diff.contains("revision r2"), diff);
    }

    /** Another page's revision is the 404 an unknown id gets, on read, as against, and on restore. */
    @Test
    void anotherPagesRevisionIsA404Everywhere() throws Exception {
        WeblogPage page = page("p1", "About", "now");
        WeblogPage sibling = page("p2", "Contact", "c");
        revision("mine", page, "T", "a");
        revision("theirs", sibling, "T", "b");

        for (String rid : new String[] {"theirs", "no-such-revision"}) {
            mvc.perform(on(get("/v1/weblogs/myblog/pages/{id}/revisions/{rid}", "p1", rid)))
                    .andExpect(status().isNotFound());
            mvc.perform(on(get("/v1/weblogs/myblog/pages/{id}/revisions/{rid}", "p1", "mine").param("against", rid)))
                    .andExpect(status().isNotFound());
            mvc.perform(on(post("/v1/weblogs/myblog/pages/{id}/revisions/{rid}/restore", "p1", rid)))
                    .andExpect(status().isNotFound());
        }
        assertEquals("now", page.getContent());
        verify(pages, never()).savePage(any(), any(), any());
    }

    // -------------------------------------------------------------- restore

    @Test
    void restorePutsTheTitleAndContentBackAsTheCaller() throws Exception {
        WeblogPage page = page("p1", "About us", "v3");
        page.setSlug("about-us");
        revision("r1", page, "About", "v1");

        String body = mvc.perform(on(post("/v1/weblogs/myblog/pages/{id}/revisions/{rid}/restore", "p1", "r1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals("About", page.getTitle());
        assertEquals("v1", page.getContent());
        assertEquals("about-us", page.getSlug(), "a revision holds no settings");
        assertEquals(WeblogPage.PubStatus.PUBLISHED, page.getStatus());
        assertEquals("v1", json(body).get("text").asString());
        verify(pages).savePage(page, user, null);
        verify(weblogger).flush();
    }

    @Test
    void restoreWithAStaleHashIsA409AndRestoresNothing() throws Exception {
        WeblogPage page = page("p1", "About us", "v3");
        revision("r1", page, "About", "v1");

        String body = mvc.perform(on(post("/v1/weblogs/myblog/pages/{id}/revisions/{rid}/restore", "p1", "r1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedContentHash\":\"" + hash("v2") + "\"}"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();

        assertEquals(hash("v3"), json(body).get("currentContentHash").asString());
        assertEquals("v3", page.getContent());
        verify(pages, never()).savePage(any(), any(), any());
    }

    @Test
    void restoreWithTheCurrentHashGoesThrough() throws Exception {
        WeblogPage page = page("p1", "About us", "v3");
        revision("r1", page, "About", "v1");

        mvc.perform(on(post("/v1/weblogs/myblog/pages/{id}/revisions/{rid}/restore", "p1", "r1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedContentHash\":\"" + hash("v3") + "\"}"))
                .andExpect(status().isOk());

        assertEquals("v1", page.getContent());
    }

    /** A trashed page's history cannot be read or restored by id; restore says why. */
    @Test
    void aTrashedPagesRevisionsAreClosed() throws Exception {
        WeblogPage page = page("p1", "About", "v3");
        revision("r1", page, "About", "v1");
        page.setStatus(WeblogPage.PubStatus.TRASHED);

        mvc.perform(on(get("/v1/weblogs/myblog/pages/{id}/revisions", "p1"))).andExpect(status().isNotFound());
        mvc.perform(on(get("/v1/weblogs/myblog/pages/{id}/revisions/{rid}", "p1", "r1")))
                .andExpect(status().isNotFound());
        String body = mvc.perform(on(post("/v1/weblogs/myblog/pages/{id}/revisions/{rid}/restore", "p1", "r1")))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("restore it first"), body);
        assertEquals("v3", page.getContent());
        verify(pages, never()).savePage(any(), any(), any());
    }
}
