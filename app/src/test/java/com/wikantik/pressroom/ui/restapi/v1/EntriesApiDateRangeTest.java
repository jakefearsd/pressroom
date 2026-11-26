package com.wikantik.pressroom.ui.restapi.v1;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code startDate} and {@code endDate} on {@code GET .../entries}: the JSP list's
 * date-range filter (spec 2026-11-25 React admin migration, A7). The day bounds are
 * {@link EntriesBean}'s, in the server's default time zone, so a range means the same
 * thing on both screens; a malformed date is a 400 naming the parameter, where the
 * JSP ignores one.
 */
class EntriesApiDateRangeTest {

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
    void theRangeIsTheStartOfTheFirstDayThroughTheEndOfTheLastInTheServersZone() throws Exception {
        WeblogEntrySearchCriteria wesc = criteriaFor(request()
                .param("startDate", "2026-03-01").param("endDate", "2026-03-31"));

        ZoneId zone = ZoneId.systemDefault();
        assertEquals(Date.from(LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant()), wesc.getStartDate());
        assertEquals(Date.from(LocalDate.of(2026, 3, 31).atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant()),
                wesc.getEndDate());
    }

    @Test
    void eitherBoundMayBeSentAlone() throws Exception {
        WeblogEntrySearchCriteria from = criteriaFor(request().param("startDate", "2026-03-01"));
        assertEquals(Date.from(LocalDate.of(2026, 3, 1).atStartOfDay(ZoneId.systemDefault()).toInstant()),
                from.getStartDate());
        assertNull(from.getEndDate());
    }

    @Test
    void aBlankDateIsNoBound() throws Exception {
        WeblogEntrySearchCriteria wesc = criteriaFor(request().param("startDate", " ").param("endDate", ""));

        assertNull(wesc.getStartDate());
        assertNull(wesc.getEndDate());
    }

    @Test
    void aMalformedDateIsA400NamingTheParameterAndNothingIsQueried() throws Exception {
        for (String bad : List.of("03/01/26", "2026-3-1", "2026-02-30", "yesterday")) {
            String body = mvc().perform(request().param("startDate", bad)).andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            JsonNode error = new ObjectMapper().readTree(body).get("errors").get(0);
            assertEquals("startDate", error.get("field").asString(), bad);
            assertEquals("parameter.invalidValue", error.get("code").asString(), bad);
        }
        String body = mvc().perform(request().param("endDate", "x")).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertEquals("endDate", new ObjectMapper().readTree(body).get("errors").get(0).get("field").asString());
        verify(manager, never()).getWeblogEntries(any());
    }
}
