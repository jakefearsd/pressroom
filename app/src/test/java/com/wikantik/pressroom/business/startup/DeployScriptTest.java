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

    /** What the stub's `docker volume ls` prints: an existing umami-db volume, or nothing. */
    private String umamiVolume = "";

    private record Result(int exit, String out, String calls) {}

    private Result deploy(int statusRc, String... args) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path stub = bin.resolve("docker");
        Files.writeString(stub, """
                #!/bin/bash
                echo "$*" >> "$STUB_LOG"
                case "$*" in
                  "volume ls"*) [ -n "$STUB_UMAMI_VOLUME" ] && echo "$STUB_UMAMI_VOLUME"; exit 0 ;;
                  *" run --rm -T admin status"*) echo "STATUS-OUTPUT-LINE"; exit "$STUB_STATUS_RC" ;;
                  *" up -d"*) echo "env-at-up: $(grep -c '^UMAMI_PRESSROOM_PASSWORD=.' .env)" >> "$STUB_LOG"
                              echo "db-env-at-up: $(grep -c '^UMAMI_DB_PASSWORD=.' .env)" >> "$STUB_LOG" ;;
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
        pb.environment().put("STUB_UMAMI_VOLUME", umamiVolume);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "deploy.sh hung");
        Path calls = dir.resolve("calls.log");
        return new Result(p.exitValue(), out, Files.exists(calls) ? Files.readString(calls) : "");
    }

    private static List<String> command(String... args) {
        List<String> command = new ArrayList<>(List.of("bash", DEPLOY.toString()));
        command.addAll(List.of(args));
        return command;
    }

    /**
     * Pressroom's Umami account password is generated, not chosen: the first
     * deploy that finds none in .env appends one, before {@code up -d} so
     * compose hands it to analytics-views and the app on that same run. Later
     * deploys keep it. It is a secret, so it is never printed, and .env keeps
     * its mode (the file holds every secret in the stack).
     */
    @Test
    void deployGeneratesPasswordOnceAndNeverPrints() throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "IMAGE_VERSION=0.6.1\nUMAMI_PRESSROOM_PASSWORD=\nSITE_DOMAIN=:80");
        Files.setPosixFilePermissions(env, PosixFilePermissions.fromString("rw-------"));

        Result first = deploy(0);
        assertEquals(0, first.exit(), first.out());
        List<String> lines = Files.readAllLines(env, StandardCharsets.UTF_8);
        List<String> passwords = lines.stream().filter(l -> l.startsWith("UMAMI_PRESSROOM_PASSWORD=")).toList();
        assertEquals(1, passwords.size(), "one password line, the blank one replaced: " + lines.size() + " lines");
        String password = passwords.get(0).substring("UMAMI_PRESSROOM_PASSWORD=".length());
        assertTrue(password.matches("[0-9a-f]{48}"), "openssl rand -hex 24 gives 48 hex digits");
        assertEquals(List.of("IMAGE_VERSION=0.6.1", "SITE_DOMAIN=:80"),
                lines.stream().filter(l -> !l.startsWith("UMAMI_PRESSROOM_PASSWORD="))
                        .filter(l -> !l.startsWith("UMAMI_DB_PASSWORD=")).toList(),
                "every other line kept, in order");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(env)));
        assertTrue(first.calls().contains("env-at-up: 1"), "generated before up -d: " + first.calls());
        assertFalse(first.out().contains(password), "the password must never be printed");
        assertFalse(first.calls().contains(password), "nor handed to docker on a command line");
        assertTrue(first.out().contains("UMAMI_PRESSROOM_PASSWORD"), "the operator is told it was generated");

        Result second = deploy(0);
        assertEquals(0, second.exit(), second.out());
        assertEquals(lines, Files.readAllLines(env, StandardCharsets.UTF_8), "a second deploy keeps it");
        assertFalse(second.out().contains(password), second.out());
    }

    /**
     * Umami's own database server takes a generated password too (spec
     * 2026-11-03, AC 8), on the same terms: once, before {@code up -d},
     * never printed, distinct from the account password, and kept by later
     * deploys. Compose refuses to start without it ({@code :?}).
     */
    @Test
    void deployGeneratesTheUmamiDatabasePasswordOnceAndNeverPrints() throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "IMAGE_VERSION=0.7.1\nUMAMI_PRESSROOM_PASSWORD=" + "a".repeat(48) + "\n");
        Files.setPosixFilePermissions(env, PosixFilePermissions.fromString("rw-------"));

        Result first = deploy(0);
        assertEquals(0, first.exit(), first.out());
        List<String> lines = Files.readAllLines(env, StandardCharsets.UTF_8);
        List<String> db = lines.stream().filter(l -> l.startsWith("UMAMI_DB_PASSWORD=")).toList();
        assertEquals(1, db.size(), String.valueOf(lines.size()));
        String password = db.get(0).substring("UMAMI_DB_PASSWORD=".length());
        assertTrue(password.matches("[0-9a-f]{48}"), "openssl rand -hex 24 gives 48 hex digits");
        assertTrue(lines.contains("UMAMI_PRESSROOM_PASSWORD=" + "a".repeat(48)), "the account password is kept");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(env)));
        assertTrue(first.calls().contains("db-env-at-up: 1"), "generated before up -d: " + first.calls());
        assertFalse(first.out().contains(password), "never printed");
        assertFalse(first.calls().contains(password), "nor handed to docker on a command line");
        assertTrue(first.out().contains("UMAMI_DB_PASSWORD"), "the operator is told it was generated");

        Result second = deploy(0);
        assertEquals(0, second.exit(), second.out());
        assertEquals(lines, Files.readAllLines(env, StandardCharsets.UTF_8), "a second deploy keeps it");
    }

    /**
     * umami-db keeps the password it was first started with. A .env without
     * the line beside an existing umami-db volume (an old .env restored on a
     * rollback) must stop the deploy before anything starts: a freshly
     * generated password would lock the move and Umami out of their server.
     */
    @Test
    void anExistingUmamiDbWithoutItsPasswordStopsTheDeploy() throws Exception {
        Path env = dir.resolve(".env");
        String before = "IMAGE_VERSION=0.7.2\nUMAMI_PRESSROOM_PASSWORD=" + "a".repeat(48) + "\n";
        Files.writeString(env, before);
        umamiVolume = "pressroom_pressroom-umami-pgdata";

        Result r = deploy(0);

        assertEquals(1, r.exit(), r.out());
        assertEquals(before, Files.readString(env), ".env is left exactly as it was");
        assertTrue(r.out().contains("UMAMI_DB_PASSWORD"), r.out());
        assertTrue(r.out().contains("pressroom_pressroom-umami-pgdata"), "names the volume: " + r.out());
        assertFalse(r.calls().contains(" up -d"), "nothing is started: " + r.calls());
        assertFalse(r.calls().contains(" pull"), r.calls());
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
                UMAMI_APP_SECRET="changeme-openssl-rand-hex-32"
                LISTMONK_ADMIN_PASSWORD=a-real-one
                """;
        Files.writeString(env, original);

        Result r = deploy(0);
        assertEquals(1, r.exit(), r.out());
        assertTrue(r.out().contains("POSTGRES_PASSWORD"), r.out());
        assertTrue(r.out().contains("UMAMI_APP_SECRET"), "a quoted placeholder too: " + r.out());
        assertFalse(r.out().contains("VHOST_DNS_API_TOKEN"), "a commented-out line is not a setting: " + r.out());
        assertFalse(r.out().contains("LISTMONK_ADMIN_PASSWORD"), r.out());
        assertFalse(r.out().contains("generate-a-real-secret"), "values are never printed: " + r.out());
        assertEquals("", r.calls(), "nothing pulled or started");
        assertEquals(original, Files.readString(env), ".env untouched (no password generated)");
    }

    @Test
    void aValueThatMerelyContainsChangemeIsAccepted() throws Exception {
        Files.writeString(dir.resolve(".env"), "POSTGRES_PASSWORD=x-changeme-x\nUMAMI_PRESSROOM_PASSWORD=abc\n");
        Result r = deploy(0);
        assertEquals(0, r.exit(), r.out());
    }
}
