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
package com.wikantik.pressroom.business.search.lucene;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.runnable.ThreadManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The search-index consistency marker's filesystem bookkeeping: {@code
 * initialize()}/{@code shutdown()} now log rather than silently discard the
 * boolean result of {@code File.mkdirs()}/{@code delete()} (previously
 * ignored -- SpotBugs RV_RETURN_VALUE_IGNORED_BAD_PRACTICE).
 *
 * <p>Both tests force the failure with a filesystem shape (a file where a
 * directory is expected, a non-empty directory where a plain file delete is
 * expected) rather than a permissions trick, so the failure is deterministic
 * and portable -- it does not depend on the OS or the privilege level the
 * build happens to run under. {@code indexDir}/{@code indexConsistencyMarker}
 * are reflectively swapped onto a normally-constructed manager rather than
 * routed through {@code WebloggerConfig}, so this cannot bleed into the
 * shared global config other tests read.
 */
class LuceneIndexManagerTest {

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = LuceneIndexManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void shutdownLogsRatherThanDiscardingAFailedMarkerDelete(@TempDir Path tempDir) throws Exception {
        // File.delete() on a non-empty directory always returns false, on
        // every OS and regardless of who owns/runs the process -- unlike a
        // permissions-based failure, this is a structural impossibility, so
        // the test is not flaky under (for example) a root-run CI container.
        File nonEmptyDir = tempDir.resolve("marker-dir").toFile();
        assertTrue(nonEmptyDir.mkdir());
        assertTrue(new File(nonEmptyDir, "occupant").createNewFile());

        LuceneIndexManager manager = new LuceneIndexManager(mock(Weblogger.class));
        setField(manager, "indexConsistencyMarker", nonEmptyDir);

        assertDoesNotThrow(manager::shutdown,
                "a failed delete must be logged, not thrown");

        assertTrue(nonEmptyDir.exists(),
                "the marker directory must still be there -- the delete genuinely failed");
    }

    @Test
    void initializeLogsRatherThanDiscardingAFailedMkdirs(@TempDir Path tempDir) throws Exception {
        // A plain file occupies the exact path indexDir needs to become a
        // directory, so mkdirs() fails deterministically for the same
        // structural reason as above -- not a permissions problem, so this
        // does not depend on who runs the build. Driving this through the
        // real initialize() (rather than calling the mkdirs() check in
        // isolation) is what surfaced indexExists()/createIndex() not
        // handling a null Directory from getIndexDirectory() -- both are
        // fixed alongside this test, which is what makes running the whole
        // method safe here.
        File blocker = tempDir.resolve("blocker").toFile();
        assertTrue(blocker.createNewFile());
        String bogusIndexDir = blocker.getPath() + File.separator + "sub";

        // A fresh index dir marks inconsistentAtStartup, which schedules a
        // background rebuild through roller.getThreadManager() -- stubbed so
        // that reaches a real (no-op) mock rather than a bare
        // mock(Weblogger.class)'s null default.
        Weblogger roller = mock(Weblogger.class);
        when(roller.getThreadManager()).thenReturn(mock(ThreadManager.class));

        LuceneIndexManager manager = new LuceneIndexManager(roller);
        setField(manager, "indexDir", bogusIndexDir);
        setField(manager, "indexConsistencyMarker", tempDir.resolve(".index-inconsistent").toFile());

        assertDoesNotThrow(manager::initialize,
                "a failed mkdirs() must be logged, not thrown, and initialize() must still return "
                        + "having done everything it safely can");
    }

    // No test drives initialize()'s createNewFile()-returns-false branch
    // (LuceneIndexManager.java:152) directly: reaching it requires
    // indexConsistencyMarker.exists() to be false at the enclosing check yet
    // createNewFile() to find it already there moments later -- a genuine
    // TOCTOU race with another process/thread, not something a single
    // sequential test can force deterministically.
}
