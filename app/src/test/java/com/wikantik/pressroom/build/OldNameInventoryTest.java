package com.wikantik.pressroom.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Inventory ratchet for the old product name (Roller) during the rename to
 * Pressroom. Phase 0 renames nothing; {@link #BASELINE} records today's count
 * of old-name occurrences so each later phase can only shrink it. The end
 * state is an empty map, in the style of the i18n ratchets.
 *
 * <p>This replaces the spec's "grouped by layer". {@code PACKAGE} and
 * {@code CLASS} are layer 1. {@code TOKEN_PREFIX} and {@code OTHER} in the
 * non-docs areas are layers 2 and 3. {@code OTHER/docs} is layer 3.
 *
 * <p>Each occurrence is counted under exactly one kind, tried in order
 * PACKAGE, CLASS, TOKEN_PREFIX, OTHER; a match is masked before the next kind
 * is tried. The ratchet is exact in both directions: a count above the
 * baseline fails (old name added), and a count below it fails too (lower the
 * baseline), so the baseline never goes slack.
 *
 * <p>File names are inventoried too: the same counting is run over each
 * tracked file's repo-relative path and recorded under {@code PATH-<KIND>}
 * keys, so a stray {@code org/apache/roller} directory or a {@code Roller*.java} // rebrand:keep
 * file name cannot survive the rename unseen.
 *
 * <p>Proven to bite: {@code // roller} appended to a main Java file produced
 * "old name added in OTHER/main-java: 781 > baseline 780"; restored with
 * git checkout.
 *
 * <p>Phase 1 (the Java package move) must bring these keys to zero:
 * {@code PACKAGE}, {@code CLASS}, {@code PATH-PACKAGE} and {@code PATH-CLASS}
 * in the areas {@code main-java}, {@code main-resources}, {@code test},
 * {@code it} and {@code build}. The {@code docs} area (docs/examples
 * included) and the {@code OTHER} and {@code TOKEN_PREFIX} kinds are later
 * phases; the {@code deploy} and {@code other} areas are not on Phase 1's
 * list either.
 *
 * <p>Excluded forever (they legitimately keep the old name): LICENSE.txt,
 * NOTICE.txt, migrations V001 to V040, docs/superpowers/**, this file and
 * {@link ProjectNames}. Binary files (a NUL in the first 8 KB) are skipped.
 */
class OldNameInventoryTest {

    private static final Pattern PACKAGE = Pattern.compile("org\\.apache\\.roller|org/apache/roller"); // rebrand:keep
    private static final Pattern CLASS = Pattern.compile("\\bRoller[A-Z]\\w*"); // rebrand:keep
    private static final Pattern TOKEN_PREFIX = Pattern.compile("rlr_");
    private static final Pattern OTHER = Pattern.compile("Roller|ROLLER|(?<![A-Za-z])roller"); // rebrand:keep

    private static final Pattern MIGRATION = Pattern.compile("bin/db/migrations/V(\\d+)__.*");

    private static final Path THIS_FILE = sourceOf(OldNameInventoryTest.class);
    private static final Path PROJECT_NAMES = sourceOf(ProjectNames.class);

    /** Keyed "KIND/area". Later phases lower these; the end state is empty. */
    static final Map<String, Integer> BASELINE = Map.ofEntries(
            Map.entry("CLASS/docs", 12),
            Map.entry("OTHER/build", 36),
            Map.entry("OTHER/deploy", 298),
            Map.entry("OTHER/docs", 674),
            Map.entry("OTHER/it", 447),
            Map.entry("OTHER/main-java", 780),
            Map.entry("OTHER/main-resources", 1076),
            Map.entry("OTHER/other", 48),
            Map.entry("OTHER/test", 1903),
            Map.entry("PACKAGE/docs", 115),
            Map.entry("PATH-CLASS/docs", 1),
            Map.entry("PATH-OTHER/deploy", 2),
            Map.entry("PATH-OTHER/docs", 15),
            Map.entry("PATH-OTHER/it", 2),
            Map.entry("PATH-OTHER/main-resources", 31),
            Map.entry("PATH-OTHER/test", 4),
            Map.entry("PATH-PACKAGE/docs", 8),
            Map.entry("TOKEN_PREFIX/docs", 3),
            Map.entry("TOKEN_PREFIX/it", 2),
            Map.entry("TOKEN_PREFIX/main-java", 2),
            Map.entry("TOKEN_PREFIX/test", 42));

    /** Kinds Phase 1 (the package move) must bring to zero. */
    static final Set<String> PHASE_ONE_KINDS = Set.of("PACKAGE", "CLASS", "PATH-PACKAGE", "PATH-CLASS");

    /** Areas Phase 1 covers. */
    static final Set<String> PHASE_ONE_AREAS = Set.of("main-java", "main-resources", "test", "it", "build");

    /** The entries of {@code counts} that are Phase 1 keys ({@code <kind>/<area>}) with a count above zero. */
    static Map<String, Integer> phaseOneLeftovers(Map<String, Integer> counts) {
        Map<String, Integer> out = new TreeMap<>();
        counts.forEach((key, n) -> {
            int slash = key.indexOf('/');
            if (slash > 0 && n > 0 && PHASE_ONE_KINDS.contains(key.substring(0, slash))
                    && PHASE_ONE_AREAS.contains(key.substring(slash + 1))) {
                out.put(key, n);
            }
        });
        return out;
    }

    private static Path sourceOf(Class<?> c) {
        return Path.of("app/src/test/java/" + c.getName().replace('.', '/') + ".java");
    }

    /** Occurrences of each kind in the text; kinds with none are absent. */
    static Map<String, Integer> count(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        String rest = text;
        Map<String, Pattern> kinds = new LinkedHashMap<>();
        kinds.put("PACKAGE", PACKAGE);
        kinds.put("CLASS", CLASS);
        kinds.put("TOKEN_PREFIX", TOKEN_PREFIX);
        kinds.put("OTHER", OTHER);
        for (Map.Entry<String, Pattern> kind : kinds.entrySet()) {
            Matcher m = kind.getValue().matcher(rest);
            int n = 0;
            while (m.find()) {
                n++;
            }
            if (n > 0) {
                counts.put(kind.getKey(), n);
                rest = kind.getValue().matcher(rest).replaceAll(" ");
            }
        }
        return counts;
    }

    /** The coarse part of the repository a repo-relative path belongs to. */
    static String area(Path repoRelative) {
        String p = repoRelative.toString().replace('\\', '/');
        String name = repoRelative.getFileName().toString();
        if (p.startsWith("app/src/main/java/")) {
            return "main-java";
        }
        if (p.startsWith("app/src/main/")) {
            return "main-resources";
        }
        if (p.startsWith("app/src/test/")) {
            return "test";
        }
        if (p.startsWith("it-selenium/")) {
            return "it";
        }
        if (name.equals("pom.xml") || p.startsWith("config/") || p.startsWith(".github/")
                || p.equals("version-rules.xml") || p.equals("nbactions.xml")) {
            return "build";
        }
        if (p.startsWith("deploy/") || p.startsWith("bin/") || p.equals("Dockerfile")
                || p.matches("docker-compose[^/]*\\.yml") || p.equals("roller")) { // rebrand:keep
            return "deploy";
        }
        if (name.endsWith(".md") || p.startsWith("docs/")) {
            return "docs";
        }
        return "other";
    }

    /** Old-name occurrences in the path string itself, keyed "PATH-" + kind. */
    static Map<String, Integer> pathCounts(Path repoRelative) {
        Map<String, Integer> out = new LinkedHashMap<>();
        count(repoRelative.toString().replace('\\', '/'))
                .forEach((kind, n) -> out.put("PATH-" + kind, n));
        return out;
    }

    private static boolean keepsOldName(Path p) {
        String s = p.toString().replace('\\', '/');
        if (s.equals("LICENSE.txt") || s.equals("NOTICE.txt") || s.startsWith("docs/superpowers/")
                || p.equals(THIS_FILE) || p.equals(PROJECT_NAMES)) {
            return true;
        }
        Matcher m = MIGRATION.matcher(s);
        return m.matches() && Integer.parseInt(m.group(1)) <= 40;
    }

    private static String textOrNull(Path p) {
        Path abs = RepoFiles.repoRoot().resolve(p);
        if (!Files.isRegularFile(abs)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(abs);
            for (int i = 0; i < Math.min(bytes.length, 8192); i++) {
                if (bytes[i] == 0) {
                    return null;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + p, e);
        }
    }

    @Test
    void countsOnlyTheBrand() {
        assertTrue(count("Controller controller scroller").isEmpty());
        assertEquals(Map.of("OTHER", 3), count("asyncRoller ROLLER_DB $rollerContentScope")); // rebrand:keep
    }

    @Test
    void classifiesEachKindOnce() {
        assertEquals(Map.of("PACKAGE", 2), count(ProjectNames.LEGACY_ROOT + ".x " + ProjectNames.LEGACY_ROOT.replace('.', '/') + "/y"));
        assertEquals(Map.of("CLASS", 1), count("RollerSession")); // rebrand:keep
        assertEquals(Map.of("TOKEN_PREFIX", 1), count("rlr_abc"));
    }

    @Test
    void pathsAreCountedUnderPathKinds() {
        Path p = Path.of("app/src/main/java/" + ProjectNames.LEGACY_ROOT.replace('.', '/')
                + "/weblogger/ui/core/RollerSession.java"); // rebrand:keep
        Map<String, Integer> expected = new TreeMap<>();
        expected.put("PATH-PACKAGE", 1);
        expected.put("PATH-CLASS", 1);
        assertEquals(expected, new TreeMap<>(pathCounts(p)));
        assertEquals("main-java", area(p));
    }

    @Test
    void scansOnlyTrackedFiles() throws IOException {
        Path probe = RepoFiles.repoRoot().resolve("app/target/old-name-probe.txt");
        Files.createDirectories(probe.getParent());
        Files.writeString(probe, "Roller"); // rebrand:keep
        try {
            assertFalse(RepoFiles.trackedFiles().contains(Path.of("app/target/old-name-probe.txt")));
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    @Test
    void areasCoverEveryPath() {
        String[][] cases = {
            {"app/src/main/java/a/B.java", "main-java"},
            {"app/src/main/webapp/x.jsp", "main-resources"},
            {"app/src/test/java/a/BTest.java", "test"},
            {"it-selenium/src/x.java", "it"},
            {"pom.xml", "build"},
            {"app/pom.xml", "build"},
            {"config/pmd/ruleset.xml", "build"},
            {".github/workflows/main.yml", "build"},
            {"version-rules.xml", "build"},
            {"nbactions.xml", "build"},
            {"deploy/provision.sh", "deploy"},
            {"bin/release.sh", "deploy"},
            {"Dockerfile", "deploy"},
            {"docker-compose.prod.yml", "deploy"},
            {"roller", "deploy"}, // rebrand:keep
            {"README.md", "docs"},
            {"docs/dev/x.txt", "docs"},
            {"something.txt", "other"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], area(Path.of(c[0])), c[0]);
        }
    }

    /** Totals keyed "KIND/area" over the tracked tree, and per-file contributions to each key. */
    private record Inventory(Map<String, Integer> totals, Map<String, Map<Path, Integer>> perFile) {
    }

    private static Inventory inventory() {
        Map<String, Integer> totals = new TreeMap<>();
        Map<String, Map<Path, Integer>> perFile = new TreeMap<>();
        for (Path p : RepoFiles.trackedFiles()) {
            if (keepsOldName(p)) {
                continue;
            }
            String area = area(p);
            List<Map.Entry<String, Integer>> found = new ArrayList<>(pathCounts(p).entrySet());
            String text = textOrNull(p);
            if (text != null) {
                found.addAll(count(text).entrySet());
            }
            for (Map.Entry<String, Integer> e : found) {
                String key = e.getKey() + "/" + area;
                totals.merge(key, e.getValue(), Integer::sum);
                perFile.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(p, e.getValue());
            }
        }
        return new Inventory(totals, perFile);
    }

    @Test
    void phaseOneLeftoversPicksOnlyLayerOneKeys() {
        assertEquals(Map.of("PACKAGE/main-java", 3), phaseOneLeftovers(Map.of(
                "PACKAGE/main-java", 3, "OTHER/main-java", 9, "CLASS/docs", 2, "PATH-CLASS/test", 0)));
    }

    /**
     * Once the package move has happened, Layer 1 (package and class names in
     * code, resources, tests, ITs and the build) must be empty. Before the
     * move the root is still the legacy one, so this passes trivially; it arms
     * itself when {@link ProjectNames#currentRoot()} changes.
     */
    @Test
    void layerOneIsEmptyOnceMoved() {
        if (ProjectNames.currentRoot().equals(ProjectNames.LEGACY_ROOT)) {
            return;
        }
        Inventory inv = inventory();
        Map<String, Integer> left = phaseOneLeftovers(inv.totals());
        StringBuilder sb = new StringBuilder("Layer 1 old names remain:");
        left.forEach((k, n) -> {
            sb.append("\n  ").append(k).append(": ").append(n);
            inv.perFile().get(k).entrySet().stream()
                    .sorted(Map.Entry.<Path, Integer>comparingByValue(Comparator.reverseOrder()))
                    .limit(5)
                    .forEach(f -> sb.append("\n      ").append(f.getKey()).append(" (").append(f.getValue()).append(')'));
        });
        assertTrue(left.isEmpty(), sb.toString());
    }

    @Test
    void oldNameCountsMatchTheBaseline() {
        Inventory inv = inventory();
        Map<String, Integer> totals = inv.totals();
        Map<String, Map<Path, Integer>> perFile = inv.perFile();
        List<String> problems = new ArrayList<>();
        boolean added = false;
        Map<String, Integer> keys = new TreeMap<>(totals);
        BASELINE.keySet().forEach(k -> keys.putIfAbsent(k, 0));
        for (Map.Entry<String, Integer> e : keys.entrySet()) {
            int actual = e.getValue();
            int base = BASELINE.getOrDefault(e.getKey(), 0);
            if (actual > base) {
                StringBuilder sb = new StringBuilder("old name added in " + e.getKey() + ": "
                        + actual + " > baseline " + base + "; top files:");
                perFile.get(e.getKey()).entrySet().stream()
                        .sorted(Map.Entry.<Path, Integer>comparingByValue(Comparator.reverseOrder()))
                        .limit(5)
                        .forEach(f -> sb.append("\n    ").append(f.getKey()).append(" (").append(f.getValue()).append(')'));
                problems.add(sb.toString());
                added = true;
            } else if (actual < base) {
                problems.add(e.getKey() + " fell to " + actual + "; lower BASELINE to " + actual);
            }
        }
        if (!problems.isEmpty()) {
            StringBuilder all = new StringBuilder(String.join("\n", problems));
            if (!added) {
                // Paste-ready only when every change is a decrease: an added old name is fixed, not baselined.
                all.append("\n\nCurrent counts:");
                totals.forEach((k, v) -> all.append("\n        \"").append(k).append("\", ").append(v).append(','));
            }
            throw new AssertionError(all.toString());
        }
    }
}
