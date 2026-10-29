package com.wikantik.pressroom.ui.restapi.v1;

import java.util.ArrayList;
import java.util.List;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shared fixture of the EntriesWriteApi controller tests: a mocked tier,
 * a weblog "myblog" (UTC) whose categories are Food and Travel, the
 * controller under test, and helpers that send a request as the user the
 * interceptor would have resolved and read the problem body back.
 */
final class EntriesWriteApiFixture {

    final Weblogger weblogger = mock(Weblogger.class);
    final WeblogEntryManager entries = mock(WeblogEntryManager.class);
    final URLStrategy urls = mock(URLStrategy.class);
    final IndexManager index = mock(IndexManager.class);
    final Weblog weblog = new Weblog();
    final EntriesWriteApi controller = new EntriesWriteApi();

    EntriesWriteApiFixture() throws Exception {
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        weblog.setTimeZone("UTC");
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(weblogger.getIndexManager()).thenReturn(index);
        when(entries.getWeblogCategories(weblog)).thenReturn(List.of(
                new WeblogCategory(weblog, "Food", null, null),
                new WeblogCategory(weblog, "Travel", null, null)));
        controller.weblogger = weblogger;
    }

    MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    /** Sends {@code request} as JSON with the action weblog and user the interceptor would set. */
    ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        User user = new User();
        user.setUserName("maiia");
        return mvc().perform(request
                .requestAttr("actionWeblog", weblog)
                .requestAttr("authenticatedUser", user)
                .contentType(MediaType.APPLICATION_JSON));
    }

    /** Sends {@code request}, expects a 400, and returns the problem body. */
    JsonNode refused(MockHttpServletRequestBuilder request) throws Exception {
        return json(send(request).andExpect(status().isBadRequest()));
    }

    static JsonNode json(ResultActions result) throws Exception {
        return new ObjectMapper().readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** The {@code field} of each entry of a problem's {@code errors}, in order. */
    static List<String> fields(JsonNode problem) {
        List<String> fields = new ArrayList<>();
        for (JsonNode error : problem.get("errors")) {
            fields.add(error.get("field").asString());
        }
        return fields;
    }
}
