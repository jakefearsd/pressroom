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
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.DoubleValues;
import org.apache.lucene.search.DoubleValuesSource;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
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
        // low: a blog's name is in every one of its posts, so it may find a
        // post but must not lift a whole blog above better matches elsewhere
        boosts.put(FieldConstants.BLOG, 0.5f);
        boosts.put(FieldConstants.CATEGORY_TEXT, 1.5f);
        boosts.put(FieldConstants.CONTENT, 1.0f);
        return java.util.Collections.unmodifiableMap(boosts);
    }

    /**
     * The fields a page search reads, weighted as the post fields holding
     * the same kind of text are: a title word counts four times a body word.
     */
    static final Map<String, Float> PAGE_FIELD_BOOSTS = pageFieldBoosts();

    private static Map<String, Float> pageFieldBoosts() {
        Map<String, Float> boosts = new java.util.LinkedHashMap<>();
        boosts.put(FieldConstants.PAGE_TITLE, FIELD_BOOSTS.get(FieldConstants.TITLE));
        boosts.put(FieldConstants.PAGE_CONTENT, FIELD_BOOSTS.get(FieldConstants.CONTENT));
        return java.util.Collections.unmodifiableMap(boosts);
    }

    private static final Map<DocumentKind, Map<String, Float>> BOOSTS_BY_KIND = Map.of(
            DocumentKind.ENTRY, FIELD_BOOSTS, DocumentKind.PAGE, PAGE_FIELD_BOOSTS);

    private static final TopDocs NO_HITS = new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO),
            new org.apache.lucene.search.ScoreDoc[0]);

    private static final int DOC_LIMIT = 500;

    /** Words the reader typed found together, within this many positions in a title, score more. */
    static final int TITLE_PHRASE_SLOP = 2;
    static final float TITLE_PHRASE_BOOST = 2.0f;
    /** ... and within this many in a body. */
    static final int CONTENT_PHRASE_SLOP = 3;
    static final float CONTENT_PHRASE_BOOST = 1.0f;

    /** The fallback before any word: this share of the words, rounded down... */
    static final int MOST_WORDS_PERCENT = 75;
    /** ... for this many words or more (fewer go straight to any word). */
    static final int MOST_WORDS_FROM = 3;

    private static final java.util.regex.Pattern NOT_A_WORD = java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+");

    // ~ Instance fields
    // ========================================================

    private IndexSearcher searcher;
    private TopDocs searchresults;
    // guarded by this operation's monitor (setAnyWordFallback, isAnyWordFallback)
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
    private Query parsedQuery;
    // Null: not narrowed to the site. A set (possibly empty): only posts
    // flagged for the site whose blog handle is in it.
    private java.util.Set<String> siteHandles;
    private String excludedHandle;
    // ENTRY: posts (the default every post search relies on); PAGE: pages.
    // Final: set by the caller's thread, read on the pool thread run() uses.
    private final DocumentKind kind;

    // ~ Constructors
    // ===========================================================

    /**
     * Create a new operation that searches the index.
     */
    public SearchOperation(IndexManager mgr) {
        this(mgr, DocumentKind.ENTRY);
    }

    /**
     * An operation that searches one kind of document: posts
     * ({@link DocumentKind#ENTRY}, what the one-argument constructor
     * makes) or pages ({@link DocumentKind#PAGE}): the page fields,
     * with no publish-time or site-flag filter (a page in the index is
     * published and not noindex). The scope setters apply to pages as they
     * do to posts, the handle set of {@link #restrictToSite} included.
     */
    public SearchOperation(IndexManager mgr, DocumentKind kind) {
        // TODO: finish moving IndexManager to backend, so this cast is not
        // needed
        super((LuceneIndexManager) mgr);
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
    }

    // ~ Methods
    // ================================================================

    public void setTerm(String term) {
        this.term = term;
    }

    /**
     * The reader's query, confined to the fields it searches. The classic
     * parser honours "field:" syntax for any field in the index, so a reader
     * could query fields search is not for ("username:a*" enumerated author
     * logins; code review, 2026-11-03). A field outside the searched ones is
     * read as no field at all, which searches its words across them all.
     */
    static final class SearchedFieldsOnly extends MultiFieldQueryParser {

        private final java.util.Set<String> searched;

        SearchedFieldsOnly(String[] fields, org.apache.lucene.analysis.Analyzer analyzer,
                Map<String, Float> boosts) {
            super(fields, analyzer, boosts);
            this.searched = java.util.Set.of(fields);
        }

        private String confine(String field) {
            return field != null && searched.contains(field) ? field : null;
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
        setAnyWordFallback(false);
        parseError = null;
        parsedQuery = null;

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

            // All of the words first; when no post has them all, most of them
            // (from three words), then any of them. The page says when it fell
            // back, so a reader is never shown partial matches as if they were
            // complete ones.
            Query phrase = phraseBoost(term, manager.getAnalyzer(), kind.titleField(), kind.contentField());
            Query all = parse(MultiFieldQueryParser.Operator.AND);
            parsedQuery = all;
            searchresults = run(searcher, all, phrase, nowMillis);
            if (searchresults.totalHits.value() == 0 && !isEmpty(all)) {
                Query any = parse(MultiFieldQueryParser.Operator.OR);
                Query most = mostWords(all, any);
                for (Query fallback : most == null ? java.util.List.of(any) : java.util.List.of(most, any)) {
                    TopDocs found = run(searcher, fallback, phrase, nowMillis);
                    if (found.totalHits.value() > 0) {
                        searchresults = found;
                        parsedQuery = fallback;
                        setAnyWordFallback(true);
                        break;
                    }
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
        return parse(term, operator, manager.getAnalyzer(),
                BOOSTS_BY_KIND.get(kind));
    }

    /** The reader's words as the query the index runs, confined to the searched fields and weighted. */
    static Query parse(String words, MultiFieldQueryParser.Operator operator,
            org.apache.lucene.analysis.Analyzer analyzer) throws ParseException {
        return parse(words, operator, analyzer, FIELD_BOOSTS);
    }

    /** The reader's words over {@code boosts}' fields, with their weights, confined to them. */
    private static Query parse(String words, MultiFieldQueryParser.Operator operator,
            org.apache.lucene.analysis.Analyzer analyzer, Map<String, Float> boosts) throws ParseException {
        MultiFieldQueryParser parser = new SearchedFieldsOnly(boosts.keySet().toArray(new String[0]),
                analyzer, boosts);
        parser.setDefaultOperator(operator);
        return parser.parse(words);
    }

    /**
     * The any-word query narrowed to most of its words, or null when there is
     * no such stage. A query of several plain words parses under AND to one
     * required clause per word; one word parses to its disjunction across the
     * fields (optional clauses that are fields, not words), and is left alone.
     * Only plain words have the stage: a reader's {@code +w} or {@code -w}
     * makes the parser nest the plain words in a clause of their own, so the
     * top-level clauses are no longer the words, and such a query goes from
     * all words straight to any as before. Of plain words,
     * {@value #MOST_WORDS_PERCENT}% (rounded down) must match, from
     * {@value #MOST_WORDS_FROM} words.
     */
    static Query mostWords(Query all, Query any) {
        if (!(all instanceof BooleanQuery allWords)
                || allWords.clauses().stream().anyMatch(c -> c.occur() != BooleanClause.Occur.MUST)
                || !(any instanceof BooleanQuery anyWord)
                || anyWord.clauses().stream().anyMatch(c -> c.occur() != BooleanClause.Occur.SHOULD)) {
            return null;
        }
        int optional = anyWord.clauses().size();
        if (optional < MOST_WORDS_FROM) {
            return null;
        }
        BooleanQuery.Builder most = new BooleanQuery.Builder();
        anyWord.clauses().forEach(most::add);
        most.setMinimumNumberShouldMatch(optional * MOST_WORDS_PERCENT / 100);
        return most.build();
    }

    /**
     * A score-only clause rewarding the reader's words found together: as a
     * phrase within {@link #TITLE_PHRASE_SLOP} positions in the title, or
     * {@link #CONTENT_PHRASE_SLOP} in the body. Query syntax is read as
     * spaces. Null when the words analyse to fewer than two tokens.
     */
    static Query phraseBoost(String words, org.apache.lucene.analysis.Analyzer analyzer) {
        return phraseBoost(words, analyzer, FieldConstants.TITLE, FieldConstants.CONTENT);
    }

    /** {@link #phraseBoost(String, org.apache.lucene.analysis.Analyzer)} over these title and body fields. */
    private static Query phraseBoost(String words, org.apache.lucene.analysis.Analyzer analyzer,
            String titleField, String contentField) {
        String plain = NOT_A_WORD.matcher(words).replaceAll(" ").trim();
        org.apache.lucene.util.QueryBuilder builder = new org.apache.lucene.util.QueryBuilder(analyzer);
        Query title = builder.createPhraseQuery(titleField, plain, TITLE_PHRASE_SLOP);
        // one token (or none) is a term query (or null): no phrase to reward.
        // Same analyzer, same text: the body's phrase has the same tokens.
        if (title == null || title instanceof TermQuery) {
            return null;
        }
        Query content = builder.createPhraseQuery(contentField, plain, CONTENT_PHRASE_SLOP);
        return new BooleanQuery.Builder()
            .add(new BoostQuery(title, TITLE_PHRASE_BOOST), BooleanClause.Occur.SHOULD)
            .add(new BoostQuery(content, CONTENT_PHRASE_BOOST), BooleanClause.Occur.SHOULD)
            .build();
    }

    /** Words that analyse to nothing (only stop words, only punctuation) parse to an empty query. */
    private static boolean isEmpty(Query query) {
        return query instanceof BooleanQuery bq && bq.clauses().isEmpty();
    }

    /**
     * Ranks the parsed words by relevance, scaled by the recency factor, and
     * narrows them to the scope. The narrowing clauses are filters, so they
     * add nothing to a score; the phrase clause, when there is one, is
     * optional beside the required words, so it adds to a score but never
     * changes what matches.
     */
    private TopDocs run(IndexSearcher indexSearcher, Query words, Query phrase, long nowMillis)
            throws IOException {
        if (isEmpty(words)) {
            return NO_HITS;
        }
        Query ranked = phrase == null ? words
                : new BooleanQuery.Builder()
                    .add(words, BooleanClause.Occur.MUST)
                    .add(phrase, BooleanClause.Occur.SHOULD)
                    .build();
        BooleanQuery.Builder query = new BooleanQuery.Builder()
            .add(FunctionScoreQuery.boostByValue(ranked, new RecencySource(nowMillis)),
                    BooleanClause.Occur.MUST)
            // posts or pages, never both: a filter, so it changes no score
            .add(new TermQuery(new Term(FieldConstants.KIND,
                    kind.kindValue())), BooleanClause.Occur.FILTER);

        Term handleTerm = IndexUtil.handleTerm(weblogHandle);
        if (handleTerm != null) {
            query.add(new TermQuery(handleTerm), BooleanClause.Occur.FILTER);
        }
        if (siteHandles != null && !narrowToSite(query)) {
            return NO_HITS;
        }
        Term excludedTerm = IndexUtil.handleTerm(excludedHandle);
        if (excludedTerm != null) {
            query.add(new TermQuery(excludedTerm), BooleanClause.Occur.MUST_NOT);
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
        // Timestamp#toString, whose text order is time order. A page has
        // no publish time: in the index means published.
        if (kind.isDated()) {
            query.add(notFuture(manager.now()), BooleanClause.Occur.FILTER);
        }

        return indexSearcher.search(query.build(), DOC_LIMIT);
    }

    /**
     * Adds the site's filters: a post flagged for the site, of a blog in
     * {@link #siteHandles}. False when nothing can match: fail closed, no
     * eligible blog is no hit, never an unfiltered search.
     */
    private boolean narrowToSite(BooleanQuery.Builder query) {
        if (siteHandles.isEmpty()) {
            return false;
        }
        // the post's half of the discovery rule; a page's is its being in the index
        if (kind.isDated()) {
            query.add(new TermQuery(new Term(FieldConstants.SITE, FieldConstants.SITE_V)),
                    BooleanClause.Occur.FILTER);
        }
        java.util.List<BytesRef> handles = new java.util.ArrayList<>();
        for (String handle : siteHandles) {
            Term term = IndexUtil.handleTerm(handle);
            if (term != null) {
                handles.add(term.bytes());
            }
        }
        if (handles.isEmpty()) {
            return false;
        }
        query.add(new TermInSetQuery(FieldConstants.WEBSITE_HANDLE, handles), BooleanClause.Occur.FILTER);
        return true;
    }

    /** Only documents published at or before {@code now}: the filter every reader-facing query carries. */
    static Query notFuture(java.sql.Timestamp now) {
        return SortedDocValuesField.newSlowRangeQuery(FieldConstants.PUBLISHED, null,
                new BytesRef(now.toString()), true, true);
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

    /**
     * True when more documents matched than the {@value #DOC_LIMIT} considered,
     * so the hit count is a floor rather than the number of matches.
     */
    public boolean isCapped() {
        if (searchresults == null || searchresults.scoreDocs.length < DOC_LIMIT) {
            return false;
        }
        return searchresults.totalHits.value() > searchresults.scoreDocs.length
                || searchresults.totalHits.relation() == TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
    }

    /** The words as the query that produced the results (for marking them in a snippet), or null. */
    public Query getParsedQuery() {
        return parsedQuery;
    }

    /**
     * Narrows the search to posts flagged for the site whose blog is one of
     * {@code handles}: the blog half of the discovery rule, decided by the
     * caller at query time. An empty set finds nothing. A page search reads
     * the handle set only (pages carry no site flag).
     */
    public void restrictToSite(java.util.Set<String> handles) {
        this.siteHandles = java.util.Set.copyOf(handles);
    }

    /** Leaves out the posts of this blog. */
    public void excludeHandle(String handle) {
        this.excludedHandle = handle;
    }

    /** True when no post had every word and these results match some of them. */
    public synchronized boolean isAnyWordFallback() {
        return anyWordFallback;
    }

    // run() sets it on the pool thread, the caller reads it afterwards: the
    // same monitor that guards acquired and closed
    private synchronized void setAnyWordFallback(boolean value) {
        anyWordFallback = value;
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
