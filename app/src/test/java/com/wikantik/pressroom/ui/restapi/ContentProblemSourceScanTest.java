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

    /** The factory names that take a literal detail: the one list both patterns below are built from. */
    private static final String FREE_TEXT_FACTORIES = "badRequest|notFound|conflict|conflictOnField|invalidField"
            + "|unauthorized|forbidden|validation|tooLarge|throttled|badGateway|busy";

    /** The factories that take a literal detail, whatever the argument. */
    private static final Pattern UNKEYED_FACTORY = Pattern.compile(
            "ApiException\\s*\\.\\s*(" + FREE_TEXT_FACTORIES + ")\\s*\\(");

    private static final Pattern FIELD_ERRORS_DECLARATION = Pattern.compile("FieldErrors\\s+(\\w+)");

    private static final Pattern WRITER_DECLARATION = Pattern.compile("ApiProblemWriter\\s+(\\w+)");

    private static final Pattern WIRE_FIELD_ERROR = Pattern.compile("new\\s+ApiProblem\\s*\\.\\s*FieldError\\s*\\(");

    /** The wire problem built by hand: a record, so its canonical constructor is public. */
    private static final Pattern WIRE_PROBLEM = Pattern.compile("new\\s+ApiProblem\\s*\\(");

    /**
     * A static import of a free-text factory, or of all of {@code ApiException}'s: the calls it
     * allows carry no {@code ApiException.}, so {@link #UNKEYED_FACTORY} would not see them.
     */
    private static final Pattern STATIC_FACTORY_IMPORT = Pattern.compile(
            "import\\s+static\\s+[\\w.]*ApiException\\s*\\.\\s*(" + FREE_TEXT_FACTORIES + "|\\*)\\s*;");

    /** An expression ending in a call whose name ends in {@code code} or {@code Code}. */
    private static final Pattern CODE_CALL = Pattern.compile(
            "(?s)(?:(?:[\\w.]*\\.)?code|[\\w.]*[a-z]Code)\\s*\\(.*\\)");

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
     * argument holds a string literal (a literal or concatenated message), or is neither a {@code
     * ProblemCode} constant (a conditional between two counts) nor a call answering a code ({@code
     * shape.code()}, {@code refusalCode(reason)}). A bare variable there could be a message.
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
            if (second.contains("\"") || !second.contains("ProblemCode.") && !CODE_CALL.matcher(second).matches()) {
                found.add(add.group() + String.join(", ", args));
            }
        }
        return found;
    }

    /**
     * A {@code write(response, problem)} on one of {@code names} (an {@code ApiProblemWriter})
     * whose problem is not a keyed {@code ApiException} rendered on the spot ({@code
     * .toProblem(...)}): an {@code ApiProblem} from anywhere else may carry free text.
     */
    private static List<String> unkeyedWrites(String source, Set<String> names) {
        List<String> found = new ArrayList<>();
        Matcher write = Pattern.compile("\\b(" + String.join("|", names) + ")\\s*\\.\\s*write\\s*\\(").matcher(source);
        while (write.find()) {
            List<String> args = arguments(source, write.end() - 1);
            if (args.size() == 2 && !args.get(1).contains(".toProblem(")) {
                found.add(write.group() + String.join(", ", args));
            }
        }
        return found;
    }

    private static Set<String> declared(Pattern declaration, String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher declared = declaration.matcher(source);
        while (declared.find()) {
            names.add(declared.group(1));
        }
        return names;
    }

    static List<String> violations(String source) {
        List<String> found = new ArrayList<>();
        Matcher factory = UNKEYED_FACTORY.matcher(source);
        while (factory.find()) {
            found.add(factory.group());
        }
        Matcher imported = STATIC_FACTORY_IMPORT.matcher(source);
        while (imported.find()) {
            found.add(imported.group());
        }
        for (Pattern byHand : List.of(WIRE_FIELD_ERROR, WIRE_PROBLEM)) {
            Matcher wire = byHand.matcher(source);
            while (wire.find()) {
                found.add(wire.group());
            }
        }
        Set<String> names = declared(FIELD_ERRORS_DECLARATION, source);
        if (!names.isEmpty()) {
            found.addAll(unkeyedAdds(source, names));
        }
        Set<String> writers = declared(WRITER_DECLARATION, source);
        if (!writers.isEmpty()) {
            found.addAll(unkeyedWrites(source, writers));
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

    /**
     * The ways around the keyed factories the first patterns did not see (spec
     * 2026-11-21-foundation-cleanup-design, Task 3, item 2): a variable where the
     * code goes in an {@code add} of three or more arguments, a static import of
     * a free-text factory (whose calls then carry no {@code ApiException.}), and
     * a problem built or written by hand, past the keyed path.
     */
    @Test
    void theScanCatchesTheWaysAroundTheKeyedFactories() {
        assertEquals(1, violations("FieldErrors errors;\nerrors.add(\"f\", message, x);").size());
        assertEquals(1, violations("FieldErrors errors;\nerrors.addIfAbsent(\"f\", detail, x, y);").size());
        assertEquals(0, violations("FieldErrors errors;\nerrors.add(f, shape.code(), args);"
                + "\nerrors.add(\"entries\", refusalCode(reason), id);").size(), "a call answering a code is keyed");
        assertEquals(1, violations("FieldErrors errors;\nerrors.add(\"f\", URLEncoder.encode(m), x);").size(),
                "encode( ends in code( but answers no code");

        assertEquals(1, violations("import static x.ApiException.badRequest;\nclass A { }").size());
        assertEquals(1, violations("import static x.y.ApiException . *;\nclass A { }").size());
        assertEquals(0, violations("import static x.ApiException.problem;\nclass A { }").size(),
                "the keyed factory may be imported");

        assertEquals(1, violations("return new ApiProblem(type, title, 400, detail, null, List.of(), null, c);").size());
        assertEquals(1, violations("ApiProblemWriter writer;\nwriter.write(response, problem);").size());
        assertEquals(1, violations("private static final ApiProblemWriter PROBLEMS = null;\n"
                + "PROBLEMS.write(response, rendered);").size());
        assertEquals(0, violations("ApiProblemWriter writer;\n"
                + "writer.write(response, ApiException.problem(ProblemCode.MCP_TOKEN_REQUIRED).toProblem(uri));\n"
                + "writer.write(request, response, ApiException.problem(ProblemCode.AUTH_FORBIDDEN));").size(),
                "a keyed problem, rendered or not, may be written");
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
