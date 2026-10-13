package org.apache.roller.weblogger.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the static-analysis gate's wiring and its exclusion policy.
 *
 * <p>The gate is only worth having if silencing it is a visible act. These
 * assertions make an undocumented exclusion, or a check quietly demoted to
 * warn-only, fail the build. Sibling of ItHarnessPomTest and
 * ProductionComposeTest.
 */
class QualityGatePomTest {

    private static final Path REPO = Path.of(System.getProperty("user.dir")).getParent();

    private static String read(String relative) throws IOException {
        return Files.readString(REPO.resolve(relative));
    }

    /** The six PMD rules the spec permits excluding. A rule outside this set is a spec change. */
    private static final List<String> PERMITTED_PMD_EXCLUSIONS = List.of(
            "GuardLogStatement", "UncommentedEmptyConstructor",
            "AssignmentInOperand", "UncommentedEmptyMethodBody",
            "UnnecessaryConstructor", "AvoidUsingVolatile");

    /** The three SpotBugs families the spec permits excluding, by representative pattern. */
    private static final List<String> PERMITTED_SPOTBUGS_EXCLUSIONS = List.of(
            "EI_EXPOSE_REP", "SE_TRANSIENT_FIELD_NOT_RESTORED", "THROWS_METHOD_THROWS");

    /**
     * The exact bug patterns making up the three permitted SpotBugs exclusion
     * families in config/spotbugs/exclude.xml, transcribed from that file. This
     * set is closed in both directions: a pattern outside it, or a fourth
     * {@code <Match>} family, is a spec change -- update the design doc and this
     * list -- and a pattern listed here must still be in the filter. Every one
     * of them finds sites today (re-counted 2026-10-12); a pattern that finds
     * nothing is removed from both, because an exclusion that excludes nothing
     * only hides whatever that detector finds next.
     */
    private static final List<String> PERMITTED_SPOTBUGS_PATTERNS = List.of(
            "EI_EXPOSE_REP", "EI_EXPOSE_REP2", "MS_EXPOSE_REP",
            "SE_TRANSIENT_FIELD_NOT_RESTORED", "SE_COMPARATOR_SHOULD_BE_SERIALIZABLE", "CT_CONSTRUCTOR_THROW",
            "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION", "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
            "REC_CATCH_EXCEPTION", "MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR");

    @Test
    void allThreeChecksAreBoundToVerifyAndFailTheBuild() throws IOException {
        String appPom = read("app/pom.xml");
        assertTrue(appPom.contains("<id>pmd-check</id>"), "pmd check execution missing");
        assertTrue(appPom.contains("<id>cpd-check</id>"), "cpd check execution missing");
        assertTrue(appPom.contains("<id>spotbugs-check</id>"), "spotbugs check execution missing");

        String parentPom = read("pom.xml");
        assertTrue(parentPom.contains("<failOnViolation>true</failOnViolation>"),
                "PMD/CPD must fail the build, not warn");
        assertTrue(parentPom.contains("<failOnError>true</failOnError>"),
                "SpotBugs must fail the build, not warn");
    }

    /**
     * javac warnings fail the build, and the lints switched off are a closed
     * set. A fifth {@code -Xlint:-x} is a policy change: update this list and
     * the comment beside the compiler arguments in the parent pom.
     */
    @Test
    void javacWarningsFailTheBuildAndOnlyFourLintsAreOff() throws IOException {
        String parentPom = read("pom.xml");
        int plugin = parentPom.indexOf("<artifactId>maven-compiler-plugin</artifactId>");
        assertTrue(plugin >= 0, "maven-compiler-plugin missing from the parent pom");
        String config = parentPom.substring(plugin, parentPom.indexOf("</plugin>", plugin));
        assertTrue(config.contains("<failOnWarning>true</failOnWarning>"),
                "javac warnings must fail the build, not scroll past");
        assertTrue(config.contains("<arg>-Xlint:all</arg>"), "-Xlint:all must stay on");
        List<String> off = new ArrayList<>();
        Matcher m = Pattern.compile("<arg>-Xlint:-([a-z-]+)</arg>").matcher(config);
        while (m.find()) {
            off.add(m.group(1));
        }
        assertEquals(List.of("options", "processing", "path", "this-escape"), off,
                "the disabled javac lints are a closed set");
    }

    @Test
    void everyPmdExclusionIsPermittedAndCarriesAReason() throws IOException {
        String ruleset = read("config/pmd/ruleset.xml");
        Matcher m = Pattern.compile("<exclude\\s+name=\"([^\"]+)\"").matcher(ruleset);
        int found = 0;
        while (m.find()) {
            String rule = m.group(1);
            found++;
            assertTrue(PERMITTED_PMD_EXCLUSIONS.contains(rule),
                    "PMD rule '" + rule + "' is excluded but the spec does not permit it. "
                    + "Adding an exclusion is a spec change: update the design doc and this list.");
            assertTrue(hasPrecedingComment(ruleset, m.start()),
                    "PMD exclusion '" + rule + "' has no justification comment above it");
        }
        assertEquals(PERMITTED_PMD_EXCLUSIONS.size(), found,
                "config/pmd/ruleset.xml must exclude exactly the rules the spec lists");
    }

    @Test
    void everySpotbugsExclusionIsPermittedAndCarriesAReason() throws IOException {
        String filter = read("config/spotbugs/exclude.xml");
        for (String family : PERMITTED_SPOTBUGS_EXCLUSIONS) {
            assertTrue(filter.contains(family), "expected SpotBugs exclusion for " + family);
        }

        Matcher m = Pattern.compile("<Match>").matcher(filter);
        int matchCount = 0;
        while (m.find()) {
            matchCount++;
            assertTrue(hasPrecedingComment(filter, m.start()),
                    "every <Match> in the SpotBugs filter needs a justification comment above it");
        }
        // Closes the set the same way everyPmdExclusionIsPermittedAndCarriesAReason does for PMD:
        // a fourth <Match> family, even with a justification comment, is a spec change.
        assertEquals(3, matchCount,
                "config/spotbugs/exclude.xml must contain exactly the three <Match> families the "
                + "spec permits. Adding a fourth is a spec change: update the design doc and this test.");

        Matcher bugPattern = Pattern.compile("<Bug\\s+pattern=\"([^\"]+)\"").matcher(filter);
        List<String> excluded = new ArrayList<>();
        while (bugPattern.find()) {
            for (String pattern : bugPattern.group(1).split(",")) {
                String trimmed = pattern.trim();
                excluded.add(trimmed);
                assertTrue(PERMITTED_SPOTBUGS_PATTERNS.contains(trimmed),
                        "SpotBugs bug pattern '" + trimmed + "' is excluded but the spec does not "
                        + "permit it. Adding an exclusion is a spec change: update the design doc "
                        + "and this test.");
            }
        }
        for (String permitted : PERMITTED_SPOTBUGS_PATTERNS) {
            assertTrue(excluded.contains(permitted),
                    "'" + permitted + "' is permitted but no longer excluded: drop it from "
                    + "PERMITTED_SPOTBUGS_PATTERNS too, so the list stays a transcript of the filter "
                    + "and cannot quietly re-admit it later.");
        }
    }

    @Test
    void theTemporaryViolationCeilingIsGone() throws IOException {
        String parentPom = read("pom.xml");
        String appPom = read("app/pom.xml");
        assertTrue(!parentPom.contains("pmd.max.violations")
                        && !parentPom.contains("spotbugs.max.violations"),
                "the wave's temporary ceiling properties must be deleted once the tree is at zero");
        assertTrue(!parentPom.contains("maxAllowedViolations") && !appPom.contains("maxAllowedViolations"),
                "maxAllowedViolations was scaffolding; the gate is zero-tolerance now. Every PMD/SpotBugs "
                + "<configuration> block lives in the parent pom's pluginManagement, not app/pom.xml, so "
                + "the parent must be checked too or this assertion is a no-op.");
    }

    /**
     * The exclusion lists above are closed, but nothing stopped the gate's
     * SCOPE from shrinking instead: dropping the security category, swapping
     * quickstart for a narrower ruleset, or adding a ruleset-level
     * {@code <exclude-pattern>} (a real PMD 7 element the exclusion regex above
     * does not see, and which silences whole files rather than one rule) would
     * all pass every other test in this class. This pins the scope itself.
     */
    @Test
    void pmdRulesetScopeIsPinned() throws IOException {
        String ruleset = read("config/pmd/ruleset.xml");
        Matcher m = Pattern.compile("<rule\\s+ref=\"([^\"]+)\"").matcher(ruleset);
        List<String> refs = new ArrayList<>();
        while (m.find()) {
            refs.add(m.group(1));
        }
        assertEquals(List.of("rulesets/java/quickstart.xml", "category/java/security.xml",
                "category/java/errorprone.xml/SimpleDateFormatNeedsLocale",
                "category/java/errorprone.xml/UseProperClassLoader",
                "category/java/errorprone.xml/MoreThanOneLogger",
                "category/java/bestpractices.xml/UnusedAssignment",
                "category/java/bestpractices.xml/UseTryWithResources",
                "category/java/bestpractices.xml/SystemPrintln",
                "category/java/bestpractices.xml/ExhaustiveSwitchHasDefault",
                "category/java/bestpractices.xml/UnnecessaryVarargsArrayCreation",
                "category/java/bestpractices.xml/UnnecessaryWarningSuppression",
                "category/java/bestpractices.xml/AvoidPrintStackTrace",
                "category/java/bestpractices.xml/AvoidReassigningLoopVariables",
                "category/java/performance.xml/AddEmptyString",
                "category/java/performance.xml/UseStringBufferForStringAppends",
                "category/java/performance.xml/InefficientStringBuffering",
                "category/java/codestyle.xml/ModifierOrder",
                "category/java/codestyle.xml/UnnecessaryCast",
                "category/java/codestyle.xml/UnnecessaryBoxing",
                "category/java/codestyle.xml/UseDiamondOperator",
                "category/java/design.xml/CollapsibleIfStatements",
                "category/java/design.xml/MutableStaticState",
                "category/java/design.xml/AvoidThrowingNullPointerException"), refs,
                "config/pmd/ruleset.xml's <rule ref> set IS the gate's scope, not merely its exclusions. "
                + "Deleting the security category, or swapping quickstart.xml for a narrower ruleset, "
                + "silently shrinks the gate with no other test failing. Changing this set is a spec "
                + "change: update the design doc and this test.");

        assertTrue(!ruleset.contains("<exclude-pattern"),
                "a ruleset-level <exclude-pattern> silences an entire file with none of the per-rule "
                + "justification-comment scrutiny everyPmdExclusionIsPermittedAndCarriesAReason enforces "
                + "-- that regex only matches <exclude name=...>, not <exclude-pattern>. Adding one is a "
                + "spec change: update the design doc and this test.");
    }

    /**
     * The operator-facing report must DERIVE the CPD threshold, never restate
     * it.
     *
     * <p>{@code bin/quality-report.sh} printed "CPD @200" for eighteen days
     * after the gate moved to 110 — the count underneath it was always right
     * (the script runs {@code pmd:cpd} through Maven and inherits the pom's
     * setting), so only the label was wrong, which is exactly the kind of
     * thing nobody re-checks. A restated constant in a reporting tool is a
     * second source of truth that nothing compares against the first.
     */
    @Test
    void theQualityReportDerivesTheCpdThresholdRatherThanRestatingIt() throws IOException {
        String script = read("bin/quality-report.sh");

        assertTrue(script.contains("minimumTokens"),
                "bin/quality-report.sh must read the CPD threshold out of pom.xml, so its label "
                + "cannot drift away from the gate it is reporting on.");
        assertFalse(script.matches("(?s).*CPD @\\d+.*"),
                "bin/quality-report.sh must not hardcode a token count in its CPD heading; it "
                + "printed \"CPD @200\" against a gate of 110 for eighteen days. Interpolate the "
                + "value it read from the pom instead.");
    }

    /**
     * The exclusion lists close WHICH rules can be silenced; this closes HOW
     * SENSITIVE the surviving rules are allowed to be. Each of these can be
     * turned down with a one-line edit that no other test in this class
     * notices: CPD's token minimum, SpotBugs's threshold/effort, and PMD's
     * minimumPriority (present nowhere today, so its absence is the pin).
     */
    @Test
    void theGatesSensitivityIsPinned() throws IOException {
        String parentPom = read("pom.xml");
        assertTrue(parentPom.contains("<minimumTokens>110</minimumTokens>"),
                "CPD's duplication threshold is part of the gate's spec (Decision 6): raising it "
                + "silently drops duplicate blocks from the gate with no other test failing. Changing "
                + "it is a spec change: update the design doc and this test. Lowered from 200 to 110 "
                + "on 2026-08-22 after a triage pass -- 200 had missed two rules duplicated across a "
                + "routing/parsing boundary at roughly a hundred tokens each.");
        assertTrue(parentPom.contains("<threshold>Low</threshold>"),
                "SpotBugs threshold=Low is part of the gate's spec: raising it (e.g. to High) silently "
                + "drops findings with no other test failing. Changing it is a spec change: update the "
                + "design doc and this test.");
        assertTrue(parentPom.contains("<effort>Max</effort>"),
                "SpotBugs effort=Max is part of the gate's spec: lowering it (e.g. to Min) silently "
                + "drops findings with no other test failing. Changing it is a spec change: update the "
                + "design doc and this test.");
        assertTrue(!parentPom.contains("<minimumPriority>"),
                "maven-pmd-plugin's minimumPriority would silently narrow which PMD violations fail the "
                + "build -- on top of, and invisible next to, the exclude list this class already pins. "
                + "Adding one is a spec change: update the design doc and this test.");
    }

    /** True if the text between the previous '>' and this offset contains an XML comment. */
    private static boolean hasPrecedingComment(String xml, int offset) {
        String before = xml.substring(0, offset);
        int lastComment = before.lastIndexOf("-->");
        int lastElement = before.lastIndexOf('>', before.length() - 1);
        // The comment must be the nearest preceding markup.
        return lastComment >= 0 && lastComment >= lastElement - 2;
    }
}
