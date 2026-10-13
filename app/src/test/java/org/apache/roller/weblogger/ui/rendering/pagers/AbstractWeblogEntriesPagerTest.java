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

package org.apache.roller.weblogger.ui.rendering.pagers;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.config.RuntimeConfigAttachment;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.wrapper.WeblogEntryWrapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AbstractWeblogEntriesPager}, the base shared by the
 * latest / month / day / permalink entry pagers.
 *
 * <p>Everything here is arithmetic that decides which slice of a weblog gets
 * rendered: how many entries fit on a page, which row the query starts at, and
 * which URL each navigation control points to. An error in the offset silently
 * repeats or skips entries rather than failing, which is why the page-size
 * boundaries are pinned individually.
 */
class AbstractWeblogEntriesPagerTest {

    private static final int SITE_MAX_ENTRIES = 30;

    private final URLStrategy urlStrategy = mock(URLStrategy.class);

    /** Neither test pager queries anything, so the facade they are built with is inert. */
    private static final Weblogger NO_TIER = mock(Weblogger.class);

    /**
     * Minimal concrete pager. The base class leaves the entry collection and
     * the has-more decision to subclasses; both are injected.
     */
    private static final class FixedEntriesPager extends AbstractWeblogEntriesPager {

        private final boolean more;

        FixedEntriesPager(URLStrategy strat, Weblog weblog, String locale, String pageLink,
                String entryAnchor, String dateString, String catName, List<String> tags,
                int page, boolean more) {
            super(strat, NO_TIER, weblog, locale, pageLink, entryAnchor, dateString, catName, tags, page);
            this.more = more;
        }

        @Override
        public Map<Date, ? extends Collection<WeblogEntryWrapper>> getEntries() {
            return Map.of();
        }

        @Override
        public boolean hasMoreEntries() {
            return more;
        }

        int offset() {
            return offset;
        }

        int length() {
            return length;
        }

        int page() {
            return page;
        }
    }

    /**
     * A pager that leaves hasMoreEntries() at the base implementation, so the
     * default can be pinned rather than being shadowed by every real subclass.
     */
    private static final class DefaultEntriesPager extends AbstractWeblogEntriesPager {

        DefaultEntriesPager(URLStrategy strat, Weblog weblog) {
            super(strat, NO_TIER, weblog, "en_US", null, null, null, null, null, 0);
        }

        @Override
        public Map<Date, ? extends Collection<WeblogEntryWrapper>> getEntries() {
            return Map.of();
        }
    }

    private static Weblog weblog(int entryDisplayCount) {
        Weblog weblog = new Weblog();
        weblog.setHandle("myblog");
        weblog.setLocale("en_US");
        weblog.setTimeZone("UTC");
        weblog.setEntryDisplayCount(entryDisplayCount);
        return weblog;
    }

    /**
     * Runs the body with the runtime config answering site.pages.maxEntries.
     * The pager reads it through WebloggerRuntimeConfig, which reads the
     * properties manager attached to it (plan Task 19), so that is what is
     * attached here -- and nothing else.
     */
    private static void withSiteMaxEntries(int maxEntries, Runnable body) {
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.answering(
                "site.pages.maxEntries", String.valueOf(maxEntries))) {
            body.run();
        }
    }

    private FixedEntriesPager pager(Weblog weblog, int page, boolean more) {
        return new FixedEntriesPager(urlStrategy, weblog, "en_US", null, null, null, null,
                null, page, more);
    }

    // ------------------------------------------------------- page arithmetic

    @Test
    void pageSizeIsTheWeblogSettingWhenItIsUnderTheSiteCeiling() {
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = pager(weblog(15), 0, false);

            assertEquals(15, pager.length(),
                    "A weblog asking for fewer entries than the site allows gets what it asked for");
        });
    }

    @Test
    void pageSizeIsCappedAtTheSiteCeiling() {
        // The ceiling is what stops one weblog rendering thousands of entries
        // per request, so the weblog's own setting must lose when it is larger.
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = pager(weblog(500), 0, false);

            assertEquals(SITE_MAX_ENTRIES, pager.length(),
                    "A weblog must not be able to exceed site.pages.maxEntries");
        });
    }

    @Test
    void pageSizeAtExactlyTheCeilingIsAllowedThrough() {
        // Equal values sit on the Math.min boundary.
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = pager(weblog(SITE_MAX_ENTRIES), 0, false);

            assertEquals(SITE_MAX_ENTRIES, pager.length(),
                    "A weblog set to exactly the ceiling must get the full ceiling");
        });
    }

    @Test
    void offsetIsPageTimesPageSize() {
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            assertEquals(0, pager(weblog(10), 0, false).offset(),
                    "Page 0 starts at row 0");
            assertEquals(10, pager(weblog(10), 1, false).offset(),
                    "Page 1 of 10-entry pages starts at row 10, not 9 or 11");
            assertEquals(30, pager(weblog(10), 3, false).offset(),
                    "Page 3 of 10-entry pages starts at row 30");
        });
    }

    @Test
    void negativePageNumbersAreClampedToTheFirstPage() {
        // A negative offset would be rejected or misinterpreted by the query.
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = pager(weblog(10), -4, false);

            assertEquals(0, pager.page(), "A negative page index must clamp to 0");
            assertEquals(0, pager.offset(),
                    "A clamped page must start at row 0, never at a negative offset");
        });
    }

    // ------------------------------------------------------ link boundaries

    @Test
    void pagersThatDoNotDecideHaveNoFurtherEntries() {
        // The base default is deliberately false: a pager that has not worked
        // out whether more entries exist must not advertise a page that may not
        // be there.
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            DefaultEntriesPager pager = new DefaultEntriesPager(urlStrategy, weblog(10));

            assertFalse(pager.hasMoreEntries(),
                    "AbstractWeblogEntriesPager.hasMoreEntries() must default to false");
            assertNull(pager.getNextLink(),
                    "With no further entries there must be no next link");
            assertNull(pager.getNextName(),
                    "With no further entries there must be no next label");
        });
    }

    @Test
    void firstPageOffersNoPreviousLinkOrLabel() {
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = pager(weblog(10), 0, true);

            assertNull(pager.getPrevLink(), "Page 0 has nothing before it");
            assertNull(pager.getPrevName(),
                    "The label must disappear with the link, or the template renders "
                            + "a caption with no href");
        });
    }

    @Test
    void pageOneIsTheFirstPageWithAPreviousLink() {
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            when(urlStrategy.getWeblogCollectionURL(any(), any(), any(), any(), any(),
                    anyInt(), anyBoolean())).thenReturn("http://localhost/roller/myblog/");

            assertNull(pager(weblog(10), 0, true).getPrevLink());
            assertEquals("http://localhost/roller/myblog/",
                    pager(weblog(10), 1, true).getPrevLink(),
                    "Page 1 must be able to get back to page 0, with the URL the "
                            + "strategy produced rather than an empty string");
        });
    }

    @Test
    void lastPageOffersNoNextLinkOrLabel() {
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = pager(weblog(10), 2, false);

            assertNull(pager.getNextLink(),
                    "With no further entries the next link must be absent, not point "
                            + "at an empty page 3");
            assertNull(pager.getNextName());
        });
    }

    @Test
    void navigationLinksStepExactlyOnePageInEachDirection() {
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            when(urlStrategy.getWeblogCollectionURL(any(), any(), any(), any(), any(),
                    anyInt(), anyBoolean())).thenReturn("http://localhost/roller/myblog/");

            FixedEntriesPager pager = pager(weblog(10), 5, true);
            pager.getNextLink();
            pager.getPrevLink();

            verify(urlStrategy).getWeblogCollectionURL(any(), eq("en_US"), any(), any(), any(),
                    eq(6), eq(false));
            verify(urlStrategy).getWeblogCollectionURL(any(), eq("en_US"), any(), any(), any(),
                    eq(4), eq(false));
        });
    }

    @Test
    void homeLinkAlwaysTargetsPageZero() {
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            when(urlStrategy.getWeblogCollectionURL(any(), any(), any(), any(), any(),
                    anyInt(), anyBoolean())).thenReturn("http://localhost/roller/myblog/");

            assertEquals("http://localhost/roller/myblog/",
                    pager(weblog(10), 5, true).getHomeLink(),
                    "Home must return the URL the strategy produced");

            verify(urlStrategy).getWeblogCollectionURL(any(), eq("en_US"), any(), any(), any(),
                    eq(0), eq(false));
        });
    }

    // -------------------------------------------------------- url dispatching

    @Test
    void customPageViewsUseThePageUrlForm() {
        // A custom page has its own URL shape; using the collection form would
        // page the reader off the custom page entirely.
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = new FixedEntriesPager(urlStrategy, weblog(10), "en_US",
                    "archive", null, null, null, null, 1, true);

            pager.getNextLink();

            verify(urlStrategy).getWeblogPageURL(any(), eq("en_US"), eq("archive"), any(),
                    any(), any(), any(), eq(2), eq(false));
        });
    }

    @Test
    void permalinkViewsUseTheEntryUrlForm() {
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            when(urlStrategy.getWeblogEntryURL(any(), any(), any(), anyBoolean()))
                    .thenReturn("http://localhost/roller/myblog/entry/hello-world");
            FixedEntriesPager pager = new FixedEntriesPager(urlStrategy, weblog(10), "en_US",
                    null, "hello-world", null, null, null, 0, true);

            assertEquals("http://localhost/roller/myblog/entry/hello-world", pager.getNextLink(),
                    "The permalink form must return the strategy's URL, not an empty string");

            verify(urlStrategy).getWeblogEntryURL(any(), eq("en_US"), eq("hello-world"),
                    eq(true));
        });
    }

    @Test
    void collectionViewsCarryTheCategoryDateAndTagFilters() {
        // Losing any of these while paging would widen the collection, so page 2
        // would contain entries page 1 could not have shown.
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = new FixedEntriesPager(urlStrategy, weblog(10), "en_US",
                    null, null, "20240115", "Java", List.of("testing"), 1, true);

            pager.getNextLink();

            verify(urlStrategy).getWeblogCollectionURL(any(), eq("en_US"), eq("Java"),
                    eq("20240115"), eq(List.of("testing")), eq(2), eq(false));
        });
    }

    @Test
    void nullTagsBecomeAnEmptyListRatherThanTravellingAsNull() {
        // The URL strategy and the entry query both iterate this list.
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = pager(weblog(10), 0, true);

            pager.getNextLink();

            verify(urlStrategy).getWeblogCollectionURL(any(), any(), any(), any(),
                    eq(List.of()), anyInt(), anyBoolean());
        });
    }

    // ------------------------------------------------------------ date parsing

    @Test
    void eightCharacterDateStringsParseToThatDay() {
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = pager(weblog(10), 0, false);

            Date parsed = pager.parseDate("20200115");

            assertNotNull(parsed, "A well-formed 8-character date must parse");
            assertEquals("20200115", format(parsed, "yyyyMMdd"),
                    "The parsed day must be the one named in the URL");
        });
    }

    @Test
    void sixCharacterDateStringsParseToThatMonth() {
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = pager(weblog(10), 0, false);

            Date parsed = pager.parseDate("202001");

            assertNotNull(parsed, "A well-formed 6-character date must parse");
            assertEquals("202001", format(parsed, "yyyyMM"));
        });
    }

    @Test
    void dateStringsOfAnyOtherLengthDoNotParse() {
        // 5, 7 and 9 characters bracket the two accepted lengths. Returning a
        // date for a truncated string would render a different archive page
        // from the one the URL asked for.
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = pager(weblog(10), 0, false);

            assertNull(pager.parseDate("20200"), "5 characters is neither form");
            assertNull(pager.parseDate("2020011"), "7 characters is neither form");
            assertNull(pager.parseDate("202001150"), "9 characters is neither form");
            assertNull(pager.parseDate(null), "null must not blow up");
        });
    }

    @Test
    void nonNumericDateStringsDoNotParse() {
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = pager(weblog(10), 0, false);

            assertNull(pager.parseDate("2020jan"), "letters are not a date");
            assertNull(pager.parseDate("2020-01-15"), "punctuation is not a date");
        });
    }

    @Test
    void futureDatesAreClampedToToday() {
        // An archive URL for next year has no entries and, worse, would let a
        // crawler walk forward forever. Clamping keeps the window bounded.
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = pager(weblog(10), 0, false);

            Date parsed = pager.parseDate("29991231");

            assertNotNull(parsed);
            assertTrue(!parsed.after(pager.getToday()),
                    "A date in the future must be pulled back to today; got " + parsed);
        });
    }

    @Test
    void pastDatesAreNotClamped() {
        // Guards the guard above: clamping everything would collapse the whole
        // archive onto today.
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = pager(weblog(10), 0, false);

            Date parsed = pager.parseDate("20200115");

            assertTrue(parsed.before(pager.getToday()),
                    "A date in the past must be left alone");
        });
    }

    // ----------------------------------------------------------------- labels

    @Test
    void labelsResolveForALanguageOnlyLocale() {
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = new FixedEntriesPager(urlStrategy, weblog(10), "fr",
                    null, null, null, null, null, 1, true);

            assertRenderedLabel(pager.getHomeName(), "weblogEntriesPager.latest.home",
                    "The home control under a language-only locale");
            assertRenderedLabel(pager.getPrevName(), "weblogEntriesPager.latest.prev",
                    "The previous control under a language-only locale");
            assertRenderedLabel(pager.getNextName(), "weblogEntriesPager.latest.next",
                    "The next control under a language-only locale");
        });
    }

    @Test
    void labelsFallBackToTheWeblogLocaleWhenTheUrlHadNone() {
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = new FixedEntriesPager(urlStrategy, weblog(10), null,
                    null, null, null, null, null, 1, true);

            assertRenderedLabel(pager.getHomeName(), "weblogEntriesPager.latest.home",
                    "The home control under the weblog's own locale");
        });
    }

    /**
     * A locale with a variant component (e.g. "en_US_POSIX") used to leave
     * viewLocale null all the way through the constructor -- neither the
     * one- nor two-part branch matched -- which reached
     * I18nMessages.getMessages(Locale), itself unconditionally dereferencing
     * its parameter (NP_NULL_PARAM_DEREF).
     */
    @Test
    void labelsResolveForAThreePartLocale() {
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = new FixedEntriesPager(urlStrategy, weblog(10),
                    "en_US_POSIX", null, null, null, null, null, 1, true);

            assertRenderedLabel(pager.getHomeName(), "weblogEntriesPager.latest.home",
                    "The home control under a three-part locale");
        });
    }

    @Test
    void flatEntryPagersHaveNoAdjacentCollections() {
        // Only the month and day pagers have a neighbouring collection; the
        // base deliberately reports none.
        withSiteMaxEntries(SITE_MAX_ENTRIES, () -> {
            FixedEntriesPager pager = pager(weblog(10), 1, true);

            assertNull(pager.getNextCollectionLink());
            assertNull(pager.getNextCollectionName());
            assertNull(pager.getPrevCollectionLink());
            assertNull(pager.getPrevCollectionName());
        });
    }

    /**
     * A label must be real user-facing text. I18nMessages returns the lookup
     * key itself when the bundle entry is missing, so a non-null check alone
     * would pass while the page renders "weblogEntriesPager.latest.home".
     */
    private static void assertRenderedLabel(String label, String messageKey, String what) {
        assertNotNull(label, what + " must have a label");
        assertTrue(!label.isBlank(), what + " must not render as an empty caption");
        org.junit.jupiter.api.Assertions.assertNotEquals(messageKey, label,
                what + " fell back to the raw message key, so " + messageKey
                        + " is missing from ApplicationResources");
    }

    private static String format(Date date, String pattern) {
        java.text.SimpleDateFormat formatter = new java.text.SimpleDateFormat(pattern);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return formatter.format(date);
    }
}
