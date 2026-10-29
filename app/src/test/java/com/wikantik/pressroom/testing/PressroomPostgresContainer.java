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

import org.testcontainers.lifecycle.Startable;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Lazily started PostgreSQL container shared by every test in the JVM.
 *
 * <p>Roller became PostgreSQL-only during this fork's simplification, and
 * tests run against the same database engine as production rather than
 * the embedded Derby they used before. The container's schema is built by
 * applying the real {@code bin/db/migrations} chain (see
 * {@link PressroomDatabaseExtension}), so there is no second, hand-maintained
 * test schema to drift out of sync.
 *
 * <p>One container per JVM, not per class: starting Postgres costs a second or
 * two, and {@link PressroomDatabaseExtension} gives each test a clean database by
 * truncating rather than by recreating the container.
 *
 * <p>Nothing stops it, by design: the container lives as long as the JVM does,
 * and Testcontainers' Ryuk sidecar removes it afterwards. The shutdown hook
 * below does not change that contract, it just stops relying on Ryuk being the
 * <em>only</em> thing that can clean up. Ryuk is a single process that can be
 * absent, and orphaned testcontainers containers with no Ryuk in existence
 * have been seen on developer machines. The hook covers every exit the JVM
 * gets to observe; Ryuk still covers the ones it does not (SIGKILL, a crash).
 */
public final class PressroomPostgresContainer {

    /** Pinned to match docker-compose.yml. */
    private static final String IMAGE = "postgres:18";

    private static volatile PostgreSQLContainer container;

    private static volatile Thread shutdownHook;

    private PressroomPostgresContainer() {
    }

    public static PostgreSQLContainer get() {
        if (container == null) {
            synchronized (PressroomPostgresContainer.class) {
                if (container == null) {
                    PostgreSQLContainer started = new PostgreSQLContainer(IMAGE)
                            .withDatabaseName("rollerdb")
                            .withUsername("roller")
                            .withPassword("roller");
                    started.start();
                    Thread hook = new Thread(() -> stopQuietly(started), "roller-postgres-container-stop");
                    Runtime.getRuntime().addShutdownHook(hook);
                    shutdownHook = hook;
                    container = started;
                }
            }
        }
        return container;
    }

    /** The registered hook, or null if the container has never been started. */
    static Thread shutdownHook() {
        return shutdownHook;
    }

    /**
     * Stops a container without letting anything escape into JVM shutdown. By
     * the time this runs the daemon may be gone, the container may already have
     * been removed by Ryuk, or the network may be torn down; none of that is
     * worth a stack trace on the way out, and a hook that throws can mask why
     * the JVM is exiting.
     */
    static void stopQuietly(Startable target) {
        try {
            target.stop();
        } catch (RuntimeException | Error problem) {
            System.err.println("PressroomPostgresContainer: could not stop the test container on shutdown ("
                    + problem + "); Testcontainers' Ryuk sidecar should remove it.");
        }
    }

    public static String getJdbcUrl() {
        return get().getJdbcUrl();
    }

    public static String getUsername() {
        return get().getUsername();
    }

    public static String getPassword() {
        return get().getPassword();
    }
}
