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

import java.util.List;
import java.util.Optional;

import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A post's place among a strand's reader-visible parts: N of M, neighbours. */
class StrandPlaceTest {

    private static WeblogEntry post(String id) {
        WeblogEntry e = new WeblogEntry();
        e.setId(id);
        return e;
    }

    private final WeblogEntry a = post("a");
    private final WeblogEntry b = post("b");
    private final WeblogEntry c = post("c");

    @Test
    void theMiddleOfThreeIsTwoOfThreeWithBothNeighbours() {
        StrandPlace p = StrandPlace.of(List.of(a, b, c), post("b")).orElseThrow();
        assertEquals(2, p.number());
        assertEquals(3, p.total());
        assertEquals("a", p.previous().getId());
        assertEquals("c", p.next().getId());
    }

    @Test
    void theFirstHasNoPrevious() {
        StrandPlace p = StrandPlace.of(List.of(a, b, c), a).orElseThrow();
        assertEquals(1, p.number());
        assertNull(p.previous());
        assertEquals("b", p.next().getId());
    }

    @Test
    void theLastHasNoNext() {
        StrandPlace p = StrandPlace.of(List.of(a, b, c), c).orElseThrow();
        assertEquals(3, p.number());
        assertEquals("b", p.previous().getId());
        assertNull(p.next());
    }

    @Test
    void anEntryNotAmongThePartsHasNoPlace() {
        Optional<StrandPlace> p = StrandPlace.of(List.of(a, b), c);
        assertTrue(p.isEmpty());
    }

    @Test
    void aSinglePartIsOneOfOne() {
        StrandPlace p = StrandPlace.of(List.of(a), a).orElseThrow();
        assertEquals(1, p.number());
        assertEquals(1, p.total());
        assertNull(p.previous());
        assertNull(p.next());
    }
}
