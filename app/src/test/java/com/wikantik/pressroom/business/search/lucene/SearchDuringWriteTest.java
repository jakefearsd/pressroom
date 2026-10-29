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
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.runnable.ThreadManager;
import com.wikantik.pressroom.business.search.SearchResultList;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A search never waits for an index write, and sees every write that has
 * completed.
 *
 * <p>Searches used to take the read side of a {@code ReentrantReadWriteLock}
 * whose write side every index write held across its whole IndexWriter
 * session -- open, add, commit, close. Under a steady stream of entry saves a
 * search therefore parked for about one write (~20 ms in the 2026-10-22
 * profile) before it could even look at the index. The first two tests hold
 * a write open mid-operation, on a latch inside the tier call it makes while
 * its writer is open, and require a concurrent search to answer within a
 * bound regardless; they failed by timing out against the lock.
 *
 * <p>The rest pin what must not be lost on the way: a write, a removal, and
 * a full rebuild are each visible to the first search that starts after the
 * operation returns, and many write/search cycles leave no superseded reader
 * holding index files open. Those are characterisation tests, written
 * against the locking implementation and passing there too: they are the
 * guarantees the lock-free one has to keep.
 *
 * <p>The tier is the inline-{@link ThreadManager} Mockito double the other
 * tests in this package use, over a {@code @TempDir} index; a blocked write
 * runs on its own thread.
 */
class SearchDuringWriteTest {

    private static final String WORD = "duringwriteword";
    private static final String HANDLE = "duringwriteblog";
    /** Far longer than any search takes; far shorter than the blocked write is held. */
    private static final long SEARCH_BOUND_SECONDS = 5;

    @TempDir
    Path indexDir;

    private WeblogEntryManager entries;
    private WeblogManager weblogs;
    private LuceneIndexManager manager;
    private Weblog blog;

    @BeforeEach
    void setUp() throws Exception {
        Weblogger pressroom = mock(Weblogger.class);
        entries = mock(WeblogEntryManager.class);
        weblogs = mock(WeblogManager.class);
        when(pressroom.getWeblogEntryManager()).thenReturn(entries);
        when(pressroom.getWeblogManager()).thenReturn(weblogs);
        when(pressroom.getUserManager()).thenReturn(mock(UserManager.class));
        ThreadManager inline = mock(ThreadManager.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(inline).executeInBackground(any(Runnable.class));
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(inline).executeInForeground(any(Runnable.class));
        when(pressroom.getThreadManager()).thenReturn(inline);

        blog = new Weblog();
        blog.setHandle(HANDLE);
        blog.setName(HANDLE);
        when(weblogs.getWeblog(blog.getId())).thenReturn(blog);

        manager = new LuceneIndexManager(pressroom);
        setField(manager, "indexDir", indexDir.toString());
        setField(manager, "indexConsistencyMarker", new File(indexDir.toFile(), ".index-inconsistent"));
        manager.initialize();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void aSearchDuringAnEntryWriteAnswersWithoutWaitingForIt() throws Exception {
        index(entry("first"));
        assertEquals(List.of("first"), titles(search()), "precondition");

        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        WeblogEntry second = entry("second");
        // ReIndexEntryOperation -- what every production save schedules --
        // re-fetches its entry inside the write, under the write lock: the
        // write is held right there.
        when(entries.getWeblogEntry("second")).thenAnswer(inv -> {
            writing.countDown();
            assertTrue(finish.await(30, TimeUnit.SECONDS), "the test never let the write finish");
            return second;
        });

        Thread writer = startWriter(() -> manager.addEntryReIndexOperation(second));
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS), "the write never started");

            assertEquals(List.of("first"), searchWithinBound(),
                    "a search during the write answers from the index as it was before it");
        } finally {
            finish.countDown();
            writer.join(Duration.ofSeconds(30));
        }
        assertFalse(writer.isAlive(), "the write never finished");
        assertNull(writerFailure.get(), "the write failed: " + writerFailure.get());
        assertEquals(List.of("first", "second"), sortedTitles(search()),
                "the first search after the write returned must see it");
    }

    @Test
    void aSearchDuringAFullRebuildAnswersWithoutWaitingForIt() throws Exception {
        index(entry("first"));
        assertEquals(List.of("first"), titles(search()), "precondition");

        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        WeblogEntry rebuilt = entry("rebuilt");
        // the rebuild fetches its entries with its writer open
        when(entries.getWeblogEntries(any())).thenAnswer(inv -> {
            writing.countDown();
            assertTrue(finish.await(30, TimeUnit.SECONDS), "the test never let the rebuild finish");
            return List.of(rebuilt);
        });

        Thread writer = startWriter(() -> manager.rebuildWeblogIndex(blog));
        try {
            assertTrue(writing.await(10, TimeUnit.SECONDS), "the rebuild never started");

            assertEquals(List.of("first"), searchWithinBound(),
                    "a search during the rebuild answers from the index as it was before it");
        } finally {
            finish.countDown();
            writer.join(Duration.ofSeconds(30));
        }
        assertFalse(writer.isAlive(), "the write never finished");
        assertNull(writerFailure.get(), "the rebuild failed: " + writerFailure.get());
        assertEquals(List.of("rebuilt"), titles(search()),
                "the first search after the rebuild returned must see exactly what it indexed");
    }

    @Test
    void aWriteIsVisibleToTheFirstSearchAfterItReturns() throws Exception {
        assertEquals(List.of(), titles(search()), "precondition: an empty index");

        for (int i = 0; i < 5; i++) {
            index(entry("entry-" + i));
            assertEquals(i + 1, search().getResults().size(), "write " + i + " was not visible");
        }
    }

    @Test
    void aRemovalIsVisibleToTheFirstSearchAfterItReturns() throws Exception {
        WeblogEntry gone = entry("gone");
        index(gone);
        index(entry("kept"));
        assertEquals(List.of("gone", "kept"), sortedTitles(search()), "precondition");

        manager.removeEntryIndexOperation(gone);

        assertEquals(List.of("kept"), titles(search()));
    }

    @Test
    void aRebuildIsVisibleToTheFirstSearchAfterItReturns() throws Exception {
        index(entry("stale"));
        assertEquals(List.of("stale"), titles(search()), "precondition");
        WeblogEntry fresh = entry("fresh");
        when(entries.getWeblogEntries(any())).thenReturn(List.of(fresh));

        manager.rebuildWeblogIndex(blog);

        assertEquals(List.of("fresh"), titles(search()));
    }

    /**
     * A write that dies with an {@link Error} after committing must still
     * refresh the searchers, or every search keeps answering without that
     * commit until some later write happens to refresh. The lock was always
     * released in a {@code finally}; the refresh must be too.
     */
    @Test
    void aWriteThatThrowsAnErrorAfterCommittingIsStillVisible() throws Exception {
        index(entry("first"));
        assertEquals(List.of("first"), titles(search()), "precondition");
        WeblogEntry second = entry("second");
        WriteToIndexOperation dying = new WriteToIndexOperation(manager) {
            @Override
            protected void doRun() {
                try {
                    beginWriting().addDocument(getDocument(second));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } finally {
                    endWriting();
                }
                throw new SimulatedError();
            }
        };

        assertThrows(SimulatedError.class, dying::run);

        assertFalse(manager.getWriteLock().isLocked(), "the Error left the index write-locked");
        assertEquals(List.of("first", "second"), sortedTitles(search()),
                "the committed write must be visible although the operation died");
    }

    private static final class SimulatedError extends Error {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Each write supersedes the reader searches use, and segment merges
     * delete the files superseded readers were reading. A reader that is
     * never closed keeps those deleted files mapped (and any it opened with
     * a descriptor, open), so after many cycles a leak shows as index files
     * the process still holds although the directory no longer has them.
     * Linux-only: it reads {@code /proc/self}.
     */
    @Test
    void manyWriteAndSearchCyclesLeaveNoDeletedIndexFileHeld() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")), "needs /proc (Linux)");
        int cycles = 60;
        for (int i = 0; i < cycles; i++) {
            index(entry("cycle-" + i));
            assertEquals(i + 1, search().getResults().size());
        }

        List<String> held = heldIndexFiles();
        assertTrue(held.stream().anyMatch(f -> !f.endsWith("(deleted)")),
                "the check is blind: the current reader's files should be held, but nothing under "
                        + indexDir + " is: " + held);
        assertEquals(List.of(), held.stream().filter(f -> f.endsWith("(deleted)")).toList(),
                "superseded readers still hold index files the writer has deleted");
    }

    // ---------------------------------------------------------------- helpers

    private final AtomicReference<Throwable> writerFailure = new AtomicReference<>();

    private interface Write {
        void run() throws Exception;
    }

    private Thread startWriter(Write write) {
        Thread writer = new Thread(() -> {
            try {
                write.run();
            } catch (Exception | AssertionError e) {
                writerFailure.set(e);
            }
        }, "during-write-writer");
        writer.start();
        return writer;
    }

    private List<String> searchWithinBound() throws Exception {
        CompletableFuture<List<String>> searching = CompletableFuture.supplyAsync(() -> {
            try {
                return titles(search());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            return searching.get(SEARCH_BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return fail("the search waited for the write in progress; it did not answer within "
                    + SEARCH_BOUND_SECONDS + " s");
        }
    }

    /** Index files this process holds mapped or open, as /proc reports them. */
    private List<String> heldIndexFiles() throws IOException {
        String dir = indexDir.toRealPath().toString() + File.separator;
        List<String> held = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("/proc/self/maps"))) {
            int at = line.indexOf(dir);
            if (at >= 0) {
                held.add(line.substring(at));
            }
        }
        try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd"))) {
            for (Path fd : fds.toList()) {
                try {
                    String target = Files.readSymbolicLink(fd).toString();
                    if (target.startsWith(dir)) {
                        held.add(target);
                    }
                } catch (IOException gone) {
                    // the descriptor closed while listing (the listing's own, say)
                }
            }
        }
        return held;
    }

    /** Indexes through ReIndexEntryOperation, the path every production save takes. */
    private void index(WeblogEntry entry) throws Exception {
        manager.addEntryReIndexOperation(entry);
    }

    private SearchResultList search() throws Exception {
        return manager.search(WORD, HANDLE, null, null, 0, 100, null);
    }

    private static List<String> titles(SearchResultList results) {
        return results.getResults().stream().map(WeblogEntryWrapper::getTitle).toList();
    }

    private static List<String> sortedTitles(SearchResultList results) {
        return titles(results).stream().sorted().toList();
    }

    private WeblogEntry entry(String id) throws Exception {
        WeblogCategory cat = new WeblogCategory();
        cat.setName("General");
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        entry.setWebsite(blog);
        entry.setCategory(cat);
        entry.setTitle(id);
        entry.setText(WORD + " is in the body of " + id);
        entry.setLocale("en");
        entry.setCreatorUserName("nobody");
        entry.setStatus(PubStatus.PUBLISHED);
        Timestamp past = new Timestamp(System.currentTimeMillis() - 60_000);
        entry.setPubTime(past);
        entry.setUpdateTime(past);
        when(entries.getWeblogEntry(id)).thenReturn(entry);
        return entry;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = LuceneIndexManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
