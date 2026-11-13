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

import java.sql.Timestamp;

import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.search.SearchSpec;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.business.runnable.ThreadManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
        // background rebuild through pressroom.getThreadManager() -- stubbed so
        // that reaches a real (no-op) mock rather than a bare
        // mock(Weblogger.class)'s null default.
        Weblogger pressroom = mock(Weblogger.class);
        when(pressroom.getThreadManager()).thenReturn(mock(ThreadManager.class));

        LuceneIndexManager manager = new LuceneIndexManager(pressroom);
        setField(manager, "indexDir", bogusIndexDir);
        setField(manager, "indexConsistencyMarker", tempDir.resolve(".index-inconsistent").toFile());

        assertDoesNotThrow(manager::initialize,
                "a failed mkdirs() must be logged, not thrown, and initialize() must still return "
                        + "having done everything it safely can");
    }

    // ------------------------------------------------------- blog rename

    private final java.util.List<Runnable> scheduled = new java.util.ArrayList<>();

    /** A manager over a private index whose operations are recorded, then run on the calling thread. */
    private LuceneIndexManager renameFixture(Path indexDir, Weblog blog, WeblogEntry post) throws Exception {
        Weblogger pressroom = mock(Weblogger.class);
        PassthroughRenderer.install(pressroom);
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        when(pressroom.getWeblogEntryManager()).thenReturn(entries);
        when(entries.getWeblogEntries(any())).thenReturn(java.util.List.of(post));
        when(entries.getWeblogEntry(post.getId())).thenReturn(post);
        WeblogManager weblogs = mock(WeblogManager.class);
        when(weblogs.getWeblog(blog.getId())).thenReturn(blog);
        when(weblogs.getWeblogs(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(java.util.List.of(blog));
        when(pressroom.getWeblogManager()).thenReturn(weblogs);
        ThreadManager threads = mock(ThreadManager.class);
        doAnswer(inv -> {
            Runnable op = inv.getArgument(0);
            scheduled.add(op);
            op.run();
            return null;
        }).when(threads).executeInBackground(any(Runnable.class));
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(threads).executeInForeground(any(Runnable.class));
        when(pressroom.getThreadManager()).thenReturn(threads);

        LuceneIndexManager manager = new LuceneIndexManager(pressroom);
        setField(manager, "indexDir", indexDir.toString());
        setField(manager, "indexConsistencyMarker", new File(indexDir.toFile(), ".index-inconsistent"));
        manager.initialize();
        manager.addEntryIndexOperation(post);
        scheduled.clear();
        return manager;
    }

    private static Weblog blog(String name) {
        Weblog blog = new Weblog();
        blog.setId("rename-blog-id");
        blog.setHandle("renameblog");
        blog.setName(name);
        return blog;
    }

    private static WeblogEntry post(Weblog blog) {
        WeblogCategory cat = new WeblogCategory();
        cat.setName("General");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("rename-post");
        entry.setWebsite(blog);
        entry.setCategory(cat);
        entry.setTitle("A post");
        entry.setText("ordinary words only");
        entry.setLocale("en");
        entry.setCreatorUserName("nobody");
        entry.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        Timestamp past = new Timestamp(System.currentTimeMillis() - 60_000);
        entry.setPubTime(past);
        entry.setUpdateTime(past);
        return entry;
    }

    private static boolean rebuilds(java.util.List<Runnable> ops) {
        return ops.stream().anyMatch(op -> op instanceof RebuildWebsiteIndexOperation);
    }

    @Test
    void firstSightOfABlogSchedulesNothing(@TempDir Path indexDir) throws Exception {
        Weblog blog = blog("Quiet Pond");
        LuceneIndexManager manager = renameFixture(indexDir, blog, post(blog));

        manager.noteWeblog(blog);

        assertTrue(scheduled.isEmpty(), "" + scheduled);
        manager.shutdown();
    }

    @Test
    void unchangedBlogSchedulesNothing(@TempDir Path indexDir) throws Exception {
        Weblog blog = blog("Quiet Pond");
        LuceneIndexManager manager = renameFixture(indexDir, blog, post(blog));

        manager.noteWeblog(blog);
        manager.noteWeblog(blog);

        assertTrue(scheduled.isEmpty(), "" + scheduled);
        manager.shutdown();
    }

    @Test
    void renamedBlogIsReindexed(@TempDir Path indexDir) throws Exception {
        Weblog blog = blog("Quiet Pond");
        WeblogEntry post = post(blog);
        LuceneIndexManager manager = renameFixture(indexDir, blog, post);
        manager.noteWeblog(blog);
        assertTrue(manager.find(new SearchSpec("heron", SearchSpec.Scope.BLOG, "renameblog", null, 0, 10),
                mock(URLStrategy.class)).hits().isEmpty());

        blog.setName("Heron Pond");
        manager.noteWeblog(blog);

        assertTrue(rebuilds(scheduled), "" + scheduled);
        assertEquals(1, manager.find(new SearchSpec("heron", SearchSpec.Scope.BLOG, "renameblog", null, 0, 10),
                mock(URLStrategy.class)).hits().size(), "the blog's new name finds its posts");
        manager.shutdown();
    }

    @Test
    void aChangedTaglineIsReindexedToo(@TempDir Path indexDir) throws Exception {
        Weblog blog = blog("Quiet Pond");
        LuceneIndexManager manager = renameFixture(indexDir, blog, post(blog));
        manager.noteWeblog(blog);

        blog.setTagline("herons");
        manager.noteWeblog(blog);

        assertTrue(rebuilds(scheduled), "" + scheduled);
        manager.shutdown();
    }

    /** The thread manager can refuse work (a rejected execution is a RuntimeException); a save must not see it. */
    @Test
    void aFailingRebuildNeverFailsTheSave(@TempDir Path indexDir) throws Exception {
        Weblog blog = blog("Quiet Pond");
        LuceneIndexManager manager = renameFixture(indexDir, blog, post(blog));
        manager.noteWeblog(blog);
        ThreadManager threads = manager.weblogger().getThreadManager();
        doThrow(new java.util.concurrent.RejectedExecutionException("pool is full"))
                .when(threads).executeInBackground(any(Runnable.class));

        blog.setName("Heron Pond");
        assertDoesNotThrow(() -> manager.noteWeblog(blog));
        assertDoesNotThrow(() -> manager.noteWeblog(null));
        manager.shutdown();
    }

    // No test drives initialize()'s createNewFile()-returns-false branch
    // (LuceneIndexManager.java:152) directly: reaching it requires
    // indexConsistencyMarker.exists() to be false at the enclosing check yet
    // createNewFile() to find it already there moments later -- a genuine
    // TOCTOU race with another process/thread, not something a single
    // sequential test can force deterministically.
}
