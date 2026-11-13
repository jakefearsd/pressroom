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
package com.wikantik.pressroom.business;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The business tier does not depend on the web tier: no source under
 * {@code business/} imports a {@code com.wikantik.pressroom.ui} type. The
 * search wave's {@code BlogStanding} was the reason this test exists
 * (WeblogEntryManager returned a type from {@code ui.rendering.model}; final
 * review, D1).
 */
class BusinessTierImportsTest {

    private static final Path BUSINESS = Path.of("src/main/java/com/wikantik/pressroom/business");
    private static final Pattern UI_IMPORT = Pattern.compile("(?m)^import\\s+(com\\.wikantik\\.pressroom\\.ui\\.[\\w.]+);");

    /**
     * The one import of the web tier that predates this test, left for its
     * own change: the page manager reads the locale-segment rule of the
     * request mapper. Empty this set when it moves; never add to it.
     */
    private static final Set<String> EXPECTED_LEGACY = Set.of(
            "jpa/JPAWeblogPageManagerImpl.java imports com.wikantik.pressroom.ui.rendering.util.LocaleSegment");

    @Test
    void theBusinessTierImportsNothingFromTheWebTier() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(BUSINESS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                Matcher imported = UI_IMPORT.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (imported.find()) {
                    found.add(BUSINESS.relativize(file).toString().replace('\\', '/') + " imports " + imported.group(1));
                }
            }
        }
        assertEquals(EXPECTED_LEGACY, found);
    }
}
