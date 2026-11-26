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

    // ---- wave 3: settings, create, theme, templates, stylesheet, members, inbox, remove

    /** The weblog-scoped screens of wave 3 each carry the handle, at both context paths. */
    @Test
    void wave3WeblogScreensCarryTheHandle() throws Exception {
        Map<String, String> routes = Map.of(
                "weblogConfig", "settings", "themeEdit", "theme", "templates", "templates",
                "stylesheetEdit", "stylesheet", "members", "members", "submissions", "inbox",
                "weblogRemove", "remove");
        for (Map.Entry<String, String> route : routes.entrySet()) {
            String legacy = "/pressroom-ui/authoring/" + route.getKey() + ".rol";
            assertRedirect("", legacy, Map.of("weblog", "it_weblog"),
                    "/pressroom-ui/app/weblogs/it_weblog/" + route.getValue());
            assertRedirect("/pressroom", legacy, Map.of("weblog", "it weblog"),
                    "/pressroom/pressroom-ui/app/weblogs/it%20weblog/" + route.getValue());
            assertRedirect("", legacy, Map.of(), "/pressroom-ui/app/");
        }
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
    void createWeblogHasNoParameters() throws Exception {
        assertRedirect("", "/pressroom-ui/createWeblog.rol", Map.of(), "/pressroom-ui/app/weblogs/new");
        assertRedirect("/pressroom", "/pressroom-ui/createWeblog.rol", Map.of("x", "y"),
                "/pressroom/pressroom-ui/app/weblogs/new");
    }

    @Test
    void templateEditCarriesTheHandleAndTheTemplateId() throws Exception {
        assertRedirect("", "/pressroom-ui/authoring/templateEdit.rol",
                Map.of("weblog", "it_weblog", "bean.id", "t-1"),
                "/pressroom-ui/app/weblogs/it_weblog/templates/t-1");
        assertRedirect("/pressroom", "/pressroom-ui/authoring/templateEdit.rol",
                Map.of("weblog", "it_weblog", "bean.id", "a/b"),
                "/pressroom/pressroom-ui/app/weblogs/it_weblog/templates/a%2Fb");
        assertRedirect("", "/pressroom-ui/authoring/templateEdit.rol", Map.of("weblog", "it_weblog"),
                "/pressroom-ui/app/");
    }

    @Test
    void theInboxCarriesItsPage() throws Exception {
        assertRedirect("", "/pressroom-ui/authoring/submissions.rol", Map.of("weblog", "it_weblog", "page", "2"),
                "/pressroom-ui/app/weblogs/it_weblog/inbox?page=2");
    }

    // ---- wave 2: pages and media

    @Test
    void pagesAndTheirTrashCarryTheHandleIncludingTheSiteSpaces() throws Exception {
        assertRedirect("", "/pressroom-ui/authoring/pages.rol", Map.of("weblog", "it_weblog"),
                "/pressroom-ui/app/weblogs/it_weblog/pages");
        assertRedirect("/pressroom", "/pressroom-ui/authoring/pages.rol", Map.of("weblog", "site-space"),
                "/pressroom/pressroom-ui/app/weblogs/site-space/pages");
        assertRedirect("", "/pressroom-ui/authoring/pagesTrash.rol", Map.of("weblog", "it_weblog"),
                "/pressroom-ui/app/weblogs/it_weblog/pages/trash");
        assertRedirect("", "/pressroom-ui/authoring/pages.rol", Map.of(), "/pressroom-ui/app/");
    }

    @Test
    void pageEditGoesToThePageWithAnIdAndToTheNewPageWithout() throws Exception {
        assertRedirect("", "/pressroom-ui/authoring/pageEdit.rol", Map.of("weblog", "it_weblog", "id", "p 1"),
                "/pressroom-ui/app/weblogs/it_weblog/pages/p%201");
        assertRedirect("/pressroom", "/pressroom-ui/authoring/pageEdit.rol", Map.of("weblog", "it_weblog"),
                "/pressroom/pressroom-ui/app/weblogs/it_weblog/pages/new");
        assertRedirect("", "/pressroom-ui/authoring/pageEdit.rol", Map.of("weblog", "it_weblog", "id", " "),
                "/pressroom-ui/app/weblogs/it_weblog/pages/new");
        assertRedirect("", "/pressroom-ui/authoring/pageEdit.rol", Map.of("id", "p1"), "/pressroom-ui/app/");
    }

    @Test
    void mediaLibraryCarriesTheFolderAndSortUnderTheirNewNames() throws Exception {
        assertRedirect("", "/pressroom-ui/authoring/mediaFileView.rol", Map.of("weblog", "it_weblog"),
                "/pressroom-ui/app/weblogs/it_weblog/media");
        assertRedirect("/pressroom", "/pressroom-ui/authoring/mediaFileView.rol",
                Map.of("weblog", "it_weblog", "directoryId", "d 1", "sortBy", "date_uploaded", "directoryName", "x"),
                "/pressroom/pressroom-ui/app/weblogs/it_weblog/media?dir=d%201&sort=date_uploaded");
    }

    @Test
    void mediaUploadAndEditCarryTheirTarget() throws Exception {
        assertRedirect("", "/pressroom-ui/authoring/mediaFileAdd.rol", Map.of("weblog", "it_weblog"),
                "/pressroom-ui/app/weblogs/it_weblog/media/upload");
        assertRedirect("", "/pressroom-ui/authoring/mediaFileAdd.rol",
                Map.of("weblog", "it_weblog", "directoryId", "d1"),
                "/pressroom-ui/app/weblogs/it_weblog/media/upload?dir=d1");
        assertRedirect("/pressroom", "/pressroom-ui/authoring/mediaFileEdit.rol",
                Map.of("weblog", "it_weblog", "mediaFileId", "m/1"),
                "/pressroom/pressroom-ui/app/weblogs/it_weblog/media/m%2F1");
        assertRedirect("", "/pressroom-ui/authoring/mediaFileEdit.rol", Map.of("weblog", "it_weblog"),
                "/pressroom-ui/app/");
    }

    /** The image chooser overlay has no React page (the editors open a modal); its address opens the library. */
    @Test
    void theImageChooserOverlayOpensTheMediaLibrary() throws Exception {
        assertRedirect("", "/pressroom-ui/authoring/overlay/mediaFileImageChooser.rol", Map.of("weblog", "it_weblog"),
                "/pressroom-ui/app/weblogs/it_weblog/media");
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

    @Test
    void entriesCarriesTheFilterUnderTheReactNames() throws Exception {
        assertRedirect("", "/pressroom-ui/authoring/entries.rol", Map.of("weblog", "it_weblog"),
                "/pressroom-ui/app/weblogs/it_weblog/entries");
        assertRedirect("/pressroom", "/pressroom-ui/authoring/entries.rol",
                Map.of("weblog", "it_weblog", "bean.status", "DRAFT", "bean.categoryName", "Trips",
                        "bean.tagsAsString", "a b", "bean.text", "rome", "bean.startDateString", "2026-01-01",
                        "bean.endDateString", "2026-02-01", "bean.sortBy", "PUBLICATION_TIME", "bean.page", "3"),
                "/pressroom/pressroom-ui/app/weblogs/it_weblog/entries?status=DRAFT&category=Trips&tags=a%20b"
                        + "&text=rome&startDate=2026-01-01&endDate=2026-02-01&sortBy=PUBLICATION_TIME");
    }

    @Test
    void newEntryGoesToTheNewEntryRoute() throws Exception {
        assertRedirect("", "/pressroom-ui/authoring/entryAdd.rol", Map.of("weblog", "it_weblog"),
                "/pressroom-ui/app/weblogs/it_weblog/entries/new");
        assertRedirect("/pressroom", "/pressroom-ui/authoring/entryAdd.rol", Map.of(),
                "/pressroom/pressroom-ui/app/");
    }

    @Test
    void editEntryCarriesTheIdAndIsHomeWithoutOne() throws Exception {
        assertRedirect("", "/pressroom-ui/authoring/entryEdit.rol", Map.of("weblog", "w", "bean.id", "e 1"),
                "/pressroom-ui/app/weblogs/w/entries/e%201");
        assertRedirect("/pressroom", "/pressroom-ui/authoring/entryEdit!firstSave.rol",
                Map.of("weblog", "w", "bean.id", "e1"), "/pressroom/pressroom-ui/app/weblogs/w/entries/e1");
        assertRedirect("", "/pressroom-ui/authoring/entryEdit.rol", Map.of("weblog", "w"), "/pressroom-ui/app/");
    }

    @Test
    void newEntryFromMediaCarriesTheChosenFilesAsMediaIds() throws Exception {
        assertRedirect("", "/pressroom-ui/authoring/entryAddWithMediaFile.rol",
                Map.of("weblog", "w", "selectedImages", "m1"), "/pressroom-ui/app/weblogs/w/entries/new?mediaId=m1");
        assertRedirect("/pressroom", "/pressroom-ui/authoring/entryAddWithMediaFile.rol",
                Map.of("weblog", "w", "selectedImage", "m 2"),
                "/pressroom/pressroom-ui/app/weblogs/w/entries/new?mediaId=m%202");
        assertRedirect("", "/pressroom-ui/authoring/entryAddWithMediaFile.rol", Map.of("weblog", "w"),
                "/pressroom-ui/app/weblogs/w/entries/new");
    }

    @Test
    void trashCommentsAndCategoriesCarryTheHandle() throws Exception {
        assertRedirect("", "/pressroom-ui/authoring/trash.rol", Map.of("weblog", "w"),
                "/pressroom-ui/app/weblogs/w/trash");
        assertRedirect("/pressroom", "/pressroom-ui/authoring/categories.rol", Map.of("weblog", "w"),
                "/pressroom/pressroom-ui/app/weblogs/w/categories");
        assertRedirect("", "/pressroom-ui/authoring/comments.rol",
                Map.of("weblog", "w", "status", "HIDDEN", "entry", "e1", "page", "2"),
                "/pressroom-ui/app/weblogs/w/comments?status=HIDDEN&entry=e1");
        assertRedirect("", "/pressroom-ui/authoring/comments.rol", Map.of(), "/pressroom-ui/app/");
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
        assertTrue(controllers.size() > 10, "the controller scan found them: " + controllers.size());
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
