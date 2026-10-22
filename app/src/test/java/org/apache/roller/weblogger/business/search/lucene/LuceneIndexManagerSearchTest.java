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
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.runnable.ThreadManager;
import org.apache.roller.weblogger.business.search.SearchResultList;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.wrapper.WeblogEntryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LuceneIndexManager#search} over a private index: how it fails, and
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

    private Weblogger roller;
    private WeblogEntryManager entries;
    private LuceneIndexManager manager;

    @BeforeEach
    void setUp() throws Exception {
        roller = mock(Weblogger.class);
        entries = mock(WeblogEntryManager.class);
        when(roller.getWeblogEntryManager()).thenReturn(entries);
        when(roller.getUserManager()).thenReturn(mock(UserManager.class));
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
                () -> manager.search(WORD, "noindexblog", null, null, 0, 10, null));
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

        assertEquals(List.of("parse-1"), idsOf(manager.search(WORD, "parseblog", null, null, 0, 10, null)));

        WebloggerException ex = assertThrows(WebloggerException.class,
                () -> manager.search("\"" + WORD, "parseblog", null, null, 0, 10, null));
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

        SearchResultList all;
        try (SearchOperation siteWide = new SearchOperation(manager)) {
            siteWide.setTerm(WORD);
            siteWide.run();
            all = manager.convertHitsToEntryList(siteWide.getResults().scoreDocs,
                    siteWide, 0, 10, null, false, mock(URLStrategy.class));
        }

        assertEquals(Set.of("facet-1", "facet-2"), Set.copyOf(idsOf(all)));
        assertEquals(Set.of("mountains", "recipes"), all.getCategories());

        SearchResultList scoped = manager.search(WORD, "facethiking", null, null, 0, 10,
                mock(URLStrategy.class));
        assertEquals(List.of("facet-1"), idsOf(scoped));
        assertTrue(scoped.getCategories().isEmpty(),
                "a weblog-scoped search must not offer category facets: " + scoped.getCategories());
    }

    /**
     * A {@code lucene.analyzer.class} that names no loadable class, or a
     * class with no public no-argument constructor, falls back to Lucene's
     * {@link StandardAnalyzer} rather than leaving indexing and search with
     * no analyzer at all.
     */
    @Test
    void anAnalyzerClassThatCannotBeInstantiatedFallsBackToTheStandardAnalyzer() throws Exception {
        assertInstanceOf(StandardAnalyzer.class,
                analyzerConfiguredAs("org.apache.roller.NoSuchAnalyzerAnywhere"));
        // A real Analyzer, but its only constructors take a delegate and a limit.
        assertInstanceOf(StandardAnalyzer.class,
                analyzerConfiguredAs("org.apache.lucene.analysis.miscellaneous.LimitTokenCountAnalyzer"));
    }

    // ---------------------------------------------------------------- helpers

    private static Analyzer analyzerConfiguredAs(String className) throws Exception {
        Field field = WebloggerConfig.class.getDeclaredField("config");
        field.setAccessible(true);
        Properties config = (Properties) field.get(null);
        String previous = config.getProperty("lucene.analyzer.class");
        config.setProperty("lucene.analyzer.class", className);
        try {
            return LuceneIndexManager.getAnalyzer();
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
        LuceneIndexManager fixed = new LuceneIndexManager(roller) {
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

        assertEquals(List.of("now-1"), idsOf(fixed.search(WORD, "nowblog", null, null, 0, 10, null)));
        fixed.shutdown();
    }

    private void index(WeblogEntry entry) throws Exception {
        when(entries.getWeblogEntry(entry.getId())).thenReturn(entry);
        manager.addEntryIndexOperation(entry);
    }

    private static Weblog weblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setName(handle);
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

    private static List<String> idsOf(SearchResultList results) {
        return results.getResults().stream().map(WeblogEntryWrapper::getId).toList();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = LuceneIndexManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
