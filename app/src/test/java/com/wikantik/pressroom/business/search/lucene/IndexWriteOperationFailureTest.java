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
import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.PersistenceException;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.util.Bits;
import org.apache.lucene.store.FSDirectory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.runnable.ThreadManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What each index write operation leaves behind when the tier fails under it.
 * These are characterisation tests, written against the existing behaviour
 * and expected to pass as soon as they were written.
 *
 * <p>Every write operation re-fetches its entry or weblog before touching the
 * index (it may run on another thread, holding a detached object). When that
 * re-fetch fails, the operation must give up without writing: a rebuild or a
 * removal that carried on with what it had would delete documents it has no
 * trustworthy replacement for. Each test here makes one re-fetch fail and
 * checks the index still holds exactly what it held before.
 *
 * <p>The tier is a Mockito {@link Weblogger} whose {@link ThreadManager} runs
 * operations inline, so every assertion reads a committed index with no
 * asynchronous queue to wait on. The index lives in a {@code @TempDir}; the
 * index the rest of the test JVM shares is never touched.
 */
class IndexWriteOperationFailureTest {

    @TempDir
    Path indexDir;

    private Weblogger roller;
    private WeblogEntryManager entries;
    private WeblogManager weblogs;
    private UserManager users;
    private LuceneIndexManager manager;
    private Weblog blog;

    @BeforeEach
    void setUp() throws Exception {
        roller = mock(Weblogger.class);
        entries = mock(WeblogEntryManager.class);
        weblogs = mock(WeblogManager.class);
        users = mock(UserManager.class);
        when(roller.getWeblogEntryManager()).thenReturn(entries);
        when(roller.getWeblogManager()).thenReturn(weblogs);
        when(roller.getUserManager()).thenReturn(users);
        ThreadManager inline = mock(ThreadManager.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(inline).executeInBackground(any(Runnable.class));
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(inline).executeInForeground(any(Runnable.class));
        when(roller.getThreadManager()).thenReturn(inline);

        manager = new LuceneIndexManager(roller);
        setField(manager, "indexDir", indexDir.toString());
        setField(manager, "indexConsistencyMarker", new File(indexDir.toFile(), ".index-inconsistent"));
        manager.initialize();

        blog = new Weblog();
        blog.setHandle("failureblog");
        blog.setName("failureblog");
    }

    @Test
    void aRemovalWhoseReFetchFailsLeavesTheDocumentIndexed() throws Exception {
        WeblogEntry entry = indexed("remove-1");
        when(entries.getWeblogEntry("remove-1")).thenThrow(new WebloggerException("database down"));

        manager.removeEntryIndexOperation(entry);

        assertTrue(indexedDocs().containsKey("remove-1"));
    }

    @Test
    void aReIndexWhoseReFetchFailsLeavesTheDocumentIndexed() throws Exception {
        WeblogEntry entry = indexed("reindex-1");
        when(entries.getWeblogEntry("reindex-1")).thenThrow(new WebloggerException("database down"));

        manager.addEntryReIndexOperation(entry);

        assertTrue(indexedDocs().containsKey("reindex-1"));
    }

    /**
     * Were the rebuild to carry on with the detached weblog it was given, it
     * would delete the weblog's documents and re-add whatever the (failing)
     * tier returned -- an emptied weblog in site search.
     */
    @Test
    void aWeblogRebuildWhoseReFetchFailsLeavesTheWeblogsDocumentsIndexed() throws Exception {
        indexed("rebuild-1");
        when(weblogs.getWeblog(blog.getId())).thenThrow(new WebloggerException("database down"));

        manager.rebuildWeblogIndex(blog);

        assertTrue(indexedDocs().containsKey("rebuild-1"));
    }

    @Test
    void aWeblogRemovalWhoseReFetchFailsLeavesTheWeblogsDocumentsIndexed() throws Exception {
        indexed("removeweblog-1");
        when(weblogs.getWeblog(blog.getId())).thenThrow(new WebloggerException("database down"));

        manager.removeWeblogIndex(blog);

        assertTrue(indexedDocs().containsKey("removeweblog-1"));
    }

    /**
     * An add whose re-fetch fails has already opened its IndexWriter; it
     * must still close it. Returning without that leaked the writer and the
     * directory's {@code write.lock} with it, so every later write failed to
     * open its own writer and the index silently stopped changing.
     */
    @Test
    void anAddWhoseReFetchFailsStillLetsTheNextWriteThrough() throws Exception {
        WeblogEntry entry = entry("add-refetch-fails");
        when(entries.getWeblogEntry("add-refetch-fails")).thenThrow(new WebloggerException("database down"));

        manager.addEntryIndexOperation(entry);

        assertFalse(indexedDocs().containsKey("add-refetch-fails"));
        indexed("after-the-failed-add");
    }

    /**
     * An author who cannot be looked up does not keep the entry out of the
     * index: it is indexed without an author field. The control entry, whose
     * author resolves, carries the lower-cased user name.
     */
    @Test
    void anEntryWhoseAuthorLookupFailsIsStillIndexedWithoutAnAuthor() throws Exception {
        User author = new User();
        author.setUserName("KnownAuthor");
        when(users.getUserByUserName("KnownAuthor")).thenReturn(author);
        when(users.getUserByUserName("LostAuthor")).thenThrow(new WebloggerException("database down"));

        WeblogEntry known = entry("author-known");
        known.setCreatorUserName("KnownAuthor");
        index(known);
        WeblogEntry lost = entry("author-lost");
        lost.setCreatorUserName("LostAuthor");
        index(lost);

        Map<String, Document> docs = indexedDocs();
        assertEquals("knownauthor", docs.get("author-known").get(FieldConstants.USERNAME));
        assertTrue(docs.containsKey("author-lost"), "the entry must be indexed anyway");
        assertNull(docs.get("author-lost").get(FieldConstants.USERNAME));
        assertEquals("Title of author-lost", docs.get("author-lost").get(FieldConstants.TITLE));
    }

    /**
     * An unchecked failure inside a write (here a persistence exception from
     * the re-fetch) must not leave the index's write lock held -- every later
     * write would block on it forever. The next write goes through.
     */
    @Test
    void aWriteThatThrowsReleasesTheWriteLockForTheNextWrite() throws Exception {
        WeblogEntry entry = indexed("unchecked-1");
        when(entries.getWeblogEntry("unchecked-1")).thenThrow(new PersistenceException("connection reset"));

        manager.removeEntryIndexOperation(entry);

        assertFalse(manager.getWriteLock().isLocked(),
                "a failed write must release the write lock");
        indexed("unchecked-2");
        assertTrue(indexedDocs().containsKey("unchecked-2"), "the next write must go through");
    }

    /**
     * While another writer holds Lucene's own lock on the directory, a write
     * operation cannot open its writer and changes nothing; once the lock is
     * released, the same operation succeeds -- so the first attempt left the
     * document in place because of the lock, not because removal is broken.
     */
    @Test
    void aWriteThatCannotTakeLucenesLockChangesNothing() throws Exception {
        WeblogEntry entry = indexed("locked-1");

        try (FSDirectory dir = FSDirectory.open(indexDir);
             IndexWriter foreign = new IndexWriter(dir, new IndexWriterConfig(new StandardAnalyzer()))) {
            manager.removeEntryIndexOperation(entry);
            assertTrue(foreign.isOpen());
        }
        assertTrue(indexedDocs().containsKey("locked-1"), "a write without the lock must change nothing");

        manager.removeEntryIndexOperation(entry);
        assertFalse(indexedDocs().containsKey("locked-1"), "with the lock free, the removal goes through");
    }

    // ---------------------------------------------------------------- helpers

    private WeblogEntry indexed(String id) throws Exception {
        WeblogEntry entry = entry(id);
        index(entry);
        assertTrue(indexedDocs().containsKey(id), "precondition: " + id + " is indexed");
        return entry;
    }

    private void index(WeblogEntry entry) throws Exception {
        when(entries.getWeblogEntry(entry.getId())).thenReturn(entry);
        manager.addEntryIndexOperation(entry);
    }

    private WeblogEntry entry(String id) {
        WeblogCategory cat = new WeblogCategory();
        cat.setName("General");
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        entry.setWebsite(blog);
        entry.setCategory(cat);
        entry.setTitle("Title of " + id);
        entry.setText("failureword is in the body of " + id);
        entry.setLocale("en");
        entry.setCreatorUserName("nobody");
        entry.setStatus(PubStatus.PUBLISHED);
        Timestamp past = new Timestamp(System.currentTimeMillis() - 60_000);
        entry.setPubTime(past);
        entry.setUpdateTime(past);
        return entry;
    }

    /** Every live document in the committed index, keyed by entry id. */
    private Map<String, Document> indexedDocs() throws Exception {
        Map<String, Document> docs = new HashMap<>();
        try (FSDirectory dir = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(dir)) {
            StoredFields stored = reader.storedFields();
            Bits live = MultiBits.getLiveDocs(reader);
            for (int i = 0; i < reader.maxDoc(); i++) {
                if (live != null && !live.get(i)) {
                    continue;
                }
                Document doc = stored.document(i);
                docs.put(doc.get(FieldConstants.ID), doc);
            }
        }
        return docs;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = LuceneIndexManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
