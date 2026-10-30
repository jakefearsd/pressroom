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
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shipped deploy/cutover-pressroom.sh in a scratch host: the old
 * deploy directory holds the pre-rename .env and compose file, the release is
 * a file:// directory, and `docker` is a stub that records every call with
 * the stdin it was fed (so SQL is visible), keeps a list of the volumes that
 * exist, and answers the row counts and the role's password kind.
 *
 * <p>The fixture names the old layout on purpose; each such line carries the
 * inventory's keep marker.
 */
class CutoverScriptTest {

    private static final Path CUTOVER = Paths.get("../deploy/cutover-pressroom.sh").toAbsolutePath();

    private static final String OLD_ENV = String.join("\n",
            "# Production environment",
            "SITE_DOMAIN=blog.example.com",
            "",
            "IMAGE_VERSION=0.4.2",
            "export ROLLER_A=1", // rebrand:keep
            "ROLLER_SITE_NAME=\"My roller site\"", // rebrand:keep
            "ROLLER_MAIL_FROM=roller@example.com", // rebrand:keep
            "ROLLER_DATABASE_JDBC_CONNECTIONURL=jdbc:postgresql://postgres:5432/rollerdb?ApplicationName=roller", // rebrand:keep
            "ROLLER_DATABASE_JDBC_USERNAME=roller", // rebrand:keep
            "ROLLER_DATABASE_JDBC_PASSWORD=s3cret", // rebrand:keep
            "# ROLLER_COMMENTED=1", // rebrand:keep
            "MY_ROLLER_X=lookalike", // rebrand:keep
            "POSTGRES_DB=rollerdb", // rebrand:keep
            "POSTGRES_USER=roller", // rebrand:keep
            "POSTGRES_PASSWORD=s3cret",
            "UMAMI_APP_SECRET='quoted value'",
            "");

    /** OLD_ENV after the rewrite: key names and the database values move, every other byte stays. */
    private static final String NEW_ENV = String.join("\n",
            "# Production environment",
            "SITE_DOMAIN=blog.example.com",
            "",
            "IMAGE_VERSION=0.5.0",
            "export PRESSROOM_A=1",
            "PRESSROOM_SITE_NAME=\"My roller site\"", // rebrand:keep
            "PRESSROOM_MAIL_FROM=roller@example.com", // rebrand:keep
            "PRESSROOM_DATABASE_JDBC_CONNECTIONURL=jdbc:postgresql://postgres:5432/pressroom?ApplicationName=roller", // rebrand:keep
            "PRESSROOM_DATABASE_JDBC_USERNAME=pressroom",
            "PRESSROOM_DATABASE_JDBC_PASSWORD=s3cret",
            "# ROLLER_COMMENTED=1", // rebrand:keep
            "MY_ROLLER_X=lookalike", // rebrand:keep
            "POSTGRES_DB=pressroom",
            "POSTGRES_USER=pressroom",
            "POSTGRES_PASSWORD=s3cret",
            "UMAMI_APP_SECRET='quoted value'",
            "");

    private static final String SECRET = "s3cret";

    private static final List<String> SUFFIXES = List.of("pgdata", "mediafiles", "search-index", "uploads", "backups");

    @TempDir Path dir;
    private Path oldDir;
    private Path newDir;
    private Path release;
    private final Map<String, String> env = new HashMap<>();

    @BeforeEach
    void layOut() throws Exception {
        oldDir = Files.createDirectories(dir.resolve("host/roller")); // rebrand:keep
        newDir = dir.resolve("host/pressroom");
        Files.writeString(oldDir.resolve(".env"), OLD_ENV);
        Files.writeString(oldDir.resolve("docker-compose.prod.yml"), "old compose\n");
        Files.writeString(oldDir.resolve("deploy.sh"), "old deploy\n");

        release = Files.createDirectories(dir.resolve("release/v0.5.0"));
        Files.writeString(release.resolve("docker-compose.prod.yml"), """
                services:
                  postgres:
                    image: postgres:18@sha256:feedface
                """);
        Files.writeString(release.resolve("deploy.sh"), """
                #!/bin/bash
                echo "deploy.sh $* project=${COMPOSE_PROJECT_NAME:-} $(grep ^IMAGE_VERSION= .env)" >> "$STUB_LOG"
                """);
        Files.writeString(release.resolve("env.example"), "IMAGE_VERSION=0.5.0\n");
        Files.writeString(release.resolve("upgrade.sh"), "# upgrade\n");

        StringBuilder volumes = new StringBuilder();
        for (String s : SUFFIXES) {
            volumes.append("roller_roller-").append(s).append('\n'); // rebrand:keep
        }
        volumes.append("roller_caddy-data\nroller_caddy-config\n"); // rebrand:keep
        Files.writeString(volumesFile(), volumes.toString());

        ScriptRun.stub(dir.resolve("bin"), "docker", """
                in=""
                if [[ ! -t 0 ]]; then in=$(cat); fi
                args="$*"
                echo "docker ${args//$'\\n'/ } <<< ${in//$'\\n'/ }" >> "$STUB_LOG"
                last="${*: -1}"
                case "$*" in
                  "info "*) echo "$STUB_STATE" ;;
                  *"--entrypoint du "*) printf '%s\t/v\n' "${STUB_VOLUME_KB:-100}" ;;
                  *"exec -T backup du -ck "*) printf '%s\ttotal\n' "${STUB_BACKUP_KB:-50}" ;;
                  "volume inspect "*) grep -qxF -- "$last" "$STUB_VOLUMES"; exit $? ;;
                  "volume create "*) echo "$last" >> "$STUB_VOLUMES"; echo "$last"; exit 0 ;;
                  *"exec -T backup /app/backup/backup.sh"*) touch "$STUB_STATE/backed-up"; exit "${STUB_BACKUP_RC:-0}" ;;
                  *"exec -T backup ls -1 /backups"*)
                    echo "roller-20261001T030000Z.dump"  # rebrand:keep
                    if [[ -e "$STUB_STATE/backed-up" ]]; then
                      echo "roller-20261029T120000Z.dump"  # rebrand:keep
                      echo "umami-20261029T120000Z.dump"
                      echo "listmonk-20261029T120000Z.dump"
                      echo "volumes-20261029T120000Z.tar.gz"
                    fi ;;
                  *" cp backup:/backups/"*)
                    if [[ -n "${STUB_FAIL_COPY:-}" && "$*" == *volumes-* ]]; then exit 1; fi
                    echo "copy of ${*: -2:1}" > "$last" ;;
                  *" down"*) touch "$STUB_STATE/down" ;;
                  *" ps "*) echo "ghcr.io/jakefearsd/pressroom:${STUB_APP_VERSION:-0.5.1}" ;;
                  *"exec -T postgres psql"*)
                    if [[ -n "${STUB_FAIL_RENAME:-}" && "$in" == *"ALTER DATABASE"* ]]; then exit 1; fi
                    if [[ -n "${STUB_NO_ROLE:-}" && "$*" == *"-U ${STUB_NO_ROLE} "* ]]; then exit 2; fi
                    if [[ -n "${STUB_FAIL_KIND:-}" && "$in" == *pg_authid* ]]; then exit 1; fi
                    case "$in" in
                      *pg_authid*) echo "${STUB_PASSWORD_KIND:-SCR}" ;;
                      *"count(*)"*)
                        if [[ "$in" == *pressroom_user* && -n "${STUB_LOST_ROW:-}" ]]; then echo 6
                        elif [[ "$in" == *website_event* && -n "${STUB_GREW:-}" && -e "$STUB_STATE/down" ]]; then echo 9
                        else echo 7; fi ;;
                    esac ;;
                esac
                exit 0
                """);
        env.put("STUB_LOG", log().toString());
        env.put("STUB_VOLUMES", volumesFile().toString());
        env.put("STUB_STATE", dir.toString());
        env.put("RELEASE_URL", dir.resolve("release").toUri().toString());
    }

    private Path log() {
        return dir.resolve("calls.log");
    }

    private Path volumesFile() {
        return dir.resolve("volumes.txt");
    }

    private String calls() throws Exception {
        return ScriptRun.log(log());
    }

    private ScriptRun.Result cutover(String... args) throws Exception {
        return ScriptRun.run(oldDir, dir.resolve("bin"), env, CUTOVER, args);
    }

    private Map<String, String> snapshot(Path d) throws Exception {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> s = Files.list(d)) {
            for (Path f : s.toList()) {
                files.put(f.getFileName().toString(), Files.readString(f));
            }
        }
        return files;
    }

    private static List<String> lines(String calls, String needle) {
        return calls.lines().filter(l -> l.contains(needle)).toList();
    }

    private void assertRefusedBeforeAnythingStopped(ScriptRun.Result r, Map<String, String> before) throws Exception {
        assertEquals(1, r.exit(), r.out());
        assertEquals(before, snapshot(oldDir), "the old directory is never modified");
        assertFalse(calls().contains(" down"), "nothing was stopped: " + calls());
        assertFalse(calls().contains("volume create"), calls());
    }

    @Test
    void rewritesOnlyKeysAndTheDatabaseValues() throws Exception {
        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        assertEquals(NEW_ENV, Files.readString(newDir.resolve(".env")));
        assertEquals(OLD_ENV, Files.readString(oldDir.resolve(".env")), "the old .env is never touched");
    }

    @Test
    void printsKeyNamesNeverValues() throws Exception {
        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("PRESSROOM_MAIL_FROM"), r.out());
        assertTrue(r.out().contains("PRESSROOM_SITE_NAME"), r.out());
        assertFalse(r.out().contains("roller@example.com"), r.out()); // rebrand:keep
        assertFalse(r.out().contains("My roller site"), r.out()); // rebrand:keep
        assertFalse(r.out().contains(SECRET), r.out());
        assertFalse(r.out().contains("quoted value"), r.out());
    }

    @Test
    void leavesTheOldDirectoryUntouchedAndDeploysTheNewOne() throws Exception {
        Map<String, String> before = snapshot(oldDir);

        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        assertEquals(before, snapshot(oldDir));
        for (String f : List.of("docker-compose.prod.yml", "deploy.sh", "env.example", "upgrade.sh")) {
            assertTrue(Files.exists(newDir.resolve(f)), f + " fetched into the new directory");
        }
        assertTrue(Files.isExecutable(newDir.resolve("deploy.sh")));
        assertTrue(calls().contains("deploy.sh  project=pressroom IMAGE_VERSION=0.5.0"), calls());
        assertEquals(8, Files.readAllLines(newDir.resolve("cutover-counts-before.txt")).size());
        assertEquals(8, Files.readAllLines(newDir.resolve("cutover-counts-after.txt")).size());
    }

    @Test
    void takesItsFilesFromADirectoryWhenGivenOne() throws Exception {
        env.put("RELEASE_URL", "file:///nonexistent");

        ScriptRun.Result r = cutover("0.5.0", "--files-from", release.toString());

        assertEquals(0, r.exit(), r.out());
        assertTrue(Files.readString(newDir.resolve("docker-compose.prod.yml")).contains("feedface"));
    }

    @Test
    void refusesWhenTheNewDirectoryExists() throws Exception {
        Files.createDirectories(newDir);
        Map<String, String> before = snapshot(oldDir);

        ScriptRun.Result r = cutover("0.5.0");

        assertRefusedBeforeAnythingStopped(r, before);
        assertTrue(r.out().contains("Refusing") && r.out().contains("already exists"), r.out());
        assertEquals("", calls());
        assertTrue(r.out().contains(newDir.getFileName().toString()), r.out());
    }

    @Test
    void refusesWhenNotOnZeroFour() throws Exception {
        Files.writeString(oldDir.resolve(".env"), OLD_ENV.replace("IMAGE_VERSION=0.4.2", "IMAGE_VERSION=0.3.2"));
        Map<String, String> before = snapshot(oldDir);

        ScriptRun.Result r = cutover("0.5.0");

        assertRefusedBeforeAnythingStopped(r, before);
        assertTrue(r.out().contains("Refusing") && r.out().contains("0.4.x"), r.out());
        assertEquals("", calls());
        assertFalse(Files.exists(newDir));
    }

    @Test
    void refusesWhenATargetVolumeAlreadyExists() throws Exception {
        Files.writeString(volumesFile(), "pressroom_pressroom-uploads\n", StandardOpenOption.APPEND);
        Map<String, String> before = snapshot(oldDir);

        ScriptRun.Result r = cutover("0.5.0");

        assertRefusedBeforeAnythingStopped(r, before);
        assertTrue(r.out().contains("Refusing") && r.out().contains("already exists"), r.out());
        assertTrue(r.out().contains("pressroom_pressroom-uploads"), r.out());
        assertFalse(Files.exists(newDir), "refused before it began");
    }

    @Test
    void refusesARoleOrDatabaseItWasNotBuiltFor() throws Exception {
        Files.writeString(oldDir.resolve(".env"), OLD_ENV.replace("POSTGRES_USER=roller", "POSTGRES_USER=blog")); // rebrand:keep
        Map<String, String> before = snapshot(oldDir);

        ScriptRun.Result r = cutover("0.5.0");

        assertRefusedBeforeAnythingStopped(r, before);
        assertTrue(r.out().contains("Refusing") && r.out().contains("POSTGRES_USER"), r.out());
        assertEquals("", calls());
    }

    @Test
    void copiesEveryVolumeAndNeverRemovesOne() throws Exception {
        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        String calls = calls();
        List<String> copies = lines(calls, "docker run --rm").stream().filter(l -> l.contains("cp -a")).toList();
        assertEquals(7, copies.size(), calls);
        for (String s : SUFFIXES) {
            String from = "-v roller_roller-" + s + ":/from:ro "; // rebrand:keep
            String to = "-v pressroom_pressroom-" + s + ":/to ";
            assertTrue(copies.stream().anyMatch(l -> l.contains(from) && l.contains(to)), s + ": " + calls);
        }
        for (String s : List.of("data", "config")) {
            String from = "-v roller_caddy-" + s + ":/from:ro "; // rebrand:keep
            String to = "-v pressroom_caddy-" + s + ":/to ";
            assertTrue(copies.stream().anyMatch(l -> l.contains(from) && l.contains(to)), s + ": " + calls);
        }
        assertTrue(copies.stream().allMatch(l -> l.contains("postgres:18@sha256:feedface")), calls);
        assertTrue(copies.stream().allMatch(l -> l.contains("cp -a /from/. /to/")), calls);
        assertFalse(calls.contains("volume rm"), calls);
        assertFalse(calls.contains("down -v"), calls);
        assertFalse(calls.contains("--volumes"), calls);
        String script = Files.readString(CUTOVER);
        assertFalse(script.contains("volume rm"), "the cutover never removes a volume");
        assertFalse(script.contains("down -v"));
        assertFalse(script.contains("rm -rf"));
    }

    /** The old app must be stopped before anything can run V041 on its data (ruling b). */
    @Test
    void stopsTheOldProjectBeforeTheNewOneStarts() throws Exception {
        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        String calls = calls();
        int down = calls.indexOf("compose -p roller -f docker-compose.prod.yml down"); // rebrand:keep
        assertTrue(down > 0, calls);
        assertTrue(calls.indexOf("exec -T backup /app/backup/backup.sh") < down, "backup first: " + calls);
        int firstUp = calls.indexOf(" up -d");
        assertTrue(firstUp > down, "no new-project up before the old project's down: " + calls);
        assertTrue(calls.indexOf("cp -a /from/. /to/") > down, "volumes copied only once the old stack is down");
        assertTrue(lines(calls.substring(0, down), "docker run --rm").stream().allMatch(l -> l.contains(":/v:ro ")),
                "before the down, volumes are only measured, read-only: " + calls);
        assertTrue(calls.indexOf("deploy.sh") > down, calls);
        assertTrue(lines(calls, " up -d").stream().allMatch(l -> l.contains("-p pressroom ")), calls);
    }

    @Test
    void pullsTheNewImagesBeforeStoppingTheOldStack() throws Exception {
        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        String calls = calls();
        List<String> pulls = lines(calls, " pull");
        assertEquals(1, pulls.size(), calls);
        assertTrue(pulls.get(0).contains("-p pressroom "), calls);
        assertTrue(calls.indexOf(" pull") < calls.indexOf(" down"), "pulled while the old site still serves: " + calls);
    }

    @Test
    void noPullSkipsThePullAndIsHandedToDeploySh() throws Exception {
        ScriptRun.Result r = cutover("0.5.0", "--no-pull");

        assertEquals(0, r.exit(), r.out());
        assertTrue(lines(calls(), " pull").isEmpty(), calls());
        assertTrue(calls().contains("deploy.sh --no-pull project=pressroom IMAGE_VERSION=0.5.0"), calls());
    }

    @Test
    void renamesTheRoleFromAnotherSuperuserSession() throws Exception {
        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        String calls = calls();
        int create = calls.indexOf("CREATE ROLE pressroom_cutover SUPERUSER LOGIN");
        int rename = calls.indexOf("ALTER ROLE roller RENAME TO pressroom"); // rebrand:keep
        int drop = calls.indexOf("DROP ROLE pressroom_cutover");
        assertTrue(create > 0 && create < rename && rename < drop, calls);
        assertTrue(lines(calls, "CREATE ROLE pressroom_cutover").get(0).contains("-U roller "), calls); // rebrand:keep
        String renameLine = lines(calls, "ALTER ROLE roller RENAME").get(0); // rebrand:keep
        assertTrue(renameLine.contains("-U pressroom_cutover -d postgres"), renameLine);
        assertTrue(renameLine.contains("ALTER DATABASE rollerdb RENAME TO pressroom"), renameLine); // rebrand:keep
        assertTrue(lines(calls, "DROP ROLE pressroom_cutover").get(0).contains("-U pressroom -d pressroom"), calls);
        assertTrue(rename > calls.indexOf("-p pressroom ") && rename < calls.indexOf("deploy.sh"), calls);
    }

    @Test
    void verifiesAPasswordLoginOverTcpBeforeDeploying() throws Exception {
        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        String calls = calls();
        List<String> logins = lines(calls, "-h postgres -U pressroom");
        assertEquals(1, logins.size(), calls);
        assertTrue(calls.indexOf(logins.get(0)) > calls.indexOf("DROP ROLE pressroom_cutover"), calls);
        assertTrue(calls.indexOf(logins.get(0)) < calls.indexOf("deploy.sh"), calls);
        assertFalse(calls.contains("ALTER ROLE pressroom PASSWORD"), "a SCRAM password survives a rename: " + calls);
    }

    /** PostgreSQL clears an MD5 password on rename, because the role name is its salt. */
    @Test
    void resetsAnMd5Password() throws Exception {
        env.put("STUB_PASSWORD_KIND", "md5");

        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        String calls = calls();
        int reset = calls.indexOf("ALTER ROLE pressroom PASSWORD");
        assertTrue(reset > calls.indexOf("ALTER ROLE roller RENAME"), calls); // rebrand:keep
        assertTrue(reset < calls.indexOf("DROP ROLE pressroom_cutover"), calls);
        assertTrue(lines(calls, "ALTER ROLE pressroom PASSWORD").get(0).contains("POSTGRES_PASSWORD"), calls);
        assertFalse(calls.contains(SECRET), "the password is in no argv and no stdin: " + calls);
        assertFalse(r.out().contains(SECRET), r.out());
    }

    @Test
    void resetsAPasswordTheRenameCleared() throws Exception {
        env.put("STUB_PASSWORD_KIND", "none");

        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        assertTrue(calls().contains("ALTER ROLE pressroom PASSWORD"), calls());
        assertFalse(calls().contains(SECRET), calls());
    }

    @Test
    void neverPutsThePasswordInAnyCall() throws Exception {
        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        assertFalse(calls().contains(SECRET), calls());
        assertFalse(r.out().contains(SECRET), r.out());
    }

    /** Ruling c: with no POSTGRES_USER in .env the old compose default named the role. */
    @Test
    void anAbsentPostgresUserMeansTheOldDefaultRole() throws Exception {
        String noUser = OLD_ENV.replace("POSTGRES_USER=roller\n", "").replace("POSTGRES_DB=rollerdb\n", ""); // rebrand:keep
        Files.writeString(oldDir.resolve(".env"), noUser);

        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        String calls = calls();
        assertTrue(lines(calls, "CREATE ROLE pressroom_cutover").get(0).contains("-U roller -d rollerdb"), calls); // rebrand:keep
        assertTrue(calls.contains("ALTER ROLE roller RENAME TO pressroom"), calls); // rebrand:keep
        String newEnv = Files.readString(newDir.resolve(".env"));
        assertFalse(newEnv.contains("POSTGRES_USER"), "the new compose default is the renamed role: " + newEnv);
        assertFalse(newEnv.contains("POSTGRES_DB"), newEnv);
    }

    @Test
    void recordsCountsFromTheOldNamesAndRecountsTheRenamedOnes() throws Exception {
        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        String before = Files.readString(newDir.resolve("cutover-counts-before.txt"));
        String after = Files.readString(newDir.resolve("cutover-counts-after.txt"));
        for (String t : List.of("roller_user", "weblog", "weblogentry", "roller_mediafile", // rebrand:keep
                "roller_reader_comment", "website", "website_event", "subscribers")) { // rebrand:keep
            assertTrue(before.contains(" " + t + " "), t + " in " + before);
        }
        for (String t : List.of("pressroom_user", "pressroom_mediafile", "pressroom_reader_comment")) {
            assertTrue(after.contains(" " + t + " "), t + " in " + after);
        }
        String calls = calls();
        assertTrue(calls.indexOf("FROM roller_user") < calls.indexOf(" down"), calls); // rebrand:keep
        assertTrue(calls.indexOf("FROM pressroom_user") > calls.indexOf("deploy.sh"), calls);
    }

    @Test
    void stopsWhenCountsDiffer() throws Exception {
        env.put("STUB_LOST_ROW", "1");

        ScriptRun.Result r = cutover("0.5.0");

        assertNotEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("pressroom_user"), r.out());
        assertTrue(r.out().contains("bash rollback-pressroom.sh --yes"), r.out());
    }

    @Test
    void aFailedBackupStopsBeforeAnythingIsStopped() throws Exception {
        env.put("STUB_BACKUP_RC", "1");
        Map<String, String> before = snapshot(oldDir);

        ScriptRun.Result r = cutover("0.5.0");

        assertRefusedBeforeAnythingStopped(r, before);
        assertTrue(r.out().contains("still serves"), r.out());
    }

    @Test
    void aVersionBeforeTheRenameIsRefused() throws Exception {
        ScriptRun.Result r = cutover("0.4.3");

        assertEquals(2, r.exit(), r.out());
        assertTrue(r.out().contains("0.5.0"), r.out());
        assertEquals("", calls());
        assertFalse(Files.exists(newDir));
    }

    /** Without --files-from the cutover downloads these four, so each must be published with the release. */
    @Test
    void everyFileItFetchesIsAReleaseAsset() throws Exception {
        String workflow = Files.readString(Paths.get("../.github/workflows/release.yml"));
        for (String f : List.of("docker-compose.prod.yml", "deploy.sh", "env.example", "upgrade.sh")) {
            assertTrue(workflow.contains("            release-assets/" + f + "\n"), f + " is not a release asset");
        }
    }

    // ---- fix round 1 -------------------------------------------------------

    /**
     * A step 8 failure must not leave a passwordless superuser behind. The
     * drop connects to database postgres, which always exists, and tries
     * the new role name, then the old: a partial rename (the database renamed,
     * the role not) leaves neither the old database nor a sure role to connect with.
     */
    @Test
    void theTemporarySuperuserIsDroppedWhenTheRenameFails() throws Exception {
        env.put("STUB_FAIL_RENAME", "1");
        env.put("STUB_NO_ROLE", "pressroom");

        ScriptRun.Result r = cutover("0.5.0");

        assertNotEquals(0, r.exit(), r.out());
        String calls = calls();
        int rename = calls.indexOf("ALTER ROLE roller RENAME"); // rebrand:keep
        List<String> drops = lines(calls.substring(rename), "DROP ROLE IF EXISTS pressroom_cutover");
        assertEquals(2, drops.size(), calls);
        assertTrue(drops.stream().allMatch(l -> l.contains("-p pressroom ") && l.contains(" -d postgres ")), calls);
        assertTrue(drops.get(0).contains("-U pressroom -d postgres"), drops.get(0));
        assertTrue(drops.get(1).contains("-U roller -d postgres"), "the role was never renamed: " + drops); // rebrand:keep
        assertFalse(calls.contains("deploy.sh"), calls);
    }

    @Test
    void theTemporarySuperuserIsDroppedWhenAStepAfterTheRenameFails() throws Exception {
        env.put("STUB_FAIL_KIND", "1");

        ScriptRun.Result r = cutover("0.5.0");

        assertNotEquals(0, r.exit(), r.out());
        List<String> drops = lines(calls(), "DROP ROLE IF EXISTS pressroom_cutover");
        assertFalse(drops.isEmpty(), calls());
        assertTrue(drops.get(0).contains("-p pressroom ") && drops.get(0).contains("-U pressroom -d postgres"),
                drops.get(0));
    }

    @Test
    void aCleanRunNeedsNoCleanupDrop() throws Exception {
        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        assertFalse(calls().contains("DROP ROLE IF EXISTS"), calls());
    }

    /** The pre-cutover backup outlives the old volumes' 30-day cleanup. */
    @Test
    void keepsThePreCutoverBackupOnTheHost() throws Exception {
        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        Path copy;
        try (Stream<Path> s = Files.list(dir.resolve("host"))) {
            copy = s.filter(p -> p.getFileName().toString().startsWith("pressroom-cutover-backup-"))
                    .findFirst().orElseThrow();
        }
        try (Stream<Path> s = Files.list(copy)) {
            assertEquals(List.of("listmonk-20261029T120000Z.dump", "roller-20261029T120000Z.dump", // rebrand:keep
                            "umami-20261029T120000Z.dump", "volumes-20261029T120000Z.tar.gz"),
                    s.map(p -> p.getFileName().toString()).sorted().toList(), "only that backup's files");
        }
        assertTrue(r.out().contains(copy.getFileName().toString()), r.out());
        String calls = calls();
        assertTrue(calls.indexOf(" cp backup:/backups/") > calls.indexOf("backup.sh"), calls);
        assertTrue(calls.indexOf(" cp backup:/backups/") < calls.indexOf(" down"), calls);
    }

    /** The live site writes between the count and the stop, and after the new one starts. */
    @Test
    void aCountThatGrewIsReportedNotFailed() throws Exception {
        env.put("STUB_GREW", "1");

        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().lines().anyMatch(l -> l.contains("website_event") && l.contains("written since")), r.out());
    }

    @Test
    void theRenameSessionsRunInTheNewProject() throws Exception {
        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        String calls = calls();
        for (String needle : List.of("CREATE ROLE pressroom_cutover", "pg_authid", "ALTER ROLE roller RENAME", // rebrand:keep
                "DROP ROLE pressroom_cutover")) {
            List<String> found = lines(calls, needle);
            assertEquals(1, found.size(), needle + ": " + calls);
            assertTrue(found.get(0).contains("compose -p pressroom "), found.get(0));
        }
    }

    @Test
    void theOldProjectIsNeverTouchedAfterItsDown() throws Exception {
        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        String calls = calls();
        int down = calls.indexOf("compose -p roller -f docker-compose.prod.yml down"); // rebrand:keep
        String after = calls.substring(calls.indexOf('\n', down) + 1);
        assertEquals(List.of(), lines(after, "-p roller "), "no old-project exec or up after its down"); // rebrand:keep
    }

    @Test
    void aCrlfEnvKeepsItsLineEndings() throws Exception {
        Files.writeString(oldDir.resolve(".env"), OLD_ENV.replace("\n", "\r\n"));

        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        assertEquals(NEW_ENV.replace("\n", "\r\n"), Files.readString(newDir.resolve(".env")));
    }

    @Test
    void aQuotedImageVersionIsRead() throws Exception {
        Files.writeString(oldDir.resolve(".env"), OLD_ENV.replace("IMAGE_VERSION=0.4.2", "IMAGE_VERSION=\"0.4.2\""));

        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        assertTrue(Files.readString(newDir.resolve(".env")).contains("\nIMAGE_VERSION=0.5.0\n"));
    }

    /** upgrade.sh moves only a plain IMAGE_VERSION line, so the cutover writes one. */
    @Test
    void anExportedImageVersionBecomesALineUpgradeShCanMove() throws Exception {
        Files.writeString(oldDir.resolve(".env"), OLD_ENV.replace("IMAGE_VERSION=0.4.2", "export IMAGE_VERSION=0.4.2"));

        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        assertEquals(NEW_ENV, Files.readString(newDir.resolve(".env")));
        Path next = Files.createDirectories(dir.resolve("release/v0.5.1"));
        try (Stream<Path> files = Files.list(release)) {
            for (Path f : files.toList()) {
                Files.copy(f, next.resolve(f.getFileName()));
            }
        }
        ScriptRun.Result up = ScriptRun.run(newDir, dir.resolve("bin"), env,
                Paths.get("../deploy/upgrade.sh").toAbsolutePath(), "0.5.1", "--no-backup");
        assertEquals(0, up.exit(), up.out());
        assertTrue(Files.readString(newDir.resolve(".env")).contains("\nIMAGE_VERSION=0.5.1\n"));
    }

    /** One parser for IMAGE_VERSION and the database names, in both scripts. */
    @Test
    void bothScriptsReadEnvWithTheSameParser() throws Exception {
        String cutover = Files.readString(CUTOVER);
        String rollback = Files.readString(Paths.get("../deploy/rollback-pressroom.sh"));
        int start = cutover.indexOf("env_value() {");
        assertTrue(start > 0, "cutover has env_value");
        String parser = cutover.substring(start, cutover.indexOf("\n}\n", start) + 3);
        assertTrue(rollback.contains(parser), "rollback-pressroom.sh carries the same env_value");
    }

    @Test
    void aMissingOldVolumeNamesTheDaemonToo() throws Exception {
        Files.writeString(volumesFile(), "");

        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(1, r.exit(), r.out());
        assertTrue(r.out().contains("or the Docker daemon is unreachable"), r.out());
    }

    // ---- fix round 2 -------------------------------------------------------

    /** Two cutovers at once would race on the same new directory and volumes. */
    @Test
    void refusesWhileAnotherCutoverHoldsTheLock() throws Exception {
        Path holder = dir.resolve("holder.sh");
        Files.writeString(holder, """
                exec 9>"$1"
                flock -n 9 || exit 99
                bash "$2" 0.5.0
                """);
        ScriptRun.Result r = ScriptRun.run(oldDir, dir.resolve("bin"), env, holder,
                dir.resolve("host/.pressroom-cutover.lock").toString(), CUTOVER.toString());

        assertEquals(1, r.exit(), r.out());
        assertTrue(r.out().contains("Refusing") && r.out().contains("another cutover"), r.out());
        assertEquals("", calls());
        assertFalse(Files.exists(newDir));
    }

    @Test
    void takesTheLockBesideTheOldDirectory() throws Exception {
        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        assertTrue(Files.exists(dir.resolve("host/.pressroom-cutover.lock")));
    }

    /** The copy-out and the volume copies must fit before anything stops. */
    @Test
    void refusesWithoutRoomForTheCopiesBeforeAnythingStops() throws Exception {
        env.put("STUB_VOLUME_KB", "999999999999");
        Map<String, String> before = snapshot(oldDir);

        ScriptRun.Result r = cutover("0.5.0");

        assertRefusedBeforeAnythingStopped(r, before);
        assertTrue(r.out().contains("free"), r.out());
        assertFalse(calls().contains(" cp backup:/backups/"), calls());
        assertFalse(hasBackupCopy(), "no copy-out was begun");
    }

    @Test
    void measuresTheBackupAndEveryOldVolume() throws Exception {
        ScriptRun.Result r = cutover("0.5.0");

        assertEquals(0, r.exit(), r.out());
        String calls = calls();
        List<String> sizes = lines(calls, "--entrypoint du ");
        assertEquals(7, sizes.size(), calls);
        assertTrue(sizes.stream().allMatch(l -> l.contains(":/v:ro ")), "measured read-only: " + sizes);
        assertTrue(calls.contains("exec -T backup du -ck /backups/"), calls);
        assertTrue(calls.indexOf("--entrypoint du ") < calls.indexOf(" cp backup:/backups/"), calls);
    }

    @Test
    void aFailedCopyOutRemovesThePartialCopyAndNamesIt() throws Exception {
        env.put("STUB_FAIL_COPY", "1");
        Map<String, String> before = snapshot(oldDir);

        ScriptRun.Result r = cutover("0.5.0");

        assertRefusedBeforeAnythingStopped(r, before);
        assertFalse(hasBackupCopy(), "the partial copy is removed");
        assertTrue(r.out().contains("pressroom-cutover-backup-"), r.out());
        assertTrue(r.out().contains("still serves"), r.out());
    }

    private boolean hasBackupCopy() throws Exception {
        try (Stream<Path> s = Files.list(dir.resolve("host"))) {
            return s.anyMatch(p -> p.getFileName().toString().startsWith("pressroom-cutover-backup-"));
        }
    }
}
