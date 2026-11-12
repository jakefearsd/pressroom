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
package com.wikantik.pressroom.ui.rendering.model;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.related.RelatedPicker;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;

/**
 * What a post page offers after the article: the same blog's nearest posts
 * and its most related ones, as {@code $model.furtherReading} (the
 * showAfterEntry macro in {@code weblog.vm}). Spec
 * {@code docs/superpowers/specs/2026-11-11-post-page-discovery-design.md}.
 *
 * <p>Two queries whatever the size of the blog: its newest
 * {@link #CANDIDATES} published posts, and the shared-tag counts. A post
 * older than those is never offered; it still offers the newer ones.
 */
public final class FurtherReading {

    /** How many of the blog's newest posts are considered. */
    static final int CANDIDATES = 200;

    static final int NEARBY_MAX = 3;

    static final int RELATED_MAX = 3;

    /** Farther than this is another trip, not "nearby". */
    static final double NEARBY_KM = 80.0;

    private static final double KM_PER_MILE = 1.609344;

    /** Countries whose road signs are in miles. */
    private static final Set<String> MILE_COUNTRIES = Set.of("US", "GB", "LR", "MM");

    /** For a page that is not a post. */
    static final FurtherReading NONE = new FurtherReading(List.of(), List.of());

    private final List<NearbyEntry> nearby;
    private final List<WeblogEntryWrapper> related;

    private FurtherReading(List<NearbyEntry> nearby, List<WeblogEntryWrapper> related) {
        this.nearby = nearby;
        this.related = related;
    }

    static FurtherReading of(WeblogEntry entry, URLStrategy urls, Weblogger weblogger)
            throws WebloggerException {
        WeblogEntryManager entries = weblogger.getWeblogEntryManager();
        WeblogEntrySearchCriteria newest = new WeblogEntrySearchCriteria();
        newest.setWeblog(entry.getWebsite());
        newest.setStatus(PubStatus.PUBLISHED);
        newest.setEndDate(new Date());
        newest.setMaxResults(CANDIDATES);
        List<WeblogEntry> candidates = entries.getWeblogEntries(newest);

        boolean miles = MILE_COUNTRIES.contains(entry.getWebsite().getLocaleInstance().getCountry());
        List<RelatedPicker.Near> near = RelatedPicker.nearby(entry, candidates, NEARBY_MAX, NEARBY_KM);
        Set<String> shown = near.stream().map(n -> n.entry().getId()).collect(Collectors.toSet());
        Map<String, Long> sharedTags = entries.getSharedTagCounts(entry);

        return new FurtherReading(
                near.stream()
                        .map(n -> new NearbyEntry(WeblogEntryWrapper.wrap(n.entry(), urls, weblogger),
                                Math.round(miles ? n.km() / KM_PER_MILE : n.km()), miles))
                        .toList(),
                RelatedPicker.related(entry, candidates, sharedTags, shown, RELATED_MAX).stream()
                        .map(e -> WeblogEntryWrapper.wrap(e, urls, weblogger))
                        .toList());
    }

    /** The nearest posts, nearest first; empty for a post without coordinates. */
    public List<NearbyEntry> getNearby() {
        return nearby;
    }

    /** The most related posts, none of them already under {@link #getNearby()}. */
    public List<WeblogEntryWrapper> getRelated() {
        return related;
    }

    /** A nearby post and how far it is, in the blog's own unit. */
    public static final class NearbyEntry {

        private final WeblogEntryWrapper entry;
        private final long distance;
        private final boolean miles;

        NearbyEntry(WeblogEntryWrapper entry, long distance, boolean miles) {
            this.entry = entry;
            this.distance = distance;
            this.miles = miles;
        }

        public WeblogEntryWrapper getEntry() {
            return entry;
        }

        /** Whole miles or kilometres, rounded; 0 means under one. */
        public long getDistance() {
            return distance;
        }

        public boolean isMiles() {
            return miles;
        }
    }
}
