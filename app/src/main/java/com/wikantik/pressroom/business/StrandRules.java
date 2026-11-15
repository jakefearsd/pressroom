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
package com.wikantik.pressroom.business;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;

/**
 * Who may be named as a part of a strand. One rule for the page editor, the
 * entry editor and the API; the answer is a reason key per refused id, never
 * a thrown exception, so each caller reports it in its own way.
 */
public final class StrandRules {

    private StrandRules() {
    }

    /**
     * The ids that cannot be parts of {@code strand}, with the message key of
     * why: unknown (null entry), of another blog, already in a different
     * strand, or listed twice (the second mention). An id keeps its first
     * reason. An empty map means the list is acceptable.
     *
     * @param requested    the entry for each id, null where the id is unknown
     *                     (parallel to {@code requestedIds})
     * @param requestedIds the ids in the order asked for
     */
    public static Map<String, String> refusals(WeblogPage strand, List<WeblogEntry> requested,
            List<String> requestedIds) {
        Map<String, String> refused = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < requestedIds.size(); i++) {
            String id = requestedIds.get(i);
            WeblogEntry entry = requested.get(i);
            if (entry == null) {
                refused.putIfAbsent(id, "strand.error.unknownPost");
            } else if (!strand.getWeblog().getId().equals(entry.getWebsite().getId())) {
                refused.putIfAbsent(id, "strand.error.otherBlog");
            } else if (entry.getStrandPageId() != null && !entry.getStrandPageId().equals(strand.getId())) {
                refused.putIfAbsent(id, "strand.error.inAnotherStrand");
            } else if (!seen.add(id)) {
                refused.putIfAbsent(id, "strand.error.duplicate");
            }
        }
        return refused;
    }
}
