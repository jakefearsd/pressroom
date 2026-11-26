package com.wikantik.pressroom.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.pressroom.pojos.ThemeTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The templates screen labels a template's action from a table in {@code Templates.tsx}, because the
 * labels were never message keys: the old JSP printed {@code ComponentType.getReadableName()}, a Java
 * literal. A literal copied into the screen is a copy that can drift, so this pins it to the enum.
 */
class TemplateActionNamesDriftTest {

    private static final Path SCREEN = Path.of("frontend/src/admin/screens/people/Templates.tsx");

    @Test
    void theScreensActionLabelsAreTheEnumsReadableNames() throws Exception {
        String source = Files.readString(SCREEN);
        Matcher block = Pattern.compile("ACTION_NAMES[^=]*=\\s*\\{([^}]*)\\}").matcher(source);
        assertTrue(block.find(), "ACTION_NAMES table not found in Templates.tsx");
        Map<String, String> screen = new LinkedHashMap<>();
        Matcher pair = Pattern.compile("([A-Z]+):\\s*'([^']*)'").matcher(block.group(1));
        while (pair.find()) {
            screen.put(pair.group(1), pair.group(2));
        }
        Map<String, String> server = new LinkedHashMap<>();
        for (ThemeTemplate.ComponentType type : ThemeTemplate.ComponentType.values()) {
            server.put(type.name(), type.getReadableName());
        }
        assertEquals(server, screen);
    }
}
