package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A deprecated parameter alias sent with a different value than its current
 * name is {@code parameter.aliasConflict}, on the alias (spec
 * 2026-11-20-api-contract-design, Task 2, criterion 3).
 */
class ParameterAliasProblemCodeTest {

    @Test
    void twoDifferentValuesAreAParameterAliasConflictOnTheAlias() throws Exception {
        Weblog weblog = new Weblog();
        weblog.setHandle("myblog");
        String body = MockMvcBuilders.standaloneSetup(new CommentsApi())
                .setControllerAdvice(new ApiExceptionHandler()).build()
                .perform(get("/v1/weblogs/myblog/comments").param("entryId", "X").param("entry", "Y")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        JsonNode problem = new ObjectMapper().readTree(body);

        oneField(problem, "entry", "parameter.aliasConflict",
                "entry is the old name of entryId and names a different value; send entryId only.");
    }
}
