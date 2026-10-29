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

import org.junit.jupiter.api.Test;
import org.testcontainers.lifecycle.Startable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The shared container is started and never stopped, which leaves Ryuk as the
 * only thing standing between a killed test JVM and an orphaned Postgres
 * container. Ryuk is good, but it is one process that can itself be missing:
 * orphaned testcontainers containers with no Ryuk anywhere have been seen on
 * this machine. A shutdown hook is a second, independent line of defence, not
 * a replacement for the singleton pattern.
 */
class PressroomPostgresContainerTest {

    /**
     * Covers ordinary exits and SIGTERM/SIGINT (Ctrl-C on {@code mvn test}),
     * which is exactly the gap Ryuk covers late and this covers immediately.
     * SIGKILL still runs no hook, and remains Ryuk's job.
     */
    @Test
    void theSharedContainerIsRegisteredForShutdown() {
        PressroomPostgresContainer.get();

        Thread hook = PressroomPostgresContainer.shutdownHook();
        assertNotNull(hook, "no shutdown hook was registered for the shared container");

        boolean removed = Runtime.getRuntime().removeShutdownHook(hook);
        if (removed) {
            Runtime.getRuntime().addShutdownHook(hook);
        }
        assertTrue(removed, "the shared container's shutdown hook is not registered with the runtime");
    }

    /**
     * A hook that throws during shutdown turns a tidy exit into a stack trace
     * and can mask the real reason the JVM is going down. Docker being gone
     * already is the ordinary case here, not an exceptional one.
     */
    @Test
    void aFailingStopNeverEscapesTheHook() {
        Startable unstoppable = mock(Startable.class);
        doThrow(new IllegalStateException("docker daemon is already gone")).when(unstoppable).stop();

        assertDoesNotThrow(() -> PressroomPostgresContainer.stopQuietly(unstoppable));

        verify(unstoppable).stop();
    }
}
