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

import com.wikantik.pressroom.testsupport.JavaSourceText;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final Path POJOS = Path.of("src/main/java/com/wikantik/pressroom/pojos");
    /** Any mention of the library in code: an import (plain, static, wildcard) or an inline qualified name. */
    private static final Pattern SWAGGER_REFERENCE = Pattern.compile("(?m)^.*\\bio\\.swagger\\..*$");

    /** The lines of {@code source} that name io.swagger, comments excluded (they may name it in prose). */
    static java.util.List<String> swaggerReferences(String source) {
        String code = JavaSourceText.code(source);
        Matcher m = SWAGGER_REFERENCE.matcher(code);
        java.util.List<String> lines = new java.util.ArrayList<>();
        while (m.find()) {
            lines.add(m.group().strip());
        }
        return lines;
    }

    /** Planted offenders: each spelling is found once, prose in a comment never. */
    @Test
    void theSwaggerScanSeesEverySpellingAndIgnoresComments() {
        assertEquals(1, swaggerReferences("import io.swagger.v3.oas.annotations.media.Schema;\nclass A {}").size());
        assertEquals(1, swaggerReferences("import static io.swagger.v3.Foo.bar;\nclass A {}").size());
        assertEquals(1, swaggerReferences("import io.swagger.v3.oas.annotations.media.*;\nclass A {}").size());
        assertEquals(1, swaggerReferences("class A { @io.swagger.v3.oas.annotations.media.Schema(name = \"x\") int f; }").size());
        assertEquals(0, swaggerReferences("/* see io.swagger.v3.Foo */\n// io.swagger.v3.Bar\nclass A {}").size());
        // A comment marker inside a literal is not a comment: "*/*" once hid the lines after it.
        assertEquals(1, swaggerReferences(
                "class A { String t = \"*/*\";\n@io.swagger.v3.Foo int f; /* x */ }").size());
        assertEquals(1, swaggerReferences(
                "class A { String u = \"http://x\"; @io.swagger.v3.Foo int f; }").size());
    }

    /**
     * The business tier and the entities describe no API: a swagger annotation
     * there ties the domain to the OpenAPI library. A description of a business
     * type's field belongs in {@code ApiDocumentCustomizer}, on the API side.
     */
    @Test
    void noBusinessOrPojoClassImportsSwagger() throws IOException {
        Set<String> found = new TreeSet<>();
        long scanned = 0;
        for (Path root : new Path[] {BUSINESS, POJOS}) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    scanned++;
                    for (String line : swaggerReferences(Files.readString(file, StandardCharsets.UTF_8))) {
                        found.add(file + " references io.swagger: " + line);
                    }
                }
            }
        }
        assertTrue(scanned > 50, "the scan read the sources: " + scanned);
        assertEquals(Set.of(), found);
    }
}
