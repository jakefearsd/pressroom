package org.apache.roller.weblogger.build;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

/**
 * Main Java names its own classes, packages and resources by reference, not by
 * string.
 *
 * <p>A string such as {@code Class.forName("org.apache.roller...")} survives a
 * package move silently and fails at runtime; a class literal is checked by
 * the compiler. This test scans the double-quoted literals of every tracked
 * {@code app/src/main/java} file (code only: comments are skipped) for a
 * qualified name under the project root and fails on each, unless it is in
 * {@link #NOT_A_CLASS_NAME}.
 */
class MainJavaStringNameTest {

    private static final String MAIN_JAVA = "app/src/main/java/";

    /** Literal to the reason it only looks like a class or package name. */
    private static final Map<String, String> NOT_A_CLASS_NAME = Map.of(
            ProjectNames.LEGACY_ROOT + ".weblogger.rollersession",
            "session attribute key that only looks like a package; renamed with the production names");

    private record Site(String file, int line, String literal) {
    }

    @Test
    void noMainJavaStringLiteralNamesAProjectClassOrPackage() {
        List<Site> offenders = new ArrayList<>();
        for (Path p : RepoFiles.trackedFiles()) {
            String s = p.toString().replace('\\', '/');
            if (!s.startsWith(MAIN_JAVA) || !s.endsWith(".java")) {
                continue;
            }
            for (Site lit : literals(s, RepoFiles.read(p))) {
                Matcher m = ProjectNames.QUALIFIED_NAME.matcher(lit.literal());
                if (m.find() && !NOT_A_CLASS_NAME.containsKey(lit.literal())) {
                    offenders.add(lit);
                }
            }
        }
        StringBuilder msg = new StringBuilder(
                "Name the class or package by a class literal (X.class, X.class.getPackageName(), "
                + "X.class.getResourceAsStream(relative)), not a string:");
        offenders.forEach(o -> msg.append("\n  ").append(o.file()).append(':').append(o.line())
                .append("  \"").append(o.literal()).append('"'));
        assertTrue(offenders.isEmpty(), msg.toString());
    }

    /** The string literals of one source file, skipping comments and char literals. */
    private static List<Site> literals(String file, String src) {
        List<Site> out = new ArrayList<>();
        int line = 1;
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (c == '\n') {
                line++;
                i++;
            } else if (c == '/' && next == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') {
                        line++;
                    }
                    i++;
                }
                i += 2;
            } else if (c == '\'') {
                i++;
                while (i < n && src.charAt(i) != '\'') {
                    i += src.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
            } else if (c == '"' && src.startsWith("\"\"\"", i)) {
                int start = line;
                int end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end;
                out.add(new Site(file, start, src.substring(i + 3, end)));
                for (int k = i; k < end; k++) {
                    if (src.charAt(k) == '\n') {
                        line++;
                    }
                }
                i = end + 3;
            } else if (c == '"') {
                int start = ++i;
                while (i < n && src.charAt(i) != '"' && src.charAt(i) != '\n') {
                    i += src.charAt(i) == '\\' ? 2 : 1;
                }
                out.add(new Site(file, line, src.substring(start, Math.min(i, n))));
                i++;
            } else {
                i++;
            }
        }
        return out;
    }
}
