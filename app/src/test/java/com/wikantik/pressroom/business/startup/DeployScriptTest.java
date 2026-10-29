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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shipped deploy/deploy.sh against a stub `docker` on PATH (no
 * daemon involved): the stub succeeds at everything, answers
 * `compose ... run --rm -T admin status` with $STUB_STATUS_RC, and records
 * every call. Spec acceptance criterion 7.
 */
class DeployScriptTest {

    private static final Path DEPLOY = Paths.get("../deploy/deploy.sh").toAbsolutePath();

    @TempDir Path dir;

    private record Result(int exit, String out, String calls) {}

    private Result deploy(int statusRc, String... args) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path stub = bin.resolve("docker");
        Files.writeString(stub, """
                #!/bin/bash
                echo "$*" >> "$STUB_LOG"
                case "$*" in
                  *" run --rm -T admin status"*) echo "STATUS-OUTPUT-LINE"; exit "$STUB_STATUS_RC" ;;
                esac
                exit 0
                """);
        stub.toFile().setExecutable(true);
        Files.writeString(dir.resolve("docker-compose.prod.yml"), "");
        Files.writeString(dir.resolve(".env"), "");
        ProcessBuilder pb = new ProcessBuilder(command(args)).directory(dir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("STUB_LOG", dir.resolve("calls.log").toString());
        pb.environment().put("STUB_STATUS_RC", String.valueOf(statusRc));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "deploy.sh hung");
        return new Result(p.exitValue(), out, Files.readString(dir.resolve("calls.log")));
    }

    private static List<String> command(String... args) {
        List<String> command = new ArrayList<>(List.of("bash", DEPLOY.toString()));
        command.addAll(List.of(args));
        return command;
    }

    @Test
    void pullsBeforeStarting() throws Exception {
        Result r = deploy(0);
        assertEquals(0, r.exit(), r.out());
        assertTrue(r.calls().contains("compose -f docker-compose.prod.yml pull\n"), r.calls());
        assertTrue(r.calls().indexOf(" pull") < r.calls().indexOf(" up -d"), r.calls());
    }

    /** For images built on this host (the cutover rehearsal), which no registry holds. */
    @Test
    void noPullStartsWhatIsAlreadyHere() throws Exception {
        Result r = deploy(0, "--no-pull");
        assertEquals(0, r.exit(), r.out());
        assertFalse(r.calls().contains(" pull"), r.calls());
        assertTrue(r.calls().contains(" up -d"), r.calls());
    }

    @Test
    void noAdminYetPrintsTheCreateCommandAndStillSucceeds() throws Exception {
        Result r = deploy(3);
        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains(
                "docker compose -f docker-compose.prod.yml run --rm admin create --username NAME --email ADDRESS"),
                r.out());
        assertTrue(r.calls().contains("compose -f docker-compose.prod.yml run --rm -T admin status"), r.calls());
        // The tool's own text would repeat the create command: only deploy.sh's hint shows.
        assertFalse(r.out().contains("STATUS-OUTPUT-LINE"), r.out());
    }

    @Test
    void anExistingAdminPrintsNoHint() throws Exception {
        Result r = deploy(0);
        assertEquals(0, r.exit(), r.out());
        assertFalse(r.out().contains("admin create"), r.out());
        assertTrue(r.out().contains("  STATUS-OUTPUT-LINE"), "status output, indented: " + r.out());
    }

    @Test
    void aFailedCheckWarnsButDoesNotFailTheDeploy() throws Exception {
        Result r = deploy(1);
        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("warning: could not check for an admin account"), r.out());
        assertTrue(r.out().contains("STATUS-OUTPUT-LINE"), "the failure's output should be shown: " + r.out());
    }
}
