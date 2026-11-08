package com.wikantik.pressroom.ui.restapi.v1;

import java.util.ArrayList;
import java.util.List;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A page's SEO card through the API (spec 2026-11-08 agent API parity,
 * P1-P4): the fields {@code PageBean} has reach {@code PageWrite} and
 * {@code PageView}, validated as the page editor and the entry API validate
 * them.
 */
class PagesApiSeoTest {

    private final Weblogger weblogger = mock(Weblogger.class);
    private final WeblogPageManager pages = mock(WeblogPageManager.class);
    private final MediaFileManager media = mock(MediaFileManager.class);
    private final Weblog weblog = weblog("myblog");
    private WeblogPage page;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        when(weblogger.getWeblogPageManager()).thenReturn(pages);
        when(weblogger.getMediaFileManager()).thenReturn(media);
        PagesApi controller = new PagesApi();
        controller.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();

        page = new WeblogPage();
        page.setId("page-1");
        page.setWeblog(weblog);
        page.setSlug("about");
        page.setTitle("About");
        when(pages.getPage("page-1")).thenReturn(page);

        Weblog other = weblog("other");
        stubImage("img", new MediaFileDirectory(weblog, "default", null));
        stubImage("foreign", new MediaFileDirectory(other, "default", null));
    }

    private static Weblog weblog(String handle) {
        Weblog w = new Weblog();
        w.setId(handle + "-id");
        w.setHandle(handle);
        return w;
    }

    private void stubImage(String id, MediaFileDirectory directory) throws Exception {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setDirectory(directory);
        file.setWeblog(directory.getWeblog());
        file.setContentType("image/jpeg");
        when(media.getMediaFile(id)).thenReturn(file);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.requestAttr("actionWeblog", weblog)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return new ObjectMapper().readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static List<String> fields(JsonNode problem) {
        List<String> names = new ArrayList<>();
        problem.path("errors").forEach(e -> names.add(e.path("field").asString()));
        return names;
    }

    private static final String ALL_SIX = "\"navOrder\":3,\"metaTitle\":\"  Meta  \","
            + "\"searchDescription\":\" Described. \",\"canonicalUrl\":\"https://example.com/a\","
            + "\"noindex\":true,\"ogImageId\":\"img\"";

    // ------------------------------------------------------------------ P1

    @Test
    void getCarriesTheSeoFieldsAndOmitsTheNullOnes() throws Exception {
        page.setNavOrder(2);
        page.setSearchDescription("Described.");
        page.setNoindex(Boolean.TRUE);

        JsonNode view = json(mvc.perform(get("/v1/weblogs/myblog/pages/page-1").requestAttr("actionWeblog", weblog)));

        assertEquals(2, view.path("navOrder").asInt());
        assertEquals("Described.", view.path("searchDescription").asString());
        assertTrue(view.path("noindex").asBoolean());
        assertFalse(view.has("metaTitle"), "a null field is omitted");
        assertFalse(view.has("canonicalUrl"));
        assertFalse(view.has("ogImageId"));
    }

    @Test
    void listCarriesTheSearchDescription() throws Exception {
        page.setSearchDescription("Described.");
        when(pages.getPages(weblog)).thenReturn(List.of(page));

        JsonNode list = json(mvc.perform(get("/v1/weblogs/myblog/pages").requestAttr("actionWeblog", weblog)));

        assertEquals("Described.", list.get(0).path("searchDescription").asString());
    }

    // ------------------------------------------------------------- P1, P2, P3

    @Test
    void createStoresTheSeoFieldsTrimmedAndAnswersThem() throws Exception {
        ResultActions result = send(post("/v1/weblogs/myblog/pages"),
                "{\"slug\":\"contact\",\"title\":\"Contact\"," + ALL_SIX + "}");

        assertEquals(201, result.andReturn().getResponse().getStatus());
        JsonNode view = json(result);
        assertEquals(3, view.path("navOrder").asInt());
        assertEquals("Meta", view.path("metaTitle").asString(), "trimmed, as the entry API trims it");
        assertEquals("Described.", view.path("searchDescription").asString());
        assertEquals("https://example.com/a", view.path("canonicalUrl").asString());
        assertTrue(view.path("noindex").asBoolean());
        assertEquals("img", view.path("ogImageId").asString());
        verify(pages).savePage(any());
    }

    @Test
    void aPatchThatOmitsAFieldLeavesTheStoredValueAlone() throws Exception {
        page.setNavOrder(4);
        page.setMetaTitle("Kept");
        page.setSearchDescription("Kept too.");
        page.setCanonicalUrl("https://example.com/kept");
        page.setNoindex(Boolean.TRUE);
        page.setOgImageId("img");

        assertEquals(200, send(patch("/v1/weblogs/myblog/pages/page-1"), "{\"title\":\"New\"}")
                .andReturn().getResponse().getStatus());

        assertEquals(4, page.getNavOrder());
        assertEquals("Kept", page.getMetaTitle());
        assertEquals("Kept too.", page.getSearchDescription());
        assertEquals("https://example.com/kept", page.getCanonicalUrl());
        assertEquals(Boolean.TRUE, page.getNoindex());
        assertEquals("img", page.getOgImageId());
    }

    @Test
    void aPatchSetsTheSeoFields() throws Exception {
        assertEquals(200, send(patch("/v1/weblogs/myblog/pages/page-1"), "{" + ALL_SIX + "}")
                .andReturn().getResponse().getStatus());

        assertEquals(3, page.getNavOrder());
        assertEquals("Meta", page.getMetaTitle());
        assertEquals("Described.", page.getSearchDescription());
        assertEquals("https://example.com/a", page.getCanonicalUrl());
        assertEquals(Boolean.TRUE, page.getNoindex());
        assertEquals("img", page.getOgImageId());
        verify(pages).savePage(page);
    }

    @Test
    void namingAFieldInClearRemovesIt() throws Exception {
        page.setMetaTitle("Gone");
        page.setSearchDescription("Gone too.");
        page.setCanonicalUrl("https://example.com/gone");
        page.setOgImageId("img");

        assertEquals(200, send(patch("/v1/weblogs/myblog/pages/page-1"),
                "{\"clear\":[\"metaTitle\",\"searchDescription\",\"canonicalUrl\",\"ogImageId\"]}")
                .andReturn().getResponse().getStatus());

        assertNull(page.getMetaTitle());
        assertNull(page.getSearchDescription());
        assertNull(page.getCanonicalUrl());
        assertNull(page.getOgImageId());
    }

    @Test
    void anUnknownNameInClearIsA400OnClear() throws Exception {
        JsonNode problem = json(send(patch("/v1/weblogs/myblog/pages/page-1"), "{\"clear\":[\"title\"]}"));

        assertEquals(400, problem.path("status").asInt());
        assertEquals(List.of("clear"), fields(problem));
        verify(pages, never()).savePage(any());
    }

    /** As on an entry create: there is nothing to remove yet. */
    @Test
    void clearOnACreateIsA400OnClear() throws Exception {
        JsonNode problem = json(send(post("/v1/weblogs/myblog/pages"),
                "{\"slug\":\"contact\",\"title\":\"Contact\",\"clear\":[\"metaTitle\"]}"));

        assertEquals(List.of("clear"), fields(problem));
        assertEquals("clear applies only to updates.", problem.path("errors").get(0).path("message").asString());
        verify(pages, never()).savePage(any());
    }

    @Test
    void aMetaTitleOverTheColumnIsA400OnMetaTitle() throws Exception {
        JsonNode problem = json(send(patch("/v1/weblogs/myblog/pages/page-1"),
                "{\"metaTitle\":\"" + "m".repeat(256) + "\"}"));

        assertEquals(List.of("metaTitle"), fields(problem));
        verify(pages, never()).savePage(any());
    }

    @Test
    void anInvalidCanonicalUrlIsA400OnCanonicalUrl() throws Exception {
        JsonNode problem = json(send(patch("/v1/weblogs/myblog/pages/page-1"),
                "{\"canonicalUrl\":\"javascript:alert(1)\"}"));

        assertEquals(List.of("canonicalUrl"), fields(problem));
        assertNull(page.getCanonicalUrl(), "nothing is stored on a refusal");
        verify(pages, never()).savePage(any());
    }

    @Test
    void anotherWeblogsImageIsA400OnOgImageIdAndNeverStored() throws Exception {
        JsonNode problem = json(send(patch("/v1/weblogs/myblog/pages/page-1"), "{\"ogImageId\":\"foreign\"}"));

        assertEquals(List.of("ogImageId"), fields(problem));
        assertNull(page.getOgImageId());
        verify(pages, never()).savePage(any());
    }

    @Test
    void createRefusesAForeignOgImageId() throws Exception {
        JsonNode problem = json(send(post("/v1/weblogs/myblog/pages"),
                "{\"slug\":\"contact\",\"title\":\"Contact\",\"ogImageId\":\"foreign\"}"));

        assertEquals(List.of("ogImageId"), fields(problem));
        verify(pages, never()).savePage(any());
    }

    /** The stored id of a file deleted since is not re-checked, as on entries. */
    @Test
    void aPatchSendingBackTheStoredOgImageIdIsNotRechecked() throws Exception {
        page.setOgImageId("deleted-since");

        assertEquals(200, send(patch("/v1/weblogs/myblog/pages/page-1"), "{\"ogImageId\":\"deleted-since\"}")
                .andReturn().getResponse().getStatus());
    }

    // ------------------------------------------------------------------ P4

    @Test
    void aRefusedCreateListsEveryBadField() throws Exception {
        JsonNode problem = json(send(post("/v1/weblogs/myblog/pages"),
                "{\"slug\":\"category\",\"title\":\" \",\"status\":\"bogus\",\"metaTitle\":\""
                        + "m".repeat(256) + "\",\"canonicalUrl\":\"ftp://x\",\"ogImageId\":\"foreign\"}"));

        assertEquals(400, problem.path("status").asInt());
        assertEquals(List.of("canonicalUrl", "metaTitle", "ogImageId", "slug", "status", "title"),
                fields(problem).stream().sorted().toList());
        verify(pages, never()).savePage(any());
    }

    @Test
    void aRefusedPatchListsEveryBadFieldAndChangesNothing() throws Exception {
        JsonNode problem = json(send(patch("/v1/weblogs/myblog/pages/page-1"),
                "{\"slug\":\"foo/bar\",\"title\":\"Changed\",\"searchDescription\":\""
                        + "d".repeat(256) + "\",\"canonicalUrl\":\"nope\"}"));

        assertEquals(List.of("canonicalUrl", "searchDescription", "slug"),
                fields(problem).stream().sorted().toList());
        assertEquals("About", page.getTitle(), "a refused write changes nothing");
        assertEquals("about", page.getSlug());
        verify(pages, never()).savePage(any());
    }
}
