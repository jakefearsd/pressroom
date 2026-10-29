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
package com.wikantik.pressroom.ui.rendering.servlets;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import org.eclipse.persistence.internal.databaseaccess.DatabaseCall;
import org.eclipse.persistence.jpa.JpaHelper;
import org.eclipse.persistence.sessions.SessionEvent;
import org.eclipse.persistence.sessions.SessionEventAdapter;
import org.eclipse.persistence.sessions.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How many times reading a page of entries reads {@code
 * pressroom_weblogentrytag}: the Atom feed through the real feed servlet (it
 * lists every entry's tags as categories) and an entry list as the API
 * reads it. The themes' list pages show no tags; a permalink shows one
 * entry's.
 *
 * <p>An entry's tags are a lazy collection. Once loaded, they ride along on
 * the entry in EclipseLink's shared cache, so a page whose entries are all
 * cached reads no tags at all. But that cache holds only the 100 most
 * recently used entries (EclipseLink's default soft-weak identity map); past
 * that an entry is weakly held, and the next garbage collection drops it. A
 * list then rebuilds its entries from the rows and, before batch fetching,
 * read each one's tags separately: one query per entry. A 2026-10-22
 * profiling run (10 weblogs x ~3,000 entries) made 418,547 such reads in 4.5
 * minutes, and concurrent feed renders queued in EclipseLink's synchronized
 * value holder, each waiting out another thread's read of the same entry's
 * tags. Emptying the entry cache here stands in for that eviction.
 *
 * <p>The other half is that tags are still never stale: an edit, a new entry
 * and a delete each show on the next render, whether or not the cached entry
 * had loaded its tags.
 */
class EntryTagsFetchRenderingTest {

    private static final String HANDLE = "entrytagsfetchblog";
    private static final int ENTRIES = 8;
    private static final int THREADS = 4;

    /** A read of the entry-tag table itself; the trailing \b keeps out the tag-count table. */
    private static final Pattern TAG_READ = Pattern.compile(
            "(?is)^\\s*SELECT\\b.*\\bFROM\\s+pressroom_weblogentrytag\\b");

    private User user;
    private Weblog weblog;
    private final List<WeblogEntry> entries = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("entrytagsfetchuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
        for (int i = 0; i < ENTRIES; i++) {
            entries.add(taggedEntry("tagged-" + i, "only" + i + " shared"));
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- the count

    @Test
    void aPageOfEntriesTheCacheHasDroppedReadsTheirTagsInOneQuery() throws Exception {
        render(); // warm the theme and template caches
        dropCachedEntries();

        Rendered page = renderCounting();

        for (int i = 0; i < ENTRIES; i++) {
            assertTrue(page.body.contains(tagLink("only" + i)), "entry " + i + "'s tag is on the page");
        }
        assertEquals(1, page.tagReads,
                "the " + ENTRIES + " entries' tags are one read, not one per entry");
    }

    @Test
    void anEntryListReadThroughTheManagerReadsItsTagsInOneQuery() throws Exception {
        dropCachedEntries();
        AtomicInteger reads = new AtomicInteger();
        List<String> names = new ArrayList<>();
        try (Counter _ = new Counter(reads)) {
            // as the entries API's list does: one query, then each entry's tags
            WeblogEntrySearchCriteria criteria = new WeblogEntrySearchCriteria();
            criteria.setWeblog(TestUtils.getManagedWebsite(weblog));
            criteria.setMaxResults(21); // the API's default page of 20, plus its has-more row
            for (WeblogEntry entry : entryManager().getWeblogEntries(criteria)) {
                names.add(entry.getTagsAsString());
            }
        } finally {
            TestUtils.endSession(false);
        }
        assertEquals(ENTRIES, names.size());
        assertTrue(names.contains("only0 shared"), names.toString());
        assertEquals(1, reads.get(), "the list's tags are one read, not one per entry");
    }

    /**
     * Characterisation, passing before batch fetching too: tags loaded once
     * stay on the cached entry, so a page of cached entries reads none.
     */
    @Test
    void aPageOfCachedEntriesReadsNoTagsAtAll() throws Exception {
        render();
        Rendered page = renderCounting();
        assertTrue(page.body.contains(tagLink("only0")), page.body);
        assertEquals(0, page.tagReads);
    }

    /**
     * The contention half: concurrent renders of the same entries, freshly
     * rebuilt, each needing their tags. Before batch fetching, the 8 entries
     * read their tags 8 times, every other thread waiting on each entry's
     * synchronized lazy load in turn.
     */
    @Test
    @Timeout(120)
    void concurrentRendersOfADroppedPageShareTheirTagReads() throws Exception {
        render();
        dropCachedEntries();

        AtomicInteger reads = new AtomicInteger();
        CyclicBarrier start = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<String>> pages = new ArrayList<>();
        try (Counter _ = new Counter(reads)) {
            for (int t = 0; t < THREADS; t++) {
                Callable<String> renderer = () -> {
                    start.await(30, TimeUnit.SECONDS);
                    return render();
                };
                pages.add(pool.submit(renderer));
            }
            for (Future<String> page : pages) {
                assertTrue(page.get(60, TimeUnit.SECONDS).contains(tagLink("only" + (ENTRIES - 1))));
            }
        } finally {
            pool.shutdownNow();
        }
        // Each read now covers the entries one render's query built, so
        // there are at most as many reads as renders, whatever the entries.
        assertTrue(reads.get() >= 1 && reads.get() <= THREADS,
                THREADS + " concurrent renders of " + ENTRIES + " dropped entries read tags "
                        + reads.get() + " times");
    }

    // ------------------------------------------------------------ never stale
    //
    // Characterisation: these passed before batch fetching too, and pin that
    // it changed nothing about when an edit, a new entry or a delete shows.

    @Test
    void aTagEditShowsOnTheNextRenderOfACachedPage() throws Exception {
        assertTrue(render().contains(tagLink("only3")));

        retag(entries.get(3), "renamed3 shared");

        String body = render();
        assertTrue(body.contains(tagLink("renamed3")), body);
        assertFalse(body.contains(tagLink("only3")), "the removed tag is gone:\n" + body);
    }

    @Test
    void aTagEditShowsWhenTheCachedEntryNeverLoadedItsTags() throws Exception {
        dropCachedEntries();
        // what a reindex does: every entry read (unbounded), tags never touched
        WeblogEntrySearchCriteria criteria = new WeblogEntrySearchCriteria();
        criteria.setWeblog(TestUtils.getManagedWebsite(weblog));
        assertEquals(ENTRIES, entryManager().getWeblogEntries(criteria).size());
        TestUtils.endSession(false);

        retag(entries.get(5), "renamed5");

        String body = render();
        assertTrue(body.contains(tagLink("renamed5")), body);
        assertFalse(body.contains(tagLink("only5")), body);
        assertTrue(body.contains(tagLink("only4")), "the other entries keep theirs:\n" + body);
    }

    @Test
    void aNewEntryShowsWithItsTags() throws Exception {
        render();
        taggedEntry("tagged-new", "brandnew");
        assertTrue(render().contains(tagLink("brandnew")));
    }

    @Test
    void aDeletedEntryTakesItsTagsOffThePage() throws Exception {
        assertTrue(render().contains(tagLink("only2")));

        WeblogEntryManager mgr = entryManager();
        mgr.removeWeblogEntry(mgr.getWeblogEntry(entries.get(2).getId()));
        TestUtils.endSession(true);

        String body = render();
        assertFalse(body.contains(tagLink("only2")), body);
        assertTrue(body.contains(tagLink("only1")), body);
    }

    // ---------------------------------------------------------------- helpers

    private WeblogEntry taggedEntry(String anchor, String tags) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, TestUtils.getManagedWebsite(weblog),
                TestUtils.getManagedUser(user));
        entry.setTagsAsString(tags);
        entryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);
        return entry;
    }

    private void retag(WeblogEntry entry, String tags) throws Exception {
        WeblogEntryManager mgr = entryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setTagsAsString(tags);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
    }

    private static String tagLink(String name) {
        return "<category term=\"" + name + "\" />";
    }

    private static WeblogEntryManager entryManager() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    /** The weblog's Atom feed, rendered afresh: the feed servlet has no cache escape. */
    private static String render() throws Exception {
        RenderingTestSupport.clearRenderCaches();
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/feed", "/" + HANDLE + "/entries/atom");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.feedServlet(), request);
        assertEquals(200, response.getStatus());
        return response.getContentAsString();
    }

    private record Rendered(String body, int tagReads) {
    }

    private static Rendered renderCounting() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        String body;
        try (Counter _ = new Counter(reads)) {
            body = render();
        }
        return new Rendered(body, reads.get());
    }

    /** As though the cache's weak references had been collected: no cached entry survives. */
    private static void dropCachedEntries() throws Exception {
        server().getIdentityMapAccessor().initializeIdentityMap(WeblogEntry.class);
    }

    private static Server server() throws Exception {
        Field f = entryManager().getClass().getDeclaredField("strategy");
        f.setAccessible(true);
        JPAPersistenceStrategy strategy = (JPAPersistenceStrategy) f.get(entryManager());
        return JpaHelper.getServerSession(strategy.getEntityManager(false).getEntityManagerFactory());
    }

    /**
     * Counts SQL statements reading the entry-tag table, on every session of
     * the persistence unit: a lazy load that fills the shared cache runs on
     * the server session, not the request's unit of work.
     */
    private static final class Counter extends SessionEventAdapter implements AutoCloseable {
        private final AtomicInteger reads;
        private final Server server;

        Counter(AtomicInteger reads) throws Exception {
            this.reads = reads;
            this.server = server();
            server.getEventManager().addListener(this);
        }

        @Override
        public void preExecuteCall(SessionEvent event) {
            if (event.getCall() instanceof DatabaseCall call && TAG_READ.matcher(call.getSQLString()).find()) {
                reads.incrementAndGet();
            }
        }

        @Override
        public void close() {
            server.getEventManager().removeListener(this);
        }
    }
}
