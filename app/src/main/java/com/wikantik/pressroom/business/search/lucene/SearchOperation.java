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
package com.wikantik.pressroom.business.search.lucene;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.function.FunctionScoreQuery;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DoubleValues;
import org.apache.lucene.search.DoubleValuesSource;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.util.automaton.TooComplexToDeterminizeException;
import org.apache.lucene.util.BytesRef;
import com.wikantik.pressroom.business.search.IndexManager;

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

    /**
     * The fields a reader's words search, with their weights: a word in the
     * title counts four times a word in the body.
     */
    static final Map<String, Float> FIELD_BOOSTS = fieldBoosts();

    private static Map<String, Float> fieldBoosts() {
        // insertion order, so the parsed query is the same on every JVM run
        Map<String, Float> boosts = new java.util.LinkedHashMap<>();
        boosts.put(FieldConstants.TITLE, 4.0f);
        boosts.put(FieldConstants.TAGS, 3.0f);
        boosts.put(FieldConstants.SUMMARY, 2.0f);
        boosts.put(FieldConstants.BLOG, 2.0f);
        boosts.put(FieldConstants.CATEGORY_TEXT, 1.5f);
        boosts.put(FieldConstants.CONTENT, 1.0f);
        return java.util.Collections.unmodifiableMap(boosts);
    }

    private static final String[] SEARCH_FIELDS = FIELD_BOOSTS.keySet().toArray(new String[0]);

    private static final TopDocs NO_HITS = new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO),
            new org.apache.lucene.search.ScoreDoc[0]);

    private static final int DOC_LIMIT = 500;

    // ~ Instance fields
    // ========================================================

    private IndexSearcher searcher;
    private TopDocs searchresults;
    private boolean anyWordFallback;

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

    /**
     * The reader's query, confined to {@link #SEARCH_FIELDS}. The classic
     * parser honours "field:" syntax for any field in the index, so a reader
     * could query fields search is not for ("username:a*" enumerated author
     * logins; code review, 2026-11-03). A field outside the searched ones is
     * read as no field at all, which searches its words across them all.
     */
    static final class SearchedFieldsOnly extends MultiFieldQueryParser {

        private static final java.util.Set<String> SEARCHED = java.util.Set.of(SEARCH_FIELDS);

        SearchedFieldsOnly(String[] fields, org.apache.lucene.analysis.Analyzer analyzer,
                Map<String, Float> boosts) {
            super(fields, analyzer, boosts);
        }

        private static String confine(String field) {
            return field != null && SEARCHED.contains(field) ? field : null;
        }

        @Override
        protected Query getFieldQuery(String field, String queryText, int slop) throws ParseException {
            return super.getFieldQuery(confine(field), queryText, slop);
        }

        @Override
        protected Query getFieldQuery(String field, String queryText, boolean quoted) throws ParseException {
            return super.getFieldQuery(confine(field), queryText, quoted);
        }

        @Override
        protected Query getFuzzyQuery(String field, String termStr, float minSimilarity) throws ParseException {
            return super.getFuzzyQuery(confine(field), termStr, minSimilarity);
        }

        @Override
        protected Query getPrefixQuery(String field, String termStr) throws ParseException {
            return super.getPrefixQuery(confine(field), termStr);
        }

        @Override
        protected Query getWildcardQuery(String field, String termStr) throws ParseException {
            return super.getWildcardQuery(confine(field), termStr);
        }

        @Override
        protected Query getRegexpQuery(String field, String termStr) throws ParseException {
            return super.getRegexpQuery(confine(field), termStr);
        }

        @Override
        protected Query getRangeQuery(String field, String part1, String part2, boolean startInclusive,
                boolean endInclusive) throws ParseException {
            return super.getRangeQuery(confine(field), part1, part2, startInclusive, endInclusive);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Runnable#run()
     */
    @Override
    protected void doRun() {
                searchresults = null;
        searcher = null;
        anyWordFallback = false;
        parseError = null;

        try {
            // acquired with a counted reference that close() lets go of: it
            // must outlive this method, because the caller reads the hits'
            // stored fields through getSearcher() afterwards
            searcher = acquireSearcher();
            if (searcher == null) {
                return;
            }

            // Nothing to search for is zero hits, not the reader's error:
            // punctuation alone ("!!!") is a parse error to Lucene, but a
            // reader who typed it simply found nothing.
            if (term == null || term.codePoints().noneMatch(Character::isLetterOrDigit)) {
                searchresults = NO_HITS;
                return;
            }

            long nowMillis = manager.now().getTime();

            // All of the words first; when no post has them all, any of them.
            // The page says which it did, so a reader is never shown partial
            // matches as if they were complete ones.
            Query all = parse(MultiFieldQueryParser.Operator.AND);
            searchresults = run(searcher, all, nowMillis);
            if (searchresults.totalHits.value() == 0 && !isEmpty(all)) {
                Query any = parse(MultiFieldQueryParser.Operator.OR);
                TopDocs second = run(searcher, any, nowMillis);
                if (second.totalHits.value() > 0) {
                    searchresults = second;
                    anyWordFallback = true;
                }
            }

        } catch (IOException e) {
            log.error("Error searching index", e);
            parseError = e.getMessage();

        } catch (ParseException e) {
            // who cares?
            parseError = e.getMessage();

        } catch (TooComplexToDeterminizeException | IndexSearcher.TooManyClauses e) {
            // The reader's query, not the index: a regular expression or
            // wildcard too complex to run, or more clauses than a search may
            // hold. Answered like an unparsable query; it used to reach
            // ReadFromIndexOperation and log ERROR with a stack trace for
            // every such anonymous request.
            log.debug("Search query too complex to run: {}", e.getMessage());
            parseError = e.getMessage();
        }
        // the searcher stays acquired: close() lets go of it, after the
        // caller has finished reading hits through getSearcher()
    }

    private Query parse(MultiFieldQueryParser.Operator operator) throws ParseException {
        return parse(term, operator);
    }

    /** The reader's words as the query the index runs, confined to the searched fields and weighted. */
    static Query parse(String words, MultiFieldQueryParser.Operator operator) throws ParseException {
        MultiFieldQueryParser parser = new SearchedFieldsOnly(
                SEARCH_FIELDS, LuceneIndexManager.getAnalyzer(), FIELD_BOOSTS);
        parser.setDefaultOperator(operator);
        return parser.parse(words);
    }

    /** Words that analyse to nothing (only stop words, only punctuation) parse to an empty query. */
    private static boolean isEmpty(Query query) {
        return query instanceof BooleanQuery bq && bq.clauses().isEmpty();
    }

    /**
     * Ranks the parsed words by relevance, scaled by the recency factor, and
     * narrows them to the scope. The narrowing clauses are filters, so they
     * add nothing to a score.
     */
    private TopDocs run(IndexSearcher indexSearcher, Query words, long nowMillis) throws IOException {
        if (isEmpty(words)) {
            return NO_HITS;
        }
        BooleanQuery.Builder query = new BooleanQuery.Builder()
            .add(FunctionScoreQuery.boostByValue(words, new RecencySource(nowMillis)),
                    BooleanClause.Occur.MUST);

        Term handleTerm = IndexUtil.getTerm(FieldConstants.WEBSITE_HANDLE, weblogHandle);
        if (handleTerm != null) {
            query.add(new TermQuery(handleTerm), BooleanClause.Occur.FILTER);
        }
        if (category != null) {
            query.add(new TermQuery(new Term(FieldConstants.CATEGORY, category.toLowerCase(Locale.ROOT))),
                    BooleanClause.Occur.FILTER);
        }
        Term localeTerm = IndexUtil.getTerm(FieldConstants.LOCALE, locale);
        if (localeTerm != null) {
            query.add(new TermQuery(localeTerm), BooleanClause.Occur.FILTER);
        }

        // A future-dated entry is in the index but never on a results
        // page, so it is left out here rather than after the fact: the
        // total a reader is told ("N entries match", whether there is a
        // next page) is the hit count. PUBLISHED holds the pub time as
        // Timestamp#toString, whose text order is time order.
        query.add(SortedDocValuesField.newSlowRangeQuery(FieldConstants.PUBLISHED, null,
                new BytesRef(manager.now().toString()), true, true), BooleanClause.Occur.FILTER);

        return indexSearcher.search(query.build(), DOC_LIMIT);
    }

    /** Multiplies a score by {@link RecencyBoost#factor}, read from the publish time doc value. */
    private static final class RecencySource extends DoubleValuesSource {

        private final long nowMillis;

        RecencySource(long nowMillis) {
            this.nowMillis = nowMillis;
        }

        @Override
        public DoubleValues getValues(LeafReaderContext ctx, DoubleValues scores) throws IOException {
            NumericDocValues published = DocValues.getNumeric(ctx.reader(), FieldConstants.PUBLISHED_NUM);
            return new DoubleValues() {
                private double factor = 1.0;

                @Override
                public double doubleValue() {
                    return factor;
                }

                @Override
                public boolean advanceExact(int doc) throws IOException {
                    // a document with no publish time (none is indexed
                    // without one) is simply not boosted
                    factor = published.advanceExact(doc)
                            ? RecencyBoost.factor(published.longValue(), nowMillis) : 1.0;
                    return true;
                }
            };
        }

        @Override
        public boolean needsScores() {
            return false;
        }

        @Override
        public DoubleValuesSource rewrite(IndexSearcher indexSearcher) {
            return this;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(nowMillis);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof RecencySource other && other.nowMillis == nowMillis;
        }

        @Override
        public String toString() {
            return "recency(" + FieldConstants.PUBLISHED_NUM + ")";
        }

        @Override
        public boolean isCacheable(LeafReaderContext ctx) {
            return true;
        }
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
    public TopDocs getResults() {
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

    /** True when no post had every word and these results match some of them. */
    public boolean isAnyWordFallback() {
        return anyWordFallback;
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
