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
package com.wikantik.pressroom.business.search;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.instancio.Instancio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.wikantik.pressroom.ui.rendering.model.SearchResultsModel.RESULTS_PER_PAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test Search Manager business layer operations.
 */
public class IndexManagerTest {
    User testUser = null;
    Weblog testWeblog = null;
    private static final Logger log = LoggerFactory.getLogger(IndexManagerTest.class);

    /**
     * All tests in this suite require a user and a weblog.
     */
    @BeforeEach
    public void setUp() throws Exception {
        
        // setup weblogger
        TestUtils.setupWeblogger();
        
        try {
            testUser = TestUtils.setupUser("entrytestuser");
            testWeblog = TestUtils.setupWeblog("entrytestweblog", testUser);
            TestUtils.endSession(true);
        } catch (Exception ex) {
            log.error("ERROR in test setup", ex);
            throw new Exception("Test setup failed", ex);
        }
    }

    @AfterEach
    public void tearDown() throws Exception {
        
        try {
            TestUtils.teardownWeblog(testWeblog.getId());
            TestUtils.teardownUser(testUser.getUserName());
            TestUtils.endSession(true);
        } catch (Exception ex) {
            log.error("ERROR in test teardown", ex);
            throw new Exception("Test teardown failed", ex);
        }
    }

    @Test
    public void testBasicOperation() throws Exception {

        IndexManager indexManager = TestUtils.weblogger().getIndexManager();
        WeblogEntryManager entryManager = TestUtils.weblogger().getWeblogEntryManager();

        List<WeblogEntry> entries = createWeblogEntries(testWeblog, indexManager, entryManager);

        try {
            SearchHits result = indexManager.find(new SearchSpec("Enterprise", SearchSpec.Scope.BLOG, testWeblog.getHandle(), null, 0, RESULTS_PER_PAGE, testWeblog.getLocale()), TestUtils.weblogger().getUrlStrategy());
            assertEquals(2, result.hits().size());

            result = indexManager.find(new SearchSpec("Tholian", SearchSpec.Scope.BLOG, testWeblog.getHandle(), null, 0, RESULTS_PER_PAGE, testWeblog.getLocale()), TestUtils.weblogger().getUrlStrategy());
            assertEquals(1, result.hits().size());

        } finally {
            for (WeblogEntry entry : entries) {
                indexManager.removeEntryIndexOperation(TestUtils.getManagedWeblogEntry(entry));
            }
            indexManager.removeWeblogIndex(testWeblog);
        }
    }

    // ------------------------------------------------------- pages

    private static WeblogPage newPage(Weblog weblog, String slug, String title, String body) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent(body);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        return page;
    }

    /** Re-reads the page, applies the change, saves it through the page manager and commits. */
    private static void savePage(String id, java.util.function.Consumer<WeblogPage> change) throws Exception {
        WeblogPageManager pages = TestUtils.weblogger().getWeblogPageManager();
        WeblogPage page = pages.getPage(id);
        change.accept(page);
        pages.savePage(page);
        TestUtils.endSession(true);
    }

    /**
     * The page manager owns "a page changed, so reindex it": a save or a
     * removal through it reaches the index once it commits, whichever caller
     * made it (the editor, the Pages screen, the API, a strand's parts).
     * Each "not found" follows a "found", so none of them passes for want of
     * a write having landed yet.
     */
    @Test
    public void aPageSavedOrRemovedThroughThePageManagerReachesTheIndex() throws Exception {
        IndexManager index = TestUtils.weblogger().getIndexManager();
        WeblogPageManager pages = TestUtils.weblogger().getWeblogPageManager();
        WeblogPage page = newPage(testWeblog, "ocelot", "Visiting the ocelotpage", "Feeding times are by the zebragate.");
        pages.savePage(page);
        TestUtils.endSession(true);
        String id = page.getId();
        String handle = testWeblog.getHandle();

        awaitPages(index, "ocelotpage", SearchSpec.Scope.BLOG, handle, List.of(id));
        awaitPages(index, "zebragate", SearchSpec.Scope.BLOG, handle, List.of(id));

        savePage(id, p -> p.setStatus(WeblogPage.PubStatus.DRAFT));
        awaitPages(index, "ocelotpage", SearchSpec.Scope.BLOG, handle, List.of());

        savePage(id, p -> p.setStatus(WeblogPage.PubStatus.PUBLISHED));
        awaitPages(index, "ocelotpage", SearchSpec.Scope.BLOG, handle, List.of(id));

        savePage(id, p -> p.setNoindex(Boolean.TRUE));
        awaitPages(index, "ocelotpage", SearchSpec.Scope.BLOG, handle, List.of());

        savePage(id, p -> p.setNoindex(Boolean.FALSE));
        awaitPages(index, "ocelotpage", SearchSpec.Scope.BLOG, handle, List.of(id));

        pages.removePage(pages.getPage(id));
        TestUtils.endSession(true);
        awaitPages(index, "ocelotpage", SearchSpec.Scope.BLOG, handle, List.of());
    }

    /** Site-space pages are found by the site-wide search, never by a blog's own. */
    @Test
    public void aSitePageIsFoundSiteWideAndNotInABlogsSearch() throws Exception {
        IndexManager index = TestUtils.weblogger().getIndexManager();
        WeblogPageManager pages = TestUtils.weblogger().getWeblogPageManager();
        Weblog site = TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(testUser.getUserName()));
        TestUtils.endSession(true);
        try {
            WeblogPage sitePage = newPage(site, "lemurpage-site", "The lemurpage of the site", "body");
            pages.savePage(sitePage);
            WeblogPage blogPage = newPage(testWeblog, "lemurpage-blog", "The lemurpage of the blog", "body");
            pages.savePage(blogPage);
            TestUtils.endSession(true);

            awaitPages(index, "lemurpage", SearchSpec.Scope.SITE, null, List.of(sitePage.getId(), blogPage.getId()));
            assertEquals(List.of(blogPage.getId()), index.findPages(new SearchSpec("lemurpage",
                    SearchSpec.Scope.BLOG, testWeblog.getHandle(), null, 0, RESULTS_PER_PAGE), 10));
        } finally {
            com.wikantik.pressroom.business.WeblogManager weblogs = TestUtils.weblogger().getWeblogManager();
            Weblog space = weblogs.getSiteSpace();
            if (space != null) {
                weblogs.removeWeblog(space);
            }
            TestUtils.endSession(true);
        }
    }

    /** Polls findPages until it returns these ids (in any order), or fails after 10 s. */
    static void awaitPages(IndexManager index, String words, SearchSpec.Scope scope, String handle,
            List<String> expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<String> found;
        do {
            found = index.findPages(new SearchSpec(words, scope, handle, null, 0, RESULTS_PER_PAGE), 10);
            if (java.util.Set.copyOf(found).equals(java.util.Set.copyOf(expected)) && found.size() == expected.size()) {
                return;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("'" + words + "' still found pages " + found + ", not " + expected
                + ", 10 s after the write committed");
    }

    /**
     * Create some weblog entries, two with some Star Trek content, and
     * return once both are searchable.
     *
     * <p>Indexing is asynchronous, and a search does not wait for a write in
     * progress ({@code SearchDuringWriteTest}), so this waits, bounded, for
     * the two Star Trek entries to be found rather than sleeping a fixed
     * time and hoping: every caller searches for them straight away.
     */
    public static List<WeblogEntry> createWeblogEntries(
        Weblog testWeblog,
        IndexManager indexManager,
        WeblogEntryManager entryManager) throws Exception {

        List<WeblogEntry> entries = Instancio.ofList(WeblogEntry.class).size(10).create();

        entries.get(0).setTitle("The Tholian Web");
        entries.get(0).setPubTime(new Timestamp(System.currentTimeMillis()));
        // Instancio otherwise assigns a random PubStatus (DRAFT/PENDING/
        // SCHEDULED/TRASHED as well as PUBLISHED) -- the search path only
        // ever resolves published entries by design (see
        // LuceneIndexManager#convertHitsToEntryList and AddEntryOperation),
        // so the two entries this test expects to be findable must be
        // explicitly PUBLISHED rather than left to chance.
        entries.get(0).setStatus(WeblogEntry.PubStatus.PUBLISHED);
        entries.get(0).setText(
            "When the Enterprise attempts to ascertain the fate of the  "
                +"U.S.S. Defiant which vanished 3 weeks ago, the warp engines  "
                +"begin to lose power, and Spock reports strange sensor readings.");

        Thread.sleep(500);

        entries.get(1).setTitle("A Piece of the Action");
        entries.get(1).setPubTime(new Timestamp(System.currentTimeMillis()));
        entries.get(1).setStatus(WeblogEntry.PubStatus.PUBLISHED);
        entries.get(1).setText(
            "The crew of the Enterprise attempts to make contact with "
                +"the inhabitants of planet Sigma Iotia II, and Uhura puts Kirk "
                +"in communication with Boss Oxmyx.");

        // save and index those entries

        for (WeblogEntry entry : entries) {

            // fill in relationship fields to make JPA happy

            WeblogCategory cat = entryManager.getWeblogCategory(
                TestUtils.categoryNamed(testWeblog, "General").getId());
            entry.setCategory(cat);
            entry.setWebsite(TestUtils.getManagedWebsite(testWeblog));
            entry.setEntryAttributes(Collections.emptySet());
            entry.setTags(Collections.emptySet());
            // Instancio fills strandPageId with a random string, which the
            // V048 foreign key to pressroom_weblogpage refuses.
            entry.setStrandPageId(null);
            entry.setStrandPosition(null);

            entry.setLocale(testWeblog.getLocale());

            entryManager.saveWeblogEntry(entry);
            TestUtils.endSession(true);

            indexManager.addEntryIndexOperation(entry);
        }

        awaitHits(testWeblog, indexManager, "Enterprise", 2);
        return entries;
    }

    private static void awaitHits(Weblog weblog, IndexManager indexManager, String term, int expected)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        int found;
        do {
            found = indexManager.find(new SearchSpec(term, SearchSpec.Scope.BLOG, weblog.getHandle(), null, 0, RESULTS_PER_PAGE, weblog.getLocale()), TestUtils.weblogger().getUrlStrategy()).hits().size();
            if (found == expected) {
                return;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("'" + term + "' still found " + found + " entries, not "
                + expected + ", 10 s after indexing was scheduled");
    }
}
