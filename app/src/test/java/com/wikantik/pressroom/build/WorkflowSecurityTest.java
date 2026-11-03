package com.wikantik.pressroom.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Supply-chain invariants for the GitHub Actions workflows.
 *
 * <p>These files are the one part of this repository that <em>cannot</em> be
 * exercised locally: {@code release.yml} runs only on a {@code v*.*.*} tag
 * push, so its first execution after any edit is a real release that publishes
 * container images publicly. A static check is therefore worth more here than
 * it would be for ordinary code -- it is the only feedback available before
 * the consequences are irreversible.
 *
 * <p>What it pins, and why each matters:
 *
 * <ul>
 *   <li><b>Every action is pinned to a commit SHA</b>, first-party ones
 *       included, with its release tag in a trailing {@code # vX.Y.Z}
 *       comment. A version tag like {@code @v4} is mutable: whoever controls
 *       it can move it, and the next run executes whatever it points at then.
 *       Owning the runner does not make an owner's tags immutable, and the
 *       release job hands {@code docker/login-action} a token holding
 *       {@code packages: write} while {@code docker/} is not even GitHub. The
 *       exemption this test once granted {@code actions/}, {@code github/}
 *       and {@code docker/} is gone; the comment keeps a pin reviewable
 *       (Dependabot and Renovate both update SHA-plus-comment pins).</li>
 *   <li><b>Every workflow declares {@code permissions}.</b> Omitting it inherits
 *       the repository default, which is a setting no reviewer of the file can
 *       see and which an administrator can change without touching this repo.
 *       The default is read-only today; this keeps that true by assertion
 *       rather than by luck.</li>
 *   <li><b>The release is published by the GitHub CLI, not an action.</b> That
 *       step runs in the only job holding {@code contents: write} plus
 *       {@code packages: write}, with a live GHCR credential in scope. A SHA
 *       pin would freeze that risk; using {@code gh} removes the third party
 *       altogether. Reintroducing an action there should be a deliberate,
 *       visible act, not a convenience refactor.</li>
 * </ul>
 *
 * <p>Sibling of QualityGatePomTest, ProductionComposeTest, ItHarnessPomTest and
 * DependencySecurityFloorTest.
 */
class WorkflowSecurityTest {

    private static final Path WORKFLOWS =
            Path.of(System.getProperty("user.dir")).getParent().resolve(".github/workflows");

    /**
     * Action owners allowed in the release job, which holds
     * {@code contents: write} and {@code packages: write}. Every action,
     * these included, must still be pinned to a commit SHA. Adding an owner
     * hands that position to another party, so it is a security decision
     * rather than a convenience one.
     */
    private static final List<String> FIRST_PARTY_OWNERS = List.of("actions/", "github/", "docker/");

    /** {@code uses: owner/repo@ref}, ignoring comments and quoting noise. */
    private static final Pattern USES =
            Pattern.compile("^\\s*-?\\s*uses:\\s*[\"']?([^\"'@\\s]+)@([^\"'\\s]+)");

    private static final Pattern FORTY_HEX = Pattern.compile("[0-9a-f]{40}");

    /** The trailing comment naming the release a SHA pin stands for. */
    private static final Pattern VERSION_COMMENT = Pattern.compile("^\\s+#\\s*v\\d+(\\.\\d+)*\\s*$");

    private static List<Path> workflowFiles() throws IOException {
        assertTrue(Files.isDirectory(WORKFLOWS), "workflow dir moved? " + WORKFLOWS.toAbsolutePath());
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            return files.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    @DisplayName("every action, first-party included, is pinned to a commit SHA with its tag in a comment")
    void everyActionIsPinnedToACommitSha() throws IOException {
        List<String> unpinned = new ArrayList<>();
        for (Path workflow : workflowFiles()) {
            List<String> lines = Files.readAllLines(workflow);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = USES.matcher(lines.get(i));
                if (!m.find()) {
                    continue;
                }
                String action = m.group(1);
                String ref = m.group(2);
                // A local composite action (./.github/actions/...) is this
                // repository's own code and needs no pin.
                if (action.startsWith("./")) {
                    continue;
                }
                boolean pinned = FORTY_HEX.matcher(ref).matches()
                        && VERSION_COMMENT.matcher(lines.get(i).substring(m.end())).find();
                if (!pinned) {
                    unpinned.add(workflow.getFileName() + ":" + (i + 1) + "  " + action + "@" + ref);
                }
            }
        }
        assertTrue(unpinned.isEmpty(),
                "Action(s) not pinned to a commit SHA. Pin each to its 40-character commit SHA "
                + "with a trailing '# vX.Y.Z' comment naming the release, or drop the dependency:\n  "
                + String.join("\n  ", unpinned));
    }

    @Test
    @DisplayName("every workflow declares its token permissions explicitly")
    void everyWorkflowDeclaresPermissions() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path workflow : workflowFiles()) {
            String text = Files.readString(workflow);
            // A top-level (column-0) permissions: key. Job-level blocks are
            // indented and do not satisfy this -- the point is that the
            // workflow as a whole states its floor.
            if (!Pattern.compile("(?m)^permissions:\\s*$").matcher(text).find()) {
                missing.add(workflow.getFileName().toString());
            }
        }
        assertTrue(missing.isEmpty(),
                "Workflow(s) with no top-level permissions block, so they inherit the repository "
                + "default -- a setting invisible from this file and changeable outside this repo: "
                + String.join(", ", missing));
    }

    @Test
    @DisplayName("the release is published by the gh CLI, not a third-party action")
    void theReleaseStepUsesTheGitHubCli() throws IOException {
        Path releaseYml = WORKFLOWS.resolve("release.yml");
        assertTrue(Files.readString(releaseYml).contains("gh release create"),
                "release.yml must create the GitHub Release with the gh CLI");

        // Checked against `uses:` lines rather than the file text, so that the
        // comment explaining WHY the third-party action was removed is still
        // allowed to name it. (The first version of this test asserted on the
        // raw text and failed on its own explanatory comment -- a rule that
        // forbids describing the history is the wrong rule.)
        List<String> thirdParty = new ArrayList<>();
        List<String> lines = Files.readAllLines(releaseYml);
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = USES.matcher(lines.get(i));
            if (!m.find()) {
                continue;
            }
            String action = m.group(1);
            if (!action.startsWith("./")
                    && FIRST_PARTY_OWNERS.stream().noneMatch(action::startsWith)) {
                thirdParty.add("release.yml:" + (i + 1) + "  " + action);
            }
        }
        assertTrue(thirdParty.isEmpty(),
                "release.yml runs the only job holding contents:write + packages:write with a live "
                + "GHCR credential in scope. It must not hand that position to a third-party "
                + "action, pinned or otherwise -- prefer the gh CLI:\n  "
                + String.join("\n  ", thirdParty));
    }

    @Test
    @DisplayName("the publishing job declares its write permissions rather than inheriting them")
    void theReleaseJobDeclaresItsOwnPermissions() throws IOException {
        String release = Files.readString(WORKFLOWS.resolve("release.yml"));
        int job = release.indexOf("\n  release:");
        assertTrue(job > 0, "release.yml no longer has a 'release:' job -- update this test");
        // Look only at the job header, before its steps begin.
        int steps = release.indexOf("steps:", job);
        String header = release.substring(job, steps > 0 ? steps : release.length());
        assertTrue(header.contains("permissions:"),
                "the release job must declare its own permissions, so a step added later cannot "
                + "silently inherit contents:write / packages:write from the workflow");
    }
}
