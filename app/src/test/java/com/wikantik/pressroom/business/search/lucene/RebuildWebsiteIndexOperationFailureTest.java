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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.runnable.ThreadManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A weblog rebuild ({@link RebuildWebsiteIndexOperation}) must be all or
 * nothing: it deletes the weblog's documents and re-adds them from the
 * database inside one writer session, and if anything fails along the way
 * -- the entry query, or building/adding one of the documents -- the index
 * must be left exactly as it was before the rebuild started, not emptied.
 *
 * <p>Follows {@link IndexWriteOperationFailureTest}'s idioms: a mocked tier
 * whose {@link ThreadManager} runs operations inline, against a real Lucene
 * index in a {@code @TempDir}, so every assertion reads a committed index
 * with nothing asynchronous to wait on.
 */
class RebuildWebsiteIndexOperationFailureTest {

    @TempDir
    Path indexDir;

    private Weblogger pressroom;
    private WeblogEntryManager entries;
    private WeblogManager weblogs;
    private LuceneIndexManager manager;
    private Weblog blog;

    @BeforeEach
    void setUp() throws Exception {
        pressroom = mock(Weblogger.class);
        entries = mock(WeblogEntryManager.class);
        weblogs = mock(WeblogManager.class);
        UserManager users = mock(UserManager.class);
        when(pressroom.getWeblogEntryManager()).thenReturn(entries);
        when(pressroom.getWeblogManager()).thenReturn(weblogs);
        when(pressroom.getUserManager()).thenReturn(users);
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

        manager = new LuceneIndexManager(pressroom);
        setField(manager, "indexDir", indexDir.toString());
        setField(manager, "indexConsistencyMarker", new File(indexDir.toFile(), ".index-inconsistent"));
        manager.initialize();

        blog = new Weblog();
        blog.setHandle("rebuildblog");
        blog.setName("rebuildblog");
        // Every test here rebuilds a specific, resolvable weblog; the
        // re-fetch-fails case is already covered by
        // IndexWriteOperationFailureTest.
        when(weblogs.getWeblog(blog.getId())).thenReturn(blog);
    }

    /**
     * Reproduction: the entry query that would supply the replacement
     * documents fails after the weblog itself was already deleted from the
     * index. Before the fix this left the index empty; the rebuild must
     * leave "r1" exactly where it was.
     */
    @Test
    void aRebuildWhoseEntryQueryFailsLeavesThePreviousDocumentsIndexed() throws Exception {
        index(entry("r1"));
        assertTrue(indexedDocs().containsKey("r1"), "precondition: r1 is indexed");

        when(entries.getWeblogEntries(any())).thenThrow(new WebloggerException("database down"));

        manager.rebuildWeblogIndex(blog);

        assertTrue(indexedDocs().containsKey("r1"),
                "a rebuild whose entry query fails must leave the index untouched");
    }

    /**
     * The query succeeds, but building the document for one of the returned
     * entries fails partway through the add loop (a null title is refused by
     * Lucene's {@code TextField}). The documents already indexed before this
     * rebuild started must survive, and the half-built document must never
     * appear.
     */
    @Test
    void aRebuildThatFailsWhileAddingADocumentLeavesThePreviousDocumentsIndexed() throws Exception {
        index(entry("r1"));
        assertTrue(indexedDocs().containsKey("r1"), "precondition: r1 is indexed");

        WeblogEntry bad = entry("r2");
        bad.setTitle(null);
        when(entries.getWeblogEntries(any())).thenReturn(List.of(bad));

        manager.rebuildWeblogIndex(blog);

        Map<String, Document> docs = indexedDocs();
        assertTrue(docs.containsKey("r1"),
                "a rebuild that fails partway through adding documents must leave the previous documents intact");
        assertFalse(docs.containsKey("r2"), "the entry that failed to build must not appear");
    }

    /**
     * Index operations run concurrently -- only the write lock serialises
     * writers (see {@code ThreadManagerImpl}'s cached thread pool). If the
     * entry query ran before the lock were taken, a concurrent
     * {@code AddEntryOperation} for a newly published entry could slip in
     * between the fetch and the delete: the rebuild would then delete that
     * entry's document and re-add only the (now stale) fetched list,
     * silently dropping the entry from search. The query must run only once
     * the lock is held.
     */
    @Test
    void aRebuildFetchesEntriesWhileHoldingTheWriteLock() throws Exception {
        index(entry("r1"));

        AtomicBoolean lockedDuringFetch = new AtomicBoolean(false);
        when(entries.getWeblogEntries(any())).thenAnswer(inv -> {
            lockedDuringFetch.set(
                    manager.getWriteLock().isLocked());
            return List.of();
        });

        manager.rebuildWeblogIndex(blog);

        assertTrue(lockedDuringFetch.get(),
                "the entry query must run while the write lock is held");
    }

    /**
     * A failed rebuild must not leave Lucene's write lock held, or every
     * later write blocks on it forever.
     */
    @Test
    void aFailedRebuildReleasesTheWriteLockForTheNextWrite() throws Exception {
        index(entry("r1"));
        when(entries.getWeblogEntries(any())).thenThrow(new WebloggerException("database down"));

        manager.rebuildWeblogIndex(blog);

        assertFalse(manager.getWriteLock().isLocked(),
                "a failed rebuild must release the write lock");

        index(entry("r3"));
        assertTrue(indexedDocs().containsKey("r3"), "the next write must go through");
    }

    // ---------------------------------------------------------------- helpers

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
