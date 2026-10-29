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
package com.wikantik.pressroom.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every admin list table scrolls inside its own wrapper at phone width.
 *
 * <p>An unwrapped {@code .rollertable} widened the whole page instead: at
 * 390px Entries measured 579px and Comments 457px, with the row actions
 * (Approve/Hide/Delete, Revoke) off-screen. Wrapping was done screen by
 * screen and kept missing screens, so this scans the tree: a new list table
 * is covered the day it lands.
 */
class PressroomTableScrollJspTest {

    private static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps");

    private static final Pattern TABLE = Pattern.compile("<table\\b[^>]*\\bclass=\"rollertable\\b");

    /** Whitespace and JSP comments may sit between the wrapper and its table. */
    private static final Pattern WRAPPED = Pattern.compile(
            "<div class=\"table-responsive\">(?:\\s|<%--(?:(?!--%>).)*--%>)*$", Pattern.DOTALL);

    @Test
    void everyRollerTableSitsDirectlyInsideATableResponsiveWrapper() throws IOException {
        List<String> offenders = new ArrayList<>();
        int tables = 0;
        try (Stream<Path> files = Files.walk(JSPS)) {
            for (Path jsp : files.filter(p -> p.toString().endsWith(".jsp")).sorted().toList()) {
                String src = Files.readString(jsp, StandardCharsets.UTF_8);
                Matcher m = TABLE.matcher(src);
                while (m.find()) {
                    tables++;
                    String before = src.substring(Math.max(0, m.start() - 2000), m.start());
                    if (!WRAPPED.matcher(before).find()) {
                        offenders.add(JSPS.relativize(jsp) + ":" + lineOf(src, m.start()));
                    }
                }
            }
        }
        assertTrue(tables > 10, "found only " + tables + " list tables; is the scan looking in the right place?");
        assertTrue(offenders.isEmpty(),
                "list tables not wrapped in <div class=\"table-responsive\">:\n  " + String.join("\n  ", offenders));
    }

    private static int lineOf(String src, int offset) {
        return src.substring(0, offset).split("\n", -1).length;
    }
}
