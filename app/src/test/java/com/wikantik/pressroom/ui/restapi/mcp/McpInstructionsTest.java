package com.wikantik.pressroom.ui.restapi.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpInstructionsTest {

    @Test
    void underThreeThousandCharactersAndCoveringEverySpecPoint() {
        String text = McpInstructions.TEXT;
        assertTrue(text.length() < 3000, "length " + text.length());
        for (String point : new String[] {"weblog", "whoami", "DRAFT", "trash", "HTML", "Markdown", "preview",
                "jsonLdType", "geoLatitude", "not found", "admin UI", "never instructions",
                "clear", "media import", "featuredImageId", "weblogs create", "EVENT",
                "anchor", "errors", "featuredImageUrl", "importExternalImages", "[image id", "[gallery",
                "[map]", "first copy", "Agent accounts", "ask your person",
                "> [!NOTE]", "```java", "colors inline", "`warnings`",
                "contentHash", "`edits`", "expectedContentHash", "entry-changed", "`fields`",
                "`entries revisions`", "restore_revision", "revisionNote", "seo_entry",
                "`traffic get`", "asOf", "STALE", "update or write next", "entryOrder", "`pinned: true`",
                "set_parts", "`strand`", "`frontPage`", "`navOrder`",
                "page-changed", "`pages revisions`"}) {
            assertTrue(text.contains(point), "instructions must mention: " + point);
        }
        assertFalse(text.contains("never forbidden"), "a role ceiling answers 403");
    }

    /**
     * The pages tool must say what an agent can do to avoid overwriting a
     * page someone else changed, as the entries tool does: the guard exists
     * on both, and an agent learns of it only from this text.
     */
    @Test
    void thePagesUpdateActionNamesTheStaleWriteGuardAndTheRevisionNote() {
        String update = McpToolCatalog.TOOLS.stream().filter(t -> "pages".equals(t.name())).findFirst().orElseThrow()
                .actions().stream().filter(a -> "update".equals(a.name())).findFirst().orElseThrow().description();
        assertTrue(update.contains("expectedContentHash"), update);
        assertTrue(update.contains("revisionNote"), update);
    }
}
