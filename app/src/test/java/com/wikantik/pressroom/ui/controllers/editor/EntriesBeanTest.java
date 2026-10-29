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
package com.wikantik.pressroom.ui.controllers.editor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EntriesBean}, the search/filter bean behind the Entries
 * list page. It is a pure translation layer from form fields to query
 * criteria, with no business tier behind it -- which makes it cheap to test
 * exhaustively and easy to get subtly wrong. A mis-mapped filter silently
 * returns the wrong rows rather than failing, so every branch of the mapping
 * is pinned here.
 *
 * <p>Task B4 replaced the sidebar's jQuery UI datepickers with native
 * {@code <input type="date">} elements, which submit {@code yyyy-MM-dd}
 * (ISO-8601) rather than the old {@code MM/dd/yy}. {@link EntriesBean} must
 * keep accepting the legacy format too, since a bookmarked search URL from
 * before this change still carries it.
 */
class EntriesBeanTest {

    @Test
    void isoDatesFromANativeDateInputParse() {
        EntriesBean bean = new EntriesBean();
        bean.setStartDateString("2026-09-01");
        bean.setEndDateString("2026-09-30");

        assertEquals(LocalDate.of(2026, 9, 1), toLocalDate(bean.getStartDate()));
        assertEquals(LocalDate.of(2026, 9, 30), toLocalDate(bean.getEndDate()));
    }

    /**
     * {@code getEndDate()} used to return midnight at the START of the typed
     * day, the same as {@code getStartDate()} -- but the search criteria
     * filters {@code pubTime <= endDate}, so an end-date of "2026-09-30"
     * excluded every entry published that day except one landing in the
     * first millisecond, which is not what a reader who typed an end date of
     * "the 30th" means. The bound must be the last instant of that day.
     */
    @Test
    void isoEndDateIncludesTheWholeDayNotJustItsFirstMillisecond() {
        EntriesBean bean = new EntriesBean();
        bean.setEndDateString("2026-09-30");

        java.time.LocalDateTime endOfDay = bean.getEndDate().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDateTime();

        assertEquals(LocalDate.of(2026, 9, 30), endOfDay.toLocalDate(),
                "the end date's own day must not shift");
        assertEquals(LocalTime.of(23, 59, 59, 999_000_000), endOfDay.toLocalTime(),
                "an end-date filter must reach the last instant of the day, not its first");
    }

    /**
     * Same fix, the legacy bookmarked-URL date format -- it is just as much a
     * date-only value as the ISO one above.
     */
    @Test
    void legacySlashEndDateIncludesTheWholeDayNotJustItsFirstMillisecond() {
        EntriesBean bean = new EntriesBean();
        bean.setEndDateString("09/30/26");

        java.time.LocalDateTime endOfDay = bean.getEndDate().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDateTime();

        assertEquals(LocalDate.of(2026, 9, 30), endOfDay.toLocalDate());
        assertEquals(LocalTime.of(23, 59, 59, 999_000_000), endOfDay.toLocalTime());
    }

    /**
     * The start-date bound is unaffected by this fix -- it must stay at the
     * start of the day, or a start date of "2026-09-01" would exclude entries
     * published that morning.
     */
    @Test
    void startDateIsStillMidnightAtTheStartOfTheDay() {
        EntriesBean bean = new EntriesBean();
        bean.setStartDateString("2026-09-01");

        java.time.LocalDateTime startOfDay = bean.getStartDate().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDateTime();

        assertEquals(LocalDate.of(2026, 9, 1), startOfDay.toLocalDate());
        assertEquals(LocalTime.MIDNIGHT, startOfDay.toLocalTime());
    }

    @Test
    void theOldSlashFormatStillParsesForBookmarkedUrls() {
        EntriesBean bean = new EntriesBean();
        bean.setStartDateString("09/01/26");
        bean.setEndDateString("09/30/26");

        assertEquals(LocalDate.of(2026, 9, 1), toLocalDate(bean.getStartDate()));
        assertEquals(LocalDate.of(2026, 9, 30), toLocalDate(bean.getEndDate()));
    }

    @Test
    void anAbsentOrUnparseableDateBecomesNoBoundRatherThanAnError() {
        // A null bound means "unbounded" to the search criteria; throwing
        // here would break the list page on a typo.
        EntriesBean bean = new EntriesBean();
        assertNull(bean.getStartDate(), "No date typed means no lower bound");
        assertNull(bean.getEndDate(), "No date typed means no upper bound");

        bean.setStartDateString("");
        bean.setEndDateString("");
        assertNull(bean.getStartDate());
        assertNull(bean.getEndDate());

        bean.setStartDateString("garbage");
        bean.setEndDateString("garbage");
        assertNull(bean.getStartDate(), "A typo must not become a bogus bound");
        assertNull(bean.getEndDate());
    }

    @Test
    void tagsAreSplitOnWhitespace() {
        EntriesBean bean = new EntriesBean();
        bean.setTagsAsString("travel  food   ");

        assertEquals(List.of("travel", "food"), bean.getTags());
    }

    @Test
    void noTagFilterIsAnEmptyListRatherThanNull() {
        // getTags() used to return null here, forcing its one caller
        // (EntriesController, which feeds it straight into
        // WeblogEntrySearchCriteria.setTags) to carry a null check on a
        // collection. JPAWeblogEntryManagerImpl.getWeblogEntries already
        // treats "tags == null" and "tags.isEmpty()" identically -- both
        // skip the tag join entirely -- so an empty list means exactly
        // what null used to mean: no tag filter at all.
        assertEquals(List.of(), new EntriesBean().getTags());
    }

    @Test
    void theListDefaultsToEverythingSortedByMostRecentlyEdited() {
        EntriesBean bean = new EntriesBean();

        assertEquals("ALL", bean.getStatus(),
                "The management list must show drafts and pending entries by default, "
                        + "since those are what the author still has to act on");
        assertEquals(com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria.SortBy.UPDATE_TIME,
                bean.getSortBy());
        assertEquals(0, bean.getPage());
    }

    private static LocalDate toLocalDate(java.util.Date date) {
        assertTrue(date != null, "Expected a parsed date");
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
