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
package org.apache.roller.weblogger.business.startup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shipped deploy/upgrade.sh in a scratch deploy directory: the
 * release is a file:// directory standing in for the GitHub release, its
 * deploy.sh is a stub that records the IMAGE_VERSION it saw, and `docker` is
 * a stub that answers the backup and the running app image.
 */
class UpgradeScriptTest {

    private static final Path UPGRADE = Paths.get("../deploy/upgrade.sh").toAbsolutePath();

    private static final String ENV = """
            SITE_DOMAIN=blog.example.com
            IMAGE_VERSION=0.2.1
            POSTGRES_PASSWORD=secret
            """;

    @TempDir Path dir;
    private Path host;
    private Path release;
    private final Map<String, String> env = new HashMap<>();

    @BeforeEach
    void layOut() throws Exception {
        host = Files.createDirectories(dir.resolve("host"));
        Files.writeString(host.resolve(".env"), ENV);
        Files.writeString(host.resolve("docker-compose.prod.yml"), "old compose\n");
        Files.writeString(host.resolve("deploy.sh"), "old deploy\n");
        Files.writeString(host.resolve("env.example"), "IMAGE_VERSION=0.2.1\nSITE_DOMAIN=\n");

        release = Files.createDirectories(dir.resolve("release/v0.2.2"));
        Files.writeString(release.resolve("docker-compose.prod.yml"), "new compose\n");
        Files.writeString(release.resolve("deploy.sh"),
                "#!/bin/bash\necho \"deploy.sh $* $(grep ^IMAGE_VERSION= .env)\" >> \"$STUB_LOG\"\n");
        Files.writeString(release.resolve("env.example"),
                "IMAGE_VERSION=0.2.2\nSITE_DOMAIN=\nNEW_KEY=x\n# OPTIONAL_KEY=\n");

        ScriptRun.stub(dir.resolve("bin"), "docker", """
                echo "docker $*" >> "$STUB_LOG"
                case "$*" in
                  *"exec -T backup /app/backup/backup.sh"*) exit "${STUB_BACKUP_RC:-0}" ;;
                  *" ps "*) echo "ghcr.io/jakefearsd/roller:${STUB_APP_VERSION}" ;;
                esac
                exit 0
                """);
        env.put("STUB_LOG", log().toString());
        env.put("STUB_APP_VERSION", "0.2.2");
        env.put("RELEASE_URL", dir.resolve("release").toUri().toString());
    }

    private Path log() {
        return dir.resolve("calls.log");
    }

    private ScriptRun.Result upgrade(String... args) throws Exception {
        return ScriptRun.run(host, dir.resolve("bin"), env, UPGRADE, args);
    }

    private void assertNothingChanged(ScriptRun.Result r) throws Exception {
        assertNotEquals(0, r.exit(), r.out());
        assertEquals(ENV, Files.readString(host.resolve(".env")), r.out());
        assertEquals("old compose\n", Files.readString(host.resolve("docker-compose.prod.yml")));
        assertEquals("old deploy\n", Files.readString(host.resolve("deploy.sh")));
        assertFalse(ScriptRun.log(log()).contains("deploy.sh"), ScriptRun.log(log()));
        try (Stream<Path> files = Files.list(host)) {
            assertEquals(List.of(), files.map(f -> f.getFileName().toString())
                    .filter(n -> n.contains(".bak-") || n.startsWith(".upgrade")).toList());
        }
    }

    @Test
    void replacesTheDeployFilesBumpsTheImageVersionAndDeploys() throws Exception {
        ScriptRun.Result r = upgrade("0.2.2");

        assertEquals(0, r.exit(), r.out());
        assertEquals(ENV.replace("IMAGE_VERSION=0.2.1", "IMAGE_VERSION=0.2.2"),
                Files.readString(host.resolve(".env")));
        assertEquals("new compose\n", Files.readString(host.resolve("docker-compose.prod.yml")));
        assertTrue(Files.isExecutable(host.resolve("deploy.sh")));
        String calls = ScriptRun.log(log());
        assertTrue(calls.contains("deploy.sh  IMAGE_VERSION=0.2.2"), "deployed on the new version: " + calls);
        assertTrue(calls.indexOf("backup.sh") < calls.indexOf("deploy.sh"), "backup first: " + calls);
    }

    @Test
    void keepsTheReplacedFilesBesideTheNewOnes() throws Exception {
        ScriptRun.Result r = upgrade("0.2.2");

        assertEquals(0, r.exit(), r.out());
        try (Stream<Path> files = Files.list(host)) {
            List<String> backups = files.map(f -> f.getFileName().toString())
                    .filter(n -> n.contains(".bak-")).sorted().toList();
            assertEquals(4, backups.size(), backups.toString());
            Path envBackup = host.resolve(backups.stream().filter(n -> n.startsWith(".env.bak-")).findFirst()
                    .orElseThrow());
            assertEquals(ENV, Files.readString(envBackup));
        }
        assertTrue(r.out().contains("To roll back"), r.out());
    }

    @Test
    void aLeadingVOnTheVersionIsAccepted() throws Exception {
        ScriptRun.Result r = upgrade("v0.2.2");

        assertEquals(0, r.exit(), r.out());
        assertTrue(Files.readString(host.resolve(".env")).contains("IMAGE_VERSION=0.2.2\n"));
    }

    @Test
    void aVersionThatIsNotAReleaseIsRefused() throws Exception {
        ScriptRun.Result r = upgrade("latest");

        assertEquals(2, r.exit(), r.out());
        assertTrue(r.out().contains("Usage"), r.out());
        assertNothingChanged(r);
    }

    @Test
    void aReleaseMissingAFileChangesNothingAndTakesNoBackup() throws Exception {
        Files.delete(release.resolve("deploy.sh"));

        ScriptRun.Result r = upgrade("0.2.2");

        assertNothingChanged(r);
        assertFalse(ScriptRun.log(log()).contains("backup.sh"), ScriptRun.log(log()));
    }

    /** A backup that could not be taken is not a backup that was taken. */
    @Test
    void aFailedBackupStopsTheUpgradeBeforeAnythingChanges() throws Exception {
        env.put("STUB_BACKUP_RC", "1");

        ScriptRun.Result r = upgrade("0.2.2");

        assertNothingChanged(r);
        assertTrue(r.out().contains("--no-backup"), r.out());
    }

    @Test
    void noBackupSkipsIt() throws Exception {
        ScriptRun.Result r = upgrade("0.2.2", "--no-backup");

        assertEquals(0, r.exit(), r.out());
        assertFalse(ScriptRun.log(log()).contains("backup.sh"), ScriptRun.log(log()));
    }

    @Test
    void pruneIsHandedToDeploySh() throws Exception {
        ScriptRun.Result r = upgrade("0.2.2", "--prune");

        assertEquals(0, r.exit(), r.out());
        assertTrue(ScriptRun.log(log()).contains("deploy.sh --prune IMAGE_VERSION=0.2.2"), ScriptRun.log(log()));
    }

    @Test
    void anEnvWithNoImageVersionIsRefused() throws Exception {
        Files.writeString(host.resolve(".env"), ENV.replace("IMAGE_VERSION=0.2.1\n", ""));

        ScriptRun.Result r = upgrade("0.2.2");

        assertNotEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("IMAGE_VERSION"), r.out());
        assertFalse(ScriptRun.log(log()).contains("deploy.sh"), ScriptRun.log(log()));
    }

    /** New keys a release's env.example sets are named, so a new setting is not missed. */
    @Test
    void namesTheKeysTheNewEnvExampleSetsThatEnvLacks() throws Exception {
        ScriptRun.Result r = upgrade("0.2.2");

        assertEquals(0, r.exit(), r.out());
        String named = r.out().lines().filter(l -> l.contains("env.example sets keys")).findFirst().orElse("");
        assertTrue(named.endsWith(": NEW_KEY"), "a commented-out key is optional, a set one named: " + r.out());
    }

    /**
     * An IMAGE_VERSION exported in the operator's shell beats .env in
     * compose, so deploy.sh can come up healthy on the old image.
     */
    @Test
    void anAppStillOnAnotherImageFailsTheUpgrade() throws Exception {
        env.put("STUB_APP_VERSION", "0.2.1");

        ScriptRun.Result r = upgrade("0.2.2");

        assertNotEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("roller:0.2.1"), r.out());
        assertTrue(r.out().contains("To roll back"), r.out());
    }
}
