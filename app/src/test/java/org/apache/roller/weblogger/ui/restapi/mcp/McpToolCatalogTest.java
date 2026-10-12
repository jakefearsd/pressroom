package org.apache.roller.weblogger.ui.restapi.mcp;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpToolCatalogTest {

    private static final List<ResolvedTool> TOOLS = McpToolCatalog.resolve(ApiRouteTables.real());

    private static ResolvedTool tool(String name) {
        return TOOLS.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void exactlyTheElevenToolsWithTheirActions() {
        Map<String, List<String>> actual = TOOLS.stream().collect(Collectors.toMap(ResolvedTool::name,
                t -> List.copyOf(t.actions().keySet())));
        assertEquals(Map.ofEntries(
                Map.entry("whoami", List.of("")),
                Map.entry("weblogs", List.of("list", "get", "create", "update")),
                Map.entry("entries", List.of("list", "get", "create", "update", "trash", "restore",
                        "delete_forever", "preview")),
                Map.entry("categories", List.of("list", "create", "update", "delete", "list_tags")),
                Map.entry("media", List.of("list", "get", "import", "update", "delete", "list_directories",
                        "create_directory")),
                Map.entry("pages", List.of("list", "get", "create", "update", "delete")),
                Map.entry("redirects", List.of("list", "create", "delete")),
                Map.entry("audits", List.of("seo", "media")),
                Map.entry("users", List.of("list", "create", "update")),
                Map.entry("site_settings", List.of("get", "update")),
                Map.entry("maintenance", List.of("flush_cache", "rebuild_index", "regenerate_renditions"))),
                actual);
    }

    @Test
    void onlyWhoamiAndAuditsAreReadOnly() {
        assertEquals(List.of("audits", "whoami"),
                TOOLS.stream().filter(ResolvedTool::readOnly).map(ResolvedTool::name).sorted().toList());
    }

    @Test
    void previewPicksTheSavedEntryVariantWhenGivenAnId() {
        ResolvedAction preview = tool("entries").actions().get("preview");
        assertEquals("POST /v1/weblogs/{handle}/entries/{id}/preview",
                preview.select(Map.of("weblog", "b", "id", "e1", "text", "x")).route().key());
        assertEquals("POST /v1/weblogs/{handle}/entries/preview",
                preview.select(Map.of("weblog", "b", "text", "x")).route().key());
    }

    @Test
    void anActionNamingNoRouteStopsStartup() {
        ApiRouteTable empty = ApiRouteTable.from(
                new org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping());
        assertThrows(IllegalStateException.class, () -> McpToolCatalog.resolve(empty));
    }
}
