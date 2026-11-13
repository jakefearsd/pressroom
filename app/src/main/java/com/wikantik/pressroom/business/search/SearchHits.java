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
package com.wikantik.pressroom.business.search;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The answer to a {@link SearchSpec}.
 *
 * @param hits            the requested window of ranked hits
 * @param total           matches among the hits considered, less what conversion dropped
 * @param anyWordFallback true when no post had every word, so these match some of them
 * @param perBlog         blog handle to its number of matches among the hits considered
 * @param categories      lower-cased categories of the hits, for a site-wide search
 * @param capped          true when more posts matched than the hits considered (500), so
 *                        {@code total} is a floor, not the number of matches
 */
public record SearchHits(List<SearchHit> hits, int total, boolean anyWordFallback,
        Map<String, Integer> perBlog, Set<String> categories, boolean capped) {

    /** Nothing found. */
    public static final SearchHits NONE = new SearchHits(List.of(), 0, false, Map.of(), Set.of());

    /** An answer within the hits considered: {@code total} is the number of matches. */
    public SearchHits(List<SearchHit> hits, int total, boolean anyWordFallback,
            Map<String, Integer> perBlog, Set<String> categories) {
        this(hits, total, anyWordFallback, perBlog, categories, false);
    }

    public List<SearchHit> getHits() {
        return hits;
    }

    public int getTotal() {
        return total;
    }

    public boolean isAnyWordFallback() {
        return anyWordFallback;
    }

    public Map<String, Integer> getPerBlog() {
        return perBlog;
    }

    public Set<String> getCategories() {
        return categories;
    }

    public boolean isCapped() {
        return capped;
    }
}
