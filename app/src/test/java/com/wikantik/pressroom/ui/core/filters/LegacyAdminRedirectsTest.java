package com.wikantik.pressroom.ui.core.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.wikantik.pressroom.ui.controllers.RouteCoverageTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The legacy-route table and its filter: one test per row (what it carries and how it behaves under a
 * context path), the filter's method and non-table behaviour, and the guard that a route is redirected
 * <em>or</em> handled by a JSP controller, never both.
 */
class LegacyAdminRedirectsTest {

    private final LegacyAdminRedirectFilter filter = new LegacyAdminRedirectFilter();

    private MockHttpServletResponse get(String ctx, String path, Map<String, String> params) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", ctx + path);
        req.setContextPath(ctx);
        params.forEach(req::setParameter);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    private void assertRedirect(String ctx, String path, Map<String, String> params, String expected)
            throws Exception {
        MockHttpServletResponse res = get(ctx, path, params);
        assertEquals(302, res.getStatus(), path);
        assertEquals(expected, res.getHeader("Location"), path);
    }

    // ---- one test per row

    @Test
    void menuGoesToTheAppHomeAtBothContextPaths() throws Exception {
        assertRedirect("", "/pressroom-ui/menu.rol", Map.of(), "/pressroom-ui/app/");
        assertRedirect("/pressroom", "/pressroom-ui/menu.rol", Map.of(), "/pressroom/pressroom-ui/app/");
    }

    @Test
    void weblogTrafficCarriesTheHandleAndTheTrafficQuery() throws Exception {
        assertRedirect("", "/pressroom-ui/authoring/traffic.rol", Map.of("weblog", "it_weblog"),
                "/pressroom-ui/app/weblogs/it_weblog/traffic");
        assertRedirect("/pressroom", "/pressroom-ui/authoring/traffic.rol",
                Map.of("weblog", "it_weblog", "period", "7d", "tab", "sources", "sort", "trend"),
                "/pressroom/pressroom-ui/app/weblogs/it_weblog/traffic?period=7d&tab=sources&sort=trend");
    }

    @Test
    void aHandleIsOneEncodedSegmentAndOtherParametersAreDropped() throws Exception {
        assertRedirect("", "/pressroom-ui/authoring/traffic.rol",
                Map.of("weblog", "a/b ?c", "period", "9 0", "other", "x"),
                "/pressroom-ui/app/weblogs/a%2Fb%20%3Fc/traffic?period=9%200");
    }

    @Test
    void trafficWithoutAHandleGoesToTheHomeNotToARouteThatCannotRender() throws Exception {
        assertRedirect("", "/pressroom-ui/authoring/traffic.rol", Map.of("period", "7d"), "/pressroom-ui/app/");
        assertRedirect("", "/pressroom-ui/authoring/traffic.rol", Map.of("weblog", " "), "/pressroom-ui/app/");
    }

    @Test
    void siteTrafficCarriesThePeriod() throws Exception {
        assertRedirect("", "/pressroom-ui/admin/siteTraffic.rol", Map.of(), "/pressroom-ui/app/admin/traffic");
        assertRedirect("/pressroom", "/pressroom-ui/admin/siteTraffic.rol", Map.of("period", "90d"),
                "/pressroom/pressroom-ui/app/admin/traffic?period=90d");
    }

    // ---- wave 4: site, people and own-account screens

    @Test
    void siteScreensGoToTheirReactRoutes() throws Exception {
        for (String ctx : List.of("", "/pressroom")) {
            assertRedirect(ctx, "/pressroom-ui/admin/globalConfig.rol", Map.of(), ctx + "/pressroom-ui/app/admin/config");
            assertRedirect(ctx, "/pressroom-ui/setup.rol", Map.of(), ctx + "/pressroom-ui/app/admin/setup");
            assertRedirect(ctx, "/pressroom-ui/admin/businesses.rol", Map.of(),
                    ctx + "/pressroom-ui/app/admin/businesses");
            assertRedirect(ctx, "/pressroom-ui/admin/maintenance.rol", Map.of(),
                    ctx + "/pressroom-ui/app/admin/maintenance");
            assertRedirect(ctx, "/pressroom-ui/admin/apiTokens.rol", Map.of(), ctx + "/pressroom-ui/app/admin/tokens");
        }
    }

    @Test
    void businessEditCarriesTheIdAndNewGoesToTheCreateForm() throws Exception {
        assertRedirect("", "/pressroom-ui/admin/businesses!edit.rol", Map.of("id", "b 1"),
                "/pressroom-ui/app/admin/businesses/b%201");
        assertRedirect("/pressroom", "/pressroom-ui/admin/businesses!edit.rol", Map.of(),
                "/pressroom/pressroom-ui/app/admin/businesses/new");
        assertRedirect("", "/pressroom-ui/admin/businesses!edit.rol", Map.of("id", " "),
                "/pressroom-ui/app/admin/businesses/new");
    }

    @Test
    void theUserListCarriesItsTabAndFilter() throws Exception {
        assertRedirect("", "/pressroom-ui/admin/userAdmin.rol", Map.of(), "/pressroom-ui/app/admin/users");
        assertRedirect("/pressroom", "/pressroom-ui/admin/userAdmin.rol", Map.of("kind", "agents", "q", "bo t"),
                "/pressroom/pressroom-ui/app/admin/users?kind=agents&q=bo%20t");
    }

    @Test
    void createUserAndItsCancelAndTheEditFormsGoToTheUserScreens() throws Exception {
        assertRedirect("", "/pressroom-ui/admin/createUser.rol", Map.of(), "/pressroom-ui/app/admin/users/new");
        assertRedirect("", "/pressroom-ui/admin/createUser!cancel.rol", Map.of(), "/pressroom-ui/app/admin/users");
        assertRedirect("", "/pressroom-ui/admin/modifyUser!cancel.rol", Map.of(), "/pressroom-ui/app/admin/users");
        for (String path : List.of("modifyUser", "modifyUser!firstSave")) {
            assertRedirect("/pressroom", "/pressroom-ui/admin/" + path + ".rol", Map.of("bean.userName", "a b"),
                    "/pressroom/pressroom-ui/app/admin/users/a%20b");
            assertRedirect("", "/pressroom-ui/admin/" + path + ".rol", Map.of(), "/pressroom-ui/app/admin/users");
        }
    }

    @Test
    void agentScreensGoToTheAgentRoutes() throws Exception {
        assertRedirect("", "/pressroom-ui/admin/createAgent.rol", Map.of(), "/pressroom-ui/app/admin/agents/new");
        assertRedirect("/pressroom", "/pressroom-ui/admin/agent.rol", Map.of("userName", "bot"),
                "/pressroom/pressroom-ui/app/admin/agents/bot");
        assertRedirect("", "/pressroom-ui/admin/agent.rol", Map.of(), "/pressroom-ui/app/admin/users?kind=agents");
    }

    @Test
    void profileAndItsTokensGoToTheOwnAccountScreens() throws Exception {
        assertRedirect("", "/pressroom-ui/profile.rol", Map.of(), "/pressroom-ui/app/profile");
        assertRedirect("/pressroom", "/pressroom-ui/profileTokens.rol", Map.of(),
                "/pressroom/pressroom-ui/app/profile/tokens");
        assertRedirect("", "/pressroom-ui/profileTokens!reveal.rol", Map.of(), "/pressroom-ui/app/profile/tokens");
    }

    // ---- the filter

    @Test
    void headIsRedirectedToo() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("HEAD", "/pressroom-ui/menu.rol");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertEquals(302, res.getStatus());
    }

    @Test
    void aPostAndAnUnlistedPathPassThrough() throws Exception {
        for (MockHttpServletRequest req : List.of(new MockHttpServletRequest("POST", "/pressroom-ui/menu.rol"),
                new MockHttpServletRequest("GET", "/pressroom-ui/login.rol"))) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, chain);
            assertSame(req, chain.getRequest(), req.getRequestURI() + " goes on down the chain");
            assertNull(res.getHeader("Location"));
            assertEquals(200, res.getStatus());
        }
    }

    // ---- the table

    @Test
    void theTableIsNotEmptyAndNamesEachLegacyPathOnce() {
        assertFalse(LegacyAdminRedirects.ROWS.isEmpty());
        Set<String> seen = new HashSet<>();
        for (LegacyAdminRedirects row : LegacyAdminRedirects.ROWS) {
            assertTrue(seen.add(row.legacyPath()), row.legacyPath() + " appears twice");
            assertTrue(row.legacyPath().startsWith("/pressroom-ui/") && row.legacyPath().endsWith(".rol"),
                    row.legacyPath());
            assertTrue(row.reactPath().startsWith("/"), row.reactPath());
            for (String placeholder : row.pathParams().keySet()) {
                assertTrue(row.reactPath().contains("{" + placeholder + "}"),
                        row.reactPath() + " has no {" + placeholder + "}");
            }
        }
        assertEquals(seen.size(), LegacyAdminRedirects.legacyPaths().size());
    }

    /** A legacy route is redirected or handled by a JSP controller, never both: delete the controller. */
    @Test
    void noRedirectedRouteIsStillHandledByAController() {
        Set<String> controllers = RouteCoverageTest.declaredGetRoutes();
        assertNotNull(controllers);
        assertTrue(controllers.size() > 40, "the controller scan found them: " + controllers.size());
        for (String legacy : LegacyAdminRedirects.legacyPaths()) {
            assertFalse(controllers.contains(legacy),
                    legacy + " is redirected to the React admin and must not also have a controller GET handler:"
                            + " delete the controller, its JSP, view model and unused messages (spec A8 step 2)");
        }
    }

    @Test
    void locateIgnoresPathsOutsideTheTable() {
        assertTrue(LegacyAdminRedirects.locate("", "/pressroom-ui/login.rol", n -> null).isEmpty());
    }
}
