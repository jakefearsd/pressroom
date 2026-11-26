package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;

import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A category's {@code image} (the icon the Categories page's form takes), on the write and the view (spec
 * 2026-11-25 React admin migration, A7). The page checks in the browser that it is an absolute http(s) URL a
 * browser can load ({@code isValidUrl} in pressroom.js: the parser decides, so {@code http://localhost:8083} and an
 * address by IP pass) and the column holds 255; the API checks the same on the server, naming the field.
 */
class CategoriesApiImageTest {

    private final Weblogger weblogger = mock(Weblogger.class);
    private final WeblogEntryManager entries = mock(WeblogEntryManager.class);
    private final Weblog weblog = new Weblog();

    CategoriesApiImageTest() throws Exception {
        weblog.setHandle("catblog");
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
    }

    private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        CategoriesApi controller = new CategoriesApi();
        controller.weblogger = weblogger;
        CategoriesReadApi reads = new CategoriesReadApi();
        reads.weblogger = weblogger;
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller, reads)
                .setControllerAdvice(new ApiExceptionHandler()).build();
        return mvc.perform(request.requestAttr("actionWeblog", weblog).contentType(MediaType.APPLICATION_JSON));
    }

    private WeblogCategory owned(String id, String image) throws Exception {
        WeblogCategory category = new WeblogCategory(weblog, "Travel", "old", image);
        category.setId(id);
        when(entries.getWeblogCategory(id)).thenReturn(category);
        return category;
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return new ObjectMapper().readTree(result.andReturn().getResponse().getContentAsString());
    }

    @Test
    void aCreateStoresTheImageAndTheViewShowsIt() throws Exception {
        JsonNode created = json(send(post("/v1/weblogs/catblog/categories")
                .content("{\"name\":\"Food\",\"image\":\"https://cdn.example.photography/food.jpg\"}"))
                .andExpect(status().isCreated()));

        ArgumentCaptor<WeblogCategory> saved = ArgumentCaptor.forClass(WeblogCategory.class);
        verify(entries).saveWeblogCategory(saved.capture());
        assertEquals("https://cdn.example.photography/food.jpg", saved.getValue().getImage());
        assertEquals("https://cdn.example.photography/food.jpg", created.get("image").asString());
    }

    @Test
    void aCreateWithoutOneHasNone() throws Exception {
        JsonNode created = json(send(post("/v1/weblogs/catblog/categories").content("{\"name\":\"Food\"}"))
                .andExpect(status().isCreated()));

        assertNull(created.get("image"), "an absent image is absent from the view");
    }

    @Test
    void anUpdateSetsReplacesAndLeavesAloneTheImage() throws Exception {
        WeblogCategory category = owned("c1", null);

        send(patch("/v1/weblogs/catblog/categories/c1").content("{\"image\":\"http://localhost:8083/a.png\"}"))
                .andExpect(status().isOk());
        assertEquals("http://localhost:8083/a.png", category.getImage(), "localhost passes, as in the browser check");

        send(patch("/v1/weblogs/catblog/categories/c1").content("{\"image\":\"http://10.0.0.5/b.png\"}"))
                .andExpect(status().isOk());
        assertEquals("http://10.0.0.5/b.png", category.getImage());

        send(patch("/v1/weblogs/catblog/categories/c1").content("{\"description\":\"new\"}"))
                .andExpect(status().isOk());
        assertEquals("http://10.0.0.5/b.png", category.getImage(), "absent leaves it");
    }

    @Test
    void aBlankImageRemovesIt() throws Exception {
        WeblogCategory category = owned("c1", "https://x.test/a.png");

        JsonNode updated = json(send(patch("/v1/weblogs/catblog/categories/c1").content("{\"image\":\"  \"}"))
                .andExpect(status().isOk()));

        assertNull(category.getImage());
        assertNull(updated.get("image"));
    }

    @Test
    void theListShowsEachCategorysImage() throws Exception {
        WeblogCategory category = owned("c1", "https://x.test/a.png");
        when(entries.getWeblogCategories(weblog)).thenReturn(List.of(category));

        JsonNode list = json(send(get("/v1/weblogs/catblog/categories")).andExpect(status().isOk()));

        assertEquals("https://x.test/a.png", list.get(0).get("image").asString());
    }

    @Test
    void anImageThatIsNotAnAbsoluteHttpUrlIsAFieldErrorAndNothingIsStored() throws Exception {
        owned("c1", null);
        for (String bad : List.of("javascript:alert(1)", "ftp://x.test/a.png", "/img/a.png", "http:example.com",
                "https://", "data:image/png;base64,AAAA", "not a url")) {
            JsonNode problem = json(send(patch("/v1/weblogs/catblog/categories/c1")
                    .content("{\"image\":\"" + bad + "\"}")).andExpect(status().isBadRequest()));
            assertEquals("category.imageInvalid", problem.get("code").asString(), bad);
            assertEquals("image", problem.get("errors").get(0).get("field").asString(), bad);

            send(post("/v1/weblogs/catblog/categories").content("{\"name\":\"N\",\"image\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        verify(entries, never()).saveWeblogCategory(any());
    }

    @Test
    void anImageOverTheColumnIsTooLong() throws Exception {
        owned("c1", null);
        String longUrl = "https://x.test/" + "a".repeat(250);

        JsonNode problem = json(send(patch("/v1/weblogs/catblog/categories/c1")
                .content("{\"image\":\"" + longUrl + "\"}")).andExpect(status().isBadRequest()));

        assertEquals("field.tooLong", problem.get("code").asString());
        assertEquals("image", problem.get("errors").get(0).get("field").asString());
        assertFalse(problem.toString().isBlank());
        assertTrue(longUrl.length() > 255);
    }
}
