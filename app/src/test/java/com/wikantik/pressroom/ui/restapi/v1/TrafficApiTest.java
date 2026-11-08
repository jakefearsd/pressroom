package com.wikantik.pressroom.ui.restapi.v1;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.KeyCount;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.SeriesPoint;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.Summary;
import com.wikantik.pressroom.business.analytics.BlogTraffic;
import com.wikantik.pressroom.business.analytics.TrafficState;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.ErrorFieldMatchers.errorFieldIs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A blog's traffic over the API (spec 2026-11-08-agent-api-parity-design, Traffic): the snapshot the
 * Traffic page shows, for one period, with the API's stricter period rule, nothing internal, and
 * valid JSON for a trend that has nothing to compare with.
 */
class TrafficApiTest {

    private static final String URL = "/v1/weblogs/myblog/traffic";
    private static final Instant AS_OF = Instant.parse("2026-11-08T10:15:00Z");

    private AnalyticsManager analytics;
    private Weblog weblog;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Weblogger weblogger = mock(Weblogger.class);
        analytics = mock(AnalyticsManager.class);
        when(weblogger.getAnalyticsManager()).thenReturn(analytics);
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");

        TrafficApi controller = new TrafficApi();
        controller.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static BlogTraffic snapshot(TrafficState state, double trend, String lastError) {
        Map<String, List<KeyCount>> breakdowns = new LinkedHashMap<>();
        breakdowns.put("sources", List.of(new KeyCount("google.com", 12)));
        breakdowns.put("places", List.of(new KeyCount("US", 30)));
        return new BlogTraffic(state, AS_OF, new Summary(40, 50, 120, 10, 3600), new Summary(20, 25, 60, 5, 1800),
                List.of(new SeriesPoint(LocalDate.parse("2026-11-07"), 7, 15),
                        new SeriesPoint(LocalDate.parse("2026-11-08"), 3, 9, 20)),
                List.of(new BlogTraffic.PostRow("e1", "Tom &amp; Jerry", "/myblog/entry/tom", 90, 30, trend),
                        new BlogTraffic.PostRow(null, null, "/myblog/tags/x", 30, -1, 0.0)),
                breakdowns, lastError, 1000);
    }

    private JsonNode okJson(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return new ObjectMapper().readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** T1: the period defaults to 30d, and the answer is the snapshot the Traffic page shows. */
    @Test
    void returnsTheSnapshotForThirtyDaysByDefault() throws Exception {
        when(analytics.getBlogTraffic("myblog-id", "30d")).thenReturn(snapshot(TrafficState.OK, 0.5, null));

        JsonNode body = okJson(get(URL).requestAttr("actionWeblog", weblog));

        assertEquals("OK", body.get("state").asString());
        assertEquals("2026-11-08T10:15:00Z", body.get("asOf").asString());
        assertEquals("30d", body.get("period").asString());
        assertEquals(120, body.get("current").get("views").asLong());
        assertEquals(40, body.get("current").get("visitors").asLong());
        assertEquals(50, body.get("current").get("visits").asLong());
        assertEquals(10, body.get("current").get("bounces").asLong());
        assertEquals(3600, body.get("current").get("totalTimeSeconds").asLong());
        assertEquals(60, body.get("previous").get("views").asLong());

        assertEquals(2, body.get("series").size());
        JsonNode day = body.get("series").get(0);
        assertEquals("2026-11-07", day.get("day").asString());
        assertFalse(day.has("hour"), "a day bucket has no hour: " + day);
        assertEquals(15, day.get("views").asLong());
        assertEquals(7, day.get("visitors").asLong());
        assertEquals(3, body.get("series").get(1).get("hour").asInt());

        JsonNode post = body.get("posts").get(0);
        assertEquals("e1", post.get("entryId").asString());
        assertEquals("Tom &amp; Jerry", post.get("title").asString(), "entry titles are stored escaped");
        assertEquals("/myblog/entry/tom", post.get("path").asString());
        assertEquals(90, post.get("views").asLong());
        assertEquals(30, post.get("visitors").asLong());
        assertEquals(0.5, post.get("trend").asDouble());

        JsonNode notAnEntry = body.get("posts").get(1);
        assertTrue(notAnEntry.get("entryId").isNull(), "a path that is not an entry: " + notAnEntry);
        assertTrue(notAnEntry.get("visitors").isNull(), "-1 (the service cannot say) is null: " + notAnEntry);

        assertEquals(List.of("sources", "places"), List.copyOf(body.get("breakdowns").propertyNames()));
        assertEquals("google.com", body.get("breakdowns").get("sources").get(0).get("key").asString());
        assertEquals(12, body.get("breakdowns").get("sources").get(0).get("value").asLong());
        assertEquals(1000, body.get("pathsCappedAt").asInt());
    }

    /** T1: each of the four periods is read as asked. */
    @Test
    void readsThePeriodAsked() throws Exception {
        for (String period : List.of("24h", "7d", "30d", "90d")) {
            when(analytics.getBlogTraffic("myblog-id", period)).thenReturn(snapshot(TrafficState.OK, 0, null));

            JsonNode body = okJson(get(URL).requestAttr("actionWeblog", weblog).param("period", period));

            assertEquals(period, body.get("period").asString());
            verify(analytics).getBlogTraffic("myblog-id", period);
        }
    }

    /** T1: with no snapshot the state says so, distinctly from zero, and no numbers are made up. */
    @Test
    void notConfiguredSaysSoWithNoNumbers() throws Exception {
        when(analytics.getBlogTraffic("myblog-id", "30d")).thenReturn(
                new BlogTraffic(TrafficState.NOT_CONFIGURED, null, null, null, null, null, null, null));

        JsonNode body = okJson(get(URL).requestAttr("actionWeblog", weblog));

        assertEquals("NOT_CONFIGURED", body.get("state").asString());
        assertFalse(body.has("asOf"), body.toString());
        assertFalse(body.has("current"), body.toString());
        assertEquals(0, body.get("posts").size());
        assertEquals(0, body.get("series").size());
    }

    /** T2: the UI falls back to 30d for a period it does not know; the API refuses, naming the valid ones. */
    @Test
    void anUnknownPeriodIsAFieldErrorNamingTheValidOnes() throws Exception {
        String body = mvc.perform(get(URL).requestAttr("actionWeblog", weblog).param("period", "1y"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("period"))
                .andReturn().getResponse().getContentAsString();

        for (String period : List.of("24h", "7d", "30d", "90d")) {
            assertTrue(body.contains(period), "names " + period + ": " + body);
        }
        verify(analytics, never()).getBlogTraffic(anyString(), anyString());
    }

    /** T2: a period the manager keeps for discovery only (an <N>d window) is not one the API offers. */
    @Test
    void theDiscoveryWindowIsNotAPeriodHere() throws Exception {
        mvc.perform(get(URL).requestAttr("actionWeblog", weblog).param("period", "14d"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("period"));
        verify(analytics, never()).getBlogTraffic(any(), any());
    }

    /** T5: the internal error text behind STALE never leaves the server. */
    @Test
    void theLastErrorIsNotExposed() throws Exception {
        when(analytics.getBlogTraffic("myblog-id", "30d")).thenReturn(
                snapshot(TrafficState.STALE, 0, "Umami answered HTTP 503 at http://umami:3000 password=x"));

        String raw = mvc.perform(get(URL).requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertTrue(raw.contains("\"STALE\""), raw);
        assertFalse(raw.contains("lastError"), raw);
        assertFalse(raw.contains("Umami answered"), raw);
    }

    /** T6: NaN (no longer period to compare with) is null, never a bare NaN token or a string. */
    @Test
    void aNanTrendIsNull() throws Exception {
        when(analytics.getBlogTraffic("myblog-id", "30d")).thenReturn(snapshot(TrafficState.OK, Double.NaN, null));

        String raw = mvc.perform(get(URL).requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertFalse(raw.contains("NaN"), raw);
        JsonNode post = new ObjectMapper().readTree(raw).get("posts").get(0);
        assertTrue(post.has("trend") && post.get("trend").isNull(), post.toString());
    }

    /** T4: the same permission as the Traffic page, and the action weblog is required. */
    @Test
    void onlyABlogAdminMayReadIt() {
        TrafficApi controller = new TrafficApi();
        assertEquals(List.of(WeblogPermission.ADMIN), controller.requiredWeblogPermissionActions());
        assertEquals(List.of(), controller.requiredGlobalPermissionActions());
        assertTrue(controller.isUserRequired());
        assertTrue(controller.isWeblogRequired());
        assertFalse(controller.allowsSiteSpace(), "T8: the site space is not offered");
    }
}
