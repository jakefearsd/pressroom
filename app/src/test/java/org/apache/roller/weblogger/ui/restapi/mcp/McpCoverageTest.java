package org.apache.roller.weblogger.ui.restapi.mcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every /api/v1 route is an MCP action or a listed exclusion with a reason,
 * and every action or exclusion names a live route. Adding an API route
 * without deciding its MCP action fails here, so the MCP surface cannot
 * drift from the API it fronts.
 */
class McpCoverageTest {

    @Test
    void everyApiRouteIsAnActionOrAStatedExclusion() {
        ApiRouteTable table = ApiRouteTables.real();
        Set<String> declared = new HashSet<>();
        McpToolCatalog.TOOLS.forEach(t -> t.actions().forEach(a -> declared.addAll(a.routes())));
        McpToolCatalog.EXCLUDED.forEach(e -> declared.add(e.route()));

        List<String> uncovered = new ArrayList<>();
        table.all().forEach(r -> {
            if (!declared.contains(r.key())) {
                uncovered.add(r.key());
            }
        });
        assertEquals(List.of(), uncovered, "API routes with neither an MCP action nor an exclusion");
    }

    @Test
    void everyActionAndExclusionNamesALiveRoute() {
        ApiRouteTable table = ApiRouteTables.real();
        List<String> dead = new ArrayList<>();
        McpToolCatalog.TOOLS.forEach(t -> t.actions().forEach(a -> a.routes().forEach(r -> {
            if (table.find(r).isEmpty()) {
                dead.add(t.name() + "." + a.name() + " -> " + r);
            }
        })));
        McpToolCatalog.EXCLUDED.forEach(e -> {
            if (table.find(e.route()).isEmpty()) {
                dead.add("excluded -> " + e.route());
            }
        });
        assertEquals(List.of(), dead);
    }

    @Test
    void noRouteIsDeclaredTwiceAndEveryExclusionHasAReason() {
        List<String> all = new ArrayList<>();
        McpToolCatalog.TOOLS.forEach(t -> t.actions().forEach(a -> all.addAll(a.routes())));
        McpToolCatalog.EXCLUDED.forEach(e -> {
            all.add(e.route());
            assertFalse(e.reason().isBlank(), e.route());
        });
        assertEquals(all.size(), new HashSet<>(all).size(), "a route declared twice: " + all);
    }
}
