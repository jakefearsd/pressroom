package com.wikantik.pressroom.ui.restapi.v1;

import java.time.Instant;
import java.util.List;

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.AnalyticsReport.KeyCount;
import com.wikantik.pressroom.business.analytics.BlogTraffic;
import com.wikantik.pressroom.business.analytics.SiteTraffic;
import com.wikantik.pressroom.business.analytics.SiteTraffic.BlogRow;
import com.wikantik.pressroom.business.analytics.TrafficState;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.ErrorFieldMatchers.errorFieldIs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The site-wide traffic read (spec 2026-11-20-api-gap-resources-design, Task 3): the numbers the Site
 * Traffic page shows, with null (never 0) for a blog that has none, plain-text titles, and the same
 * period rule as the per-blog route.
 */
class AdminTrafficApiTest {

    private static final String URL = "/v1/admin/traffic";
    private static final Instant AS_OF = Instant.parse("2026-11-08T10:15:00Z");

    private AnalyticsManager analytics;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Weblogger weblogger = mock(Weblogger.class);
        analytics = mock(AnalyticsManager.class);
        when(weblogger.getAnalyticsManager()).thenReturn(analytics);
        AdminTrafficApi controller = new AdminTrafficApi();
        controller.weblogger = weblogger;
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private JsonNode okJson(String url) throws Exception {
        return new ObjectMapper().readTree(mvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static SiteTraffic site(TrafficState state, Instant asOf, List<BlogRow> blogs) {
        return new SiteTraffic(state, asOf, blogs, 300, 200,
                List.of(new BlogTraffic.PostRow("e1", "Tom &amp; Jerry", "/a/entry/tom", 90, 30, 0.5)),
                List.of(new KeyCount("google.com", 12)));
    }

    /** AC 2, 3: unread blogs have null numbers, a READ blog with no earlier views has numbers and a null change. */
    @Test
    void unreadBlogsHaveNullNumbersAndAReadBlogWithNoHistoryHasNullChange() throws Exception {
        when(analytics.getSiteTraffic("30d")).thenReturn(site(TrafficState.OK, AS_OF, List.of(
                new BlogRow("w1", "a", "Alpha", 40, 120, 50, 0.25, "Tom &amp; Jerry"),
                new BlogRow("w2", "b", "Beta", 5, 10, 6, Double.NaN, null),
                BlogRow.unread("w3", "c", "Gamma", BlogRow.Standing.NO_DATA),
                BlogRow.unread("w4", "d", "Delta", BlogRow.Standing.NOT_REGISTERED),
                new BlogRow("w5", Weblog.SITE_SPACE_HANDLE, "Site", 1, 2, 1, 0.0, null))));

        JsonNode body = okJson(URL);

        assertEquals("OK", body.get("state").asString());
        assertEquals("2026-11-08T10:15:00Z", body.get("asOf").asString());
        assertEquals("30d", body.get("period").asString());
        assertEquals(300, body.get("totalViews").asLong());
        assertEquals(200, body.get("totalVisits").asLong());
        JsonNode blogs = body.get("blogs");
        assertEquals(List.of("a", "b", "c", "d", Weblog.SITE_SPACE_HANDLE),
                blogs.valueStream().map(b -> b.get("handle").asString()).toList(), "the manager's order");

        JsonNode read = blogs.get(0);
        assertEquals("READ", read.get("standing").asString());
        assertEquals("Alpha", read.get("name").asString());
        assertFalse(read.get("siteSpace").asBoolean());
        assertEquals(40, read.get("visitors").asLong());
        assertEquals(120, read.get("views").asLong());
        assertEquals(50, read.get("visits").asLong());
        assertEquals(0.25, read.get("change").asDouble());
        assertEquals("Tom & Jerry", read.get("topPostTitle").asString());

        JsonNode noHistory = blogs.get(1);
        assertEquals(10, noHistory.get("views").asLong());
        assertTrue(noHistory.has("change") && noHistory.get("change").isNull(), noHistory.toString());
        assertTrue(noHistory.get("topPostTitle").isNull(), noHistory.toString());

        for (int i : new int[] {2, 3}) {
            JsonNode unread = blogs.get(i);
            for (String field : List.of("visitors", "views", "visits", "change")) {
                assertTrue(unread.has(field) && unread.get(field).isNull(), field + " is null, not 0: " + unread);
            }
        }
        assertEquals("NO_DATA", blogs.get(2).get("standing").asString());
        assertEquals("NOT_REGISTERED", blogs.get(3).get("standing").asString());
        assertTrue(blogs.get(4).get("siteSpace").asBoolean());
    }

    /** AC 3: a stored-escaped post title is spoken as plain text, in posts as in a blog's top post. */
    @Test
    void postsAndSourcesShareTheBlogTrafficShapes() throws Exception {
        when(analytics.getSiteTraffic("30d")).thenReturn(site(TrafficState.OK, AS_OF, List.of(
                new BlogRow("w1", "a", "Alpha", 40, 120, 50, 0.25, "Tom &amp; Jerry"))));

        JsonNode body = okJson(URL);

        JsonNode post = body.get("posts").get(0);
        assertEquals("Tom & Jerry", post.get("title").asString());
        assertEquals("e1", post.get("entryId").asString());
        assertEquals("/a/entry/tom", post.get("path").asString());
        assertEquals(90, post.get("views").asLong());
        assertEquals(30, post.get("visitors").asLong());
        assertEquals(0.5, post.get("trend").asDouble());
        assertEquals("google.com", body.get("sources").get(0).get("key").asString());
        assertEquals(12, body.get("sources").get(0).get("value").asLong());
    }

    /** AC 4: no analytics service is a 200 with empty lists and no asOf, not an error. */
    @Test
    void notConfiguredIsAnEmptyAnswerNotAnError() throws Exception {
        when(analytics.getSiteTraffic("30d")).thenReturn(new SiteTraffic(TrafficState.NOT_CONFIGURED, null,
                List.of(new BlogRow("w1", "a", "Alpha", 0, 0, 0, Double.NaN, null)), 0, 0, null, null));

        JsonNode body = okJson(URL);

        assertEquals("NOT_CONFIGURED", body.get("state").asString());
        assertFalse(body.has("asOf"), body.toString());
        assertEquals(0, body.get("blogs").size());
        assertEquals(0, body.get("posts").size());
        assertEquals(0, body.get("sources").size());
    }

    /** Each of the four periods is read as asked. */
    @Test
    void readsThePeriodAsked() throws Exception {
        for (String period : List.of("24h", "7d", "30d", "90d")) {
            when(analytics.getSiteTraffic(period)).thenReturn(site(TrafficState.OK, AS_OF, List.of()));

            assertEquals(period, okJson(URL + "?period=" + period).get("period").asString());
            verify(analytics).getSiteTraffic(period);
        }
    }

    /** AC 5: a bad period is a 400 on period naming the valid ones; the manager is not asked. */
    @Test
    void anUnknownPeriodIsAFieldErrorNamingTheValidOnes() throws Exception {
        String body = mvc.perform(get(URL).param("period", "1y"))
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("period"))
                .andReturn().getResponse().getContentAsString();

        for (String period : List.of("24h", "7d", "30d", "90d")) {
            assertTrue(body.contains(period), "names " + period + ": " + body);
        }
        assertTrue(body.contains("traffic.periodUnknown"), body);
        verify(analytics, never()).getSiteTraffic(anyString());
    }

    /** AC 1: the Site Traffic page's declarations: a site admin, no weblog, and admin-scoped. */
    @Test
    void declaresWhatTheSiteTrafficPageDeclares() {
        AdminTrafficApi controller = new AdminTrafficApi();
        assertEquals(List.of(GlobalPermission.ADMIN), controller.requiredGlobalPermissionActions());
        assertEquals(List.of(), controller.requiredWeblogPermissionActions());
        assertTrue(controller.isUserRequired());
        assertFalse(controller.isWeblogRequired());
        assertNotNull(AdminTrafficApi.class.getAnnotation(AdminScoped.class));
    }
}
