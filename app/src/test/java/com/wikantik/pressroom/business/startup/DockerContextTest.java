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
package com.wikantik.pressroom.business.startup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that {@code .dockerignore} excludes the Node artifacts
 * {@code frontend-maven-plugin} produces under {@code app/frontend/}.
 *
 * <p>{@code Dockerfile} COPYs the whole {@code app/} tree into the builder
 * stage, and {@code .dockerignore} only ever excluded {@code app/target/}.
 * On a dev checkout that already has {@code app/frontend/node/} (the pinned
 * Node runtime the plugin downloads, ~138 MB) and
 * {@code app/frontend/node_modules/} on disk, both get pulled into the build
 * context -- and the host's already-extracted Node lands at exactly the path
 * the plugin checks before downloading its own, so the builder image quietly
 * runs a binary built for the host platform instead of the one it would have
 * fetched itself. Same failure class {@link ProductionComposeTest} guards
 * for compose: a change that works on the machine that made it and fails
 * only somewhere else.
 */
class DockerContextTest {

    private static final Path DOCKERIGNORE = Paths.get("../.dockerignore");

    @Test
    void dockerignoreExcludesFrontendNodeArtifacts() throws IOException {
        assertTrue(Files.exists(DOCKERIGNORE), "Expected to find " + DOCKERIGNORE.toAbsolutePath());
        List<String> lines = Files.readAllLines(DOCKERIGNORE, StandardCharsets.UTF_8);

        assertTrue(lines.stream().anyMatch(line -> line.strip().equals("app/frontend/node/")),
                DOCKERIGNORE + " must exclude app/frontend/node/ (the pinned Node runtime "
                        + "frontend-maven-plugin downloads) -- otherwise a dev checkout's "
                        + "host-platform Node lands in the build context at exactly the path "
                        + "the plugin checks before downloading its own.");
        assertTrue(lines.stream().anyMatch(line -> line.strip().equals("app/frontend/node_modules/")),
                DOCKERIGNORE + " must exclude app/frontend/node_modules/ (npm packages "
                        + "installed on the host) for the same reason.");
    }
}
