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
package com.wikantik.pressroom.business.jpa;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.EventManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Refusal and filter branches of {@link JPAWeblogEntryManagerImpl}: the
 * argument guards, category-name uniqueness, how the next/previous entry
 * navigation narrows by category and locale, the tag-cloud query variants,
 * and that a failure to record the publish event never fails the save.
 */
class JPAWeblogEntryManagerBranchesTest {

    private User user;
    private Weblog weblog;
    private WeblogEntryManager entries;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        entries = TestUtils.weblogger().getWeblogEntryManager();
        user = TestUtils.setupUser("emb");
        weblog = TestUtils.setupWeblog("embblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private WeblogEntry entryAt(String anchor, WeblogCategory cat, long minutesAgo) throws Exception {
        WeblogEntry e = TestUtils.setupWeblogEntry(anchor, cat, PubStatus.PUBLISHED, weblog, user);
        WeblogEntry managed = entries.getWeblogEntry(e.getId());
        managed.setPubTime(new Timestamp(System.currentTimeMillis() - minutesAgo * 60_000L));
        entries.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return entries.getWeblogEntry(e.getId());
    }

    // ----------------------------------------------------------------- guards

    @Test
    void lookupsRefuseAMissingWeblogOrAnchorAndAnEmptyRevisionListIsNotAnError() throws Exception {
        assertThrows(WebloggerException.class, () -> entries.getWeblogCategories(null));
        assertThrows(WebloggerException.class, () -> entries.getWeblogEntryByAnchor(null, "a"));
        assertThrows(WebloggerException.class,
                () -> entries.getWeblogEntryByAnchor(TestUtils.getManagedWebsite(weblog), null));
        assertTrue(entries.getRevisions(null).isEmpty());
        assertTrue(entries.getRevisions(new WeblogEntry()).isEmpty(), "an unsaved entry has no history");
        assertNull(entries.getRevision(null));
        assertFalse(entries.getTagComboExists(List.of(), TestUtils.getManagedWebsite(weblog)));
        assertFalse(entries.getTagComboExists(null, null));
    }

    @Test
    void aRemovedEntryIsNotServedFromTheAnchorCache() throws Exception {
        WeblogCategory cat = TestUtils.getManagedWebsite(weblog).getWeblogCategories().iterator().next();
        WeblogEntry e = entryAt("embgone", cat, 5);
        assertNotNull(entries.getWeblogEntryByAnchor(TestUtils.getManagedWebsite(weblog), "embgone"),
                "primes the anchor cache");

        TestUtils.teardownWeblogEntry(e.getId());
        TestUtils.endSession(true);

        assertNull(entries.getWeblogEntryByAnchor(TestUtils.getManagedWebsite(weblog), "embgone"));
    }

    // -------------------------------------------------------------- categories

    @Test
    void aCategoryNameCannotBeUsedTwiceInOneWeblogButASavedCategoryCanBeResaved() throws Exception {
        WeblogCategory cat = TestUtils.setupWeblogCategory(TestUtils.getManagedWebsite(weblog), "Embcat");
        TestUtils.endSession(true);
        try {
            Weblog managedWeblog = TestUtils.getManagedWebsite(weblog);
            WeblogCategory dup = new WeblogCategory(managedWeblog, "Embcat", null, null);
            assertThrows(WebloggerException.class, () -> entries.saveWeblogCategory(dup));
            // the constructor attached the refused category to its weblog; end the
            // session WITHOUT flushing so it is discarded instead of persisted
            TestUtils.endSession(false);

            WeblogCategory again = entries.getWeblogCategory(cat.getId());
            again.setDescription("changed");
            entries.saveWeblogCategory(again);
            TestUtils.endSession(true);
            assertEquals("changed", entries.getWeblogCategory(cat.getId()).getDescription());
        } finally {
            TestUtils.teardownWeblogCategory(cat.getId());
            TestUtils.endSession(true);
        }
    }

    // ------------------------------------------------------------ navigation

    @Test
    void nextAndPreviousEntryNarrowByCategoryAndLocale() throws Exception {
        Weblog w = TestUtils.getManagedWebsite(weblog);
        WeblogCategory general = w.getWeblogCategories().iterator().next();
        WeblogCategory other = TestUtils.setupWeblogCategory(w, "Embother");
        TestUtils.endSession(true);
        WeblogEntry a = entryAt("emba", general, 180);
        WeblogEntry b = entryAt("embb", general, 120);
        WeblogEntry c = entryAt("embc", general, 60);
        WeblogEntry d = entryAt("embd", entries.getWeblogCategory(other.getId()), 30);
        try {
            assertEquals("embb", entries.getNextEntry(a, null, null).getAnchor());
            assertEquals("embd", entries.getNextEntry(a, "Embother", null).getAnchor(),
                    "restricting to a category skips the entries outside it");
            assertEquals("embb", entries.getPreviousEntry(c, null, null).getAnchor());
            assertNull(entries.getPreviousEntry(a, null, null), "nothing before the oldest");
            assertNull(entries.getNextEntry(d, null, null), "nothing after the newest");
            assertNotNull(entries.getNextEntry(a, null, "en"), "locale en matches en_US");
            assertNull(entries.getNextEntry(a, null, "fr"), "no entry in French");
            assertThrows(WebloggerException.class, () -> entries.getNextEntry(a, "NoSuchCategory", null),
                    "a category that does not exist is an error, not 'no neighbour'");
            assertNull(entries.getNextEntry(null, null, null), "no current entry, no neighbour");
        } finally {
            for (WeblogEntry e : List.of(a, b, c, d)) {
                TestUtils.teardownWeblogEntry(e.getId());
            }
            TestUtils.teardownWeblogCategory(other.getId());
            TestUtils.endSession(true);
        }
    }

    // ------------------------------------------------------------------- tags

    @Test
    void popularTagsCanBeCountedForOneWeblogOrTheWholeSiteWithAndWithoutAStartDate() throws Exception {
        Weblog w = TestUtils.getManagedWebsite(weblog);
        WeblogCategory cat = w.getWeblogCategories().iterator().next();
        WeblogEntry e = TestUtils.setupWeblogEntry("embtags", cat, PubStatus.DRAFT, weblog, user);
        WeblogEntry managed = entries.getWeblogEntry(e.getId());
        managed.addTag("embtagone");
        managed.setStatus(PubStatus.PUBLISHED);
        entries.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        try {
            Date past = new Date(System.currentTimeMillis() - 3_600_000L);
            Date future = new Date(System.currentTimeMillis() + 3_600_000L);
            Weblog again = TestUtils.getManagedWebsite(weblog);

            assertTrue(entries.getPopularTags(again, null, 0, -1).stream().anyMatch(t -> "embtagone".equals(t.getName())));
            assertTrue(entries.getPopularTags(again, past, 0, -1).stream().anyMatch(t -> "embtagone".equals(t.getName())));
            assertTrue(entries.getPopularTags(again, future, 0, -1).isEmpty(), "not used since the start date");
            assertTrue(entries.getPopularTags(null, null, 0, -1).stream().anyMatch(t -> "embtagone".equals(t.getName())));
            assertTrue(entries.getPopularTags(null, past, 0, -1).stream().anyMatch(t -> "embtagone".equals(t.getName())));
            assertTrue(entries.getPopularTags(null, future, 0, -1).stream().noneMatch(t -> "embtagone".equals(t.getName())));

            assertTrue(entries.getTagComboExists(List.of("embtagone"), again));
            assertTrue(entries.getTagComboExists(List.of("embtagone"), null), "site-wide aggregate");
            assertFalse(entries.getTagComboExists(List.of("embtagone", "embnever"), again),
                    "every tag of the combination must exist");
        } finally {
            TestUtils.teardownWeblogEntry(e.getId());
            TestUtils.endSession(true);
        }
    }

    // ----------------------------------------------------------------- events

    /**
     * An entry's first publication is recorded as an analytics event, but the
     * event is best-effort: if recording it fails, the author's save still
     * succeeds.
     */
    @Test
    void aFailureRecordingThePublishEventDoesNotFailTheSave() throws Exception {
        Field strategyField = JPAWeblogEntryManagerImpl.class.getDeclaredField("strategy");
        strategyField.setAccessible(true);
        JPAPersistenceStrategy strategy = (JPAPersistenceStrategy) strategyField.get(entries);

        Weblogger real = TestUtils.weblogger();
        Weblogger wrapped = mock(Weblogger.class, AdditionalAnswers.delegatesTo(real));
        EventManager failing = mock(EventManager.class);
        doThrow(new WebloggerException("event store down")).when(failing).record(any());
        doReturn(failing).when(wrapped).getEventManager();
        WeblogEntryManager flaky = new JPAWeblogEntryManagerImpl(wrapped, strategy);

        Weblog w = TestUtils.getManagedWebsite(weblog);
        WeblogEntry fresh = new WeblogEntry();
        fresh.setTitle("embpub");
        fresh.setText("body");
        fresh.setAnchor("embpub");
        fresh.setStatus(PubStatus.PUBLISHED);
        fresh.setPubTime(new Timestamp(System.currentTimeMillis()));
        fresh.setUpdateTime(new Timestamp(System.currentTimeMillis()));
        fresh.setWebsite(w);
        fresh.setCreatorUserName(TestUtils.getManagedUser(user).getUserName());
        fresh.setCategory(w.getWeblogCategories().iterator().next());

        flaky.saveWeblogEntry(fresh);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        try {
            assertNotNull(entries.getWeblogEntry(fresh.getId()), "the entry was saved despite the event failure");
        } finally {
            TestUtils.teardownWeblogEntry(fresh.getId());
            TestUtils.endSession(true);
        }
    }

    // ------------------------------------------------------- entry searches

    private List<String> anchors(WeblogEntrySearchCriteria criteria) throws Exception {
        criteria.setWeblog(TestUtils.getManagedWebsite(weblog));
        return entries.getWeblogEntries(criteria).stream().map(WeblogEntry::getAnchor).toList();
    }

    private WeblogEntry entryWithTimes(String anchor, long pubMinutesAgo) throws Exception {
        Thread.sleep(5);
        WeblogCategory cat = TestUtils.getManagedWebsite(weblog).getWeblogCategories().iterator().next();
        WeblogEntry e = TestUtils.setupWeblogEntry(anchor, cat, PubStatus.PUBLISHED, weblog, user);
        WeblogEntry managed = entries.getWeblogEntry(e.getId());
        managed.setPubTime(new Timestamp(System.currentTimeMillis() - pubMinutesAgo * 60_000L));
        entries.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        return managed;
    }

    @Test
    void entriesAreSortedByPublicationUpdateOrTrashTimeInEitherDirection() throws Exception {
        // saved in the order a, b -- but a was PUBLISHED more recently than b
        WeblogEntry a = entryWithTimes("embsorta", 60);
        WeblogEntry b = entryWithTimes("embsortb", 120);
        try {
            WeblogEntrySearchCriteria byPub = new WeblogEntrySearchCriteria();
            assertEquals(List.of("embsorta", "embsortb"), anchors(byPub), "newest publication first by default");
            WeblogEntrySearchCriteria ascending = new WeblogEntrySearchCriteria();
            ascending.setSortOrder(WeblogEntrySearchCriteria.SortOrder.ASCENDING);
            assertEquals(List.of("embsortb", "embsorta"), anchors(ascending));

            WeblogEntrySearchCriteria byUpdate = new WeblogEntrySearchCriteria();
            byUpdate.setSortBy(WeblogEntrySearchCriteria.SortBy.UPDATE_TIME);
            assertEquals(List.of("embsortb", "embsorta"), anchors(byUpdate),
                    "b was saved last, so it was updated most recently, whatever its publication time");

            entries.trashWeblogEntry(entries.getWeblogEntry(a.getId()));
            TestUtils.endSession(true);
            Thread.sleep(20);
            entries.trashWeblogEntry(entries.getWeblogEntry(b.getId()));
            TestUtils.endSession(true);
            WeblogEntrySearchCriteria byTrash = new WeblogEntrySearchCriteria();
            byTrash.setIncludeTrashed(true);
            byTrash.setSortBy(WeblogEntrySearchCriteria.SortBy.TRASH_TIME);
            byTrash.setSortOrder(WeblogEntrySearchCriteria.SortOrder.ASCENDING);
            assertEquals(List.of("embsorta", "embsortb"), anchors(byTrash), "earliest trashed first");
        } finally {
            TestUtils.teardownWeblogEntry(a.getId());
            TestUtils.teardownWeblogEntry(b.getId());
            TestUtils.endSession(true);
        }
    }

    @Test
    void trashIsHiddenUnlessAskedForByStatusOrByIncludeTrashed() throws Exception {
        WeblogEntry live = entryWithTimes("embtrashlive", 30);
        WeblogEntry doomed = entryWithTimes("embtrashgone", 20);
        entries.trashWeblogEntry(entries.getWeblogEntry(doomed.getId()));
        TestUtils.endSession(true);
        try {
            assertEquals(List.of("embtrashlive"), anchors(new WeblogEntrySearchCriteria()),
                    "a default search never shows trash");
            WeblogEntrySearchCriteria explicit = new WeblogEntrySearchCriteria();
            explicit.setStatus(PubStatus.TRASHED);
            assertEquals(List.of("embtrashgone"), anchors(explicit));
            WeblogEntrySearchCriteria published = new WeblogEntrySearchCriteria();
            published.setStatus(PubStatus.PUBLISHED);
            published.setIncludeTrashed(true);
            assertEquals(List.of("embtrashlive"), anchors(published),
                    "an explicit status wins over includeTrashed: asking for published never returns trash");
            WeblogEntrySearchCriteria everything = new WeblogEntrySearchCriteria();
            everything.setIncludeTrashed(true);
            assertEquals(2, anchors(everything).size());
        } finally {
            TestUtils.teardownWeblogEntry(live.getId());
            TestUtils.teardownWeblogEntry(doomed.getId());
            TestUtils.endSession(true);
        }
    }

    @Test
    void searchCriteriaNarrowByCreatorLocaleTextDatesAndCategory() throws Exception {
        WeblogEntry e = entryWithTimes("embfilter", 30);
        try {
            WeblogEntrySearchCriteria byCreator = new WeblogEntrySearchCriteria();
            byCreator.setUser(TestUtils.getManagedUser(user));
            assertEquals(List.of("embfilter"), anchors(byCreator));
            WeblogEntrySearchCriteria byOtherCreator = new WeblogEntrySearchCriteria();
            User nobody = new User();
            nobody.setUserName("nobody-at-all");
            byOtherCreator.setUser(nobody);
            assertTrue(anchors(byOtherCreator).isEmpty());

            WeblogEntrySearchCriteria byLocale = new WeblogEntrySearchCriteria();
            byLocale.setLocale("en");
            assertEquals(List.of("embfilter"), anchors(byLocale));
            byLocale.setLocale("fr");
            assertTrue(anchors(byLocale).isEmpty());

            WeblogEntrySearchCriteria byText = new WeblogEntrySearchCriteria();
            byText.setText("blah blah");
            assertEquals(List.of("embfilter"), anchors(byText), "matches the entry body");
            byText.setText("not in any entry");
            assertTrue(anchors(byText).isEmpty());

            WeblogEntrySearchCriteria window = new WeblogEntrySearchCriteria();
            window.setStartDate(new Date(System.currentTimeMillis() - 3_600_000L));
            window.setEndDate(new Date(System.currentTimeMillis() + 3_600_000L));
            assertEquals(List.of("embfilter"), anchors(window));
            window.setStartDate(new Date(System.currentTimeMillis() + 3_600_000L));
            assertTrue(anchors(window).isEmpty(), "published before the window opens");

            WeblogEntrySearchCriteria byCategory = new WeblogEntrySearchCriteria();
            byCategory.setCatName(entries.getWeblogEntry(e.getId()).getCategory().getName());
            assertEquals(List.of("embfilter"), anchors(byCategory));
            byCategory.setCatName("NoSuchCategory");
            assertTrue(anchors(byCategory).isEmpty(), "a category that does not exist matches nothing, not everything");

            WeblogEntrySearchCriteria sitewide = new WeblogEntrySearchCriteria();
            assertTrue(entries.getWeblogEntries(sitewide).stream().anyMatch(x -> "embfilter".equals(x.getAnchor())),
                    "with no weblog the search covers every visible weblog");
        } finally {
            TestUtils.teardownWeblogEntry(e.getId());
            TestUtils.endSession(true);
        }
    }

    @Test
    void aCategoryIsInUseOnlyWhileAnEntryIsFiledUnderIt() throws Exception {
        WeblogCategory empty = TestUtils.setupWeblogCategory(TestUtils.getManagedWebsite(weblog), "Embempty");
        TestUtils.endSession(true);
        WeblogEntry e = TestUtils.setupWeblogEntry("embfiled", entries.getWeblogCategory(empty.getId()),
                PubStatus.PUBLISHED, weblog, user);
        TestUtils.endSession(true);
        try {
            assertTrue(entries.isWeblogCategoryInUse(entries.getWeblogCategory(empty.getId())));
            TestUtils.teardownWeblogEntry(e.getId());
            TestUtils.endSession(true);
            assertFalse(entries.isWeblogCategoryInUse(entries.getWeblogCategory(empty.getId())));
        } finally {
            TestUtils.teardownWeblogCategory(empty.getId());
            TestUtils.endSession(true);
        }
    }

    @Test
    void tagsCanBeListedByNameOrByCountForOneWeblogOrTheSiteAndFilteredByPrefix() throws Exception {
        WeblogCategory cat = TestUtils.getManagedWebsite(weblog).getWeblogCategories().iterator().next();
        WeblogEntry first = TestUtils.setupWeblogEntry("embtagsa", cat, PubStatus.DRAFT, weblog, user);
        WeblogEntry second = TestUtils.setupWeblogEntry("embtagsb", cat, PubStatus.DRAFT, weblog, user);
        for (WeblogEntry e : List.of(first, second)) {
            WeblogEntry managed = entries.getWeblogEntry(e.getId());
            managed.addTag("embzzz");
            managed.setStatus(PubStatus.PUBLISHED);
            entries.saveWeblogEntry(managed);
        }
        WeblogEntry managedSecond = entries.getWeblogEntry(second.getId());
        managedSecond.addTag("embaaa");
        entries.saveWeblogEntry(managedSecond);
        TestUtils.endSession(true);
        try {
            Weblog w = TestUtils.getManagedWebsite(weblog);
            assertEquals(List.of("embaaa", "embzzz"),
                    entries.getTags(w, null, "emb", 0, -1).stream().map(t -> t.getName()).toList(), "by name");
            assertEquals(List.of("embzzz", "embaaa"),
                    entries.getTags(w, "count", "emb", 0, -1).stream().map(t -> t.getName()).toList(),
                    "most used first");
            assertEquals(1, entries.getTags(w, "count", "emb", 0, 1).size(), "limit applies");
            assertTrue(entries.getTags(w, null, "nomatch", 0, -1).isEmpty());
            assertTrue(entries.getTags(null, null, "", 0, -1).stream().anyMatch(t -> "embzzz".equals(t.getName())),
                    "no weblog and no prefix means the whole site");
        } finally {
            TestUtils.teardownWeblogEntry(first.getId());
            TestUtils.teardownWeblogEntry(second.getId());
            TestUtils.endSession(true);
        }
    }

    @Test
    void anEntryAttributeCanBeRemovedWithoutTouchingTheOthers() throws Exception {
        WeblogCategory cat = TestUtils.getManagedWebsite(weblog).getWeblogCategories().iterator().next();
        WeblogEntry e = TestUtils.setupWeblogEntry("embattrs", cat, PubStatus.PUBLISHED, weblog, user);
        WeblogEntry managed = entries.getWeblogEntry(e.getId());
        managed.putEntryAttribute("keep", "1");
        managed.putEntryAttribute("drop", "2");
        entries.saveWeblogEntry(managed);
        TestUtils.endSession(true);
        try {
            WeblogEntry again = entries.getWeblogEntry(e.getId());
            entries.removeWeblogEntryAttribute("drop", again);
            entries.removeWeblogEntryAttribute("never-there", again);
            TestUtils.endSession(true);

            WeblogEntry reloaded = entries.getWeblogEntry(e.getId());
            assertEquals("1", reloaded.findEntryAttribute("keep"));
            assertNull(reloaded.findEntryAttribute("drop"));
        } finally {
            TestUtils.teardownWeblogEntry(e.getId());
            TestUtils.endSession(true);
        }
    }

    @Test
    void pinnedEntriesAreListedNewestFirstAndCapped() throws Exception {
        WeblogEntry older = entryWithTimes("embpinold", 90);
        WeblogEntry newer = entryWithTimes("embpinnew", 10);
        WeblogEntry unpinned = entryWithTimes("embunpinned", 5);
        for (WeblogEntry e : List.of(older, newer)) {
            WeblogEntry managed = entries.getWeblogEntry(e.getId());
            managed.setPinnedToMain(Boolean.TRUE);
            entries.saveWeblogEntry(managed);
        }
        TestUtils.endSession(true);
        try {
            List<String> pinned = entries.getWeblogEntriesPinnedToMain(null).stream().map(WeblogEntry::getAnchor).toList();
            assertTrue(pinned.indexOf("embpinnew") >= 0 && pinned.indexOf("embpinnew") < pinned.indexOf("embpinold"));
            assertFalse(pinned.contains("embunpinned"));
            assertEquals(1, entries.getWeblogEntriesPinnedToMain(1).size());
        } finally {
            for (WeblogEntry e : List.of(older, newer, unpinned)) {
                TestUtils.teardownWeblogEntry(e.getId());
            }
            TestUtils.endSession(true);
        }
    }
}
