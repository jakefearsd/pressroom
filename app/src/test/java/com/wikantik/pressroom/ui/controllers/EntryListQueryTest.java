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

package com.wikantik.pressroom.ui.controllers;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria.SortBy;
import com.wikantik.pressroom.ui.controllers.EntryListQuery.EntryListPage;
import com.wikantik.pressroom.ui.controllers.EntryListQuery.Filters;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link EntryListQuery}: criteria, the trash rule and the fetch-one-more paging, with no servlet or controller. */
class EntryListQueryTest {

    private static final Filters NONE = new Filters(null, null, null, null, null, null, null, null);

    private final WeblogEntryManager manager = mock(WeblogEntryManager.class);
    private final Weblog weblog = new Weblog();
    private List<WeblogEntry> found = new ArrayList<>();

    private WeblogEntrySearchCriteria criteria(Filters filters, int offset, int limit) throws Exception {
        when(manager.getWeblogEntries(any())).thenAnswer(inv -> found);
        EntryListQuery.run(manager, weblog, filters, offset, limit);
        ArgumentCaptor<WeblogEntrySearchCriteria> captor = ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(manager, org.mockito.Mockito.atLeastOnce()).getWeblogEntries(captor.capture());
        return captor.getValue();
    }

    private void entries(int n) {
        for (int i = 0; i < n; i++) {
            WeblogEntry entry = new WeblogEntry();
            entry.setId("e" + i);
            found.add(entry);
        }
    }

    @Test
    void everyFilterReachesTheCriteriaAndOneExtraRowIsAsked() throws Exception {
        Date start = new Date(1);
        Date end = new Date(2);

        WeblogEntrySearchCriteria wesc = criteria(new Filters(PubStatus.DRAFT, "Travel", List.of("a"), "rome", "it",
                start, end, SortBy.PUBLICATION_TIME), 60, 30);

        assertSame(weblog, wesc.getWeblog());
        assertEquals(PubStatus.DRAFT, wesc.getStatus());
        assertEquals("Travel", wesc.getCatName());
        assertEquals(List.of("a"), wesc.getTags());
        assertEquals("rome", wesc.getText());
        assertEquals("it", wesc.getLocale());
        assertEquals(start, wesc.getStartDate());
        assertEquals(end, wesc.getEndDate());
        assertEquals(SortBy.PUBLICATION_TIME, wesc.getSortBy());
        assertEquals(60, wesc.getOffset());
        assertEquals(31, wesc.getMaxResults());
    }

    @Test
    void noSortKeepsTheCriteriaDefaultOrder() throws Exception {
        assertEquals(new WeblogEntrySearchCriteria().getSortBy(), criteria(NONE, 0, 5).getSortBy());
    }

    @Test
    void onlyAnExplicitTrashedStatusReachesTheTrash() throws Exception {
        assertTrue(criteria(new Filters(PubStatus.TRASHED, null, null, null, null, null, null, null), 0, 5)
                .isIncludeTrashed());
        assertTrue(EntryListQuery.includesTrash(PubStatus.TRASHED));
        for (PubStatus other : List.of(PubStatus.DRAFT, PubStatus.PUBLISHED, PubStatus.PENDING,
                PubStatus.SCHEDULED)) {
            assertFalse(EntryListQuery.includesTrash(other), other.name());
        }
        assertFalse(EntryListQuery.includesTrash(null));
        assertFalse(criteria(NONE, 0, 5).isIncludeTrashed());
    }

    @Test
    void oneRowOverThePageIsTrimmedAndMeansMore() throws Exception {
        entries(4);

        EntryListPage page = EntryListQuery.run(stubbed(), weblog, NONE, 8, 3);

        assertEquals(found.subList(0, 3), page.items());
        assertTrue(page.hasMore());
        assertEquals(8, page.offset());
        assertEquals(3, page.limit());
    }

    @Test
    void exactlyAPageMeansNoMoreAndNothingMeansEmpty() throws Exception {
        entries(3);
        EntryListPage exact = EntryListQuery.run(stubbed(), weblog, NONE, 0, 3);
        assertEquals(3, exact.items().size());
        assertFalse(exact.hasMore());

        found = new ArrayList<>();
        EntryListPage none = EntryListQuery.run(stubbed(), weblog, NONE, 0, 3);
        assertTrue(none.items().isEmpty());
        assertFalse(none.hasMore());
    }

    @Test
    void aFailedLookupIsNotSwallowed() throws Exception {
        when(manager.getWeblogEntries(any())).thenThrow(new WebloggerException("down"));

        assertThrows(WebloggerException.class, () -> EntryListQuery.run(manager, weblog, NONE, 0, 3));
    }

    @Test
    void aStatusTypedIntoAUrlIsLenient() {
        assertNull(EntryListQuery.lenientStatus(null));
        assertNull(EntryListQuery.lenientStatus(""));
        assertNull(EntryListQuery.lenientStatus("  "));
        assertNull(EntryListQuery.lenientStatus("ALL"));
        assertNull(EntryListQuery.lenientStatus("NOPE"));
        assertNull(EntryListQuery.lenientStatus("draft"));
        assertEquals(PubStatus.PENDING, EntryListQuery.lenientStatus("PENDING"));
        assertEquals(PubStatus.TRASHED, EntryListQuery.lenientStatus("TRASHED"));
    }

    private WeblogEntryManager stubbed() throws Exception {
        when(manager.getWeblogEntries(any())).thenAnswer(inv -> found);
        return manager;
    }

    /** Acceptance criterion 5: neither surface builds the criteria itself any more. */
    @Test
    void neitherSurfaceBuildsTheCriteriaItself() throws Exception {
        for (String file : List.of("src/main/java/com/wikantik/pressroom/ui/controllers/editor/EntriesController.java",
                "src/main/java/com/wikantik/pressroom/ui/restapi/v1/EntriesApi.java")) {
            java.nio.file.Path path = java.nio.file.Path.of(file);
            String source = java.nio.file.Files.readString(
                    java.nio.file.Files.exists(path) ? path : java.nio.file.Path.of("app", file));
            assertFalse(source.contains("WeblogEntrySearchCriteria"), file);
        }
    }
}
