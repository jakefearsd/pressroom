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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.TrafficState;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria.SortBy;
import com.wikantik.pressroom.ui.controllers.EntryListQuery.EntryListPage;
import com.wikantik.pressroom.ui.controllers.editor.TrafficView;
import com.wikantik.pressroom.ui.controllers.util.KeyValueObject;

/**
 * What the entries list ({@code Entries.jsp}) shows, as plain values: the rows
 * with their plain titles and views cells, the first and last entry, the page
 * number, whether there is more, whether a filter is active, the views-column
 * flag, and the category, sort and status choices with the filter values
 * selected. Holds no urls -- links are the controller's business -- and knows
 * nothing of a servlet, a model or a form bean.
 *
 * @param entries the page's entries; null when the lookup failed, which the pager reports as such
 * @param rows each entry with the text the list prints for it; empty when the lookup failed
 * @param pageNumber the zero-based page
 * @param hasMore whether a further page exists
 * @param filtered whether the author narrowed the list, so an empty result means "nothing matched"
 * @param viewsColumn whether the "Views (30d)" column is shown
 * @param viewsShown whether the views cells were read; false when the lookup failed
 * @param categories the weblog's categories (no synthetic "Any" entry)
 * @param sortByOptions the sort choices, keyed by {@link SortBy} name
 * @param statusOptions the status choices, "ALL" first
 * @param filters the selected filter values
 */
public record EntryListView(List<WeblogEntry> entries, List<Row> rows, int pageNumber, boolean hasMore,
                            boolean filtered, boolean viewsColumn, boolean viewsShown,
                            List<WeblogCategory> categories, List<KeyValueObject> sortByOptions,
                            List<KeyValueObject> statusOptions, Filters filters) {

    /** One line of the list: the entry, its title as plain text, and its views cell (null without a column). */
    public record Row(WeblogEntry entry, String title, String views) {
    }

    /** The filter values as the author typed or selected them; each may be null. */
    public record Filters(String status, String categoryName, String tags, String text, String startDate,
                          String endDate, SortBy sortBy) {

        /** Whether any of them narrows the list. The sort order and the "ALL" status are defaults, not filters. */
        public boolean any() {
            return StringUtils.isNotEmpty(categoryName) || StringUtils.isNotEmpty(tags)
                    || StringUtils.isNotEmpty(text) || StringUtils.isNotEmpty(startDate)
                    || StringUtils.isNotEmpty(endDate)
                    || StringUtils.isNotEmpty(status) && !"ALL".equals(status);
        }
    }

    /**
     * The analytics the views column needs, read once. An entry with no recorded
     * views is a real zero once the blog has a complete snapshot; before that, or
     * when the snapshot holds only the top paths, its views are not known.
     *
     * @param configured whether analytics is set up at all
     * @param views views over 30 days by entry id; empty when none were read
     * @param zeroIsKnown whether an entry missing from {@code views} really had none
     */
    public record Views(boolean configured, Map<String, Long> views, boolean zeroIsKnown) {

        /** Analytics is not set up: no column. */
        public static Views none() {
            return new Views(false, Map.of(), false);
        }

        /**
         * Reads the 30-day views of a weblog, and only when analytics is
         * configured and {@code needed}; the local snapshot is read once.
         */
        public static Views read(AnalyticsManager analytics, String weblogId, boolean needed) {
            if (!analytics.getStatus().configured()) {
                return none();
            }
            if (!needed) {
                return new Views(true, Map.of(), false);
            }
            Map<String, Long> views = analytics.entryViews(weblogId, "30d");
            boolean snapshot = !views.isEmpty()
                    || analytics.getBlogTraffic(weblogId, "30d").state() != TrafficState.NO_DATA;
            return new Views(true, views, snapshot && analytics.pathsCappedAt(weblogId, "30d") == 0);
        }
    }

    /**
     * Builds the view.
     *
     * @param page the page found, or null when the lookup failed
     * @param pageNumber the zero-based page asked for
     * @param views the analytics; its cells are filled only when {@code page} is present
     * @param categories the weblog's categories
     * @param label turns a message key into the viewer's text
     */
    public static EntryListView of(EntryListPage page, int pageNumber, Views views,
                                   Locale locale, List<WeblogCategory> categories, Function<String, String> label,
                                   Filters filters) {
        List<WeblogEntry> entries = page == null ? null : page.items();
        boolean cells = views.configured() && entries != null;
        List<Row> rows = new ArrayList<>();
        if (entries != null) {
            for (WeblogEntry entry : entries) {
                rows.add(new Row(entry, plainTitle(entry), cells ? cell(views, entry.getId(), locale) : null));
            }
        }
        return new EntryListView(entries, rows, pageNumber, page != null && page.hasMore(), filters.any(),
                views.configured(), cells, categories, sortByOptions(label), statusOptions(label), filters);
    }

    private static String cell(Views views, String id, Locale locale) {
        if (views.views().containsKey(id)) {
            return TrafficView.number(views.views().get(id), locale);
        }
        return views.zeroIsKnown() ? "0" : TrafficView.DASH;
    }

    /** The first entry on the page, or null when it is empty or the lookup failed. */
    public WeblogEntry firstEntry() {
        return entries == null || entries.isEmpty() ? null : entries.get(0);
    }

    /** The last entry on the page, or null when it is empty or the lookup failed. */
    public WeblogEntry lastEntry() {
        return entries == null || entries.isEmpty() ? null : entries.get(entries.size() - 1);
    }

    /** Each row's plain title by entry id; empty when the lookup failed. */
    public Map<String, String> titlesById() {
        Map<String, String> titles = new HashMap<>();
        for (Row row : rows) {
            titles.put(row.entry().getId(), row.title());
        }
        return titles;
    }

    /** Each row's views cell by entry id. */
    public Map<String, String> viewsById() {
        Map<String, String> cells = new HashMap<>();
        for (Row row : rows) {
            cells.put(row.entry().getId(), row.views());
        }
        return cells;
    }

    /**
     * An entry's display title as plain text, for a JSP to escape exactly once
     * with {@code fn:escapeXml} -- in element content and attributes alike.
     *
     * <p>Neither stored form is safe to print as it stands. A title is stored
     * HTML-escaped (see {@code EntryFieldRules.escapeTitle}), so escaping it
     * again shows "Tom &amp;amp; Jerry" and a screen reader reads "amp". An
     * UNTITLED entry's {@code getDisplayTitle()} is its body with tags removed
     * -- not escaped, and {@code Utilities.removeHTML} keeps an unterminated
     * tag verbatim -- so printing it bare is markup injection. Unescaping the
     * one and leaving the other as text gives both the same shape: raw text.
     */
    public static String plainTitle(WeblogEntry entry) {
        return StringEscapeUtils.unescapeHtml4(entry.getDisplayTitle());
    }

    private static List<KeyValueObject> sortByOptions(Function<String, String> label) {
        List<KeyValueObject> opts = new ArrayList<>();
        opts.add(new KeyValueObject(SortBy.PUBLICATION_TIME.name(), label.apply("weblogEntryQuery.label.pubTime")));
        opts.add(new KeyValueObject(SortBy.UPDATE_TIME.name(), label.apply("weblogEntryQuery.label.updateTime")));
        return opts;
    }

    private static List<KeyValueObject> statusOptions(Function<String, String> label) {
        List<KeyValueObject> opts = new ArrayList<>();
        opts.add(new KeyValueObject("ALL", label.apply("weblogEntryQuery.label.allEntries")));
        opts.add(new KeyValueObject("DRAFT", label.apply("weblogEntryQuery.label.draftOnly")));
        opts.add(new KeyValueObject("PUBLISHED", label.apply("weblogEntryQuery.label.publishedOnly")));
        opts.add(new KeyValueObject("PENDING", label.apply("weblogEntryQuery.label.pendingOnly")));
        opts.add(new KeyValueObject("SCHEDULED", label.apply("weblogEntryQuery.label.scheduledOnly")));
        return opts;
    }
}
