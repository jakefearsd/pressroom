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

package com.wikantik.pressroom.ui.rendering.util.cache;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.ui.rendering.util.WeblogFeedRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.util.Utilities;


/**
 * Building blocks for the rendering caches' keys.
 *
 * A rendering cache key is a '/' delimited list of segments built from parts of
 * the request, and every one of those parts -- category names, tags, search
 * terms, page names, query parameters -- is text the visitor controls. If a
 * value is allowed to contain the delimiter, two different requests can build
 * the same key, and then one visitor is served the page rendered for another.
 * These helpers are the one place that guarantees a segment cannot spill into
 * the next one, so that neither the per-weblog caches nor the site-wide cache
 * has to re-derive the rule (they used to, and the copies had drifted).
 */
final class CacheKeys {

    private CacheKeys() {}


    /**
     * The segments of a page key after its prefix, which the per-weblog and
     * site-wide page caches share: the view ([/entry/anchor[/page/name]] for
     * a permalink, else [/pageslug/slug][/page/name][/date/d][/cat/c]
     * [/tags/t]), then [/locale], [/page=n] (not for a permalink) and
     * [/user=u]. Every segment built from request data is labelled and
     * escaped: a category called "en" must not produce the key of the
     * English front page.
     */
    static void appendPageSegments(StringBuilder key, WeblogPageRequest pageRequest) {
        if (pageRequest.getWeblogAnchor() != null) {
            // may contain spaces or other bad chars
            key.append("/entry/").append(encode(pageRequest.getWeblogAnchor()));
            if (pageRequest.getWeblogPageName() != null) {
                // /page/<name>?entry=x renders the named template, not the
                // permalink: without this the theme's stylesheet could be
                // cached over a post (code review, 2026-11-03)
                key.append("/page/").append(encode(pageRequest.getWeblogPageName()));
            }
        } else {
            appendListingView(key, pageRequest);
        }

        if (pageRequest.getLocale() != null) {
            key.append('/').append(pageRequest.getLocale());
        }

        // add page number when applicable
        if (pageRequest.getWeblogAnchor() == null) {
            key.append("/page=").append(pageRequest.getPageNum());
        }

        // add login state
        if (pageRequest.getAuthenticUser() != null) {
            key.append("/user=").append(pageRequest.getAuthenticUser());
        }
    }

    /**
     * The feed key segments after the prefix and page number, which the
     * per-weblog and site-wide feed caches share: [/search/t], [/cat/c],
     * [/tags/t], [/locale], [/excerpts], each labelled and escaped.
     */
    static void appendFeedSegments(StringBuilder key, WeblogFeedRequest feedRequest) {
        if (feedRequest.getTerm() != null) {
            // arbitrary text straight off the query string
            key.append("/search/").append(encode(feedRequest.getTerm()));
        }

        if (feedRequest.getWeblogCategoryName() != null) {
            key.append("/cat/").append(encode(feedRequest.getWeblogCategoryName()));
        }

        if (feedRequest.getTags() != null && !feedRequest.getTags().isEmpty()) {
            key.append("/tags/").append(tags(feedRequest.getTags()));
        }

        if (feedRequest.getLocale() != null) {
            key.append('/').append(feedRequest.getLocale());
        }

        if (feedRequest.isExcerpts()) {
            key.append("/excerpts");
        }
    }

    private static void appendListingView(StringBuilder key, WeblogPageRequest pageRequest) {
        if (pageRequest.getPageSlug() != null) {
            // the raw path segment of a bare-slug page (/<handle>/about),
            // read here rather than the WeblogPage it resolves to, so
            // that generating a key never queries the database. Without
            // this segment every bare-slug page on a weblog -- and the
            // weblog's homepage -- shared one cache key, so whichever
            // page rendered first was served for all of them.
            key.append("/pageslug/").append(encode(pageRequest.getPageSlug()));
        }

        if (pageRequest.getWeblogPageName() != null) {
            // comes straight off the url path, so it may contain slashes
            key.append("/page/").append(encode(pageRequest.getWeblogPageName()));
        }

        if (pageRequest.getWeblogDate() != null) {
            key.append("/date/").append(pageRequest.getWeblogDate());
        }

        if (pageRequest.getWeblogCategoryName() != null) {
            // may contain spaces or other bad chars
            key.append("/cat/").append(encode(pageRequest.getWeblogCategoryName()));
        }

        if ("tags".equals(pageRequest.getContext())
                && pageRequest.getTags() != null && !pageRequest.getTags().isEmpty()) {
            key.append("/tags/").append(tags(pageRequest.getTags()));
        }
    }

    /**
     * Escape a request-supplied value for use as a single key segment.
     *
     * Percent-encoding removes '/' (the segment delimiter). Spaces are encoded
     * as %20 rather than the URLEncoder default of '+', because '+' is itself
     * the delimiter between tags.
     */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }


    /**
     * Render a tag set as one key segment.
     *
     * Sorted, because the same tags in a different order select the same
     * entries and should hit the same cache entry.
     */
    static String tags(List<String> tags) {

        String[] sorted = tags.toArray(new String[0]);
        Arrays.sort(sorted);

        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = encode(sorted[i]);
        }

        return Utilities.stringArrayToString(sorted, "+");
    }


    /**
     * Render the query parameters of a request as one opaque key segment.
     *
     * Custom page templates can read any query parameter, so two requests that
     * differ only in their parameters render differently and must not share a
     * cache entry. Parameters are sorted by name so that the segment depends on
     * what was requested rather than on the iteration order of whatever Map the
     * request was parsed into; every value of a repeated parameter is included
     * so that {@code ?tag=a&tag=b} cannot be served the response cached for
     * {@code ?tag=a&tag=c}; and names and values are escaped so that neither can
     * contain the ',' '=' or '&' that hold the segment together.
     */
    static String params(Map<String, String[]> params) {

        List<String> names = new ArrayList<>(params.size());
        for (String name : params.keySet()) {
            // a Map is free to hold a null key; a cache key is no place to throw
            if (name != null) {
                names.add(name);
            }
        }
        Collections.sort(names);

        StringBuilder segment = new StringBuilder();
        for (String name : names) {
            String[] values = params.get(name);
            if (values == null) {
                continue;
            }
            if (segment.length() > 0) {
                segment.append(',');
            }
            segment.append(encode(name)).append('=');
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    segment.append('&');
                }
                segment.append(encode(values[i]));
            }
        }

        return Utilities.toBase64(segment.toString().getBytes(StandardCharsets.UTF_8));
    }
}
