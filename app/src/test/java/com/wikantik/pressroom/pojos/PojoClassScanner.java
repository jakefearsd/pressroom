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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Enumerates the compiled classes of a package so tests can assert things about
 * <em>every</em> member of it rather than the handful somebody remembered.
 *
 * <p>Tests that hard-code a list of classes stop covering new code the moment
 * someone adds a class; the checks built on this scanner fail loudly instead.
 * The package directory is located through the classloader rather than a
 * relative path so it works the same under Surefire and under a mutation
 * testing run, which use different working directories.
 */
final class PojoClassScanner {

    static final String POJOS = "com.wikantik.pressroom.pojos";
    static final String WRAPPERS = "com.wikantik.pressroom.pojos.wrapper";

    private PojoClassScanner() {
    }

    /**
     * All production classes directly in {@code packageName}, nested types
     * included.
     *
     * <p>The directory is resolved from the code source of a class known to be
     * production code rather than from the classloader: the test tree shares
     * these package names, and a classloader lookup would find
     * {@code target/test-classes} first and hand back the tests themselves.
     */
    static List<Class<?>> classesIn(String packageName) {
        URL codeSource = Weblog.class.getProtectionDomain().getCodeSource().getLocation();
        Path directory;
        try {
            directory = Path.of(codeSource.toURI()).resolve(packageName.replace('.', '/'));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Could not read " + codeSource, e);
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Compiled classes for " + packageName
                    + " are not at " + directory + "; the scanner is looking in the wrong place.");
        }

        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".class")) {
                    continue;
                }
                String className = packageName + "." + name.substring(0, name.length() - ".class".length());
                try {
                    classes.add(Class.forName(className));
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    throw new IllegalStateException("Could not load " + className, e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return classes;
    }

    /**
     * The classes that override {@code equals} themselves, i.e. the ones whose
     * equality contract is Roller's responsibility rather than the JDK's.
     */
    static List<Class<?>> classesDeclaringEquals() {
        List<Class<?>> declaring = new ArrayList<>();
        for (Class<?> candidate : Stream.concat(
                classesIn(POJOS).stream(), classesIn(WRAPPERS).stream()).toList()) {
            if (declaresEquals(candidate)) {
                declaring.add(candidate);
            }
        }
        return declaring;
    }

    private static boolean declaresEquals(Class<?> candidate) {
        try {
            candidate.getDeclaredMethod("equals", Object.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
