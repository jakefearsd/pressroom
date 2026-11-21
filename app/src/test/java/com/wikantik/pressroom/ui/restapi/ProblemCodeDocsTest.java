package com.wikantik.pressroom.ui.restapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/api/README.md's code table, in "Errors", names exactly the problem
 * codes the API has, each with its own status and type (spec
 * 2026-11-20-api-contract-design, Task 1, criterion 8).
 */
class ProblemCodeDocsTest {

    /** A row of the code table: | `code` | status | `type` | meaning |. */
    private static final Pattern ROW = Pattern.compile("^\\| `([^`]+)` \\| ([^|]+) \\| `([^`]+)` \\|", Pattern.MULTILINE);

    /** A {@code "code": "x"} anywhere in the README's examples. */
    private static final Pattern EXAMPLE_CODE = Pattern.compile("\"code\":\\s*\"([^\"]+)\"");

    /** A text and the code of the same JSON object, no nested object between them (the code is last). */
    private static final Pattern EXAMPLE_TEXT_AND_CODE = Pattern.compile(
            "\"(?:detail|message)\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"[^{}]*?\"code\":\\s*\"([^\"]+)\"");

    /** The category fallbacks: in the table, but no route answers them, so no example may show one. */
    private static final Set<String> FALLBACKS = Set.of("request.invalid", "resource.notFound", "resource.conflict",
            "upstream.failure");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    private static String readme() throws IOException {
        Path path = Path.of("..", "docs", "api", "README.md");
        if (!Files.exists(path)) {
            path = Path.of("docs", "api", "README.md");
        }
        return Files.readString(path);
    }

    private static String errorsSection() throws IOException {
        String readme = readme();
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

    /**
     * An example body in the README shows a code a route really answers:
     * one the API has, not a category fallback, and with the text that code
     * renders (its English with each placeholder standing for any value).
     */
    @Test
    void everyExampleCodeIsOneARouteAnswersWithItsOwnText() throws IOException {
        String readme = readme();
        Map<String, ProblemCode> byCode = Arrays.stream(ProblemCode.values())
                .collect(Collectors.toMap(ProblemCode::code, code -> code));
        Matcher codes = EXAMPLE_CODE.matcher(readme);
        int seen = 0;
        while (codes.find()) {
            String code = codes.group(1);
            assertTrue(byCode.containsKey(code), "README example names no such code: " + code);
            assertFalse(FALLBACKS.contains(code), "README example shows a fallback no route answers: " + code);
            seen++;
        }
        assertTrue(seen > 0, "the README has example codes");

        ResourceBundle english = ResourceBundle.getBundle("ApplicationResources", Locale.ROOT,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        Matcher texts = EXAMPLE_TEXT_AND_CODE.matcher(readme);
        while (texts.find()) {
            ProblemCode code = byCode.get(texts.group(2));
            String pattern = english.getString(code.key());
            String text = texts.group(1).replace("\\\"", "\"");
            assertTrue(Pattern.matches(asRegex(code, pattern), text),
                    code.code() + ": the example's text \"" + text + "\" is not what it renders: " + pattern);
        }
    }

    /** The English as a regex: literal text, each {n} any value; '' is one apostrophe when it formats. */
    private static String asRegex(ProblemCode code, String pattern) {
        if (code.arity() == 0) {
            return Pattern.quote(pattern);
        }
        StringBuilder regex = new StringBuilder();
        Matcher placeholder = PLACEHOLDER.matcher(pattern);
        int at = 0;
        while (placeholder.find()) {
            regex.append(Pattern.quote(pattern.substring(at, placeholder.start()).replace("''", "'"))).append(".+");
            at = placeholder.end();
        }
        return regex.append(Pattern.quote(pattern.substring(at).replace("''", "'"))).toString();
    }
}
