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
package com.wikantik.pressroom.pojos;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How a stored or submitted listing order reads: lenient from storage, strict from input. */
public class EntryOrderTest {

    @Test
    void storedValueFallsBackToNewest() {
        assertEquals(EntryOrder.NEWEST, EntryOrder.of(null));
        assertEquals(EntryOrder.NEWEST, EntryOrder.of(""));
        assertEquals(EntryOrder.NEWEST, EntryOrder.of("  "));
        assertEquals(EntryOrder.NEWEST, EntryOrder.of("RANDOM"));
        assertEquals(EntryOrder.NEWEST, EntryOrder.of("NEWEST"));
        assertEquals(EntryOrder.OLDEST, EntryOrder.of("oldest"));
        assertEquals(EntryOrder.OLDEST, EntryOrder.of("OLDEST"));
    }

    @Test
    void parseIsStrictAndCaseInsensitive() {
        assertEquals(Optional.of(EntryOrder.OLDEST), EntryOrder.parse("Oldest"));
        assertEquals(Optional.of(EntryOrder.NEWEST), EntryOrder.parse("newest"));
        assertEquals(Optional.empty(), EntryOrder.parse("random"));
        assertEquals(Optional.empty(), EntryOrder.parse(""));
        assertEquals(Optional.empty(), EntryOrder.parse(null));
    }
}
