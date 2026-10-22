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
package org.apache.roller.weblogger.pojos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link EntityIds}: ids are generated except inside {@code materialise}, and
 * the suspension never outlives it -- a leak would hand the next entity the
 * application builds on that thread a null id, which a store writes as a
 * null primary key.
 */
class EntityIdsTest {

    @Test
    void generatesADistinctUuidOutsideMaterialise() {
        String first = EntityIds.newId();

        assertNotNull(first);
        assertEquals(36, first.length());
        assertNotEquals(first, EntityIds.newId());
    }

    @Test
    void anEntityBuiltInsideMaterialiseHasNoIdAndTheNextOneDoes() {
        WeblogEntry materialised = EntityIds.materialise(WeblogEntry::new);

        assertNull(materialised.getId());
        assertNotNull(new WeblogEntry().getId());
    }

    @Test
    void nestingRestoresTheOuterSuspension() {
        String afterInner = EntityIds.materialise(() -> {
            EntityIds.materialise(User::new);
            return EntityIds.newId();
        });

        assertNull(afterInner, "the inner call must not end the outer one's suspension");
        assertNotNull(EntityIds.newId());
    }

    @Test
    void generationResumesWhenTheConstructorThrows() {
        assertThrows(IllegalStateException.class, () -> EntityIds.materialise(() -> {
            throw new IllegalStateException("constructor failed");
        }));

        assertNotNull(EntityIds.newId());
    }
}
