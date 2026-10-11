package org.apache.roller.weblogger.ui.restapi.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiRouteTableTest {

    @Test
    void holdsEveryApiRouteByMethodAndTemplate() {
        ApiRouteTable table = ApiRouteTables.real();

        assertEquals(47, table.all().size(), "the /api/v1 surface as of this plan: " + table.all());
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
}
