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
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria.SortBy;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria.SortOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code getWeblogEntries} under every criterion it accepts.
 *
 * <p>This one method decides what a reader sees on a front page, what a search
 * returns, what a feed carries and what an author finds in their entry list --
 * every one of those callers hands it a {@link WeblogEntrySearchCriteria} and
 * trusts the answer. It builds its JPQL by string concatenation, one
 * {@code if} per criterion, with hand-numbered positional parameters. That
 * shape fails in a particular way: a criterion that stops being applied returns
 * <em>more</em> rows, not fewer, so a draft leaks onto a public page or one
 * weblog's entries appear on another's, and nothing throws.
 *
 * <p>Ninety-three uncovered branches lived here. The fixture below is built so
 * each test can assert both halves -- what came back and what did not -- since
 * a filter that quietly stopped filtering still returns the row you were
 * looking for.
 */
class WeblogEntryCriteriaTest {

    private User alice;
    private User bob;
    private Weblog blog;
    private Weblog otherBlog;
    private WeblogCategory travel;
    private WeblogCategory food;

    /** Deliberately spread across time so the date filters have something to cut. */
    private WeblogEntry oldTravelPost;
    private WeblogEntry recentFoodPost;
    private WeblogEntry draft;
    private WeblogEntry bobsPost;
    private WeblogEntry otherBlogPost;
    private WeblogEntry trashedPost;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();

        alice = TestUtils.setupUser("criteriaAlice");
        bob = TestUtils.setupUser("criteriaBob");
        blog = TestUtils.setupWeblog("criteriablog", alice);
        otherBlog = TestUtils.setupWeblog("criteriaother", bob);
        TestUtils.endSession(true);

        travel = TestUtils.setupWeblogCategory(TestUtils.getManagedWebsite(blog), "Travel");
        food = TestUtils.setupWeblogCategory(TestUtils.getManagedWebsite(blog), "Food");
        TestUtils.endSession(true);

        oldTravelPost = entry("old-travel", travel, PubStatus.PUBLISHED, blog, alice,
                daysAgo(30), "Hiking in Spain", "mountains and long walks");
        recentFoodPost = entry("recent-food", food, PubStatus.PUBLISHED, blog, alice,
                daysAgo(1), "Dinner in Lisbon", "grilled sardines");
        draft = entry("a-draft", travel, PubStatus.DRAFT, blog, alice,
                daysAgo(2), "Unfinished", "not ready yet");
        bobsPost = entry("bobs-post", travel, PubStatus.PUBLISHED, blog, bob,
                daysAgo(3), "Bob writes too", "a guest contribution");
        otherBlogPost = entry("other-blog-post", null, PubStatus.PUBLISHED, otherBlog, bob,
                daysAgo(1), "Somewhere else entirely", "different weblog");
        trashedPost = entry("a-trashed-post", travel, PubStatus.TRASHED, blog, alice,
                daysAgo(4), "Deleted by mistake", "should not be visible by default");
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(blog.getId());
        TestUtils.teardownWeblog(otherBlog.getId());
        TestUtils.teardownUser(alice.getUserName());
        TestUtils.teardownUser(bob.getUserName());
        TestUtils.endSession(true);
    }

    // --------------------------------------------------------------- weblog

    /**
     * The tenant filter. Without it one weblog's entries appear on another's
     * pages, which is the failure this whole method most has to avoid.
     */
    @Test
    void aWeblogCriterionReturnsOnlyThatWeblogsEntries() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> c.setWeblog(managed(blog))));

        assertTrue(anchors.contains("old-travel"));
        assertFalse(anchors.contains("other-blog-post"),
                "another weblog's entry must never appear: " + anchors);
    }

    /**
     * With no weblog named the query falls back to every <em>visible</em>
     * weblog, which is what the site-wide front page and aggregated feeds
     * depend on.
     */
    @Test
    void noWeblogCriterionSpansEveryVisibleWeblog() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> { }));

        assertTrue(anchors.contains("old-travel") && anchors.contains("other-blog-post"),
                "a site-wide query must reach both weblogs: " + anchors);
    }

    // ----------------------------------------------------------------- user

    @Test
    void aUserCriterionReturnsOnlyThatAuthorsEntries() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setUser(managed(bob));
        }));

        assertEquals(List.of("bobs-post"), anchors,
                "only the named author's entries, and all of them");
    }

    // ---------------------------------------------------------------- dates

    @Test
    void aStartDateExcludesAnythingPublishedBeforeIt() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setStartDate(daysAgo(7));
        }));

        assertTrue(anchors.contains("recent-food"));
        assertFalse(anchors.contains("old-travel"),
                "a post from 30 days ago is outside a 7-day window: " + anchors);
    }

    @Test
    void anEndDateExcludesAnythingPublishedAfterIt() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setEndDate(daysAgo(7));
        }));

        assertTrue(anchors.contains("old-travel"));
        assertFalse(anchors.contains("recent-food"), "got: " + anchors);
    }

    @Test
    void startAndEndTogetherBoundBothSides() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setStartDate(daysAgo(5));
            c.setEndDate(daysAgo(2));
        }));

        assertTrue(anchors.contains("bobs-post"), "the 3-day-old post is inside: " + anchors);
        assertFalse(anchors.contains("old-travel"), "got: " + anchors);
        assertFalse(anchors.contains("recent-food"), "got: " + anchors);
    }

    // ------------------------------------------------------------- category

    @Test
    void aCategoryNameNarrowsToThatCategory() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setCatName("Food");
        }));

        assertEquals(List.of("recent-food"), anchors);
    }

    /**
     * A category that does not exist matches nothing.
     *
     * <p>It used to drop the filter and return every entry, so a url naming a
     * renamed category answered with the weblog's whole archive. Public
     * rendering happened to 404 first, but feeds and {@code SiteModel} call
     * this directly, and "everything" is the wrong answer to "show me this one
     * category" from any caller.
     */
    @Test
    void anUnknownCategoryNameMatchesNothing() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setCatName("NoSuchCategory");
        }));

        assertTrue(anchors.isEmpty(),
                "a category nobody has cannot contain entries: " + anchors);
    }

    // --------------------------------------------------------------- status

    /**
     * The one that keeps unfinished writing off the public web. Every public
     * caller sets PUBLISHED, and a filter that stopped applying would publish
     * every draft in the installation at once.
     */
    @Test
    void aStatusCriterionExcludesEveryOtherStatus() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setStatus(PubStatus.PUBLISHED);
        }));

        assertFalse(anchors.contains("a-draft"),
                "a draft must not survive a PUBLISHED filter: " + anchors);
        assertTrue(anchors.contains("old-travel"));
    }

    /**
     * A PUBLISHED filter is exactly as blind to trash as it is to any other
     * status -- an explicit status always wins, and TRASHED is simply another
     * status a caller did not ask for. See the trash section below for the
     * one place trash gets a default of its own.
     */
    @Test
    void aPublishedStatusCriterionNeverReturnsATrashedEntry() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setStatus(PubStatus.PUBLISHED);
        }));

        assertFalse(anchors.contains("a-trashed-post"),
                "a trashed entry must not survive a PUBLISHED filter: " + anchors);
    }

    @Test
    void draftsAreReachableWhenAskedForByStatus() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setStatus(PubStatus.DRAFT);
        }));

        assertEquals(List.of("a-draft"), anchors,
                "the author's own entry list asks for these by name");
    }

    /**
     * Every status but trash. This is the "nothing is filtered out" case for
     * everything except trash: see {@code aTrashedEntryIsAbsentByDefault}
     * immediately below for why trash is the one exception, and why it has to
     * be.
     */
    @Test
    void noStatusCriterionReturnsEveryStatusExceptTrash() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> c.setWeblog(managed(blog))));

        assertTrue(anchors.contains("a-draft") && anchors.contains("old-travel"),
                "with no status asked for, nothing but trash is filtered out: " + anchors);
    }

    // ---------------------------------------------------------------- trash

    /**
     * The property the whole trash design rests on: a caller that names no
     * status and does not ask for trash must never see a trashed entry, with
     * no per-caller opt-out required. This is what keeps a deleted entry off
     * the 23 call sites of {@code getWeblogEntries} that do not think about
     * trash at all.
     */
    @Test
    void aTrashedEntryIsAbsentByDefault() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> c.setWeblog(managed(blog))));

        assertFalse(anchors.contains("a-trashed-post"),
                "a default-constructed criteria must never surface trash: " + anchors);
    }

    /** The one escape hatch, and it is opt-in: only the trash screen sets this. */
    @Test
    void aTrashedEntryIsPresentWhenIncludeTrashedIsSet() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setIncludeTrashed(true);
        }));

        assertTrue(anchors.contains("a-trashed-post"),
                "includeTrashed=true must surface trash: " + anchors);
    }

    /** Asking for TRASHED by name is the other way to reach it. */
    @Test
    void aTrashedEntryIsPresentWhenStatusIsTrashedExplicitly() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setStatus(PubStatus.TRASHED);
        }));

        assertEquals(List.of("a-trashed-post"), anchors);
    }

    // --------------------------------------------------------------- locale

    /**
     * Locale matches as a prefix, so asking for "en" finds "en_US" -- that is
     * what makes a language-scoped feed work without enumerating every region.
     */
    @Test
    void aLocaleCriterionMatchesOnPrefix() throws Exception {
        setLocale(oldTravelPost, "en_US");
        setLocale(recentFoodPost, "fr_FR");
        TestUtils.endSession(true);

        List<String> english = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setLocale("en");
        }));

        assertTrue(english.contains("old-travel"), "en must match en_US: " + english);
        assertFalse(english.contains("recent-food"), "and must not match fr_FR: " + english);
    }

    // ----------------------------------------------------------------- text

    /**
     * The text criterion searches title, summary and body together -- a reader
     * typing a word they remember does not know which field it was in.
     */
    @Test
    void aTextCriterionSearchesTitleSummaryAndBody() throws Exception {
        List<String> byTitle = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setText("Lisbon");
        }));
        assertEquals(List.of("recent-food"), byTitle, "matched in the title");

        List<String> byBody = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setText("sardines");
        }));
        assertEquals(List.of("recent-food"), byBody, "matched in the body");
    }

    @Test
    void aTextCriterionThatMatchesNothingReturnsNothing() throws Exception {
        assertTrue(anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setText("zzz-no-such-word");
        })).isEmpty());
    }

    // ----------------------------------------------------------------- tags

    @Test
    void aTagCriterionReturnsOnlyEntriesCarryingThatTag() throws Exception {
        tag(oldTravelPost, "spain");
        tag(recentFoodPost, "portugal");
        TestUtils.endSession(true);

        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setTags(List.of("spain"));
        }));

        assertEquals(List.of("old-travel"), anchors);
    }

    /** Several tags are an OR, which is what a multi-tag url means. */
    @Test
    void severalTagsMatchAnyOfThem() throws Exception {
        tag(oldTravelPost, "spain");
        tag(recentFoodPost, "portugal");
        TestUtils.endSession(true);

        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setTags(List.of("spain", "portugal"));
        }));

        assertEquals(2, anchors.size(), "both tagged entries: " + anchors);
        assertTrue(anchors.containsAll(List.of("old-travel", "recent-food")));
    }

    // -------------------------------------------------------------- ordering

    @Test
    void resultsAreNewestFirstByDefault() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setStatus(PubStatus.PUBLISHED);
        }));

        assertEquals(List.of("recent-food", "bobs-post", "old-travel"), anchors,
                "a front page shows the newest post at the top");
    }

    @Test
    void ascendingOrderCanBeAskedFor() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setStatus(PubStatus.PUBLISHED);
            c.setSortOrder(SortOrder.ASCENDING);
        }));

        assertEquals(List.of("old-travel", "bobs-post", "recent-food"), anchors);
    }

    /**
     * Sorting by update time rather than publication time is what the author's
     * entry list uses -- recently edited, not recently published.
     */
    @Test
    void sortingByUpdateTimeIsDistinctFromPublicationTime() throws Exception {
        touchUpdateTime(oldTravelPost, Instant.now());
        TestUtils.endSession(true);

        List<String> byUpdate = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setStatus(PubStatus.PUBLISHED);
            c.setSortBy(SortBy.UPDATE_TIME);
        }));

        assertEquals("old-travel", byUpdate.get(0),
                "the just-edited entry sorts first by update time even though it is the "
                        + "oldest by publication time: " + byUpdate);
    }

    // ---------------------------------------------------------------- paging

    @Test
    void maxResultsBoundsThePage() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setStatus(PubStatus.PUBLISHED);
            c.setMaxResults(2);
        }));

        assertEquals(List.of("recent-food", "bobs-post"), anchors);
    }

    @Test
    void offsetSkipsIntoTheOrderedResults() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setStatus(PubStatus.PUBLISHED);
            c.setOffset(1);
            c.setMaxResults(1);
        }));

        assertEquals(List.of("bobs-post"), anchors,
                "page two of a one-per-page listing");
    }

    // ------------------------------------------------------------ combined

    /**
     * The criteria are conjunctive. Every public page combines several at once,
     * and a criterion silently dropped from the conjunction widens the result
     * rather than narrowing it.
     */
    @Test
    void criteriaCombineAsAConjunction() throws Exception {
        List<String> anchors = anchorsOf(criteria(c -> {
            c.setWeblog(managed(blog));
            c.setUser(managed(alice));
            c.setStatus(PubStatus.PUBLISHED);
            c.setCatName("Travel");
        }));

        assertEquals(List.of("old-travel"), anchors,
                "alice's published travel post, and nothing else -- not bob's travel post, "
                        + "not the draft, not the food post: " + anchors);
    }

    // ---------------------------------------------------------------- helpers

    private interface CriteriaTweak {
        void apply(WeblogEntrySearchCriteria criteria);
    }

    private static WeblogEntrySearchCriteria criteria(CriteriaTweak tweak) {
        WeblogEntrySearchCriteria criteria = new WeblogEntrySearchCriteria();
        tweak.apply(criteria);
        return criteria;
    }

    private static List<String> anchorsOf(WeblogEntrySearchCriteria criteria) throws Exception {
        return TestUtils.weblogger().getWeblogEntryManager()
                .getWeblogEntries(criteria).stream()
                .map(WeblogEntry::getAnchor)
                .toList();
    }

    private static Weblog managed(Weblog weblog) {
        try {
            return TestUtils.getManagedWebsite(weblog);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static User managed(User user) {
        try {
            return TestUtils.getManagedUser(user);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Date daysAgo(int days) {
        return Date.from(Instant.now().minus(days, ChronoUnit.DAYS));
    }

    private static WeblogEntry entry(String anchor, WeblogCategory category, PubStatus status,
            Weblog weblog, User user, Date pubTime, String title, String text) throws Exception {
        WeblogCategory cat = category != null ? category
                : TestUtils.setupWeblogCategory(TestUtils.getManagedWebsite(weblog), "General-" + anchor);
        WeblogEntry created = TestUtils.setupWeblogEntry(anchor, cat, status, weblog, user);

        WeblogEntryManager manager = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = manager.getWeblogEntry(created.getId());
        managed.setPubTime(new Timestamp(pubTime.getTime()));
        managed.setUpdateTime(new Timestamp(pubTime.getTime()));
        managed.setTitle(title);
        managed.setText(text);
        manager.saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
        return managed;
    }

    private static void setLocale(WeblogEntry entry, String locale) throws Exception {
        WeblogEntryManager manager = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = manager.getWeblogEntry(entry.getId());
        managed.setLocale(locale);
        manager.saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
    }

    private static void tag(WeblogEntry entry, String tagName) throws Exception {
        WeblogEntryManager manager = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = manager.getWeblogEntry(entry.getId());
        managed.addTag(tagName);
        manager.saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
    }

    private static void touchUpdateTime(WeblogEntry entry, Instant when) throws Exception {
        WeblogEntryManager manager = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = manager.getWeblogEntry(entry.getId());
        managed.setUpdateTime(new Timestamp(when.toEpochMilli()));
        manager.saveWeblogEntry(managed);
        TestUtils.weblogger().flush();
    }
}
