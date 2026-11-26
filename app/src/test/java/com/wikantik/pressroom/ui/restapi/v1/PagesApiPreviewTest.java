package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.business.EntryRenderer;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Page preview: the API twin of {@code PageEditController#pageEditPreview}. It renders through
 * {@code EntryRenderer.pageContent} on a scratch page (the weblog, and the stored page's slug when an id
 * is given), never the stored row, and saves nothing.
 */
class PagesApiPreviewTest {

    private final Weblog weblog = weblog("myblog");
    private final WeblogPageManager pages = mock(WeblogPageManager.class);
    private final Weblogger weblogger = mock(Weblogger.class);
    private final EntryRenderer renderer = mock(EntryRenderer.class);
    private final MockMvc mvc;

    PagesApiPreviewTest() {
        when(weblogger.getWeblogPageManager()).thenReturn(pages);
        when(weblogger.getEntryRenderer()).thenReturn(renderer);
        when(renderer.pageContent(any())).thenAnswer(inv -> {
            WeblogPage scratch = inv.getArgument(0);
            return "<p>" + scratch.getContent() + "|" + scratch.getSlug() + "</p>";
        });
        PagesApi api = new PagesApi();
        when(weblogger.getUrlStrategy()).thenReturn(mock(com.wikantik.pressroom.business.URLStrategy.class));
        api.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static Weblog weblog(String handle) {
        Weblog w = new Weblog();
        w.setId(handle + "-id");
        w.setHandle(handle);
        return w;
    }

    private static WeblogPage page(Weblog w, String id, String slug, String text) {
        WeblogPage p = new WeblogPage();
        p.setId(id);
        p.setWeblog(w);
        p.setSlug(slug);
        p.setContent(text);
        return p;
    }

    private JsonNode send(String path, String json, int expected) throws Exception {
        MockHttpServletResponse response = mvc.perform(post(path).requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", new User())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse();
        assertEquals(expected, response.getStatus());
        return new ObjectMapper().readTree(response.getContentAsString());
    }

    @Test
    void anExistingPageRendersTheSentTextOnAScratchPageWithItsSlugAndSavesNothing() throws Exception {
        WeblogPage stored = page(weblog, "p1", "about", "stored text");
        when(pages.getPage("p1")).thenReturn(stored);

        JsonNode view = send("/v1/weblogs/myblog/pages/p1/preview", "{\"text\":\"new text\"}", 200);

        assertEquals("<p>new text|about</p>", view.get("html").asString());
        assertTrue(view.get("warnings").isArray());
        assertEquals("stored text", stored.getContent(), "the stored row is never touched");
        verify(pages, never()).savePage(any());
    }

    @Test
    void editsApplyToTheStoredText() throws Exception {
        when(pages.getPage("p1")).thenReturn(page(weblog, "p1", "about", "The pier. The beach."));

        JsonNode view = send("/v1/weblogs/myblog/pages/p1/preview",
                "{\"edits\":[{\"find\":\"The pier.\",\"replace\":\"The jetty.\"}]}", 200);

        assertEquals("<p>The jetty. The beach.|about</p>", view.get("html").asString());
    }

    @Test
    void anUnsavedPageRendersOnTheWeblogWithNoSlug() throws Exception {
        JsonNode view = send("/v1/weblogs/myblog/pages/preview", "{\"text\":\"hello\"}", 200);

        assertEquals("<p>hello|null</p>", view.get("html").asString());
    }

    @Test
    void aMissingTextRendersAnEmptyDraft() throws Exception {
        JsonNode view = send("/v1/weblogs/myblog/pages/preview", "{}", 200);

        assertEquals("<p>|null</p>", view.get("html").asString());
    }

    @Test
    void editsWithoutAnIdAreA400() throws Exception {
        JsonNode problem = send("/v1/weblogs/myblog/pages/preview",
                "{\"edits\":[{\"find\":\"a\",\"replace\":\"b\"}]}", 400);

        assertEquals("edits", problem.get("errors").get(0).get("field").asString());
    }

    @Test
    void anotherWeblogsPageIsA404() throws Exception {
        when(pages.getPage("p9")).thenReturn(page(weblog("other"), "p9", "x", "t"));

        send("/v1/weblogs/myblog/pages/p9/preview", "{\"text\":\"t\"}", 404);
    }

    @Test
    void aTrashedPageIsA404LikeAnUnknownOne() throws Exception {
        WeblogPage trashed = page(weblog, "p8", "old", "t");
        trashed.setStatus(WeblogPage.PubStatus.TRASHED);
        when(pages.getPage("p8")).thenReturn(trashed);

        JsonNode problem = send("/v1/weblogs/myblog/pages/p8/preview", "{\"text\":\"t\"}", 404);

        assertEquals("page.notFound", problem.get("code").asString());
    }
}
