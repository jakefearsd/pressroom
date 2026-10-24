package org.apache.roller.weblogger.ui.restapi.mcp;

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
                "contentHash", "`edits`", "expectedContentHash", "entry-changed"}) {
            assertTrue(text.contains(point), "instructions must mention: " + point);
        }
        assertFalse(text.contains("never forbidden"), "a role ceiling answers 403");
    }
}
