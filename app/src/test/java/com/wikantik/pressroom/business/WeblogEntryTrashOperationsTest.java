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
package com.wikantik.pressroom.business;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.PressroomEvent;
import com.wikantik.pressroom.pojos.TagStat;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code trashWeblogEntry}/{@code restoreWeblogEntry}/{@code getTrashedEntries}/
 * {@code purgeTrash} against the real database.
 *
 * <p>Two things this class exists specifically to prove rather than assume,
 * because both are easy to get subtly wrong by reasoning alone: trashing (a
 * pure status change) must not deposit an entry revision, since revisions
 * exist to record CONTENT changes; and trashing a published entry must not
 * emit a second {@code pressroom_event} row, since {@code ENTRY_PUBLISHED} is
 * gated on the save's new status being PUBLISHED, which TRASHED never is.
 */
class WeblogEntryTrashOperationsTest {

    private User user;
    private Weblog blog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("trashOpsUser");
        blog = TestUtils.setupWeblog("trashopsblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(blog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ----------------------------------------------------------------- trash

    @Test
    void trashingSetsStatusAndStampAndDoesNotHardDelete() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry("to-trash", blog, user, PubStatus.DRAFT);
        TestUtils.endSession(true);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        entries().trashWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        WeblogEntry reloaded = entries().getWeblogEntry(created.getId());
        assertNotNull(reloaded, "trashing must not hard-delete the row");
        assertEquals(PubStatus.TRASHED, reloaded.getStatus());
        assertNotNull(reloaded.getTrashedAt(), "the trash stamp must be set");
    }

    /**
     * {@code WeblogPageCache} has no CacheHandler and expires a rendered page
     * only by comparing it against {@code weblog.lastModified} (see the
     * Templates section of CLAUDE.md). {@code saveWeblogEntry}'s own bump is
     * gated on the entry's NEW status being PUBLISHED, which TRASHED never
     * is, so without a bump of its own a reader would keep seeing a trashed
     * post on the cached home page.
     */
    @Test
    void trashingAPublishedEntryBumpsTheWeblogsLastModified() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry("bumps-last-modified", blog, user);
        TestUtils.endSession(true);
        assertEquals(PubStatus.PUBLISHED, created.getStatus(), "fixture sanity check");

        Date before = freshLastModifiedBaseline();
        // Guarantee a distinguishable millisecond boundary even on a very
        // fast clock/database, same pattern as WeblogPageManagerTest.
        Thread.sleep(5);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        entries().trashWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        Date after = TestUtils.getManagedWebsite(blog).getLastModified();
        assertTrue(after.after(before),
                "trashing a published entry must bump weblog.lastModified or a cached "
                        + "copy of the home page keeps showing it");
    }

    /**
     * The flip side of the test above: a draft was never visible on any
     * public page, so trashing one must not spuriously expire an otherwise
     * still-valid cached page.
     */
    @Test
    void trashingAnUnpublishedEntryDoesNotBumpTheWeblogsLastModified() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry("no-bump-for-draft", blog, user, PubStatus.DRAFT);
        TestUtils.endSession(true);

        Date before = freshLastModifiedBaseline();
        Thread.sleep(5);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        entries().trashWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        Date after = TestUtils.getManagedWebsite(blog).getLastModified();
        assertEquals(before, after,
                "a draft was never visible, so trashing it must not bump weblog.lastModified");
    }

    /**
     * Unpublishing is the same exit from the public pages as trashing, by
     * way of {@code saveWeblogEntry} itself: an entry going from PUBLISHED to
     * DRAFT must bump {@code weblog.lastModified}, or every cached page and
     * the cached sitemap keep advertising it. The bump used to be gated on
     * the NEW status alone, so only arriving at PUBLISHED counted.
     */
    @Test
    void unpublishingAPublishedEntryBumpsTheWeblogsLastModified() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry("unpublish-bumps", blog, user);
        TestUtils.endSession(true);

        Date before = freshLastModifiedBaseline();
        Thread.sleep(5);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        managed.setStatus(PubStatus.DRAFT);
        entries().saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        Date after = TestUtils.getManagedWebsite(blog).getLastModified();
        assertTrue(after.after(before),
                "unpublishing must bump weblog.lastModified or cached pages keep showing the entry");
    }

    /**
     * Saving a draft that stays a draft changed nothing a reader could see,
     * so it must not expire every cached page of the weblog.
     */
    @Test
    void savingADraftThatStaysADraftDoesNotBumpTheWeblogsLastModified() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry("draft-stays", blog, user, PubStatus.DRAFT);
        TestUtils.endSession(true);

        Date before = freshLastModifiedBaseline();
        Thread.sleep(5);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        managed.setText("still a draft");
        entries().saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertEquals(before, TestUtils.getManagedWebsite(blog).getLastModified());
    }

    /**
     * The tag cloud counts published entries only. Trashing a published
     * entry must decrement its tags out of the aggregate the same way
     * unpublishing does -- otherwise its tags stay counted until the entry
     * is purged, which with the default "keep forever" retention is
     * effectively never.
     */
    @Test
    void trashingAPublishedEntryDecrementsItsTagsOutOfTheAggregate() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry("tagged-then-trashed", blog, user);
        TestUtils.endSession(true);

        WeblogEntry withTag = entries().getWeblogEntry(created.getId());
        withTag.addTag("cycling");
        entries().saveWeblogEntry(withTag);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        List<String> beforeTrash = entries()
                .getTags(TestUtils.getManagedWebsite(blog), null, null, 0, 50)
                .stream().map(TagStat::getName).toList();
        assertTrue(beforeTrash.contains("cycling"), "fixture sanity check, got: " + beforeTrash);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        entries().trashWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        List<String> afterTrash = entries()
                .getTags(TestUtils.getManagedWebsite(blog), null, null, 0, 50)
                .stream().map(TagStat::getName).toList();
        assertFalse(afterTrash.contains("cycling"),
                "trashing a published entry must decrement its tags out of the tag cloud, got: "
                        + afterTrash);
    }

    // --------------------------------------------------------------- restore

    @Test
    void restoringSetsDraftAndClearsTheStamp() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry("to-restore", blog, user, PubStatus.DRAFT);
        TestUtils.endSession(true);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        entries().trashWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        WeblogEntry trashed = entries().getWeblogEntry(created.getId());
        entries().restoreWeblogEntry(trashed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        WeblogEntry restored = entries().getWeblogEntry(created.getId());
        assertEquals(PubStatus.DRAFT, restored.getStatus());
        assertNull(restored.getTrashedAt(), "restoring must clear the trash stamp");
    }

    /**
     * The hazard named in the brief: nothing records what an entry's status
     * was before it was trashed, on purpose. Restoring a formerly-PUBLISHED
     * entry straight back to PUBLISHED would silently republish it to feeds,
     * the sitemap and every subscriber -- worse than making the author click
     * Publish again -- so restore always lands on DRAFT regardless of what
     * was trashed.
     */
    @Test
    void restoringAPublishedEntryStillYieldsDraftNotPublished() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry("published-then-trashed", blog, user);
        TestUtils.endSession(true);
        assertEquals(PubStatus.PUBLISHED, created.getStatus(), "fixture sanity check");

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        entries().trashWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        WeblogEntry trashed = entries().getWeblogEntry(created.getId());
        entries().restoreWeblogEntry(trashed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertEquals(PubStatus.DRAFT, entries().getWeblogEntry(created.getId()).getStatus(),
                "restoring must never silently republish -- DRAFT only, never PUBLISHED");
    }

    // ------------------------------------------------------------- listing

    @Test
    void getTrashedEntriesListsNewestTrashedFirst() throws Exception {
        WeblogEntry first = TestUtils.setupWeblogEntry("trashed-first", blog, user, PubStatus.DRAFT);
        WeblogEntry second = TestUtils.setupWeblogEntry("trashed-second", blog, user, PubStatus.DRAFT);
        TestUtils.endSession(true);

        trashWithStamp(first, Instant.now().minus(2, ChronoUnit.DAYS));
        trashWithStamp(second, Instant.now().minus(1, ChronoUnit.DAYS));

        List<String> anchors = entries().getTrashedEntries(TestUtils.getManagedWebsite(blog))
                .stream().map(WeblogEntry::getAnchor).toList();

        assertEquals(List.of("trashed-second", "trashed-first"), anchors,
                "newest-trashed first: " + anchors);
    }

    /**
     * {@code getTrashedEntries} is routed through the general-purpose
     * {@code getWeblogEntries(WeblogEntrySearchCriteria)} query, which is
     * happy to run scoped to "no weblog" and would silently return every
     * TRASHED entry across every weblog on the system -- exposing another
     * weblog's trashed content to whoever is looking at this one's Trash
     * screen. The explicit null-check exists so a caller mistake fails loudly
     * instead of leaking that.
     */
    @Test
    void getTrashedEntriesRejectsANullWeblogInsteadOfListingEveryWeblogsTrash() {
        assertThrows(WebloggerException.class, () -> entries().getTrashedEntries(null));
    }

    // ---------------------------------------------------------------- purge

    @Test
    void purgeTrashHardDeletesPastRetentionAndKeepsWithinIt() throws Exception {
        WeblogEntry stale = TestUtils.setupWeblogEntry("stale-trash", blog, user, PubStatus.DRAFT);
        WeblogEntry fresh = TestUtils.setupWeblogEntry("fresh-trash", blog, user, PubStatus.DRAFT);
        TestUtils.endSession(true);

        trashWithStamp(stale, Instant.now().minus(10, ChronoUnit.DAYS));
        trashWithStamp(fresh, Instant.now().minus(1, ChronoUnit.DAYS));

        int purged = entries().purgeTrash(TestUtils.getManagedWebsite(blog), 5);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertEquals(1, purged, "only the entry trashed past the 5-day retention should purge");
        assertNull(entries().getWeblogEntry(stale.getId()),
                "the stale trashed entry must be hard-deleted");
        assertNotNull(entries().getWeblogEntry(fresh.getId()),
                "the entry inside the retention window must survive");
        assertEquals(PubStatus.TRASHED, entries().getWeblogEntry(fresh.getId()).getStatus());
    }

    /**
     * The cutoff comparison is a strict {@code before(cutoff)}, matching the
     * brief's "longer ago than" wording: an entry trashed exactly at the
     * retention boundary is KEPT, not purged. The coarse test above (10 days
     * vs. 1 day against a 5-day retention) never exercises that edge -- both
     * fixtures here sit within a few seconds of the one-day boundary itself,
     * tight enough to prove the comparison is strict rather than inclusive,
     * loose enough not to flake against a real wall clock (there is no way
     * to observe {@code purgeTrash}'s internal "now" directly, since real
     * time has always moved on a little by the time it runs).
     */
    @Test
    void anEntryTrashedExactlyAtTheRetentionBoundaryIsKept() throws Exception {
        WeblogEntry justInside = TestUtils.setupWeblogEntry("just-inside-retention", blog, user, PubStatus.DRAFT);
        WeblogEntry justOutside = TestUtils.setupWeblogEntry("just-outside-retention", blog, user, PubStatus.DRAFT);
        TestUtils.endSession(true);

        Instant boundary = Instant.now().minus(1, ChronoUnit.DAYS);
        trashWithStamp(justInside, boundary.plusSeconds(5));
        trashWithStamp(justOutside, boundary.minusSeconds(5));

        int purged = entries().purgeTrash(TestUtils.getManagedWebsite(blog), 1);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertEquals(1, purged, "only the entry trashed more than a day ago should purge");
        assertNotNull(entries().getWeblogEntry(justInside.getId()),
                "an entry trashed just inside the retention window must survive");
        assertNull(entries().getWeblogEntry(justOutside.getId()),
                "an entry trashed just past the retention window must be purged");
    }

    @Test
    void aRetentionOfMinusOnePurgesNothing() throws Exception {
        WeblogEntry ancient = TestUtils.setupWeblogEntry("ancient-trash", blog, user, PubStatus.DRAFT);
        TestUtils.endSession(true);

        trashWithStamp(ancient, Instant.now().minus(365, ChronoUnit.DAYS));

        int purged = entries().purgeTrash(TestUtils.getManagedWebsite(blog), -1);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertEquals(0, purged, "-1 means keep trash forever");
        assertNotNull(entries().getWeblogEntry(ancient.getId()),
                "nothing may be purged when retention is -1");
    }

    // -------------------------------------------------------------- revisions

    /**
     * A status change is not a content change. {@code recordRevision} compares
     * only title/text/summary, so trashing -- which touches neither -- must
     * leave {@code weblogentry_revision} untouched. Verified against the table
     * through {@code getRevisions} rather than assumed from reading the save
     * path, per the brief.
     */
    @Test
    void trashingDoesNotCreateARevision() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry("no-revision-on-trash", blog, user);
        TestUtils.endSession(true);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        entries().trashWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertTrue(entries().getRevisions(entries().getWeblogEntry(created.getId())).isEmpty(),
                "trashing is a status change, not a content change -- no revision should be recorded");
    }

    // ------------------------------------------------------------------ events

    /**
     * {@code ENTRY_PUBLISHED} fires only when a save's NEW status is
     * PUBLISHED. Trashing a published entry moves it to TRASHED, so no event
     * fires; restoring it afterwards moves it to DRAFT, so still no event.
     * Verified against {@code pressroom_event} through the event manager, not
     * assumed from the gate's code.
     */
    @Test
    void trashingAPublishedEntryEmitsNoRollerEvent() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry("published-no-double-event", blog, user);
        TestUtils.endSession(true);

        List<PressroomEvent> afterPublish = events();
        assertEquals(1, afterPublish.size(),
                "the initial publish itself records exactly one event: " + afterPublish);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        entries().trashWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertEquals(1, events().size(),
                "trashing a published entry must not itself count as a publish event");

        WeblogEntry trashed = entries().getWeblogEntry(created.getId());
        entries().restoreWeblogEntry(trashed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertEquals(1, events().size(),
                "restoring to DRAFT must not record a publish event either");
    }

    // ---------------------------------------------------------------- helpers

    private static WeblogEntryManager entries() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    /**
     * Forces a fresh, known {@code lastModified} value and returns it, so a
     * later assertion that it moved cannot pass by coincidence against
     * whatever the fixture setup happened to leave behind. Same idiom as
     * {@code WeblogPageManagerTest}.
     */
    private Date freshLastModifiedBaseline() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(blog);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        return TestUtils.getManagedWebsite(blog).getLastModified();
    }

    private List<PressroomEvent> events() throws Exception {
        return TestUtils.weblogger().getEventManager()
                .getEvents(TestUtils.getManagedWebsite(blog), 10);
    }

    /**
     * Trashes the entry with a caller-chosen stamp rather than "now", so
     * ordering and retention-window tests can be deterministic instead of
     * racing the clock.
     */
    private void trashWithStamp(WeblogEntry entry, Instant trashedAt) throws Exception {
        WeblogEntry managed = entries().getWeblogEntry(entry.getId());
        managed.setStatus(PubStatus.TRASHED);
        managed.setTrashedAt(Timestamp.from(trashedAt));
        entries().saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }
}
