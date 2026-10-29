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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the PostgreSQL major version across every place that names it, and the
 * two things that must move with it.
 *
 * <p>Four files name the server: both compose files, this module's
 * Testcontainers constant, and the browser harness's image property. They
 * drifted apart once already -- the compose files pinned a digest while the
 * two test harnesses floated on a bare tag -- and nothing failed, because a
 * test suite passing on a different major than production is exactly the kind
 * of difference that surfaces in production instead.
 *
 * <p>Two further things are version-coupled, and both fail in ways that do not
 * look like a version problem:
 *
 * <ul>
 *   <li><b>The data directory moved in 18.</b> {@code PGDATA} became
 *       {@code /var/lib/postgresql/<major>/docker} and the declared
 *       {@code VOLUME} became the parent, {@code /var/lib/postgresql}. A
 *       compose file still mounting the pre-18 path gets a container that
 *       refuses to start; one that mounts a pre-18 cluster at the new path
 *       gets an entrypoint error about old databases. Either is loud, but
 *       neither says "your compose file is a major behind".</li>
 *   <li><b>{@code pg_dump} refuses a server newer than itself.</b> The app
 *       image is also the backup runner, so its client floor must be at least
 *       the server major, or the nightly dump fails at 03:00 rather than at
 *       build time. The floor is declared once in the Dockerfile as
 *       {@code PG_CLIENT_MIN_MAJOR} so it can be checked here rather than
 *       hidden inside a regular expression's bounds.</li>
 * </ul>
 */
class PostgresMajorPinTest {

    /** The major at which the image's data directory moved up one level. */
    private static final int VERSIONED_DATA_DIR_SINCE = 18;

    private static final Path DEV_COMPOSE = Paths.get("../docker-compose.yml");
    private static final Path PROD_COMPOSE = Paths.get("../docker-compose.prod.yml");
    private static final Path IT_POM = Paths.get("../it-selenium/pom.xml");
    private static final Path DOCKERFILE = Paths.get("../Dockerfile");
    private static final Path CONTAINER_SOURCE =
            Paths.get("src/test/java/com/wikantik/pressroom/testing/PressroomPostgresContainer.java");

    private static final Pattern IMAGE_MAJOR = Pattern.compile("postgres:(\\d+)");
    private static final Pattern CONTAINER_IMAGE =
            Pattern.compile("IMAGE\\s*=\\s*\"postgres:(\\d+)");
    private static final Pattern IT_IMAGE =
            Pattern.compile("<it\\.postgres\\.image>postgres:(\\d+)");
    private static final Pattern CLIENT_FLOOR =
            Pattern.compile("ARG\\s+PG_CLIENT_MIN_MAJOR=(\\d+)");

    @Test
    void everyPlaceThatNamesPostgresNamesTheSameMajor() throws IOException {
        Map<String, Integer> majors = new LinkedHashMap<>();
        majors.put("docker-compose.yml", majorIn(postgresImage(DEV_COMPOSE), IMAGE_MAJOR,
                "docker-compose.yml postgres image"));
        majors.put("docker-compose.prod.yml", majorIn(postgresImage(PROD_COMPOSE), IMAGE_MAJOR,
                "docker-compose.prod.yml postgres image"));
        majors.put("PressroomPostgresContainer", majorIn(read(CONTAINER_SOURCE), CONTAINER_IMAGE,
                "PressroomPostgresContainer's IMAGE constant"));
        majors.put("it-selenium/pom.xml", majorIn(read(IT_POM), IT_IMAGE,
                "it-selenium's it.postgres.image property"));

        assertEquals(1, Set.copyOf(majors.values()).size(),
                "every place that names PostgreSQL must name the same major, or the suite "
                        + "proves nothing about the engine production runs: " + majors);
    }

    @Test
    void eachComposeFileMountsTheDataVolumeWhereItsImageKeepsIt() throws IOException {
        for (Path compose : List.of(DEV_COMPOSE, PROD_COMPOSE)) {
            int major = majorIn(postgresImage(compose), IMAGE_MAJOR, compose + " postgres image");
            String expected = major >= VERSIONED_DATA_DIR_SINCE
                    ? "/var/lib/postgresql"
                    : "/var/lib/postgresql/data";
            assertEquals(expected, dataMountTarget(compose),
                    compose + " runs postgres " + major + ", whose data directory lives at "
                            + expected + "; mounting the other path is a container that will "
                            + "not start");
        }
    }

    @Test
    void theAppImagesClientFloorIsAtLeastTheServerMajor() throws IOException {
        int server = majorIn(postgresImage(PROD_COMPOSE), IMAGE_MAJOR, "production postgres image");
        int floor = majorIn(read(DOCKERFILE), CLIENT_FLOOR, "Dockerfile PG_CLIENT_MIN_MAJOR");

        assertTrue(floor >= server,
                "the app image is also the backup runner, and pg_dump refuses a server newer "
                        + "than itself: the client floor is " + floor + " but production runs "
                        + server + ", so backups would fail at 03:00 rather than at build time");
    }

    @Test
    void bothComposeFilesPinPostgresByDigest() throws IOException {
        for (Path compose : List.of(DEV_COMPOSE, PROD_COMPOSE)) {
            assertTrue(postgresImage(compose).contains("@sha256:"),
                    compose + " must pin the postgres image by digest, not by tag alone");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "missing " + path.toAbsolutePath());
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> postgresService(Path compose) throws IOException {
        assertTrue(Files.exists(compose), "missing " + compose.toAbsolutePath());
        Map<String, Object> parsed;
        try (InputStream in = Files.newInputStream(compose)) {
            parsed = (Map<String, Object>) new Yaml().load(in);
        }
        Map<String, Object> services = (Map<String, Object>) parsed.get("services");
        Map<String, Object> postgres = (Map<String, Object>) services.get("postgres");
        assertTrue(postgres != null, "no postgres service in " + compose);
        return postgres;
    }

    private static String postgresImage(Path compose) throws IOException {
        return String.valueOf(postgresService(compose).get("image"));
    }

    /** The container-side path the postgres data volume is mounted at. */
    private static String dataMountTarget(Path compose) throws IOException {
        Object volumes = postgresService(compose).get("volumes");
        if (!(volumes instanceof List<?> list) || list.isEmpty()) {
            fail(compose + "'s postgres service declares no data volume");
            return "";
        }
        Object first = list.get(0);
        if (first instanceof Map<?, ?> longForm) {
            return String.valueOf(longForm.get("target"));
        }
        String[] parts = String.valueOf(first).split(":");
        assertEquals(2, parts.length, compose + "'s data volume must be source:target, not " + first);
        return parts[1];
    }

    private static int majorIn(String text, Pattern pattern, String what) {
        Matcher matcher = pattern.matcher(text);
        assertTrue(matcher.find(), "could not read a PostgreSQL major from " + what);
        return Integer.parseInt(matcher.group(1));
    }
}
