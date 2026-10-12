package org.apache.roller.weblogger.ui.restapi.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiRouteTableTest {

    @Test
    void holdsEveryApiRouteByMethodAndTemplate() {
        ApiRouteTable table = ApiRouteTables.real();

        assertEquals(48, table.all().size(), "the /api/v1 surface as of this plan: " + table.all());
        assertTrue(table.find("GET /v1/weblogs/{handle}/entries").isPresent());
        assertTrue(table.find("PATCH /v1/admin/users/{userName}").isPresent());
        assertTrue(table.find("GET /v1/weblogs").isPresent());
        assertTrue(table.find("POST /v1/weblogs/{handle}/entries/preview").isPresent());
        assertTrue(table.find("GET /v1/nothing").isEmpty());
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
        org.mockito.Mockito.doReturn(org.apache.roller.weblogger.ui.restapi.v1.AdminApi.class)
                .when(handler).getBeanType();
        return handler;
    }
}
