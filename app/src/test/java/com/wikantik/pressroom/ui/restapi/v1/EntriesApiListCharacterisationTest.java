package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import java.util.Set;

import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Characterisation tests (Task 5 of spec 2026-11-20-jsp-rules-and-view-models-design):
 * written against {@code EntriesApi.list} as it stood BEFORE the criteria moved
 * into {@code EntryListQuery}, and passing on that code. They pin what
 * {@code GET /v1/weblogs/{handle}/entries} hands the entry manager for each
 * parameter, and the shape of the answer. {@code EntriesApiReadTest} already pins
 * the trash default, {@code status=TRASHED}, the 400s and the hasMore trim.
 */
class EntriesApiListCharacterisationTest {

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
        when(manager.getWeblogEntries(any())).thenReturn(List.of());
        mvc().perform(request).andExpect(status().isOk());
        ArgumentCaptor<WeblogEntrySearchCriteria> captor = ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(manager).getWeblogEntries(captor.capture());
        return captor.getValue();
    }

    @Test
    void noParametersAskForTheFirstFiftyAndOneMore() throws Exception {
        WeblogEntrySearchCriteria wesc = criteriaFor(request());

        assertEquals(weblog, wesc.getWeblog());
        assertEquals(0, wesc.getOffset());
        assertEquals(51, wesc.getMaxResults());
        assertNull(wesc.getStatus());
        assertNull(wesc.getCatName());
        assertNull(wesc.getText());
        assertNull(wesc.getLocale());
        assertNull(wesc.getStartDate());
        assertNull(wesc.getEndDate());
        assertFalse(wesc.isIncludeTrashed());
        assertEquals(WeblogEntrySearchCriteria.SortBy.PUBLICATION_TIME, wesc.getSortBy());
    }

    @Test
    void offsetLimitAndEveryFilterReachTheCriteria() throws Exception {
        WeblogEntrySearchCriteria wesc = criteriaFor(request().param("offset", "5").param("limit", "10")
                .param("category", "Travel").param("tags", "a", "b").param("text", "rome")
                .param("locale", "it").param("status", "draft"));

        assertEquals(5, wesc.getOffset());
        assertEquals(11, wesc.getMaxResults());
        assertEquals("Travel", wesc.getCatName());
        assertEquals(List.of("a", "b"), wesc.getTags());
        assertEquals("rome", wesc.getText());
        assertEquals("it", wesc.getLocale());
        assertEquals(WeblogEntry.PubStatus.DRAFT, wesc.getStatus());
        assertFalse(wesc.isIncludeTrashed());
    }

    @Test
    void aBlankStatusIsNoFilter() throws Exception {
        WeblogEntrySearchCriteria wesc = criteriaFor(request().param("status", " "));

        assertNull(wesc.getStatus());
        assertFalse(wesc.isIncludeTrashed());
    }

    @Test
    void aLimitAboveTheCapIsCappedAtTwoHundredAndReportedAsSuch() throws Exception {
        when(manager.getWeblogEntries(any())).thenReturn(List.of());

        String body = mvc().perform(request().param("limit", "500")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ArgumentCaptor<WeblogEntrySearchCriteria> captor = ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(manager).getWeblogEntries(captor.capture());
        assertEquals(201, captor.getValue().getMaxResults());
        assertEquals(200, new ObjectMapper().readTree(body).get("limit").asInt());
    }

    @Test
    void theAnswerIsExactlyTheEnvelope() throws Exception {
        when(manager.getWeblogEntries(any())).thenReturn(List.of());

        String body = mvc().perform(request().param("offset", "3").param("limit", "7"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = new ObjectMapper().readTree(body);
        assertEquals(Set.of("items", "offset", "limit", "hasMore"), Set.copyOf(json.propertyNames()));
        assertEquals(3, json.get("offset").asInt());
        assertEquals(7, json.get("limit").asInt());
        assertFalse(json.get("hasMore").asBoolean());
        assertEquals(0, json.get("items").size());
    }

    @Test
    void exactlyTheLimitIsNotMoreAndOneOverIs() throws Exception {
        WeblogEntry a = entry("a");
        WeblogEntry b = entry("b");
        when(manager.getWeblogEntries(any())).thenReturn(List.of(a, b));
        MockMvc mvc = mvc();

        JsonNode exact = new ObjectMapper().readTree(mvc.perform(request().param("limit", "2"))
                .andReturn().getResponse().getContentAsString());
        JsonNode over = new ObjectMapper().readTree(mvc.perform(request().param("limit", "1"))
                .andReturn().getResponse().getContentAsString());

        assertFalse(exact.get("hasMore").asBoolean());
        assertEquals(2, exact.get("items").size());
        assertTrue(over.get("hasMore").asBoolean());
        assertEquals(1, over.get("items").size());
    }

    @Test
    void anUnknownStatusIsRefusedBeforeTheManagerIsAsked() throws Exception {
        mvc().perform(request().param("status", "NOPE")).andExpect(status().isBadRequest());

        verify(manager, never()).getWeblogEntries(any());
    }

    private WeblogEntry entry(String id) {
        WeblogEntry e = new WeblogEntry();
        e.setId(id);
        e.setAnchor(id);
        e.setWebsite(weblog);
        return e;
    }
}
