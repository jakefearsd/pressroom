package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria.SortBy;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET .../entries?sortBy=}: the entries page's sort select (publish time or update time). */
class EntriesApiSortTest {

    private final WeblogEntryManager manager = mock(WeblogEntryManager.class);
    private final Weblog weblog = new Weblog();

    private MockMvc mvc() {
        weblog.setId("weblog-1");
        weblog.setHandle("myblog");
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(manager);
        when(weblogger.getUrlStrategy()).thenReturn(mock(URLStrategy.class));
        EntriesApi api = new EntriesApi();
        api.weblogger = weblogger;
        return MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private MockHttpServletRequestBuilder request() {
        return get("/v1/weblogs/myblog/entries").requestAttr("actionWeblog", weblog);
    }

    private WeblogEntrySearchCriteria criteriaFor(MockHttpServletRequestBuilder request) throws Exception {
        clearInvocations(manager);
        when(manager.getWeblogEntries(any())).thenReturn(List.of());
        mvc().perform(request).andExpect(status().isOk());
        ArgumentCaptor<WeblogEntrySearchCriteria> captor = ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(manager).getWeblogEntries(captor.capture());
        return captor.getValue();
    }

    @Test
    void theSortIsPassedToTheQuery() throws Exception {
        assertEquals(SortBy.UPDATE_TIME, criteriaFor(request().param("sortBy", "UPDATE_TIME")).getSortBy());
    }

    @Test
    void theNameIsTrimmed() throws Exception {
        assertEquals(SortBy.UPDATE_TIME, criteriaFor(request().param("sortBy", " UPDATE_TIME ")).getSortBy());
    }

    @Test
    void noSortKeepsTheQuerysOwnOrder() throws Exception {
        assertEquals(new WeblogEntrySearchCriteria().getSortBy(), criteriaFor(request()).getSortBy());
        assertEquals(new WeblogEntrySearchCriteria().getSortBy(),
                criteriaFor(request().param("sortBy", " ")).getSortBy());
    }

    @Test
    void anythingElseIsA400OnTheParameterAndNothingIsQueried() throws Exception {
        for (String bad : List.of("TRASH_TIME", "title", "update_time")) {
            String body = mvc().perform(request().param("sortBy", bad)).andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            var error = new ObjectMapper().readTree(body).get("errors").get(0);
            assertEquals("sortBy", error.get("field").asString(), bad);
            assertEquals("parameter.invalidValue", error.get("code").asString(), bad);
        }
        verify(manager, never()).getWeblogEntries(any());
    }
}
