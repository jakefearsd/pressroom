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
package com.wikantik.pressroom.business.startup;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MigrationCatalog#versions()} itself is covered end to end by
 * {@link SchemaMigrationTest} (a real classpath directory of migrations).
 * {@link MigrationCatalog#fileNameOf} is pinned directly here for the one
 * case a real directory-stream entry cannot produce: a path with no name
 * elements, where {@code Path.getFileName()} returns null
 * (NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE).
 *
 * <p>The class-loader tests (a packaged {@code jar:} classpath, as in the
 * executable WAR, plus the fallback and failure cases) are characterisation
 * tests, written against the existing behaviour and expected to pass
 * immediately. Each swaps the thread's context class loader for one with no
 * parent, so the real {@code /dbmigrations} directory cannot answer instead.
 */
class MigrationCatalogTest {

    @Test
    void fileNameOfReturnsTheLastElement() {
        assertEquals("V001__init.sql", MigrationCatalog.fileNameOf(Path.of("/dbmigrations/V001__init.sql")));
    }

    @Test
    void fileNameOfIsNullForAZeroElementPath() {
        assertNull(MigrationCatalog.fileNameOf(Path.of("/")));
    }

    @Test
    void versionsListsTheRealMigrationsOnTheClasspath() {
        List<String> versions = MigrationCatalog.versions();
        assertFalse(versions.isEmpty(), "the build must copy bin/db/migrations onto the classpath");
    }

    @Test
    void aPackagedClasspathListsOnlyWellFormedMigrationsUnderTheDirectoryInOrder(@TempDir Path tmp)
            throws Exception {
        Path jar = jarWith(tmp.resolve("migrations.jar"),
                "dbmigrations/",
                "dbmigrations/V002__second_step.sql",
                "dbmigrations/V001__first_step.sql",
                "dbmigrations/README.md",
                "dbmigrations/V3__not_padded.sql",
                "other/V004__elsewhere.sql");

        List<String> versions = withContextLoader(jar.toUri().toURL(), MigrationCatalog::versions);

        assertEquals(List.of("V001__first_step", "V002__second_step"), versions);
    }

    @Test
    void aPackagedDirectoryWithNoMigrationsIsRefused(@TempDir Path tmp) throws Exception {
        Path jar = jarWith(tmp.resolve("empty.jar"), "dbmigrations/", "dbmigrations/README.md");
        URL url = jar.toUri().toURL();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> withContextLoader(url, MigrationCatalog::versions));

        assertEquals("Found /dbmigrations on the classpath but it contains no "
                + "V<NNN>__<description>.sql files.", refused.getMessage());
    }

    @Test
    void aDbmigrationsEntryThatIsNotADirectoryIsReportedWithItsLocation(@TempDir Path tmp)
            throws Exception {
        Files.writeString(tmp.resolve("dbmigrations"), "not a directory");
        URL url = tmp.toUri().toURL();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> withContextLoader(url, MigrationCatalog::versions));

        assertTrue(refused.getMessage().startsWith("Could not list migrations at file:"),
                refused.getMessage());
        assertTrue(refused.getMessage().endsWith("/dbmigrations"), refused.getMessage());
        assertInstanceOf(NotDirectoryException.class, refused.getCause());
    }

    @Test
    void aContextLoaderWithoutMigrationsFallsBackToTheCatalogsOwnLoader(@TempDir Path tmp)
            throws Exception {
        List<String> real = MigrationCatalog.versions();

        List<String> viaFallback = withContextLoader(tmp.toUri().toURL(), MigrationCatalog::versions);

        assertEquals(real, viaFallback);
    }

    private static Path jarWith(Path jar, String... entries) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String entry : entries) {
                out.putNextEntry(new JarEntry(entry));
                if (!entry.endsWith("/")) {
                    out.write("SELECT 1;".getBytes(StandardCharsets.UTF_8));
                }
                out.closeEntry();
            }
        }
        return jar;
    }

    /** Runs {@code action} with a parentless context class loader over {@code root}, then restores it. */
    private static <T> T withContextLoader(URL root, Supplier<T> action) throws IOException {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{root}, null)) {
            thread.setContextClassLoader(loader);
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
