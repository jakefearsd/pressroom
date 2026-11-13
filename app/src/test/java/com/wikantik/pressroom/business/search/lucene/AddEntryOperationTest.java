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

import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.Map;

import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import com.wikantik.pressroom.business.EntryRenderer;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.plugins.PluginManager;
import com.wikantik.pressroom.business.shortcodes.ShortcodeExpander;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AddEntryOperation} has no production caller today -- only tests
 * reach {@code addEntryIndexOperation} directly (see its class javadoc) --
 * so this exercises {@code doRun()} directly rather than through
 * {@code IndexManager}.
 *
 * <p>{@code pressroom} is dereferenced unconditionally in {@code doRun()}
 * (getWeblogEntryManager, then release() in the finally), so the guard
 * belongs before either happens rather than in a redundant post-hoc check
 * (RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE).
 */
class AddEntryOperationTest {

    @Test
    void doRunWithoutAWebloggerLogsAndReturnsInsteadOfThrowing() {
        WeblogEntry data = new WeblogEntry();
        data.setId("does-not-matter");
        // The second null is the LuceneIndexManager, deliberately unmocked: doRun()
        // returns on the null-pressroom path before it is ever touched.
        AddEntryOperation op = new AddEntryOperation(null, null, data);

        assertDoesNotThrow(op::doRun);
    }

    // ------------------------------------------------- the indexed document

    private static final String FILLER = "lorem ";

    /** Indexes {@code entry} through a real {@link AddEntryOperation} into a temporary index. */
    private static IndexSearcher indexed(Path indexDir, WeblogEntry entry, EntryRenderer renderer)
            throws Exception {
        Weblogger pressroom = mock(Weblogger.class);
        WeblogEntryManager entries = mock(WeblogEntryManager.class);
        when(entries.getWeblogEntry(entry.getId())).thenReturn(entry);
        when(pressroom.getWeblogEntryManager()).thenReturn(entries);
        when(pressroom.getEntryRenderer()).thenReturn(renderer);

        LuceneIndexManager manager = mock(LuceneIndexManager.class);
        FSDirectory dir = FSDirectory.open(indexDir);
        when(manager.getIndexDirectory()).thenReturn(dir);
        when(manager.weblogger()).thenReturn(pressroom);

        new AddEntryOperation(pressroom, manager, entry).doRun();
        return new IndexSearcher(DirectoryReader.open(dir));
    }

    private static EntryRenderer realRenderer() throws Exception {
        PluginManager plugins = mock(PluginManager.class);
        MediaFileManager media = mock(MediaFileManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getMediaFileManager()).thenReturn(media);
        when(weblogger.getPluginManager()).thenReturn(plugins);
        when(weblogger.getUrlStrategy()).thenReturn(mock(URLStrategy.class));
        when(plugins.getWeblogEntryPlugins(org.mockito.ArgumentMatchers.any(Weblog.class)))
                .thenReturn(Map.of());
        return new EntryRenderer(ShortcodeExpander.builtIn(weblogger, media), plugins);
    }

    private static WeblogEntry publishedEntry(String text) throws Exception {
        Weblog weblog = new Weblog();
        weblog.setHandle("indexblog");
        weblog.setName("Salt Marsh Journal");
        weblog.setTagline("birds and boats");
        weblog.setLocale("en_US");
        weblog.setVisible(true);
        weblog.setActive(true);
        WeblogCategory category = new WeblogCategory();
        category.setName("Field Notes");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setCategory(category);
        entry.setAnchor("entry-one");
        entry.setTitle("Heron Watch");
        entry.setSummary("a short summary about egrets");
        entry.setText(text);
        entry.setLocale("en_US");
        entry.setStatus(PubStatus.PUBLISHED);
        entry.setPubTime(new Timestamp(1_700_000_000_000L));
        entry.setUpdateTime(new Timestamp(1_700_000_000_000L));
        entry.addTag("waders");
        entry.addTag("estuary");
        return entry;
    }

    /** The query a reader's words become: analyzed as at search time. */
    private static Query analyzed(String field, String words) throws Exception {
        return new QueryParser(field, new EnglishAnalyzer()).parse(words);
    }

    private static int hits(IndexSearcher searcher, Query query) throws Exception {
        return searcher.count(query);
    }

    @Test
    void wordAfterThe1000thTokenIsFound(@TempDir Path tmp) throws Exception {
        WeblogEntry entry = publishedEntry(FILLER.repeat(1500) + "zanzibar");
        IndexSearcher searcher = indexed(tmp, entry, realRenderer());

        assertEquals(1, hits(searcher, new QueryParser("content", new EnglishAnalyzer()).parse("zanzibar")));
    }

    @Test
    void tagSummaryCategoryAndBlogNameAreIndexed(@TempDir Path tmp) throws Exception {
        IndexSearcher searcher = indexed(tmp, publishedEntry("body words"), realRenderer());

        assertEquals(1, hits(searcher, analyzed(FieldConstants.TAGS, "waders")));
        assertEquals(1, hits(searcher, analyzed(FieldConstants.TAGS, "estuary")));
        assertEquals(1, hits(searcher, analyzed(FieldConstants.SUMMARY, "egrets")));
        assertEquals(1, hits(searcher, analyzed(FieldConstants.CATEGORY_TEXT, "field")));
        assertEquals(1, hits(searcher, analyzed(FieldConstants.BLOG, "marsh")));
        assertEquals(1, hits(searcher, analyzed(FieldConstants.BLOG, "boats")),
                "the tagline is part of the blog text");
        NumericDocValues pubnum = DocValues.getNumeric(
                searcher.getIndexReader().leaves().get(0).reader(), FieldConstants.PUBLISHED_NUM);
        assertTrue(pubnum.advanceExact(0));
        assertEquals(1_700_000_000_000L, pubnum.longValue());
    }

    @Test
    void siteFlagFollowsEntryEligibility(@TempDir Path tmp) throws Exception {
        IndexSearcher plain = indexed(tmp.resolve("plain"), publishedEntry("body"), realRenderer());
        assertEquals(1, hits(plain, new TermQuery(new Term(FieldConstants.SITE, FieldConstants.SITE_V))));

        WeblogEntry hidden = publishedEntry("body");
        hidden.setNoindex(true);
        IndexSearcher noindex = indexed(tmp.resolve("hidden"), hidden, realRenderer());
        assertEquals(1, noindex.getIndexReader().numDocs(), "a noindex post is still indexed for its own blog");
        assertEquals(0, hits(noindex, new TermQuery(new Term(FieldConstants.SITE, FieldConstants.SITE_V))));
    }

    @Test
    void stemmingMatchesWordForms(@TempDir Path tmp) throws Exception {
        IndexSearcher searcher = indexed(tmp, publishedEntry("we love remodeling kitchens"), realRenderer());

        assertEquals(1, hits(searcher, new QueryParser("content", new EnglishAnalyzer()).parse("remodel")));
    }

    @Test
    void contentIsStoredAsPlainText(@TempDir Path tmp) throws Exception {
        IndexSearcher searcher = indexed(tmp, publishedEntry("some **bold** words"), realRenderer());

        String stored = searcher.storedFields().document(0).get(FieldConstants.CONTENT);
        assertNotNull(stored);
        assertTrue(stored.contains("bold"), stored);
        assertFalse(stored.contains("**"), stored);
        assertFalse(stored.contains("<"), stored);
    }

    @Test
    void postThatFailsToRenderIsStillIndexedByTitle(@TempDir Path tmp) throws Exception {
        WeblogEntry entry = publishedEntry("body that cannot render");
        EntryRenderer broken = mock(EntryRenderer.class);
        when(broken.transformedText(entry)).thenThrow(new IllegalStateException("boom"));

        IndexSearcher searcher = indexed(tmp, entry, broken);

        assertEquals(1, searcher.getIndexReader().numDocs());
        assertEquals(1, hits(searcher, new QueryParser("title", new EnglishAnalyzer()).parse("heron")));
    }
}
