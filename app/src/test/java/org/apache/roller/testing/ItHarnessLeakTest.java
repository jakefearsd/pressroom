/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.testing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The browser IT harness must never leave a Roller app JVM running, whatever
 * killed the build. Four abort paths used to leak one permanently: the
 * readiness timeout in {@code start-app.sh} (which exited without killing what
 * it had started), an infrastructure failure anywhere in
 * {@code pre-integration-test} (which skips {@code post-integration-test},
 * where all cleanup lived), a Ctrl-C, and a single fixed pidfile that every
 * run truncated -- so run N+1 destroyed the only record of run N's leaked pid.
 *
 * <p>The replacement is identity-based rather than pidfile-based: every process
 * the harness starts carries {@code -Droller.it.run=<run id>} and
 * {@code -Droller.it.owner=<pid>@<start time>} on its command line, so it can
 * always be found without a file, and "is this process stale?" is answered by
 * asking whether the Maven build that owns it is still alive. These tests pin
 * the three mechanisms that follow from that: a {@code start-app.sh} that kills
 * what it started on every failure path, a {@code stop-app.sh} that works with
 * no pidfile at all, a pre-run sweep that reaps other runs' corpses but leaves
 * a concurrent run alone, and a supervisor that reaps when the build it belongs
 * to dies without reaching any cleanup phase.
 *
 * <p>Linux only: the assertions drive {@code ps}/{@code kill} and the harness
 * is only ever run on Linux (CI) or a developer's Linux box.
 */
@EnabledOnOs(OS.LINUX)
class ItHarnessLeakTest {

    /**
     * Fixture loops live in a random namespace no sweep looks in, so they end with
     * this JVM: if surefire is SIGKILLed, @AfterEach never runs and they would
     * otherwise sleep forever.
     */
    private static final long JVM_PID = ProcessHandle.current().pid();

    private static final Path SCRIPTS = Paths.get("../it-selenium/src/test/script");
    private static final Path LIB = SCRIPTS.resolve("it-harness-lib.sh");
    private static final Path START_APP = SCRIPTS.resolve("start-app.sh");
    private static final Path STOP_APP = SCRIPTS.resolve("stop-app.sh");
    private static final Path SWEEP = SCRIPTS.resolve("sweep-stale.sh");
    private static final Path SUPERVISE = SCRIPTS.resolve("supervise-run.sh");

    /** Generous: these poll real process state, and a loaded CI box is slow. */
    private static final Duration REAP_BUDGET = Duration.ofSeconds(30);

    @TempDir
    private Path work;

    /**
     * This test's own marker namespace (IT_NAMESPACE, see it-harness-lib.sh), handed to
     * every script it runs.
     *
     * <p>Without it these tests shared the real harness's markers with every other run on
     * the machine, and a sweep is machine-wide by design: another build's sweep -- a
     * concurrent {@code mvn verify -Pit}, or this class in another checkout's unit suite --
     * reaped the deliberately stale fixtures here before this class's own sweep could
     * report them, and this class's sweeps reaped and printed whatever other builds had
     * left. The verdict depended on what else the machine was running. Per test, not per
     * class, so no two tests can see each other's fixtures either.
     */
    private final String namespace = newNamespace();

    /** Marker property names as the harness library spells them, per namespace. */
    private final Map<String, String> libProperties = new HashMap<>();

    private final List<Process> spawned = new ArrayList<>();
    private final Map<Long, Path> output = new HashMap<>();

    @AfterEach
    void killEverythingThisTestStarted() {
        spawned.forEach(Process::destroyForcibly);
    }

    // ---------------------------------------------------------------- start

    /**
     * Defect 1: the readiness loop expired, printed the log tail and exited 1,
     * leaving the JVM it had just started running forever -- and because this
     * runs in pre-integration-test, that exit 1 aborts the build before
     * post-integration-test, so the app-stop that would have killed it never
     * runs.
     */
    @Test
    void startAppKillsTheJvmItStartedWhenReadinessTimesOut() throws Exception {
        String runId = runId("timeout");
        Result result = runStartApp(runId, "4");

        assertEquals(1, result.exit(), "readiness timeout must still fail the build:\n" + result.output());
        assertNoProcessFor(runId, "start-app.sh timed out and left its JVM running");
    }

    /** Ctrl-C reaches start-app.sh while it is still polling for readiness. */
    @Test
    void startAppKillsTheJvmItStartedWhenItIsSignalled() throws Exception {
        String runId = runId("signal");
        Process script = startStartApp(runId, "600");

        awaitProcessFor(runId, "start-app.sh never launched its child");
        script.destroy();
        assertTrue(script.waitFor(30, TimeUnit.SECONDS), "start-app.sh ignored SIGTERM");

        assertNoProcessFor(runId, "start-app.sh was signalled and left its JVM running");
    }

    // ----------------------------------------------------------------- stop

    /**
     * Defect 2: cleanup that can only work from a pidfile cannot work at all
     * once the pidfile is missing -- which is the state a leaked run leaves
     * behind, because the next run truncates it.
     */
    @Test
    void stopAppKillsByRunMarkerWhenThePidfileIsMissing() throws Exception {
        String runId = runId("nopidfile");
        long pid = spawnMarked(runId, ownerTokenOfLiveProcess());

        Path absent = work.resolve("app-" + runId + ".pid");
        Result result = run(List.of(STOP_APP.toString(), absent.toString(), runId), Map.of(), Duration.ofMinutes(1));

        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains(String.valueOf(pid)),
                "stop-app.sh must report the pid it reaped, got:\n" + result.output());
        assertNoProcessFor(runId, "stop-app.sh could not stop a run whose pidfile was gone");
    }

    // ---------------------------------------------------------------- sweep

    /**
     * The bound on accumulation: whatever an earlier run leaked, the next run
     * finds it by marker, reaps it, and says so. Silence is the actual bug --
     * build-helper reserves a free port every run, so leaked servers never
     * collide and nothing ever complained.
     */
    @Test
    void sweepReapsAnAppWhoseOwningBuildIsGoneAndSaysSo() throws Exception {
        String staleRun = runId("stale");
        long pid = spawnMarked(staleRun, ownerTokenOfDeadProcess());

        Result result = runSweep(runId("current"));

        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains(String.valueOf(pid)) && result.output().contains(staleRun),
                "the sweep must name what it reaped (pid " + pid + ", run " + staleRun + "), got:\n"
                        + result.output());
        assertNoProcessFor(staleRun, "the sweep left a stale app running");
    }

    /**
     * The other half of the same rule, and the reason staleness is decided by
     * the owning build's liveness rather than by "an IT app exists": a second
     * run on the same machine is legitimate and must survive the first one's
     * sweep. Random reserved ports exist precisely so concurrent runs work.
     */
    @Test
    void sweepLeavesAConcurrentRunAlone() throws Exception {
        Process owner = spawn(List.of("sleep", "300"));
        String liveRun = runId("live");
        long pid = spawnMarked(liveRun, ownerToken(owner.pid()));

        Result result = runSweep(runId("current"));

        assertEquals(0, result.exit(), result.output());
        assertTrue(isAlive(pid), "the sweep killed a concurrent run's app:\n" + result.output());
        assertFalse(result.output().contains(String.valueOf(pid)),
                "the sweep reported a live run as reaped:\n" + result.output());
    }

    /**
     * Isolation between namespaces, which is what lets this class run beside a real
     * {@code mvn verify -Pit}, or beside itself in another checkout, and get the same
     * verdict as alone: a stale app in another namespace is invisible to this sweep --
     * not reaped, not even mentioned -- while that namespace's own sweep still reaps it.
     * Real runs all share the default namespace, so a real sweep still finds every real
     * leftover on the machine.
     */
    @Test
    void aSweepNeverTouchesAStaleAppInAnotherNamespace() throws Exception {
        String foreignNamespace = newNamespace();
        String staleRun = runId("foreign");
        long pid = spawnMarked(foreignNamespace, staleRun, ownerTokenOfDeadProcess());

        Result ours = runSweep(runId("current"));

        assertEquals(0, ours.exit(), ours.output());
        assertTrue(isAlive(pid), "a sweep reaped a stale app from another namespace:\n" + ours.output());
        assertFalse(ours.output().contains(staleRun),
                "a sweep must not even mention another namespace's processes:\n" + ours.output());

        Map<String, String> env = new HashMap<>();
        env.put("IT_NAMESPACE", foreignNamespace);
        env.put("IT_RECORD_DIR", recordDir().toString());
        Result theirs = run(List.of(SWEEP.toString(), runId("foreign-current")), env, Duration.ofMinutes(1));

        assertTrue(theirs.output().contains(staleRun),
                "the namespace's own sweep must still reap its stale app:\n" + theirs.output());
        assertNoProcessMarked(foreignNamespace, "IT_RUN_PROP", staleRun,
                "the namespace's own sweep left its stale app running");
    }

    // ----------------------------------------------------------- supervisor

    /**
     * Defect 3: cleanup cannot live only in post-integration-test, because an
     * infrastructure failure earlier in pre-integration-test (pg-wait-ready,
     * migrate.sh, the seed) aborts the build before that phase is ever
     * reached, and Ctrl-C skips it too. The supervisor is started before any
     * of that and outlives the build, so cleanup no longer depends on reaching
     * a later phase.
     */
    @Test
    void supervisorReapsTheRunWhenTheOwningBuildDies() throws Exception {
        Process owner = spawn(List.of("sleep", "300"));
        String runId = runId("supervised");
        String ownerToken = ownerToken(owner.pid());
        long app = spawnMarked(runId, ownerToken);

        Map<String, String> env = new HashMap<>();
        env.put("IT_OWNER_STAMP", ownerToken);
        env.put("IT_RECORD_DIR", recordDir().toString());
        Instant before = Instant.now();
        Result result = run(List.of(SUPERVISE.toString(), runId, "roller-it-postgres-" + runId, work.toString()),
                env, Duration.ofSeconds(30));

        assertEquals(0, result.exit(), result.output());
        assertTrue(Duration.between(before, Instant.now()).toSeconds() < 15,
                "supervise-run.sh must detach and return immediately, not block the build");
        assertTrue(isAlive(app), "the supervisor reaped a run whose build is still alive");

        owner.destroyForcibly();
        assertTrue(owner.waitFor(30, TimeUnit.SECONDS), "the fake build did not die");

        assertNoProcessFor(runId, "the supervisor did not reap the run after its build died");
        assertNoSupervisorFor(runId, "the supervisor outlived the run it was cleaning up");
    }

    /**
     * A supervisor marked as a member of its own run finds its own transient
     * subshells while enumerating what to kill -- a forked shell shows its
     * parent's command line in {@code ps} -- and kills those instead of, and
     * potentially as well as, the app. So the run marker and the supervisor
     * marker are two different properties, and a supervisor with nothing to
     * clean up must report exactly that.
     */
    @Test
    void supervisorNeverMistakesItsOwnSubshellsForTheRun() throws Exception {
        Process owner = spawn(List.of("sleep", "300"));
        String runId = runId("selfkill");
        String ownerToken = ownerToken(owner.pid());

        Map<String, String> env = new HashMap<>();
        env.put("IT_OWNER_STAMP", ownerToken);
        env.put("IT_POLL_SECONDS", "1");
        env.put("IT_RECORD_DIR", recordDir().toString());
        Result start = run(List.of(SUPERVISE.toString(), runId, "roller-it-postgres-" + runId, work.toString()),
                env, Duration.ofSeconds(30));
        assertEquals(0, start.exit(), start.output());

        owner.destroyForcibly();
        assertTrue(owner.waitFor(30, TimeUnit.SECONDS), "the fake build did not die");

        String log = awaitFileContaining(work.resolve("supervisor-" + runId + ".log"), "cleaned up");
        assertFalse(log.contains("killing pid"),
                "the supervisor had nothing to reap but killed something anyway:\n" + log);
    }

    // ------------------------------------------------------------ containers

    /**
     * A run owns two containers since the SSO tests brought Keycloak in, and
     * the sweep must treat the second exactly like the first: an aborted run's
     * Keycloak goes, a concurrent live run's stays. A Keycloak container under
     * a name the sweep did not look for would leak on every aborted run; one
     * whose run id it misread would be removed from under a live run.
     */
    @Test
    void sweepRemovesEveryContainerOfAStaleRunAndNoneOfALiveOne() throws Exception {
        Process owner = spawn(List.of("sleep", "300"));
        String liveRun = runId("live");
        spawnMarked(liveRun, ownerToken(owner.pid()));
        String staleRun = runId("stale");
        Path docker = fakeDocker(postgresContainer(staleRun), keycloakContainer(staleRun),
                postgresContainer(liveRun), keycloakContainer(liveRun));

        Result result = runSweepAgainst(docker, runId("current"));

        assertEquals(0, result.exit(), result.output());
        List<String> removed = removedContainers(docker);
        assertTrue(removed.contains(keycloakContainer(staleRun)),
                "the sweep left a stale run's Keycloak container: " + removed + "\n" + result.output());
        assertTrue(removed.contains(postgresContainer(staleRun)),
                "the sweep left a stale run's postgres container: " + removed + "\n" + result.output());
        assertFalse(removed.contains(keycloakContainer(liveRun)),
                "the sweep removed a LIVE run's Keycloak container: " + removed + "\n" + result.output());
        assertFalse(removed.contains(postgresContainer(liveRun)),
                "the sweep removed a LIVE run's postgres container: " + removed + "\n" + result.output());
        assertTrue(result.output().contains(keycloakContainer(staleRun)),
                "the sweep must name what it removed:\n" + result.output());
    }

    /**
     * The supervisor removes every container it is given once the build dies,
     * not only the first: a build killed between pg-start and pg-stop would
     * otherwise leave its Keycloak running, and nothing but the next sweep
     * would ever notice.
     */
    @Test
    void supervisorRemovesEveryContainerItWasGivenWhenTheBuildDies() throws Exception {
        Process owner = spawn(List.of("sleep", "300"));
        String runId = runId("containers");
        Path docker = fakeDocker(postgresContainer(runId), keycloakContainer(runId));

        Map<String, String> env = new HashMap<>(fakeDockerEnvironment(docker));
        env.put("IT_OWNER_STAMP", ownerToken(owner.pid()));
        env.put("IT_POLL_SECONDS", "1");
        env.put("IT_RECORD_DIR", recordDir().toString());
        Result start = run(List.of(SUPERVISE.toString(), runId, postgresContainer(runId), work.toString(),
                keycloakContainer(runId)), env, Duration.ofSeconds(30));
        assertEquals(0, start.exit(), start.output());
        assertTrue(removedContainers(docker).isEmpty(),
                "the supervisor removed a container while its build was still alive");

        owner.destroyForcibly();
        assertTrue(owner.waitFor(30, TimeUnit.SECONDS), "the fake build did not die");

        String log = awaitFileContaining(work.resolve("supervisor-" + runId + ".log"), "cleaned up");
        List<String> removed = removedContainers(docker);
        assertTrue(removed.contains(postgresContainer(runId)), "postgres container left behind:\n" + log);
        assertTrue(removed.contains(keycloakContainer(runId)), "Keycloak container left behind:\n" + log);
    }

    // ------------------------------------------------------ chromedriver records

    /**
     * Defect 5, half 1: the chromedriver attribution record used to live
     * under target/ ({@code it.work.dir}), so a routine {@code mvn clean}
     * destroyed it out from under a still-running driver -- and once the
     * record naming a pid is gone, that pid is unattributable FOREVER, by
     * design (the sweep correctly refuses to kill what it cannot attribute).
     * This is precisely how 81 processes under 11 chromedrivers (8.1GB) went
     * permanently unreapable on one developer machine.
     */
    @Test
    void chromedriverRecordSurvivesTargetDirectoryBeingDeleted() throws Exception {
        Path fakeTarget = Files.createDirectories(work.resolve("fake-target")); // stands in for target/
        Path cache = Files.createDirectories(work.resolve("fake-cache")); // stands in for ~/.cache/roller-it
        Process owner = spawnOwnerRunningFakeChromedriver();
        String runId = runId("clean");
        String ownerToken = ownerToken(owner.pid());
        long driverPid = childPidOf(owner.pid());

        Map<String, String> env = new HashMap<>();
        env.put("IT_OWNER_STAMP", ownerToken);
        env.put("IT_POLL_SECONDS", "1");
        env.put("IT_RECORD_DIR", cache.toString());
        Result start = run(List.of(SUPERVISE.toString(), runId, "roller-it-postgres-" + runId, fakeTarget.toString()),
                env, Duration.ofSeconds(30));
        assertEquals(0, start.exit(), start.output());

        Path record = awaitRecordNaming(cache, runId, driverPid);

        deleteRecursively(fakeTarget); // simulate `mvn clean`

        assertTrue(Files.exists(record),
                "the chromedriver record must not live under target/, or `mvn clean` destroys it");
        assertTrue(recordPidLines(record).contains(String.valueOf(driverPid)),
                "the record must still be usable (readable, still naming the driver) once target/ is gone:\n"
                        + recordLines(record));

        owner.destroyForcibly();
        killTree(driverPid);
    }

    /**
     * Defect 5, half 2: sweep-stale.sh used to key its chromedriver reaping
     * off STALE_RUNS, which is built entirely from LIVE marked processes. A
     * run whose app AND supervisor have both already exited contributes
     * nothing to that list, so its record was never even opened -- precisely
     * the abandoned-run shape that produced the real leak. This drives the
     * sweep against a record with no live process behind it at all.
     */
    @Test
    void sweepReapsAChromedriverRecordWhoseOwningBuildIsCompletelyGone() throws Exception {
        Path records = recordDir();
        String runId = runId("record-dead");
        String deadOwner = ownerTokenOfDeadProcess();
        Process driver = spawnFakeChromedriver();
        long child = childPidOf(driver.pid());

        writeChromedriverRecord(records, runId, deadOwner, driver.pid());

        Result result = runSweep(runId("current"), records);

        assertEquals(0, result.exit(), result.output());
        assertNoProcessAlive(driver.pid(),
                "the sweep left a chromedriver running whose record's owner is fully gone:\n" + result.output());
        assertNoProcessAlive(child, "the sweep reaped the chromedriver but left its child running:\n" + result.output());
        assertFalse(Files.exists(records.resolve(runId + ".chromedrivers")),
                "the sweep must delete a record once it has reaped it");
    }

    /**
     * THE concurrent-run safety property, and non-negotiable: a second,
     * legitimate {@code mvn verify -Pit} must survive this sweep untouched.
     * Staleness is decided by the record's own owner header, never by
     * whether some other process happens to exist -- so a record whose owner
     * is alive must be left completely alone, pid, file, and all.
     */
    @Test
    void sweepLeavesAChromedriverRecordWhoseOwnerIsAliveCompletelyUntouched() throws Exception {
        Path records = recordDir();
        Process owner = spawn(List.of("sleep", "300"));
        String runId = runId("record-live");
        String ownerToken = ownerToken(owner.pid());
        Process driver = spawnFakeChromedriver();

        writeChromedriverRecord(records, runId, ownerToken, driver.pid());
        Path record = records.resolve(runId + ".chromedrivers");
        List<String> before = recordLines(record);

        Result result = runSweep(runId("current"), records);

        assertEquals(0, result.exit(), result.output());
        assertTrue(isAlive(driver.pid()),
                "the sweep killed a chromedriver whose owning build is still alive:\n" + result.output());
        assertTrue(Files.exists(record), "the sweep deleted a record whose owning build is still alive");
        assertEquals(before, recordLines(record), "a live owner's record must be left byte-for-byte untouched");
        assertFalse(result.output().contains(String.valueOf(driver.pid())),
                "the sweep must not even mention a live run's chromedriver:\n" + result.output());

        owner.destroyForcibly();
        driver.destroyForcibly();
    }

    /** Pids recycle; a record written once can outlive the pid it names. */
    @Test
    void sweepDoesNotKillARecordedPidThatIsNoLongerAChromedriver() throws Exception {
        Path records = recordDir();
        String runId = runId("recycled");
        String deadOwner = ownerTokenOfDeadProcess();
        Process notADriver = spawn(List.of("sleep", "300")); // comm "sleep", never recorded as a chromedriver

        writeChromedriverRecord(records, runId, deadOwner, notADriver.pid());

        Result result = runSweep(runId("current"), records);

        assertEquals(0, result.exit(), result.output());
        assertTrue(isAlive(notADriver.pid()),
                "the sweep killed a recorded pid that is no longer a chromedriver:\n" + result.output());

        notADriver.destroyForcibly();
    }

    /**
     * The other half of defect 4: a bare SIGKILL to chromedriver does not
     * take its Chrome children with it -- that gap is where the 8.1GB
     * actually sat, under drivers that were themselves long dead.
     */
    @Test
    void sweepReapsDescendantsOfARecordedChromedriverToo() throws Exception {
        Path records = recordDir();
        String runId = runId("descendants");
        String deadOwner = ownerTokenOfDeadProcess();
        Process driver = spawnFakeChromedriver();
        long child = childPidOf(driver.pid());

        writeChromedriverRecord(records, runId, deadOwner, driver.pid());

        Result result = runSweep(runId("current"), records);

        assertEquals(0, result.exit(), result.output());
        assertNoProcessAlive(driver.pid(), "the sweep left the chromedriver running:\n" + result.output());
        assertNoProcessAlive(child,
                "the sweep killed the chromedriver but left its child running -- SIGKILL to chromedriver "
                        + "does not take Chrome children with it, which is where the 8.1GB actually sat");
    }

    /**
     * Must-not-regress: on a successful run the supervisor itself still
     * reaps its own recorded drivers (and now their descendants) when the
     * build it watches exits, exactly as before this whole fix.
     */
    @Test
    void supervisorReapsItsRecordedChromedriversAndTheirDescendantsWhenTheBuildDies() throws Exception {
        Path cache = recordDir();
        Process owner = spawnOwnerRunningFakeChromedriver();
        String runId = runId("supervised-driver");
        String ownerToken = ownerToken(owner.pid());
        long driverPid = childPidOf(owner.pid());
        long child = childPidOf(driverPid);

        Map<String, String> env = new HashMap<>();
        env.put("IT_OWNER_STAMP", ownerToken);
        env.put("IT_POLL_SECONDS", "1");
        env.put("IT_RECORD_DIR", cache.toString());
        Result start = run(List.of(SUPERVISE.toString(), runId, "roller-it-postgres-" + runId, work.toString()),
                env, Duration.ofSeconds(30));
        assertEquals(0, start.exit(), start.output());

        awaitRecordNaming(cache, runId, driverPid);

        owner.destroyForcibly();
        assertTrue(owner.waitFor(30, TimeUnit.SECONDS), "the fake build did not die");

        assertNoProcessAlive(driverPid, "the supervisor did not reap its own recorded chromedriver");
        assertNoProcessAlive(child, "the supervisor reaped the chromedriver but not its child");
        assertRecordDeleted(cache.resolve(runId + ".chromedrivers"),
                "the supervisor must delete its own record once it has cleaned up");
    }

    // --------------------------------------------------------------- driving

    private Result runStartApp(String runId, String timeoutSeconds) throws Exception {
        Process script = startStartApp(runId, timeoutSeconds);
        return await(script, Duration.ofMinutes(2));
    }

    private Process startStartApp(String runId, String timeoutSeconds) throws Exception {
        Path war = Files.createFile(work.resolve(runId + ".war"));
        Path props = Files.createFile(work.resolve(runId + ".properties"));
        Map<String, String> env = new HashMap<>();
        env.put("IT_APP_JAVA", fakeJava().toString());
        env.put("IT_READY_TIMEOUT_SECONDS", timeoutSeconds);
        return spawn(List.of(START_APP.toString(),
                war.toString(),
                String.valueOf(freePort()),
                props.toString(),
                work.resolve("app-" + runId + ".pid").toString(),
                work.resolve("app-" + runId + ".log").toString(),
                runId), env);
    }

    /**
     * IT_RECORD_DIR is ALWAYS set, even by callers that don't care about
     * chromedriver records: sweep-stale.sh now scans it unconditionally, and
     * without an override that means the developer's own
     * {@code ~/.cache/roller-it} -- exactly the directory this class must
     * never touch, since it may hold real records from a real leak.
     */
    private Result runSweep(String currentRunId) throws Exception {
        return runSweep(currentRunId, recordDir());
    }

    private Result runSweep(String currentRunId, Path recordDir) throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("IT_WORK_DIR", work.toString());
        env.put("IT_RECORD_DIR", recordDir.toString());
        return run(List.of(SWEEP.toString(), currentRunId), env, Duration.ofMinutes(1));
    }

    /** A sweep that finds {@code docker} on its PATH ahead of the real one. */
    private Result runSweepAgainst(Path docker, String currentRunId) throws Exception {
        Map<String, String> env = new HashMap<>(fakeDockerEnvironment(docker));
        env.put("IT_WORK_DIR", work.toString());
        env.put("IT_RECORD_DIR", recordDir().toString());
        return run(List.of(SWEEP.toString(), currentRunId), env, Duration.ofMinutes(1));
    }

    // ------------------------------------------------------ container fixtures

    /** This test's postgres container for a run, under its own IT_CONTAINER_PREFIX (see spawn). */
    private String postgresContainer(String runId) {
        return namespace.replace('.', '-') + "-postgres-" + runId;
    }

    /**
     * This test's Keycloak container for a run: the postgres prefix with its role
     * swapped, which is how it-harness-lib.sh derives IT_KC_CONTAINER_PREFIX and how
     * it-selenium/pom.xml names the real one (roller-it-keycloak-run id).
     */
    private String keycloakContainer(String runId) {
        return namespace.replace('.', '-') + "-keycloak-" + runId;
    }

    /**
     * A stand-in for the docker CLI, holding {@code containers} and nothing else: the
     * "daemon" is a file of names, {@code ps --filter name=REGEX} greps it, and
     * {@code rm} deletes the line and appends the name to {@code removed}. The harness
     * only ever lists names and removes by name, so that is the whole contract. No real
     * container is created, so these tests need no Docker and cannot touch a real run.
     *
     * @return the fake's directory, holding {@code bin/docker}
     */
    private Path fakeDocker(String... containers) throws IOException {
        Path dir = Files.createDirectories(work.resolve("fake-docker"));
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Files.write(dir.resolve("containers"), List.of(containers), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("removed"), "", StandardCharsets.UTF_8);
        Path docker = bin.resolve("docker");
        Files.writeString(docker, String.join("\n",
                "#!/usr/bin/env bash",
                "dir=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"",
                "case \"${1:-}\" in",
                "    ps)",
                "        filter=''",
                "        while [ $# -gt 0 ]; do",
                "            if [ \"$1\" = --filter ]; then filter=\"${2#name=}\"; shift; fi",
                "            shift",
                "        done",
                "        grep -E -- \"$filter\" \"$dir/containers\" || true",
                "        ;;",
                "    rm)",
                "        name=\"${!#}\"",
                "        grep -vxF -- \"$name\" \"$dir/containers\" > \"$dir/containers.next\" || true",
                "        mv \"$dir/containers.next\" \"$dir/containers\"",
                "        printf '%s\\n' \"$name\" >> \"$dir/removed\"",
                "        ;;",
                "esac",
                ""), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwx------"));
        return dir;
    }

    private static Map<String, String> fakeDockerEnvironment(Path docker) {
        return Map.of("PATH", docker.resolve("bin") + ":" + System.getenv("PATH"));
    }

    private static List<String> removedContainers(Path docker) throws IOException {
        return Files.readAllLines(docker.resolve("removed"), StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();
    }

    /**
     * Stands in for {@code java -jar roller.war}: keeps the command line it was
     * given (so the run marker stays greppable, exactly as the real JVM's does)
     * and never answers on the port, so readiness always times out.
     */
    private Path fakeJava() throws IOException {
        Path java = work.resolve("fake-java.sh");
        if (!Files.exists(java)) {
            Files.writeString(java, "#!/usr/bin/env bash\nwhile kill -0 " + JVM_PID + " 2>/dev/null; do sleep 1; done\n", StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
        }
        return java;
    }

    /** A process carrying the harness's markers, standing in for a Roller app JVM. */
    private long spawnMarked(String runId, String ownerToken) throws Exception {
        return spawnMarked(namespace, runId, ownerToken);
    }

    /**
     * As above, in a given namespace. The marker properties come from the harness's own
     * library, as the owner token does, so a fixture and the real start-app.sh cannot
     * drift apart in how they spell a marker.
     */
    private long spawnMarked(String inNamespace, String runId, String ownerToken) throws Exception {
        Process process = spawn(List.of("bash", "-c",
                "source \"$1\"; exec bash -c 'while kill -0 " + JVM_PID + " 2>/dev/null; do sleep 1; done' "
                        + "\"${IT_RUN_PROP}$2\" \"${IT_OWNER_PROP}$3\"",
                "bash", LIB.toString(), runId, ownerToken), Map.of("IT_NAMESPACE", inNamespace));
        awaitProcessMarked(inNamespace, runId, "the fixture process never appeared");
        return process.pid();
    }

    /** Asks the harness's own library for the token, so the format has one definition. */
    private String ownerToken(long pid) throws Exception {
        Result result = run(List.of("bash", "-c", "source \"$1\"; it_owner_stamp \"$2\"",
                "bash", LIB.toString(), String.valueOf(pid)), Map.of(), Duration.ofSeconds(30));
        assertEquals(0, result.exit(), "it_owner_stamp failed:\n" + result.output());
        String token = result.output().trim();
        assertFalse(token.isEmpty(), "it_owner_stamp produced nothing for pid " + pid);
        return token;
    }

    private String ownerTokenOfLiveProcess() throws Exception {
        return ownerToken(ProcessHandle.current().pid());
    }

    private String ownerTokenOfDeadProcess() throws Exception {
        Process shortLived = spawn(List.of("sleep", "5"));
        String token = ownerToken(shortLived.pid());
        shortLived.destroyForcibly();
        assertTrue(shortLived.waitFor(30, TimeUnit.SECONDS), "fixture process would not die");
        return token;
    }

    // ------------------------------------------------------ chromedriver fixtures

    /**
     * A fake chromedriver: a copy of the real bash binary, named
     * "chromedriver". {@code /proc/[pid]/comm} -- what {@code ps -o comm=}
     * reads on Linux, and what {@code it_comm} in it-harness-lib.sh checks --
     * is set from the basename of the FILE PASSED TO execve, never from
     * argv[0]; an {@code exec -a chromedriver ...} rename trick does not
     * touch it; the file itself has to be named "chromedriver". It spawns one
     * persistent child ("sleep 300") standing in for a Chrome process, so
     * descendant-reaping can be verified: a bare SIGKILL to the parent alone
     * leaves that child running, which is exactly the 8.1GB this class exists
     * to catch (confirmed by hand against this JDK/coreutils before writing
     * these tests: `kill -9` on the fake driver leaves its `sleep 300` child
     * alive).
     */
    private Process spawnFakeChromedriver() throws Exception {
        return spawn(List.of(fakeChromedriverExecutable().toString(), "-c", "sleep 300 & wait"));
    }

    /**
     * Like {@link #spawnFakeChromedriver()}, but run as the CHILD of a
     * returned "owner" process rather than as a direct child of the test
     * JVM -- for driving supervise-run.sh itself, whose
     * {@code record_chromedrivers} only ever looks at descendants of the
     * build pid it is watching (real chromedrivers are children of the
     * Selenium/failsafe JVM, never siblings of it). The tree is owner ->
     * fake chromedriver -> "sleep 300" (standing in for a Chrome child).
     */
    private Process spawnOwnerRunningFakeChromedriver() throws Exception {
        Path exe = fakeChromedriverExecutable();
        return spawn(List.of("bash", "-c", "\"$0\" -c 'sleep 300 & wait' & wait", exe.toString()));
    }

    /** Best-effort SIGKILL of a pid and everything below it. */
    private static void killTree(long pid) {
        ProcessHandle.of(pid).ifPresent(handle -> {
            handle.children().forEach(child -> killTree(child.pid()));
            handle.destroyForcibly();
        });
    }

    private Path fakeChromedriverExecutable() throws IOException {
        Path exe = work.resolve("chromedriver");
        if (!Files.exists(exe)) {
            Files.copy(realBashBinary(), exe);
            Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return exe;
    }

    private static Path realBashBinary() {
        for (String candidate : List.of("/bin/bash", "/usr/bin/bash")) {
            Path path = Paths.get(candidate);
            if (Files.isExecutable(path)) {
                return path;
            }
        }
        throw new IllegalStateException("no bash binary found to build the fake-chromedriver fixture from");
    }

    /** The single child a {@link #spawnFakeChromedriver()} process spawns. */
    private static long childPidOf(long parentPid) throws InterruptedException {
        Instant deadline = Instant.now().plus(REAP_BUDGET);
        while (Instant.now().isBefore(deadline)) {
            List<ProcessHandle> children = ProcessHandle.of(parentPid)
                    .map(handle -> handle.children().toList())
                    .orElse(List.of());
            if (!children.isEmpty()) {
                return children.get(0).pid();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("fake chromedriver " + parentPid + " never spawned its child");
    }

    /**
     * Writes a chromedriver record via the library's own functions, not by
     * hand -- so a fixture and the real writer (supervise-run.sh) cannot
     * silently drift in format.
     */
    private void writeChromedriverRecord(Path recordDir, String runId, String ownerToken, long... pids) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", "-c",
                "set -e; . \"$1\"; RECORD_DIR=\"$2\"; RUN_ID=\"$3\"; OWNER=\"$4\"; "
                        + "export IT_RECORD_DIR=\"$RECORD_DIR\"; it_record_init \"$RUN_ID\" \"$OWNER\"; "
                        + "shift 4; for pid in \"$@\"; do it_record_add_pid \"$RUN_ID\" \"$pid\"; done",
                "bash", LIB.toString(), recordDir.toString(), runId, ownerToken));
        for (long pid : pids) {
            command.add(String.valueOf(pid));
        }
        Result result = run(command, Map.of(), Duration.ofSeconds(30));
        assertEquals(0, result.exit(), "failed to write the fixture chromedriver record:\n" + result.output());
    }

    private Path recordDir() throws IOException {
        return Files.createDirectories(work.resolve("record-dir"));
    }

    private static List<String> recordLines(Path record) throws IOException {
        return Files.readAllLines(record, StandardCharsets.UTF_8);
    }

    private static List<String> recordPidLines(Path record) throws IOException {
        List<String> pids = new ArrayList<>();
        for (String line : recordLines(record)) {
            if (line.matches("\\d+")) {
                pids.add(line);
            }
        }
        return pids;
    }

    private static Path awaitRecordNaming(Path dir, String runId, long pid) throws Exception {
        Path record = dir.resolve(runId + ".chromedrivers");
        Instant deadline = Instant.now().plus(REAP_BUDGET);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(record) && recordPidLines(record).contains(String.valueOf(pid))) {
                return record;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("the record at " + record + " never named pid " + pid + "; it holds:\n"
                + (Files.exists(record) ? recordLines(record) : "(no such file)"));
    }

    /**
     * Waits for the supervisor to delete a record, rather than checking once.
     *
     * <p>The supervisor reaps the recorded processes and deletes the record as two
     * steps on its own poll tick, so a bare {@code assertFalse(Files.exists(...))}
     * immediately after the process assertions lands in the window between them
     * roughly one run in five. Every other assertion in these tests awaits; this
     * one has to as well.
     */
    private static void assertRecordDeleted(Path record, String message) throws InterruptedException {
        Instant deadline = Instant.now().plus(REAP_BUDGET);
        while (Instant.now().isBefore(deadline)) {
            if (!Files.exists(record)) {
                return;
            }
            Thread.sleep(200);
        }
        fail(message + " (" + record + " still present)");
    }

    private static void assertNoProcessAlive(long pid, String message) throws InterruptedException {
        Instant deadline = Instant.now().plus(REAP_BUDGET);
        while (Instant.now().isBefore(deadline)) {
            if (!isAlive(pid)) {
                return;
            }
            Thread.sleep(200);
        }
        fail(message + " (pid " + pid + " still alive)");
    }

    /** Stands in for `rm -rf`, to simulate `mvn clean` deleting target/. */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    // --------------------------------------------------------------- process

    private record Result(int exit, String output) {
    }

    private Process spawn(List<String> command) throws IOException {
        return spawn(command, Map.of());
    }

    /**
     * Output goes to a file rather than a pipe: draining a pipe before
     * {@code waitFor} would block for as long as the process ran, which would
     * silently defeat every timeout in this class.
     */
    private Process spawn(List<String> command, Map<String, String> env) throws IOException {
        Path log = work.resolve("proc-" + spawned.size() + ".out");
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        // Every script this class runs is confined to this test's namespace unless the
        // caller names another; see the namespace field.
        builder.environment().put("IT_NAMESPACE", namespace);
        builder.environment().put("IT_CONTAINER_PREFIX", namespace.replace('.', '-') + "-postgres");
        builder.environment().putAll(env);
        Process process = builder.start();
        spawned.add(process);
        output.put(process.pid(), log);
        return process;
    }

    private Result run(List<String> command, Map<String, String> env, Duration timeout) throws Exception {
        return await(spawn(command, env), timeout);
    }

    private Result await(Process process, Duration timeout) throws Exception {
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("timed out after " + timeout + "; output so far:\n" + outputOf(process));
        }
        return new Result(process.exitValue(), outputOf(process));
    }

    private String outputOf(Process process) throws IOException {
        Path log = output.get(process.pid());
        return log != null && Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Every process whose command line carries the given marker, whoever started it. */
    private List<String> processesMarked(String inNamespace, String propertyVariable, String runId)
            throws Exception {
        String marker = libProperty(inNamespace, propertyVariable) + runId;
        Process ps = new ProcessBuilder("ps", "-ww", "-eo", "pid=,args=").redirectErrorStream(true).start();
        String output = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        ps.waitFor(30, TimeUnit.SECONDS);
        return output.lines()
                .filter(line -> line.contains(marker))
                .toList();
    }

    /** A marker property's spelling in a namespace, asked of the harness library itself. */
    private String libProperty(String inNamespace, String variable) throws Exception {
        String key = inNamespace + " " + variable;
        String cached = libProperties.get(key);
        if (cached != null) {
            return cached;
        }
        Result result = run(List.of("bash", "-c", "source \"$1\"; printf '%s' \"${!2}\"",
                "bash", LIB.toString(), variable), Map.of("IT_NAMESPACE", inNamespace), Duration.ofSeconds(30));
        assertEquals(0, result.exit(), "could not read " + variable + " from the harness library:\n"
                + result.output());
        assertFalse(result.output().isBlank(), variable + " is empty in the harness library");
        libProperties.put(key, result.output());
        return result.output();
    }

    private static String awaitFileContaining(Path file, String needle) throws Exception {
        Instant deadline = Instant.now().plus(REAP_BUDGET);
        String content = "";
        while (Instant.now().isBefore(deadline)) {
            content = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            if (content.contains(needle)) {
                return content;
            }
            Thread.sleep(200);
        }
        return fail(file + " never contained '" + needle + "'; it holds:\n" + content);
    }

    private void awaitProcessFor(String runId, String message) throws Exception {
        awaitProcessMarked(namespace, runId, message);
    }

    private void awaitProcessMarked(String inNamespace, String runId, String message) throws Exception {
        Instant deadline = Instant.now().plus(REAP_BUDGET);
        while (Instant.now().isBefore(deadline)) {
            if (!processesMarked(inNamespace, "IT_RUN_PROP", runId).isEmpty()) {
                return;
            }
            Thread.sleep(200);
        }
        fail(message + " (no process carrying " + libProperty(inNamespace, "IT_RUN_PROP") + runId + ")");
    }

    private void assertNoProcessFor(String runId, String message) throws Exception {
        assertNoProcessMarked(namespace, "IT_RUN_PROP", runId, message);
    }

    private void assertNoSupervisorFor(String runId, String message) throws Exception {
        assertNoProcessMarked(namespace, "IT_SUPERVISOR_PROP", runId, message);
    }

    private void assertNoProcessMarked(String inNamespace, String propertyVariable, String runId,
            String message) throws Exception {
        Instant deadline = Instant.now().plus(REAP_BUDGET);
        List<String> survivors = List.of();
        while (Instant.now().isBefore(deadline)) {
            survivors = processesMarked(inNamespace, propertyVariable, runId);
            if (survivors.isEmpty()) {
                return;
            }
            Thread.sleep(500);
        }
        fail(message + "; still running:\n" + String.join("\n", survivors));
    }

    /** A fresh marker namespace: unique on this machine, and never the real harness's. */
    private static String newNamespace() {
        return "roller.itleak" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String runId(String label) {
        return "itleaktest-" + label + "-" + System.nanoTime();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
