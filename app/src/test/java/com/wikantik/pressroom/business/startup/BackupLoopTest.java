/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shipped {@code deploy/backup/loop.sh} with its two neighbours
 * ({@code backup.sh}, {@code umami-retention.sh}) replaced by stubs that log
 * their turn, and with {@code date} and {@code sleep} stubbed so the loop
 * sees {@code BACKUP_HOUR} at once and is stopped on its second sleep. The
 * loop resolves both scripts relative to itself, as it does in the image at
 * {@code /app/backup/}.
 */
class BackupLoopTest {

    private static final Path LOOP = Paths.get("../deploy/backup/loop.sh");

    @TempDir
    Path tmp;

    private ScriptRun.Result runLoop(int backupExit, int retentionExit) throws IOException, InterruptedException {
        Path dir = Files.createDirectories(tmp.resolve("backup"));
        Path loop = dir.resolve("loop.sh");
        Files.copy(LOOP, loop, StandardCopyOption.REPLACE_EXISTING);
        ScriptRun.stub(dir, "backup.sh", "echo backup >> \"$LOG\"\nexit " + backupExit + "\n");
        ScriptRun.stub(dir, "umami-retention.sh", "echo retention >> \"$LOG\"\nexit " + retentionExit + "\n");

        Path bin = tmp.resolve("bin");
        ScriptRun.stub(bin, "date", """
                case "$*" in
                    *%H*) echo 03 ;;
                    *%Y-%m-%d*) echo 2026-11-02 ;;
                    *) echo 2026-11-02T03:00:00Z ;;
                esac
                """);
        // The second sleep proves the loop came round again; stop it there.
        ScriptRun.stub(bin, "sleep", """
                echo sleep >> "$LOG"
                if [[ "$(grep -c sleep "$LOG")" -ge 2 ]]; then kill -TERM "$PPID"; fi
                """);
        return ScriptRun.run(tmp, bin, Map.of("LOG", log().toString(), "BACKUP_HOUR", "3"), loop);
    }

    private Path log() {
        return tmp.resolve("calls.log");
    }

    @Test
    void retentionRunsAfterTheBackupOncePerDay() throws Exception {
        ScriptRun.Result result = runLoop(0, 0);

        assertEquals("backup\nretention\nsleep\nsleep\n", ScriptRun.log(log()), result.out());
    }

    @Test
    void aFailingRetentionRunLeavesTheLoopRunning() throws Exception {
        ScriptRun.Result result = runLoop(0, 1);

        assertEquals("backup\nretention\nsleep\nsleep\n", ScriptRun.log(log()),
                "the loop sleeps and comes round again, and does not re-run the day's backup: " + result.out());
        assertTrue(result.out().contains("Umami retention failed"), result.out());
        assertFalse(result.out().contains("Backup failed"), result.out());
    }

    /** No fresh backup holds the rows, so nothing is deleted that day. */
    @Test
    void aFailedBackupSkipsRetention() throws Exception {
        ScriptRun.Result result = runLoop(1, 0);

        assertFalse(ScriptRun.log(log()).contains("retention"), ScriptRun.log(log()) + result.out());
        assertTrue(result.out().contains("Backup failed"), result.out());
    }
}
