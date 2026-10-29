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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every {@link IndexReader} the search tier opens is closed once nothing
 * uses it.
 *
 * <p>Searches share the manager's current searcher, and every index write
 * replaces its reader ({@code refreshSearcher()}). A replaced reader once
 * went unclosed, so each write left its segment files open until the
 * garbage collector happened to finalize it -- a file-handle leak that grows
 * with write traffic. It cannot simply be closed at the refresh either: a
 * search reads stored fields for its hits ({@code convertHitsToEntryList})
 * <em>after</em> its query, with no lock held, so a write can land between
 * the two, and closing the reader then would fail that search with
 * {@code AlreadyClosedException}.
 *
 * <p>So these tests pin both halves: the superseded reader ends with a
 * reference count of zero (Lucene's own definition of closed), and a
 * search that straddles a write still finishes against the reader it
 * started with, which closes only when that search lets go of it. Every
 * way a search can end -- hits, no hits, a query the parser rejects, a
 * conversion that throws -- must let go.
 *
 * <p>The tier is the same inline-{@link ThreadManager} Mockito double
 * {@link SearchDuringWriteTest} uses, over a {@code @TempDir} index. The
 * write that overlaps a search is run from inside the search's conversion
 * hook, on the same thread, so the interleaving is fixed rather than raced.
 */
class SharedReaderLifecycleTest {

    private static final String WORD = "lifecycleword";
    private static final String HANDLE = "lifecycleblog";

    @TempDir
    Path indexDir;

    private Weblogger pressroom;
    private WeblogEntryManager entries;
    private Weblog blog;
    private LuceneIndexManager manager;

    @BeforeEach
    void setUp() throws Exception {
        pressroom = mock(Weblogger.class);
        entries = mock(WeblogEntryManager.class);
        when(pressroom.getWeblogEntryManager()).thenReturn(entries);
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
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    @Test
    void aWriteClosesTheReaderItSuperseded() throws Exception {
        manager = initialized(new LuceneIndexManager(pressroom));
        index(entry("first"));
        assertEquals(List.of("first"), titles(search(WORD)));
        IndexReader before = currentReader();
        assertEquals(1, before.getRefCount(),
                "a finished search must hold no reference; only the manager's own remains");

        index(entry("second"));

        assertEquals(0, before.getRefCount(), "the write left the reader it replaced open");
    }

    @Test
    void aSearchThatStraddlesAWriteFinishesOnItsOwnReaderWhichThenCloses() throws Exception {
        AtomicReference<IndexReader> used = new AtomicReference<>();
        AtomicInteger refsAfterTheWrite = new AtomicInteger(-1);
        LuceneIndexManager straddling = new LuceneIndexManager(pressroom) {
            @Override
            SearchResultList convertHitsToEntryList(ScoreDoc[] hits, SearchOperation search,
                    int pageNum, int entryCount, String weblogHandle, boolean websiteSpecificSearch,
                    URLStrategy urlStrategy) throws WebloggerException {
                if (used.get() == null) {
                    // The query ran and the read lock is released; a write
                    // lands now, before this search reads its hits.
                    used.set(search.getSearcher().getIndexReader());
                    try {
                        index(entry("second"));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    refsAfterTheWrite.set(used.get().getRefCount());
                }
                return super.convertHitsToEntryList(hits, search, pageNum, entryCount,
                        weblogHandle, websiteSpecificSearch, urlStrategy);
            }
        };
        manager = initialized(straddling);
        index(entry("first"));

        assertEquals(List.of("first"), titles(search(WORD)),
                "the straddling search must answer from the reader it queried, not fail");
        assertEquals(1, refsAfterTheWrite.get(),
                "while the search converts, its own reference must be the only one left");
        assertEquals(0, used.get().getRefCount(),
                "the superseded reader must close once the straddling search lets go of it");
        assertNotSame(used.get(), currentReader());
        assertEquals(List.of("first", "second"), sortedTitles(search(WORD)),
                "the next search must see the write");
    }

    @Test
    void aSearchWithNoHitsLetsGoOfItsReader() throws Exception {
        manager = initialized(new LuceneIndexManager(pressroom));
        index(entry("first"));
        assertEquals(List.of(), titles(search("absentfromeveryentry")));
        IndexReader before = currentReader();

        index(entry("second"));

        assertEquals(0, before.getRefCount(), "a search that found nothing kept its reader open");
    }

    @Test
    void aSearchTheParserRejectsLetsGoOfItsReader() throws Exception {
        manager = initialized(new LuceneIndexManager(pressroom));
        index(entry("first"));
        assertThrows(WebloggerException.class, () -> search("\"" + WORD));
        IndexReader before = currentReader();

        index(entry("second"));

        assertEquals(0, before.getRefCount(), "a search whose query failed to parse kept its reader open");
    }

    @Test
    void aSearchWhoseConversionThrowsLetsGoOfItsReader() throws Exception {
        manager = initialized(new LuceneIndexManager(pressroom));
        index(entry("first"));
        when(entries.getWeblogEntry("first")).thenThrow(new IllegalStateException("store down"));
        assertThrows(IllegalStateException.class, () -> search(WORD));
        IndexReader before = currentReader();

        index(entry("second"));

        assertEquals(0, before.getRefCount(), "a search whose conversion threw kept its reader open");
    }

    @Test
    void repeatedWritesLeaveExactlyOneOpenReaderAndShutdownClosesIt() throws Exception {
        manager = initialized(new LuceneIndexManager(pressroom));
        List<IndexReader> seen = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            index(entry("entry-" + i));
            assertEquals(i + 1, search(WORD).getResults().size());
            seen.add(currentReader());
        }

        IndexReader current = seen.get(seen.size() - 1);
        for (IndexReader reader : seen.subList(0, seen.size() - 1)) {
            assertEquals(0, reader.getRefCount(), "a superseded reader is still open");
        }
        assertEquals(1, current.getRefCount(), "the current reader is held by the manager alone");

        manager.shutdown();
        manager = null;

        assertEquals(0, current.getRefCount(), "shutdown must close the current reader");
    }

    /**
     * A caller whose wait for the operation was interrupted closes it while
     * the operation may not have run yet; when it then runs, it must not
     * take a reference that nobody is left to release.
     */
    @Test
    void anOperationClosedBeforeItRunsTakesNoReference() throws Exception {
        manager = initialized(new LuceneIndexManager(pressroom));
        index(entry("first"));
        IndexReader current = currentReader();

        SearchOperation op = new SearchOperation(manager);
        op.setTerm(WORD);
        op.close();
        op.run();

        assertEquals(-1, op.getResultsCount(), "a closed operation does not search");
        assertEquals(1, current.getRefCount(), "a closed operation took a reference");
    }

    // The explicit op.close() is the point: the test reads the reader's count
    // after the operation lets go, and the try block's own close then repeats it.
    @SuppressWarnings("try")
    @Test
    void anOperationRunTwiceHoldsOnlyItsLatestReader() throws Exception {
        manager = initialized(new LuceneIndexManager(pressroom));
        index(entry("first"));
        try (SearchOperation op = new SearchOperation(manager)) {
            op.setTerm(WORD);
            op.run();
            IndexReader firstRun = op.getSearcher().getIndexReader();
            index(entry("second"));
            assertEquals(1, firstRun.getRefCount(), "precondition: only the operation holds it");

            op.run();
            IndexReader secondRun = op.getSearcher().getIndexReader();

            assertEquals(0, firstRun.getRefCount(), "the first run's reader was not let go of");
            assertEquals(2, secondRun.getRefCount(), "the manager and the operation, once each");
            assertEquals(2, op.getResultsCount());
            op.close();
            assertEquals(1, secondRun.getRefCount());
        }
    }

    /**
     * Releasing is the last thing a finished search does; a reader that
     * fails to close is logged, never turned into a failed search.
     *
     * <p>Written after the catch it covers, not before it: it passed the
     * first time it ran, so it pins that branch (and gives it coverage)
     * rather than having been seen to fail. The reader is a hand-built one
     * the manager never handed out, because {@code IndexReader} is sealed
     * and cannot be mocked into failing its close.
     */
    @Test
    void aReaderThatFailsToCloseDoesNotFailTheRelease() throws Exception {
        manager = initialized(new LuceneIndexManager(pressroom));
        index(entry("first"));
        try (Directory dir = FSDirectory.open(indexDir);
                DirectoryReader real = DirectoryReader.open(dir)) {
            LeafReader failing = new FilterLeafReader(real.leaves().get(0).reader()) {
                @Override
                protected void doClose() throws IOException {
                    throw new IOException("disk gone");
                }

                @Override
                public CacheHelper getCoreCacheHelper() {
                    return null;
                }

                @Override
                public CacheHelper getReaderCacheHelper() {
                    return null;
                }
            };

            assertDoesNotThrow(() -> manager.releaseSearcher(new IndexSearcher(failing)));
        }
    }

    /**
     * A reader closed behind the manager's back (a misuse, but one
     * {@code LuceneIndexManagerStartupTest} makes deliberately) makes the
     * next refresh fail. The refresh runs in a write's {@code finally}, so
     * the failure must neither escape the write nor leave the index
     * write-locked (every later write would wait on it forever), and the
     * dead searchers must be dropped: the next search reopens from disk and
     * sees the write.
     */
    @Test
    void aWriteWhoseRefreshFailsReleasesTheLockAndTheNextSearchReopens() throws Exception {
        manager = initialized(new LuceneIndexManager(pressroom));
        index(entry("first"));
        IndexReader dead = currentReader();
        dead.close();

        assertDoesNotThrow(() -> index(entry("second")), "the refresh's failure escaped the write");

        assertFalse(manager.getWriteLock().isLocked(), "the write left the index write-locked");
        assertEquals(List.of("first", "second"), sortedTitles(search(WORD)));
        assertNotSame(dead, currentReader(), "the closed reader is still in use");
    }

    /**
     * A search reads the searchers' field and then acquires from it, with no
     * lock between. A failed refresh or {@code shutdown()} can close them in
     * that window, leaving the search holding closed searchers: it must
     * discard them and open fresh ones rather than fail. Closing them
     * behind the manager's back while the field still names them is that
     * window, held open.
     */
    @Test
    void aSearchThatFindsTheSearchersClosedUnderItReopensThem() throws Exception {
        manager = initialized(new LuceneIndexManager(pressroom));
        index(entry("first"));
        IndexReader before = currentReader();
        Field field = LuceneIndexManager.class.getDeclaredField("searcherManager");
        field.setAccessible(true);
        ((SearcherManager) field.get(manager)).close();

        assertEquals(List.of("first"), titles(search(WORD)),
                "a search that met closed searchers failed instead of reopening them");
        assertEquals(0, before.getRefCount(), "the closed searchers' reader is still open");
        assertNotSame(before, currentReader());
    }

    /**
     * {@code WebloggerImpl.shutdown} stops the thread manager only after the
     * index manager's shutdown returns; a throw here would skip that. The old
     * {@code reader.close()} was idempotent, and the release must be too.
     */
    @Test
    void shutdownToleratesAReaderAlreadyClosed() throws Exception {
        manager = initialized(new LuceneIndexManager(pressroom));
        index(entry("first"));
        currentReader().close();

        assertDoesNotThrow(manager::shutdown);
        manager = null;
    }

    /**
     * {@code initialize()} opens the searchers directly. Run again on a
     * manager already holding them, it must retire their reader, not
     * overwrite the only reference to it.
     */
    @Test
    void initializingAgainRetiresTheReaderAlreadyHeld() throws Exception {
        manager = initialized(new LuceneIndexManager(pressroom));
        index(entry("first"));
        IndexReader held = currentReader();
        // a clean previous run: no in-use marker, so the index is reopened
        // as it stands rather than discarded and rebuilt
        Files.deleteIfExists(indexDir.resolve(".index-inconsistent"));

        manager.initialize();

        assertEquals(0, held.getRefCount(), "initialize() dropped a held reader without closing it");
        assertEquals(List.of("first"), titles(search(WORD)));
    }

    /**
     * Once shut down, the manager holds no reader; a write that straggles in
     * afterwards has no searchers to refresh and must not reopen any.
     */
    @Test
    void aWriteAfterShutdownReopensNothing() throws Exception {
        manager = initialized(new LuceneIndexManager(pressroom));
        index(entry("first"));
        IndexReader current = currentReader();
        manager.shutdown();

        assertDoesNotThrow(() -> index(entry("second")));

        assertEquals(0, current.getRefCount(), "shutdown must close the current reader");
        assertTrue(searcherManagerIsNull(), "the straggling write reopened the searchers");
        manager = null;
    }

    // ---------------------------------------------------------------- helpers

    private LuceneIndexManager initialized(LuceneIndexManager target) throws Exception {
        setField(target, "indexDir", indexDir.toString());
        setField(target, "indexConsistencyMarker", new File(indexDir.toFile(), ".index-inconsistent"));
        target.initialize();
        return target;
    }

    private void index(WeblogEntry entry) throws Exception {
        manager.addEntryIndexOperation(entry);
    }

    /**
     * The manager's current reader. The reference taken to find it is handed
     * straight back, so the counts the tests read are the manager's and the
     * searches' alone.
     */
    private IndexReader currentReader() throws IOException {
        IndexSearcher searcher = manager.acquireSearcher();
        manager.releaseSearcher(searcher);
        return searcher.getIndexReader();
    }

    private boolean searcherManagerIsNull() throws Exception {
        Field field = LuceneIndexManager.class.getDeclaredField("searcherManager");
        field.setAccessible(true);
        return field.get(manager) == null;
    }

    private SearchResultList search(String term) throws Exception {
        return manager.search(term, HANDLE, null, null, 0, 50, null);
    }

    private static List<String> titles(SearchResultList results) {
        return results.getResults().stream().map(WeblogEntryWrapper::getTitle).toList();
    }

    private static List<String> sortedTitles(SearchResultList results) {
        return titles(results).stream().sorted().toList();
    }

    private WeblogEntry entry(String id) throws WebloggerException {
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
