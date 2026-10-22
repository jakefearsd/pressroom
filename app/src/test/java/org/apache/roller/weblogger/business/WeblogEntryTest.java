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

package org.apache.roller.weblogger.business;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.*;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test WeblogEntry related business operations.
 */
public class WeblogEntryTest  {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogEntryTest.class);
    
    User testUser = null;
    Weblog testWeblog = null;

    
    /**
     * All tests in this suite require a user and a weblog.
     */
    @BeforeEach
    public void setUp() throws Exception {

        // setup weblogger
        TestUtils.setupWeblogger();
        
        assertEquals(0L,
           TestUtils.weblogger().getWeblogManager().getWeblogCount());

        try {
            testUser = TestUtils.setupUser("entryTestUser");
            testWeblog = TestUtils.setupWeblog("entryTestWeblog", testUser);
            TestUtils.endSession(true);

            //WeblogManager wmgr = TestUtils.weblogger().getWeblogManager();
            //assertEquals(1, wmgr.getWeblogCount());
 
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
    
    
    /**
     * Test basic persistence operations ... Create, Update, Delete.
     */
    @Test
    public void testWeblogEntryCRUD() throws Exception {
        
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry entry;
        
        WeblogEntry testEntry = new WeblogEntry();
        testEntry.setTitle("entryTestEntry");
        testEntry.setLink("testEntryLink");
        testEntry.setText("blah blah entry");
        testEntry.setAnchor("testEntryAnchor");
        testEntry.setPubTime(new java.sql.Timestamp(new java.util.Date().getTime()));
        testEntry.setUpdateTime(new java.sql.Timestamp(new java.util.Date().getTime()));
        testEntry.setWebsite(testWeblog);
        testEntry.setCreatorUserName(testUser.getUserName());

        WeblogCategory cat = TestUtils.categoryNamed(testWeblog, "General");
        testEntry.setCategory(cat);
        
        // create a weblog entry
        mgr.saveWeblogEntry(testEntry);
        String id = testEntry.getId();
        TestUtils.endSession(true);
        
        // make sure entry was created
        entry = mgr.getWeblogEntry(id);
        assertNotNull(entry);
        assertEquals(testEntry, entry);
        
        // update a weblog entry
        entry.setTitle("testtest");
        mgr.saveWeblogEntry(entry);
        TestUtils.endSession(true);
        
        // make sure entry was updated
        entry = mgr.getWeblogEntry(id);
        assertNotNull(entry);
        assertEquals("testtest", entry.getTitle());
        
        // delete a weblog entry
        mgr.removeWeblogEntry(entry);
        TestUtils.endSession(true);
        
        // make sure entry was deleted
        entry = mgr.getWeblogEntry(id);
        assertNull(entry);
    }
    
    
    /**
     * Test lookup mechanisms ... 
     */
    @Test
    public void testWeblogEntryLookups() throws Exception {
        
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry entry;
        List<WeblogEntry> entries;
        Map<Date, ?> entryMap;

        // setup some test entries to use
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        testUser = TestUtils.getManagedUser(testUser);
        WeblogEntry entry1 = TestUtils.setupWeblogEntry("entry1", testWeblog, testUser);
        WeblogEntry entry2 = TestUtils.setupWeblogEntry("entry2", testWeblog, testUser);
        WeblogEntry entry3 = TestUtils.setupWeblogEntry("entry3", testWeblog, testUser);
        WeblogEntry entry4 = TestUtils.setupWeblogEntry("entry4", testWeblog, testUser);
        WeblogEntry entry5 = TestUtils.setupWeblogEntry("entry5", testWeblog, testUser);
        
        // make a couple changes
        entry1.setLocale("en_US");
        entry1.setStatus(PubStatus.PUBLISHED);
        entry1.setPinnedToMain(Boolean.TRUE);
        mgr.saveWeblogEntry(entry1);
        
        entry2.setLocale("ja_JP");
        entry2.setStatus(PubStatus.PUBLISHED);
        entry2.setUpdateTime(new java.sql.Timestamp(entry2.getUpdateTime().getTime()+8822384));
        entry2.setPubTime(entry2.getUpdateTime());
        mgr.saveWeblogEntry(entry2);

        entry3.setStatus(PubStatus.DRAFT);
        entry3.setUpdateTime(new java.sql.Timestamp(entry3.getUpdateTime().getTime()+348829384));
        entry3.setPubTime(entry3.getUpdateTime());
        mgr.saveWeblogEntry(entry3);
        
        entry4.setPubTime(new java.sql.Timestamp(entry1.getPubTime().getTime() - 348829384));
        entry5.setPubTime(new java.sql.Timestamp(entry1.getPubTime().getTime() - 8822384));
        
        TestUtils.endSession(true);
        
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        testUser = TestUtils.getManagedUser(testUser);

        log.debug("entry1 = {}", entry1.getUpdateTime());
        log.debug("entry2 = {}", entry2.getUpdateTime());
        log.debug("entry3 = {}", entry3.getUpdateTime());
        
        entry1 = TestUtils.getManagedWeblogEntry(entry1);
        entry2 = TestUtils.getManagedWeblogEntry(entry2);
        entry3 = TestUtils.getManagedWeblogEntry(entry3);
        entry4 = TestUtils.getManagedWeblogEntry(entry4);
        entry5 = TestUtils.getManagedWeblogEntry(entry5);
        
        // get entry by id
        entry = mgr.getWeblogEntry(entry1.getId());
        assertNotNull(entry);
        assertEquals(entry1.getAnchor(), entry.getAnchor());
        
        // get entry by anchor
        entry = mgr.getWeblogEntryByAnchor(testWeblog, entry1.getAnchor());
        assertNotNull(entry);
        assertEquals(entry1.getTitle(), entry.getTitle());
        
        // get all entries for weblog
        WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
        wesc.setWeblog(testWeblog);
        entries = mgr.getWeblogEntries(wesc);
        assertNotNull(entries);
        assertEquals(5, entries.size());
        assertEquals(entry3, entries.get(0));
        
        // get all (non-future) PUBLISHED entries in category 
        WeblogEntrySearchCriteria wesc9 = new WeblogEntrySearchCriteria();
        wesc9.setWeblog(testWeblog);
        wesc9.setCatName("General");
        wesc9.setStatus(PubStatus.PUBLISHED);
        entries = mgr.getWeblogEntries(wesc9);
        assertNotNull(entries);
        assertEquals(3, entries.size());
        
        // get all (non-future) PUBLISHED entries only 
        WeblogEntrySearchCriteria wesc2 = new WeblogEntrySearchCriteria();
        wesc2.setWeblog(testWeblog);
        wesc2.setStatus(PubStatus.PUBLISHED);
        entries = mgr.getWeblogEntries(wesc2);
        assertNotNull(entries);
        assertEquals(3, entries.size());
        
        // get all entries in date range
        WeblogEntrySearchCriteria wesc3 = new WeblogEntrySearchCriteria();
        wesc3.setWeblog(testWeblog);
        wesc3.setStartDate(entry2.getPubTime());
        wesc3.setEndDate(entry2.getPubTime());
        entries = mgr.getWeblogEntries(wesc3);
        assertNotNull(entries);
        assertEquals(1, entries.size());
        assertEquals(entry2, entries.get(0));
        
        // get all entries, limited to maxSize
        WeblogEntrySearchCriteria wesc4 = new WeblogEntrySearchCriteria();
        wesc4.setWeblog(testWeblog);
        wesc4.setMaxResults(2);
        entries = mgr.getWeblogEntries(wesc4);
        assertNotNull(entries);
        assertEquals(2, entries.size());
        
        // get all entries in category
        WeblogEntrySearchCriteria wesc5 = new WeblogEntrySearchCriteria();
        wesc5.setWeblog(testWeblog);
        wesc5.setCatName("General");
        entries = mgr.getWeblogEntries(wesc5);
        assertNotNull(entries);
        assertEquals(5, entries.size());
        
        // get all entries, limited by offset/range
        WeblogEntrySearchCriteria wesc6 = new WeblogEntrySearchCriteria();
        wesc6.setWeblog(testWeblog);
        wesc6.setOffset(1);
        wesc6.setMaxResults(1);
        entries = mgr.getWeblogEntries(wesc6);
        assertNotNull(entries);
        assertEquals(1, entries.size());
        assertEquals(entry2, entries.get(0));
        
        // get all entries, limited by locale
        WeblogEntrySearchCriteria wesc7 = new WeblogEntrySearchCriteria();
        wesc7.setWeblog(testWeblog);
        wesc7.setLocale("en_US");
        entries = mgr.getWeblogEntries(wesc7);
        assertNotNull(entries);
        assertEquals(4, entries.size());
        assertEquals(entry3, entries.get(0));
        
        // get pinned entries only
        entries = mgr.getWeblogEntriesPinnedToMain(5);
        assertNotNull(entries);
        assertEquals(1, entries.size());
        assertEquals(entry1, entries.get(0));
        
        // get next entry
        entry = mgr.getNextEntry(entry4, null, null);
        assertNotNull(entry);
        assertEquals(entry5, entry);
        
        // get previous entry
        entry = mgr.getPreviousEntry(entry5, null, null);
        assertNotNull(entry);
        assertEquals(entry4, entry);
        
        // get object map
        WeblogEntrySearchCriteria wesc8 = new WeblogEntrySearchCriteria();
        wesc8.setWeblog(testWeblog);
        entryMap = mgr.getWeblogEntryObjectMap(wesc8);
        assertNotNull(entryMap);
        assertTrue(entryMap.keySet().size() > 1);
        
        // get string map
        entryMap = mgr.getWeblogEntryStringMap(wesc8);
        assertNotNull(entryMap);
        assertTrue(entryMap.keySet().size() > 1);
                
        // teardown our test entries
        TestUtils.teardownWeblogEntry(entry1.getId());
        TestUtils.teardownWeblogEntry(entry2.getId());
        TestUtils.teardownWeblogEntry(entry3.getId());
        TestUtils.endSession(true);
    }

    /**
     * Test that the createAnchor() method actually ensures unique anchors.
     */
    @Test
    public void testCreateAnchor() throws Exception {
        
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();

        // setup some test entries to use
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        testUser = TestUtils.getManagedUser(testUser);
        WeblogEntry entry1 = TestUtils.setupWeblogEntry("entry1", testWeblog, testUser);
        TestUtils.endSession(true);
        
        // make sure createAnchor gives us a new anchor value
        entry1 = TestUtils.getManagedWeblogEntry(entry1);
        String anchor = mgr.createAnchor(entry1);
        assertNotNull(anchor);
        assertNotSame("entry1", anchor);
        
        // make sure we can create a new entry with specified anchor
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        testUser = TestUtils.getManagedUser(testUser);
        WeblogEntry entry2 = TestUtils.setupWeblogEntry(anchor, testWeblog, testUser);
        TestUtils.endSession(true);
        assertNotNull(entry2);
        
        // teardown our test entries
        TestUtils.teardownWeblogEntry(entry1.getId());
        TestUtils.teardownWeblogEntry(entry2.getId());
        TestUtils.endSession(true);
    }

    @Test
    public void testCreateAnEntryWithTagsShortcut() throws Exception {
        try {
            WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
            WeblogEntry entry;
            testWeblog = TestUtils.getManagedWebsite(testWeblog);
            testUser = TestUtils.getManagedUser(testUser);

            WeblogEntry testEntry = new WeblogEntry();
            testEntry.setTitle("entryTestEntry");
            testEntry.setLink("testEntryLink");
            testEntry.setText("blah blah entry");
            testEntry.setAnchor("testEntryAnchor");
            testEntry.setPubTime(
                    new java.sql.Timestamp(new java.util.Date().getTime()));
            testEntry.setUpdateTime(
                    new java.sql.Timestamp(new java.util.Date().getTime()));
            testEntry.setWebsite(testWeblog);
            testEntry.setCreatorUserName(testUser.getUserName());
            testEntry.setCategory(TestUtils.categoryNamed(testWeblog, "General"));

            // shortcut
            testEntry.addTag("testTag");

            // create a weblog entry
            mgr.saveWeblogEntry(testEntry);
            String id = testEntry.getId();
            TestUtils.endSession(true);

            // make sure entry was created
            entry = mgr.getWeblogEntry(id);
            assertNotNull(entry);
            assertEquals(testEntry, entry);
            assertNotNull(entry.getTags());
            assertEquals(1, entry.getTags().size());
            assertEquals("testtag", (entry.getTags()
                    .iterator().next()).getName());
            TestUtils.endSession(true);

            // teardown our test entry
            TestUtils.teardownWeblogEntry(id);
            TestUtils.endSession(true);
        
        } catch (Throwable t) {
            log.info("Unexpected error", t);
        }
    }

    @Test
    public void testAddMultipleTags() throws Exception {

        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();

        // setup some test entries to use
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        testUser = TestUtils.getManagedUser(testUser);
        WeblogEntry entry = TestUtils.setupWeblogEntry("entry1", testWeblog, testUser);
        entry.addTag("testTag");
        entry.addTag("whateverTag");
        String id = entry.getId();
        mgr.saveWeblogEntry(entry);
        TestUtils.endSession(true);

        entry = mgr.getWeblogEntry(id);
        entry.addTag("testTag2");
        mgr.saveWeblogEntry(entry);
        TestUtils.endSession(true);

        entry = mgr.getWeblogEntry(id);
        assertEquals(3, entry.getTags().size());

        // teardown our test entry
        TestUtils.teardownWeblogEntry(id);
        TestUtils.endSession(true);
    }

    @Test
    public void testAddMultipleIdenticalTags() throws Exception {

        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();

        // setup some test entries to use
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        testUser = TestUtils.getManagedUser(testUser);
        WeblogEntry entry = TestUtils.setupWeblogEntry("entry1", testWeblog, testUser);
        entry.addTag("testTag");
        String id = entry.getId();
        mgr.saveWeblogEntry(entry);
        TestUtils.endSession(true);

        entry = mgr.getWeblogEntry(id);
        entry.addTag("testTag");
        mgr.saveWeblogEntry(entry);
        TestUtils.endSession(true);

        entry = mgr.getWeblogEntry(id);
        assertEquals(1, entry.getTags().size());

        // teardown our test entry
        TestUtils.teardownWeblogEntry(id);
        TestUtils.endSession(true);
    }

    @Test
    public void testRemoveTagsViaShortcut() throws Exception {
        try {
            WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();

            // setup some test entries to use
            testWeblog = TestUtils.getManagedWebsite(testWeblog);
            testUser = TestUtils.getManagedUser(testUser);
            WeblogEntry entry = TestUtils.setupWeblogEntry("entry1", testWeblog, testUser);
            entry.addTag("testTag");
            entry.addTag("testTag2");
            String id = entry.getId();
            mgr.saveWeblogEntry(entry);
            TestUtils.endSession(true);

            entry = mgr.getWeblogEntry(id);
            assertEquals(2, entry.getTags().size());
            TestUtils.endSession(true);

            entry = mgr.getWeblogEntry(id);
            entry.setTagsAsString("");
            mgr.saveWeblogEntry(entry);
            TestUtils.endSession(true);

            entry = mgr.getWeblogEntry(id);
            assertEquals(0, entry.getTags().size());
            TestUtils.endSession(true);

            // teardown our test entry
            TestUtils.teardownWeblogEntry(id);
            TestUtils.endSession(true);
            
        } catch (Throwable t) {
            log.info("Unexpected error", t);
        }
    }

    @Test
    public void testTagsExist() throws Exception {
        
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        testUser = TestUtils.getManagedUser(testUser);
        Weblog weblog = TestUtils.setupWeblog("tagsExistWeblog1", testUser);
        String wid = weblog.getId();
        
        // setup some test entries to use
        WeblogEntry entry = TestUtils.setupWeblogEntry("tagsExistEntry1", testWeblog, testUser);
        String id1 = entry.getId();
        entry.addTag("blahTag");
        entry.addTag("fooTag");
        mgr.saveWeblogEntry(entry);

        WeblogEntry entry2 = TestUtils.setupWeblogEntry("tagsExistEntry2", weblog, testUser);
        String id2 = entry2.getId();
        entry2.addTag("aaaTag");
        entry2.addTag("bbbTag");
        mgr.saveWeblogEntry(entry2);
        TestUtils.endSession(true);
        
        // we'll need these
        List<String> tags1 = new ArrayList<String>();
        tags1.add("nonExistTag");
        
        List<String> tags2 = new ArrayList<String>();
        tags2.add("blahtag");
        
        // test site-wide
        assertTrue(mgr.getTagComboExists(tags2, null));
        assertFalse(mgr.getTagComboExists(tags1, null));
        
        // test weblog specific
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        weblog = TestUtils.getManagedWebsite(weblog);
        assertTrue(mgr.getTagComboExists(tags2, testWeblog));
        assertFalse(mgr.getTagComboExists(tags1, testWeblog));
        assertFalse(mgr.getTagComboExists(tags2, weblog));
        
        // teardown our test data
        TestUtils.teardownWeblogEntry(id1);
        TestUtils.teardownWeblogEntry(id2);
        TestUtils.endSession(true);

        TestUtils.teardownWeblog(wid);
        TestUtils.endSession(true);
    }

    @Test
    public void testGetEntriesByTag() throws Exception {
        try {
            WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();

            // setup some test entries to use
            testWeblog = TestUtils.getManagedWebsite(testWeblog);
            testUser = TestUtils.getManagedUser(testUser);
            WeblogEntry entry = TestUtils.setupWeblogEntry("entry1", testWeblog, testUser);
            String id = entry.getId();
            entry.addTag("testTag");
            mgr.saveWeblogEntry(entry);
            TestUtils.endSession(true);

            testWeblog = TestUtils.getManagedWebsite(testWeblog);

            WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
            wesc.setWeblog(testWeblog);
            // tags are always saved lowercase (testTag -> testtag)
            wesc.setTags(Arrays.asList("testtag"));
            List<WeblogEntry> results = mgr.getWeblogEntries(wesc);
            assertEquals(1, results.size());
            WeblogEntry testEntry = results.iterator().next();
            assertEquals(entry, testEntry);
        
            // teardown our test entry
            TestUtils.teardownWeblogEntry(id);
            TestUtils.endSession(true);
            
        } catch (Throwable t) {
            log.info("Unexpected error", t);
        }
    }


    @Test
    public void testRemoveEntryTagCascading() throws Exception {

        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();

        // setup some test entries to use
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        testUser = TestUtils.getManagedUser(testUser);
        WeblogEntry entry = TestUtils.setupWeblogEntry("entry1", testWeblog, testUser);
        entry.addTag("testTag");
        String id = entry.getId();
        mgr.saveWeblogEntry(entry);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
        wesc.setWeblog(testWeblog);
        // tags are always saved lowercase (testTag -> testtag)
        wesc.setTags(Arrays.asList("testtag"));
        List<WeblogEntry> results = mgr.getWeblogEntries(wesc);
        assertEquals(1, results.size());
        WeblogEntry testEntry = results.iterator().next();
        assertEquals(entry, testEntry);

        // teardown our test entry
        TestUtils.teardownWeblogEntry(id);
        TestUtils.endSession(true);

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        results = mgr.getWeblogEntries(wesc);
        assertEquals(0, results.size());

        // terminate
        TestUtils.endSession(true);
    }

    @Test
    public void testUpdateTags() throws Exception {
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();

        // setup some test entries to use
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        testUser = TestUtils.getManagedUser(testUser);
        WeblogEntry entry = TestUtils.setupWeblogEntry("entry1", testWeblog, testUser);
        entry.addTag("testWillStayTag");
        entry.addTag("testTagWillBeRemoved");
        String id = entry.getId();
        mgr.saveWeblogEntry(entry);
        TestUtils.endSession(true);

        entry = mgr.getWeblogEntry(id);
        assertEquals(2, entry.getTags().size());

        entry.setTagsAsString("testwillstaytag testnewtag testnewtag3");
        mgr.saveWeblogEntry(entry);
        TestUtils.endSession(true);

        entry = mgr.getWeblogEntry(id);
        HashSet<String> tagNames = new HashSet<String>();
        for (WeblogEntryTag tagData : entry.getTags()) {
            tagNames.add(tagData.getName());
        }

        assertEquals(3, entry.getTags().size());
        assertEquals(3, tagNames.size());
        assertEquals(true, tagNames.contains("testwillstaytag"));
        assertEquals(true, tagNames.contains("testnewtag"));
        assertEquals(true, tagNames.contains("testnewtag3"));

        // teardown our test entry
        TestUtils.teardownWeblogEntry(id);
        TestUtils.endSession(true);
    }

    /**
     * We want to make sure that the first time placed on the tag remains
     * through consequent updates.
     * 
     * @throws Exception
     */
    @Test
     public void testUpdateTagTime() throws Exception {
         WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();

        // setup some test entries to use
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        testUser = TestUtils.getManagedUser(testUser);
        WeblogEntry entry = TestUtils.setupWeblogEntry("entry1", testWeblog, testUser);
        String id = entry.getId();

        entry.addTag("testWillStayTag");
        entry.addTag("testTagWillBeRemoved");
        mgr.saveWeblogEntry(entry);
        TestUtils.endSession(true);

        entry = mgr.getWeblogEntry(id);
        assertEquals(2, entry.getTags().size());

        Timestamp original = null;

        for (WeblogEntryTag tagData : entry.getTags()) {
            if (tagData.getName().equals("testwillstaytag"))
                original = tagData.getTime();
        }

        entry.setTagsAsString("testwillstaytag testnewtag testnewtag3");
        mgr.saveWeblogEntry(entry);
        TestUtils.endSession(true);

        entry = mgr.getWeblogEntry(id);
        HashSet<String> tagNames = new HashSet<String>();
        for (WeblogEntryTag tagData : entry.getTags()) {
            tagNames.add(tagData.getName());
            if (tagData.getName().equals("testwillstaytag")) {
                assertEquals(original, tagData.getTime());
            }
        }

        assertEquals(3, entry.getTags().size());
        assertEquals(3, tagNames.size());
        assertEquals(true, tagNames.contains("testwillstaytag"));
        assertEquals(true, tagNames.contains("testnewtag"));
        assertEquals(true, tagNames.contains("testnewtag3"));

        // teardown our test entry
        TestUtils.teardownWeblogEntry(id);
        TestUtils.endSession(true);
    }

    @Test
    public void testTagAggregates() throws Exception {
        log.info("BEGIN");
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        testUser = TestUtils.getManagedUser(testUser);
        Weblog testWeblog2 = TestUtils.setupWeblog("entryTestWeblog2", testUser);

        try {
            // let's make sure we are starting from scratch

            // site-wide
            List<TagStat> tags = mgr.getTags(null, null, null, 0, -1);
            assertEquals(0, tags.size());

            // first weblog
            tags = mgr.getTags(testWeblog, null, null, 0, -1);
            assertEquals(0, tags.size());

            // second weblog
            tags = mgr.getTags(testWeblog2, null, null, 0, -1);
            assertEquals(0, tags.size());

            // setup some test entries to use
            WeblogEntry entry = TestUtils.setupWeblogEntry("entry1", testWeblog, testUser);
            entry.addTag("one");
            entry.addTag("two");
            mgr.saveWeblogEntry(entry);

            entry = TestUtils.setupWeblogEntry("entry2", testWeblog, testUser);
            entry.addTag("one");
            entry.addTag("two");
            entry.addTag("three");
            mgr.saveWeblogEntry(entry);

            TestUtils.endSession(true);

            testWeblog = TestUtils.getManagedWebsite(testWeblog);
            tags = mgr.getTags(testWeblog, null, null, 0, -1);
            assertEquals(3, tags.size());

            HashMap<String,Integer> expectedWeblogTags = new HashMap<String,Integer>();
            expectedWeblogTags.put("one", 2);
            expectedWeblogTags.put("two", 2);
            expectedWeblogTags.put("three", 1);

            for (TagStat stat : tags) {
                if (!expectedWeblogTags.containsKey(stat.getName())) {
                    fail("Unexpected tagName.");
                }
                Integer expectedCount = expectedWeblogTags.get(stat.getName());
                assertEquals(expectedCount.intValue(), stat.getCount());
            }

            // now add another entry in another blog
            testWeblog2 = TestUtils.getManagedWebsite(testWeblog2);
            testUser = TestUtils.getManagedUser(testUser);
            entry = TestUtils.setupWeblogEntry("entry3", testWeblog2, testUser);
            entry.addTag("one");
            entry.addTag("three");
            entry.addTag("four");
            mgr.saveWeblogEntry(entry);

            TestUtils.endSession(true);

            // let's fetch "site" tags now
            tags = mgr.getTags(null, null, null, 0, -1);
            assertEquals(4, tags.size());

            HashMap<String, Integer> expectedSiteTags = new HashMap<String, Integer>();
            expectedSiteTags.put("one", 3);
            expectedSiteTags.put("two", 2);
            expectedSiteTags.put("three", 2);
            expectedSiteTags.put("four", 1);

            for (TagStat stat : tags) {
                if (!expectedSiteTags.containsKey(stat.getName()))
                    fail("Unexpected tagName.");

                Integer expectedCount = expectedSiteTags.get(stat.getName());
                assertEquals(expectedCount.intValue(), stat.getCount());
            }

            TestUtils.endSession(true);

            testWeblog = TestUtils.getManagedWebsite(testWeblog);
            entry = mgr.getWeblogEntryByAnchor(testWeblog, "entry2");
            entry.setTagsAsString("one three five");
            mgr.saveWeblogEntry(entry);

            TestUtils.endSession(true);

            testWeblog = TestUtils.getManagedWebsite(testWeblog);
            tags = mgr.getTags(testWeblog, null, null, 0, -1);
            assertEquals(4, tags.size());

            expectedWeblogTags = new HashMap<String, Integer>();
            expectedWeblogTags.put("one", 2);
            expectedWeblogTags.put("two", 1);
            expectedWeblogTags.put("three", 1);
            expectedWeblogTags.put("five", 1);

            for (TagStat stat : tags) {
                if (!expectedWeblogTags.containsKey(stat.getName())) {
                    fail("Unexpected tagName.");
                }
                Integer expectedCount =
                        expectedWeblogTags.get(stat.getName());
                assertEquals(expectedCount.intValue(), stat.getCount(), stat.getName());
            }

            tags = mgr.getTags(null, null, null, 0, -1);
            assertEquals(5, tags.size());

            expectedSiteTags = new HashMap<String, Integer>();
            expectedSiteTags.put("one", 3);
            expectedSiteTags.put("two", 1);
            expectedSiteTags.put("three", 2);
            expectedSiteTags.put("four", 1);
            expectedSiteTags.put("five", 1);

            for (TagStat stat : tags) {
                if (!expectedSiteTags.containsKey(stat.getName())) {
                    fail("Unexpected tagName.");
                }
                Integer expectedCount = expectedSiteTags.get(stat.getName());
                assertEquals( expectedCount.intValue(), stat.getCount(), stat.getName());
            }

        } finally {
            TestUtils.endSession(false);
            TestUtils.teardownWeblog(testWeblog2.getId());
            TestUtils.endSession(true);
        }
        
        log.info("END");
    }

    @Test
    public void testTagAggregatesCaseSensitivity() throws Exception {

        Weblog testWeblog2 = TestUtils.setupWeblog("entryTestWeblog2",
                testUser);
        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        testUser = TestUtils.getManagedUser(testUser);

        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();

        // let's make sure we are starting from scratch

        // site-wide
        List<TagStat> tags = mgr.getTags(null, null, null, 0, -1);
        assertEquals(0, tags.size());

        // first weblog
        tags = mgr.getTags(testWeblog, null, null, 0, -1);
        assertEquals(0, tags.size());

        // second weblog
        tags = mgr.getTags(testWeblog2, null, null, 0, -1);
        assertEquals(0, tags.size());

        // setup some test entries to use
        WeblogEntry entry = TestUtils.setupWeblogEntry("entry1", testWeblog, testUser);
        entry.addTag("one");
        entry.addTag("two");
        entry.addTag("ONE");
        mgr.saveWeblogEntry(entry);

        TestUtils.endSession(true);

        tags = mgr.getTags(testWeblog, null, null, 0, -1);
        assertEquals(2, tags.size());

        HashMap<String, Integer> expectedWeblogTags = new HashMap<String, Integer>();
        expectedWeblogTags.put("one", 1);
        expectedWeblogTags.put("two", 1);

        for (TagStat stat : tags) {
            if (!expectedWeblogTags.containsKey(stat.getName()))
                fail("Unexpected tagName.");

            Integer expectedCount = expectedWeblogTags.get(stat.getName());
            assertEquals(expectedCount.intValue(), stat.getCount());
        }

        // now add another entry in another blog
        entry = TestUtils.setupWeblogEntry("entry3", testWeblog2, testUser);
        entry.addTag("ONE");
        entry.addTag("three");
        mgr.saveWeblogEntry(entry);
        
        TestUtils.endSession(true);
        
        // let's fetch "site" tags now
        tags = mgr.getTags(null, null, null, 0, -1);
        assertEquals(3, tags.size());

        HashMap<String, Integer> expectedSiteTags = new HashMap<String, Integer>();
        expectedSiteTags.put("one", 2);
        expectedSiteTags.put("two", 1);
        expectedSiteTags.put("three", 1);

        for (TagStat stat : tags) {
            if (!expectedSiteTags.containsKey(stat.getName()))
                fail("Unexpected tagName.");

            Integer expectedCount = expectedSiteTags.get(stat.getName());
            assertEquals(expectedCount.intValue(), stat.getCount());
        }

        TestUtils.endSession(true);

        // teardown our test blog 2
        TestUtils.teardownWeblog(testWeblog2.getId());
        TestUtils.endSession(true);
    }

  
    
    /**
     * Test that we can add and remove entry attributes for an entry.
     */
    @Test
     public void testEntryAttributeCRUD() throws Exception {
        
        WeblogEntryManager emgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry entry;
        
        WeblogEntry testEntry = new WeblogEntry();
        testEntry.setTitle("entryTestEntry");
        testEntry.setLink("testEntryLink");
        testEntry.setText("blah blah entry");
        testEntry.setAnchor("testEntryAnchor");
        testEntry.setPubTime(new java.sql.Timestamp(new java.util.Date().getTime()));
        testEntry.setUpdateTime(new java.sql.Timestamp(new java.util.Date().getTime()));
        testEntry.setWebsite(testWeblog);
        testEntry.setCreatorUserName(testUser.getUserName());

        WeblogCategory cat = TestUtils.categoryNamed(testWeblog, "General");
        testEntry.setCategory(cat);
        
        // create a weblog entry
        emgr.saveWeblogEntry(testEntry);
        String id = testEntry.getId();
        TestUtils.endSession(true);

        testEntry = TestUtils.getManagedWeblogEntry(testEntry);
        testEntry.putEntryAttribute("att_mediacast_url", "http://podcast-schmodcast.com");
        testEntry.putEntryAttribute("att_mediacast_type", "application/drivel");
        testEntry.putEntryAttribute("att_mediacast_length", "3141592654");
                    
        TestUtils.endSession(true);
        
        // make sure entry was created
        entry = emgr.getWeblogEntry(id);
        assertNotNull(entry);
        assertEquals(testEntry, entry);
        assertNotNull(entry.getEntryAttributes());
        assertEquals(3, entry.getEntryAttributes().size());
        assertNotNull(entry.findEntryAttribute("att_mediacast_url"));
        assertNotNull(entry.findEntryAttribute("att_mediacast_type"));
        assertNotNull(entry.findEntryAttribute("att_mediacast_length"));
        assertEquals("http://podcast-schmodcast.com", entry.findEntryAttribute("att_mediacast_url"));
        assertEquals("application/drivel", entry.findEntryAttribute("att_mediacast_type"));
        assertEquals("3141592654", entry.findEntryAttribute("att_mediacast_length"));
        
        // update a weblog entry
        entry.setTitle("testtest");
        emgr.saveWeblogEntry(entry);
        TestUtils.endSession(true);
        
        // make sure entry was updated
        entry = emgr.getWeblogEntry(id);
        assertNotNull(entry);
        assertEquals("testtest", entry.getTitle());
        
        // delete a weblog entry
        emgr.removeWeblogEntry(entry);
        TestUtils.endSession(true);
        
        // make sure entry was deleted
        entry = emgr.getWeblogEntry(id);
        assertNull(entry);
    }


    /**
     * saveWeblogEntry() falls back to the weblog's first category whenever an
     * entry arrives with none set -- the entry editor never lets an author
     * leave category blank, but any other caller that builds a WeblogEntry
     * directly (a future import path, a script, this fixture) can. If that
     * fallback regressed, such an entry would save with a null category and
     * silently drop out of every category-scoped view -- category archive
     * pages, category-filtered search -- with nothing to say why.
     */
    @Test
    public void testSavingAnEntryWithNoCategorySetFallsBackToTheWeblogsFirstCategory() throws Exception {

        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        testUser = TestUtils.getManagedUser(testUser);
        WeblogCategory onlyCategory = testWeblog.getWeblogCategories().getFirst();

        WeblogEntry testEntry = new WeblogEntry();
        testEntry.setTitle("noCategoryEntry");
        testEntry.setLink("testEntryLink");
        testEntry.setText("blah blah entry");
        testEntry.setAnchor("noCategoryEntryAnchor");
        testEntry.setPubTime(new java.sql.Timestamp(new java.util.Date().getTime()));
        testEntry.setUpdateTime(new java.sql.Timestamp(new java.util.Date().getTime()));
        testEntry.setWebsite(testWeblog);
        testEntry.setCreatorUserName(testUser.getUserName());
        // Deliberately no setCategory() call -- that is the case under test.

        mgr.saveWeblogEntry(testEntry);
        String id = testEntry.getId();
        TestUtils.endSession(true);

        WeblogEntry saved = mgr.getWeblogEntry(id);
        assertNotNull(saved);
        assertNotNull(saved.getCategory());
        assertEquals(onlyCategory.getId(), saved.getCategory().getId());

        // teardown our test entry
        TestUtils.teardownWeblogEntry(id);
        TestUtils.endSession(true);
    }

    /**
     * removeWeblogCategory() is supposed to make "a weblog with zero
     * categories" unreachable (see
     * WeblogCategoryCRUDTest#testCannotRemoveTheLastCategory), so
     * saveWeblogEntry()'s null-category fallback should never actually find
     * an empty list. But if that invariant is ever breached some other way,
     * the fallback must fail loudly with a WebloggerException naming the
     * weblog, not let the bare iterator throw NoSuchElementException with no
     * hint of which weblog broke or why -- that failure mode is exactly what
     * an operator staring at a stack trace with no context cannot debug.
     */
    @Test
    public void testSavingAnEntryWithNoCategorySetFailsLoudlyWhenTheWeblogHasNoCategoriesAtAll() throws Exception {

        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();

        testWeblog = TestUtils.getManagedWebsite(testWeblog);
        testUser = TestUtils.getManagedUser(testUser);

        // Simulate the otherwise-unreachable state directly, in memory only:
        // clear the managed weblog's category list without deleting
        // anything, so the throw below happens before saveWeblogEntry ever
        // touches the database.
        testWeblog.getWeblogCategories().clear();

        WeblogEntry testEntry = new WeblogEntry();
        testEntry.setTitle("noCategoryNoWeblogCategoriesEntry");
        testEntry.setLink("testEntryLink");
        testEntry.setText("blah blah entry");
        testEntry.setAnchor("noCategoryNoWeblogCategoriesEntryAnchor");
        testEntry.setPubTime(new java.sql.Timestamp(new java.util.Date().getTime()));
        testEntry.setUpdateTime(new java.sql.Timestamp(new java.util.Date().getTime()));
        testEntry.setWebsite(testWeblog);
        testEntry.setCreatorUserName(testUser.getUserName());
        // Deliberately no setCategory() call -- that is the case under test.

        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> mgr.saveWeblogEntry(testEntry));
        assertTrue(thrown.getMessage().contains(testWeblog.getHandle()),
                "exception should name the offending weblog, not just say 'no categories': "
                        + thrown.getMessage());

        // The save must have been refused before anything was written --
        // confirm the entry never made it into the database, not merely
        // that an exception was thrown somewhere.
        assertNull(mgr.getWeblogEntry(testEntry.getId()));

        // Roll back rather than commit: the in-memory category-list clear
        // was never meant to reach the database, and ending with flush=true
        // here would be testing something else entirely.
        TestUtils.endSession(false);
    }

    @Test
    public void testWeblogStats() throws Exception {

        WeblogEntryManager emgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogManager wmgr = TestUtils.weblogger().getWeblogManager();
        UserManager umgr = TestUtils.weblogger().getUserManager();
        
        long existingUserCount = umgr.getUserCount() - 1;
        
        User user1 = TestUtils.setupUser("statuser1");
        Weblog blog1 = TestUtils.setupWeblog("statblog1", user1);
        Weblog blog2 = TestUtils.setupWeblog("statblog2", user1);

        Weblog blog3 = TestUtils.setupWeblog("statblog3", user1);
        blog3.setVisible(Boolean.FALSE);
        wmgr.saveWeblog(blog3);

        WeblogEntry entry1 = TestUtils.setupWeblogEntry("entry1", blog1, user1);
        WeblogEntry entry2 = TestUtils.setupWeblogEntry("entry2", blog1, user1);
        
        WeblogEntry entry3 = TestUtils.setupWeblogEntry("entry3", blog2, user1);
        WeblogEntry entry4 = TestUtils.setupWeblogEntry("entry4", blog2, user1);
        WeblogEntry entry5 = TestUtils.setupWeblogEntry("entry5", blog2, user1);
        TestUtils.endSession(true);

        try {
            blog1 = wmgr.getWeblog(blog1.getId());
            blog2 = wmgr.getWeblog(blog2.getId());

            assertEquals(2L, emgr.getEntryCount(blog1));
            assertEquals(3L, emgr.getEntryCount(blog2));
            assertEquals(5L, emgr.getEntryCount());

            assertEquals(4L, wmgr.getWeblogCount());
            assertEquals(existingUserCount + 2L, umgr.getUserCount());

        } finally {

            TestUtils.teardownWeblogEntry(entry1.getId());
            TestUtils.teardownWeblogEntry(entry2.getId());
            TestUtils.teardownWeblogEntry(entry3.getId());
            TestUtils.teardownWeblogEntry(entry4.getId());
            TestUtils.teardownWeblogEntry(entry5.getId());

            TestUtils.teardownWeblog(blog1.getId());
            TestUtils.teardownWeblog(blog2.getId());
            TestUtils.teardownWeblog(blog3.getId());

            TestUtils.teardownUser(user1.getUserName());      
            
            TestUtils.endSession(true);
        }
    }
}



