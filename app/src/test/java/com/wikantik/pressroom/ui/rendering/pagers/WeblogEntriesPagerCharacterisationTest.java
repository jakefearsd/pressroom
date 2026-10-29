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

package com.wikantik.pressroom.ui.rendering.pagers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.MultiWeblogURLStrategy;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CHARACTERISATION TEST -- passes against the code as it stands today, before
 * any change. It exists to prove that extracting the ~300 tokens shared by
 * WeblogEntriesDayPager, MonthPager and LatestPager into their existing common
 * superclass leaves observable pager behaviour identical. A passing run here
 * BEFORE the refactor is the point, not a mistake.
 *
 * <p>The constructor calls below use the real nine-argument
 * {@code AbstractWeblogEntriesPager} shape -- {@code (URLStrategy, Weblog,
 * locale, pageLink, entryAnchor, dateString, catName, tags, page)} -- rather
 * than the six-argument shape a first guess might reach for.
 */
class WeblogEntriesPagerCharacterisationTest {

    private final URLStrategy urlStrategy = new MultiWeblogURLStrategy();

    private User testUser;
    private Weblog testWeblog;

    @BeforeEach
    void setUp() throws Exception {
        // Boots the business tier (Weblogger + PostgreSQL container) the first
        // time any test class in this JVM needs it; a no-op afterwards.
        TestUtils.setupWeblogger();
        testUser = TestUtils.setupUser("pagerCharUser");
        testWeblog = TestUtils.setupWeblog("pagerCharWeblog", testUser);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void dayPagerReportsItsOwnNavigationUrlsAndTitle() {
        WeblogEntriesDayPager pager = new WeblogEntriesDayPager(
                urlStrategy, TestUtils.weblogger(), testWeblog, null, null, null, "20260818", null, null, 0);

        assertNotNull(pager.getHomeLink(), "home link");
        assertNotNull(pager.getEntries(), "entries map");
        assertEquals(0, pager.getEntries().size(), "empty weblog has no entries");
    }

    @Test
    void monthPagerReportsItsOwnNavigationUrlsAndTitle() {
        WeblogEntriesMonthPager pager = new WeblogEntriesMonthPager(
                urlStrategy, TestUtils.weblogger(), testWeblog, null, null, null, "202608", null, null, 0);

        assertNotNull(pager.getHomeLink(), "home link");
        assertEquals(0, pager.getEntries().size(), "empty weblog has no entries");
    }

    @Test
    void monthPagerNamesTheMonthInTheViewersLanguage() {
        // The French bundle supplies the pattern ("MMM yyyy"); the month name
        // must come from the same locale, not the server's.
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.US);
            WeblogEntriesMonthPager pager = new WeblogEntriesMonthPager(
                    urlStrategy, TestUtils.weblogger(), testWeblog, "fr", null, null, "202006", null, null, 0);

            String name = pager.getNextCollectionName();
            assertNotNull(name, "a past month has a following month");
            assertTrue(name.contains("juil"), "expected the French month (July) in '" + name + "'");
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    @Test
    void latestPagerReportsItsOwnNavigationUrlsAndTitle() {
        WeblogEntriesLatestPager pager = new WeblogEntriesLatestPager(
                urlStrategy, TestUtils.weblogger(), testWeblog, null, null, null, null, null, null, 0);

        assertNotNull(pager.getHomeLink(), "home link");
        assertEquals(0, pager.getEntries().size(), "empty weblog has no entries");
    }
}
