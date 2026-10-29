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
                  "volume inspect "*) grep -qxF -- "$last" "$STUB_VOLUMES"; exit $? ;;
                  "volume create "*) echo "$last" >> "$STUB_VOLUMES"; echo "$last"; exit 0 ;;
                  *"exec -T backup /app/backup/backup.sh"*) exit "${STUB_BACKUP_RC:-0}" ;;
                  *"exec -T postgres psql"*)
                    case "$in" in
                      *pg_authid*) echo "${STUB_PASSWORD_KIND:-SCR}" ;;
                      *"count(*)"*)
                        if [[ "$in" == *pressroom_user* && -n "${STUB_LOST_ROW:-}" ]]; then echo 6; else echo 7; fi ;;
                    esac ;;
                esac
                exit 0
                """);
        env.put("STUB_LOG", log().toString());
        env.put("STUB_VOLUMES", volumesFile().toString());
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
        List<String> copies = lines(calls, "docker run --rm");
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
        assertTrue(calls.indexOf("docker run --rm") > down, "volumes copied only once the old stack is down");
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
        assertTrue(r.out().contains("rollback-pressroom.sh"), r.out());
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
}
