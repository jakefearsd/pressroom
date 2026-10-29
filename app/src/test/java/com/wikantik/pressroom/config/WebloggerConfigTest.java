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
package com.wikantik.pressroom.config;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link WebloggerConfig#runAtClassLoad}, the catch-clause logic (PMD
 * AvoidInstanceofChecksInCatchClause) extracted from the class's static
 * initializer.
 *
 * <p>The initializer itself runs exactly once per JVM, at class-load, before
 * this or any other test can intervene -- by the time any test method runs,
 * {@code WebloggerConfig} is already loaded and its static block has already
 * either succeeded or (fatally, since nothing would have loaded) failed. This
 * extraction is the only way to drive the two catch clauses directly, per
 * CLAUDE.md's "Policy: defensive branches and the diff-coverage gate".
 */
class WebloggerConfigTest {

    @Test
    void aRuntimeExceptionEscapesRatherThanBeingSwallowed() {
        assertThrows(IllegalStateException.class, () ->
                WebloggerConfig.runAtClassLoad(() -> {
                    throw new IllegalStateException("programming error");
                }),
                "a RuntimeException is a programming or configuration error and must not boot "
                        + "silently");
    }

    @Test
    void aCheckedExceptionIsLoggedAndSwallowed() {
        assertDoesNotThrow(() ->
                WebloggerConfig.runAtClassLoad(() -> {
                    throw new IOException("missing optional file");
                }),
                "a checked exception (e.g. a missing optional config file) must not abort class "
                        + "loading");
    }

    @Test
    void workThatSucceedsRunsNormally() {
        boolean[] ran = {false};
        assertDoesNotThrow(() -> WebloggerConfig.runAtClassLoad(() -> ran[0] = true));
        org.junit.jupiter.api.Assertions.assertTrue(ran[0]);
    }

    /**
     * Pins that loading the configuration layers calls the legacy-name guard
     * first: deleting that call must fail this test. The guard runs before any
     * file is read, so the test needs no properties files.
     */
    @Test
    void loadingRefusesAnOldName() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> WebloggerConfig.loadLayers(java.util.Map.of("ROLLER_X", "1"), // rebrand:keep
                        new java.util.Properties()));

        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("ROLLER_X"), e.getMessage()); // rebrand:keep
    }
}
