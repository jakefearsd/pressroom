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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.text.StringEscapeUtils;
import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.TrafficState;
import com.wikantik.pressroom.pojos.WeblogEntry;

/**
 * What an entry list needs beyond the entries themselves: the 30-day views the entries screen's Views column
 * reads ({@link Views}), and an entry's title as plain text ({@link #plainTitle}). The JSP list's view model
 * went with the JSP; the React list reads {@code GET /v1/weblogs/{handle}/entries}.
 */
public final class EntryListView {

    private EntryListView() {
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

        /**
         * One entry's 30-day views as a number: its recorded figure, else a known zero, else null (unknown:
         * the entries page prints a dash).
         */
        public Long of(String entryId) {
            if (views.containsKey(entryId)) {
                return views.get(entryId);
            }
            return configured && zeroIsKnown ? Long.valueOf(0) : null;
        }

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
     * An entry's display title as plain text, for a caller to escape exactly once
     * (React escapes text itself, so there it is simply the text).
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

    /** Each entry's {@link #plainTitle}, by entry id; empty for none. */
    public static Map<String, String> plainTitles(List<WeblogEntry> entries) {
        Map<String, String> titles = new HashMap<>();
        if (entries != null) {
            for (WeblogEntry entry : entries) {
                titles.put(entry.getId(), plainTitle(entry));
            }
        }
        return titles;
    }
}
