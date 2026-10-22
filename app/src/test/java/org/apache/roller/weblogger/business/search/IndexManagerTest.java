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
package org.apache.roller.weblogger.business.search;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.instancio.Instancio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.apache.roller.weblogger.ui.rendering.model.SearchResultsModel.RESULTS_PER_PAGE;
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
            // Indexing is asynchronous, and a search does not wait for a
            // write in progress (SearchDuringWriteTest): wait, bounded, for
            // the writes to land rather than trusting the sleep above.
            SearchResultList result = awaitHits(indexManager, "Enterprise", 2);
            assertEquals(2, result.getResults().size());

            result = indexManager.search("Tholian",
                testWeblog.getHandle(), null, testWeblog.getLocale(), 0, RESULTS_PER_PAGE,
                TestUtils.weblogger().getUrlStrategy());
            assertEquals(1, result.getResults().size());

        } finally {
            for (WeblogEntry entry : entries) {
                indexManager.removeEntryIndexOperation(TestUtils.getManagedWeblogEntry(entry));
            }
            indexManager.removeWeblogIndex(testWeblog);
        }
    }

    private SearchResultList awaitHits(IndexManager indexManager, String term, int expected)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        SearchResultList result;
        do {
            result = indexManager.search(term,
                testWeblog.getHandle(), null, testWeblog.getLocale(), 0, RESULTS_PER_PAGE,
                TestUtils.weblogger().getUrlStrategy());
            if (result.getResults().size() == expected) {
                break;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return result;
    }

    /**
     * Create some weblog entries, two with some Star Trek content
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

            entry.setLocale(testWeblog.getLocale());

            entryManager.saveWeblogEntry(entry);
            TestUtils.endSession(true);

            indexManager.addEntryIndexOperation(entry);
        }

        Thread.sleep(RollerConstants.SEC_IN_MS);
        return entries;
    }
}
