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
import java.util.Map;

import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.AnalyticsStatus;
import com.wikantik.pressroom.business.analytics.BlogTraffic;
import com.wikantik.pressroom.business.analytics.TrafficState;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.view.EntryListView.Views;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link EntryListView}: the views the entries list reads, and the plain titles, with no servlet or controller. */
class EntryListViewTest {

    private static WeblogEntry entry(String id, String title) {
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        entry.setTitle(title);
        return entry;
    }

    @Test
    void anUntitledEntryShowsItsBodyAsText() {
        WeblogEntry untitled = new WeblogEntry();
        untitled.setId("u");
        untitled.setText("Hello <b>there</b>");

        assertEquals("Hello there", EntryListView.plainTitle(untitled).replaceAll("\\s+", " "));
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

    /** Each entry's plain title by id, as the reader account page and the page editor label entries; empty for none. */
    @Test
    void plainTitlesAreKeyedByEntryIdAndTolerateNull() {
        WeblogEntry a = new WeblogEntry();
        a.setId("a");
        a.setTitle("Tom &amp; Jerry");
        WeblogEntry b = new WeblogEntry();
        b.setId("b");
        b.setTitle("Plain");

        assertEquals(java.util.Map.of("a", "Tom & Jerry", "b", "Plain"), EntryListView.plainTitles(java.util.List.of(a, b)));
        assertEquals(java.util.Map.of(), EntryListView.plainTitles(null));
    }
}
