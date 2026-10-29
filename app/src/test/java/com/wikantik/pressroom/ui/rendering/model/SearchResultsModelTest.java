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

/* Created on March 8, 2023 */

package com.wikantik.pressroom.ui.rendering.model;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.search.IndexManagerTest;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogSearchRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.wikantik.pressroom.business.search.IndexManagerTest.createWeblogEntries;
import static com.wikantik.pressroom.ui.rendering.util.WeblogSearchRequest.SEARCH_SERVLET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchResultsModelTest {
    User testUser = null;
    Weblog testWeblog = null;
    private static final Logger log = LoggerFactory.getLogger(SearchResultsModelTest.class);

    @BeforeEach
    public void setUp() throws Exception {
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
    void testBasicOperation() throws Exception {

        IndexManager indexManager = TestUtils.weblogger().getIndexManager();
        WeblogEntryManager entryManager = TestUtils.weblogger().getWeblogEntryManager();

        List<WeblogEntry> entries = createWeblogEntries(testWeblog, indexManager, entryManager);

        try {
            SearchResultsModel model = executeSearch("Enterprise");
            assertEquals(1, model.getResults().size());
            int count = 0;
            for (Date midnight : model.getResults().keySet()) {
                Set<WeblogEntryWrapper> wrappers = model.getResults().get(midnight);
                count += wrappers.size();
            }
            assertEquals(2, count);

            model = executeSearch("Tholian");
            assertEquals(1, model.getResults().size());
            count = 0;
            for (Date midnight : model.getResults().keySet()) {
                Set<WeblogEntryWrapper> wrappers = model.getResults().get(midnight);
                count += wrappers.size();
            }
            assertEquals(1, count);

        } finally {
            for (WeblogEntry entry : entries) {
                indexManager.removeEntryIndexOperation(TestUtils.getManagedWeblogEntry(entry));
            }
            indexManager.removeWeblogIndex(testWeblog);
        }
    }

    /**
     * The hit count is every match, not the page that was read: it was set
     * from the size of the current page, so twelve matches read "10" and
     * "Showing 11 - 10 of total 10" on page 2, and the pager, which offers a
     * next page only when hits exceed offset plus limit, never offered one.
     */
    @Test
    void theHitCountIsEveryMatchAndThePagerOffersTheNextPage() throws Exception {
        IndexManager indexManager = TestUtils.weblogger().getIndexManager();
        int matches = SearchResultsModel.RESULTS_PER_PAGE + 2;
        try {
            for (int i = 0; i < matches; i++) {
                WeblogEntry entry = TestUtils.setupWeblogEntry("hits-entry-" + i, testWeblog, testUser);
                entry.setPubTime(new java.sql.Timestamp(System.currentTimeMillis() - 60_000L));
                TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(entry);
                TestUtils.endSession(true);
                indexManager.addEntryIndexOperation(TestUtils.getManagedWeblogEntry(entry));
            }
            SearchResultsModel model = executeSearch("blah");
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (model.getHits() != matches && System.nanoTime() < deadline) {
                Thread.sleep(50);
                model = executeSearch("blah");
            }

            assertEquals(matches, model.getHits(), "every match counts, not just this page");
            assertEquals(0, model.getOffset());
            assertEquals(SearchResultsModel.RESULTS_PER_PAGE, model.getLimit());
            assertNotNull(model.getWeblogEntriesPager().getNextLink(),
                    "twelve matches at ten a page must offer a second page");
        } finally {
            indexManager.removeWeblogIndex(testWeblog);
        }
    }

    SearchResultsModel executeSearch(String term) throws WebloggerException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getServletPath()).thenReturn(SEARCH_SERVLET);
        when(request.getRequestURL()).thenReturn(
            new StringBuffer(String.format("http://localhost/%s", SEARCH_SERVLET)));
        when(request.getPathInfo()).thenReturn(null);

        WeblogSearchRequest searchRequest = new WeblogSearchRequest(TestUtils.weblogger(), request);
        searchRequest.setWeblogHandle(testWeblog.getHandle());
        searchRequest.setQuery(term);

        WeblogPageRequest pageRequest = new WeblogPageRequest();

        Map<String, Object> initData = new HashMap<>();
        initData.put("searchRequest", searchRequest);
        initData.put("parsedRequest", pageRequest);
        initData.put("pageRequest", pageRequest);
        // The servlets supply both; the model no longer falls back to the
        // static for either (plan Task 10).
        initData.put("weblogger", TestUtils.weblogger());
        initData.put("urlStrategy", TestUtils.weblogger().getUrlStrategy());

        SearchResultsModel model = new SearchResultsModel();
        model.init(initData);
        return model;
    }


}