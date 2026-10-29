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
package com.wikantik.pressroom.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The admin UI loads jQuery 4, which removed the utilities jQuery 3 had
 * deprecated. A call to one is a TypeError the moment it runs -- and the
 * page's other start-up code in the same ready handler never runs after it --
 * while every source-level test of the page still passes. The SEO counters
 * shipped with {@code $.trim} once; the browser is the only other place this
 * would have shown.
 */
class JQueryRemovedApiTest {

    private static final Path WEBAPP = Paths.get("src/main/webapp");

    /** jQuery 4.0's removals that a page script could still reach for. */
    private static final Pattern REMOVED = Pattern.compile(
            "(?:\\$|jQuery)\\.(trim|isArray|parseJSON|type|now|isNumeric|isFunction|isWindow|camelCase|"
                    + "nodeName|unique|holdReady|proxy)\\s*\\(");

    @Test
    void noPageScriptCallsAnApiJQueryFourRemoved() throws IOException {
        List<String> calls = new ArrayList<>();
        try (Stream<Path> files = Files.walk(WEBAPP)) {
            for (Path file : files.filter(JQueryRemovedApiTest::isPageSource).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher call = REMOVED.matcher(source);
                while (call.find()) {
                    calls.add(WEBAPP.relativize(file) + ": " + call.group());
                }
            }
        }

        assertEquals(List.of(), calls, "jQuery 4 has none of these; use the native equivalent "
                + "(String.prototype.trim, Array.isArray, JSON.parse, Date.now, typeof)");
    }

    /** JSPs and hand-written scripts; the esbuild bundles are generated from app/frontend. */
    private static boolean isPageSource(Path file) {
        String name = file.getFileName().toString();
        return (name.endsWith(".jsp") || name.endsWith(".js"))
                && !name.equals("pressroom-editor.js") && !name.equals("pressroom-content.js")
                && !file.toString().contains("node_modules");
    }
}
