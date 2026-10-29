package org.apache.roller.weblogger.build;

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
 * <p>Excluded forever (they legitimately keep the old name): LICENSE.txt,
 * NOTICE.txt, migrations V001 to V040, docs/superpowers/**, this file and
 * {@link ProjectNames}. Binary files (a NUL in the first 8 KB) are skipped.
 */
class OldNameInventoryTest {

    private static final Pattern PACKAGE = Pattern.compile("org\\.apache\\.roller|org/apache/roller");
    private static final Pattern CLASS = Pattern.compile("\\bRoller[A-Z]\\w*");
    private static final Pattern TOKEN_PREFIX = Pattern.compile("rlr_");
    private static final Pattern OTHER = Pattern.compile("Roller|ROLLER|(?<![A-Za-z])roller");

    private static final Pattern MIGRATION = Pattern.compile("bin/db/migrations/V(\\d+)__.*");

    private static final Path THIS_FILE =
            Path.of("app/src/test/java/org/apache/roller/weblogger/build/OldNameInventoryTest.java");
    private static final Path PROJECT_NAMES =
            Path.of("app/src/test/java/org/apache/roller/weblogger/build/ProjectNames.java");

    /** Keyed "KIND/area". Later phases lower these; the end state is empty. */
    static final Map<String, Integer> BASELINE = Map.ofEntries(
            Map.entry("CLASS/build", 3),
            Map.entry("CLASS/deploy", 1),
            Map.entry("CLASS/docs", 55),
            Map.entry("CLASS/it", 166),
            Map.entry("CLASS/main-java", 438),
            Map.entry("CLASS/main-resources", 32),
            Map.entry("CLASS/other", 2),
            Map.entry("CLASS/test", 830),
            Map.entry("OTHER/build", 40),
            Map.entry("OTHER/deploy", 298),
            Map.entry("OTHER/docs", 674),
            Map.entry("OTHER/it", 450),
            Map.entry("OTHER/main-java", 780),
            Map.entry("OTHER/main-resources", 1079),
            Map.entry("OTHER/other", 48),
            Map.entry("OTHER/test", 1903),
            Map.entry("PACKAGE/build", 18),
            Map.entry("PACKAGE/deploy", 1),
            Map.entry("PACKAGE/docs", 125),
            Map.entry("PACKAGE/it", 166),
            Map.entry("PACKAGE/main-java", 2777),
            Map.entry("PACKAGE/main-resources", 162),
            Map.entry("PACKAGE/test", 3746),
            Map.entry("TOKEN_PREFIX/docs", 3),
            Map.entry("TOKEN_PREFIX/it", 2),
            Map.entry("TOKEN_PREFIX/main-java", 2),
            Map.entry("TOKEN_PREFIX/test", 42));

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
                || p.matches("docker-compose[^/]*\\.yml") || p.equals("roller")) {
            return "deploy";
        }
        if (name.endsWith(".md") || p.startsWith("docs/")) {
            return "docs";
        }
        return "other";
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
        assertEquals(Map.of("OTHER", 3), count("asyncRoller ROLLER_DB $rollerContentScope"));
    }

    @Test
    void classifiesEachKindOnce() {
        assertEquals(Map.of("PACKAGE", 2), count("org.apache.roller.x org/apache/roller/y"));
        assertEquals(Map.of("CLASS", 1), count("RollerSession"));
        assertEquals(Map.of("TOKEN_PREFIX", 1), count("rlr_abc"));
    }

    @Test
    void scansOnlyTrackedFiles() throws IOException {
        Path probe = RepoFiles.repoRoot().resolve("app/target/old-name-probe.txt");
        Files.createDirectories(probe.getParent());
        Files.writeString(probe, "Roller");
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
            {"roller", "deploy"},
            {"README.md", "docs"},
            {"docs/dev/x.txt", "docs"},
            {"something.txt", "other"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], area(Path.of(c[0])), c[0]);
        }
    }

    @Test
    void oldNameCountsMatchTheBaseline() {
        Map<String, Integer> totals = new TreeMap<>();
        Map<String, Map<Path, Integer>> perFile = new TreeMap<>();
        for (Path p : RepoFiles.trackedFiles()) {
            if (keepsOldName(p)) {
                continue;
            }
            String text = textOrNull(p);
            if (text == null) {
                continue;
            }
            String area = area(p);
            for (Map.Entry<String, Integer> e : count(text).entrySet()) {
                String key = e.getKey() + "/" + area;
                totals.merge(key, e.getValue(), Integer::sum);
                perFile.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(p, e.getValue());
            }
        }
        List<String> problems = new ArrayList<>();
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
            } else if (actual < base) {
                problems.add(e.getKey() + " fell to " + actual + "; lower BASELINE to " + actual);
            }
        }
        if (!problems.isEmpty()) {
            StringBuilder all = new StringBuilder(String.join("\n", problems));
            all.append("\n\nCurrent counts:");
            totals.forEach((k, v) -> all.append("\n        \"").append(k).append("\", ").append(v).append(','));
            throw new AssertionError(all.toString());
        }
    }
}
