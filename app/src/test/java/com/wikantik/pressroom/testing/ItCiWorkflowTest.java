/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package com.wikantik.pressroom.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the CI side of the browser-IT harness: how many browsers the nightly
 * is allowed to run at once, and whether a failing nightly leaves behind
 * enough evidence to diagnose itself.
 *
 * <p>Both were learned the hard way. {@code junit-platform.properties} sets
 * class-parallelism to 4, a value chosen for -- and documented against -- a
 * 16-core developer machine, where "each worker holds a Chrome, so the
 * binding constraint is memory". A GitHub-hosted runner is not that machine,
 * and it inherited the number silently. When the runner image moved from
 * ubuntu24/20260810.271 to 20260816.277 the nightly began failing every
 * night with Chrome renderer timeouts scattered across a different handful
 * of classes each run -- several reported with a NEGATIVE remaining
 * deadline, i.e. the command's timeout had already expired before the
 * browser was scheduled at all, which is starvation rather than a slow page.
 * The parallelism must therefore be overridable per environment, and CI must
 * actually take a lower value rather than inheriting the local default.
 *
 * <p>The second test exists because of what that outage cost. The app log
 * carrying the answer -- {@code it-work/app-<run id>.log} -- lives only on
 * the runner and dies with it, so six consecutive red nightlies produced no
 * evidence anyone could read afterwards. That is also why a separate,
 * genuinely code-shaped failure on the same job (a 403 on the admin
 * {@code createUser!save.rol} POST, CLAUDE.md's "MECHANISM NOT ESTABLISHED")
 * has stayed unexplained: nothing survives the run that could explain it.
 */
class ItCiWorkflowTest {

    private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
    private static final Path MAIN_WORKFLOW = REPO_ROOT.resolve(".github/workflows/main.yml");
    private static final Path IT_POM = REPO_ROOT.resolve("it-selenium/pom.xml");
    private static final Path PARENT_POM = REPO_ROOT.resolve("pom.xml");
    private static final Path APP_POM = REPO_ROOT.resolve("app/pom.xml");

    /**
     * An apt-get install line naming the webp package. Matched loosely on
     * purpose -- the assertion is "CI installs cwebp", not "CI spells its
     * install step exactly this way".
     */
    private static final Pattern WEBP_INSTALL =
            Pattern.compile("apt-get\\s+install[^\\n]*\\bwebp\\b");

    /**
     * CI must turn class-parallelism OFF, not merely turn it down.
     *
     * <p>Turning it down does nothing, and that is the whole finding here.
     * {@code fixed.parallelism} is a TARGET for JUnit's ForkJoinPool, not a
     * cap: the pool compensates for blocked tasks by spawning more threads,
     * and a Selenium test blocks on almost every line. Measured on this
     * repository, {@code -Dit.parallelism=1} still ran four IT classes
     * concurrently (~17s wall clock for four classes of ~10s each, instead
     * of ~40s), and a CI run with {@code -Dit.parallelism=2} started 24
     * classes within four seconds. The number has never bounded anything.
     *
     * <p>{@code parallel.enabled=false} is a different code path with no
     * pool involved, so it is the only bound that actually holds. It costs
     * the nightly wall clock (roughly 14 minutes of test time rather than
     * 8) against a 45-minute timeout, on a job that runs while everyone is
     * asleep.
     */
    @Test
    void theNightlyDoesNotRunBrowsersConcurrentlyAtAll() throws IOException {
        String itJob = integrationTestJob(Files.readString(MAIN_WORKFLOW));

        assertTrue(itJob.contains("-Dit.parallel.enabled=false"),
                "the nightly must pass -Dit.parallel.enabled=false. Lowering it.parallelism "
                        + "instead does NOT bound anything: JUnit's fixed strategy treats it as a "
                        + "target and compensates for blocked tasks by spawning more threads, so "
                        + "even parallelism=1 runs classes concurrently.");
    }

    /**
     * Both knobs must be handed to JUnit as system properties on the
     * failsafe-forked JVM: junit-platform.properties alone is not
     * overridable, and a -D on the Maven command line does not reach a fork
     * by itself.
     */
    @Test
    void theParallelSettingsReachTheForkedJvmAsOverridableProperties() throws IOException {
        String pom = Files.readString(IT_POM);

        assertTrue(pom.contains("<junit.jupiter.execution.parallel.enabled>"
                        + "${it.parallel.enabled}</junit.jupiter.execution.parallel.enabled>"),
                "failsafe must pass junit.jupiter.execution.parallel.enabled as "
                        + "${it.parallel.enabled}, or -Dit.parallel.enabled never reaches the fork "
                        + "and junit-platform.properties wins");
        assertTrue(pom.contains("<junit.jupiter.execution.parallel.config.fixed.parallelism>"
                        + "${it.parallelism}</junit.jupiter.execution.parallel.config.fixed.parallelism>"),
                "failsafe must also forward the parallelism, which still shapes the LOCAL run");
    }

    /**
     * The browser pool needs a CEILING, and {@code fixed.parallelism} is not one.
     *
     * <p>JUnit's FIXED strategy reads three parameters, not one. {@code fixed.parallelism}
     * is the ForkJoinPool's <em>target</em>; {@code fixed.max-pool-size} is how far the
     * pool may grow to compensate for threads that are blocked -- and it defaults to
     * <b>256</b>. A Selenium test blocks on nearly every line, so the pool grows almost
     * immediately, which is why this repository measured {@code -Dit.parallelism=2}
     * starting 24 classes inside four seconds and why a GitHub runner ended up launching
     * ~30 Chromes at once. Setting the parallelism alone has never bounded anything.
     *
     * <p>The ceiling matters more now than it did, not less: the browser is held for a
     * whole test CLASS rather than one test, so an uncapped pool means many
     * <em>long-lived</em> browsers rather than many short-lived ones.
     *
     * <p>Deliberately NOT setting {@code fixed.saturate}. It reads like the knob for this
     * and is inverted: its default {@code true} means "do not throw when the pool cannot
     * grow, just run with fewer threads", which is exactly what we want. Setting it
     * {@code false} makes ForkJoinPool throw {@code RejectedExecutionException} at a
     * blocked join instead.
     */
    @Test
    void theBrowserPoolIsCappedAndNotMerelyTargeted() throws IOException {
        String pom = Files.readString(IT_POM);
        assertTrue(pom.contains("<junit.jupiter.execution.parallel.config.fixed.max-pool-size>"
                        + "${it.parallelism}"
                        + "</junit.jupiter.execution.parallel.config.fixed.max-pool-size>"),
                "failsafe must forward fixed.max-pool-size as ${it.parallelism}: without it the "
                        + "pool grows to JUnit's default of 256 and -Dit.parallelism caps nothing");

        String properties = Files.readString(REPO_ROOT.resolve(
                "it-selenium/src/test/resources/junit-platform.properties"));
        assertTrue(properties.contains(
                        "junit.jupiter.execution.parallel.config.fixed.max-pool-size"),
                "junit-platform.properties must carry the cap too, so a run that does not go "
                        + "through failsafe (an IDE, a bare JUnit launcher) is bounded as well");
    }

    /** The local default stays parallel; only CI opts out. */
    @Test
    void aDeveloperMachineStillRunsTheSuiteInParallel() throws IOException {
        String pom = Files.readString(IT_POM);
        Matcher m = Pattern.compile(
                "<it\\.parallel\\.enabled>\\s*(true|false)\\s*</it\\.parallel\\.enabled>").matcher(pom);
        if (!m.find()) {
            fail("no <it.parallel.enabled> property in " + IT_POM);
        }
        assertTrue("true".equals(m.group(1)),
                "the local default must stay parallel; the 8-minute suite is why it exists");
        assertTrue(localDefaultParallelism() >= 1, "the local parallelism default must survive");
    }

    /**
     * A red nightly must leave the app log and the failsafe reports behind.
     * Without them the only record is the Selenium stack trace, which says
     * what the browser could not do and nothing about what the server did.
     */
    @Test
    void aFailingNightlyUploadsTheEvidenceNeededToDiagnoseIt() throws IOException {
        String workflow = Files.readString(MAIN_WORKFLOW);
        String itJob = integrationTestJob(workflow);

        assertTrue(itJob.contains("upload-artifact"),
                "the integration-test job must upload its diagnostics; the app log lives only "
                        + "on the runner and dies with it");
        assertTrue(itJob.contains("it-work"),
                "the uploaded diagnostics must include it-selenium's it-work directory, which "
                        + "holds app-<run id>.log -- the only record of what the SERVER did");
        assertTrue(itJob.contains("failsafe-reports"),
                "the uploaded diagnostics must include the failsafe reports");
        assertTrue(itJob.contains("if: always()") || itJob.contains("if: failure()"),
                "the upload must run on failure, which is the only run whose evidence matters");
    }

    /**
     * CI must install {@code cwebp}, because the three tests that cover the
     * WebP rendition path feature-detect it and SKIP when it is absent.
     *
     * <p>This is the silent-erosion shape the class already tests for
     * elsewhere, arriving through an assumption rather than a flag.
     * {@code CwebpEncoder} shells out to the {@code cwebp} binary and is
     * deliberately feature-detected, so a developer machine without it still
     * builds -- that part is by design. What was not by design is that
     * {@code Dockerfile} installs {@code webp} (Debian's name for the
     * package carrying {@code cwebp}) while no CI job did, so
     * {@code CwebpEncoderTest}, {@code MediaFileTest} and
     * {@code MediaResourceServletRenderingTest} skipped their WebP arms on
     * every single run and the build reported "Skipped: 3" to nobody. A
     * shipped production code path had no automated coverage that ever
     * executed anywhere.
     *
     * <p>An assumption that never holds is worse than a missing test: a
     * missing test is visible in a coverage report, while this one reports
     * as a passing class.
     */
    @Test
    void ciInstallsTheCwebpBinaryTheWebpTestsFeatureDetect() throws IOException {
        String buildJob = buildTestJob(Files.readString(MAIN_WORKFLOW));

        assertTrue(WEBP_INSTALL.matcher(buildJob).find(),
                "the build-test job must apt-get install webp before running the unit suite. "
                        + "Without it CwebpEncoder.isAvailable() is false on the runner and the "
                        + "three WebP tests skip silently while the job stays green -- so the "
                        + "rendition path the Dockerfile ships is covered nowhere that runs.");
    }

    // ------------------------------------------------------------------ parsing

    private static int localDefaultParallelism() throws IOException {
        String pom = Files.readString(IT_POM);
        Matcher m = Pattern.compile("<it\\.parallelism>\\s*(\\d+)\\s*</it\\.parallelism>").matcher(pom);
        if (!m.find()) {
            return fail("no <it.parallelism> property in " + IT_POM
                    + "; the parallelism must be a named property so CI can override it");
        }
        return Integer.parseInt(m.group(1));
    }

    /** The build-test job's block, so an assertion cannot pass on the IT job's text. */
    private static String buildTestJob(String workflow) {
        return jobBlock(workflow, "build-test");
    }

    /** The integration-test job's block, so an assertion cannot pass on some other job's text. */
    private static String integrationTestJob(String workflow) {
        return jobBlock(workflow, "integration-test");
    }

    private static String jobBlock(String workflow, String job) {
        int start = workflow.indexOf("\n  " + job + ":");
        if (start < 0) {
            return fail("no " + job + " job in " + MAIN_WORKFLOW);
        }
        Matcher next = Pattern.compile("\n  [a-zA-Z][a-zA-Z0-9_-]*:").matcher(workflow);
        int end = workflow.length();
        if (next.find(start + 1)) {
            end = next.start();
        }
        return workflow.substring(start, end);
    }

    /**
     * {@code skipUnitTests} exists so someone iterating on the browser suite
     * can skip the unit suite that runs ahead of it, and it must default to
     * false and stay unset in CI.
     *
     * <p>Pinned because of what it silently costs if it ever escapes. The
     * property's whole job is to stop a phase of the build from running, and
     * a build that skips a phase still says BUILD SUCCESS -- so a stray
     * {@code -DskipUnitTests=true} in a workflow, a profile or a committed
     * {@code .mvn/maven.config} would take the unit suite out of CI while
     * every run stayed green. That is the same shape as the gate erosions
     * this repository already tests for: {@code maxAllowedViolations} left
     * above zero, an exclusion added without a justification. A convenience
     * flag that turns a check off is worth exactly as much scrutiny as the
     * check.
     *
     * <p>Also asserts it is NOT spelled {@code skipTests}: that name is
     * shared with failsafe, so it would skip the browser suite too, which is
     * the opposite of what anyone reaching for this wants.
     */
    @Test
    void skippingTheUnitSuiteIsOptInAndNeverOnInCi() throws IOException {
        String parent = Files.readString(PARENT_POM);
        assertTrue(parent.contains("<skipUnitTests>${skipTests}</skipUnitTests>"),
                "skipUnitTests must default to ${skipTests}, not to a bare false. "
                        + "Naming skipTests in surefire's <configuration> makes the pom "
                        + "win over the command line, so a hardcoded false there silently "
                        + "breaks `mvn -DskipTests` for this module.");
        assertTrue(parent.contains("<skipTests>false</skipTests>"),
                "and skipTests itself must default to false, so a plain `mvn verify` "
                        + "always runs the unit suite.");

        String app = Files.readString(APP_POM);
        assertTrue(app.contains("<skipTests>${skipUnitTests}</skipTests>"),
                "app/pom.xml's surefire must read the opt-in property, or the flag is "
                        + "inert and someone will believe they skipped something.");

        for (Path file : List.of(MAIN_WORKFLOW, REPO_ROOT.resolve(".github/workflows"))) {
            if (!Files.exists(file)) {
                continue;
            }
            try (Stream<Path> tree = Files.walk(file)) {
                for (Path candidate : tree.filter(Files::isRegularFile).toList()) {
                    String text = Files.readString(candidate);
                    if (text.contains("skipUnitTests")) {
                        fail("CI must never skip the unit suite, but " + candidate.getFileName()
                                + " names skipUnitTests.");
                    }
                }
            }
        }
    }

    /**
     * The late-click mode holds every click back on purpose; a nightly run in it
     * would test the harness's reproduction, not the app.
     */
    @Test
    void ciNeverRunsInLateClickMode() throws IOException {
        assertTrue(setsLateClickMode("env:\n  IT_LATE_CLICK_MS: 150\n"),
                "the detector must see a workflow that sets the variable");
        assertFalse(setsLateClickMode("env:\n  CI: true\n"),
                "the detector must not flag a workflow that does not");
        assertFalse(setsLateClickMode(Files.readString(MAIN_WORKFLOW)),
                "CI must never set IT_LATE_CLICK_MS: that mode delays every click on purpose.");
    }

    private static boolean setsLateClickMode(String workflow) {
        return workflow.contains("IT_LATE_CLICK_MS");
    }
}
