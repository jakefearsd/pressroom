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
        assertEquals(List.of("list", "get", "create", "update", "trash", "restore", "delete_forever", "preview",
                "revisions", "revision", "restore_revision"), action.get("enum"));
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

    /**
     * One property serves every action that takes the argument, so its text
     * must hold for all of them. A default that applies on one action only
     * names that action: omitting locale on a weblogs update leaves it
     * unchanged, it does not become the user's; omitting directoryId on a
     * media update does not move the file to the default directory.
     */
    @Test
    void aDefaultThatAppliesToOneActionNamesThatAction() {
        String[][] cases = {
            {"weblogs", "locale", "On create, "},
            {"weblogs", "timeZone", "On create, "},
            {"media", "directoryId", "On import, "},
            {"media", "name", "On import, "},
        };
        for (String[] c : cases) {
            @SuppressWarnings("unchecked")
            String text = (String) ((Map<String, Object>) properties(c[0]).get(c[1])).get("description");
            assertTrue(text.contains(c[2]), c[0] + "." + c[1] + ": " + text);
            assertFalse(text.contains("omitted, "), c[0] + "." + c[1] + ": " + text);
            assertFalse(text.startsWith("File name to store"), c[0] + "." + c[1] + ": " + text);
        }
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
        // 14 through anchor (Task 3); featuredImageUrl, featuredImageAlt and importExternalImages (Task 8) make 17;
        // summary, once it became the line under the title in entry lists, 18; edits and expectedContentHash
        // (spec 2026-10-24), 20; revisionNote (Wave B), 21.
        assertEquals(21, described);
    }

    /**
     * fields is one property of the entries tool: an array of strings, with
     * one description, on every action that returns entries (list, both get
     * variants, create, update, restore) and on no other.
     */
    @Test
    @SuppressWarnings("unchecked")
    void fieldsIsOneArrayOfStringsOnEveryEntriesActionThatReturnsEntries() {
        Map<String, Object> fields = (Map<String, Object>) properties("entries").get("fields");
        assertEquals("array", fields.get("type"));
        assertEquals(Map.of("type", "string"), fields.get("items"));
        String text = (String) fields.get("description");
        assertTrue(text != null && text.contains("id always comes back"), String.valueOf(text));

        java.util.Set<String> taking = new java.util.TreeSet<>();
        tool("entries").actions().values().forEach(action -> action.variants().forEach(variant ->
                variant.arguments().stream().filter(a -> "fields".equals(a.name())).forEach(a -> {
                    assertEquals(ArgumentSpec.Location.QUERY, a.location(), action.name());
                    assertEquals(fields, a.schema(), action.name());
                    taking.add(action.name() + "/" + variant.route().pathTemplate());
                })));
        assertEquals(java.util.Set.of("create//v1/weblogs/{handle}/entries", "get//v1/weblogs/{handle}/entries/{id}",
                "get//v1/weblogs/{handle}/entries/by-anchor/{anchor}", "list//v1/weblogs/{handle}/entries",
                "restore//v1/weblogs/{handle}/entries/{id}/restore", "update//v1/weblogs/{handle}/entries/{id}",
                "restore_revision//v1/weblogs/{handle}/entries/{id}/revisions/{revisionId}/restore"),
                taking);
    }

    /**
     * One anchor property serves create, where it sets the slug, and get,
     * where it finds the entry (the by-anchor variant's path value carries no
     * text of its own, so EntryWrite's is the one the agent reads).
     */
    @Test
    @SuppressWarnings("unchecked")
    void theAnchorDescriptionHoldsForCreateAndForGet() {
        String text = (String) ((Map<String, Object>) properties("entries").get("anchor")).get("description");
        assertTrue(text != null && text.contains("entries get") && text.contains("create"), String.valueOf(text));
        assertFalse(text.contains("Set only when"), text);
    }

    /** The agent learns from the tool what a summary is for, and what readers see without one. */
    @Test
    @SuppressWarnings("unchecked")
    void theSummarySaysItIsTheListLineAndWhatShowsWithoutOne() {
        String text = (String) ((Map<String, Object>) properties("entries").get("summary")).get("description");
        assertTrue(text != null && text.contains("lists of entries") && text.contains("first couple of sentences"),
                String.valueOf(text));
    }

    /**
     * update and preview both take edits, as one array of find/replace
     * objects: ToolSchemas refuses an argument whose type differs across
     * actions, and the nested descriptions are TextEdit's own.
     */
    @Test
    @SuppressWarnings("unchecked")
    void editsIsOneArrayOfFindReplaceObjectsOnUpdateAndPreview() throws Exception {
        Map<String, Object> edits = (Map<String, Object>) properties("entries").get("edits");
        assertEquals("array", edits.get("type"));
        Map<String, Object> item = (Map<String, Object>) edits.get("items");
        assertEquals("object", item.get("type"));
        Map<String, Object> fields = (Map<String, Object>) item.get("properties");
        assertEquals(java.util.Set.of("find", "replace"), fields.keySet());
        assertEquals(org.apache.roller.weblogger.ui.restapi.dto.EntryDtos.TextEdit.class.getMethod("find")
                .getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class).description(),
                ((Map<String, Object>) fields.get("find")).get("description"));

        String description = ToolSchemas.description(tool("entries"));
        for (String action : List.of("- update:", "- preview:")) {
            String line = description.substring(description.indexOf(action)).lines().findFirst().orElseThrow();
            assertTrue(line.contains("edits"), line);
        }
        String update = description.substring(description.indexOf("- update:")).lines().findFirst().orElseThrow();
        assertTrue(update.contains("expectedContentHash"), update);
    }
}
