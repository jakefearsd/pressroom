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
