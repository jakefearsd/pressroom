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
package org.apache.roller.weblogger.business.jpa;

import java.sql.Timestamp;
import java.util.UUID;

import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.apache.roller.weblogger.business.jpa.TagAggregateRows.assertAggregateIsTrue;
import static org.apache.roller.weblogger.business.jpa.TagAggregateRows.siteTotal;
import static org.apache.roller.weblogger.business.jpa.TagAggregateRows.weblogTotal;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a save does to the tag counts is decided by the entry's status as
 * loaded against its status as saved -- not by a flag the caller has to
 * remember to set. A tag counts once for every PUBLISHED entry carrying it,
 * which is also what V034 recomputes; any transition the bookkeeping got
 * wrong would drift the counts away from that again.
 *
 * <p>Each step ends the session, so the next loads the entry afresh, as the
 * next request would.
 */
class TagAggregateBookkeepingTest {

    private User user;
    private Weblog weblog;
    private WeblogEntryManager entries;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        entries = TestUtils.weblogger().getWeblogEntryManager();
        user = TestUtils.setupUser("tagbook");
        weblog = TestUtils.setupWeblog("tagbookblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void aDraftThatBecomesPublishedCountsEveryTagItCarries() throws Exception {
        String id = create(PubStatus.DRAFT, now(), "x", "y");
        assertEquals(0, weblogTotal(weblog.getId(), "x"));

        update(id, entry -> entry.setStatus(PubStatus.PUBLISHED));

        assertEquals(1, weblogTotal(weblog.getId(), "x"));
        assertEquals(1, siteTotal("y"));
        assertTrue("x", "y");
    }

    @Test
    void aPublishedEntryThatBecomesADraftStopsCounting() throws Exception {
        String id = create(PubStatus.PUBLISHED, now(), "x", "y");
        assertEquals(1, weblogTotal(weblog.getId(), "x"));

        update(id, entry -> entry.setStatus(PubStatus.DRAFT));

        assertEquals(0, weblogTotal(weblog.getId(), "x"));
        assertEquals(0, siteTotal("y"));
        assertTrue("x", "y");
    }

    @Test
    void unpublishingWhileDroppingATagUncountsTheDroppedTagToo() throws Exception {
        String id = create(PubStatus.PUBLISHED, now(), "x", "y");

        update(id, entry -> {
            entry.setTagsAsString("x");
            entry.setStatus(PubStatus.DRAFT);
        });

        assertEquals(0, weblogTotal(weblog.getId(), "y"));
        assertTrue("x", "y");
    }

    @Test
    void publishingADraftWhileDroppingATagLeavesOtherEntriesCountsAlone() throws Exception {
        create(PubStatus.PUBLISHED, now(), "y");
        String draft = create(PubStatus.DRAFT, now(), "x", "y");

        update(draft, entry -> {
            entry.setTagsAsString("x");
            entry.setStatus(PubStatus.PUBLISHED);
        });

        assertEquals(1, weblogTotal(weblog.getId(), "x"));
        assertEquals(1, weblogTotal(weblog.getId(), "y"), "the draft's y was never counted");
        assertTrue("x", "y");
    }

    @Test
    void retaggingAPublishedEntryMovesItsCount() throws Exception {
        String id = create(PubStatus.PUBLISHED, now(), "x", "y");

        update(id, entry -> entry.setTagsAsString("y z"));

        assertEquals(0, weblogTotal(weblog.getId(), "x"));
        assertEquals(1, weblogTotal(weblog.getId(), "y"));
        assertEquals(1, weblogTotal(weblog.getId(), "z"));
        assertTrue("x", "y", "z");
    }

    @Test
    void aFutureDatedPublishIsCountedOnlyOnceItIsPromoted() throws Exception {
        Timestamp later = new Timestamp(System.currentTimeMillis() + 10 * RollerConstants.MIN_IN_MS);
        String id = create(PubStatus.PUBLISHED, later, "x");
        assertEquals(PubStatus.SCHEDULED, entries.getWeblogEntry(id).getStatus(),
                "saveWeblogEntry turns a future-dated publish into SCHEDULED");
        TestUtils.endSession(false);
        assertEquals(0, weblogTotal(weblog.getId(), "x"), "a scheduled entry is not published yet");

        // what ScheduledEntriesTask does once the time comes
        update(id, entry -> {
            entry.setPubTime(now());
            entry.setStatus(PubStatus.PUBLISHED);
        });

        assertEquals(1, weblogTotal(weblog.getId(), "x"));
        assertTrue("x");
    }

    @Test
    void reschedulingAPublishedEntryIntoTheFutureStopsCountingIt() throws Exception {
        String id = create(PubStatus.PUBLISHED, now(), "x");

        update(id, entry -> {
            entry.setPubTime(new Timestamp(System.currentTimeMillis() + 10 * RollerConstants.MIN_IN_MS));
            entry.setStatus(PubStatus.SCHEDULED);
        });

        assertEquals(0, weblogTotal(weblog.getId(), "x"));
        assertTrue("x");
    }

    @Test
    void savingTheSameEntryTwiceInOneSessionCountsItOnce() throws Exception {
        WeblogEntry entry = newEntry(PubStatus.PUBLISHED, now(), "x");
        entries.saveWeblogEntry(entry);
        entries.saveWeblogEntry(entry);
        TestUtils.endSession(true);

        assertEquals(1, weblogTotal(weblog.getId(), "x"));
        assertTrue("x");
    }

    @Test
    void trashingRestoringAndRepublishingKeepsTheCountTrue() throws Exception {
        String id = create(PubStatus.PUBLISHED, now(), "x");

        entries.trashWeblogEntry(entries.getWeblogEntry(id));
        TestUtils.endSession(true);
        assertEquals(0, weblogTotal(weblog.getId(), "x"));

        entries.restoreWeblogEntry(entries.getWeblogEntry(id));
        TestUtils.endSession(true);
        assertEquals(0, weblogTotal(weblog.getId(), "x"), "restore lands on DRAFT");

        update(id, entry -> entry.setStatus(PubStatus.PUBLISHED));
        assertEquals(1, weblogTotal(weblog.getId(), "x"));
        assertTrue("x");
    }

    @Test
    void deletingAnEntryUncountsItOnlyIfItWasPublished() throws Exception {
        String published = create(PubStatus.PUBLISHED, now(), "x");
        String draft = create(PubStatus.DRAFT, now(), "x");
        create(PubStatus.PUBLISHED, now(), "x");

        entries.removeWeblogEntry(entries.getWeblogEntry(draft));
        TestUtils.endSession(true);
        assertEquals(2, weblogTotal(weblog.getId(), "x"));

        entries.removeWeblogEntry(entries.getWeblogEntry(published));
        TestUtils.endSession(true);
        assertEquals(1, weblogTotal(weblog.getId(), "x"));
        assertTrue("x");
    }

    @Test
    void deletingAWeblogTakesItsCountsOutOfTheSiteWideRows() throws Exception {
        Weblog other = TestUtils.setupWeblog("tagbookother", user);
        TestUtils.endSession(true);
        create(PubStatus.PUBLISHED, now(), "x");
        createIn(other, "x", "only");

        TestUtils.teardownWeblog(other.getId());
        TestUtils.endSession(true);

        assertEquals(1, siteTotal("x"));
        assertEquals(0, siteTotal("only"));
        assertTrue("x", "only");
    }

    // ------------------------------------------------------------- helpers

    @FunctionalInterface
    private interface Change {
        void apply(WeblogEntry entry) throws Exception;
    }

    private void assertTrue(String... tags) throws Exception {
        for (String tag : tags) {
            assertAggregateIsTrue(tag, weblog);
        }
    }

    private static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    private String create(PubStatus status, Timestamp pubTime, String... tags) throws Exception {
        WeblogEntry entry = newEntry(status, pubTime, tags);
        entries.saveWeblogEntry(entry);
        TestUtils.endSession(true);
        return entry.getId();
    }

    private void createIn(Weblog target, String... tags) throws Exception {
        WeblogEntry entry = newEntry(target, PubStatus.PUBLISHED, now(), tags);
        entries.saveWeblogEntry(entry);
        TestUtils.endSession(true);
    }

    private WeblogEntry newEntry(PubStatus status, Timestamp pubTime, String... tags) throws Exception {
        return newEntry(weblog, status, pubTime, tags);
    }

    private WeblogEntry newEntry(Weblog target, PubStatus status, Timestamp pubTime, String... tags)
            throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(TestUtils.getManagedWebsite(target));
        entry.setTitle("t");
        entry.setText("text");
        entry.setAnchor("book-" + UUID.randomUUID());
        entry.setCreatorUserName(user.getUserName());
        entry.setPubTime(pubTime);
        entry.setUpdateTime(now());
        entry.setStatus(status);
        for (String tag : tags) {
            entry.addTag(tag);
        }
        return entry;
    }

    private void update(String id, Change change) throws Exception {
        WeblogEntry entry = entries.getWeblogEntry(id);
        change.apply(entry);
        entries.saveWeblogEntry(entry);
        TestUtils.endSession(true);
    }
}
