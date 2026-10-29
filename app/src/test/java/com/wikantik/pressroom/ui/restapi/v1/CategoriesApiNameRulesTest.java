package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.wikantik.pressroom.ui.restapi.ErrorFieldMatchers.errorFieldIs;

/** The category name rules a request can break before anything is stored. */
class CategoriesApiNameRulesTest {

    private final Weblogger weblogger = mock(Weblogger.class);
    private final WeblogEntryManager entries = mock(WeblogEntryManager.class);
    private final Weblog weblog = new Weblog();

    CategoriesApiNameRulesTest() {
        weblog.setHandle("catblog");
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
    }

    private MockMvc mvc() {
        CategoriesApi controller = new CategoriesApi();
        controller.weblogger = weblogger;
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private WeblogCategory ownedCategory(String id, String name) throws Exception {
        WeblogCategory category = new WeblogCategory(weblog, name, "old", null);
        category.setId(id);
        when(entries.getWeblogCategory(id)).thenReturn(category);
        return category;
    }

    @Test
    void createWithNoNameIsBadRequestAndStoresNothing() throws Exception {
        mvc().perform(post("/v1/weblogs/catblog/categories").requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"d\"}"))
                .andExpect(status().isBadRequest());

        verify(entries, never()).saveWeblogCategory(any());
    }

    @Test
    void createWithAWhitespaceOnlyNameIsBadRequest() throws Exception {
        mvc().perform(post("/v1/weblogs/catblog/categories").requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(entries, never()).saveWeblogCategory(any());
    }

    @Test
    void renamingToABlankNameIsBadRequestAndTheCategoryIsUntouched() throws Exception {
        WeblogCategory category = ownedCategory("c1", "Travel");

        mvc().perform(patch("/v1/weblogs/catblog/categories/{id}", "c1").requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());

        assertEquals("Travel", category.getName());
        verify(entries, never()).saveWeblogCategory(any());
    }

    @Test
    void patchingOnlyTheDescriptionKeepsTheNameAndSkipsTheDuplicateLookup() throws Exception {
        WeblogCategory category = ownedCategory("c1", "Travel");

        mvc().perform(patch("/v1/weblogs/catblog/categories/{id}", "c1").requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"new\"}"))
                .andExpect(status().isOk());

        assertEquals("Travel", category.getName());
        assertEquals("new", category.getDescription());
        verify(entries, never()).getWeblogCategoryByName(any(), any());
        verify(entries).saveWeblogCategory(category);
    }

    @Test
    void patchingToTheNameItAlreadyHasIsNotAConflictWithItself() throws Exception {
        WeblogCategory category = ownedCategory("c1", "Travel");

        mvc().perform(patch("/v1/weblogs/catblog/categories/{id}", "c1").requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" Travel \"}"))
                .andExpect(status().isOk());

        assertEquals("Travel", category.getName());
        verify(entries, never()).getWeblogCategoryByName(any(), any());
    }

    @Test
    void renamingToTheNameOfTheSameCategoryFoundByNameIsNotAConflict() throws Exception {
        WeblogCategory category = ownedCategory("c1", "Travel");
        when(entries.getWeblogCategoryByName(weblog, "Trips")).thenReturn(category);

        mvc().perform(patch("/v1/weblogs/catblog/categories/{id}", "c1").requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Trips\"}"))
                .andExpect(status().isOk());

        assertEquals("Trips", category.getName());
    }

    @Test
    void createWithNoNameNamesTheNameField() throws Exception {
        mvc().perform(post("/v1/weblogs/catblog/categories").requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"d\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("name"));
    }

    @Test
    void renamingToABlankNameNamesTheNameField() throws Exception {
        ownedCategory("c1", "Travel");
        mvc().perform(patch("/v1/weblogs/catblog/categories/{id}", "c1").requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("name"));
    }
}
