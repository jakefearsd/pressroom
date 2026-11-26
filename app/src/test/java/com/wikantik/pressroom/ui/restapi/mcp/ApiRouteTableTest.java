package com.wikantik.pressroom.ui.restapi.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiRouteTableTest {

    @Test
    void holdsEveryApiRouteByMethodAndTemplate() {
        ApiRouteTable table = ApiRouteTables.real();

        // 59 = 49 + the five comment routes (list, get, update, delete, reply) of CommentsApi + entries/by-anchor/{anchor}
        // + audit/seo/entries/{id} + an entry's revisions: list, get and restore + a blog's traffic
        // + a strand page's parts (PUT) + the page trash: restore and delete-forever (2026-11-16).
        // + a page's revisions: list, get and restore (2026-11-16).
        // + keep and unkeep of a revision, on entries and on pages (2026-11-16).
        // + the admin's revision figures and sweep (2026-11-16).
        // + GET /v1/meta and GET /v1/me/profile (2026-11-21).
        // The reads a contributor may make moved to CategoriesReadApi and MediaReadApi (2026-11-21): the
        // same five routes on new controllers, so the count is unchanged.
        // + POST /v1/weblogs/{handle}/media/{id}/crop (2026-11-21).
        // + the business profiles: five /v1/admin/businesses routes and the weblog's picker (2026-11-21).
        // + GET /v1/weblogs/{handle}/members and GET /v1/admin/traffic (2026-11-21).
        // + Wave 4 of the React admin migration (2026-11-25): a user's weblogs and set-password link, and discovery
        // and analytics status, refresh and sync.
        // + Wave 1: entry duplicate and send-newsletter.
        // + Wave 3: weblog options, themes, and the SA-18 session-only routes.
        // + page preview: POST pages/{id}/preview and POST pages/preview (2026-11-25).
        // + media folders: DELETE and PATCH media/directories/{id} (2026-11-25).
        // + Wave 0 screens: GET /v1/me/weblogs (the main menu) and GET /v1/admin/traffic/{handle} (the site space).
        
        // + PUT /v1/weblogs/{handle}/media/{id}/content, the media edit screen's Replace file (wave 2 cutover).
        // + GET /v1/admin/config/definitions (Global Config screen, 2026-11-25).
        assertEquals(131, table.all().size(), "the /api/v1 surface as of this plan: " + table.all());
        assertTrue(table.find("POST /v1/admin/revisions/sweep").isPresent());
        assertTrue(table.find("POST /v1/weblogs/{handle}/entries/{id}/revisions/{revisionId}/keep").isPresent());
        assertTrue(table.find("POST /v1/weblogs/{handle}/pages/{id}/revisions/{revisionId}/unkeep").isPresent());
        assertTrue(table.find("GET /v1/weblogs/{handle}/pages/{id}/revisions").isPresent());
        assertTrue(table.find("GET /v1/weblogs/{handle}/pages/{id}/revisions/{revisionId}").isPresent());
        assertTrue(table.find("POST /v1/weblogs/{handle}/pages/{id}/revisions/{revisionId}/restore").isPresent());
        assertTrue(table.find("POST /v1/weblogs/{handle}/pages/{id}/restore").isPresent());
        assertTrue(table.find("POST /v1/weblogs/{handle}/pages/{id}/delete-forever").isPresent());
        assertTrue(table.find("GET /v1/weblogs/{handle}/audit/seo/entries/{id}").isPresent());
        assertTrue(table.find("GET /v1/weblogs/{handle}/entries").isPresent());
        assertTrue(table.find("GET /v1/weblogs/{handle}/traffic").isPresent());
        assertTrue(table.find("PUT /v1/weblogs/{handle}/pages/{id}/parts").isPresent());
        assertTrue(table.find("PATCH /v1/admin/users/{userName}").isPresent());
        assertTrue(table.find("GET /v1/weblogs").isPresent());
        assertTrue(table.find("POST /v1/weblogs/{handle}/entries/preview").isPresent());
        assertTrue(table.find("GET /v1/nothing").isEmpty());
    }

    /**
     * The reads the entry editor shows a contributor answer from their own
     * controllers, which declare EDIT_DRAFT; every change on the same paths
     * stays on the publisher's controller (owner decisions, editor parity).
     */
    @Test
    void theReadsAContributorMayMakeHaveTheirOwnControllers() {
        ApiRouteTable table = ApiRouteTables.real();
        java.util.Map<String, String> owners = new java.util.LinkedHashMap<>();
        String w = "/v1/weblogs/{handle}";
        owners.put("GET " + w + "/categories", "CategoriesReadApi");
        owners.put("GET " + w + "/tags", "CategoriesReadApi");
        owners.put("POST " + w + "/categories", "CategoriesApi");
        owners.put("PATCH " + w + "/categories/{id}", "CategoriesApi");
        owners.put("GET " + w + "/media", "MediaReadApi");
        owners.put("GET " + w + "/media/{id}", "MediaReadApi");
        owners.put("GET " + w + "/media/directories", "MediaReadApi");
        owners.put("PATCH " + w + "/media/{id}", "MediaApi");
        owners.put("POST " + w + "/media/{id}/crop", "MediaApi");
        owners.put("PUT " + w + "/media/{id}/content", "MediaApi");
        owners.put("POST " + w + "/media/directories", "MediaApi");
        owners.forEach((route, owner) -> assertEquals(owner,
                table.find(route).orElseThrow().handler().getBeanType().getSimpleName(), route));
    }

    @Test
    void holdsOnlyTheApiPackage() {
        assertTrue(ApiRouteTables.real().all().stream()
                .allMatch(r -> r.handler().getBeanType().getPackageName().equals(ApiRouteTable.API_PACKAGE)));
    }

    @Test
    void twoHandlersOnTheSameMethodAndPatternAreRefusedNotSilentlyMerged() throws Exception {
        java.lang.reflect.Method method = Object.class.getMethod("toString");
        org.springframework.web.method.HandlerMethod first = handler(method);
        org.springframework.web.method.HandlerMethod second = handler(method);
        org.springframework.web.servlet.mvc.method.RequestMappingInfo a =
                org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/v1/x")
                        .methods(org.springframework.web.bind.annotation.RequestMethod.GET).params("a").build();
        org.springframework.web.servlet.mvc.method.RequestMappingInfo b =
                org.springframework.web.servlet.mvc.method.RequestMappingInfo.paths("/v1/x")
                        .methods(org.springframework.web.bind.annotation.RequestMethod.GET).params("b").build();
        java.util.Map<org.springframework.web.servlet.mvc.method.RequestMappingInfo,
                org.springframework.web.method.HandlerMethod> handlers = new java.util.LinkedHashMap<>();
        handlers.put(a, first);
        handlers.put(b, second);
        org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mapping =
                org.mockito.Mockito.mock(
                        org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        org.mockito.Mockito.when(mapping.getHandlerMethods()).thenReturn(handlers);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ApiRouteTable.from(mapping));
        assertTrue(e.getMessage().contains("GET /v1/x"), e.getMessage());
    }

    private static org.springframework.web.method.HandlerMethod handler(java.lang.reflect.Method method) {
        org.springframework.web.method.HandlerMethod handler =
                org.mockito.Mockito.mock(org.springframework.web.method.HandlerMethod.class);
        org.mockito.Mockito.doReturn(com.wikantik.pressroom.ui.restapi.v1.AdminApi.class)
                .when(handler).getBeanType();
        return handler;
    }
}
