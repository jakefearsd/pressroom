package com.wikantik.pressroom.build;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every qualified class, package or resource name written as a string outside
 * main Java resolves.
 *
 * <p>Characterisation test (Phase 0 of the Pressroom rebrand): it passes on
 * today's tree and exists to catch what the Phase 1 package move breaks in
 * XML, properties, JSP/TLD, Velocity, POM, config and test-code strings that
 * the compiler never sees. Scope: tracked files under app/src/main/resources,
 * app/src/main/webapp (jsp, jspf, tld, xml, properties, vm), app/src/test/resources,
 * config/, the two pom.xml files, and the double-quoted string literals of
 * app/src/test/java. it-selenium/ is excluded (its classes are not on this
 * classpath; a later task counts it).
 *
 * <p>Once the package move makes the current root differ from the legacy
 * root, every legacy-root token is reported unless allowlisted, whether or
 * not it resolves ({@link #problem}); allowlist keys are root-relative, so
 * they match before and after the move without an edit.
 *
 * <p>Proven to bite: changing {@code ThemeResourceLoader} to
 * {@code ThemeResourceLoadr} in app/src/main/webapp/WEB-INF/velocity.properties
 * made this test fail naming that file, the line and the token; the file was
 * restored with git checkout.
 */
class StringClassReferenceTest {

    private static final String SELF =
            "app/src/test/java/" + StringClassReferenceTest.class.getName().replace('.', '/') + ".java";

    private static final Set<String> WEBAPP_EXTENSIONS =
            Set.of("jsp", "jspf", "tld", "xml", "properties", "vm");

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "ico", "webp", "woff", "woff2", "ttf", "eot",
            "jar", "zip", "class", "gz", "pdf", "svgz", "mp4", "avif");

    private static final Pattern JAVA_STRING = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");

    /** The post-move root, written so no literal here is a qualified name under it. */
    private static final String NEW_ROOT = "com.wikantik.pressroom";

    /**
     * Root-relative name ({@link ProjectNames#rootRelative}) to the reason it
     * is not a class, package or resource. Keyed without the root, so an entry
     * matches the name before and after the package move.
     */
    private static final Map<String, String> ALLOWED_UNRESOLVED = Map.of(
            ".business.plugins.NoSuchPluginClassXYZ",
            "deliberately nonexistent class: PluginManagerImplTest feeds it to the plugin loader",
            ".NoSuchAnalyzerAnywhere",
            "deliberately nonexistent class: LuceneIndexManagerSearchTest exercises the fallback",
            ".no.such.Model",
            "deliberately nonexistent class: ModelLoaderTest exercises the load failure",
            ".ui.restapi.ApiSecurityRefusalTest$Secured$Inner",
            "name handed to isTestFixture() as a string; no such nested class exists");

    /**
     * Whole legacy-root token to the reason it is allowed. These are not
     * package names, so the package move does not rename them and they stay
     * keyed on the legacy root.
     */
    private static final Map<String, String> ALLOWED_LEGACY = Map.of();

    /** Stand-in resolvers for the self-tests. */
    private static final Function<String, Optional<String>> RESOLVES = t -> Optional.empty();
    private static final Function<String, Optional<String>> DOES_NOT_RESOLVE = t -> Optional.of("nope");

    private record Hit(String file, int line, String token, boolean glob) {
    }

    private static String extension(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean inScope(Path p) {
        String s = p.toString().replace('\\', '/');
        if (s.equals(SELF) || BINARY_EXTENSIONS.contains(extension(p))) {
            return false;
        }
        if (s.startsWith("app/src/main/resources/") || s.startsWith("app/src/test/resources/")
                || s.startsWith("config/")) {
            return true;
        }
        if (s.startsWith("app/src/main/webapp/")) {
            return WEBAPP_EXTENSIONS.contains(extension(p));
        }
        if (s.startsWith("app/src/test/java/")) {
            return s.endsWith(".java");
        }
        return s.equals("pom.xml") || s.equals("app/pom.xml");
    }

    private static List<Hit> scan() {
        List<Hit> hits = new ArrayList<>();
        for (Path p : RepoFiles.trackedFiles()) {
            if (!inScope(p)) {
                continue;
            }
            boolean javaTest = p.toString().startsWith("app/src/test/java/");
            String[] lines = RepoFiles.read(p).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (javaTest) {
                    Matcher lit = JAVA_STRING.matcher(lines[i]);
                    while (lit.find()) {
                        collect(hits, p, i + 1, lit.group());
                    }
                } else {
                    collect(hits, p, i + 1, lines[i]);
                }
            }
        }
        return hits;
    }

    private static void collect(List<Hit> hits, Path file, int line, String text) {
        Matcher m = ProjectNames.QUALIFIED_NAME.matcher(text);
        while (m.find()) {
            boolean glob = m.end() < text.length() && text.charAt(m.end()) == '*';
            hits.add(new Hit(file.toString(), line, m.group(), glob));
        }
    }

    /** A trailing-star glob resolves when some compiled class starts with it; otherwise as a name. */
    private static Optional<String> resolve(Hit h) {
        if (h.glob() && ClassNameResolver.matchesClassPrefix(h.token())) {
            return Optional.empty();
        }
        return ClassNameResolver.unresolved(h.token());
    }

    /**
     * Why the token is a problem, or empty when it is fine. Allowlisted tokens
     * are fine. Once the current root differs from the legacy root, a token
     * under the legacy root is reported even if it resolves: stale build
     * output (maven never deletes an old class or resource after a git mv)
     * can make a leftover old name resolve.
     */
    static Optional<String> problem(String token, String currentRoot, String legacyRoot,
            Function<String, Optional<String>> resolver) {
        if (ALLOWED_LEGACY.containsKey(token)) {
            return Optional.empty();
        }
        Optional<String> key = ProjectNames.rootRelative(token, List.of(currentRoot, legacyRoot));
        if (key.isPresent() && ALLOWED_UNRESOLVED.containsKey(key.get())) {
            return Optional.empty();
        }
        if (!currentRoot.equals(legacyRoot)
                && ProjectNames.rootRelative(token, List.of(currentRoot)).isEmpty()
                && ProjectNames.rootRelative(token, List.of(legacyRoot)).isPresent()) {
            return Optional.of("old root left after the package move (it may resolve only from stale build output)");
        }
        return resolver.apply(token);
    }

    @Test
    void everyQualifiedNameOutsideMainJavaResolves() {
        List<String> offenders = new ArrayList<>();
        String current = ProjectNames.currentRoot();
        for (Hit h : scan()) {
            Optional<String> reason = problem(h.token(), current, ProjectNames.LEGACY_ROOT, t -> resolve(h));
            if (reason.isPresent()) {
                offenders.add(h.file() + ":" + h.line() + " " + h.token() + " " + reason.get());
            }
        }
        assertTrue(offenders.isEmpty(), "unresolved class names:\n" + String.join("\n", offenders));
    }

    @Test
    void everyAllowlistEntryIsStillPresent() {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> seenRelative = new LinkedHashSet<>();
        for (Hit h : scan()) {
            seen.add(h.token());
            ProjectNames.rootRelative(h.token(), ProjectNames.roots()).ifPresent(seenRelative::add);
        }
        List<String> stale = new ArrayList<>();
        for (String key : ALLOWED_UNRESOLVED.keySet()) {
            if (!seenRelative.contains(key)) {
                stale.add(key);
            }
        }
        for (String token : ALLOWED_LEGACY.keySet()) {
            if (!seen.contains(token)) {
                stale.add(token);
            }
        }
        assertTrue(stale.isEmpty(), "allowlist entries no longer in scope: " + stale);
    }

    // Self-tests of the decision. NEW_ROOT stands for the root after the package move.

    @Test
    void beforeTheMoveTheResolverDecides() {
        String legacy = ProjectNames.LEGACY_ROOT;
        assertTrue(problem(legacy + ".weblogger.a.B", legacy, legacy, RESOLVES).isEmpty());
        assertTrue(problem(legacy + ".weblogger.a.B", legacy, legacy, DOES_NOT_RESOLVE).isPresent());
    }

    @Test
    void afterTheMoveALegacyNameIsReportedEvenWhenItResolves() {
        String legacy = ProjectNames.LEGACY_ROOT;
        // Stale output in target/ can make the old name load; it is still a leftover.
        assertTrue(problem(legacy + ".weblogger.a.B", NEW_ROOT, legacy, RESOLVES).isPresent());
        assertTrue(problem(legacy.replace('.', '/') + "/weblogger/a/b.xml", NEW_ROOT, legacy, RESOLVES)
                .isPresent());
        assertTrue(problem(NEW_ROOT + ".a.B", NEW_ROOT, legacy, RESOLVES).isEmpty());
        assertTrue(problem(NEW_ROOT + ".a.B", NEW_ROOT, legacy, DOES_NOT_RESOLVE).isPresent());
    }

    @Test
    void allowlistEntriesMatchUnderEitherRoot() {
        String legacy = ProjectNames.LEGACY_ROOT;
        String plugin = ".business.plugins.NoSuchPluginClassXYZ";
        assertTrue(problem(legacy + ".weblogger" + plugin, legacy, legacy, DOES_NOT_RESOLVE).isEmpty());
        assertTrue(problem(NEW_ROOT + plugin, NEW_ROOT, legacy, DOES_NOT_RESOLVE).isEmpty());
        assertTrue(problem(legacy + ".NoSuchAnalyzerAnywhere", NEW_ROOT, legacy, DOES_NOT_RESOLVE).isEmpty());
        assertTrue(problem(NEW_ROOT + plugin + "x", NEW_ROOT, legacy, DOES_NOT_RESOLVE).isPresent());
    }

    @Test
    void legacyOnlyEntriesStayKeyedOnTheLegacyRoot() {
        String legacy = ProjectNames.LEGACY_ROOT;
        assertTrue(problem(legacy + ".configuration", NEW_ROOT, legacy, DOES_NOT_RESOLVE).isPresent());
        assertTrue(problem(NEW_ROOT + ".configuration", NEW_ROOT, legacy, DOES_NOT_RESOLVE).isPresent());
    }
}
