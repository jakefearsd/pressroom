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

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria.SortBy;

/**
 * The one place a list of a weblog's entries is asked for: the filters become a
 * {@link WeblogEntrySearchCriteria}, the trash rule is applied, and one row more
 * than a page is fetched so "is there more?" needs no count query. The JSP
 * list ({@code entries.rol}) and {@code GET /v1/weblogs/{handle}/entries} both
 * call it; each parses its own parameters (the API's strict status and range
 * checks, the JSP's page number times its page size) before it does.
 *
 * <p>Knows nothing of a servlet, a model or a form bean.
 */
public final class EntryListQuery {

    private EntryListQuery() {
    }

    /**
     * What the author narrowed the list to. Every member may be null (no filter);
     * a null {@code sortBy} keeps the criteria's own default order.
     */
    public record Filters(PubStatus status, String category, List<String> tags, String text, String locale,
                          Date startDate, Date endDate, SortBy sortBy) {
    }

    /**
     * One page of entries.
     *
     * @param items the entries on this page, never more than {@code limit}
     * @param offset the index of the first one among all matches
     * @param limit the page size asked for
     * @param hasMore whether a further page exists
     */
    public record EntryListPage(List<WeblogEntry> items, int offset, int limit, boolean hasMore) {
    }

    /**
     * A status filter typed into a url: blank, "ALL" and anything that is not a
     * status name mean no filter, so a stale bookmark shows the whole list.
     * (The API is stricter and parses its own.)
     */
    public static PubStatus lenientStatus(String text) {
        if (StringUtils.isBlank(text) || "ALL".equals(text)) {
            return null;
        }
        try {
            return PubStatus.valueOf(text);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** Whether asking for {@code status} reaches the trash: only {@code TRASHED}, by name. */
    public static boolean includesTrash(PubStatus status) {
        return status == PubStatus.TRASHED;
    }

    /**
     * Fetches a page.
     *
     * @param limit the page size, at least 1
     */
    public static EntryListPage run(WeblogEntryManager manager, Weblog weblog, Filters filters, int offset,
                                    int limit) throws WebloggerException {
        WeblogEntrySearchCriteria criteria = new WeblogEntrySearchCriteria();
        criteria.setWeblog(weblog);
        criteria.setStatus(filters.status());
        // The only way to see the trash, and only when asked for by name --
        // WeblogEntrySearchCriteria.includeTrashed defaults to false, which is
        // the safety property: a caller naming no status gets it untouched.
        criteria.setIncludeTrashed(includesTrash(filters.status()));
        criteria.setCatName(filters.category());
        criteria.setTags(filters.tags());
        criteria.setText(filters.text());
        criteria.setLocale(filters.locale());
        criteria.setStartDate(filters.startDate());
        criteria.setEndDate(filters.endDate());
        if (filters.sortBy() != null) {
            criteria.setSortBy(filters.sortBy());
        }
        criteria.setOffset(offset);
        // One extra row decides hasMore without a second count query.
        criteria.setMaxResults(limit + 1);

        List<WeblogEntry> found = manager.getWeblogEntries(criteria);
        boolean hasMore = found.size() > limit;
        return new EntryListPage(new ArrayList<>(hasMore ? found.subList(0, limit) : found), offset, limit, hasMore);
    }
}
