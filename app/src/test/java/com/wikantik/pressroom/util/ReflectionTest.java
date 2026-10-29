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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the reflection helpers that instantiate pluggable components.
 *
 * <p>Cache factories, cache handlers and comment plugins are all named in
 * configuration and built through here, so a failure surfaces as "the cache
 * factory could not be created" at startup with no compile-time warning.
 */
public class ReflectionTest {

    /** Stands in for a plugin whose constructor is deliberately not public. */
    static class PackagePrivateConstructor {
        private PackagePrivateConstructor() {
        }
    }

    @Test
    public void buildsAnInstanceFromAClassName() {
        Object instance = assertInstantiated("java.util.ArrayList");
        assertInstanceOf(ArrayList.class, instance);
    }

    @Test
    public void buildsAnInstanceFromAClassLiteral() throws ReflectiveOperationException {
        assertNotNull(Reflection.newInstance(ArrayList.class));
    }

    @Test
    public void reachesAConstructorThatIsNotPublic() {
        // Roller's own factories hide their constructors ("protected so only
        // the CacheManager can instantiate us"), so the helper has to open
        // them up or every cache factory fails to load.
        assertNotNull(assertInstantiated(PackagePrivateConstructor.class.getName()));
    }

    @Test
    public void reportsAnUnknownClassNameRatherThanReturningNull() {
        // Misconfiguration must be loud: a null here would fail later with an
        // NPE far from the property that caused it.
        assertThrows(ClassNotFoundException.class,
                () -> Reflection.newInstance("com.example.NoSuchPlugin"));
    }

    @Test
    public void reportsAClassWithNoNoArgConstructor() {
        assertThrows(NoSuchMethodException.class, () -> Reflection.newInstance(Integer.class));
    }

    @Test
    public void buildsOneInstancePerNameAndKeepsTheOrder() throws ReflectiveOperationException {
        List<Object> instances = Reflection.newInstances(
                new String[]{"java.util.ArrayList", "java.util.HashMap"});

        assertEquals(2, instances.size());
        assertInstanceOf(ArrayList.class, instances.get(0));
        assertInstanceOf(java.util.HashMap.class, instances.get(1));
    }

    @Test
    public void anEmptyClassListYieldsAnEmptyListOfInstances() throws ReflectiveOperationException {
        assertTrue(Reflection.newInstances(new String[0]).isEmpty());
    }

    @Test
    public void anUnsetPropertyYieldsNoInstancesRatherThanFailing() throws ReflectiveOperationException {
        // Optional extension points (cache.customHandlers and friends) are
        // usually unset; that must not be an error.
        assertTrue(Reflection.newInstancesFromProperty("no.such.property.in.roller").isEmpty());
    }

    @Test
    public void onlyDirectlyImplementedInterfacesCount() {
        // Documented limitation, and it matters: the check is used to decide
        // whether a configured class is usable as a given extension point.
        assertTrue(Reflection.implementsInterface(ArrayList.class, List.class));
        assertFalse(Reflection.implementsInterface(ArrayList.class, Comparator.class));
        assertFalse(Reflection.implementsInterface(String.class, List.class));
        assertTrue(Reflection.implementsInterface(String.class, Serializable.class));
    }

    private Object assertInstantiated(String className) {
        try {
            return Reflection.newInstance(className);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not instantiate " + className
                    + "; configured plugins and cache factories are created this way.", e);
        }
    }
}
