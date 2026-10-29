package com.wikantik.pressroom.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.wikantik.pressroom.ui.rendering.RendererFactory;
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
 * current tree and expected to pass at once. The rest are self-tests of the
 * checkers; the SpotBugs and PMD ones feed synthetic files, because neither
 * real file has a location entry today.
 *
 * <p>Proven to bite: changing the {@code ui.restapi.mcp} include in the
 * parent pom to {@code ui.restapi.mcpx} fails
 * {@link #everyJacocoPackageIncludeNamesAPackageWithClasses()} naming that
 * include; restored afterwards. The SpotBugs and PMD self-tests failed first
 * against stubs returning no problems, and again with the location match in
 * {@code check} and the PMD pattern match forced to pass.
 */
class GatePackagePatternTest {

    private static final Path MAIN_JAVA = Path.of("app/src/main/java");

    /** Tracked main sources relative to {@link #MAIN_JAVA}: an untracked local file cannot make a stale include look valid. */
    private static List<Path> mainSources() {
        return RepoFiles.trackedFiles().stream()
                .filter(p -> p.startsWith(MAIN_JAVA) && p.toString().endsWith(".java"))
                .map(MAIN_JAVA::relativize)
                .toList();
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

        problems.addAll(spotBugsProblems(
                RepoFiles.xml(Path.of("config/spotbugs/exclude.xml")).getDocumentElement(),
                classes, packages, fileNames));
        // None exist today; a future exclude-pattern or include-pattern is checked, not ignored.
        problems.addAll(pmdProblems(RepoFiles.read(Path.of("config/pmd/ruleset.xml")),
                RepoFiles.trackedFiles().stream().map(Path::toString).toList()));
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    /** Each SpotBugs Class, Package or Source location in the filter that matches nothing. */
    static List<String> spotBugsProblems(Element filter, List<String> classes, List<String> packages,
            List<String> fileNames) {
        List<String> problems = new ArrayList<>();
        for (Element e : elements(filter, "Class")) {
            check(problems, "Class", e.getAttribute("name"), classes, true);
        }
        for (Element e : elements(filter, "Package")) {
            check(problems, "Package", e.getAttribute("name"), packages, false);
        }
        for (Element e : elements(filter, "Source")) {
            check(problems, "Source", e.getAttribute("name"), fileNames, false);
        }
        return problems;
    }

    /** Each PMD exclude-pattern or include-pattern in the ruleset that matches no tracked file. */
    static List<String> pmdProblems(String ruleset, List<String> trackedFiles) {
        List<String> problems = new ArrayList<>();
        Matcher m = Pattern.compile("<(?:exclude|include)-pattern>([^<]*)<").matcher(ruleset);
        while (m.find()) {
            Pattern re = Pattern.compile(m.group(1).trim());
            if (trackedFiles.stream().noneMatch(f -> re.matcher(f).find())) {
                problems.add("PMD pattern '" + m.group(1).trim() + "' matches no tracked file");
            }
        }
        return problems;
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
        String rendering = RendererFactory.class.getPackageName();
        String ui = rendering.substring(0, rendering.lastIndexOf('.'));
        assertTrue(packageProblem(ui).isPresent(), "a package with only subpackages must be reported");
        assertTrue(packageProblem(rendering).isEmpty());
        assertTrue(packageProblem(ui + ".nosuchpackage").isPresent());
    }

    /** Self-test: SpotBugs locations are checked once the filter has any. */
    @Test
    void spotBugsLocationsThatMatchNothingAreReported() {
        Element filter = RepoFiles.xmlText("""
                <FindBugsFilter>
                  <Match><Class name="a.b.Real"/></Match>
                  <Match><Class name="a.b.Real$Inner"/></Match>
                  <Match><Class name="~a\\.b\\.Re.*"/></Match>
                  <Match><Class name="a.b.Gone"/></Match>
                  <Match><Package name="a.b"/></Match>
                  <Match><Package name="a.gone"/></Match>
                  <Match><Source name="Real.java"/></Match>
                  <Match><Source name="Gone.java"/></Match>
                </FindBugsFilter>
                """).getDocumentElement();
        List<String> problems = spotBugsProblems(filter, List.of("a.b.Real"), List.of("a.b"),
                List.of("Real.java"));
        assertEquals(List.of(
                "SpotBugs Class 'a.b.Gone' matches nothing in main sources",
                "SpotBugs Package 'a.gone' matches nothing in main sources",
                "SpotBugs Source 'Gone.java' matches nothing in main sources"), problems);
    }

    /** Self-test: PMD file patterns are checked once the ruleset has any. */
    @Test
    void pmdPatternsThatMatchNoTrackedFileAreReported() {
        String ruleset = """
                <ruleset>
                  <exclude-pattern>.*/Real\\.java</exclude-pattern>
                  <include-pattern> .*/Gone\\.java </include-pattern>
                </ruleset>
                """;
        assertEquals(List.of("PMD pattern '.*/Gone\\.java' matches no tracked file"),
                pmdProblems(ruleset, List.of("app/src/main/java/a/b/Real.java")));
    }
}
