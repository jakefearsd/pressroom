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
package com.wikantik.pressroom.pojos.wrapper;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces the property that gives {@code pojos.wrapper} its reason to exist:
 * a wrapper is a <em>read-only</em> view of a domain object.
 *
 * <p>Velocity resolves method calls reflectively at render time. Whatever a
 * wrapper exposes, a theme author can call -- and themes are editable through
 * the web UI by anyone with weblog admin rights. If a wrapper ever grew a
 * setter, a theme could quietly rewrite the database, and nothing else in the
 * build would notice. So rather than listing the wrappers by hand, this scans
 * the whole package: a new wrapper is covered the moment it is added.
 */
class WrapperSafetyTest {

    /** Read-only accessors whose names would otherwise look like mutators. */
    private static final List<String> ALLOWED_NON_GETTERS = List.of(
            "wrap", "findEntryAttribute", "displayContent", "formatPubTime", "formatUpdateTime",
            "retrieveWeblogEntries", "isInUse", "isHidden", "isNavbar",
            "equals", "hashCode", "toString", "url", "webpUrl");

    private static List<Class<?>> wrapperClasses() {
        URL codeSource = WeblogWrapper.class.getProtectionDomain().getCodeSource().getLocation();
        Path directory;
        try {
            directory = Path.of(codeSource.toURI())
                    .resolve("com/wikantik/pressroom/pojos/wrapper");
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Could not read " + codeSource, e);
        }

        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith("Wrapper.class")) {
                    continue;
                }
                String className = "com.wikantik.pressroom.pojos.wrapper."
                        + name.substring(0, name.length() - ".class".length());
                try {
                    classes.add(Class.forName(className));
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("Could not load " + className, e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return classes;
    }

    @Test
    void everyWrapperInThePackageIsScanned() {
        // Guards the guard: if the scan stopped finding wrappers, the checks
        // below would pass by looking at nothing.
        List<String> found = wrapperClasses().stream().map(Class::getSimpleName).toList();

        assertTrue(found.size() >= 8,
                "Expected to find all of the wrapper classes but only saw " + found
                        + ". The scan is looking in the wrong directory.");
        assertTrue(found.contains("WeblogEntryWrapper") && found.contains("WeblogWrapper"),
                "The two largest wrappers must be in the scan: " + found);
    }

    @TestFactory
    Stream<DynamicTest> noWrapperExposesAMutator() {
        return wrapperClasses().stream().map(wrapper -> DynamicTest.dynamicTest(
                wrapper.getSimpleName(), () -> assertExposesNoMutator(wrapper)));
    }

    private static void assertExposesNoMutator(Class<?> wrapper) {
        List<String> offenders = new ArrayList<>();
        for (Method method : wrapper.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            String name = method.getName();
            boolean readOnly = name.startsWith("get") || name.startsWith("is")
                    || ALLOWED_NON_GETTERS.contains(name);
            if (!readOnly) {
                offenders.add(name);
            }
        }

        assertTrue(offenders.isEmpty(),
                wrapper.getSimpleName() + " exposes " + offenders + ". Wrappers are handed "
                        + "to Velocity, which resolves methods reflectively, so anything "
                        + "public here is callable from a theme template. Move the mutator "
                        + "back onto the pojo, or add it to ALLOWED_NON_GETTERS if it is "
                        + "genuinely read-only despite its name.");
    }

    @TestFactory
    Stream<DynamicTest> everyWrapperIsFinalAndOnlyConstructibleThroughWrap() {
        return wrapperClasses().stream().map(wrapper -> DynamicTest.dynamicTest(
                wrapper.getSimpleName(), () -> {
                    assertTrue(Modifier.isFinal(wrapper.getModifiers()),
                            wrapper.getSimpleName() + " must be final: a subclass could "
                                    + "override an accessor and hand a theme something "
                                    + "other than the wrapped pojo's value");

                    for (Constructor<?> constructor : wrapper.getDeclaredConstructors()) {
                        assertFalse(Modifier.isPublic(constructor.getModifiers()),
                                wrapper.getSimpleName() + " has a public constructor. Every "
                                        + "wrapper must be built through wrap(), which is "
                                        + "what turns a null pojo into a null wrapper "
                                        + "instead of a wrapper that NPEs on first use.");
                    }
                }));
    }

    @TestFactory
    Stream<DynamicTest> wrappingNullYieldsNullRatherThanAWrapperAroundNothing() {
        // Templates do `#if($entry.category)`; a wrapper around a null pojo
        // would test as true and then blow up on the first accessor.
        return wrapperClasses().stream().map(wrapper -> DynamicTest.dynamicTest(
                wrapper.getSimpleName(), () -> {
                    Method wrap = findWrapMethod(wrapper);
                    Object[] args = new Object[wrap.getParameterCount()];
                    assertEquals(null, wrap.invoke(null, args),
                            wrapper.getSimpleName() + ".wrap(null) must return null so a "
                                    + "template can test the value for presence");
                }));
    }

    private static Method findWrapMethod(Class<?> wrapper) {
        for (Method method : wrapper.getDeclaredMethods()) {
            if ("wrap".equals(method.getName()) && Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        throw new AssertionError(wrapper.getSimpleName() + " has no static wrap() factory; "
                + "every wrapper needs one so callers cannot construct a wrapper around null.");
    }

    @TestFactory
    Stream<DynamicTest> wrappersDoNotLeakTheirPojoExceptThroughGetPojo() {
        // getPojo() is the deliberate, documented escape hatch used by the
        // rendering internals. Any *other* accessor returning a raw pojo would
        // be an accidental hole through the read-only barrier.
        return wrapperClasses().stream().map(wrapper -> DynamicTest.dynamicTest(
                wrapper.getSimpleName(), () -> {
                    List<String> leaks = new ArrayList<>();
                    for (Method method : wrapper.getDeclaredMethods()) {
                        if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()
                                || "getPojo".equals(method.getName())) {
                            continue;
                        }
                        Class<?> returned = method.getReturnType();
                        if (returned.getPackageName().equals("com.wikantik.pressroom.pojos")
                                && !returned.isEnum()) {
                            leaks.add(method.getName() + " -> " + returned.getSimpleName());
                        }
                    }
                    assertTrue(leaks.isEmpty(),
                            wrapper.getSimpleName() + " hands out unwrapped domain objects "
                                    + "from " + leaks + ". A template holding a real pojo "
                                    + "can call its setters; return the wrapper type instead, "
                                    + "or route the access through getPojo() explicitly.");
                }));
    }
}
