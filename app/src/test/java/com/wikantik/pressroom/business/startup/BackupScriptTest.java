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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shipped deploy/backup/backup.sh with stub {@code pg_dump},
 * {@code psql}, {@code pg_restore} and {@code tar} first on PATH: each
 * records its arguments, the dumping ones write the file they were asked
 * for, and the test steers their exit codes with variables.
 */
class BackupScriptTest {

    private static final Path BACKUP = Paths.get("../deploy/backup/backup.sh").toAbsolutePath();

    @TempDir Path dir;
    private Path backups;
    private final Map<String, String> env = new HashMap<>();

    @BeforeEach
    void stubs() throws Exception {
        backups = Files.createDirectories(dir.resolve("backups"));
        Path bin = dir.resolve("bin");
        ScriptRun.stub(bin, "pg_dump", """
                echo "pg_dump $*" >> "$STUB_LOG"
                while [[ $# -gt 0 ]]; do
                  if [[ "$1" == "-f" ]]; then echo DUMP > "$2"; fi
                  shift
                done
                """);
        ScriptRun.stub(bin, "psql", """
                echo "psql $*" >> "$STUB_LOG"
                [[ "${STUB_PSQL_RC:-0}" -eq 0 ]] || exit "${STUB_PSQL_RC}"
                echo "${STUB_PSQL_OUT-1}"
                """);
        ScriptRun.stub(bin, "pg_restore", """
                echo "pg_restore $*" >> "$STUB_LOG"
                exit "${STUB_RESTORE_RC:-0}"
                """);
        ScriptRun.stub(bin, "tar", """
                echo "tar $*" >> "$STUB_LOG"
                for a in "$@"; do
                  if [[ "$a" == *.tar.gz.tmp ]]; then echo TAR > "$a"; fi
                done
                if [[ "${STUB_TAR_RC:-0}" -ne 0 ]]; then
                  echo "tar: data/search-index/_3.cfs: file changed as we read it" >&2
                fi
                exit "${STUB_TAR_RC:-0}"
                """);
        env.put("STUB_LOG", log().toString());
        env.put("BACKUP_DIR", backups.toString());
        env.put("POSTGRES_USER", "pressroom");
        env.put("POSTGRES_PASSWORD", "secret");
        env.put("POSTGRES_DB", "pressroom");
    }

    private Path log() {
        return dir.resolve("calls.log");
    }

    private ScriptRun.Result backup() throws Exception {
        return ScriptRun.run(dir, dir.resolve("bin"), env, BACKUP);
    }

    private List<String> published() throws Exception {
        try (Stream<Path> files = Files.list(backups)) {
            return files.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aGoodRunPublishesEveryDumpAndTheArchive() throws Exception {
        ScriptRun.Result r = backup();

        assertEquals(0, r.exit(), r.out());
        List<String> names = published();
        assertEquals(4, names.size(), names.toString());
        assertTrue(names.stream().anyMatch(n -> n.matches("pressroom-\\d{8}T\\d{6}Z\\.dump")), names.toString());
        assertTrue(names.stream().anyMatch(n -> n.matches("umami-\\d{8}T\\d{6}Z\\.dump")), names.toString());
        assertTrue(names.stream().anyMatch(n -> n.matches("listmonk-\\d{8}T\\d{6}Z\\.dump")), names.toString());
        assertTrue(names.stream().anyMatch(n -> n.matches("volumes-\\d{8}T\\d{6}Z\\.tar\\.gz")), names.toString());
        assertTrue(r.out().contains("Backup complete"), r.out());
    }

    /**
     * Lucene merges segments while the app runs, so tar of a live volume
     * routinely meets a file that changed or vanished under it and exits 1.
     * Under {@code set -e} that killed the night's backup before its archive
     * was published and before rotation, and upgrade.sh then refused to run.
     * Exit 1 means "archive written, a file differed"; only 2 is fatal.
     */
    @Test
    void aFileChangedUnderTarStillPublishesTheArchiveAndRotates() throws Exception {
        env.put("STUB_TAR_RC", "1");

        ScriptRun.Result r = backup();

        assertEquals(0, r.exit(), r.out());
        assertTrue(published().stream().anyMatch(n -> n.endsWith(".tar.gz")), published().toString());
        assertTrue(r.out().contains("Rotating"), r.out());
        assertTrue(r.out().contains("Backup complete"), r.out());
    }

    @Test
    void aFatalTarErrorFailsTheBackupAndPublishesNoArchive() throws Exception {
        env.put("STUB_TAR_RC", "2");

        ScriptRun.Result r = backup();

        assertNotEquals(0, r.exit(), r.out());
        assertFalse(published().stream().anyMatch(n -> n.endsWith(".tar.gz")), published().toString());
        assertFalse(r.out().contains("Backup complete"), r.out());
    }

    /**
     * The search index is rebuilt from the database whenever it is missing
     * (LuceneIndexManager), so archiving it buys nothing and is the volume
     * whose files change under tar. Changed and removed files are not worth
     * a warning line every night.
     */
    @Test
    void tarSkipsTheRebuildableSearchIndexAndQuietsChangedFiles() throws Exception {
        ScriptRun.Result r = backup();

        assertEquals(0, r.exit(), r.out());
        String tar = ScriptRun.log(log()).lines().filter(l -> l.startsWith("tar ")).findFirst().orElseThrow();
        assertTrue(tar.contains(" data/mediafiles"), tar);
        assertTrue(tar.contains(" data/uploads"), tar);
        assertFalse(tar.contains("search-index"), tar);
        assertTrue(tar.contains("--warning=no-file-changed"), tar);
        assertTrue(tar.contains("--warning=no-file-removed"), tar);
    }

    /** A check that could not run is not a database that is absent. */
    @Test
    void aFailedExistenceQueryFailsTheBackupRatherThanSkippingTheDump() throws Exception {
        env.put("STUB_PSQL_RC", "2");

        ScriptRun.Result r = backup();

        assertNotEquals(0, r.exit(), r.out());
        assertFalse(r.out().contains("no such database"), r.out());
        assertFalse(r.out().contains("Backup complete"), r.out());
    }

    @Test
    void anAbsentServiceDatabaseIsSkipped() throws Exception {
        env.put("STUB_PSQL_OUT", "");

        ScriptRun.Result r = backup();

        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("umami -> none (no such database on this stack)"), r.out());
        assertFalse(published().stream().anyMatch(n -> n.startsWith("umami-")), published().toString());
    }

    /** pg_restore -l reads the archive's table of contents: a dump it cannot list is no backup. */
    @Test
    void everyDumpIsListedByPgRestoreBeforeItIsPublished() throws Exception {
        ScriptRun.Result r = backup();

        assertEquals(0, r.exit(), r.out());
        List<String> listed = ScriptRun.log(log()).lines().filter(l -> l.startsWith("pg_restore -l ")).toList();
        assertEquals(3, listed.size(), ScriptRun.log(log()));
        assertTrue(listed.stream().allMatch(l -> l.endsWith(".dump.tmp")), "checked before the rename: " + listed);
    }

    @Test
    void aDumpPgRestoreCannotReadFailsTheBackupAndIsNotPublished() throws Exception {
        env.put("STUB_RESTORE_RC", "1");

        ScriptRun.Result r = backup();

        assertNotEquals(0, r.exit(), r.out());
        assertFalse(published().stream().anyMatch(n -> n.endsWith(".dump")), published().toString());
    }
}
