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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real Umami, for contract tests: the <em>same digest-pinned image</em>
 * {@code docker-compose.prod.yml} runs (read from that file, so the two cannot
 * drift), against its own scratch PostgreSQL on a private Docker network.
 *
 * <p>One instance per JVM, started on first use and never stopped by a test
 * (a shutdown hook and Testcontainers' Ryuk clean up), like
 * {@link PressroomPostgresContainer}. Umami's first boot runs its own
 * migrations and seeds the default {@code admin}/{@code umami} account, which
 * {@link #loginAdmin()} uses; that password exists only in this throwaway
 * container. Test classes share the instance, so each must use its own
 * usernames and website names.
 *
 * <p>Umami's database is separate from the suite's shared container because
 * Umami must reach it by a network alias, and the shared container sits on
 * the default bridge with no alias. It runs the same engine image
 * ({@link PressroomPostgresContainer#IMAGE}).
 */
public final class UmamiContainer {

    /** Relative to the app module, which is the working directory for tests. */
    static final Path PROD_COMPOSE = Paths.get("../docker-compose.prod.yml");

    private static final String DB_ALIAS = "umami-db";
    private static final String DB_NAME = "umami";
    private static final String DB_USER = "umami";
    private static final String DB_PASSWORD = "umami";
    private static final int PORT = 3000;
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static volatile UmamiContainer instance;

    private final Network network;
    private final PostgreSQLContainer database;
    private final GenericContainer<?> umami;

    private UmamiContainer(Network network, PostgreSQLContainer database, GenericContainer<?> umami) {
        this.network = network;
        this.database = database;
        this.umami = umami;
    }

    /** The shared instance, started on first call. */
    public static UmamiContainer get() {
        if (instance == null) {
            synchronized (UmamiContainer.class) {
                if (instance == null) {
                    instance = start();
                }
            }
        }
        return instance;
    }

    private static UmamiContainer start() {
        Network network = Network.newNetwork();
        PostgreSQLContainer database = new PostgreSQLContainer(PressroomPostgresContainer.IMAGE)
                .withNetwork(network)
                .withNetworkAliases(DB_ALIAS)
                .withDatabaseName(DB_NAME)
                .withUsername(DB_USER)
                .withPassword(DB_PASSWORD);
        database.start();

        GenericContainer<?> umami = new GenericContainer<>(DockerImageName.parse(imageFromCompose()));
        umami.withNetwork(network)
                .withExposedPorts(PORT)
                // The production service's environment, minus secrets: the
                // database, a throwaway APP_SECRET, no telemetry, and the
                // same client-IP header so geo lookups behave as in prod.
                .withEnv("DATABASE_URL", "postgresql://" + DB_USER + ":" + DB_PASSWORD + "@"
                        + DB_ALIAS + ":5432/" + DB_NAME)
                .withEnv("DATABASE_TYPE", "postgresql")
                .withEnv("APP_SECRET", UUID.randomUUID().toString().replace("-", ""))
                .withEnv("CLIENT_IP_HEADER", "X-Forwarded-For")
                .withEnv("DISABLE_TELEMETRY", "1")
                .waitingFor(Wait.forHttp("/api/heartbeat").forPort(PORT).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(4)));
        umami.start();

        UmamiContainer started = new UmamiContainer(network, database, umami);
        Runtime.getRuntime().addShutdownHook(new Thread(started::stopQuietly, "umami-container-stop"));
        return started;
    }

    private void stopQuietly() {
        PressroomPostgresContainer.stopQuietly(umami);
        PressroomPostgresContainer.stopQuietly(database);
        try {
            network.close();
        } catch (RuntimeException problem) {
            System.err.println("UmamiContainer: could not remove the test network on shutdown ("
                    + problem + "); Testcontainers' Ryuk sidecar should remove it.");
        }
    }

    /** The {@code umami} service's image reference in {@code docker-compose.prod.yml}, digest included. */
    @SuppressWarnings("unchecked")
    public static String imageFromCompose() {
        Map<String, Object> parsed;
        try (InputStream in = Files.newInputStream(PROD_COMPOSE)) {
            parsed = (Map<String, Object>) new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + PROD_COMPOSE.toAbsolutePath(), e);
        }
        Map<String, Object> services = (Map<String, Object>) parsed.get("services");
        Map<String, Object> service = (Map<String, Object>) services.get("umami");
        if (service == null || !(service.get("image") instanceof String image) || !image.contains("@sha256:")) {
            throw new IllegalStateException(PROD_COMPOSE + " has no digest-pinned umami image");
        }
        return image;
    }

    /** Umami's root URL as seen from the test JVM, with no trailing slash. */
    public String baseUrl() {
        return "http://" + umami.getHost() + ":" + umami.getMappedPort(PORT);
    }

    /** A bearer token for the image's default admin. Contract tests only. */
    public String loginAdmin() {
        return login("admin", "umami");
    }

    /**
     * {@code POST /api/auth/login}; the bearer token, or an
     * {@link IllegalStateException} naming the status (never the password).
     */
    public String login(String username, String password) {
        String body = JSON.writeValueAsString(Map.of("username", username, "password", password));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("login as " + username + " answered " + response.statusCode());
            }
            JsonNode token = JSON.readTree(response.body()).get("token");
            if (token == null || token.asString().isBlank()) {
                throw new IllegalStateException("login as " + username + " returned no token");
            }
            return token.asString();
        } catch (IOException e) {
            throw new IllegalStateException("login as " + username + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted logging in as " + username, e);
        }
    }

    /**
     * Everything Umami's PostgreSQL has written to stdout and stderr so far:
     * its server log, which is where a failed statement's text lands.
     */
    public String databaseLogs() {
        return database.getLogs();
    }

    /** A new connection to Umami's own database; the caller closes it. */
    public Connection jdbc() throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    /**
     * Runs a SQL file with {@code psql} inside Umami's database container,
     * as the deploy's one-shot does, with {@code variables} as psql
     * {@code -v name=value} pairs and {@code ON_ERROR_STOP}. A non-zero exit
     * throws with psql's stderr, every variable's value redacted.
     */
    public void psql(Path script, Map<String, String> variables) {
        psql(script, variables, DB_NAME);
    }

    /** {@link #psql(Path, Map)} against another database in Umami's PostgreSQL. */
    public void psql(Path script, Map<String, String> variables, String databaseName) {
        String inContainer = "/tmp/" + UUID.randomUUID() + ".sql";
        database.copyFileToContainer(MountableFile.forHostPath(script), inContainer);
        List<String> command = new ArrayList<>(List.of(
                "psql", "-q", "-X", "-v", "ON_ERROR_STOP=1", "-U", DB_USER, "-d", databaseName));
        variables.forEach((name, value) -> {
            command.add("-v");
            command.add(name + "=" + value);
        });
        command.add("-f");
        command.add(inContainer);
        try {
            Container.ExecResult result = database.execInContainer(command.toArray(String[]::new));
            if (result.getExitCode() != 0) {
                String stderr = result.getStderr();
                for (String value : variables.values()) {
                    stderr = stderr.replace(value, "***");
                }
                throw new IllegalStateException("psql -f " + script + " exited " + result.getExitCode() + ": " + stderr);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not run psql on " + script, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running " + script, e);
        }
    }

    /**
     * Runs a deploy script with {@code bash} inside Umami's database container
     * (it has {@code psql}, as the app image the one-shots run from does),
     * from a fresh directory holding {@code script} and {@code files}, which
     * is also the working directory. The environment is what compose hands a
     * one-shot ({@code UMAMI_PGHOST} this container, {@code UMAMI_DB_USER},
     * {@code UMAMI_DB_PASSWORD}, {@code UMAMI_DB}), overlaid with
     * {@code env}; values travel in the exec's environment, never its command
     * line. Returns the result whatever the exit code.
     *
     * <p>Since 0.7.2 Umami has its own server ({@code umami-db}) and the
     * scripts reach it through {@code UMAMI_PGHOST}, {@code UMAMI_DB_USER}
     * and {@code UMAMI_DB_PASSWORD}. Pressroom's own server settings
     * ({@code PGHOST}, {@code POSTGRES_USER}, {@code POSTGRES_PASSWORD}) are
     * set to decoys that cannot connect, as the backup service hands them
     * over pointing at the other server: a script that still reads them for
     * Umami fails here instead of passing against the wrong database.
     */
    public Container.ExecResult runDeployScript(Path script, Map<String, String> env, Path... files) {
        String dir = "/tmp/" + UUID.randomUUID();
        List<Path> all = new ArrayList<>(List.of(script));
        all.addAll(List.of(files));
        for (Path file : all) {
            database.copyFileToContainer(MountableFile.forHostPath(file), dir + "/" + file.getFileName());
        }
        Map<String, String> environment = new HashMap<>(Map.of(
                "UMAMI_PGHOST", "localhost", "UMAMI_DB_USER", DB_USER, "UMAMI_DB_PASSWORD", DB_PASSWORD,
                "UMAMI_DB", DB_NAME,
                "PGHOST", "pressroom-server.invalid", "POSTGRES_USER", "not-umami",
                "POSTGRES_PASSWORD", "not-umami"));
        environment.putAll(env);
        try {
            return database.execInContainer(ExecConfig.builder()
                    .command(new String[] {"bash", dir + "/" + script.getFileName()})
                    .workDir(dir)
                    .envVars(environment)
                    .build());
        } catch (IOException e) {
            throw new IllegalStateException("could not run " + script, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running " + script, e);
        }
    }

    /** A new connection to another database in Umami's PostgreSQL; the caller closes it. */
    public Connection jdbc(String databaseName) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + database.getHost() + ":"
                + database.getMappedPort(5432) + "/" + databaseName, DB_USER, DB_PASSWORD);
    }
}
