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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.discovery.DiscoveryEligibility;
import com.wikantik.pressroom.business.related.GeoBox;
import com.wikantik.pressroom.business.related.RelatedPicker;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;

/**
 * What a post page offers after the article: the nearest posts and the
 * same blog's most related ones, as {@code $model.furtherReading} (the
 * showAfterEntry macro in {@code weblog.vm}). Specs
 * {@code docs/superpowers/specs/2026-11-11-post-page-discovery-design.md}
 * and {@code 2026-11-12-patch-0-9-1-design.md} (other blogs under Nearby).
 *
 * <p>Two queries whatever the size of the blog: its newest
 * {@link #CANDIDATES} published posts, and the shared-tag counts. A post
 * older than those is never offered; it still offers the newer ones. A
 * third, bounded by a box and a row limit, asks the other blogs for posts
 * nearby, and only when the post has coordinates and its own blog left a
 * Nearby place empty.
 */
public final class FurtherReading {

    /** How many of the blog's newest posts are considered. */
    static final int CANDIDATES = 200;

    static final int NEARBY_MAX = 3;

    static final int RELATED_MAX = 3;

    /** Farther than this is another trip, not "nearby". */
    static final double NEARBY_KM = 80.0;

    /** The row limit of the one read of other blogs' posts around this one. */
    static final int OTHER_BLOG_ROWS = 50;

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
        if (near.size() < NEARBY_MAX && hasPlace(entry) && showsOtherBlogs(entry)) {
            near = RelatedPicker.nearby(entry, candidates, otherBlogsPostsAround(entry, entries),
                    NEARBY_MAX, NEARBY_KM);
        }
        Set<String> shown = near.stream().map(n -> n.entry().getId()).collect(Collectors.toSet());
        Map<String, Long> sharedTags = entries.getSharedTagCounts(entry);

        return new FurtherReading(
                near.stream()
                        .map(n -> new NearbyEntry(WeblogEntryWrapper.wrap(n.entry(), urls, weblogger),
                                Math.round(miles ? n.km() / KM_PER_MILE : n.km()), miles,
                                !sameBlog(entry, n.entry())))
                        .toList(),
                RelatedPicker.related(entry, candidates, sharedTags, shown, RELATED_MAX).stream()
                        .map(e -> WeblogEntryWrapper.wrap(e, urls, weblogger))
                        .toList());
    }

    private static boolean hasPlace(WeblogEntry entry) {
        return entry.getGeoLatitude() != null && entry.getGeoLongitude() != null;
    }

    /**
     * False for a blog that turned "more from the site" off: its pages show
     * no other blog's posts, under the article as in the footer.
     */
    private static boolean showsOtherBlogs(WeblogEntry entry) {
        return !Boolean.FALSE.equals(entry.getWebsite().getShowMoreFromSite());
    }

    private static boolean sameBlog(WeblogEntry one, WeblogEntry other) {
        return Objects.equals(one.getWebsite().getId(), other.getWebsite().getId());
    }

    /**
     * Other blogs' posts that may be near enough: one read bounded by the box
     * around the post and by {@link #OTHER_BLOG_ROWS}, then discovery's own
     * rule for what one blog may show of another.
     */
    private static List<WeblogEntry> otherBlogsPostsAround(WeblogEntry entry, WeblogEntryManager entries)
            throws WebloggerException {
        GeoBox box = GeoBox.around(entry.getGeoLatitude(), entry.getGeoLongitude(), NEARBY_KM);
        return entries.getPublishedEntriesInBox(entry.getWebsite(), box, OTHER_BLOG_ROWS).stream()
                .filter(other -> DiscoveryEligibility.isEligible(other) && !sameBlog(entry, other))
                .toList();
    }

    /**
     * The nearest posts: the blog's own nearest first, then other blogs'
     * in the places left; empty for a post without coordinates.
     */
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
        private final boolean otherBlog;

        NearbyEntry(WeblogEntryWrapper entry, long distance, boolean miles, boolean otherBlog) {
            this.entry = entry;
            this.distance = distance;
            this.miles = miles;
            this.otherBlog = otherBlog;
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

        /** True for a post of another blog: the page names that blog and links it absolutely. */
        public boolean isOtherBlog() {
            return otherBlog;
        }
    }
}
