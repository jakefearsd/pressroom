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
package org.apache.roller.weblogger.business.search;

import java.util.List;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.search.IndexManager;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.wrapper.WeblogEntryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Searching the Lucene index: scoping, and keeping removed content out of it.
 *
 * <p>The one existing test indexes three entries into one weblog and searches
 * for a word. What it cannot show is the scoping -- that a search on one weblog
 * does not return another's entries -- or that an entry removed from the index
 * stops being findable. Both are user-visible: the first would surface a
 * neighbour's posts in your own site search, and the second leaves deleted
 * posts advertised in search results with links that 404.
 *
 * <p>Indexing is asynchronous. Every write here is followed by a wait on the
 * result rather than a sleep, so the test is bounded by the work rather than by
 * a guess at how long it takes.
 */
class SearchIndexQueryTest {

    private User user;
    private Weblog blog;
    private Weblog otherBlog;
    private WeblogCategory travel;
    private WeblogEntry spainPost;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();

        user = TestUtils.setupUser("searchUser");
        blog = TestUtils.setupWeblog("searchblog", user);
        otherBlog = TestUtils.setupWeblog("searchother", user);
        TestUtils.endSession(true);

        travel = TestUtils.setupWeblogCategory(TestUtils.getManagedWebsite(blog), "Travel");
        TestUtils.endSession(true);

        spainPost = indexed("spain-post", travel, blog,
                "Hiking in Spain", "the pyrenees are extraordinary quokkaword");
        indexed("other-post", null, otherBlog,
                "Also about Spain", "a different weblog entirely quokkaword");
        TestUtils.endSession(true);

        // addEntryIndexOperation only schedules the write. Without these waits
        // every test's first search raced the setUp's own indexing (it nearly
        // always returned with the write still in flight) and once passed only
        // because the search happened to queue behind it. Searches no longer
        // wait for a write in progress (SearchDuringWriteTest), so these waits
        // are the only thing ordering the two.
        awaitTitles(blog.getHandle(), titles -> titles.contains("Hiking in Spain"),
                "setUp's entry was never indexed");
        awaitTitles(otherBlog.getHandle(), titles -> titles.contains("Also about Spain"),
                "setUp's other-weblog entry was never indexed");
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(blog.getId());
        TestUtils.teardownWeblog(otherBlog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void aSearchFindsAnIndexedEntryByAWordInItsBody() throws Exception {
        SearchResultList results = search("quokkaword", blog.getHandle());

        assertTrue(titlesOf(results).contains("Hiking in Spain"),
                "got: " + titlesOf(results));
    }

    /**
     * The scoping that matters. Both weblogs contain the search term; a search
     * scoped to one must return only its own.
     */
    @Test
    void aSearchScopedToAWeblogDoesNotReturnAnothersEntries() throws Exception {
        List<String> titles = titlesOf(search("quokkaword", blog.getHandle()));

        assertTrue(titles.contains("Hiking in Spain"), "got: " + titles);
        assertFalse(titles.contains("Also about Spain"),
                "another weblog's entry must not appear in this weblog's search: " + titles);
    }

    @Test
    void aSearchForSomethingAbsentReturnsNothing() throws Exception {
        assertTrue(titlesOf(search("zzznosuchterm", blog.getHandle())).isEmpty());
    }

    /**
     * An entry taken out of the index stops being findable. Without this the
     * index keeps advertising posts that have been deleted, and every result
     * links to a 404.
     */
    @Test
    void anEntryRemovedFromTheIndexIsNoLongerFound() throws Exception {
        assertFalse(titlesOf(search("quokkaword", blog.getHandle())).isEmpty(),
                "the entry must be findable before it is removed");

        IndexManager index = TestUtils.weblogger().getIndexManager();
        index.removeEntryIndexOperation(TestUtils.getManagedWeblogEntry(spainPost));

        assertFalse(titlesOf(search("quokkaword", blog.getHandle()))
                        .contains("Hiking in Spain"),
                "a removed entry must stop being findable");
    }

    /**
     * Removing a whole weblog's index leaves other weblogs alone -- it runs
     * when a weblog is deleted, and taking the neighbours' index with it would
     * empty site search for everyone.
     */
    @Test
    void removingOneWeblogsIndexLeavesTheOthersIntact() throws Exception {
        IndexManager index = TestUtils.weblogger().getIndexManager();
        index.removeWeblogIndex(TestUtils.getManagedWebsite(blog));

        awaitTitles(blog.getHandle(), List::isEmpty,
                "the removed weblog's entries must be gone");
        assertTrue(titlesOf(search("quokkaword", otherBlog.getHandle()))
                        .contains("Also about Spain"),
                "the other weblog's index must be untouched");
    }

    /**
     * Re-indexing a weblog rebuilds what search can see, which is what the
     * admin "rebuild index" action exists to do after the index has drifted.
     */
    @Test
    void rebuildingAWeblogsIndexRestoresIt() throws Exception {
        IndexManager index = TestUtils.weblogger().getIndexManager();
        index.removeWeblogIndex(TestUtils.getManagedWebsite(blog));
        awaitTitles(blog.getHandle(), List::isEmpty, "the index must empty first");

        index.rebuildWeblogIndex(TestUtils.getManagedWebsite(blog));

        awaitTitles(blog.getHandle(), titles -> titles.contains("Hiking in Spain"),
                "a rebuild must put the weblog's entries back");
    }

    @Test
    void aSearchCanBeNarrowedToACategory() throws Exception {
        List<String> inTravel = titlesOf(TestUtils.weblogger().getIndexManager()
                .search("quokkaword", blog.getHandle(), "Travel", null, 0, 10,
                        TestUtils.weblogger().getUrlStrategy()));

        assertTrue(inTravel.contains("Hiking in Spain"), "got: " + inTravel);

        List<String> inNothing = titlesOf(TestUtils.weblogger().getIndexManager()
                .search("quokkaword", blog.getHandle(), "NoSuchCategory", null, 0, 10,
                        TestUtils.weblogger().getUrlStrategy()));

        assertTrue(inNothing.isEmpty(),
                "a category with nothing in it must return nothing: " + inNothing);
    }

    /**
     * Deleting a weblog takes its documents out of the index. Nothing called
     * removeWeblogIndex, so a deleted weblog's documents stayed forever: the
     * results page dropped them (their entries are gone) but they still
     * counted, and "N entries match" and the next-page link overcounted.
     * Checked on the raw index, under the results filter.
     */
    @Test
    void deletingAWeblogTakesItsDocumentsOutOfTheIndex() throws Exception {
        Weblog doomed = TestUtils.setupWeblog("searchdoomed", user);
        TestUtils.endSession(true);
        indexed("doomed-post", null, doomed, "Doomed", "soon gone quokkaword");
        TestUtils.endSession(true);
        awaitIndexedCount(doomed.getHandle(), 1);

        TestUtils.teardownWeblog(doomed.getId());
        TestUtils.endSession(true);

        awaitIndexedCount(doomed.getHandle(), 0);
    }

    // ---------------------------------------------------------------- helpers

    /** Polls the raw index (no results filter) until the weblog holds this many matching documents. */
    private static void awaitIndexedCount(String handle, int expected) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        int count = -2;
        while (System.nanoTime() < deadline) {
            try (org.apache.roller.weblogger.business.search.lucene.SearchOperation op =
                         new org.apache.roller.weblogger.business.search.lucene.SearchOperation(
                                 TestUtils.weblogger().getIndexManager())) {
                op.setTerm("quokkaword");
                op.setWeblogHandle(handle);
                op.run();
                count = op.getResultsCount();
            }
            if (count == expected) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(handle + ": expected " + expected + " indexed documents, still " + count);
    }

    private static SearchResultList search(String term, String handle) throws Exception {
        return TestUtils.weblogger().getIndexManager().search(
                term, handle, null, null, 0, 10,
                TestUtils.weblogger().getUrlStrategy());
    }

    /**
     * Polls the index until the titles for a weblog satisfy the condition, or
     * the deadline passes. Bulk index operations are queued, so asserting
     * immediately after one races it.
     */
    private static void awaitTitles(String handle,
            java.util.function.Predicate<List<String>> condition, String message)
            throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        List<String> titles = List.of();
        while (System.nanoTime() < deadline) {
            titles = titlesOf(search("quokkaword", handle));
            if (condition.test(titles)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(message + " -- still " + titles + " after 10s");
    }

    private static List<String> titlesOf(SearchResultList results) {
        return results.getResults().stream()
                .map(WeblogEntryWrapper::getTitle)
                .toList();
    }

    private WeblogEntry indexed(String anchor, WeblogCategory category, Weblog weblog,
            String title, String text) throws Exception {
        WeblogCategory cat = category != null ? category
                : TestUtils.setupWeblogCategory(TestUtils.getManagedWebsite(weblog),
                        "General-" + anchor);
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, cat, PubStatus.PUBLISHED,
                weblog, user);
        entry.setTitle(title);
        entry.setText(text);
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.weblogger().flush();

        TestUtils.weblogger().getIndexManager()
                .addEntryIndexOperation(TestUtils.getManagedWeblogEntry(entry));
        return entry;
    }

}
