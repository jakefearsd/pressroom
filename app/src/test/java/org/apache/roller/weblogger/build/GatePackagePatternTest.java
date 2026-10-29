package org.apache.roller.weblogger.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * No coverage or analysis gate names a package, class or file that does not
 * exist.
 *
 * <p>JaCoCo matches {@code PACKAGE} elements exactly (a parent package does
 * not cover its subpackages) and a rule whose include matches nothing is
 * silently satisfied. SpotBugs and PMD location filters behave the same way.
 * After the package move an un-updated include would turn a floor into a
 * no-op with the build still green.
 *
 * <p>The first two tests are characterisation tests: written against the
 * current tree and expected to pass at once. The third is a self-test of the
 * checker.
 *
 * <p>Proven to bite: changing the {@code ui.restapi.mcp} include in the
 * parent pom to {@code ui.restapi.mcpx} fails
 * {@link #everyJacocoPackageIncludeNamesAPackageWithClasses()} naming that
 * include; restored afterwards.
 */
class GatePackagePatternTest {

    private static final Path MAIN_JAVA = Path.of("app/src/main/java");

    private static List<Path> mainSources() {
        Path root = RepoFiles.repoRoot().resolve(MAIN_JAVA);
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).map(root::relativize).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Dotted packages that hold at least one .java file directly. */
    private static Set<String> packagesWithClasses() {
        Set<String> packages = new TreeSet<>();
        for (Path p : mainSources()) {
            packages.add(p.getParent().toString().replace('/', '.'));
        }
        return packages;
    }

    /** Empty when {@code include} names a package that holds classes directly. */
    static Optional<String> packageProblem(String include) {
        return packageProblem(include, packagesWithClasses());
    }

    static Optional<String> packageProblem(String include, Set<String> packages) {
        if (include.contains("*") || include.contains("?")) {
            Pattern glob = Pattern.compile(Pattern.quote(include)
                    .replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q"));
            return packages.stream().anyMatch(p -> glob.matcher(p).matches())
                    ? Optional.empty()
                    : Optional.of("matches no package that holds classes");
        }
        if (packages.contains(include)) {
            return Optional.empty();
        }
        boolean hasSubpackages = packages.stream().anyMatch(p -> p.startsWith(include + "."));
        return Optional.of(hasSubpackages
                ? "has only subpackages and no classes of its own (JaCoCo matches exactly)"
                : "is not a package under " + MAIN_JAVA);
    }

    private static List<Element> elements(Element scope, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nl = scope.getElementsByTagName(tag);
        for (int i = 0; i < nl.getLength(); i++) {
            out.add((Element) nl.item(i));
        }
        return out;
    }

    @Test
    void everyJacocoPackageIncludeNamesAPackageWithClasses() {
        Document pom = RepoFiles.xml(Path.of("pom.xml"));
        Set<String> packages = packagesWithClasses();
        int total = 0;
        List<String> problems = new ArrayList<>();
        for (Element rule : elements(pom.getDocumentElement(), "rule")) {
            List<Element> element = elements(rule, "element");
            if (element.isEmpty() || !"PACKAGE".equals(element.get(0).getTextContent().trim())) {
                continue;
            }
            List<Element> includes = elements(rule, "include");
            String first = includes.isEmpty() ? "?" : includes.get(0).getTextContent().trim();
            for (Element include : includes) {
                total++;
                String name = include.getTextContent().trim();
                packageProblem(name, packages).ifPresent(why -> problems.add(
                        "PACKAGE rule starting at '" + first + "': include '" + name + "' " + why));
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
        assertEquals(8, total, "PACKAGE includes in the parent pom (3 rendering + 5 security boundary)");
    }

    @Test
    void everySpotBugsAndPmdLocationNamesSomethingThatExists() {
        List<String> classes = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        for (Path p : mainSources()) {
            String rel = p.toString();
            classes.add(rel.substring(0, rel.length() - 5).replace('/', '.'));
            fileNames.add(p.getFileName().toString());
        }
        List<String> packages = List.copyOf(packagesWithClasses());
        List<String> problems = new ArrayList<>();

        Element sb = RepoFiles.xml(Path.of("config/spotbugs/exclude.xml")).getDocumentElement();
        for (Element e : elements(sb, "Class")) {
            check(problems, "Class", e.getAttribute("name"), classes, true);
        }
        for (Element e : elements(sb, "Package")) {
            check(problems, "Package", e.getAttribute("name"), packages, false);
        }
        for (Element e : elements(sb, "Source")) {
            check(problems, "Source", e.getAttribute("name"), fileNames, false);
        }

        // None exist today; a future exclude-pattern or include-pattern is checked, not ignored.
        String pmd = RepoFiles.read(Path.of("config/pmd/ruleset.xml"));
        Matcher m = Pattern.compile("<(?:exclude|include)-pattern>([^<]*)<").matcher(pmd);
        List<String> tracked = RepoFiles.trackedFiles().stream().map(Path::toString).toList();
        while (m.find()) {
            Pattern re = Pattern.compile(m.group(1).trim());
            if (tracked.stream().noneMatch(f -> re.matcher(f).find())) {
                problems.add("PMD pattern '" + m.group(1).trim() + "' matches no tracked file");
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    private static void check(List<String> problems, String kind, String name,
            List<String> candidates, boolean nestedClasses) {
        boolean ok;
        if (name.startsWith("~")) {
            Pattern re = Pattern.compile(name.substring(1));
            ok = candidates.stream().anyMatch(c -> re.matcher(c).find());
        } else {
            ok = candidates.stream().anyMatch(c -> c.equals(name)
                    || (nestedClasses && name.startsWith(c + "$")));
        }
        if (!ok) {
            problems.add("SpotBugs " + kind + " '" + name + "' matches nothing in main sources");
        }
    }

    @Test
    void aPackageWithOnlySubpackagesIsReported() {
        String root = ProjectNames.currentRoot();
        assertTrue(packageProblem(root + ".weblogger.ui").isPresent(),
                "a package with only subpackages must be reported");
        assertTrue(packageProblem(root + ".weblogger.ui.rendering").isEmpty());
        assertTrue(packageProblem(root + ".weblogger.ui.nosuchpackage").isPresent());
    }
}
