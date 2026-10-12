package org.apache.roller.weblogger.ui.restapi.mcp;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolSchemasTest {

    private static final List<ResolvedTool> TOOLS = McpToolCatalog.resolve(ApiRouteTables.real());

    private static ResolvedTool tool(String name) {
        return TOOLS.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(String tool) {
        return (Map<String, Object>) ToolSchemas.inputSchema(tool(tool)).get("properties");
    }

    @Test
    void theEntriesSchemaCarriesTheLocationWithNoMcpCodeNamingIt() {
        Map<String, Object> props = properties("entries");
        assertTrue(props.containsKey("jsonLdType"));
        assertTrue(props.containsKey("geoLatitude"));
        assertTrue(props.containsKey("geoLongitude"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMultiActionToolRequiresAnActionFromItsEnum() {
        Map<String, Object> schema = ToolSchemas.inputSchema(tool("entries"));
        assertEquals(List.of("action"), schema.get("required"));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
        Map<String, Object> action = (Map<String, Object>) properties("entries").get("action");
        assertEquals(List.of("list", "get", "create", "update", "trash", "restore", "delete_forever", "preview"),
                action.get("enum"));
    }

    @Test
    void whoamiTakesNoArgumentsAtAll() {
        assertEquals(Map.of(), properties("whoami"));
        assertNull(ToolSchemas.inputSchema(tool("whoami")).get("required"));
    }

    @Test
    void thePropertyDescriptionIsExactlyTheApiTypesOwnText() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> pubTime = (Map<String, Object>) properties("entries").get("pubTime");
        assertEquals(org.apache.roller.weblogger.ui.restapi.dto.EntryDtos.EntryWrite.class.getMethod("pubTime")
                .getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class).description(),
                pubTime.get("description"));
    }

    @Test
    void theToolDescriptionListsEachActionAndTheArgumentsItTakes() {
        String description = ToolSchemas.description(tool("entries"));
        assertTrue(description.contains("- create:"), description);
        assertTrue(description.contains("geoLatitude"), description);
        assertTrue(description.contains("- delete_forever:"), description);
    }

    /** Every shared description reaches the agent, whichever action declared the argument first. */
    @Test
    @SuppressWarnings("unchecked")
    void everyDescribedEntryWriteFieldKeepsItsSharedDescriptionOnTheEntriesTool() {
        Map<String, Object> props = properties("entries");
        int described = 0;
        for (java.lang.reflect.RecordComponent component
                : org.apache.roller.weblogger.ui.restapi.dto.EntryDtos.EntryWrite.class.getRecordComponents()) {
            io.swagger.v3.oas.annotations.media.Schema schema =
                    component.getAccessor().getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
            if (schema == null) {
                continue;
            }
            described++;
            assertEquals(schema.description(),
                    ((Map<String, Object>) props.get(component.getName())).get("description"), component.getName());
        }
        assertEquals(11, described);
    }
}
