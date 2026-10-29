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
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shipped deploy/rollback-pressroom.sh in the old deploy directory
 * of a scratch host, beside a new one the cutover made, with `docker` a stub
 * that records each call. The old layout is named on purpose; such lines
 * carry the inventory's keep marker.
 */
class RollbackScriptTest {

    private static final Path ROLLBACK = Paths.get("../deploy/rollback-pressroom.sh").toAbsolutePath();

    private static final String OLD_ENV = "IMAGE_VERSION=0.4.2\nPOSTGRES_PASSWORD=s3cret\n";

    @TempDir Path dir;
    private Path oldDir;
    private final Map<String, String> env = new HashMap<>();

    @BeforeEach
    void layOut() throws Exception {
        oldDir = Files.createDirectories(dir.resolve("host/roller")); // rebrand:keep
        Files.writeString(oldDir.resolve(".env"), OLD_ENV);
        Files.writeString(oldDir.resolve("docker-compose.prod.yml"), "old compose\n");
        Path deploy = oldDir.resolve("deploy.sh");
        Files.writeString(deploy, """
                #!/bin/bash
                echo "deploy.sh $* project=${COMPOSE_PROJECT_NAME:-} $(grep ^IMAGE_VERSION= .env)" >> "$STUB_LOG"
                """);
        assertTrue(deploy.toFile().setExecutable(true));

        Path newDir = Files.createDirectories(dir.resolve("host/pressroom"));
        Files.writeString(newDir.resolve(".env"), "IMAGE_VERSION=0.5.0\n");
        Files.writeString(newDir.resolve("docker-compose.prod.yml"), "new compose\n");

        ScriptRun.stub(dir.resolve("bin"), "docker", """
                echo "docker $*" >> "$STUB_LOG"
                case "$*" in
                  *"exec -T app curl"*) exit "${STUB_HEALTH_RC:-0}" ;;
                esac
                exit 0
                """);
        env.put("STUB_LOG", dir.resolve("calls.log").toString());
        env.put("ROLLBACK_WAIT_SECONDS", "0");
    }

    private String calls() throws Exception {
        return ScriptRun.log(dir.resolve("calls.log"));
    }

    private ScriptRun.Result rollback() throws Exception {
        return ScriptRun.run(oldDir, dir.resolve("bin"), env, ROLLBACK, "--yes");
    }

    /**
     * Ruling R12: the old stack starts from the images already on the host.
     * The old 0.4.x deploy.sh pulls under set -e, so a registry outage would
     * fail the rollback.
     */
    @Test
    void downsTheNewProjectAndStartsTheOldOneFromLocalImages() throws Exception {
        ScriptRun.Result r = rollback();

        assertEquals(0, r.exit(), r.out());
        String calls = calls();
        int down = calls.indexOf("compose -p pressroom -f ../pressroom/docker-compose.prod.yml down");
        int up = calls.indexOf("compose -p roller -f docker-compose.prod.yml up -d --pull never"); // rebrand:keep
        assertTrue(down >= 0, calls);
        assertTrue(up > down, "the new stack frees the ports before the old one starts: " + calls);
        assertTrue(calls.indexOf("exec -T app curl") > up, "health checked after the start: " + calls);
        assertFalse(Pattern.compile("(?m)\\spull(\\s|$)").matcher(calls).find(), "no pull: " + calls);
        assertFalse(calls.contains("deploy.sh"), calls);
        assertFalse(calls.contains("down -v"), calls);
        assertFalse(calls.contains("volume rm"), calls);
        assertEquals(OLD_ENV, Files.readString(oldDir.resolve(".env")));
        assertFalse(r.out().contains("s3cret"), r.out());
    }

    /** An accidental run must not take a healthy site back to 0.4.x. */
    @Test
    void refusesWithoutYes() throws Exception {
        ScriptRun.Result r = ScriptRun.run(oldDir, dir.resolve("bin"), env, ROLLBACK);

        assertEquals(1, r.exit(), r.out());
        assertTrue(r.out().contains("Refusing") && r.out().contains("--yes"), r.out());
        assertEquals("", calls());
    }

    @Test
    void aQuotedImageVersionIsRead() throws Exception {
        Files.writeString(oldDir.resolve(".env"), "IMAGE_VERSION=\"0.4.2\"\r\n");

        ScriptRun.Result r = rollback();

        assertEquals(0, r.exit(), r.out());
    }

    @Test
    void refusesWhenTheOldEnvIsNotTheOneBeforeTheCutover() throws Exception {
        Files.writeString(oldDir.resolve(".env"), "IMAGE_VERSION=0.5.0\n");

        ScriptRun.Result r = rollback();

        assertEquals(1, r.exit(), r.out());
        assertTrue(r.out().contains("Refusing") && r.out().contains("0.4.x"), r.out());
        assertEquals("", calls());
    }

    @Test
    void anUnhealthyOldStackFailsTheRollback() throws Exception {
        env.put("STUB_HEALTH_RC", "1");
        env.put("ROLLBACK_HEALTH_TRIES", "2");

        ScriptRun.Result r = rollback();

        assertNotEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("healthy"), r.out());
    }

    @Test
    void withNoNewStackItOnlyDeploysTheOldOne() throws Exception {
        Files.delete(dir.resolve("host/pressroom/.env"));

        ScriptRun.Result r = rollback();

        assertEquals(0, r.exit(), r.out());
        assertFalse(calls().contains(" down"), calls());
        assertTrue(calls().contains("compose -p roller -f docker-compose.prod.yml up -d --pull never"), calls()); // rebrand:keep
    }
}
