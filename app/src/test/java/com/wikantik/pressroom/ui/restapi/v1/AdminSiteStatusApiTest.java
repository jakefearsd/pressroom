package com.wikantik.pressroom.ui.restapi.v1;

import java.time.Instant;
import java.util.List;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.AnalyticsStatus;
import com.wikantik.pressroom.business.discovery.DiscoveryManager;
import com.wikantik.pressroom.business.discovery.DiscoveryStatus;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the Maintenance screen shows about front-page discovery and analytics, and the two buttons next to it
 * (spec 2026-11-25 react admin migration, Wave 4): the same managers {@code MaintenanceController} calls.
 */
class AdminSiteStatusApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant T1 = Instant.parse("2026-11-25T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-25T09:00:00Z");

    private final Weblogger weblogger = mock(Weblogger.class);
    private final DiscoveryManager discovery = mock(DiscoveryManager.class);
    private final AnalyticsManager analytics = mock(AnalyticsManager.class);

    private MockMvc mvc() {
        when(weblogger.getDiscoveryManager()).thenReturn(discovery);
        when(weblogger.getAnalyticsManager()).thenReturn(analytics);
        AdminSiteStatusApi controller = new AdminSiteStatusApi();
        controller.weblogger = weblogger;
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static JsonNode json(String body) throws Exception {
        return JSON.readTree(body);
    }

    @Test
    void discoveryStatusIsWhatTheScreenShows() throws Exception {
        when(discovery.getStatus()).thenReturn(new DiscoveryStatus(DiscoveryStatus.State.OK, T1, T2, 120, 30, null));

        JsonNode status = json(mvc().perform(get("/v1/admin/discovery")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals("OK", status.get("state").asString());
        assertEquals("2026-11-25T10:00:00Z", status.get("lastAttempt").asString());
        assertEquals("2026-11-25T09:00:00Z", status.get("lastSuccess").asString());
        assertEquals(120, status.get("scoredEntries").asInt());
        assertEquals(30, status.get("candidates").asInt());
    }

    @Test
    void refreshRunsTheRefreshThenReportsTheStatusItLeft() throws Exception {
        when(discovery.getStatus()).thenReturn(
                new DiscoveryStatus(DiscoveryStatus.State.FAILED, T1, null, 0, 0, "umami unreachable"));

        JsonNode status = json(mvc().perform(post("/v1/admin/discovery/refresh")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        InOrder order = inOrder(discovery);
        order.verify(discovery).refresh();
        order.verify(discovery).getStatus();
        assertEquals("FAILED", status.get("state").asString());
        assertEquals("umami unreachable", status.get("lastError").asString());
    }

    @Test
    void analyticsStatusCarriesRegistrationReadAndFailures() throws Exception {
        when(analytics.getStatus()).thenReturn(new AnalyticsStatus(true, Boolean.TRUE, 2, 3,
                List.of(new AnalyticsStatus.SiteFailure("blog", "boom", T2)), T1, "slow"));

        JsonNode status = json(mvc().perform(get("/v1/admin/analytics")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertTrue(status.get("configured").asBoolean());
        assertTrue(status.get("reachable").asBoolean());
        assertEquals(2, status.get("registered").asInt());
        assertEquals(3, status.get("total").asInt());
        assertEquals("2026-11-25T10:00:00Z", status.get("lastRead").asString());
        assertEquals("slow", status.get("lastReadError").asString());
        assertEquals("blog", status.get("failures").get(0).get("handle").asString());
        assertEquals("boom", status.get("failures").get(0).get("error").asString());
        assertEquals("2026-11-25T09:00:00Z", status.get("failures").get(0).get("at").asString());
    }

    @Test
    void anUnconfiguredAnalyticsHasNullsAndNoFailures() throws Exception {
        when(analytics.getStatus()).thenReturn(AnalyticsStatus.notConfigured());

        JsonNode status = json(mvc().perform(get("/v1/admin/analytics")).andReturn().getResponse()
                .getContentAsString());

        assertEquals(false, status.get("configured").asBoolean());
        assertNull(status.get("reachable"));
        assertEquals(0, status.get("failures").size());
    }

    @Test
    void syncOnlyAsksForASyncAndAnswersAcceptedWithTheStatusAsItStands() throws Exception {
        when(analytics.getStatus()).thenReturn(AnalyticsStatus.notConfigured());

        mvc().perform(post("/v1/admin/analytics/sync")).andExpect(status().isAccepted());

        verify(analytics).requestSync();
        verify(discovery, never()).refresh();
    }

    @Test
    void theControllerIsAdminOnlyAndTokenAdminScoped() {
        assertTrue(AdminSiteStatusApi.class.isAnnotationPresent(AdminScoped.class));
        assertEquals(List.of(GlobalPermission.ADMIN), new AdminSiteStatusApi().requiredGlobalPermissionActions());
    }
}
