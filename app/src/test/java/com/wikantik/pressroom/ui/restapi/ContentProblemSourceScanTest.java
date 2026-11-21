package com.wikantik.pressroom.ui.restapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After spec 2026-11-20-api-contract-design, Task 2 (criterion 1), the
 * content routes raise no problem or field error from a literal or
 * concatenated detail: every one is keyed with a {@link ProblemCode}. A
 * source scan of the files the task covers finds no call to an unkeyed
 * {@link ApiException} factory and no {@code FieldErrors.add} whose second
 * argument is not a code. A guard asserts the scan found those files, and
 * that its patterns do match what they exist to catch.
 */
class ContentProblemSourceScanTest {

    private static final String ROOT = "src/main/java/com/wikantik/pressroom/ui/restapi/";

    /** The files the task covers, relative to {@code ui/restapi/}. */
    private static final List<String> FILES = List.of(
            "v1/EntriesApi.java", "v1/EntriesWriteApi.java", "v1/EntryContent.java", "v1/EntryEdits.java",
            "v1/EntryFields.java", "v1/EntryImports.java", "v1/RevisionedContent.java", "v1/PagesApi.java",
            "v1/PageContent.java", "v1/BaseApiController.java", "ColumnLimits.java", "dto/EntryDtos.java",
            "dto/PageDtos.java", "dto/SeoFields.java", "dto/ClearFields.java");

    /** The factories that take a literal detail, whatever the argument. */
    private static final Pattern UNKEYED_FACTORY = Pattern.compile(
            "ApiException\\s*\\.\\s*(badRequest|notFound|conflict|conflictOnField|invalidField|unauthorized"
                    + "|forbidden|validation|tooLarge|throttled|badGateway|busy)\\s*\\(");

    private static final Pattern FIELD_ERRORS_DECLARATION = Pattern.compile("FieldErrors\\s+(\\w+)");

    private static final Pattern WIRE_FIELD_ERROR = Pattern.compile("new\\s+ApiProblem\\s*\\.\\s*FieldError\\s*\\(");

    private static Path path(String file) {
        Path path = Path.of(ROOT + file);
        return Files.exists(path) ? path : Path.of("app", ROOT + file);
    }

    /** The source without its comments, which may name a factory in prose. */
    private static String code(String file) throws IOException {
        return Files.readString(path(file)).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /**
     * The arguments of the call whose open parenthesis is at {@code open}, split at the top level
     * (nested calls and string literals do not split them).
     */
    private static List<String> arguments(String source, int open) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        boolean quoted = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quoted) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                args.add(source.substring(start, i).trim());
                return args;
            } else if (c == ',' && depth == 1) {
                args.add(source.substring(start, i).trim());
                start = i + 1;
            }
        }
        return args;
    }

    /**
     * A {@code add} or {@code addIfAbsent} on one of {@code names} that is not keyed: its second
     * argument holds a string literal (a literal or concatenated message), or the call has two
     * arguments and the second is not a {@code ProblemCode}.
     */
    private static List<String> unkeyedAdds(String source, Set<String> names) {
        List<String> found = new ArrayList<>();
        Matcher add = Pattern.compile("\\b(" + String.join("|", names) + ")\\s*\\.\\s*add(IfAbsent)?\\s*\\(")
                .matcher(source);
        while (add.find()) {
            List<String> args = arguments(source, add.end() - 1);
            if (args.size() < 2) {
                continue;
            }
            String second = args.get(1);
            if (second.contains("\"") || args.size() == 2 && !second.startsWith("ProblemCode.")) {
                found.add(add.group() + String.join(", ", args));
            }
        }
        return found;
    }

    static List<String> violations(String source) {
        List<String> found = new ArrayList<>();
        Matcher factory = UNKEYED_FACTORY.matcher(source);
        while (factory.find()) {
            found.add(factory.group());
        }
        Matcher wire = WIRE_FIELD_ERROR.matcher(source);
        while (wire.find()) {
            found.add(wire.group());
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher declared = FIELD_ERRORS_DECLARATION.matcher(source);
        while (declared.find()) {
            names.add(declared.group(1));
        }
        if (!names.isEmpty()) {
            found.addAll(unkeyedAdds(source, names));
        }
        return found;
    }

    @Test
    void theScanFoundEveryFileTheTaskCovers() throws IOException {
        assertEquals(15, FILES.size());
        for (String file : FILES) {
            assertTrue(Files.exists(path(file)), file + " was not found");
            assertFalse(code(file).isBlank(), file + " is empty");
        }
    }

    @Test
    void theScanCatchesWhatItExistsToCatch() {
        assertEquals(1, violations("throw ApiException.badRequest(\"x\" + y);").size());
        assertEquals(1, violations("throw ApiException . invalidField(field, \"x\");").size());
        assertEquals(1, violations("FieldErrors errors = new FieldErrors();\nerrors.add(\"f\", \"msg\");").size());
        assertEquals(1, violations("FieldErrors failures;\nfailures.addIfAbsent(\"f\",\n  field + \" is bad\");").size());
        assertEquals(1, violations("return new ApiProblem.FieldError(\"f\", \"m\");").size());
        assertEquals(1, violations("FieldErrors errors;\nerrors.add(\"f\", message);").size());
        assertEquals(0, violations("FieldErrors errors;\nerrors.add(\"f\", ProblemCode.FIELD_REQUIRED, \"f\");"
                + "\nerrors.add(\"f\", cond ? ProblemCode.A : ProblemCode.B, x, y);"
                + "\nthrow ApiException.problem(ProblemCode.ENTRY_NOT_FOUND);").size());
        assertEquals(0, violations("List<String> names;\nnames.add(\"x\", y);").size());
    }

    @Test
    void noContentFileRaisesAProblemFromALiteralDetail() throws IOException {
        List<String> found = new ArrayList<>();
        for (String file : FILES) {
            for (String violation : violations(code(file))) {
                found.add(file + ": " + violation);
            }
        }
        assertEquals(List.of(), found);
    }
}
