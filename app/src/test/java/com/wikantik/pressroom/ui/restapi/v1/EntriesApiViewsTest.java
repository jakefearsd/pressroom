package com.wikantik.pressroom.ui.restapi.v1;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.AnalyticsStatus;
import com.wikantik.pressroom.business.analytics.BlogTraffic;
import com.wikantik.pressroom.business.analytics.TrafficState;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code views30d} on the entries list, read as the entries page's "Views (30d)" column is: one batch
 * read per list, and only when the caller asks for it with {@code fields=views30d}.
 */
class EntriesApiViewsTest {

    private final WeblogEntryManager manager = mock(WeblogEntryManager.class);
    private final AnalyticsManager analytics = mock(AnalyticsManager.class);
    private final Weblog weblog = new Weblog();
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        weblog.setId("weblog-1");
        weblog.setHandle("myblog");
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(manager);
        when(weblogger.getUrlStrategy()).thenReturn(mock(URLStrategy.class));
        when(weblogger.getAnalyticsManager()).thenReturn(analytics);
        EntriesApi api = new EntriesApi();
        api.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new ApiExceptionHandler()).build();
        when(manager.getWeblogEntries(any())).thenReturn(List.of(entry("e1"), entry("e2")));
    }

    private static WeblogEntry entry(String id) {
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        return entry;
    }

    private static AnalyticsStatus configured() {
        return new AnalyticsStatus(true, true, 1, 1, List.of(), null, null);
    }

    private JsonNode list(String fields) throws Exception {
        String body = mvc.perform(get("/v1/weblogs/myblog/entries").requestAttr("actionWeblog", weblog)
                        .param("fields", fields))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("items");
    }

    @Test
    void eachItemCarriesItsViewsAndAnEntryWithNoneIsAKnownZeroOnceASnapshotExists() throws Exception {
        when(analytics.getStatus()).thenReturn(configured());
        when(analytics.entryViews("weblog-1", "30d")).thenReturn(Map.of("e1", 1234L));

        JsonNode items = list("views30d");

        assertEquals(1234, items.get(0).get("views30d").asInt());
        assertEquals(0, items.get(1).get("views30d").asInt());
        verify(analytics, times(1)).entryViews("weblog-1", "30d");
    }

    @Test
    void anEntryOutsideACappedSnapshotIsUnknownNotZero() throws Exception {
        when(analytics.getStatus()).thenReturn(configured());
        when(analytics.entryViews("weblog-1", "30d")).thenReturn(Map.of("e1", 7L));
        when(analytics.pathsCappedAt("weblog-1", "30d")).thenReturn(1000);

        JsonNode items = list("views30d");

        assertEquals(7, items.get(0).get("views30d").asInt());
        assertFalse(items.get(1).has("views30d"), "unknown is absent, never a made-up zero");
    }

    @Test
    void beforeAnySnapshotNoEntryHasAFigure() throws Exception {
        when(analytics.getStatus()).thenReturn(configured());
        when(analytics.entryViews("weblog-1", "30d")).thenReturn(Map.of());
        when(analytics.getBlogTraffic("weblog-1", "30d")).thenReturn(
                new BlogTraffic(TrafficState.NO_DATA, null, null, null, null, null, null, null));

        assertFalse(list("views30d").get(0).has("views30d"));
    }

    @Test
    void withoutAnalyticsConfiguredThereIsNoFigureAndNoReadOfThem() throws Exception {
        when(analytics.getStatus()).thenReturn(new AnalyticsStatus(false, null, 0, 0, List.of(), null, null));

        assertFalse(list("views30d").get(0).has("views30d"));
        verify(analytics, never()).entryViews(anyString(), anyString());
    }

    @Test
    void aListThatDoesNotAskForViewsNeverReadsAnalytics() throws Exception {
        when(analytics.getStatus()).thenReturn(configured());

        assertFalse(list("anchor").get(0).has("views30d"));
        verify(analytics, never()).entryViews(anyString(), anyString());
    }
}
