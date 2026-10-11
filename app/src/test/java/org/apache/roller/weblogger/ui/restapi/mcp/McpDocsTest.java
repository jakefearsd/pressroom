package org.apache.roller.weblogger.ui.restapi.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** docs/api/README.md's MCP tool table names exactly the registry's tools and actions. */
class McpDocsTest {

    private static String readme() throws Exception {
        Path path = Path.of("..", "docs", "api", "README.md");
        if (!Files.exists(path)) {
            path = Path.of("docs", "api", "README.md");
        }
        return Files.readString(path);
    }

    @Test
    void theReadmeToolTableMatchesTheCatalog() throws Exception {
        String readme = readme();
        int start = readme.indexOf("## MCP");
        assertTrue(start >= 0, "docs/api/README.md has an '## MCP' section");
        String section = readme.substring(start, readme.indexOf("\n## ", start + 1) < 0 ? readme.length()
                : readme.indexOf("\n## ", start + 1));

        Map<String, List<String>> documented = new LinkedHashMap<>();
        Matcher row = Pattern.compile("(?m)^\\| `([a-z_]+)` \\| ([^|]*)\\|").matcher(section);
        while (row.find()) {
            List<String> actions = new ArrayList<>();
            Matcher action = Pattern.compile("`([a-z_]+)`").matcher(row.group(2));
            while (action.find()) {
                actions.add(action.group(1));
            }
            documented.put(row.group(1), actions);
        }

        Map<String, List<String>> registry = new LinkedHashMap<>();
        McpToolCatalog.TOOLS.forEach(t -> registry.put(t.name(),
                t.actions().stream().map(McpToolCatalog.ActionDecl::name).filter(n -> !n.isEmpty()).toList()));
        assertEquals(registry, documented);
    }
}
