package com.wikantik.pressroom.ui.restapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/api/README.md's code table, in "Errors", names exactly the problem
 * codes the API has, each with its own status and type (spec
 * 2026-11-20-api-contract-design, Task 1, criterion 8).
 */
class ProblemCodeDocsTest {

    /** A row of the code table: | `code` | status | `type` | meaning |. */
    private static final Pattern ROW = Pattern.compile("^\\| `([^`]+)` \\| ([^|]+) \\| `([^`]+)` \\|", Pattern.MULTILINE);

    private static String errorsSection() throws IOException {
        Path path = Path.of("..", "docs", "api", "README.md");
        if (!Files.exists(path)) {
            path = Path.of("docs", "api", "README.md");
        }
        String readme = Files.readString(path);
        int start = readme.indexOf("\n## Errors\n");
        assertTrue(start >= 0, "docs/api/README.md has an '## Errors' section");
        int end = readme.indexOf("\n## ", start + 1);
        return readme.substring(start, end < 0 ? readme.length() : end);
    }

    @Test
    void theCodeTableNamesEveryCodeAndNoOther() throws IOException {
        Map<String, String[]> rows = new LinkedHashMap<>();
        Matcher matcher = ROW.matcher(errorsSection());
        while (matcher.find()) {
            rows.put(matcher.group(1), new String[] {matcher.group(2).trim(), matcher.group(3)});
        }

        assertEquals(Arrays.stream(ProblemCode.values()).map(ProblemCode::code).collect(Collectors.toSet()),
                rows.keySet());
        for (ProblemCode code : ProblemCode.values()) {
            String[] row = rows.get(code.code());
            if (code.type() == null) {
                assertEquals("about:blank", row[1], code.code());
            } else {
                assertEquals(Integer.toString(code.type().status()), row[0], code.code());
                assertTrue(code.type().uri().endsWith("/" + row[1]), code.code() + ": type " + row[1]);
            }
        }
    }
}
