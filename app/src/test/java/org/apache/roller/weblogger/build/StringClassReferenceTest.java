package org.apache.roller.weblogger.build;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <p>Proven to bite: changing {@code ThemeResourceLoader} to
 * {@code ThemeResourceLoadr} in app/src/main/webapp/WEB-INF/velocity.properties
 * made this test fail naming that file, the line and the token; the file was
 * restored with git checkout.
 */
class StringClassReferenceTest {

    private static final String SELF =
            "app/src/test/java/org/apache/roller/weblogger/build/StringClassReferenceTest.java";

    private static final Set<String> WEBAPP_EXTENSIONS =
            Set.of("jsp", "jspf", "tld", "xml", "properties", "vm");

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "ico", "webp", "woff", "woff2", "ttf", "eot",
            "jar", "zip", "class", "gz", "pdf", "svgz", "mp4", "avif");

    private static final Pattern JAVA_STRING = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");

    /** Token to the reason it is not a class, package or resource. */
    private static final Map<String, String> ALLOWED_UNRESOLVED = Map.of(
            ProjectNames.LEGACY_ROOT + ".configuration",
            "JBoss module name in jboss-deployment-structure.xml, not a class or package",
            ProjectNames.LEGACY_ROOT + ".weblogger.business.plugins.NoSuchPluginClassXYZ",
            "deliberately nonexistent class: PluginManagerImplTest feeds it to the plugin loader",
            ProjectNames.LEGACY_ROOT + ".NoSuchAnalyzerAnywhere",
            "deliberately nonexistent class: LuceneIndexManagerSearchTest exercises the fallback",
            ProjectNames.LEGACY_ROOT + ".no.such.Model",
            "deliberately nonexistent class: ModelLoaderTest exercises the load failure",
            ProjectNames.LEGACY_ROOT + ".weblogger.ui.restapi.ApiSecurityRefusalTest$Secured$Inner",
            "name handed to isTestFixture() as a string; no such nested class exists",
            ProjectNames.LEGACY_ROOT.replace('.', '/') + "/aapp",
            "tail of the XML namespace URI http://purl.org/apache/roller/aapp#, not a path");

    private record Hit(String file, int line, String token) {
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
            if (glob && ClassNameResolver.matchesClassPrefix(m.group())) {
                continue;
            }
            hits.add(new Hit(file.toString(), line, m.group()));
        }
    }

    @Test
    void everyQualifiedNameOutsideMainJavaResolves() {
        List<String> offenders = new ArrayList<>();
        for (Hit h : scan()) {
            if (ALLOWED_UNRESOLVED.containsKey(h.token())) {
                continue;
            }
            Optional<String> reason = ClassNameResolver.unresolved(h.token());
            if (reason.isPresent()) {
                offenders.add(h.file() + ":" + h.line() + " " + h.token() + " " + reason.get());
            }
        }
        assertTrue(offenders.isEmpty(), "unresolved class names:\n" + String.join("\n", offenders));
    }

    @Test
    void everyAllowlistEntryIsStillPresent() {
        Set<String> seen = new LinkedHashSet<>();
        for (Hit h : scan()) {
            seen.add(h.token());
        }
        List<String> stale = new ArrayList<>();
        for (String token : ALLOWED_UNRESOLVED.keySet()) {
            if (!seen.contains(token)) {
                stale.add(token);
            }
        }
        assertTrue(stale.isEmpty(), "allowlist entries no longer in scope: " + stale);
    }
}
