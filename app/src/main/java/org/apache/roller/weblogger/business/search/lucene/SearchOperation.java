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
/* Created on Jul 18, 2003 */
package org.apache.roller.weblogger.business.search.lucene;

import java.io.IOException;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.util.BytesRef;
import org.apache.roller.weblogger.business.search.IndexManager;

/**
 * An operation that searches the index.
 *
 * <p>{@link AutoCloseable}: running it acquires a counted reference to the
 * manager's current searcher, which it keeps after {@code run()} returns so
 * the caller can still read the hits' stored fields through
 * {@link #getSearcher()}, however many index writes land meanwhile. Closing
 * it lets go of that reference; a caller that runs one must close it.
 * (Running it again lets go of the previous run's searcher first, so it never
 * holds more than one.)
 * 
 * @author Mindaugas Idzelis (min@idzelis.com)
 */
public class SearchOperation extends ReadFromIndexOperation implements AutoCloseable {

    // ~ Static fields/initializers
    // =============================================

    private static final Logger log = LoggerFactory.getLogger(
            SearchOperation.class);

    private static final String[] SEARCH_FIELDS = new String[] {
        FieldConstants.CONTENT,
        FieldConstants.TITLE
    };

    private static final Sort SORTER = new Sort(new SortField(
            FieldConstants.PUBLISHED, SortField.Type.STRING, true));

    // ~ Instance fields
    // ========================================================

    private IndexSearcher searcher;
    private TopFieldDocs searchresults;

    // The searcher this operation acquired, held until close(). Guarded by
    // this operation's monitor, as is `closed`: run() executes on the
    // ThreadManager's pool thread while close() runs on the caller's.
    private IndexSearcher acquired;
    // Set by close(). A caller whose wait for run() was interrupted closes
    // the operation while run() may still be about to start; a run() that
    // finds it closed must not take a reference nobody will release.
    private boolean closed;

    private String term;
    private String weblogHandle;
    private String category;
    private String locale;
    private String parseError;

    // ~ Constructors
    // ===========================================================

    /**
     * Create a new operation that searches the index.
     */
    public SearchOperation(IndexManager mgr) {
        // TODO: finish moving IndexManager to backend, so this cast is not
        // needed
        super((LuceneIndexManager) mgr);
    }

    // ~ Methods
    // ================================================================

    public void setTerm(String term) {
        this.term = term;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Runnable#run()
     */
    @Override
    protected void doRun() {
        final int docLimit = 500;
        searchresults = null;
        searcher = null;

        try {
            // acquired with a counted reference that close() lets go of: it
            // must outlive this method, because the caller reads the hits'
            // stored fields through getSearcher() afterwards
            searcher = acquireSearcher();
            if (searcher == null) {
                return;
            }

            MultiFieldQueryParser multiParser = new MultiFieldQueryParser(
                    SEARCH_FIELDS, LuceneIndexManager.getAnalyzer());

            // Make it an AND by default. Comment this out for an or (default)
            multiParser.setDefaultOperator(MultiFieldQueryParser.Operator.AND);

            // Create a query object out of our term
            Query query = multiParser.parse(term);

            Term handleTerm = IndexUtil.getTerm(FieldConstants.WEBSITE_HANDLE, weblogHandle);
            if (handleTerm != null) {
                query = new BooleanQuery.Builder()
                    .add(query, BooleanClause.Occur.MUST)
                    .add(new TermQuery(handleTerm), BooleanClause.Occur.MUST)
                    .build();
            }

            if (category != null) {
                Term catTerm = new Term(FieldConstants.CATEGORY, category.toLowerCase(Locale.ROOT));
                query = new BooleanQuery.Builder()
                    .add(query, BooleanClause.Occur.MUST)
                    .add(new TermQuery(catTerm), BooleanClause.Occur.MUST)
                    .build();
            }

            Term localeTerm = IndexUtil.getTerm(FieldConstants.LOCALE, locale);
            if (localeTerm != null) {
                query = new BooleanQuery.Builder()
                    .add(query, BooleanClause.Occur.MUST)
                    .add(new TermQuery(localeTerm), BooleanClause.Occur.MUST)
                    .build();
            }

            // A future-dated entry is in the index but never on a results
            // page, so it is left out here rather than after the fact: the
            // total a reader is told ("N entries match", whether there is a
            // next page) is the hit count. PUBLISHED holds the pub time as
            // Timestamp#toString, whose text order is time order.
            query = new BooleanQuery.Builder()
                .add(query, BooleanClause.Occur.MUST)
                .add(SortedDocValuesField.newSlowRangeQuery(FieldConstants.PUBLISHED, null,
                        new BytesRef(manager.now().toString()), true, true), BooleanClause.Occur.FILTER)
                .build();

            searchresults = searcher.search(query, docLimit, SORTER);

        } catch (IOException e) {
            log.error("Error searching index", e);
            parseError = e.getMessage();

        } catch (ParseException e) {
            // who cares?
            parseError = e.getMessage();
        }
        // the searcher stays acquired: close() lets go of it, after the
        // caller has finished reading hits through getSearcher()
    }

    /**
     * Acquires the current searcher for this run, or returns null if the
     * operation was already closed. A second run lets go of the first run's
     * searcher before acquiring the current one, so this operation never
     * holds more than one reference.
     */
    // acquired = null looks dead to PMD (it is overwritten next) but is not:
    // if the acquire below throws, the field must already be clear.
    @SuppressWarnings("PMD.UnusedAssignment")
    private synchronized IndexSearcher acquireSearcher() throws IOException {
        if (closed) {
            return null;
        }
        if (acquired != null) {
            manager.releaseSearcher(acquired);
            // cleared before re-acquiring: if that throws (no index on
            // disk), close() must not release this reference a second time
            acquired = null;
        }
        acquired = manager.acquireSearcher();
        return acquired;
    }

    /**
     * Lets go of the searcher this operation acquired, if any; its reader
     * closes once nothing else holds it. Idempotent. After it,
     * {@link #getSearcher()} must not be used to read documents.
     */
    @Override
    public synchronized void close() {
        closed = true;
        if (acquired != null) {
            manager.releaseSearcher(acquired);
            acquired = null;
        }
    }

    /**
     * Gets the searcher.
     * 
     * @return the searcher
     */
    public IndexSearcher getSearcher() {
        return searcher;
    }

    /**
     * Gets the results.
     * 
     * @return the results
     */
    public TopFieldDocs getResults() {
        return searchresults;
    }

    /**
     * Gets the results count.
     * 
     * @return the results count
     */
    public int getResultsCount() {
        if (searchresults == null) {
            return -1;
        }
        // TotalHits became a record in Lucene 10, so this is an accessor
        // rather than a public field.
        return (int) searchresults.totalHits.value();
    }

    /**
     * Gets the parses the error.
     *
     * @return the parses the error
     */
    public String getParseError() {
        return parseError;
    }

    /**
     * Sets the website handle.
     * 
     * @param weblogHandle
     *            the new website handle
     */
    public void setWeblogHandle(String weblogHandle) {
        this.weblogHandle = weblogHandle;
    }

    /**
     * Sets the category.
     * 
     * @param category
     *            the new category
     */
    public void setCategory(String category) {
        this.category = category;
    }

    /**
     * Sets the locale.
     * 
     * @param locale
     *            the new locale
     */
    public void setLocale(String locale) {
        this.locale = locale;
    }

}
