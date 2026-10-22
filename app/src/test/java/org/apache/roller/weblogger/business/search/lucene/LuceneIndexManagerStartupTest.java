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
package org.apache.roller.weblogger.business.search.lucene;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.runnable.ThreadManager;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * How {@link LuceneIndexManager} decides at startup whether the index on disk
 * can be trusted, and what it does when it cannot. These are characterisation
 * tests, written against the existing behaviour and expected to pass as soon
 * as they were written.
 *
 * <p>The decision rests on the {@code .index-inconsistent} marker: {@code
 * initialize()} creates it and {@code shutdown()} deletes it, so a marker
 * found at startup means the last run never shut down cleanly and the index
 * may be half-written. Each test works in its own temporary directory,
 * swapped onto the manager reflectively (the same way {@code
 * LuceneIndexManagerTest} does), so nothing here touches the index the rest
 * of the test JVM shares. The {@link ThreadManager} is a mock that only
 * records what was scheduled: the rebuild itself is not the subject here,
 * only whether startup asked for one.
 */
class LuceneIndexManagerStartupTest {

    private static final String MARKER = ".index-inconsistent";

    /**
     * The normal restart: a clean shutdown removes the marker, so the next
     * startup opens the index it finds, keeps its documents, and schedules no
     * rebuild. Shutting down closes the reader that startup opened.
     */
    @Test
    void aCleanShutdownLetsTheNextStartupReuseTheIndexWithoutARebuild(@TempDir Path tmp) throws Exception {
        Path indexDir = tmp.resolve("index");

        ThreadManager firstThreads = mock(ThreadManager.class);
        LuceneIndexManager first = managerOver(indexDir, firstThreads);
        first.initialize();
        assertTrue(first.isInconsistentAtStartup(), "a brand-new index directory needs a first build");
        verify(firstThreads).executeInBackground(isA(RebuildWebsiteIndexOperation.class));
        addDocument(indexDir, "entry-kept");
        first.shutdown();

        ThreadManager secondThreads = mock(ThreadManager.class);
        LuceneIndexManager second = managerOver(indexDir, secondThreads);
        second.initialize();

        assertFalse(second.isInconsistentAtStartup(),
                "an index left by a clean shutdown must be trusted");
        verifyNoInteractions(secondThreads);
        IndexReader reader = currentReader(second);
        assertEquals(1, reader.numDocs(), "the existing document must survive the restart");
        assertTrue(Files.exists(indexDir.resolve(MARKER)), "startup must mark the index as in use");

        second.shutdown();

        assertEquals(0, reader.getRefCount(), "shutdown must close the reader startup opened");
        assertFalse(Files.exists(indexDir.resolve(MARKER)), "shutdown must clear the in-use marker");
    }

    /**
     * The crash case: a run that never reached {@code shutdown()} leaves the
     * marker behind, and the next startup must not trust what that run was
     * writing. It discards the index and schedules a full rebuild.
     */
    @Test
    void aMarkerLeftByAnUncleanShutdownDiscardsTheIndexAndSchedulesARebuild(@TempDir Path tmp)
            throws Exception {
        Path indexDir = tmp.resolve("index");
        LuceneIndexManager first = managerOver(indexDir, mock(ThreadManager.class));
        first.initialize();
        first.shutdown();

        LuceneIndexManager crashed = managerOver(indexDir, mock(ThreadManager.class));
        crashed.initialize();
        addDocument(indexDir, "entry-written-before-the-crash");
        currentReader(crashed).close();
        // no crashed.shutdown(): the process died here
        assertTrue(Files.exists(indexDir.resolve(MARKER)), "precondition: the marker is left behind");

        ThreadManager threads = mock(ThreadManager.class);
        LuceneIndexManager next = managerOver(indexDir, threads);
        next.initialize();

        assertTrue(next.isInconsistentAtStartup());
        verify(threads).executeInBackground(isA(RebuildWebsiteIndexOperation.class));
        assertEquals(0, numDocs(indexDir),
                "the untrusted index must be discarded, leaving an empty one for the rebuild to fill");
        next.shutdown();
    }

    /**
     * An index that exists but cannot be opened -- corrupt, or written by an
     * incompatible Lucene version -- is deleted and rebuilt rather than
     * failing startup. An empty index takes its place at once, as on the
     * unclean-shutdown path, so until the rebuild commits a search finds
     * nothing rather than failing: searches no longer wait for the rebuild.
     */
    @Test
    void anIndexThatCannotBeOpenedIsDeletedAndRebuilt(@TempDir Path tmp) throws Exception {
        Path indexDir = tmp.resolve("index");
        Files.createDirectories(indexDir);
        Path segments = indexDir.resolve("segments_1");
        Files.write(segments, "this is not a lucene commit point".getBytes(StandardCharsets.UTF_8));

        ThreadManager threads = mock(ThreadManager.class);
        LuceneIndexManager manager = managerOver(indexDir, threads);
        manager.initialize();

        assertTrue(manager.isInconsistentAtStartup());
        verify(threads).executeInBackground(isA(RebuildWebsiteIndexOperation.class));
        assertEquals(0, numDocs(indexDir),
                "the unreadable index must be replaced by an empty, readable one");
        // searches run on the thread manager; this one runs them inline
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(threads).executeInForeground(any(Runnable.class));
        assertTrue(manager.search("anything", "rebuildingblog", null, null, 0, 10, null)
                        .getResults().isEmpty(),
                "a search during the rebuild must find nothing, not fail");
        manager.shutdown();
    }

    /**
     * With {@code search.enabled=false} the manager is inert: startup creates
     * no index, nothing is ever handed to the thread manager, and a search
     * fails rather than pretending to have found nothing.
     */
    @Test
    void withSearchDisabledNothingIsIndexedAndASearchFails(@TempDir Path tmp) throws Exception {
        Path indexDir = tmp.resolve("index");
        ThreadManager threads = mock(ThreadManager.class);

        LuceneIndexManager manager;
        Properties config = webloggerConfig();
        String previous = config.getProperty("search.enabled");
        config.setProperty("search.enabled", "false");
        try {
            manager = managerOver(indexDir, threads);
        } finally {
            if (previous == null) {
                config.remove("search.enabled");
            } else {
                config.setProperty("search.enabled", previous);
            }
        }

        manager.initialize();
        WeblogEntry entry = new WeblogEntry();
        entry.setId("disabled-entry");
        Weblog weblog = new Weblog();
        manager.addEntryIndexOperation(entry);
        manager.addEntryReIndexOperation(entry);
        manager.removeEntryIndexOperation(entry);
        manager.rebuildWeblogIndex();
        manager.rebuildWeblogIndex(weblog);
        manager.removeWeblogIndex(weblog);

        assertFalse(Files.exists(indexDir), "a disabled search must not create an index directory");
        assertFalse(manager.isInconsistentAtStartup());
        verifyNoInteractions(threads);
        WebloggerException ex = assertThrows(WebloggerException.class,
                () -> manager.search("anything", "disabledblog", null, null, 0, 10, null));
        assertEquals("Error executing search", ex.getMessage());
    }

    // ---------------------------------------------------------------- helpers

    private static LuceneIndexManager managerOver(Path indexDir, ThreadManager threads) throws Exception {
        Weblogger roller = mock(Weblogger.class);
        when(roller.getThreadManager()).thenReturn(threads);
        LuceneIndexManager manager = new LuceneIndexManager(roller);
        setField(manager, "indexDir", indexDir.toString());
        setField(manager, "indexConsistencyMarker", new File(indexDir.toFile(), MARKER));
        return manager;
    }

    /** The manager's current reader, the reference taken to find it handed back. */
    private static IndexReader currentReader(LuceneIndexManager manager) throws Exception {
        IndexSearcher searcher = manager.acquireSearcher();
        manager.releaseSearcher(searcher);
        return searcher.getIndexReader();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = LuceneIndexManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Properties webloggerConfig() throws Exception {
        Field field = WebloggerConfig.class.getDeclaredField("config");
        field.setAccessible(true);
        return (Properties) field.get(null);
    }

    private static void addDocument(Path indexDir, String id) throws Exception {
        try (FSDirectory dir = FSDirectory.open(indexDir);
             IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
            Document doc = new Document();
            doc.add(new StringField(FieldConstants.ID, id, Store.YES));
            writer.addDocument(doc);
        }
    }

    private static int numDocs(Path indexDir) throws Exception {
        try (FSDirectory dir = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(dir)) {
            return reader.numDocs();
        }
    }
}
