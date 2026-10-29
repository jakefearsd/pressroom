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

package com.wikantik.pressroom.business.search.lucene;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.beanutils.ConstructorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.LimitTokenCountAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.InitializationException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.business.search.SearchResultList;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;

/**
 * Lucene implementation of IndexManager. This is the central entry point into
 * the Lucene searching API.
 * 
 * @author Mindaugas Idzelis (min@idzelis.com)
 * @author mraible (formatting and making indexDir configurable)
 */
public class LuceneIndexManager implements IndexManager {

    // The searchers every search shares, refreshed by each index write before
    // the write returns. Searches read it without a lock (volatile, and
    // created lazily under this object's monitor); see acquireSearcher.
    private volatile SearcherManager searcherManager;
    private final Weblogger roller;

    private static final Logger log = LoggerFactory.getLogger(LuceneIndexManager.class);

    private boolean searchEnabled = true;

    private final String indexDir;

    private final File indexConsistencyMarker;

    // volatile: written during initialize() (the bootstrap thread) and read
    // via isInconsistentAtStartup() from whatever thread later asks -- an
    // admin request thread, unrelated to the one that bootstrapped search.
    private volatile boolean inconsistentAtStartup = false;

    // Serialises index writes -- only one IndexWriter may hold the
    // directory's lock -- and nothing else: searches never take it.
    private final ReentrantLock writeLock = new ReentrantLock();

    // A search that finds its searchers closed under it reopens them; this
    // bounds how often before it gives up (see acquireSearcher).
    private static final int MAX_ACQUIRE_ATTEMPTS = 3;


    /** The tier an {@link IndexOperation} resolves entry authors through. */
    Weblogger weblogger() {
        return roller;
    }

    /**
     * Creates a new lucene index manager. This should only be created once.
     * Creating the index manager more than once will definitely result in
     * errors. The preferred way of getting an index is through the
     * PressroomContext.
     * 
     * @param roller - the weblogger instance
     */
    @SuppressFBWarnings(
            value = {"PATH_TRAVERSAL_IN", "IMPROPER_UNICODE"},
            justification = "indexDir is search.index.dir, operator configuration (pressroom.properties or "
                    + "PRESSROOM_SEARCH_INDEX_DIR); no request reaches it. search.enabled, compared with "
                    + "false in any case, is operator configuration too.")
    public LuceneIndexManager(Weblogger roller) {
        this.roller = roller;

        // check config to see if the internal search is enabled
        String enabled = WebloggerConfig.getProperty("search.enabled");
        if ("false".equalsIgnoreCase(enabled)) {
            this.searchEnabled = false;
        }

        // we also need to know what our index directory is
        // Note: system property expansion is now handled by WebloggerConfig
        String searchIndexDir = WebloggerConfig.getProperty("search.index.dir");
        this.indexDir = searchIndexDir.replace('/', File.separatorChar);

        // a little debugging
        log.info("search enabled: {}", this.searchEnabled);
        log.info("index dir: {}", this.indexDir);

        String test = indexDir + File.separator + ".index-inconsistent";
        indexConsistencyMarker = new File(test);
    }

    /**
     * @inheritDoc
     */
    @Override
    @SuppressFBWarnings(
            value = "PATH_TRAVERSAL_IN",
            justification = "indexDir is search.index.dir, operator configuration (pressroom.properties or "
                    + "PRESSROOM_SEARCH_INDEX_DIR); no request reaches it.")
    public void initialize() throws InitializationException {

        // only initialize the index if search is enabled
        if (this.searchEnabled) {

            // delete index if inconsistency marker exists
            if (indexConsistencyMarker.exists()) {
                log.debug("Index inconsistent: marker exists");
                inconsistentAtStartup = true;
                deleteIndex();
            } else {
                try {
                    File makeIndexDir = new File(indexDir);
                    if (!makeIndexDir.exists()) {
                        if (!makeIndexDir.mkdirs()) {
                            log.error("Unable to create search index directory: {}", indexDir);
                        }
                        inconsistentAtStartup = true;
                        log.debug("Index inconsistent: new");
                    }
                    if (!indexConsistencyMarker.createNewFile()) {
                        // We only reach this branch when
                        // indexConsistencyMarker.exists() was already false
                        // (the enclosing if/else above), so a false return
                        // here means the marker was created by someone else
                        // between that check and this call, not a normal
                        // outcome.
                        log.warn("Search index consistency marker already existed unexpectedly: {}",
                                indexConsistencyMarker);
                    }
                } catch (IOException e) {
                    log.error("Error creating search index consistency marker", e);
                }
            }

            if (indexExists()) {

                // test if the index is readable, if the version is outdated or it fails we rebuild.
                try {
                    synchronized(this) {
                        // retire any searchers already held rather than
                        // overwrite the only reference to them (and leak
                        // their files): a no-op on the one startup call today
                        closeSearcherManager();
                        searcherManager = new SearcherManager(getIndexDirectory(), null);
                    }
                } catch (IOException | IllegalArgumentException ex) {  // IAE for incompatible codecs
                    log.warn("Failed to open search index, scheduling rebuild.", ex);
                    inconsistentAtStartup = true;
                    deleteIndex();
                    // an empty index in its place, as on the unclean-shutdown
                    // path below: until the rebuild commits, searches (which
                    // never wait for it) find nothing rather than fail
                    createIndex(getIndexDirectory());
                }
            } else {
                log.debug("Creating index");
                inconsistentAtStartup = true;
                deleteIndex();
                createIndex(getIndexDirectory());
            }

            if (inconsistentAtStartup) {
                log.info("Index was inconsistent. Rebuilding index in the background...");
                try {
                    rebuildWeblogIndex();
                } catch (WebloggerException ex) {
                    log.error("ERROR: scheduling re-index operation", ex);
                }
            } else {
                log.info("Index initialized and ready for use.");
            }
        }

    }

    @Override
    public void rebuildWeblogIndex() throws WebloggerException {
        scheduleIndexOperation(new RebuildWebsiteIndexOperation(roller, this, null));
    }

    @Override
    public void rebuildWeblogIndex(Weblog website) throws WebloggerException {
        scheduleIndexOperation(new RebuildWebsiteIndexOperation(roller, this, website));
    }

    @Override
    public void removeWeblogIndex(Weblog website) throws WebloggerException {
        scheduleIndexOperation(new RemoveWebsiteIndexOperation(roller, this, website));
    }

    @Override
    public void addEntryIndexOperation(WeblogEntry entry) throws WebloggerException {
        scheduleIndexOperation(new AddEntryOperation(roller, this, entry));
    }

    @Override
    public void addEntryReIndexOperation(WeblogEntry entry) throws WebloggerException {
        scheduleIndexOperation(new ReIndexEntryOperation(roller, this, entry));
    }

    @Override
    public void removeEntryIndexOperation(WeblogEntry entry) throws WebloggerException {
        executeIndexOperationNow(new RemoveEntryOperation(roller, this, entry));
    }

    @Override
    public SearchResultList search(
        String term,
        String weblogHandle,
        String category,
        String locale,
        int pageNum,
        int entryCount,
        URLStrategy urlStrategy) throws WebloggerException {

        // try-with-resources: the operation holds a counted reference to the
        // searcher it queried (see acquireSearcher), and it must hold it
        // past the query, through convertHitsToEntryList's stored-field
        // reads -- a write landing in between would otherwise close the
        // reader under this search. Closing the operation here
        // lets go on every path out: hits, no hits, a query the parser
        // rejected, a conversion that threw.
        try (SearchOperation search = new SearchOperation(this)) {
            search.setTerm(term);
            boolean weblogSpecific = !WebloggerRuntimeConfig.isSiteWideWeblog(weblogHandle);
            if (weblogSpecific) {
                search.setWeblogHandle(weblogHandle);
            }
            if (category != null) {
                search.setCategory(category);
            }
            if (locale != null) {
                search.setLocale(locale);
            }

            executeIndexOperationNow(search);
            if (search.getResultsCount() >= 0) {
                TopFieldDocs docs = search.getResults();
                ScoreDoc[] hitsArr = docs.scoreDocs;
                return convertHitsToEntryList(
                    hitsArr,
                    search,
                    pageNum,
                    entryCount,
                    weblogHandle,
                    weblogSpecific,
                    urlStrategy);
            }
            throw new WebloggerException("Error executing search");
        }
    }

    /** The lock every index write holds for its whole writer session. */
    public ReentrantLock getWriteLock() {
        return writeLock;
    }

    @Override
    public boolean isInconsistentAtStartup() {
        return inconsistentAtStartup;
    }

    /**
     * This is the analyzer that will be used to tokenize comment text.
     * 
     * @return Analyzer to be used in manipulating the database.
     */
    public static final Analyzer getAnalyzer() {
        return instantiateAnalyzer();
    }

    private static Analyzer instantiateAnalyzer() {
        final String className = WebloggerConfig.getProperty("lucene.analyzer.class");
        try {
            final Class<?> clazz = Class.forName(className);
            return (Analyzer) ConstructorUtils.invokeConstructor(clazz, null);
        } catch (final ClassNotFoundException e) {
            log.error("failed to lookup analyzer class: {}", className, e);
            return instantiateDefaultAnalyzer();
        } catch (final NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            log.error("failed to instantiate analyzer: {}", className, e);
            return instantiateDefaultAnalyzer();
        }
    }

    private static Analyzer instantiateDefaultAnalyzer() {
        return new StandardAnalyzer();
    }

    private void scheduleIndexOperation(final IndexOperation op) {
        try {
            // only if search is enabled
            if (this.searchEnabled) {
                log.debug("Starting scheduled index operation: {}", op.getClass().getName());
                roller.getThreadManager().executeInBackground(op);
            }
        } catch (InterruptedException e) {
            log.error("Error executing operation", e);
        }
    }

    /**
     * @param op
     */
    private void executeIndexOperationNow(final IndexOperation op) {
        try {
            // only if search is enabled
            if (this.searchEnabled) {
                log.debug("Executing index operation now: {}", op.getClass().getName());
                roller.getThreadManager().executeInForeground(op);
            }
        } catch (InterruptedException e) {
            log.error("Error executing operation", e);
        }
    }

    /**
     * The current searcher, with a reference counted for the caller, who
     * must hand it to {@link #releaseSearcher} exactly once, in a
     * {@code finally}. Never blocks on an index write: a write in progress is
     * simply not visible yet, and every write {@linkplain #refreshSearcher
     * refreshes} the searchers before it returns.
     *
     * <p>The reference is what keeps the reader open past a later refresh,
     * which drops the manager's own reference to the reader it supersedes: a
     * search reads its hits' stored fields ({@code convertHitsToEntryList})
     * after the query, and a write can land in between.
     *
     * <p>Searchers closed between reading the field and acquiring from them
     * (a failed refresh, {@code shutdown()}) are discarded and reopened, so
     * that race costs a reopen, not a failed search. A search after
     * {@code shutdown()} therefore reopens searchers nothing will close, as
     * the lazily opened reader always did.
     *
     * @throws IOException if there is no readable index on disk (the
     *     searchers are opened on first use)
     */
    // `current` is the manager's own long-lived SearcherManager, published to
    // the field, not a resource this method owns: closeSearcherManager()
    // closes it, on re-initialise, a failed refresh and shutdown.
    @SuppressWarnings("PMD.CloseResource")
    public IndexSearcher acquireSearcher() throws IOException {
        for (int attempt = 1; ; attempt++) {
            SearcherManager current = searcherManager;
            if (current == null) {
                synchronized (this) {
                    current = searcherManager;
                    if (current == null) {
                        current = new SearcherManager(getIndexDirectory(), null);
                        searcherManager = current;
                    }
                }
            }
            try {
                return current.acquire();
            } catch (AlreadyClosedException closed) {
                // A failed refresh or shutdown() closed these searchers
                // between the read above and the acquire: drop them (if
                // nothing has replaced them yet) and open fresh ones. Bounded,
                // so a manager that keeps closing under us fails the search
                // rather than spinning.
                if (attempt >= MAX_ACQUIRE_ATTEMPTS) {
                    throw closed;
                }
                discardSearcherManager(current);
            }
        }
    }

    /**
     * Lets go of a searcher {@link #acquireSearcher} returned; its reader
     * closes once nothing else holds it. A failure to close is logged, not
     * thrown: the caller is finished with the reader either way, and its
     * answer does not depend on the close. That includes a reader already
     * closed ({@code AlreadyClosedException}, unchecked), so a release can
     * never fail a search that has its answer.
     *
     * <p>Decrements the reader directly rather than through the manager that
     * handed it out, which a re-initialise or {@code shutdown()} may have
     * closed and replaced since.
     */
    public void releaseSearcher(IndexSearcher acquired) {
        try {
            acquired.getIndexReader().decRef();
        } catch (IOException | AlreadyClosedException ex) {
            log.warn("Unable to close a search index reader.", ex);
        }
    }

    /**
     * Makes the latest commit visible to every search that starts after this
     * returns; each index write calls it before it is done. Searches already
     * running finish on the reader they hold, which closes when the last of
     * them lets go.
     *
     * <p>If no searchers are open yet there is nothing to refresh: the first
     * search opens them on the latest commit. Reading the field under the
     * monitor matters there: a search opening them concurrently either
     * finished before this looks (and is refreshed) or starts after it (and
     * opens the commit this write made).
     *
     * <p>A refresh that fails (the reader closed behind the manager's back,
     * an I/O error) is logged and the searchers are discarded, so the next
     * search reopens them from disk rather than every search failing, or
     * answering without this write, from then on.
     */
    // `current` is the field's SearcherManager, owned (and closed) by
    // closeSearcherManager(), not by this method.
    @SuppressWarnings("PMD.CloseResource")
    void refreshSearcher() {
        SearcherManager current;
        synchronized (this) {
            current = searcherManager;
        }
        if (current == null) {
            return;
        }
        try {
            current.maybeRefreshBlocking();
        } catch (IOException | IllegalStateException ex) {
            log.error("Unable to refresh the search index reader; reopening it on the next search", ex);
            discardSearcherManager(current);
        }
    }

    /**
     * Closes and forgets {@code failed}, but only if it is still the
     * installed instance: never one a concurrent re-initialise, or another
     * search's reopen, has put in its place since.
     */
    // The == is deliberate identity, as above.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private synchronized void discardSearcherManager(SearcherManager failed) {
        if (searcherManager == failed) {
            closeSearcherManager();
        }
    }

    /**
     * Drops the manager's reference to its current reader and forgets the
     * searchers, so the next search opens fresh ones. The reader closes now,
     * or when the last search still holding it lets go. A close that fails
     * (the reader already closed behind the manager's back) is logged; the
     * searchers are forgotten either way.
     */
    // It is closed, explicitly, below; try-with-resources would not fit: the
    // field must be cleared first, and a failed close is logged, not thrown.
    @SuppressWarnings("PMD.CloseResource")
    private synchronized void closeSearcherManager() {
        SearcherManager current = searcherManager;
        searcherManager = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException | IllegalStateException ex) {
                log.warn("Unable to close the search index reader.", ex);
            }
        }
    }

    /**
     * Get the directory that is used by the lucene index. This method will
     * return null if there is no index at the directory location.
     * 
     * @return Directory The directory containing the index, or null if error.
     */
    public Directory getIndexDirectory() {

        try {
            return FSDirectory.open(Path.of(indexDir));
        } catch (IOException e) {
            log.error("Problem accessing index directory", e);
        }
        return null;
    }

    private boolean indexExists() {
        try {
            // getIndexDirectory() returns null on its own IOException
            // (caught and logged there already), and DirectoryReader
            // .indexExists(null) throws an uncaught NullPointerException
            // rather than the IOException this method's catch expects --
            // an inaccessible/invalid search.index.dir would otherwise
            // abort Weblogger bootstrap with an NPE instead of degrading
            // gracefully, which is what every other caller in this class
            // treats "no index yet" as.
            Directory dir = getIndexDirectory();
            return dir != null && DirectoryReader.indexExists(dir);
        } catch (IOException e) {
            log.error("Problem accessing index directory", e);
        }
        return false;
    }

    
    private void deleteIndex() {
        
        try(FSDirectory directory = FSDirectory.open(Path.of(indexDir))) {
            
            String[] files = directory.listAll();
            for (String file : files) {
                Files.delete(Path.of(indexDir, file));
            }
        } catch (IOException ex) {
             log.error("Problem accessing index directory", ex);
        }

    }

    private void createIndex(Directory dir) {
        if (dir == null) {
            // getIndexDirectory() already logged the underlying IOException
            // that produced this; there is nothing further to do without a
            // Directory to write an index into. Guards the same gap fixed
            // in indexExists() above -- new IndexWriter(null, config) throws
            // an uncaught NullPointerException, not the IOException this
            // method's catch expects.
            return;
        }

        IndexWriterConfig config = new IndexWriterConfig(
                new LimitTokenCountAnalyzer(
                        getAnalyzer(), 128));

        try (IndexWriter writer = new IndexWriter(dir, config)) {
            // an empty commit is what writes the first segments file
            writer.commit();
        } catch (IOException e) {
            log.error("Error creating index", e);
        }
    }

    @Override
    public void release() {
        // no-op
    }

    @Override
    public synchronized void shutdown() {

        // A failed delete here means the next startup's initialize() sees
        // the marker still present and concludes (wrongly) that the
        // previous shutdown was unclean, forcing an unnecessary full index
        // rebuild -- worth a log line to explain that rebuild after the
        // fact. exists() is checked only when delete() itself reports
        // failure, so a marker that was never created (search disabled, or
        // initialize() never ran) does not log a spurious warning on every
        // shutdown.
        if (!indexConsistencyMarker.delete() && indexConsistencyMarker.exists()) {
            log.warn("Unable to delete search index consistency marker: {}", indexConsistencyMarker);
        }

        // Drop the manager's own reference: the reader closes now, or when a
        // search still converting on it lets go. The searchers are also
        // forgotten, so a write that straggles in after shutdown has nothing
        // to refresh.
        closeSearcherManager();
    }

    /**
     * Convert hits to entries.
     *
     * @param hits
     *            the hits
     * @param search
     *            the search
     * @throws WebloggerException
     *             the weblogger exception
     */
    SearchResultList convertHitsToEntryList(
        ScoreDoc[] hits,
        SearchOperation search,
        int pageNum,
        int entryCount,
        String weblogHandle,
        boolean websiteSpecificSearch,
        URLStrategy urlStrategy)
        throws WebloggerException {

        List<WeblogEntryWrapper> results = new ArrayList<>();

        // determine offset
        int offset = pageNum * entryCount;
        if (offset >= hits.length) {
            offset = 0;
        }

        // determine limit
        int limit = entryCount;
        if (offset + limit > hits.length) {
            limit = hits.length - offset;
        }

        try {
            Set<String> categories = new TreeSet<>();
            Set<String> categorySet = new TreeSet<>();
            WeblogEntryManager weblogMgr = roller.getWeblogEntryManager();

            WeblogEntry entry;
            Document doc;
            String handle;
            Timestamp now = now();
            for (int i = offset; i < offset + limit; i++) {
                doc = search.getSearcher().storedFields().document(hits[i].doc);
                handle = doc.getField(FieldConstants.WEBSITE_HANDLE).stringValue();
                entry = weblogMgr.getWeblogEntry(doc.getField(FieldConstants.ID).stringValue());

                if (!(websiteSpecificSearch && handle.equals(weblogHandle))
                    && doc.getField(FieldConstants.CATEGORY) != null) {
                    categorySet.add(doc.getField(FieldConstants.CATEGORY).stringValue());
                }

                // maybe null if search result returned inactive user
                // or entry's user is not the requested user.
                // but don't return future posts, and -- now that a trashed
                // (or otherwise unpublished) entry can survive as a live row
                // rather than disappearing on delete -- don't resolve a
                // stale index document back into a reader-visible entry
                // either. The de-index on trash is best-effort (swallowed
                // failures, no retry), so a stale document is not
                // hypothetical: this is what keeps search safe by
                // construction rather than relying on the index always
                // being current, the same defence ReIndexEntryOperation
                // already applies on the write side.
                if (entry != null && entry.isPublished() && !entry.getPubTime().after(now)) {
                    results.add(WeblogEntryWrapper.wrap(entry, urlStrategy, roller));
                }
            }

            if (!categorySet.isEmpty()) {
                categories = categorySet;
            }

            // What this page had to drop (a stale document: its entry gone or
            // no longer published) is not counted either. The query already
            // left out future-dated entries; this covers what the index has
            // not caught up with.
            int dropped = limit - results.size();
            return new SearchResultList(results, categories, limit, offset, hits.length - dropped);

        } catch (IOException e) {
            throw new WebloggerException(e);
        }
    }

    /** The instant a search treats as "now" when hiding future entries; a seam for tests. */
    Timestamp now() {
        return new Timestamp(new Date().getTime());
    }
}
