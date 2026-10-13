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

package org.apache.roller.weblogger.business.search.lucene;

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
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.commons.beanutils.ConstructorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.LimitTokenCountAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.InitializationException;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.search.IndexManager;
import org.apache.roller.weblogger.business.search.SearchResultList;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.wrapper.WeblogEntryWrapper;

/**
 * Lucene implementation of IndexManager. This is the central entry point into
 * the Lucene searching API.
 * 
 * @author Mindaugas Idzelis (min@idzelis.com)
 * @author mraible (formatting and making indexDir configurable)
 */
public class LuceneIndexManager implements IndexManager {

    private IndexReader reader;
    private final Weblogger roller;

    private static final Logger log = LoggerFactory.getLogger(LuceneIndexManager.class);

    private boolean searchEnabled = true;

    private final String indexDir;

    private final File indexConsistencyMarker;

    // volatile: written during initialize() (the bootstrap thread) and read
    // via isInconsistentAtStartup() from whatever thread later asks -- an
    // admin request thread, unrelated to the one that bootstrapped search.
    private volatile boolean inconsistentAtStartup = false;

    private final ReadWriteLock rwl = new ReentrantReadWriteLock();


    /** The tier an {@link IndexOperation} resolves entry authors through. */
    Weblogger weblogger() {
        return roller;
    }

    /**
     * Creates a new lucene index manager. This should only be created once.
     * Creating the index manager more than once will definitely result in
     * errors. The preferred way of getting an index is through the
     * RollerContext.
     * 
     * @param roller - the weblogger instance
     */
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
                        // retire any reader already held rather than
                        // overwrite the only reference to it (and leak its
                        // files): a no-op on the one startup call today
                        resetSharedReader();
                        reader = DirectoryReader.open(getIndexDirectory());
                    }
                } catch (IOException | IllegalArgumentException ex) {  // IAE for incompatible codecs
                    log.warn("Failed to open search index, scheduling rebuild.", ex);
                    inconsistentAtStartup = true;
                    deleteIndex();
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
        // reader it queried (see acquireSharedIndexReader), and it must hold
        // it past the read lock, through convertHitsToEntryList's
        // stored-field reads -- a write landing in between would otherwise
        // close the reader under this search. Closing the operation here
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

    public ReadWriteLock getReadWriteLock() {
        return rwl;
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
     * Retires the cached reader after an index write, so the next search
     * opens one that sees the write.
     *
     * <p>The manager owns one reference to the cached reader (the one
     * {@code DirectoryReader.open} returned) and drops it here. That closes
     * the reader -- and its segment file handles -- at once if no search is
     * using it, or when the last search that {@linkplain
     * #acquireSharedIndexReader acquired} it lets go. Simply forgetting the
     * reader, as this once did, left every superseded reader's files open
     * until the garbage collector got to it; closing it outright would fail
     * a search still converting its hits on it.
     */
    public synchronized void resetSharedReader() {
        if (reader != null) {
            try {
                releaseSharedIndexReader(reader);
            } finally {
                // forgotten even if the release threw, or every later search
                // would be handed the dead reader
                reader = null;
            }
        }
    }

    /**
     * The cached reader, opened on first use, <em>borrowed</em>: the caller
     * takes no reference, so the reader is only guaranteed open while the
     * caller holds the index read lock (a write, which retires it, needs the
     * write lock). Anything that uses the reader after letting go of that
     * lock -- a search converting its hits -- must use
     * {@link #acquireSharedIndexReader} instead.
     */
    public synchronized IndexReader getSharedIndexReader() {
        if (reader == null) {
            try {
                reader = DirectoryReader.open(getIndexDirectory());
            } catch (IOException ex) {
                log.error("Error opening DirectoryReader", ex);
                throw new RuntimeException(ex);
            }
        }
        return reader;
    }

    /**
     * The cached reader with a reference counted for the caller, which keeps
     * it open across a later {@link #resetSharedReader} until the caller
     * hands it to {@link #releaseSharedIndexReader} -- exactly once, in a
     * {@code finally}. Taking the reference under this monitor is what makes
     * it safe: a reset cannot drop the manager's reference between the
     * lookup and the increment.
     */
    public synchronized IndexReader acquireSharedIndexReader() {
        IndexReader shared = getSharedIndexReader();
        shared.incRef();
        return shared;
    }

    /**
     * Lets go of one reference to a reader; the reader closes when its last
     * reference goes. A failure to close is logged, not thrown: the caller
     * is finished with the reader either way, and its answer (a search
     * result, a write) does not depend on the close.
     *
     * <p>That includes a reader already closed ({@code AlreadyClosedException},
     * unchecked): one lent by {@link #getSharedIndexReader} and closed by its
     * borrower. The release runs in a write's {@code finally} and in
     * {@code shutdown()}, where {@code WebloggerImpl.shutdown} stops the
     * thread manager only after it returns -- and the {@code reader.close()}
     * shutdown used before was idempotent -- so a second close must not
     * throw from here either.
     */
    public void releaseSharedIndexReader(IndexReader acquired) {
        try {
            acquired.decRef();
        } catch (IOException | AlreadyClosedException ex) {
            log.warn("Unable to close a search index reader.", ex);
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

    // synchronized: matches getSharedIndexReader()/resetSharedReader() below
    // -- every other access to `reader` holds this monitor, and an
    // unguarded read here was the one inconsistent access
    // (IS2_INCONSISTENT_SYNC). A method-level modifier, like its two
    // siblings, rather than a synchronized(this) block around the existing
    // body, so the already-untested body's lines are not re-indented into
    // looking like new (and newly diff-covered) lines.
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

        // Drop the manager's own reference, as a write does: the reader
        // closes now, or when a search still converting on it lets go. It is
        // also forgotten, so a write that straggles in after shutdown does
        // not release the same reference a second time.
        resetSharedReader();
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

            return new SearchResultList(results, categories, limit, offset);

        } catch (IOException e) {
            throw new WebloggerException(e);
        }
    }

    /** The instant a search treats as "now" when hiding future entries; a seam for tests. */
    Timestamp now() {
        return new Timestamp(new Date().getTime());
    }
}
