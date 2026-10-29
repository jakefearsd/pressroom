package org.apache.roller.weblogger.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>Proven to bite: before Task 2 converted them, this test failed listing
 * exactly the ten string-named sites in main Java (WebloggerConfig,
 * WebloggerRuntimeConfig, the two MenuHelper menu resources, ApiRouteTable,
 * the handler interceptor's API package prefix, CacheManager,
 * ApiExceptionHandler, and the Boot application's scan base), with the
 * session-attribute key let through by {@link #NOT_A_CLASS_NAME}.
 *
 * <p>The lexer self-tests below are characterisation tests of
 * {@link #literals}: they passed on arrival. Proven to bite: with the
 * line-comment, block-comment, char-literal and text-block branches of the
 * lexer disabled, all four failed (comment names reported, the name after
 * {@code '"'} and the text-block name missed); the lexer was restored.
 */
class MainJavaStringNameTest {

    private static final String MAIN_JAVA = "app/src/main/java/";

    /** The post-move root, written so no literal here is a qualified name under it. */
    private static final String NEW_ROOT = "com.wikantik.pressroom";

    /** The session attribute key, root-relative. */
    private static final String SESSION_KEY = ".rollersession";

    /**
     * Root-relative literal ({@link ProjectNames#rootRelative}) to the reason
     * it only looks like a class or package name. Keyed without the root, so
     * an entry matches the literal before and after the package move.
     */
    private static final Map<String, String> NOT_A_CLASS_NAME = Map.of(
            SESSION_KEY,
            "session attribute key that only looks like a package; renamed with the production names");

    record Site(String file, int line, String literal) {
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
                if (m.find() && !notAClassName(lit.literal(), ProjectNames.roots())) {
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

    /** True when the whole literal is an entry of {@link #NOT_A_CLASS_NAME}, under any of the roots. */
    static boolean notAClassName(String literal, Collection<String> roots) {
        return ProjectNames.rootRelative(literal, roots).filter(NOT_A_CLASS_NAME::containsKey).isPresent();
    }

    /** The string literals of one source file, skipping comments and char literals. */
    static List<Site> literals(String file, String src) {
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

    // Self-tests. Fixture sources are built from the root, so no literal here is a qualified name.

    private static List<Site> rootLiterals(String src) {
        List<Site> out = new ArrayList<>();
        for (Site s : literals("Fixture.java", src)) {
            if (ProjectNames.QUALIFIED_NAME.matcher(s.literal()).find()) {
                out.add(s);
            }
        }
        return out;
    }

    @Test
    void aNameInALineCommentIsNotALiteral() {
        String name = ProjectNames.currentRoot() + ".a.B";
        assertEquals(List.of(), rootLiterals("int x; // see \"" + name + "\"\n"));
    }

    @Test
    void aNameInABlockCommentIsNotALiteral() {
        String name = ProjectNames.currentRoot() + ".a.B";
        assertEquals(List.of(), rootLiterals("/* see\n \"" + name + "\" */ int x;\n"));
    }

    @Test
    void aQuoteCharLiteralDoesNotHideALiteralAfterIt() {
        String name = ProjectNames.currentRoot() + ".a.B";
        String src = "int x;\nchar q = '\"'; String s = \"" + name + "\";\n";
        assertEquals(List.of(new Site("Fixture.java", 2, name)), rootLiterals(src));
    }

    @Test
    void aNameInATextBlockIsReported() {
        String name = ProjectNames.currentRoot() + ".a.B";
        String src = "String s = \"\"\"\n    uses " + name + "\n    \"\"\";\n";
        List<Site> found = rootLiterals(src);
        assertEquals(1, found.size(), found::toString);
        assertEquals(1, found.get(0).line());
    }

    @Test
    void theAllowlistKeyMatchesBeforeAndAfterTheMove() {
        String legacy = ProjectNames.LEGACY_ROOT;
        Set<String> roots = Set.of(NEW_ROOT, legacy);
        assertTrue(notAClassName(legacy + ".weblogger" + SESSION_KEY, roots));
        assertTrue(notAClassName(NEW_ROOT + SESSION_KEY, roots));
        assertFalse(notAClassName(NEW_ROOT + SESSION_KEY + "x", roots));
        assertFalse(notAClassName("x" + legacy + ".weblogger" + SESSION_KEY, roots));
    }
}
