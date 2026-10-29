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

package com.wikantik.pressroom.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;

/**
 * Test Weblog Category related business operations.
 */
public class WeblogCategoryFunctionalityTest  {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogCategoryFunctionalityTest.class);
    
    User testUser = null;
    Weblog testWeblog = null;
    WeblogCategory cat1 = null;
    WeblogCategory cat2 = null;
    WeblogCategory cat3 = null;
    WeblogCategory testCat = null;
    
    
    /**
     * All tests in this suite require a user and a weblog.
     */
    @BeforeEach
    public void setUp() {
        
        log.info("BEGIN");
        
        try {
            // setup weblogger
            TestUtils.setupWeblogger();
            
            testUser = TestUtils.setupUser("categoryTestUser");
            testWeblog = TestUtils.setupWeblog("categoryTestWeblog", testUser);
            
            // setup several categories for testing
            cat1 = TestUtils.setupWeblogCategory(testWeblog, "catTest-cat1");
            cat2 = TestUtils.setupWeblogCategory(testWeblog, "catTest-cat2");
            cat3 = TestUtils.setupWeblogCategory(testWeblog, "catTest-cat3");
            
            // a simple test cat at the root level
            testCat = TestUtils.setupWeblogCategory(testWeblog, "catTest-testCat");
            
            TestUtils.endSession(true);
        } catch (Throwable t) {
            log.error("ERROR in setup", t);
        }
        
        log.info("END");
    }

    @AfterEach
    public void tearDown() {
        
        log.info("BEGIN");
        
        try {
            TestUtils.teardownWeblog(testWeblog.getId());
            TestUtils.teardownUser(testUser.getUserName());
            TestUtils.endSession(true);
        } catch (Throwable t) {
            log.error("ERROR in teardown", t);
        }
        
        log.info("END");
    }

    /**
     * Test the hasCategory() method on WeblogCategory.
     */
    @Test
    public void testHasCategory() throws Exception {
        
        log.info("BEGIN");

        // check that root has category
        assertTrue(testWeblog.hasCategory(testCat.getName()));
        
        log.info("END");
    }
    
    
    /**
     * Lookup category by id.
     */
    @Test
    public void testLookupCategoryById() throws Exception {
        
        log.info("BEGIN");
        
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        
        WeblogCategory cat = mgr.getWeblogCategory(testCat.getId());
        assertNotNull(cat);
        assertEquals(cat, testCat);
        
        log.info("END");
    }
    
    
    /**
     * Lookup category by name.
     */
    @Test
    public void testLookupCategoryByName() throws Exception {
        
        log.info("BEGIN");
        
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        WeblogCategory cat = mgr.getWeblogCategoryByName(testWeblog, "catTest-cat1");
        assertNotNull(cat);
        assertEquals(cat, cat1);
        
        cat = mgr.getWeblogCategoryByName(testWeblog, "catTest-cat3");
        assertNotNull(cat);
        assertEquals(cat, cat3);
        
        // test lazy lookup, specifying just a name without slashes
        cat = mgr.getWeblogCategoryByName(testWeblog, "catTest-cat1");
        assertNotNull(cat);
        assertEquals(cat, cat1);

        log.info("END");
    }
    
    
    /**
     * Lookup all categories for a weblog.
     */
    @Test
    public void testLookupAllCategoriesByWeblog() throws Exception {
        
        log.info("BEGIN");
        
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        List<WeblogCategory> cats = mgr.getWeblogCategories(testWeblog);
        assertNotNull(cats);
        assertEquals(5, cats.size());
        
        log.info("END");
    }

    /**
     * Test moving entries in category to new category.
     */
    @Test
    public void testMoveWeblogCategoryContents() throws Exception {
        log.info("BEGIN");
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry e1 = null;
        WeblogEntry e2 = null; 
        try {

            testWeblog = TestUtils.getManagedWebsite(testWeblog);
            testUser = TestUtils.getManagedUser(testUser);

            // add some categories and entries to test with
            WeblogCategory c1 = new WeblogCategory(testWeblog, "c1", null, null);
            mgr.saveWeblogCategory(c1);

            WeblogCategory dest = new WeblogCategory(testWeblog, "dest", null, null);
            mgr.saveWeblogCategory(dest);

            TestUtils.endSession(true);

            c1 = mgr.getWeblogCategory(c1.getId());
            dest = mgr.getWeblogCategory(dest.getId());

            testWeblog = TestUtils.getManagedWebsite(testWeblog);
            testUser = TestUtils.getManagedUser(testUser);
            e1 = TestUtils.setupWeblogEntry("e1", c1, testWeblog, testUser);
            e2 = TestUtils.setupWeblogEntry("e2", c1, PubStatus.DRAFT, testWeblog, testUser);

            TestUtils.endSession(true);

            // need to query for cats again since session was closed
            c1 = mgr.getWeblogCategory(c1.getId());
            dest = mgr.getWeblogCategory(dest.getId());

            // verify number of entries in each category
            assertEquals(0, entriesIn(dest, true).size());
            assertEquals(0, entriesIn(dest, false).size());
            assertEquals(2, entriesIn(c1, false).size());
            assertEquals(1, entriesIn(c1, true).size());

            // move contents of source category c1 to destination category dest
            mgr.moveWeblogCategoryContents(c1, dest);
            mgr.saveWeblogCategory(c1);
            TestUtils.endSession(true);

            // after move, verify number of entries in each category
            dest = mgr.getWeblogCategory(dest.getId());
            c1 = mgr.getWeblogCategory(c1.getId());

            // Hierarchy is flattened under dest      
            assertEquals(2, entriesIn(dest, false).size());
            assertEquals(1, entriesIn(dest, true).size());

            // c1 category should be empty now
            assertEquals(0, entriesIn(c1, false).size());

        } finally {
            mgr.removeWeblogEntry(TestUtils.getManagedWeblogEntry(e1));
            mgr.removeWeblogEntry(TestUtils.getManagedWeblogEntry(e2));
        }
        
        log.info("END");
    }

    /**
     * Entries never change weblog: the tag aggregates count a tag row under
     * the weblog it was written with (TagAggregateStore). A move into another
     * weblog's category is refused, and nothing moves.
     */
    @Test
    public void testMoveWeblogCategoryContentsRefusesAnotherWeblogsCategory() throws Exception {
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        Weblog other = TestUtils.setupWeblog("categoryTestOther", testUser);
        WeblogCategory foreign = TestUtils.setupWeblogCategory(other, "catTest-foreign");
        TestUtils.endSession(true);
        WeblogEntry entry = null;
        try {
            testWeblog = TestUtils.getManagedWebsite(testWeblog);
            testUser = TestUtils.getManagedUser(testUser);
            WeblogCategory source = mgr.getWeblogCategory(cat1.getId());
            entry = TestUtils.setupWeblogEntry("stays", source, testWeblog, testUser);
            TestUtils.endSession(true);

            WeblogCategory from = mgr.getWeblogCategory(cat1.getId());
            WeblogCategory to = mgr.getWeblogCategory(foreign.getId());
            assertThrows(WebloggerException.class, () -> mgr.moveWeblogCategoryContents(from, to));
            TestUtils.endSession(false);

            WeblogEntry reloaded = TestUtils.getManagedWeblogEntry(entry);
            assertEquals(testWeblog.getId(), reloaded.getWebsite().getId());
            assertEquals(cat1.getId(), reloaded.getCategory().getId());
        } finally {
            TestUtils.endSession(false);
            if (entry != null) {
                mgr.removeWeblogEntry(TestUtils.getManagedWeblogEntry(entry));
            }
            TestUtils.teardownWeblog(other.getId());
            TestUtils.endSession(true);
        }
    }


    /** All (or only published) entries in a category, through the entry manager. */
    private static List<WeblogEntry> entriesIn(WeblogCategory category, boolean publishedOnly)
            throws WebloggerException {
        return TestUtils.weblogger().getWeblogEntryManager()
                .getWeblogEntries(WeblogEntrySearchCriteria.forCategory(category, publishedOnly));
    }
}
