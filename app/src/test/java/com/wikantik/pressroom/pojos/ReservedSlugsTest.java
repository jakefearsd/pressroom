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
package com.wikantik.pressroom.pojos;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ReservedSlugs#RESERVED} against the one other place a first
 * path segment is given routing meaning:
 * {@code WeblogRequestMapper}'s GET {@code switch(context)}.
 *
 * <p>A word the mapper routes by name but {@code ReservedSlugs} does not
 * reserve can be saved as a page slug (savePage only refuses reserved
 * names) and shown in navigation, yet its URL is swallowed by the mapper's
 * case for that word instead of ever reaching the page it names -- exactly
 * what happened for {@code mediaresource} before it was added here.
 *
 * <p>{@code WeblogRequestMapper}'s cases are string literals in a
 * {@code switch}, not constants this test can import, so the mirrored list
 * below is hand-maintained: whoever adds a case to that switch (see
 * {@code WeblogRequestMapper#mapRequestUrl}, the block handling GET context
 * dispatch) must add the same word here.
 */
class ReservedSlugsTest {

    /**
     * Every {@code case} label in {@code WeblogRequestMapper}'s GET
     * {@code switch (context)} -- the words that resolve to something other
     * than a candidate page slug before {@code ReservedSlugs} is ever
     * consulted.
     */
    private static final Set<String> MAPPER_GET_CONTEXTS = Set.of(
            "page", "entry", "date", "category", "tags",
            "feed", "resource", "mediaresource", "search");

    @Test
    void everyMapperGetContextIsReserved() {
        for (String context : MAPPER_GET_CONTEXTS) {
            assertTrue(ReservedSlugs.isReserved(context),
                    "WeblogRequestMapper routes '" + context + "' by name, so a page "
                            + "slugged '" + context + "' would be unreachable at its own "
                            + "URL -- it must be in ReservedSlugs.RESERVED");
        }
    }
}
