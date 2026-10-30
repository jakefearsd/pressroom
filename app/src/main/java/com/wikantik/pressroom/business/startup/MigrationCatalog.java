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
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Lists the schema migrations available on the classpath.
 *
 * <p>The canonical migrations live in {@code bin/db/migrations}; the build
 * copies them to {@code /dbmigrations} on the classpath so they ship inside the
 * WAR and can be applied by {@link DatabaseInstaller}. {@code bin/db/migrate.sh}
 * reads the originals directly.
 *
 * <p>Resolves both an exploded classpath ({@code file:}, used by tests and by
 * servlet containers that unpack the WAR) and a packaged one ({@code jar:}).
 */
public final class MigrationCatalog {

    /** Classpath directory the build copies bin/db/migrations into. */
    static final String MIGRATIONS_PATH = "dbmigrations";

    /** Matches V001__description.sql and captures nothing; ordering is lexical. */
    private static final Pattern MIGRATION_FILE = Pattern.compile("^V\\d{3}__[a-z0-9_]+\\.sql$");

    private MigrationCatalog() {
    }

    /**
     * Migration version names (file names without {@code .sql}), in the order
     * they must be applied. Zero-padded numbering makes lexical order correct.
     *
     * @throws IllegalStateException if the migrations are missing from the
     *         classpath, which means the build did not copy them and the
     *         application would otherwise silently skip all schema setup.
     */
    @SuppressWarnings("PMD.UseProperClassLoader") // the context loader is tried first; this is the fallback
    public static List<String> versions() {
        URL dir = Thread.currentThread().getContextClassLoader().getResource(MIGRATIONS_PATH);
        if (dir == null) {
            dir = MigrationCatalog.class.getClassLoader().getResource(MIGRATIONS_PATH);
        }
        if (dir == null) {
            throw new IllegalStateException(
                    "No /" + MIGRATIONS_PATH + " directory on the classpath. The build copies "
                    + "bin/db/migrations there; without it Pressroom cannot create or migrate its "
                    + "schema.");
        }

        List<String> versions;
        try {
            versions = "jar".equals(dir.getProtocol()) ? listFromJar(dir) : listFromDirectory(dir);
        } catch (IOException | java.net.URISyntaxException e) {
            throw new IllegalStateException("Could not list migrations at " + dir, e);
        }

        if (versions.isEmpty()) {
            throw new IllegalStateException(
                    "Found /" + MIGRATIONS_PATH + " on the classpath but it contains no "
                    + "V<NNN>__<description>.sql files.");
        }

        Collections.sort(versions);
        return versions;
    }

    @SuppressFBWarnings(
            value = "PATH_TRAVERSAL_IN",
            justification = "dir is ClassLoader.getResource for the migrations folder, a location inside "
                    + "the application; no request reaches it.")
    private static List<String> listFromDirectory(URL dir)
            throws IOException, java.net.URISyntaxException {
        List<String> versions = new ArrayList<>();
        Path path = Paths.get(dir.toURI());
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path entry : stream) {
                String name = fileNameOf(entry);
                if (name != null && MIGRATION_FILE.matcher(name).matches()) {
                    versions.add(stripSuffix(name));
                }
            }
        }
        return versions;
    }

    @SuppressFBWarnings(
            value = "URLCONNECTION_SSRF_FD",
            justification = "dir is ClassLoader.getResource for the migrations folder, a jar: URL inside "
                    + "the application; the connection opens the application's own archive, never a "
                    + "network address.")
    private static List<String> listFromJar(URL dir) throws IOException {
        List<String> versions = new ArrayList<>();
        JarURLConnection connection = (JarURLConnection) dir.openConnection();
        try (JarFile jar = connection.getJarFile()) {
            String prefix = MIGRATIONS_PATH + "/";
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String entryName = entries.nextElement().getName();
                if (!entryName.startsWith(prefix)) {
                    continue;
                }
                String name = entryName.substring(prefix.length());
                if (MIGRATION_FILE.matcher(name).matches()) {
                    versions.add(stripSuffix(name));
                }
            }
        }
        return versions;
    }

    private static String stripSuffix(String fileName) {
        return fileName.substring(0, fileName.length() - ".sql".length());
    }

    /**
     * {@code Path.getFileName()} is null only for a zero-element path, which
     * a directory-stream entry never is in practice -- but package-private
     * rather than inlined so {@code MigrationCatalogTest} can pin the null
     * case directly (e.g. {@code Path.of("/")}) without needing a directory
     * entry that cannot actually occur.
     */
    // Package-private for test access to a defensive branch -- not ordinary API.
    static String fileNameOf(Path entry) {
        Path fileName = entry.getFileName();
        return fileName == null ? null : fileName.toString();
    }
}
