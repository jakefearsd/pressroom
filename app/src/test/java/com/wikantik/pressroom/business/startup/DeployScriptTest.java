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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
                echo "compose-file: ${COMPOSE_FILE-unset}" >> "$STUB_LOG"
                case "$*" in
                  "volume ls"*) exit 0 ;;
                  *" run --rm -T admin status"*) echo "STATUS-OUTPUT-LINE"; exit "$STUB_STATUS_RC" ;;
                esac
                exit 0
                """);
        stub.toFile().setExecutable(true);
        Files.writeString(dir.resolve("docker-compose.prod.yml"), "");
        if (!Files.exists(dir.resolve(".env"))) {
            Files.writeString(dir.resolve(".env"), "");
        }
        ProcessBuilder pb = new ProcessBuilder(command(args)).directory(dir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("STUB_LOG", dir.resolve("calls.log").toString());
        pb.environment().put("STUB_STATUS_RC", String.valueOf(statusRc));
        pb.environment().remove("COMPOSE_FILE");
        pb.environment().putAll(inherited);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "deploy.sh hung");
        Path calls = dir.resolve("calls.log");
        return new Result(p.exitValue(), out, Files.exists(calls) ? Files.readString(calls) : "");
    }

    /** An environment variable for the deploy.sh process itself, as upgrade.sh might leave one. */
    private final Map<String, String> inherited = new HashMap<>();

    private static List<String> command(String... args) {
        List<String> command = new ArrayList<>(List.of("bash", DEPLOY.toString()));
        command.addAll(List.of(args));
        return command;
    }

    private static final String CORE = "docker-compose.prod.yml";

    /** The COMPOSE_FILE each {@code docker compose} call saw (the stub logs it after each call). */
    private static Set<String> composeFiles(String calls) {
        List<String> lines = calls.lines().toList();
        Set<String> seen = new TreeSet<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (lines.get(i).startsWith("compose ")) {
                seen.add(lines.get(i + 1).replaceFirst("^compose-file: ", ""));
            }
        }
        assertFalse(seen.isEmpty(), "no compose call: " + calls);
        return seen;
    }

    /**
     * The host deploys the files COMPOSE_FILE in .env names, which plain
     * {@code docker compose} reads by itself. A .env without the line gets
     * the core file, once, so the operator's own commands see the same file,
     * and every compose call deploy.sh makes uses that setting. (0.8.5 to
     * 0.16.6 added the back-office add-on too; Umami and listmonk now run in
     * the back-office suite.)
     */
    @Test
    void deployAddsTheComposeFileSettingOnceAndUsesIt() throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "IMAGE_VERSION=0.8.4\n");

        Result first = deploy(0);
        Result second = deploy(0);

        assertEquals(0, first.exit(), first.out());
        List<String> lines = Files.readAllLines(env, StandardCharsets.UTF_8);
        assertEquals(List.of("COMPOSE_FILE=" + CORE), lines.stream().filter(l -> l.startsWith("COMPOSE_FILE")).toList());
        assertTrue(first.out().contains("COMPOSE_FILE"), "the operator is told: " + first.out());
        assertFalse(first.calls().contains(" -f "), "no -f: the setting chooses the files: " + first.calls());
        assertEquals(Set.of(CORE), composeFiles(first.calls()), first.calls());
        assertEquals(0, second.exit(), second.out());
        assertEquals(lines, Files.readAllLines(env, StandardCharsets.UTF_8), "a second deploy keeps it");
    }

    /** deploy.sh deploys what .env says. */
    @Test
    void anExistingComposeFileSettingIsKept() throws Exception {
        Files.writeString(dir.resolve("docker-compose.override.yml"), "");
        Files.writeString(dir.resolve(".env"),
                "IMAGE_VERSION=0.8.5\nCOMPOSE_FILE=docker-compose.prod.yml:docker-compose.override.yml\n");

        Result r = deploy(0);

        assertEquals(0, r.exit(), r.out());
        assertEquals(Set.of("docker-compose.prod.yml:docker-compose.override.yml"), composeFiles(r.calls()),
                r.calls());
    }

    /**
     * A host still running Umami and listmonk from the 0.16.6 add-on must
     * cut over to the back-office suite first: the add-on's one-shots and
     * backup scripts are no longer in the image, so deploying it would leave
     * them failing. Stop before anything is pulled or started (even with the
     * old add-on file still lying in the directory), and say what to do.
     */
    @Test
    void theRetiredAddOnInComposeFileStopsTheDeploy() throws Exception {
        Files.writeString(dir.resolve("docker-compose.backoffice.yml"), "services: {}\n");
        String before = "IMAGE_VERSION=0.16.6\nCOMPOSE_FILE=docker-compose.prod.yml:docker-compose.backoffice.yml\n";
        Files.writeString(dir.resolve(".env"), before);

        Result r = deploy(0);

        assertEquals(1, r.exit(), r.out());
        assertTrue(r.out().contains("docker-compose.backoffice.yml"), r.out());
        assertTrue(r.out().contains("SimpleAgilityBackOfficeSuite"), "points at the cutover: " + r.out());
        assertEquals(before, Files.readString(dir.resolve(".env")), ".env untouched");
        assertFalse(r.calls().contains(" pull"), r.calls());
        assertFalse(r.calls().contains(" up -d"), r.calls());
    }

    /**
     * Umami's and listmonk's secrets (Pressroom's Umami account, their
     * database servers) belong to the back-office suite now: deploy.sh
     * generates none of them and probes no back-office volume.
     */
    @Test
    void deployGeneratesNoBackOfficeSecret() throws Exception {
        Path env = dir.resolve(".env");
        String before = "IMAGE_VERSION=0.16.7\nCOMPOSE_FILE=docker-compose.prod.yml\n";
        Files.writeString(env, before);

        Result r = deploy(0);

        assertEquals(0, r.exit(), r.out());
        assertEquals(before, Files.readString(env), "nothing appended");
        assertFalse(r.out().contains("Generated"), r.out());
        assertFalse(r.calls().contains("volume ls"), r.calls());
    }

    /** .env decides, not whatever COMPOSE_FILE the calling shell (or upgrade.sh) exported. */
    @Test
    void anInheritedComposeFileDoesNotOverrideEnv() throws Exception {
        Files.writeString(dir.resolve(".env"), "IMAGE_VERSION=0.8.4\n");
        inherited.put("COMPOSE_FILE", "docker-compose.prod.yml");

        Result r = deploy(0);

        assertEquals(0, r.exit(), r.out());
        assertEquals(Set.of(CORE), composeFiles(r.calls()), r.calls());
    }

    @Test
    void aComposeFileThatIsNotHereStopsTheDeploy() throws Exception {
        Files.writeString(dir.resolve(".env"), "IMAGE_VERSION=0.8.5\nCOMPOSE_FILE=docker-compose.prod.yml:gone.yml\n");

        Result r = deploy(0);

        assertEquals(1, r.exit(), r.out());
        assertTrue(r.out().contains("gone.yml"), r.out());
        assertFalse(r.calls().contains(" up -d"), r.calls());
    }

    @Test
    void pullsBeforeStarting() throws Exception {
        Result r = deploy(0);
        assertEquals(0, r.exit(), r.out());
        assertTrue(r.calls().contains("compose pull\n"), r.calls());
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
                "docker compose run --rm admin create --username NAME --email ADDRESS"),
                r.out());
        assertTrue(r.calls().contains("compose run --rm -T admin status"), r.calls());
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

    /**
     * env.example ships {@code changeme-...} placeholders for every secret,
     * and they satisfy compose's {@code ${VAR:?}} checks, so a .env copied
     * without editing would start a stack whose database password is in the
     * public repository. deploy.sh refuses before touching anything, names
     * each offending variable, and never prints a value.
     */
    @Test
    void aPlaceholderSecretIsRefusedBeforeAnythingRuns() throws Exception {
        Path env = dir.resolve(".env");
        String original = """
                IMAGE_VERSION=0.6.1
                # VHOST_DNS_API_TOKEN=changeme-cloudflare-api-token
                POSTGRES_PASSWORD=changeme-generate-a-real-secret
                VHOST_DNS_API_TOKEN="changeme-cloudflare-api-token"
                PRESSROOM_ANALYTICS_UMAMI_PASSWORD=a-real-one
                """;
        Files.writeString(env, original);

        Result r = deploy(0);
        assertEquals(1, r.exit(), r.out());
        assertTrue(r.out().contains("POSTGRES_PASSWORD"), r.out());
        assertTrue(r.out().contains("    VHOST_DNS_API_TOKEN"), "a quoted placeholder too: " + r.out());
        assertEquals(1, r.out().split("VHOST_DNS_API_TOKEN", -1).length - 1,
                "the commented-out line is not a setting, so it is named once: " + r.out());
        assertFalse(r.out().contains("PRESSROOM_ANALYTICS_UMAMI_PASSWORD"), r.out());
        assertFalse(r.out().contains("generate-a-real-secret"), "values are never printed: " + r.out());
        assertEquals("", r.calls(), "nothing pulled or started");
        assertEquals(original, Files.readString(env), ".env untouched (no password generated)");
    }

    @Test
    void aValueThatMerelyContainsChangemeIsAccepted() throws Exception {
        Files.writeString(dir.resolve(".env"), "POSTGRES_PASSWORD=x-changeme-x\nIMAGE_VERSION=0.16.7\n");
        Result r = deploy(0);
        assertEquals(0, r.exit(), r.out());
    }
}
