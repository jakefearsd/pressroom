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
package com.wikantik.pressroom.business.startup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shipped bin/deploy-host.sh from a scratch checkout (its own
 * pom.xml and deploy/upgrade.sh) with `scp`, `ssh` and `curl` as stubs that
 * record their arguments.
 */
class DeployHostScriptTest {

    @TempDir Path dir;
    private Path checkout;
    private final Map<String, String> env = new HashMap<>();

    @BeforeEach
    void layOut() throws Exception {
        checkout = dir.resolve("checkout");
        Files.createDirectories(checkout.resolve("bin"));
        Files.createDirectories(checkout.resolve("deploy"));
        Files.copy(Paths.get("../bin/deploy-host.sh"), checkout.resolve("bin/deploy-host.sh"));
        Files.writeString(checkout.resolve("deploy/upgrade.sh"), "# upgrade\n");
        Files.writeString(checkout.resolve("deploy/cutover-pressroom.sh"), "# cutover\n");
        Files.writeString(checkout.resolve("deploy/rollback-pressroom.sh"), "# rollback\n");
        Files.writeString(checkout.resolve("pom.xml"), """
                <project>
                    <groupId>com.wikantik.pressroom</groupId>
                    <version>9.8.7</version>
                    <dependency><version>1.0.0</version></dependency>
                </project>
                """);

        Path bin = dir.resolve("stubs");
        ScriptRun.stub(bin, "scp", "echo \"scp $*\" >> \"$STUB_LOG\"\n");
        // With STUB_HOME set it runs the remote command there, as the host's shell would.
        ScriptRun.stub(bin, "ssh", """
                echo "ssh $*" >> "$STUB_LOG"
                if [[ -n "${STUB_HOME:-}" ]]; then
                  ps -o sid= -p $$ > "$STUB_HOME/ssh-sid"
                  shift; cd "$STUB_HOME" && exec bash -c "$*"
                fi
                exit "${STUB_SSH_RC:-0}"
                """);
        ScriptRun.stub(bin, "curl", "echo \"curl $*\" >> \"$STUB_LOG\"\nprintf %s \"${STUB_HTTP:-200}\"\n");
        env.put("STUB_LOG", dir.resolve("calls.log").toString());
    }

    private ScriptRun.Result deploy(String... args) throws Exception {
        return ScriptRun.run(dir, dir.resolve("stubs"), env, checkout.resolve("bin/deploy-host.sh"), args);
    }

    private String calls() throws Exception {
        return ScriptRun.log(dir.resolve("calls.log"));
    }

    @Test
    void copiesTheUpgradeScriptAndRunsItThereAtTheCheckoutsVersion() throws Exception {
        ScriptRun.Result r = deploy("docker1");

        assertEquals(0, r.exit(), r.out());
        assertTrue(calls().contains("scp -q " + checkout.resolve("deploy/upgrade.sh") + " docker1:pressroom/upgrade.sh"),
                calls());
        assertTrue(calls().contains("ssh docker1 cd pressroom && bash upgrade.sh 9.8.7\n"), calls());
        assertTrue(calls().indexOf("scp") < calls().indexOf("ssh"), calls());
    }

    @Test
    void passesAVersionADirectoryAndTheUpgradeFlagsThrough() throws Exception {
        ScriptRun.Result r = deploy("docker1", "0.3.0", "--dir", "/srv/pressroom", "--no-backup", "--prune");

        assertEquals(0, r.exit(), r.out());
        assertTrue(calls().contains("docker1:/srv/pressroom/upgrade.sh"), calls());
        assertTrue(calls().contains("ssh docker1 cd /srv/pressroom && bash upgrade.sh 0.3.0 --no-backup --prune\n"),
                calls());
    }

    /** The one-time move from the old deploy directory, which stays as it was for a rollback. */
    @Test
    void cutoverCopiesBothScriptsToTheOldDirectoryAndRunsTheCutoverThere() throws Exception {
        Path home = fakeHostWithCutover(0);

        ScriptRun.Result r = deploy("docker1", "0.5.0", "--cutover", "--url", "https://blog.example.com");

        assertEquals(0, r.exit(), r.out());
        String calls = calls();
        assertTrue(calls.contains("scp -q " + checkout.resolve("deploy/cutover-pressroom.sh") + " "
                + checkout.resolve("deploy/rollback-pressroom.sh") + " docker1:roller/\n"), calls); // rebrand:keep
        int run = calls.indexOf("ssh docker1 cd roller || exit 1;"); // rebrand:keep
        assertTrue(run > 0, calls);
        assertFalse(calls.contains("upgrade.sh"), calls);
        assertTrue(calls.indexOf("scp") < run && run < calls.indexOf("curl"), calls);
        assertTrue(r.out().contains("cutover ran 0.5.0 in roller"), "the log is followed: " + r.out()); // rebrand:keep
        assertTrue(Files.readString(onlyLog(home)).contains("cutover ran 0.5.0"));
    }

    /** A dropped ssh session must not kill the cutover half way: it runs in its own session. */
    @Test
    void cutoverRunsDetachedFromTheSessionAndLogsBesideTheDirectories() throws Exception {
        Path home = fakeHostWithCutover(0);

        ScriptRun.Result r = deploy("docker1", "0.5.0", "--cutover");

        assertEquals(0, r.exit(), r.out());
        assertTrue(calls().contains("setsid nohup bash"), calls());
        Path log = onlyLog(home);
        assertTrue(log.getFileName().toString().matches("pressroom-cutover-\\d{8}T\\d{6}Z\\.log"), log.toString());
        assertTrue(r.out().contains("tail -f ~/" + log.getFileName()), "says how to reattach: " + r.out());
        assertNotEquals(Files.readString(home.resolve("ssh-sid")).strip(), Files.readString(home.resolve("sid")).strip(),
                "the cutover runs in a session of its own, which a hang-up of the ssh one does not reach");
    }

    @Test
    void aFailedCutoverReportsItsExitStatus() throws Exception {
        Path home = fakeHostWithCutover(3);

        ScriptRun.Result r = deploy("docker1", "0.5.0", "--cutover", "--url", "https://blog.example.com");

        assertEquals(3, r.exit(), r.out());
        assertTrue(r.out().contains("exited 3"), r.out());
        assertTrue(r.out().contains(onlyLog(home).getFileName().toString()), r.out());
        assertFalse(calls().contains("curl"), calls());
    }

    /** A log with no exit file is a cutover still running, or one that died uncleanly. */
    @Test
    void refusesWhileAnEarlierCutoverHasNotExited() throws Exception {
        Path home = fakeHostWithCutover(0);
        Files.writeString(home.resolve("pressroom-cutover-20261029T010203Z.log"), "half way\n");

        ScriptRun.Result r = deploy("docker1", "0.5.0", "--cutover");

        assertEquals(1, r.exit(), r.out());
        assertTrue(r.out().contains("ssh docker1 'tail -f ~/pressroom-cutover-20261029T010203Z.log'"), r.out());
        assertFalse(calls().contains("scp"), "refused before copying anything: " + calls());
        assertFalse(Files.exists(home.resolve("sid")), "no cutover started");
    }

    @Test
    void anEarlierCutoverThatExitedDoesNotBlockTheNext() throws Exception {
        Path home = fakeHostWithCutover(0);
        Files.writeString(home.resolve("pressroom-cutover-20261029T010203Z.log"), "done\n");
        Files.writeString(home.resolve("pressroom-cutover-20261029T010203Z.log.exit"), "1\n");

        ScriptRun.Result r = deploy("docker1", "0.5.0", "--cutover");

        assertEquals(0, r.exit(), r.out());
        assertTrue(Files.exists(home.resolve("sid")), r.out());
    }

    /** A home whose old deploy directory holds a stand-in cutover that exits RC. */
    private Path fakeHostWithCutover(int rc) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path old = Files.createDirectories(home.resolve("roller")); // rebrand:keep
        Files.writeString(old.resolve("cutover-pressroom.sh"), """
                echo "cutover ran $* in $(basename "$PWD")"
                ps -o sid= -p $$ > ../sid
                exit %d
                """.formatted(rc));
        env.put("STUB_HOME", home.toString());
        return home;
    }

    private static Path onlyLog(Path home) throws Exception {
        try (java.util.stream.Stream<Path> s = Files.list(home)) {
            List<Path> logs = s.filter(p -> p.getFileName().toString().endsWith(".log"))
                    .filter(p -> !p.getFileName().toString().contains("T010203Z")).toList();
            assertEquals(1, logs.size(), logs.toString());
            return logs.get(0);
        }
    }

    /** The read-only rehearsal on the live host: in the foreground, no log, no detach. */
    @Test
    void cutoverCheckCopiesTheCutoverAndRunsItsCheckInTheForeground() throws Exception {
        Path home = fakeHostWithCutover(0);

        ScriptRun.Result r = deploy("docker1", "0.5.0", "--cutover", "--check");

        assertEquals(0, r.exit(), r.out());
        String calls = calls();
        assertTrue(calls.contains("scp -q " + checkout.resolve("deploy/cutover-pressroom.sh") + " docker1:roller/\n"), // rebrand:keep
                calls);
        assertTrue(calls.contains("ssh docker1 cd roller && bash cutover-pressroom.sh --check 0.5.0\n"), calls); // rebrand:keep
        assertFalse(calls.contains("setsid"), calls);
        assertFalse(calls.contains("rollback-pressroom.sh"), calls);
        assertTrue(r.out().contains("cutover ran --check 0.5.0 in roller"), r.out()); // rebrand:keep
        assertFalse(hasLog(home), "no detached run, so no log");
        assertFalse(r.out().contains("is on 0.5.0"), "a check moves nothing: " + r.out());
    }

    @Test
    void aFailedCutoverCheckFailsWithItsStatus() throws Exception {
        fakeHostWithCutover(1);

        ScriptRun.Result r = deploy("docker1", "0.5.0", "--cutover", "--check");

        assertEquals(1, r.exit(), r.out());
        assertTrue(r.out().contains("check"), r.out());
    }

    /** A copy over a cutover still running would rewrite the script bash is reading. */
    @Test
    void cutoverCheckRefusesWhileAnEarlierCutoverHasNotExited() throws Exception {
        Path home = fakeHostWithCutover(0);
        Files.writeString(home.resolve("pressroom-cutover-20261029T010203Z.log"), "half way\n");

        ScriptRun.Result r = deploy("docker1", "0.5.0", "--cutover", "--check");

        assertEquals(1, r.exit(), r.out());
        assertFalse(calls().contains("scp"), calls());
    }

    @Test
    void checkIsOnlyForTheCutover() throws Exception {
        ScriptRun.Result r = deploy("docker1", "0.5.0", "--check");

        assertEquals(2, r.exit(), r.out());
        assertTrue(r.out().contains("--check"), r.out());
        assertEquals("", calls());
    }

    private static boolean hasLog(Path home) throws Exception {
        try (java.util.stream.Stream<Path> s = Files.list(home)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(".log"));
        }
    }

    /** The cutover always backs up and always moves to the default directory. */
    @Test
    void cutoverRefusesTheUpgradeOnlyFlags() throws Exception {
        for (String flag : new String[] {"--no-backup", "--prune"}) {
            ScriptRun.Result r = deploy("docker1", "0.5.0", "--cutover", flag);
            assertEquals(2, r.exit(), flag + ": " + r.out());
            assertTrue(r.out().contains("--cutover takes no " + flag), r.out());
        }
        ScriptRun.Result r = deploy("docker1", "0.5.0", "--cutover", "--dir", "elsewhere");
        assertEquals(2, r.exit(), r.out());
        assertTrue(r.out().contains("--cutover takes no --dir"), r.out());
        assertEquals("", calls());
    }

    @Test
    void aFailedUpgradeOnTheHostFailsTheDeploy() throws Exception {
        env.put("STUB_SSH_RC", "1");

        ScriptRun.Result r = deploy("docker1", "--url", "https://blog.example.com");

        assertNotEquals(0, r.exit(), r.out());
        assertFalse(calls().contains("curl"), "no site check after a failed upgrade: " + calls());
    }

    @Test
    void theSiteCheckPassesOnA200() throws Exception {
        ScriptRun.Result r = deploy("docker1", "--url", "https://blog.example.com");

        assertEquals(0, r.exit(), r.out());
        assertTrue(calls().contains("https://blog.example.com"), calls());
    }

    /** The app can be healthy inside its container while the public route to it is broken. */
    @Test
    void aSiteThatDoesNotAnswer200FailsTheDeploy() throws Exception {
        env.put("STUB_HTTP", "502");

        ScriptRun.Result r = deploy("docker1", "--url", "https://blog.example.com");

        assertNotEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("502"), r.out());
    }

    @Test
    void noHostIsAUsageError() throws Exception {
        ScriptRun.Result r = deploy();

        assertEquals(2, r.exit(), r.out());
        assertTrue(r.out().contains("Usage"), r.out());
        assertEquals("", calls());
    }

    @Test
    void aVersionThatIsNotXyzIsRefused() throws Exception {
        ScriptRun.Result r = deploy("docker1", "latest");

        assertEquals(2, r.exit(), r.out());
        assertEquals("", calls());
    }
}
