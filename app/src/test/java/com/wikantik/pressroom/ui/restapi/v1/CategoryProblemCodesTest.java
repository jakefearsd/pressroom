package com.wikantik.pressroom.ui.restapi.v1;

import java.util.ArrayList;
import com.wikantik.pressroom.business.DuplicateCategoryNameException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.problem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every refusal of the category routes carries its own code, with the English
 * it always had (spec 2026-11-20-api-contract-design, Task 3): the code
 * asserted beside the byte-for-byte detail, and for a field error its own
 * {@code errors[].code}. The English half passed on the code before Task 3
 * (characterisation); the code half is what Task 3 adds.
 */
class CategoryProblemCodesTest {

    private static final String CATEGORIES = "/v1/weblogs/myblog/categories";

    private WeblogEntryManager entries;
    private Weblog weblog;
    private WeblogCategory travel;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        entries = mock(WeblogEntryManager.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        weblog.setWeblogCategories(new ArrayList<>());
        travel = category("c1", "Travel");
        CategoriesApi controller = new CategoriesApi();
        controller.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private WeblogCategory category(String id, String name) throws Exception {
        WeblogCategory made = new WeblogCategory(weblog, name, null, null);
        made.setId(id);
        when(entries.getWeblogCategory(id)).thenReturn(made);
        when(entries.getWeblogCategoryByName(weblog, name)).thenReturn(made);
        return made;
    }

    private JsonNode answer(ResultMatcher status, MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request.requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    @Test
    void aMissingOrBlankNameIsFieldRequiredOrBlank() throws Exception {
        oneField(answer(status().isBadRequest(), post(CATEGORIES).content("{}")),
                "name", "field.required", "name is required.");
        oneField(answer(status().isBadRequest(), patch(CATEGORIES + "/c1").content("{\"name\":\"  \"}")),
                "name", "field.blank", "name cannot be blank.");
    }

    @Test
    void aTakenNameIsCategoryNameInUse() throws Exception {
        oneField(answer(status().isConflict(), post(CATEGORIES).content("{\"name\":\"Travel\"}")),
                "name", "category.nameInUse", "A category named 'Travel' already exists.");

        doThrow(new DuplicateCategoryNameException("Food")).when(entries).saveWeblogCategory(any());
        oneField(answer(status().isConflict(), post(CATEGORIES).content("{\"name\":\"Food\"}")),
                "name", "category.nameInUse", "A category named 'Food' already exists.");

        WeblogCategory food = category("c2", "Food");
        when(entries.getWeblogCategoryByName(weblog, "Food")).thenReturn(food);
        oneField(answer(status().isConflict(), patch(CATEGORIES + "/c1").content("{\"name\":\"Food\"}")),
                "name", "category.nameInUse", "A category named 'Food' already exists.");
    }

    @Test
    void anUnknownCategoryIsCategoryNotFound() throws Exception {
        problem(answer(status().isNotFound(), patch(CATEGORIES + "/nope").content("{}")),
                "category.notFound", "No such category.");
        problem(answer(status().isNotFound(), delete(CATEGORIES + "/nope")),
                "category.notFound", "No such category.");
    }

    @Test
    void theLastCategoryIsCategoryLastOne() throws Exception {
        problem(answer(status().isConflict(), delete(CATEGORIES + "/c1")),
                "category.lastOne", "A weblog must keep at least one category.");
    }

    @Test
    void theDeleteRefusalsAboutMoveToAndEntriesHaveTheirCodes() throws Exception {
        category("c2", "Food");
        oneField(answer(status().isBadRequest(), delete(CATEGORIES + "/c1").param("moveTo", "c1")),
                "moveTo", "category.moveToSelf", "moveTo cannot name the category being deleted.");
        problem(answer(status().isNotFound(), delete(CATEGORIES + "/c1").param("moveTo", "nope")),
                "category.moveTargetNotFound", "No such category to move entries to.");

        when(entries.isWeblogCategoryInUse(travel)).thenReturn(true);
        problem(answer(status().isConflict(), delete(CATEGORIES + "/c1")),
                "category.hasEntries", "Category has entries; pass moveTo to move them to another category first.");
    }
}
