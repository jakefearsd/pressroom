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
package com.wikantik.pressroom.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Locates the canonical migration files in {@code bin/db/migrations}.
 *
 * <p>Tests read the originals from the repository rather than the copies the
 * build places on the classpath, so a test failure points at the file you would
 * actually edit.
 */
public final class MigrationFiles {

    /** Relative to the app module, which is the working directory for tests. */
    private static final Path MIGRATIONS_DIR = Paths.get("../bin/db/migrations");

    private MigrationFiles() {
    }

    /** Every V*.sql migration, in the order they must be applied. */
    public static List<Path> all() {
        if (!Files.isDirectory(MIGRATIONS_DIR)) {
            throw new IllegalStateException(
                    "Migrations directory not found at " + MIGRATIONS_DIR.toAbsolutePath()
                    + ". Tests expect to run with the app module as the working directory.");
        }
        try (Stream<Path> files = Files.list(MIGRATIONS_DIR)) {
            List<Path> migrations = files
                    .filter(p -> p.getFileName().toString().matches("^V\\d{3}__[a-z0-9_]+\\.sql$"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            if (migrations.isEmpty()) {
                throw new IllegalStateException(
                        "No V<NNN>__<description>.sql files in " + MIGRATIONS_DIR.toAbsolutePath());
            }
            return migrations;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list migrations", e);
        }
    }

    /** The directory holding the migrations, for tests that need the path itself. */
    public static Path directory() {
        return MIGRATIONS_DIR;
    }
}
