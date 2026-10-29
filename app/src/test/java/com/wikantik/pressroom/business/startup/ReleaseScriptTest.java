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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shipped bin/release.sh in a scratch git repository holding copies
 * of the real poms and deploy/.env.example, with a bare repository as its
 * origin and `mvn` and `gh` as stubs that record their calls.
 */
class ReleaseScriptTest {

    private static final List<String> VERSIONED =
            List.of("pom.xml", "app/pom.xml", "it-selenium/pom.xml", "deploy/.env.example");
    private static final String NEXT = "99.0.0";

    @TempDir Path dir;
    private Path work;
    private Path origin;
    private String current;
    private final Map<String, String> env = new HashMap<>();

    @BeforeEach
    void layOut() throws Exception {
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_AUTHOR_NAME", "Release Test");
        env.put("GIT_AUTHOR_EMAIL", "release@example.com");
        env.put("GIT_COMMITTER_NAME", "Release Test");
        env.put("GIT_COMMITTER_EMAIL", "release@example.com");
        env.put("STUB_LOG", dir.resolve("calls.log").toString());

        work = Files.createDirectories(dir.resolve("work"));
        for (String f : VERSIONED) {
            Files.createDirectories(work.resolve(f).getParent());
            Files.copy(Paths.get("..", f), work.resolve(f));
        }
        Files.createDirectories(work.resolve("bin"));
        Files.copy(Paths.get("../bin/release.sh"), work.resolve("bin/release.sh"));
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(Files.readString(work.resolve("pom.xml")));
        assertTrue(m.find());
        current = m.group(1);
        // A later <version> that shares the project's (a dependency, say) is not the project's.
        Files.writeString(work.resolve("app/pom.xml"), "<!-- <version>" + current + "</version> -->\n",
                StandardOpenOption.APPEND);

        origin = dir.resolve("origin.git");
        git(dir, "init", "-q", "--bare", "-b", "master", origin.toString());
        git(work, "init", "-q", "-b", "master");
        git(work, "add", "-A");
        git(work, "commit", "-q", "-m", "base");
        git(work, "remote", "add", "origin", origin.toString());
        git(work, "push", "-q", "origin", "master");

        Path bin = dir.resolve("stubs");
        ScriptRun.stub(bin, "mvn", "echo \"mvn $*\" >> \"$STUB_LOG\"\nexit \"${STUB_MVN_RC:-0}\"\n");
        ScriptRun.stub(bin, "gh", """
                echo "gh $*" >> "$STUB_LOG"
                case "$*" in
                  "run list"*) echo 4242 ;;
                esac
                exit 0
                """);
    }

    private String git(Path in, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(in.toFile()).redirectErrorStream(true);
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "git hung");
        assertEquals(0, p.exitValue(), "git " + command + ": " + out);
        return out.trim();
    }

    private ScriptRun.Result release(String... args) throws Exception {
        return ScriptRun.run(work, dir.resolve("stubs"), env, work.resolve("bin/release.sh"), args);
    }

    private String calls() throws Exception {
        return ScriptRun.log(dir.resolve("calls.log"));
    }

    private void assertReleasedNothing(ScriptRun.Result r) throws Exception {
        assertNotEquals(0, r.exit(), r.out());
        assertEquals("base", git(work, "log", "-1", "--format=%s"), r.out());
        assertEquals("", git(work, "tag", "--list", "v" + NEXT));
        for (String f : List.of("pom.xml", "deploy/.env.example")) {
            assertFalse(Files.readString(work.resolve(f)).contains(NEXT), f);
        }
    }

    @Test
    void bumpsTheVersionsCommitsAndTagsWithoutPushing() throws Exception {
        ScriptRun.Result r = release(NEXT, "--skip-verify");

        assertEquals(0, r.exit(), r.out());
        assertEquals("release: " + NEXT, git(work, "log", "-1", "--format=%s"));
        assertEquals(String.join("\n", VERSIONED.stream().sorted().toList()),
                git(work, "diff", "--name-only", "HEAD~1", "HEAD"));
        for (String f : List.of("pom.xml", "app/pom.xml", "it-selenium/pom.xml")) {
            String diff = git(work, "diff", "HEAD~1", "HEAD", "--", f);
            assertEquals(1, diff.lines().filter(l -> l.startsWith("-") && l.contains(current)).count(), diff);
            assertEquals(1, diff.lines().filter(l -> l.startsWith("+") && l.contains("<version>" + NEXT)).count(), diff);
        }
        assertTrue(Files.readString(work.resolve("app/pom.xml")).contains("<!-- <version>" + current + "</version> -->"),
                "only the first, the project's, moves");
        assertTrue(Files.readString(work.resolve("deploy/.env.example")).contains("\nIMAGE_VERSION=" + NEXT + "\n"));
        assertEquals(git(work, "rev-parse", "HEAD"), git(work, "rev-parse", "v" + NEXT + "^{commit}"));
        assertEquals("", git(work, "ls-remote", "--tags", "origin"), "not pushed");
        assertFalse(calls().contains("mvn"), calls());
        assertTrue(r.out().contains("git push origin master v" + NEXT), r.out());
    }

    @Test
    void verifiesWithTheUnitSuiteAndTheBrowserSuiteAtBothContextPaths() throws Exception {
        ScriptRun.Result r = release(NEXT);

        assertEquals(0, r.exit(), r.out());
        assertEquals("""
                mvn clean install
                mvn verify -Pit -DskipUnitTests=true
                mvn verify -Pit -DskipUnitTests=true -Dit.context.path=roller
                """, calls());
    }

    @Test
    void aFailedVerificationReleasesNothing() throws Exception {
        env.put("STUB_MVN_RC", "1");

        assertReleasedNothing(release(NEXT));
    }

    @Test
    void pushSendsMasterAndTheTagThenWatchesTheReleaseRun() throws Exception {
        ScriptRun.Result r = release(NEXT, "--skip-verify", "--push");

        assertEquals(0, r.exit(), r.out());
        assertEquals(git(work, "rev-parse", "HEAD"), git(work, "ls-remote", "origin", "refs/heads/master").split("\\s")[0]);
        assertEquals(git(work, "rev-parse", "HEAD"), git(work, "ls-remote", "origin", "refs/tags/v" + NEXT).split("\\s")[0]);
        assertTrue(calls().contains("gh run list --workflow release.yml --branch v" + NEXT), calls());
        assertTrue(calls().contains("gh run watch 4242 --exit-status"), calls());
    }

    @Test
    void refusesADirtyTree() throws Exception {
        Files.writeString(work.resolve("stray.txt"), "x");

        ScriptRun.Result r = release(NEXT, "--skip-verify");

        assertReleasedNothing(r);
        assertTrue(r.out().contains("not clean"), r.out());
    }

    @Test
    void refusesABranchOtherThanMaster() throws Exception {
        git(work, "checkout", "-q", "-b", "elsewhere");

        ScriptRun.Result r = release(NEXT, "--skip-verify");

        assertReleasedNothing(r);
        assertTrue(r.out().contains("master"), r.out());
    }

    @Test
    void refusesATreeBehindOrigin() throws Exception {
        Path other = dir.resolve("other");
        git(dir, "clone", "-q", origin.toString(), other.toString());
        Files.writeString(other.resolve("later.txt"), "x");
        git(other, "add", "later.txt");
        git(other, "commit", "-q", "-m", "later");
        git(other, "push", "-q", "origin", "master");

        ScriptRun.Result r = release(NEXT, "--skip-verify");

        assertReleasedNothing(r);
        assertTrue(r.out().contains("behind"), r.out());
    }

    @Test
    void refusesATagThatExists() throws Exception {
        git(work, "tag", "v" + NEXT);

        ScriptRun.Result r = release(NEXT, "--skip-verify");

        assertNotEquals(0, r.exit(), r.out());
        assertEquals("base", git(work, "log", "-1", "--format=%s"));
        assertTrue(r.out().contains("v" + NEXT), r.out());
    }

    @Test
    void refusesAVersionNotAboveTheCurrentOne() throws Exception {
        for (String version : List.of(current, "0.0.1")) {
            ScriptRun.Result r = release(version, "--skip-verify");

            assertNotEquals(0, r.exit(), version + ": " + r.out());
            assertEquals("base", git(work, "log", "-1", "--format=%s"), version);
            assertTrue(r.out().contains(current), r.out());
        }
    }

    @Test
    void aVersionThatIsNotXyzIsRefused() throws Exception {
        ScriptRun.Result r = release("next", "--skip-verify");

        assertEquals(2, r.exit(), r.out());
        assertTrue(r.out().contains("Usage"), r.out());
    }
}
