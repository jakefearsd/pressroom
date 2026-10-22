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

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every persistent entity in {@code pojos} assigns itself a UUID in its field
 * initialiser rather than waiting for the database. (EclipseLink's own
 * construction of loaded rows skips it: {@code EntityLoadIdGenerationTest}.)
 *
 * <p>That is what lets Roller build an object graph, put the objects in sets
 * and wire up their relationships before anything is flushed. An entity that
 * came into existence with a null or blank id would break
 * {@code JPAPersistenceStrategy.store}, and one that reused an id would let a
 * save silently overwrite an unrelated row. Neither shows up until a fairly
 * specific integration test happens to run.
 *
 * <p>The entities are discovered by scanning the package, so a new one is
 * covered as soon as it is written.
 */
class GeneratedIdTest {

    /**
     * Entities that legitimately start without an id. {@code MediaFileDirectory}
     * only assigns one in its three-argument constructor, where the weblog it
     * belongs to is known.
     */
    private static final List<String> ID_ASSIGNED_LATER = List.of("MediaFileDirectory");

    private static List<Class<?>> entitiesWithAGeneratedId() {
        List<Class<?>> entities = new ArrayList<>();
        for (Class<?> candidate : PojoClassScanner.classesIn(PojoClassScanner.POJOS)) {
            if (Modifier.isAbstract(candidate.getModifiers()) || candidate.isEnum()
                    || candidate.isInterface()
                    || ID_ASSIGNED_LATER.contains(candidate.getSimpleName())) {
                continue;
            }
            if (noArgConstructor(candidate) != null && stringIdGetter(candidate) != null) {
                entities.add(candidate);
            }
        }
        return entities;
    }

    private static Constructor<?> noArgConstructor(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            return Modifier.isPublic(constructor.getModifiers()) ? constructor : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Method stringIdGetter(Class<?> type) {
        try {
            Method getter = type.getMethod("getId");
            return getter.getReturnType() == String.class ? getter : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @Test
    void theScanFindsTheEntitiesItIsSupposedTo() {
        // Guards the guard: a scan that found nothing would make the checks
        // below vacuous.
        List<String> found = entitiesWithAGeneratedId().stream()
                .map(Class::getSimpleName).sorted().toList();

        assertTrue(found.size() >= 12,
                "Expected the pojos package to contain at least a dozen self-identifying "
                        + "entities, found " + found);
        assertTrue(found.containsAll(List.of("Weblog", "WeblogEntry", "User", "WeblogCategory")),
                "The core entities must be in the scan: " + found);
    }

    @TestFactory
    Stream<DynamicTest> everyEntityGivesItselfAUniqueIdBeforeItIsPersisted() {
        return entitiesWithAGeneratedId().stream().map(entity ->
                DynamicTest.dynamicTest(entity.getSimpleName(), () -> {
                    String first = idOf(entity);
                    String second = idOf(entity);

                    assertNotNull(first, entity.getSimpleName()
                            + " must generate its own id at construction; a null id means "
                            + "the entity cannot be stored or put in an id-keyed map before "
                            + "it reaches the database");
                    assertTrue(!first.isBlank(), entity.getSimpleName()
                            + " generated a blank id, which the persistence layer will "
                            + "happily store and then never find again");
                    assertNotEquals(first, second, entity.getSimpleName()
                            + " gave two separate instances the same id. Saving one would "
                            + "overwrite the other.");
                }));
    }

    private static String idOf(Class<?> entity) throws Exception {
        Object instance = noArgConstructor(entity).newInstance();
        return (String) stringIdGetter(entity).invoke(instance);
    }

    @Test
    void aMediaFileDirectoryTakesItsIdWhenItIsAttachedToAWeblog() {
        // The documented exception to the rule above, asserted so the exception
        // list cannot quietly grow to cover a real regression.
        Weblog weblog = new Weblog();
        weblog.setHandle("testblog");

        MediaFileDirectory attached = new MediaFileDirectory(weblog, "photos", "Holiday snaps");

        assertNotNull(attached.getId(),
                "The weblog-attaching constructor must assign the id");
        assertTrue(!attached.getId().isBlank());
        assertEquals(List.of(attached), weblog.getMediaFileDirectories(),
                "and must register the directory with its weblog");
    }
}
