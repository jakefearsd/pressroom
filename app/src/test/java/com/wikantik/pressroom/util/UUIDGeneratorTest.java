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

package com.wikantik.pressroom.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the id generator used for every persisted entity's primary key.
 *
 * <p>Both properties asserted here are load bearing: the ids go into a
 * {@code varchar(36)} column, so the width matters, and a repeated value would
 * collide on insert.
 */
public class UUIDGeneratorTest {

    @Test
    public void generatesAThirtySixCharacterUuid() {
        String uuid = UUIDGenerator.generateUUID();

        assertEquals(36, uuid.length(),
                "Entity id columns are varchar(36); a longer id is truncated or rejected "
                        + "by the database. Got: " + uuid);
        assertTrue(uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "Not a canonical UUID: " + uuid);
    }

    @Test
    public void generatesADistinctValueEveryTime() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            generated.add(UUIDGenerator.generateUUID());
        }

        assertEquals(1000, generated.size(),
                "Duplicate ids were generated, which would collide as primary keys.");
    }
}
