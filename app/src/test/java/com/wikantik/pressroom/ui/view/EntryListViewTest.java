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

package com.wikantik.pressroom.ui.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.AnalyticsStatus;
import com.wikantik.pressroom.business.analytics.BlogTraffic;
import com.wikantik.pressroom.business.analytics.TrafficState;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria.SortBy;
import com.wikantik.pressroom.ui.controllers.EntryListQuery.EntryListPage;
import com.wikantik.pressroom.ui.controllers.util.KeyValueObject;
import com.wikantik.pressroom.ui.view.EntryListView.Filters;
import com.wikantik.pressroom.ui.view.EntryListView.Views;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link EntryListView}: rows, views cells, paging facts, filter flag and options, with no servlet or controller. */
class EntryListViewTest {

    private static final Filters NO_FILTERS = new Filters("ALL", null, null, null, null, null, SortBy.UPDATE_TIME);
    private static final Filters ANY = new Filters(null, null, null, null, null, null, null);

    private static WeblogEntry entry(String id, String title) {
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        entry.setTitle(title);
        return entry;
    }

    private static EntryListView view(List<WeblogEntry> items, boolean hasMore, Views views, Filters filters) {
        return EntryListView.of(new EntryListPage(items, 0, 30, hasMore), 2, views, Locale.US, List.of(), key -> key,
                filters);
    }

    private static String keys(List<KeyValueObject> options) {
        return options.stream().map(o -> String.valueOf(o.getKey())).collect(Collectors.joining(","));
    }

    @Test
    void rowsCarryThePlainTitleAndTheFirstAndLastEntry() {
        WeblogEntry a = entry("a", "Tom &amp; Jerry");
        WeblogEntry b = entry("b", "Second");

        EntryListView view = view(List.of(a, b), true, Views.none(), NO_FILTERS);

        assertEquals("Tom & Jerry", view.rows().get(0).title());
        assertSame(a, view.firstEntry());
        assertSame(b, view.lastEntry());
        assertEquals(Map.of("a", "Tom & Jerry", "b", "Second"), view.titlesById());
        assertTrue(view.hasMore());
        assertEquals(2, view.pageNumber());
        assertFalse(view.viewsColumn());
        assertFalse(view.viewsShown());
        assertNull(view.rows().get(0).views());
    }

    @Test
    void anUntitledEntryShowsItsBodyAsText() {
        WeblogEntry untitled = new WeblogEntry();
        untitled.setId("u");
        untitled.setText("Hello <b>there</b>");

        assertEquals("Hello there", EntryListView.plainTitle(untitled).replaceAll("\\s+", " "));
    }

    @Test
    void anEmptyPageHasNoFirstOrLastAndALostLookupHasNoEntriesAtAll() {
        EntryListView empty = view(List.of(), false, Views.none(), NO_FILTERS);
        assertNull(empty.firstEntry());
        assertNull(empty.lastEntry());
        assertEquals(List.of(), empty.entries());

        EntryListView failed = EntryListView.of(null, 0, new Views(true, Map.of(), false), Locale.US, List.of(),
                key -> key, NO_FILTERS);
        assertNull(failed.entries());
        assertNull(failed.firstEntry());
        assertEquals(List.of(), failed.rows());
        assertEquals(Map.of(), failed.titlesById());
        assertFalse(failed.hasMore());
        assertTrue(failed.viewsColumn());
        assertFalse(failed.viewsShown(), "no cells when the lookup failed");
    }

    @Test
    void viewsCellsAreNumbersZerosOrDashes() {
        List<WeblogEntry> items = List.of(entry("e1", "One"), entry("e2", "Two"));

        EntryListView known = view(items, false, new Views(true, Map.of("e1", 1234L), true), NO_FILTERS);
        assertEquals(Map.of("e1", "1,234", "e2", "0"), known.viewsById());
        assertTrue(known.viewsShown());

        EntryListView unknown = view(items, false, new Views(true, Map.of("e1", 7L), false), NO_FILTERS);
        assertEquals(Map.of("e1", "7", "e2", "—"), unknown.viewsById());
    }

    @Test
    void theFilterFlagIgnoresTheSortAndTheAllStatus() {
        assertFalse(NO_FILTERS.any());
        assertFalse(new Filters("", "", "", "", "", "", SortBy.PUBLICATION_TIME).any());
        assertTrue(new Filters("DRAFT", null, null, null, null, null, null).any());
        assertTrue(new Filters(null, "c", null, null, null, null, null).any());
        assertTrue(new Filters(null, null, "t", null, null, null, null).any());
        assertTrue(new Filters(null, null, null, "x", null, null, null).any());
        assertTrue(new Filters(null, null, null, null, "2026-01-01", null, null).any());
        assertTrue(new Filters(null, null, null, null, null, "2026-01-01", null).any());
        assertTrue(view(List.of(), false, Views.none(), new Filters(null, "c", null, null, null, null, null)).filtered());
    }

    @Test
    void optionsAreKeyedByNameAndLabelledByTheCallersFunction() {
        EntryListView view = EntryListView.of(null, 0, Views.none(), Locale.US, List.of(new WeblogCategory()),
                key -> "<" + key + ">", ANY);

        assertEquals("PUBLICATION_TIME,UPDATE_TIME", keys(view.sortByOptions()));
        assertEquals("ALL,DRAFT,PUBLISHED,PENDING,SCHEDULED", keys(view.statusOptions()));
        assertEquals("<weblogEntryQuery.label.pubTime>", view.sortByOptions().get(0).getValue());
        assertEquals("<weblogEntryQuery.label.scheduledOnly>", view.statusOptions().get(4).getValue());
        assertEquals(1, view.categories().size());
        assertSame(ANY, view.filters());
    }

    @Test
    void viewsReadNothingWhenAnalyticsIsNotConfiguredOrNotNeeded() {
        AnalyticsManager analytics = mock(AnalyticsManager.class);
        when(analytics.getStatus()).thenReturn(AnalyticsStatus.notConfigured());
        assertFalse(Views.read(analytics, "w", true).configured());

        when(analytics.getStatus()).thenReturn(new AnalyticsStatus(true, true, 1, 1, List.of(), null, null));
        Views unneeded = Views.read(analytics, "w", false);
        assertTrue(unneeded.configured());
        verify(analytics, never()).entryViews(anyString(), anyString());
    }

    @Test
    void viewsReadTheSnapshotOnceAndKnowWhetherZeroIsReal() {
        AnalyticsManager analytics = mock(AnalyticsManager.class);
        when(analytics.getStatus()).thenReturn(new AnalyticsStatus(true, true, 1, 1, List.of(), null, null));
        when(analytics.entryViews("w", "30d")).thenReturn(Map.of());
        when(analytics.getBlogTraffic("w", "30d")).thenReturn(
                new BlogTraffic(TrafficState.NO_DATA, null, null, null, null, null, null, null));
        assertFalse(Views.read(analytics, "w", true).zeroIsKnown(), "no snapshot yet");

        when(analytics.getBlogTraffic("w", "30d")).thenReturn(
                new BlogTraffic(TrafficState.OK, java.time.Instant.now(), null, null, null, null, null, null));
        assertTrue(Views.read(analytics, "w", true).zeroIsKnown());

        when(analytics.pathsCappedAt("w", "30d")).thenReturn(1000);
        assertFalse(Views.read(analytics, "w", true).zeroIsKnown(), "capped paths: unknown, not zero");

        when(analytics.entryViews("w", "30d")).thenReturn(Map.of("e", 1L));
        when(analytics.pathsCappedAt("w", "30d")).thenReturn(0);
        assertTrue(Views.read(analytics, "w", true).zeroIsKnown(), "views present means a snapshot");
        assertEquals(Map.of("e", 1L), Views.read(analytics, "w", true).views());
        assertEquals(new ArrayList<>(), new ArrayList<>(Views.none().views().keySet()));
    }
}
