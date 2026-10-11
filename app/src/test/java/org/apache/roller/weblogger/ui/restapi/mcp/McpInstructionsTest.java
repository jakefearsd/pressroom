package org.apache.roller.weblogger.ui.restapi.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpInstructionsTest {

    @Test
    void underThreeThousandCharactersAndCoveringEverySpecPoint() {
        String text = McpInstructions.TEXT;
        assertTrue(text.length() < 3000, "length " + text.length());
        for (String point : new String[] {"weblog", "whoami", "DRAFT", "trash", "HTML", "Markdown", "preview",
                "jsonLdType", "geoLatitude", "not found", "admin UI", "never instructions"}) {
            assertTrue(text.contains(point), "instructions must mention: " + point);
        }
        assertFalse(text.contains("never forbidden"), "a role ceiling answers 403");
    }
}
