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
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.index.IndexNotFoundException;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.runnable.ThreadManager;
import com.wikantik.pressroom.business.search.SearchHits;
import com.wikantik.pressroom.business.search.SearchSpec;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LuceneIndexManager#find} over a private index: how it fails, and
 * the category facet it reports. These are characterisation tests, written
 * against the existing behaviour and expected to pass as soon as they were
 * written.
 *
 * <p>The tier is a Mockito {@link Weblogger} whose {@link ThreadManager} runs
 * every index operation inline on the calling thread, so there is no
 * asynchronous queue to wait on: when {@code addEntryIndexOperation} returns,
 * the document is committed. Entries are plain pojos the mocked
 * {@link WeblogEntryManager} hands back by id, which is how every index
 * operation re-fetches what it was given. The index directory is a
 * {@code @TempDir}, swapped onto the manager reflectively, so the index the
 * rest of the test JVM shares is never touched.
 */
class LuceneIndexManagerSearchTest {

    private static final String WORD = "searchfacetword";

    @TempDir
    Path indexDir;

    private Weblogger pressroom;
    private WeblogEntryManager entries;
    private LuceneIndexManager manager;
    private WeblogManager weblogManager;
    /** Every blog {@link #weblog} made: what the mocked WeblogManager lists. */
    private final List<Weblog> knownBlogs = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        pressroom = mock(Weblogger.class);
        PassthroughRenderer.install(pressroom);
        entries = mock(WeblogEntryManager.class);
        when(pressroom.getWeblogEntryManager()).thenReturn(entries);
        when(pressroom.getUserManager()).thenReturn(mock(UserManager.class));
        weblogManager = mock(WeblogManager.class);
        when(weblogManager.getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenAnswer(inv -> List.copyOf(knownBlogs));
        when(pressroom.getWeblogManager()).thenReturn(weblogManager);
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

        manager = newManager();
    }

    /** A manager over this test's index directory and tier: a second one stands for a restart. */
    private LuceneIndexManager newManager() throws Exception {
        LuceneIndexManager made = new LuceneIndexManager(pressroom);
        setField(made, "indexDir", indexDir.toString());
        setField(made, "indexConsistencyMarker", new File(indexDir.toFile(), ".index-inconsistent"));
        return made;
    }

    /**
     * A directory holding no index is an error, not an empty result: the
     * searchers cannot be opened, and the search reports failure rather
     * than "no matches", which would be indistinguishable from a working
     * index that simply found nothing.
     */
    @Test
    void aSearchWithNoIndexOnDiskFailsRatherThanFindingNothing() {
        assertThrows(IndexNotFoundException.class, manager::acquireSearcher);

        WebloggerException ex = assertThrows(WebloggerException.class,
                () -> manager.find(new SearchSpec(WORD, SearchSpec.Scope.BLOG, "noindexblog", null, 0, 10), null));
        assertEquals("Error executing search", ex.getMessage());
    }

    /**
     * A query Lucene's parser rejects (an unclosed phrase) fails the search,
     * and the operation keeps the parser's message; the same index answers a
     * well-formed query, so the failure is the query's, not the index's.
     */
    @Test
    void aQueryTheParserRejectsFailsTheSearch() throws Exception {
        manager.initialize();
        Weblog blog = weblog("parseblog");
        index(entry("parse-1", blog, "General", "Parsable"));

        assertEquals(List.of("parse-1"), idsOf(manager.find(new SearchSpec(WORD, SearchSpec.Scope.BLOG, "parseblog", null, 0, 10), null)));

        WebloggerException ex = assertThrows(WebloggerException.class,
                () -> manager.find(new SearchSpec("\"" + WORD, SearchSpec.Scope.BLOG, "parseblog", null, 0, 10), null));
        assertEquals("Error executing search", ex.getMessage());

        try (SearchOperation op = new SearchOperation(manager)) {
            op.setTerm("\"" + WORD);
            op.run();
            assertEquals(-1, op.getResultsCount());
            assertNotNull(op.getParseError(), "the parser's complaint must be kept");
        }
    }

    /**
     * A site-wide search reports the (lower-cased) categories its hits came
     * from, so the results page can offer them as filters; a search scoped
     * to one weblog reports none.
     */
    @Test
    void aSiteWideSearchReportsTheCategoriesOfItsHits() throws Exception {
        manager.initialize();
        Weblog hiking = weblog("facethiking");
        Weblog cooking = weblog("facetcooking");
        index(entry("facet-1", hiking, "Mountains", "Up high"));
        index(entry("facet-2", cooking, "Recipes", "Soup"));

        SearchHits all;
        try (SearchOperation siteWide = new SearchOperation(manager)) {
            siteWide.setTerm(WORD);
            siteWide.run();
            all = manager.convertHitsToEntryList(siteWide.getResults().scoreDocs, siteWide,
                    new SearchSpec(WORD, SearchSpec.Scope.SITE, null, null, 0, 10), false,
                    mock(URLStrategy.class));
        }

        assertEquals(Set.of("facet-1", "facet-2"), Set.copyOf(idsOf(all)));
        assertEquals(Set.of("mountains", "recipes"), all.categories());

        SearchHits scoped = manager.find(new SearchSpec(WORD, SearchSpec.Scope.BLOG, "facethiking", null, 0, 10), mock(URLStrategy.class));
        assertEquals(List.of("facet-1"), idsOf(scoped));
        assertTrue(scoped.categories().isEmpty(),
                "a weblog-scoped search must not offer category facets: " + scoped.categories());
    }

    /**
     * A {@code lucene.analyzer.class} that names no loadable class, or a
     * class with no public no-argument constructor, falls back to Lucene's
     * {@link EnglishAnalyzer} (the default since schema version 2) rather than leaving indexing and search with
     * no analyzer at all.
     */
    @Test
    void anAnalyzerClassThatCannotBeInstantiatedFallsBackToTheEnglishAnalyzer() throws Exception {
        assertInstanceOf(EnglishAnalyzer.class,
                analyzerConfiguredAs("com.wikantik.pressroom.NoSuchAnalyzerAnywhere"));
        // A real Analyzer, but its only constructors take a delegate and a limit.
        assertInstanceOf(EnglishAnalyzer.class,
                analyzerConfiguredAs("org.apache.lucene.analysis.miscellaneous.LimitTokenCountAnalyzer"));
    }

    /**
     * One analyzer serves every parse, snippet and MoreLikeThis call; it used
     * to be built by reflection for each and never closed (final review, C3).
     */
    @Test
    void theAnalyzerIsSharedNotBuiltPerCall() throws Exception {
        assertTrue(manager.getAnalyzer() == manager.getAnalyzer(), "the same instance on every call");
    }

    /** A write closes its LimitTokenCountAnalyzer wrapper; the shared analyzer it wraps stays usable. */
    @Test
    void anIndexWriteLeavesTheSharedAnalyzerOpen() throws Exception {
        manager.initialize();
        Weblog blog = weblog("analyzerblog");
        index(entry("an-1", blog, "General", "One"));
        index(entry("an-2", blog, "General", "Two"));

        try (org.apache.lucene.analysis.TokenStream tokens = manager.getAnalyzer().tokenStream("content", "kitchens")) {
            org.apache.lucene.analysis.tokenattributes.CharTermAttribute term =
                    tokens.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
            tokens.reset();
            assertTrue(tokens.incrementToken());
            assertEquals("kitchen", term.toString());
            tokens.end();
        }
        assertEquals(2, find(WORD, "analyzerblog").total());
    }

    // ---------------------------------------------------------------- helpers

    private static Analyzer analyzerConfiguredAs(String className) throws Exception {
        Field field = WebloggerConfig.class.getDeclaredField("config");
        field.setAccessible(true);
        Properties config = (Properties) field.get(null);
        String previous = config.getProperty("lucene.analyzer.class");
        config.setProperty("lucene.analyzer.class", className);
        try {
            return LuceneIndexManager.newAnalyzer();
        } finally {
            if (previous == null) {
                config.remove("lucene.analyzer.class");
            } else {
                config.setProperty("lucene.analyzer.class", previous);
            }
        }
    }

    /**
     * An entry whose pubTime is exactly the instant the search treats as
     * "now" is published, not future. {@code TestUtils.setupWeblogEntry}
     * stamps {@code new Date()}, so a strict before-now filter dropped it
     * whenever the search ran in the same millisecond.
     */
    @Test
    void anEntryPublishedAtExactlyNowIsFound() throws Exception {
        Timestamp instant = new Timestamp(1_700_000_000_000L);
        LuceneIndexManager fixed = new LuceneIndexManager(pressroom) {
            @Override
            Timestamp now() {
                return instant;
            }
        };
        setField(fixed, "indexDir", indexDir.toString());
        setField(fixed, "indexConsistencyMarker", new File(indexDir.toFile(), ".index-inconsistent"));
        fixed.initialize();
        Weblog blog = weblog("nowblog");
        WeblogEntry atNow = entry("now-1", blog, "General", "AtNow");
        atNow.setPubTime(instant);
        WeblogEntry later = entry("now-2", blog, "General", "Later");
        later.setPubTime(new Timestamp(instant.getTime() + 1));
        when(entries.getWeblogEntry("now-1")).thenReturn(atNow);
        when(entries.getWeblogEntry("now-2")).thenReturn(later);
        fixed.addEntryIndexOperation(atNow);
        fixed.addEntryIndexOperation(later);

        assertEquals(List.of("now-1"), idsOf(fixed.find(new SearchSpec(WORD, SearchSpec.Scope.BLOG, "nowblog", null, 0, 10), null)));
        fixed.shutdown();
    }

    /**
     * The total a reader is told ("N entries match", "Showing 1-10 of N", and
     * whether there is a next page) counts only what the results page can
     * show. A future-dated entry is in the index but never on the page, so
     * it is left out by the query itself; a document whose entry has gone
     * (a stale document) is left out of the count once the page has seen it.
     */
    @Test
    void theTotalCountsOnlyWhatTheResultsCanShow() throws Exception {
        manager.initialize();
        Weblog blog = weblog("countblog");
        index(entry("count-1", blog, "General", "Live"));
        WeblogEntry future = entry("count-2", blog, "General", "Future");
        future.setPubTime(new Timestamp(System.currentTimeMillis() + 3_600_000));
        index(future);
        WeblogEntry gone = entry("count-3", blog, "General", "Gone");
        index(gone);
        when(entries.getWeblogEntry("count-3")).thenReturn(null);

        SearchHits results = manager.find(new SearchSpec(WORD, SearchSpec.Scope.BLOG, "countblog", null, 0, 10), mock(URLStrategy.class));

        assertEquals(List.of("count-1"), idsOf(results));
        assertEquals(1, results.total(), "the total must not count what the page dropped");
    }

    /**
     * A reader's query Lucene cannot run -- a regular expression too complex
     * to turn into an automaton, or more clauses than a search may hold --
     * is the reader's mistake, answered like any unparsable query. Only
     * ParseException was caught, so these runtime exceptions reached
     * ReadFromIndexOperation, which logged each anonymous request at ERROR
     * with a stack trace, and the operation recorded no parse error.
     */
    @Test
    void aQueryTooComplexToRunIsAParseError() throws Exception {
        manager.initialize();
        index(entry("complex-1", weblog("complexblog"), "General", "Complex"));
        String tooManyWords = String.join(" ",
                java.util.stream.IntStream.range(0, 1100).mapToObj(i -> "w" + i).toList());

        for (String term : List.of("/(a|b)*a(a|b){24}/", tooManyWords)) {
            String label = term.substring(0, Math.min(20, term.length()));
            try (SearchOperation op = new SearchOperation(manager)) {
                op.setTerm(term);
                op.run();
                assertEquals(-1, op.getResultsCount(), label);
                assertNotNull(op.getParseError(), "kept as the query's error: " + label);
            }
        }
    }

    // ------------------------------------------------------- ranking

    private static final long DAY = 24L * 60 * 60 * 1000;

    private SearchHits find(String words, String handle) throws Exception {
        return manager.find(new SearchSpec(words, SearchSpec.Scope.BLOG, handle, null, 0, 10),
                mock(URLStrategy.class));
    }

    private SearchHits findSite(String words, int offset, int limit) throws Exception {
        return manager.find(new SearchSpec(words, SearchSpec.Scope.SITE, null, null, offset, limit),
                mock(URLStrategy.class));
    }

    /** A post with chosen title, body and age, apart from the shared fixture word. */
    private WeblogEntry post(String id, Weblog blog, String title, String text, long ageDays) throws Exception {
        WeblogEntry entry = entry(id, blog, "General", title);
        entry.setText(text);
        Timestamp when = new Timestamp(System.currentTimeMillis() - ageDays * DAY);
        entry.setPubTime(when);
        entry.setUpdateTime(when);
        index(entry);
        return entry;
    }

    @Test
    void titleMatchOutranksBodyMentionOfTheSameAge() throws Exception {
        manager.initialize();
        Weblog blog = weblog("rankblog");
        post("body-1", blog, "Weekend notes", "we cooked a lot and the kitchen was warm", 10);
        post("title-1", blog, "The kitchen", "we cooked a lot and it was warm", 10);

        assertEquals(List.of("title-1", "body-1"), idsOf(find("kitchen", "rankblog")));
    }

    /** The weights reach the query the index runs, not just a constant. */
    @Test
    void theParsedQueryWeighsEachFieldAsSpecified() throws Exception {
        org.apache.lucene.search.BooleanQuery parsed = assertInstanceOf(
                org.apache.lucene.search.BooleanQuery.class,
                SearchOperation.parse("kitchen", org.apache.lucene.queryparser.classic.MultiFieldQueryParser
                        .Operator.AND, manager.getAnalyzer()));
        java.util.Map<String, Float> boosts = new java.util.HashMap<>();
        for (org.apache.lucene.search.BooleanClause clause : parsed.clauses()) {
            org.apache.lucene.search.Query q = clause.query();
            float boost = 1.0f;
            if (q instanceof org.apache.lucene.search.BoostQuery bq) {
                boost = bq.getBoost();
                q = bq.getQuery();
            }
            boosts.put(assertInstanceOf(org.apache.lucene.search.TermQuery.class, q).getTerm().field(), boost);
        }
        assertEquals(java.util.Map.of("title", 4.0f, "tags", 3.0f, "summary", 2.0f, "blog", 2.0f,
                "cattext", 1.5f, "content", 1.0f), boosts);
    }

    /** One-token fields of equal length on each side, so only the weights can order them. */
    @Test
    void aTagMatchOutranksASummaryMatchOfTheSameAge() throws Exception {
        manager.initialize();
        Weblog blog = weblog("weightblog");
        Timestamp when = new Timestamp(System.currentTimeMillis() - 5 * DAY);
        // indexed first, so a tie in score would put it first
        WeblogEntry summarised = entry("sum-1", blog, "General", "Plain one");
        summarised.setText("nothing relevant");
        summarised.setSummary("quokkaword");
        summarised.setPubTime(when);
        index(summarised);
        WeblogEntry tagged = entry("tag-2", blog, "General", "Plain two");
        tagged.setText("nothing relevant");
        tagged.addTag("quokkaword");
        tagged.setPubTime(when);
        index(tagged);

        assertEquals(List.of("tag-2", "sum-1"), idsOf(find("quokkaword", "weightblog")));
    }

    @Test
    void oldTitleMatchOutranksNewPassingMention() throws Exception {
        manager.initialize();
        Weblog blog = weblog("oldblog");
        post("new-1", blog, "Weekend notes", "we cooked a lot and the kitchen was warm", 1);
        post("old-1", blog, "The kitchen", "we cooked a lot and it was warm", 3 * 365);

        assertEquals(List.of("old-1", "new-1"), idsOf(find("kitchen", "oldblog")));
    }

    @Test
    void newerOfTwoEqualMatchesComesFirst() throws Exception {
        manager.initialize();
        Weblog blog = weblog("tieblog");
        post("tie-old", blog, "The kitchen", "same body here", 200);
        post("tie-new", blog, "The kitchen", "same body here", 1);

        assertEquals(List.of("tie-new", "tie-old"), idsOf(find("kitchen", "tieblog")));
    }

    @Test
    void tagOnlyAndBlogNameOnlyMatchesAreFound() throws Exception {
        manager.initialize();
        Weblog blog = weblog("zanzibarblog");
        WeblogEntry tagged = entry("tag-1", blog, "General", "Plain title");
        tagged.setText("nothing relevant");
        tagged.addTag("quokkatag");
        index(tagged);

        assertEquals(List.of("tag-1"), idsOf(find("quokkatag", "zanzibarblog")));
        assertEquals(List.of("tag-1"), idsOf(find("zanzibarblog", "zanzibarblog")));
    }

    @Test
    void allWordsThenAnyWord() throws Exception {
        manager.initialize();
        Weblog blog = weblog("anyblog");
        post("any-1", blog, "Kitchen", "pots and pans", 5);
        post("any-2", blog, "Savanna", "a zebra crossing", 5);

        SearchHits both = find("kitchen zebra", "anyblog");
        assertEquals(2, both.hits().size());
        assertTrue(both.anyWordFallback());
    }

    @Test
    void allWordsMatchDoesNotReportFallback() throws Exception {
        manager.initialize();
        Weblog blog = weblog("allblog");
        post("all-1", blog, "Kitchen", "a zebra crossing", 5);

        SearchHits hits = find("kitchen zebra", "allblog");
        assertEquals(1, hits.hits().size());
        assertTrue(!hits.anyWordFallback());
    }

    @Test
    void readerCannotQueryNonSearchedFields() throws Exception {
        manager.initialize();
        Weblog blog = weblog("fieldblog");
        blog.setName("Wombat Journal");
        WeblogEntry post = entry("fieldpost", blog, "General", "Plain");
        post.setText("only ordinary words here");
        index(post);
        assertEquals(List.of("fieldpost"), idsOf(findSite("plain", 0, 10)), "fixture is findable");

        // each would match the document if read as the field it names (its
        // handle, id and locale are all in the index); read as the
        // plain word after the colon, none of them is in the post
        for (String query : List.of("site:1", "blog:x", "handle:fieldblog", "username:nobody",
                "locale:en", "id:fieldpost")) {
            assertTrue(findSite(query, 0, 10).hits().isEmpty(), query);
        }
    }

    @Test
    void queryOfOnlyStopWordsFindsNothingWithoutError() throws Exception {
        manager.initialize();
        index(entry("stop-1", weblog("stopblog"), "General", "Plain"));

        for (String query : List.of("the", "!!!", "\"", "*", "the of and")) {
            SearchHits hits;
            try {
                hits = findSite(query, 0, 10);
            } catch (WebloggerException e) {
                throw new AssertionError("threw for: " + query, e);
            }
            assertEquals(0, hits.total(), query);
            assertTrue(hits.hits().isEmpty(), query);
        }
    }

    @Test
    void perBlogCountsTheHitsOfEachBlog() throws Exception {
        manager.initialize();
        Weblog a = weblog("countera");
        Weblog b = weblog("counterb");
        index(entry("pb-1", a, "General", "One"));
        index(entry("pb-2", a, "General", "Two"));
        index(entry("pb-3", b, "General", "Three"));

        SearchHits hits = findSite(WORD, 0, 1);

        assertEquals(1, hits.hits().size(), "only the page");
        assertEquals(java.util.Map.of("countera", 2, "counterb", 1), hits.perBlog(),
                "but the counts cover every hit considered");
    }

    @Test
    void offsetAndLimitPageThroughHits() throws Exception {
        manager.initialize();
        Weblog blog = weblog("pageblog");
        for (int i = 0; i < 5; i++) {
            post("pg-" + i, blog, "Page", WORD + " same body", i + 1);
        }

        List<String> first = idsOf(findSite(WORD, 0, 2));
        List<String> second = idsOf(findSite(WORD, 2, 2));
        List<String> third = idsOf(findSite(WORD, 4, 2));

        assertEquals(List.of("pg-0", "pg-1"), first);
        assertEquals(List.of("pg-2", "pg-3"), second);
        assertEquals(List.of("pg-4"), third);
        assertEquals(5, findSite(WORD, 2, 2).total());
    }

    /** A negative offset (an overflowed page number) is the first page, never an index out of bounds (C2). */
    @Test
    void aNegativeOffsetIsTheFirstPage() throws Exception {
        manager.initialize();
        Weblog blog = weblog("negoffset");
        for (int i = 0; i < 3; i++) {
            post("neg-" + i, blog, "Page", WORD + " same body", i + 1);
        }

        assertEquals(List.of("neg-0", "neg-1"), idsOf(manager.find(
                new SearchSpec(WORD, SearchSpec.Scope.BLOG, "negoffset", null, -2147483640, 2), mock(URLStrategy.class))));
    }

    // ------------------------------------------------------- scopes and eligibility

    private SearchHits findScoped(SearchSpec.Scope scope, String handle) throws Exception {
        return manager.find(new SearchSpec(WORD, scope, handle, null, 0, 20), mock(URLStrategy.class));
    }

    /** Blogs A, B, C (C hidden), a noindex post on B, a post in the site space. */
    private void scopeFixture() throws Exception {
        manager.initialize();
        Weblog a = weblog("scopea");
        Weblog b = weblog("scopeb");
        Weblog c = weblog("scopec");
        Weblog space = weblog("scopespace");
        space.setSiteSpace(true);
        index(entry("sc-a", a, "General", "A post"));
        index(entry("sc-b", b, "General", "B post"));
        WeblogEntry hidden = entry("sc-b-noindex", b, "General", "B hidden post");
        hidden.setNoindex(Boolean.TRUE);
        index(hidden);
        index(entry("sc-c", c, "General", "C post"));
        index(entry("sc-space", space, "General", "Site space post"));
        c.setVisible(Boolean.FALSE);
    }

    @Test
    void otherBlogsScopeLeavesOutTheNamedBlog() throws Exception {
        scopeFixture();
        assertEquals(List.of("sc-b"), idsOf(findScoped(SearchSpec.Scope.OTHER_BLOGS, "scopea")));
        assertEquals(List.of("sc-a"), idsOf(findScoped(SearchSpec.Scope.OTHER_BLOGS, "scopeb")));
    }

    @Test
    void otherBlogsAndSiteScopesLeaveOutNoindexPosts() throws Exception {
        scopeFixture();
        assertTrue(!idsOf(findScoped(SearchSpec.Scope.OTHER_BLOGS, "scopea")).contains("sc-b-noindex"));
        assertTrue(!idsOf(findScoped(SearchSpec.Scope.SITE, null)).contains("sc-b-noindex"));
    }

    @Test
    void otherBlogsAndSiteScopesLeaveOutHiddenInactiveAndSiteSpaceBlogs() throws Exception {
        scopeFixture();
        // inactive too, flipped after indexing
        knownBlogs.stream().filter(w -> w.getHandle().equals("scopeb")).findFirst().orElseThrow()
                .setActive(Boolean.FALSE);

        assertEquals(Set.of("sc-a"), Set.copyOf(idsOf(findScoped(SearchSpec.Scope.SITE, null))));
        assertEquals(List.of(), idsOf(findScoped(SearchSpec.Scope.OTHER_BLOGS, "scopea")));
    }

    @Test
    void otherBlogsAndSiteScopesLeaveOutFutureDatedPostsAndDrafts() throws Exception {
        scopeFixture();
        Weblog b = knownBlogs.stream().filter(w -> w.getHandle().equals("scopeb")).findFirst().orElseThrow();
        WeblogEntry future = entry("sc-b-future", b, "General", "B future post");
        future.setPubTime(new Timestamp(System.currentTimeMillis() + 3_600_000));
        index(future);
        WeblogEntry draft = entry("sc-b-draft", b, "General", "B draft post");
        draft.setStatus(PubStatus.DRAFT);
        index(draft);

        for (String id : List.of("sc-b-future", "sc-b-draft")) {
            assertTrue(!idsOf(findScoped(SearchSpec.Scope.OTHER_BLOGS, "scopea")).contains(id), id);
            assertTrue(!idsOf(findScoped(SearchSpec.Scope.SITE, null)).contains(id), id);
        }
        assertEquals(List.of("sc-b"), idsOf(findScoped(SearchSpec.Scope.OTHER_BLOGS, "scopea")),
                "the live post of the same blog is still found");
    }

    @Test
    void hidingABlogTakesEffectWithoutReindex() throws Exception {
        manager.initialize();
        Weblog a = weblog("hidea");
        Weblog b = weblog("hideb");
        index(entry("hide-a", a, "General", "A"));
        index(entry("hide-b", b, "General", "B"));
        assertEquals(Set.of("hide-a", "hide-b"), Set.copyOf(idsOf(findScoped(SearchSpec.Scope.SITE, null))));

        b.setVisible(Boolean.FALSE);

        assertEquals(List.of("hide-a"), idsOf(findScoped(SearchSpec.Scope.SITE, null)));
        assertEquals(List.of("hide-b"), idsOf(findScoped(SearchSpec.Scope.BLOG, "hideb")),
                "its own section still finds it");
    }

    /**
     * The query's own filters, before conversion can mask them: the
     * conversion re-check drops the same posts, so the hits the index
     * returns are counted directly.
     */
    private int indexHits(java.util.Set<String> handles, String excluded) throws Exception {
        try (SearchOperation op = new SearchOperation(manager)) {
            op.setTerm(WORD);
            op.restrictToSite(handles);
            op.excludeHandle(excluded);
            op.run();
            return op.getResultsCount();
        }
    }

    @Test
    void theQueryItselfNarrowsToTheSiteFlagAndTheHandleSet() throws Exception {
        scopeFixture();
        // scopea, scopeb (one site post, one noindex), scopec: three site-flagged posts in all
        assertEquals(3, indexHits(Set.of("scopea", "scopeb", "scopec"), null));
        assertEquals(1, indexHits(Set.of("scopea"), null), "handle set");
        assertEquals(1, indexHits(Set.of("scopeb"), null), "site flag leaves out the noindex post");
        assertEquals(0, indexHits(Set.of(), null), "an empty set finds nothing");
    }

    @Test
    void theQueryItselfLeavesOutTheNamedBlog() throws Exception {
        scopeFixture();
        assertEquals(2, indexHits(Set.of("scopea", "scopeb", "scopec"), "scopea"));
        assertEquals(1, indexHits(Set.of("scopea", "scopeb"), "scopea"));
    }

    @Test
    void siteCountsCoverOnlyTheEligibleBlogsPosts() throws Exception {
        scopeFixture();
        assertEquals(java.util.Map.of("scopea", 1, "scopeb", 1), findScoped(SearchSpec.Scope.SITE, null).perBlog());
    }

    @Test
    void blogScopeStillFindsItsOwnNoindexPost() throws Exception {
        scopeFixture();
        assertEquals(Set.of("sc-b", "sc-b-noindex"), Set.copyOf(idsOf(findScoped(SearchSpec.Scope.BLOG, "scopeb"))));
    }

    @Test
    void staleSiteFlagIsCaughtAtConversion() throws Exception {
        manager.initialize();
        Weblog a = weblog("stalea");
        WeblogEntry first = entry("stale-1", a, "General", "One");
        WeblogEntry second = entry("stale-2", a, "General", "Two");
        index(first);
        index(second);
        assertEquals(2, findScoped(SearchSpec.Scope.SITE, null).total());

        second.setNoindex(Boolean.TRUE); // the index still says site:1

        SearchHits hits = findScoped(SearchSpec.Scope.SITE, null);
        assertEquals(List.of("stale-1"), idsOf(hits));
        assertEquals(1, hits.total(), "the total drops what conversion dropped");
    }

    @Test
    void scopesNeedingABlogFailClosedWithoutOne() throws Exception {
        scopeFixture();
        for (SearchSpec.Scope scope : List.of(SearchSpec.Scope.BLOG, SearchSpec.Scope.OTHER_BLOGS)) {
            for (String handle : java.util.Arrays.asList(null, "", "   ")) {
                assertThrows(IllegalArgumentException.class, () -> findScoped(scope, handle),
                        scope + " with handle '" + handle + "'");
            }
        }
    }

    @Test
    void anUnreadableBlogListFindsNothingRatherThanEverything() throws Exception {
        scopeFixture();
        when(weblogManager.getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new WebloggerException("db down"));

        assertEquals(0, findScoped(SearchSpec.Scope.SITE, null).total());
        assertEquals(List.of(), idsOf(findScoped(SearchSpec.Scope.OTHER_BLOGS, "scopea")));
    }

    @Test
    void noEligibleBlogMeansNoSiteHits() throws Exception {
        scopeFixture();
        knownBlogs.clear();
        assertEquals(List.of(), idsOf(findScoped(SearchSpec.Scope.SITE, null)));
    }

    @Test
    void perBlogIsOnlyComputedForSiteScope() throws Exception {
        scopeFixture();
        assertTrue(findScoped(SearchSpec.Scope.BLOG, "scopeb").perBlog().isEmpty());
        assertTrue(findScoped(SearchSpec.Scope.OTHER_BLOGS, "scopea").perBlog().isEmpty());
        assertEquals(java.util.Map.of("scopea", 1, "scopeb", 1), findScoped(SearchSpec.Scope.SITE, null).perBlog());
    }

    @Test
    void snippetEscapesOnceAndFallsBackToExcerpt() throws Exception {
        manager.initialize();
        Weblog a = weblog("snipa");
        // a body match is marked, and the text is escaped
        WeblogEntry body = entry("snip-body", a, "General", "Plain");
        body.setText("Fish & <b>chips</b> with a quokkaword on top");
        index(body);
        // a title-only match has no body passage: the list excerpt stands in
        WeblogEntry titled = entry("snip-title", a, "General", "Quokkaword");
        titled.setText("nothing relevant");
        index(titled);
        when(pressroom.getEntryRenderer().cachedOpening(titled)).thenReturn("Tom & Jerry <i>go</i> out");

        java.util.Map<String, String> snippets = new java.util.HashMap<>();
        for (var hit : manager.find(new SearchSpec("quokkaword", SearchSpec.Scope.BLOG, "snipa", null, 0, 10),
                mock(URLStrategy.class)).hits()) {
            snippets.put(hit.entry().getId(), hit.snippetHtml());
        }

        assertEquals("Fish &amp; chips with a <mark>quokkaword</mark> on top", snippets.get("snip-body"));
        assertEquals("Tom &amp; Jerry &lt;i&gt;go&lt;/i&gt; out", snippets.get("snip-title"));
    }

    @Test
    void siteAndOtherBlogHitsAreMarkedOtherBlog() throws Exception {
        scopeFixture();
        assertTrue(findScoped(SearchSpec.Scope.SITE, null).hits().stream().allMatch(h -> h.otherBlog()));
        assertTrue(findScoped(SearchSpec.Scope.OTHER_BLOGS, "scopea").hits().stream().allMatch(h -> h.otherBlog()));
        assertTrue(findScoped(SearchSpec.Scope.BLOG, "scopeb").hits().stream().noneMatch(h -> h.otherBlog()));
    }

    // ------------------------------------------------------- handles keep their case

    /**
     * Handles are case-sensitive: the unique constraint and the lookup by
     * handle compare them exactly, and handles may use A-Z. A blog whose
     * handle has capitals was indexed verbatim and queried lowercased, so it
     * was found in no scope (final review, A1).
     */
    @Test
    void aMixedCaseHandleIsFoundInEveryScope() throws Exception {
        manager.initialize();
        Weblog mixed = weblog("MixedCase");
        Weblog plain = weblog("plaincase");
        index(entry("mx-1", mixed, "General", "Mixed one"));
        index(entry("mx-2", mixed, "General", "Mixed two"));
        index(entry("pl-1", plain, "General", "Plain one"));

        assertEquals(Set.of("mx-1", "mx-2"), Set.copyOf(idsOf(find(WORD, "MixedCase"))));
        assertEquals(Set.of("mx-1", "mx-2", "pl-1"), Set.copyOf(idsOf(findScoped(SearchSpec.Scope.SITE, null))));
        assertEquals(Set.of("mx-1", "mx-2"), Set.copyOf(idsOf(findScoped(SearchSpec.Scope.OTHER_BLOGS, "plaincase"))));
        assertEquals(List.of("pl-1"), idsOf(findScoped(SearchSpec.Scope.OTHER_BLOGS, "MixedCase")));
        assertEquals(java.util.Map.of("MixedCase", 2, "plaincase", 1), findScoped(SearchSpec.Scope.SITE, null).perBlog(),
                "the counts are keyed by the handle as stored, so the page can link the blog");
    }

    /** Two blogs may differ only by case (ws_handle_uq is case-sensitive); each search keeps them apart. */
    @Test
    void blogsWhoseHandlesDifferOnlyByCaseStayApart() throws Exception {
        manager.initialize();
        Weblog upper = weblog("TwinBlog");
        Weblog lower = weblog("twinblog");
        index(entry("twin-upper", upper, "General", "Upper"));
        index(entry("twin-lower", lower, "General", "Lower"));

        assertEquals(List.of("twin-upper"), idsOf(find(WORD, "TwinBlog")));
        assertEquals(List.of("twin-lower"), idsOf(find(WORD, "twinblog")));
        assertEquals(List.of("twin-lower"), idsOf(findScoped(SearchSpec.Scope.OTHER_BLOGS, "TwinBlog")));
        assertEquals(List.of("twin-upper"), idsOf(findScoped(SearchSpec.Scope.OTHER_BLOGS, "twinblog")));
    }

    /** Characterisation: moreLikeThis already matched the handle verbatim; pinned so both paths stay one rule. */
    @Test
    void moreLikeThisWorksForAMixedCaseHandle() throws Exception {
        manager.initialize();
        kitchenFixture(weblog("MixedKitchen"));

        assertEquals(Set.of("k2", "k3"), Set.copyOf(manager.moreLikeThis(entries.getWeblogEntry("k1"), 2)));
    }

    @Test
    void rebuildingAMixedCaseBlogLeavesOneDocumentPerPost() throws Exception {
        manager.initialize();
        Weblog mixed = weblog("MixedRebuild");
        mixed.setId("blog-mixed-rebuild");
        WeblogEntry first = entry("mr-1", mixed, "General", "One");
        WeblogEntry second = entry("mr-2", mixed, "General", "Two");
        index(first);
        index(second);
        when(weblogManager.getWeblog("blog-mixed-rebuild")).thenReturn(mixed);
        when(entries.getWeblogEntries(any())).thenReturn(List.of(first, second));

        manager.rebuildWeblogIndex(mixed);
        manager.rebuildWeblogIndex(mixed);

        assertEquals(1, documentsWithId("mr-1"));
        assertEquals(1, documentsWithId("mr-2"));
        SearchHits hits = find(WORD, "MixedRebuild");
        assertEquals(List.of("mr-1", "mr-2"), idsOf(hits).stream().sorted().toList());
        assertEquals(2, hits.total());
    }

    @Test
    void removingAMixedCaseBlogLeavesNothingBehind() throws Exception {
        manager.initialize();
        Weblog mixed = weblog("MixedRemove");
        mixed.setId("blog-mixed-remove");
        index(entry("rm-1", mixed, "General", "One"));
        when(weblogManager.getWeblog("blog-mixed-remove")).thenReturn(mixed);

        assertEquals(1, documentsWithId("rm-1"));

        manager.removeWeblogIndex(mixed);

        assertEquals(0, documentsWithId("rm-1"));
    }

    /** Documents in the index with this entry id, whatever any search filter would make of them. */
    private int documentsWithId(String id) throws Exception {
        org.apache.lucene.search.IndexSearcher searcher = manager.acquireSearcher();
        try {
            return searcher.count(new org.apache.lucene.search.TermQuery(
                    new org.apache.lucene.index.Term(FieldConstants.ID, id)));
        } finally {
            manager.releaseSearcher(searcher);
        }
    }

    // ------------------------------------------------------- the schema marker certifies a completed build

    private String schemaMarker() throws Exception {
        Path marker = indexDir.resolve(".index-schema");
        return java.nio.file.Files.isRegularFile(marker) ? java.nio.file.Files.readString(marker).trim() : null;
    }

    /**
     * The marker used to be written when the empty index was created, before
     * the rebuild it promised: a rebuild that failed (or a JVM stopped during
     * it) left a current marker over an empty index, and the next start
     * trusted it and never rebuilt (final review, A2).
     */
    @Test
    void aFailedRebuildLeavesNoCurrentMarkerAndTheNextStartRebuildsAgain() throws Exception {
        when(entries.getWeblogEntries(any())).thenThrow(new WebloggerException("db down"));
        manager.initialize(); // a brand-new directory: the full rebuild runs inline, and fails
        assertEquals(null, schemaMarker(), "nothing was built, so nothing is certified");
        manager.shutdown();

        LuceneIndexManager restarted = newManager();
        restarted.initialize();

        assertTrue(restarted.isInconsistentAtStartup(), "the next start must rebuild");
        org.mockito.Mockito.verify(entries, org.mockito.Mockito.times(2)).getWeblogEntries(any());
        restarted.shutdown();
    }

    /** Characterisation: a site with nothing to index still completes a build, and the next start trusts it. */
    @Test
    void aCompletedFullRebuildWritesTheMarkerAndTheNextStartTrustsIt() throws Exception {
        manager.initialize();
        assertEquals(String.valueOf(LuceneIndexManager.SCHEMA_VERSION), schemaMarker());
        manager.shutdown();

        LuceneIndexManager restarted = newManager();
        restarted.initialize();

        org.junit.jupiter.api.Assertions.assertFalse(restarted.isInconsistentAtStartup());
        restarted.shutdown();
    }

    /**
     * One post that cannot be turned into a document (here a missing update
     * time) is logged and skipped; it used to roll back the whole rebuild.
     */
    @Test
    void aRebuildSkipsAPostItCannotIndexAndKeepsTheRest() throws Exception {
        manager.initialize();
        Weblog blog = weblog("skipblog");
        WeblogEntry first = entry("skip-1", blog, "General", "One");
        WeblogEntry broken = entry("skip-broken", blog, "General", "Broken");
        broken.setUpdateTime(null);
        WeblogEntry second = entry("skip-2", blog, "General", "Two");
        when(entries.getWeblogEntries(any())).thenReturn(List.of(first, broken, second));

        List<String> warnings;
        try (LogLines logged = LogLines.of(RebuildWebsiteIndexOperation.class)) {
            manager.rebuildWeblogIndex();
            warnings = logged.at(org.apache.logging.log4j.Level.WARN);
        }

        assertEquals(1, documentsWithId("skip-1"));
        assertEquals(1, documentsWithId("skip-2"));
        assertEquals(0, documentsWithId("skip-broken"));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("skip-broken"), warnings.get(0));
        assertEquals(String.valueOf(LuceneIndexManager.SCHEMA_VERSION), schemaMarker(), "the rebuild still completed");
    }

    /** The entry locale column is nullable; a post without one is indexed, with no locale term. */
    @Test
    void anEntryWithNoLocaleIsIndexed() throws Exception {
        manager.initialize();
        WeblogEntry entry = entry("no-locale", weblog("localeblog"), "General", "No locale");
        entry.setLocale(null);
        index(entry);

        assertEquals(1, documentsWithId("no-locale"));
    }

    // ------------------------------------------------------- rename detection survives a restart

    /**
     * The names noteWeblog compares against were only ever learned from a
     * save, so the first rename after every restart (every deploy) was not
     * reindexed (final review, A3).
     */
    @Test
    void aRenameIsCaughtOnTheFirstSaveAfterARestart() throws Exception {
        manager.initialize();
        Weblog blog = weblog("renameblog");
        blog.setId("blog-rename");
        blog.setName("Old name");
        WeblogEntry post = entry("rn-1", blog, "General", "A post");
        index(post);
        manager.shutdown();

        LuceneIndexManager restarted = newManager();
        restarted.initialize(); // the index is current: no rebuild, the names are read
        org.junit.jupiter.api.Assertions.assertFalse(restarted.isInconsistentAtStartup());
        blog.setName("Zebraword gazette");
        when(weblogManager.getWeblog("blog-rename")).thenReturn(blog);
        when(entries.getWeblogEntries(any())).thenReturn(List.of(post));

        restarted.noteWeblog(blog);

        assertEquals(List.of("rn-1"), idsOf(restarted.find(
                new SearchSpec("zebraword", SearchSpec.Scope.BLOG, "renameblog", null, 0, 10), mock(URLStrategy.class))));
        restarted.shutdown();
    }

    @Test
    void aCompletedFullRebuildLearnsTheNames() throws Exception {
        Weblog blog = weblog("seedblog");
        blog.setId("blog-seed");
        blog.setName("Old name");
        WeblogEntry post = entry("seed-1", blog, "General", "A post");
        when(entries.getWeblogEntry("seed-1")).thenReturn(post);
        when(entries.getWeblogEntries(any())).thenReturn(List.of(post));
        when(weblogManager.getWeblog("blog-seed")).thenReturn(blog);
        manager.initialize(); // brand-new: the full rebuild runs, indexes the post, and reads the names

        blog.setName("Zebraword gazette");
        manager.noteWeblog(blog);

        assertEquals(List.of("seed-1"), idsOf(find("zebraword", "seedblog")));
    }

    // ------------------------------------------------------- what the index holds (final review, A4)

    @Test
    void theSummaryIsIndexedAsDecodedText() throws Exception {
        manager.initialize();
        WeblogEntry entry = entry("sum-1", weblog("sumblog"), "General", "Plain");
        entry.setSummary("Fish &amp; quokkachips");
        index(entry);

        assertEquals(List.of("sum-1"), idsOf(find("quokkachips", "sumblog")));
        assertEquals(List.of(), idsOf(find("amp", "sumblog")), "an entity is not a word");
    }

    @Test
    void aBlogWithNoNameOrTaglineIndexesNoWordNull() throws Exception {
        manager.initialize();
        Weblog blog = weblog("namelessblog");
        // setName(null) stores "" and the column is NOT NULL, so a null name
        // only arrives some other way; the field is set directly to stand for it
        Field name = Weblog.class.getDeclaredField("name");
        name.setAccessible(true);
        name.set(blog, null);
        blog.setTagline(null);
        index(entry("nameless-1", blog, "General", "Plain"));

        assertEquals(1, documentsWithId("nameless-1"));
        assertEquals(List.of(), idsOf(find("null", "namelessblog")));
    }

    /** A document without stored title or body gives MoreLikeThis nothing to go on, not the word "null". */
    @Test
    void moreLikeThisReadsAMissingStoredFieldAsNoText() throws Exception {
        manager.initialize();
        Weblog blog = weblog("mltnull");
        post("np-1", blog, "Null pointers", "A null pointer and a null check.", 3);
        post("np-2", blog, "More null", "Null again, and null once more.", 4);
        try (org.apache.lucene.index.IndexWriter writer = new org.apache.lucene.index.IndexWriter(
                manager.getIndexDirectory(), new org.apache.lucene.index.IndexWriterConfig(new EnglishAnalyzer()))) {
            org.apache.lucene.document.Document bare = new org.apache.lucene.document.Document();
            bare.add(new org.apache.lucene.document.StringField(FieldConstants.ID, "bare",
                    org.apache.lucene.document.Field.Store.YES));
            bare.add(new org.apache.lucene.document.StringField(FieldConstants.WEBSITE_HANDLE, "mltnull",
                    org.apache.lucene.document.Field.Store.YES));
            bare.add(new org.apache.lucene.document.SortedDocValuesField(FieldConstants.WEBSITE_HANDLE,
                    new org.apache.lucene.util.BytesRef("mltnull")));
            writer.addDocument(bare);
        }
        manager.refreshSearcher();
        WeblogEntry bareEntry = entry("bare", blog, "General", "Bare");

        assertEquals(List.of(), manager.moreLikeThis(bareEntry, 5));
    }

    /** Site counts read the handle from doc values, not up to 500 stored documents. */
    @Test
    void theHandleIsASortedDocValue() throws Exception {
        manager.initialize();
        index(entry("dv-1", weblog("dvblog"), "General", "One"));

        org.apache.lucene.search.IndexSearcher searcher = manager.acquireSearcher();
        try {
            org.apache.lucene.index.FieldInfo handle = org.apache.lucene.index.FieldInfos
                    .getMergedFieldInfos(searcher.getIndexReader()).fieldInfo(FieldConstants.WEBSITE_HANDLE);
            assertEquals(org.apache.lucene.index.DocValuesType.SORTED, handle.getDocValuesType());
        } finally {
            manager.releaseSearcher(searcher);
        }
    }

    /** Hits come in score order across segments; the counts must not depend on it. */
    @Test
    void perBlogCountsHitsAcrossSegmentsInAnyOrder() throws Exception {
        manager.initialize();
        Weblog a = weblog("segmenta");
        Weblog b = weblog("segmentb");
        // each index() is its own commit, so its own segment; ages interleave the score order
        for (int i = 0; i < 6; i++) {
            post("seg-" + i, i % 2 == 0 ? a : b, "Segment " + i, WORD + " body", 6 - i);
        }
        post("seg-6", a, "Segment 6", WORD + " body", 20);

        assertEquals(java.util.Map.of("segmenta", 4, "segmentb", 3), findSite(WORD, 0, 2).perBlog());
    }

    // ------------------------------------------------------- more like this

    private void kitchenFixture(Weblog blog) throws Exception {
        post("k1", blog, "Renovating the kitchen", "We chose walnut cabinets and a granite countertop. "
                + "The plumber moved the sink and the cabinets arrived late.", 30);
        post("k2", blog, "Kitchen cabinets finished", "The granite countertop went in and the walnut "
                + "cabinets were hung, then the sink was plumbed.", 40);
        post("k3", blog, "Countertop choices", "Granite or quartz for the kitchen countertop, and walnut "
                + "cabinets to match; the sink goes under the window.", 50);
        for (int i = 0; i < 5; i++) {
            post("u" + i, blog, "Day " + i + " in Lisbon", "Trams climb the hills of Lisbon, we ate "
                    + "pastries by the river and watched ferries cross number" + i + ".", i);
        }
    }

    @Test
    void moreLikeThisFindsPostsOnTheSameSubjectWithoutTags() throws Exception {
        manager.initialize();
        Weblog blog = weblog("mltblog");
        kitchenFixture(blog);
        WeblogEntry k1 = entries.getWeblogEntry("k1");

        List<String> ids = manager.moreLikeThis(k1, 2);

        assertEquals(Set.of("k2", "k3"), Set.copyOf(ids));
        assertEquals(2, ids.size());
    }

    @Test
    void moreLikeThisNeverReturnsTheEntryItself() throws Exception {
        manager.initialize();
        Weblog blog = weblog("mltself");
        kitchenFixture(blog);

        List<String> ids = manager.moreLikeThis(entries.getWeblogEntry("k2"), 20);

        assertTrue(!ids.contains("k2"), ids.toString());
        assertEquals(List.of("k1", "k3"), ids.subList(0, 2).stream().sorted().toList());
    }

    @Test
    void moreLikeThisStaysInsideTheBlog() throws Exception {
        manager.initialize();
        Weblog blog = weblog("mltone");
        kitchenFixture(blog);
        Weblog other = weblog("mlttwo");
        post("o1", other, "Our kitchen too", "Walnut cabinets, a granite countertop and a new sink "
                + "in the kitchen.", 1);

        List<String> ids = manager.moreLikeThis(entries.getWeblogEntry("k1"), 20);

        assertTrue(!ids.contains("o1"), ids.toString());
        assertTrue(ids.containsAll(List.of("k2", "k3")), ids.toString());
    }

    @Test
    void moreLikeThisSkipsFuturePosts() throws Exception {
        manager.initialize();
        Weblog blog = weblog("mltfuture");
        kitchenFixture(blog);
        post("future", blog, "Kitchen cabinets next year", "Walnut cabinets, granite countertop, sink.", -30);

        assertTrue(!manager.moreLikeThis(entries.getWeblogEntry("k1"), 20).contains("future"));
    }

    @Test
    void moreLikeThisIsEmptyForAnEntryNotInTheIndex() throws Exception {
        manager.initialize();
        Weblog blog = weblog("mltnone");
        kitchenFixture(blog);

        assertEquals(List.of(), manager.moreLikeThis(entry("ghost", blog, "General", "Ghost kitchen"), 3));
    }

    @Test
    void moreLikeThisIsEmptyWhenSearchIsDisabledOrTheIndexFails() throws Exception {
        Weblog blog = weblog("mltoff");
        WeblogEntry e = entry("e1", blog, "General", "Anything");
        assertEquals(List.of(), manager.moreLikeThis(e, 3), "no index on disk: logged, empty");
        setField(manager, "searchEnabled", false);
        assertEquals(List.of(), manager.moreLikeThis(e, 3));
    }

    private void index(WeblogEntry entry) throws Exception {
        when(entries.getWeblogEntry(entry.getId())).thenReturn(entry);
        manager.addEntryIndexOperation(entry);
    }

    private Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setName(handle);
        knownBlogs.add(weblog);
        return weblog;
    }

    private static WeblogEntry entry(String id, Weblog weblog, String category, String title) {
        WeblogCategory cat = new WeblogCategory();
        cat.setName(category);
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        entry.setWebsite(weblog);
        entry.setCategory(cat);
        entry.setTitle(title);
        entry.setText(WORD + " is in the body of " + id);
        entry.setLocale("en");
        entry.setCreatorUserName("nobody");
        entry.setStatus(PubStatus.PUBLISHED);
        Timestamp past = new Timestamp(System.currentTimeMillis() - 60_000);
        entry.setPubTime(past);
        entry.setUpdateTime(past);
        return entry;
    }

    private static List<String> idsOf(SearchHits results) {
        return results.hits().stream().map(h -> h.entry().getId()).toList();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = LuceneIndexManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
