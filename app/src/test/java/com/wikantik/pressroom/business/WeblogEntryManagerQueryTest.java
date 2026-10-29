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
import com.wikantik.pressroom.pojos.TagStat;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of {@code JPAWeblogEntryManagerImpl} outside the entry-criteria
 * query: tag aggregates, entry navigation and attributes.
 *
 * <p>Unlike {@code PageServlet}, nothing here needed restructuring -- these are
 * small, single-purpose methods that simply had no tests. Two were at zero
 * ({@code removeWeblogEntryAttribute}, {@code getRevision}), and two more
 * carried most of their branches in the null-or-not permutations of their
 * parameters, which is exactly what goes wrong when a caller starts passing a
 * null it never used to.
 *
 * <p>W1 deleted the comment bulk-operation coverage this class used to carry
 * ({@code removeMatchingComments}, {@code applyCommentDefaultsToEntries}, and
 * the {@code getMostCommentedWeblogEntries} ranking) along with the manager
 * methods themselves.
 */
class WeblogEntryManagerQueryTest {

    private User user;
    private Weblog blog;
    private WeblogCategory travel;
    private WeblogEntry popular;
    private WeblogEntry quiet;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();

        user = TestUtils.setupUser("wemqUser");
        blog = TestUtils.setupWeblog("wemqblog", user);
        TestUtils.endSession(true);

        travel = TestUtils.setupWeblogCategory(TestUtils.getManagedWebsite(blog), "Travel");
        TestUtils.endSession(true);

        popular = entry("popular-post", "Popular", Instant.now().minus(2, ChronoUnit.DAYS));
        quiet = entry("quiet-post", "Quiet", Instant.now().minus(1, ChronoUnit.DAYS));
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(blog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------------ tags

    @Test
    void popularTagsAreReturnedForAWeblog() throws Exception {
        tag(popular, "spain");
        tag(quiet, "spain");
        tag(quiet, "food");
        TestUtils.endSession(true);

        List<TagStat> tags = entries().getPopularTags(
                TestUtils.getManagedWebsite(blog), null, 0, 10);

        List<String> names = tags.stream().map(TagStat::getName).toList();
        assertTrue(names.containsAll(List.of("spain", "food")), "got: " + names);

        // Alphabetical, not by count. The query orders by total to pick the top
        // N and the results are then deliberately re-sorted by name, because
        // this feeds a tag cloud: position is alphabetical and popularity is
        // carried by intensity, the 1-5 bucket a theme renders as font size.
        assertEquals(List.of("food", "spain"), names,
                "a tag cloud reads alphabetically: " + names);

        int spain = tags.stream().filter(t -> t.getName().equals("spain"))
                .findFirst().orElseThrow().getIntensity();
        int food = tags.stream().filter(t -> t.getName().equals("food"))
                .findFirst().orElseThrow().getIntensity();
        assertTrue(spain > food,
                "the tag used twice must render larger than the one used once: "
                        + "spain=" + spain + " food=" + food);
    }

    /**
     * A start date bounds the aggregate, which is how "popular this month"
     * differs from "popular ever".
     */
    @Test
    void popularTagsCanBeBoundedByStartDate() throws Exception {
        tag(popular, "spain");
        TestUtils.endSession(true);

        Date future = Date.from(Instant.now().plus(1, ChronoUnit.DAYS));
        List<TagStat> none = entries().getPopularTags(
                TestUtils.getManagedWebsite(blog), future, 0, 10);

        assertTrue(none.isEmpty(),
                "nothing was tagged after tomorrow: " + none.stream().map(TagStat::getName).toList());
    }

    /** A null weblog aggregates across the whole site, for the front page. */
    @Test
    void popularTagsAcrossTheWholeSiteDoNotRequireAWeblog() throws Exception {
        tag(popular, "spain");
        TestUtils.endSession(true);

        List<String> names = entries().getPopularTags(null, null, 0, 50)
                .stream().map(TagStat::getName).toList();

        assertTrue(names.contains("spain"), "got: " + names);
    }

    @Test
    void tagsCanBeListedAndFilteredByPrefix() throws Exception {
        tag(popular, "spain");
        tag(quiet, "food");
        TestUtils.endSession(true);

        Weblog managed = TestUtils.getManagedWebsite(blog);
        List<String> all = entries().getTags(managed, null, null, 0, 50)
                .stream().map(TagStat::getName).toList();
        assertTrue(all.containsAll(List.of("spain", "food")), "got: " + all);

        List<String> starting = entries().getTags(managed, null, "sp", 0, 50)
                .stream().map(TagStat::getName).toList();
        assertEquals(List.of("spain"), starting);
    }

    @Test
    void tagsCanBeSortedByCountInsteadOfName() throws Exception {
        tag(popular, "spain");
        tag(quiet, "spain");
        tag(quiet, "food");
        TestUtils.endSession(true);

        List<String> byCount = entries()
                .getTags(TestUtils.getManagedWebsite(blog), "count", null, 0, 50)
                .stream().map(TagStat::getName).toList();

        assertEquals("spain", byCount.get(0),
                "sorted by count the most-used tag leads: " + byCount);
    }

    // ------------------------------------------------------------ navigation

    /**
     * Next and previous are what a permalink's pager links to, and they are
     * ordered by publication time rather than by insertion.
     */
    @Test
    void nextAndPreviousFollowPublicationOrder() throws Exception {
        WeblogEntry managedPopular = TestUtils.getManagedWeblogEntry(popular);
        WeblogEntry managedQuiet = TestUtils.getManagedWeblogEntry(quiet);

        WeblogEntry next = entries().getNextEntry(managedPopular, null, null);
        assertNotNull(next, "the older post must have a newer one after it");
        assertEquals(managedQuiet.getAnchor(), next.getAnchor());

        WeblogEntry previous = entries().getPreviousEntry(managedQuiet, null, null);
        assertNotNull(previous);
        assertEquals(managedPopular.getAnchor(), previous.getAnchor());
    }

    @Test
    void theNewestEntryHasNothingAfterIt() throws Exception {
        assertNull(entries().getNextEntry(TestUtils.getManagedWeblogEntry(quiet), null, null),
                "the pager must stop rather than wrap around");
    }

    @Test
    void navigationCanBeConfinedToACategory() throws Exception {
        WeblogEntry next = entries().getNextEntry(
                TestUtils.getManagedWeblogEntry(popular), "Travel", null);

        assertNotNull(next, "both entries are in Travel: " + next);
        assertEquals(quiet.getAnchor(), next.getAnchor());
    }

    // ------------------------------------------------------------ attributes

    @Test
    void anEntryAttributeCanBeRemoved() throws Exception {
        WeblogEntry managed = TestUtils.getManagedWeblogEntry(popular);
        managed.putEntryAttribute("pinned", "yes");
        entries().saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        managed = TestUtils.getManagedWeblogEntry(popular);
        assertNotNull(managed.findEntryAttribute("pinned"));

        entries().removeWeblogEntryAttribute("pinned", managed);
        entries().saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        assertNull(TestUtils.getManagedWeblogEntry(popular).findEntryAttribute("pinned"),
                "a removed attribute must be gone from the entry and the database");
    }

    /**
     * Removing an attribute that is not there is a no-op, not an error -- the
     * caller does not know what the entry carries.
     */
    @Test
    void removingAnAttributeThatIsNotThereChangesNothing() throws Exception {
        WeblogEntry managed = TestUtils.getManagedWeblogEntry(popular);
        int before = managed.getEntryAttributes().size();

        entries().removeWeblogEntryAttribute("never-set", managed);

        assertEquals(before, managed.getEntryAttributes().size());
    }

    // -------------------------------------------------------------- revisions

    @Test
    void anUnknownRevisionIdResolvesToNothing() throws Exception {
        assertNull(entries().getRevision("no-such-revision"));
        assertNull(entries().getRevision(null),
                "a null id must not reach the database");
    }

    // -------------------------------------------------------------- trash
    //
    // These two exist because the two named queries they exercise
    // (WeblogEntry.getByCategory, WeblogEntry.getByWebsite&Anchor) name no
    // status and deliberately do NOT get the trash exclusion that
    // getWeblogEntries() gets by default -- a trashed entry still holds its
    // category and still occupies its anchor. See WeblogEntry.orm.xml for
    // why, and WeblogEntryCriteriaTest for the query that DOES exclude trash.

    /**
     * A category whose only entry has been trashed must still be refused
     * deletion -- the entry has not been deleted forever, it can be
     * restored, and deleting the category out from under it would leave
     * nothing to restore into. {@code isWeblogCategoryInUse} (a UI hint fed
     * by the always-trash-visible {@code getByCategory} query) is not what
     * enforces this; {@code removeWeblogCategory}'s own guard is, and it
     * goes through {@code retrieveWeblogEntries(false)} instead -- so this
     * asserts the actual enforcement, not the query next to it.
     */
    @Test
    void deletingACategoryWhoseOnlyEntryIsTrashedIsRefused() throws Exception {
        WeblogCategory onlyTrash = TestUtils.setupWeblogCategory(
                TestUtils.getManagedWebsite(blog), "OnlyTrash");
        TestUtils.endSession(true);

        WeblogEntry created = TestUtils.setupWeblogEntry(
                "to-be-trashed", onlyTrash, PubStatus.PUBLISHED, blog, user);
        TestUtils.endSession(true);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        managed.setStatus(PubStatus.TRASHED);
        entries().saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        WeblogCategory managedCat = TestUtils.getManagedWeblogCategory(onlyTrash);
        assertThrows(WebloggerException.class, () -> entries().removeWeblogCategory(managedCat),
                "a category holding only a trashed entry must still be refused deletion");
    }

    /**
     * Moving a category's contents elsewhere -- the path the UI drives before
     * it will let an author delete a non-empty category -- must carry a
     * trashed entry along too. Before the fix, {@code
     * moveWeblogCategoryContents} silently moved zero entries for a
     * category holding only trash, leaving the trashed entry pointed at a
     * category about to be removed out from under it.
     */
    @Test
    void movingACategorysContentsMovesItsTrashedEntryToo() throws Exception {
        WeblogCategory onlyTrash = TestUtils.setupWeblogCategory(
                TestUtils.getManagedWebsite(blog), "OnlyTrash2");
        WeblogCategory dest = TestUtils.setupWeblogCategory(
                TestUtils.getManagedWebsite(blog), "Dest");
        TestUtils.endSession(true);

        WeblogEntry created = TestUtils.setupWeblogEntry(
                "to-be-trashed-and-moved", onlyTrash, PubStatus.PUBLISHED, blog, user);
        TestUtils.endSession(true);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        managed.setStatus(PubStatus.TRASHED);
        entries().saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        WeblogCategory src = TestUtils.getManagedWeblogCategory(onlyTrash);
        WeblogCategory destManaged = TestUtils.getManagedWeblogCategory(dest);
        entries().moveWeblogCategoryContents(src, destManaged);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        WeblogEntry moved = entries().getWeblogEntry(created.getId());
        assertEquals(dest.getId(), moved.getCategory().getId(),
                "moving a category's contents must move its trashed entry, not skip it");
    }

    /**
     * Trashing an entry does not free its anchor. If it did, a new entry
     * could take the same anchor and collide with the trashed one the moment
     * it is restored.
     */
    @Test
    void theAnchorOfATrashedEntryIsStillTaken() throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry(
                "trash-anchor-post", travel, PubStatus.PUBLISHED, blog, user);
        TestUtils.endSession(true);

        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        managed.setStatus(PubStatus.TRASHED);
        entries().saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        // setupWeblogEntry gives the entry the same title as its anchor, so
        // createAnchorBase() on the trashed entry reproduces its own anchor
        // exactly -- the same shape createAnchor()'s own uniqueness check
        // has to defeat.
        WeblogEntry trashed = TestUtils.getManagedWeblogEntry(created);
        String newAnchor = entries().createAnchor(trashed);

        assertNotEquals("trash-anchor-post", newAnchor,
                "a trashed entry's anchor must still be reported as taken: got " + newAnchor);
    }

    // ---------------------------------------------------------------- helpers

    private static WeblogEntryManager entries() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    private WeblogEntry entry(String anchor, String title, Instant pubTime) throws Exception {
        WeblogEntry created = TestUtils.setupWeblogEntry(anchor, travel,
                PubStatus.PUBLISHED, blog, user);
        WeblogEntry managed = entries().getWeblogEntry(created.getId());
        managed.setTitle(title);
        managed.setPubTime(new Timestamp(pubTime.toEpochMilli()));
        managed.setUpdateTime(new Timestamp(pubTime.toEpochMilli()));
        entries().saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
        return managed;
    }

    private void tag(WeblogEntry entry, String tagName) throws Exception {
        WeblogEntry managed = entries().getWeblogEntry(entry.getId());
        managed.addTag(tagName);
        entries().saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
    }
}
