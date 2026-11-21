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

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.ui.controllers.pagers.EntriesPager;
import com.wikantik.pressroom.ui.controllers.util.KeyValueObject;
import com.wikantik.pressroom.util.URLUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.Model;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.analytics.AnalyticsStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterisation tests (Task 5 of spec 2026-11-20-jsp-rules-and-view-models-design):
 * written against the controller as it stood BEFORE the entry list moved into
 * {@code EntryListQuery} and {@code EntryListView}, and passing on that code.
 * They pin every model attribute {@code entries.rol} sets (name and value) and
 * the search criteria it builds, for each filter, status and paging case, so
 * the move is shown not to change either. The views column's four analytics
 * states are pinned by {@code EntriesViewsColumnTest}; the failed-lookup
 * state is added here.
 */
class EntriesModelCharacterisationTest extends EditorControllerTestSupport {

    private static final int COUNT = 30;
    private static final List<String> LIST_ATTRIBUTES = List.of("pager", "entryTitles", "entryViews",
            "viewsColumn", "firstEntry", "lastEntry", "filtered", "categories", "sortByOptions",
            "statusOptions", "statusChipUrls");

    private EntriesController controller;
    private Model model;
    private EntriesBean bean;
    private List<WeblogEntry> found;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new EntriesController());
        model = newModel();
        bean = new EntriesBean();
        found = new ArrayList<>();
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenAnswer(inv -> found);
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog)).thenReturn(new ArrayList<>());
        when(weblogger.getUrlStrategy().getActionURL(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> params = new LinkedHashMap<>((Map<String, String>) inv.getArgument(3));
                    params.put("weblog", inv.getArgument(2));
                    return "/pressroom-ui/authoring/entries.rol" + URLUtilities.getQueryString(params);
                });
    }

    private void reset() {
        model = newModel();
        bean = new EntriesBean();
        found.clear();
    }

    private void entries(int n) {
        for (int i = 0; i < n; i++) {
            WeblogEntry e = new WeblogEntry();
            e.setId("e" + i);
            e.setTitle("Title " + i);
            found.add(e);
        }
    }

    private WeblogEntrySearchCriteria run() throws Exception {
        controller.execute(request, model, bean);
        ArgumentCaptor<WeblogEntrySearchCriteria> captor = ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(weblogger.getWeblogEntryManager(), org.mockito.Mockito.atLeastOnce()).getWeblogEntries(captor.capture());
        return captor.getValue();
    }

    private EntriesPager pager() {
        return (EntriesPager) model.getAttribute("pager");
    }

    private static String keys(Object options) {
        return ((List<?>) options).stream().map(o -> String.valueOf(((KeyValueObject) o).getKey()))
                .collect(Collectors.joining(","));
    }

    @Test
    void noFilterSetsEveryAttributeAndAsksForOneRowMoreThanAPage() throws Exception {
        entries(2);

        WeblogEntrySearchCriteria wesc = run();

        assertSame(weblog, wesc.getWeblog());
        assertNull(wesc.getStatus());
        assertNull(wesc.getCatName());
        assertEquals(List.of(), wesc.getTags());
        assertNull(wesc.getText());
        assertNull(wesc.getStartDate());
        assertNull(wesc.getEndDate());
        assertNull(wesc.getLocale());
        assertEquals(WeblogEntrySearchCriteria.SortBy.UPDATE_TIME, wesc.getSortBy());
        assertEquals(0, wesc.getOffset());
        assertEquals(COUNT + 1, wesc.getMaxResults());
        assertFalse(wesc.isIncludeTrashed());

        for (String name : List.of("pager", "entryTitles", "viewsColumn", "firstEntry", "lastEntry",
                "filtered", "categories", "sortByOptions", "statusOptions", "statusChipUrls")) {
            assertTrue(model.containsAttribute(name), name);
        }
        assertFalse(model.containsAttribute("entryViews"), "no views map while analytics is unconfigured");
        assertFalse(model.containsAttribute("errors"));
        assertEquals(Boolean.FALSE, model.getAttribute("viewsColumn"));
        assertEquals(Boolean.FALSE, model.getAttribute("filtered"));
        assertSame(found.get(0), model.getAttribute("firstEntry"));
        assertSame(found.get(1), model.getAttribute("lastEntry"));
        assertEquals(Map.of("e0", "Title 0", "e1", "Title 1"), model.getAttribute("entryTitles"));
        assertEquals("PUBLICATION_TIME,UPDATE_TIME", keys(model.getAttribute("sortByOptions")));
        assertEquals("ALL,DRAFT,PUBLISHED,PENDING,SCHEDULED", keys(model.getAttribute("statusOptions")));
        assertEquals(found, pager().getItems());
        assertFalse(pager().isMoreItems());
        assertNull(pager().getNextLink());
        assertNull(pager().getPrevLink());
    }

    @Test
    void theStatusChipsCarryTheSortAndSwapOnlyTheStatus() throws Exception {
        bean.setStatus("DRAFT");
        bean.setText("rome");

        run();

        @SuppressWarnings("unchecked")
        Map<String, String> chips = (Map<String, String>) model.getAttribute("statusChipUrls");
        assertEquals(List.of("ALL", "DRAFT", "PUBLISHED", "PENDING", "SCHEDULED"), List.copyOf(chips.keySet()));
        for (Map.Entry<String, String> chip : chips.entrySet()) {
            assertTrue(chip.getValue().contains("bean.text=rome"), chip.getValue());
            assertTrue(chip.getValue().contains("bean.sortBy=UPDATE_TIME"), chip.getValue());
            assertTrue(chip.getValue().contains("weblog=testblog"), chip.getValue());
            assertEquals("ALL".equals(chip.getKey()), !chip.getValue().contains("bean.status="), chip.getValue());
            if (!"ALL".equals(chip.getKey())) {
                assertTrue(chip.getValue().contains("bean.status=" + chip.getKey()), chip.getValue());
            }
        }
    }

    @Test
    void eachStatusReachesTheCriteriaAndCountsAsAFilter() throws Exception {
        for (PubStatus status : List.of(PubStatus.DRAFT, PubStatus.PUBLISHED, PubStatus.PENDING,
                PubStatus.SCHEDULED, PubStatus.TRASHED)) {
            reset();
            bean.setStatus(status.name());

            WeblogEntrySearchCriteria wesc = run();

            assertEquals(status, wesc.getStatus(), status.name());
            assertEquals(Boolean.TRUE, model.getAttribute("filtered"), status.name());
        }
    }

    @Test
    void allAndBlankStatusAreNotFilters() throws Exception {
        bean.setStatus("ALL");
        run();
        assertEquals(Boolean.FALSE, model.getAttribute("filtered"));
    }

    @Test
    void aCategoryTextTagsAndDatesReachTheCriteriaAndCountAsAFilter() throws Exception {
        bean.setCategoryName("Travel");
        bean.setText("cinque terre");
        bean.setTagsAsString("liguria italy");
        bean.setStartDateString("2026-01-01");
        bean.setEndDateString("2026-12-31");
        bean.setSortBy(WeblogEntrySearchCriteria.SortBy.PUBLICATION_TIME);

        WeblogEntrySearchCriteria wesc = run();

        assertEquals("Travel", wesc.getCatName());
        assertEquals("cinque terre", wesc.getText());
        assertEquals(List.of("liguria", "italy"), wesc.getTags());
        assertEquals(bean.getStartDate(), wesc.getStartDate());
        assertEquals(bean.getEndDate(), wesc.getEndDate());
        assertTrue(wesc.getEndDate() instanceof Date);
        assertEquals(WeblogEntrySearchCriteria.SortBy.PUBLICATION_TIME, wesc.getSortBy());
        assertEquals(Boolean.TRUE, model.getAttribute("filtered"));
    }

    @Test
    void eachFilterAloneCountsAsAFilterAndTheSortDoesNot() throws Exception {
        List<java.util.function.Consumer<EntriesBean>> setters = List.of(
                b -> b.setCategoryName("Travel"), b -> b.setText("x"), b -> b.setTagsAsString("t"),
                b -> b.setStartDateString("2026-01-01"), b -> b.setEndDateString("2026-01-02"));
        for (java.util.function.Consumer<EntriesBean> set : setters) {
            reset();
            set.accept(bean);
            run();
            assertEquals(Boolean.TRUE, model.getAttribute("filtered"));
        }
        reset();
        bean.setSortBy(WeblogEntrySearchCriteria.SortBy.PUBLICATION_TIME);
        run();
        assertEquals(Boolean.FALSE, model.getAttribute("filtered"));
    }

    @Test
    void aPagePastTheFirstSkipsWholePagesAndLinksBothWays() throws Exception {
        bean.setPage(2);
        entries(COUNT + 1);

        WeblogEntrySearchCriteria wesc = run();

        assertEquals(2 * COUNT, wesc.getOffset());
        assertEquals(COUNT + 1, wesc.getMaxResults());
        assertTrue(pager().getNextLink().contains("bean.page=3"), pager().getNextLink());
        assertTrue(pager().getPrevLink().contains("bean.page=1"), pager().getPrevLink());
    }

    /** A negative bean.page is page 0 (Jake, 2026-11-21); it used to reach setFirstResult as a negative offset and answer a 500. */
    @Test
    void aNegativePageIsPageZeroAndLinksNoPreviousPage() throws Exception {
        bean.setPage(-1);
        entries(COUNT + 1);

        WeblogEntrySearchCriteria wesc = run();

        assertEquals(0, wesc.getOffset());
        assertEquals(COUNT + 1, wesc.getMaxResults());
        assertTrue(pager().getNextLink().contains("bean.page=1"), pager().getNextLink());
        assertNull(pager().getPrevLink());
    }

    @Test
    void exactlyOnePageOfEntriesHasNoMore() throws Exception {
        entries(COUNT);

        run();

        assertEquals(COUNT, pager().getItems().size());
        assertFalse(pager().isMoreItems());
        assertSame(found.get(COUNT - 1), model.getAttribute("lastEntry"));
    }

    @Test
    void onePageAndOneMoreTrimsTheLookaheadAndHasMore() throws Exception {
        entries(COUNT + 1);

        run();

        assertEquals(COUNT, pager().getItems().size());
        assertTrue(pager().isMoreItems());
        assertSame(found.get(COUNT - 1), model.getAttribute("lastEntry"));
        assertEquals(COUNT, ((Map<?, ?>) model.getAttribute("entryTitles")).size());
        assertTrue(pager().getNextLink().contains("bean.page=1"), pager().getNextLink());
    }

    @Test
    void noEntriesLeavesFirstAndLastOffAndAnEmptyTitleMap() throws Exception {
        run();

        assertFalse(model.containsAttribute("firstEntry"));
        assertFalse(model.containsAttribute("lastEntry"));
        assertEquals(Map.of(), model.getAttribute("entryTitles"));
        assertNotNull(pager().getItems());
    }

    @Test
    void whenAnalyticsIsConfiguredAnEmptyListStillGetsAnEmptyViewsMap() throws Exception {
        when(weblogger.analyticsManager().getStatus())
                .thenReturn(new AnalyticsStatus(true, true, 1, 1, List.of(), null, null));
        when(weblogger.analyticsManager().getBlogTraffic("weblog-1", "30d")).thenReturn(
                new com.wikantik.pressroom.business.analytics.BlogTraffic(
                        com.wikantik.pressroom.business.analytics.TrafficState.NO_DATA, null, null, null, null, null,
                        null, null));

        run();

        assertEquals(Boolean.TRUE, model.getAttribute("viewsColumn"));
        assertEquals(Map.of(), model.getAttribute("entryViews"));
    }

    @Test
    void aFailedLookupKeepsTheColumnFlagButAsksAnalyticsNothing() throws Exception {
        when(weblogger.analyticsManager().getStatus())
                .thenReturn(new AnalyticsStatus(true, true, 1, 1, List.of(), null, null));
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any()))
                .thenThrow(new WebloggerException("down"));

        controller.execute(request, model, bean);

        assertEquals(Boolean.TRUE, model.getAttribute("viewsColumn"));
        assertFalse(model.containsAttribute("entryViews"));
        assertFalse(model.containsAttribute("firstEntry"));
        assertEquals(Map.of(), model.getAttribute("entryTitles"));
        assertNull(pager().getItems());
        assertEquals(List.of("generic.error.check.logs"), errors(model));
        for (String name : LIST_ATTRIBUTES) {
            if (!"entryViews".equals(name) && !"firstEntry".equals(name) && !"lastEntry".equals(name)) {
                assertTrue(model.containsAttribute(name), name);
            }
        }
    }
}
