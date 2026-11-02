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
package com.wikantik.pressroom.ui.controllers.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.AnalyticsStatus;
import com.wikantik.pressroom.business.analytics.BlogTraffic;
import com.wikantik.pressroom.business.analytics.TrafficState;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The entries list's "Views (30d)" column: the controller's cell texts and
 * the markup around them. The numbers come from the local snapshot only.
 */
class EntriesViewsColumnTest extends EditorControllerTestSupport {

    private static final Path JSP = Path.of("src/main/webapp/WEB-INF/jsps/editor/Entries.jsp");
    private static final String DASH = "—";

    private EntriesController controller;
    private Model model;
    private AnalyticsManager analytics;
    private List<WeblogEntry> entries;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new EntriesController());
        model = newModel();
        analytics = weblogger.analyticsManager();
        entries = new ArrayList<>();
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenAnswer(inv -> entries);
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog)).thenReturn(new ArrayList<>());
    }

    private void listing(String... ids) {
        for (String id : ids) {
            WeblogEntry e = new WeblogEntry();
            e.setId(id);
            entries.add(e);
        }
        controller.execute(request, model, new EntriesBean());
    }

    private static String read() throws IOException {
        return Files.readString(JSP, StandardCharsets.UTF_8).replaceAll("(?s)<%--.*?--%>", "");
    }

    private static AnalyticsStatus configured() {
        return new AnalyticsStatus(true, true, 1, 1, List.of(), null, null);
    }

    @Test
    void entriesListShowsViews() {
        when(analytics.getStatus()).thenReturn(configured());
        when(analytics.entryViews("weblog-1", "30d")).thenReturn(Map.of("e1", 1234L));

        listing("e1", "e2");

        assertEquals(Boolean.TRUE, model.getAttribute("viewsColumn"));
        assertEquals(Map.of("e1", "1,234", "e2", "0"), model.getAttribute("entryViews"),
                "no recorded views is a real zero once a snapshot exists");
        verify(analytics, times(1)).entryViews("weblog-1", "30d");
    }

    @Test
    void anEntryWithNoViewsBeforeAnySnapshotShowsADash() {
        when(analytics.getStatus()).thenReturn(configured());
        when(analytics.entryViews("weblog-1", "30d")).thenReturn(Map.of());
        when(analytics.getBlogTraffic("weblog-1", "30d")).thenReturn(
                new BlogTraffic(TrafficState.NO_DATA, null, null, null, null, null, null, null));

        listing("e1");

        assertEquals(Map.of("e1", DASH), model.getAttribute("entryViews"));
    }

    @Test
    void anEmptyMapWithASnapshotIsZeros() {
        when(analytics.getStatus()).thenReturn(configured());
        when(analytics.entryViews("weblog-1", "30d")).thenReturn(Map.of());
        when(analytics.getBlogTraffic("weblog-1", "30d")).thenReturn(
                new BlogTraffic(TrafficState.OK, java.time.Instant.now(), null, null, null, null, null, null));

        listing("e1");

        assertEquals(Map.of("e1", "0"), model.getAttribute("entryViews"));
    }

    @Test
    void entriesListHidesColumnWhenUnconfigured() {
        when(analytics.getStatus()).thenReturn(AnalyticsStatus.notConfigured());

        listing("e1");

        assertEquals(Boolean.FALSE, model.getAttribute("viewsColumn"));
        verify(analytics, never()).entryViews(anyString(), anyString());
        verify(analytics, never()).getBlogTraffic(anyString(), anyString());
    }

    @Test
    void theColumnIsGatedOnTheFlagAndTheDeleteModalMarkerSurvives() throws IOException {
        String jsp = read();
        assertTrue(jsp.contains("<c:if test=\"${viewsColumn}\">"), "header and cell are shown only when configured");
        assertTrue(jsp.contains("code=\"entries.views30\""));
        assertTrue(jsp.contains("${fn:escapeXml(entryViews[post.id])}"));
        assertTrue(jsp.contains("id=\"delete-entry-modal\""));
        assertEquals(2, jsp.split("<c:if test=\"\\$\\{viewsColumn\\}\">", -1).length - 1, "one in the header, one per row");
    }
}
