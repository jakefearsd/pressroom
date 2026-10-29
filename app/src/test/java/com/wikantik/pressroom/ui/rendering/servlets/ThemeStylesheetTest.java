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
package com.wikantik.pressroom.ui.rendering.servlets;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A theme's {@code *-custom.css} is rendered through Velocity like every
 * other theme template (it carries {@code $url.site} interpolations), which
 * means an ordinary CSS comment is not inert the way a CSS author expects:
 * Velocity has already interpolated the whole file by the time a browser
 * sees it, and it does not know or care that the surrounding text is a
 * comment.
 *
 * <p>A bare {@code #someName} inside such a comment, with no parentheses, is
 * a valid velocimacro CALL if that macro takes no arguments -- the macro's
 * whole body is spliced into the stylesheet, silently, with no error and no
 * log line (see CLAUDE.md's Templates section: Velocity here is lenient by
 * configuration). {@code #showAudienceAssets} and
 * {@code #showWeblogCategoryLinksList} are exactly this shape today, and a
 * comment naming either one used to sit in three of these files, invoking
 * them and leaking a contact/subscribe form (or a piece of the category-nav
 * markup) into the stylesheet the moment a browser requested it. Task A5's
 * report describes finding the identical mistake in a JavaScript comment;
 * this is the CSS-comment twin.
 *
 * <p>This test does not try to distinguish a zero-argument macro name from
 * one that takes arguments (which would be harmless to mention bare, since
 * Velocity cannot match a call with no parens against a macro that requires
 * them): it bans every {@code #show...}-shaped mention inside a comment, so
 * a future macro that grows a zero-argument overload cannot silently
 * reactivate a comment that was safe when it was written. Write "the showX
 * macro" instead.
 */
class ThemeStylesheetTest {

    private static final Path THEMES = Paths.get("src/main/webapp/themes");

    private static final String COMMENT_START = "/*";
    private static final String COMMENT_END = "*/";

    /**
     * One non-nested CSS comment block, matched lazily so a scan finds each
     * block in turn rather than spanning from the first {@code /*} in the
     * file to the last {@code *}{@code /} anywhere after it -- CSS comments
     * do not nest, and the naive greedy-across-blocks version of this regex
     * would treat ordinary code between two unrelated comments as "inside a
     * comment" too.
     */
    private static final Pattern COMMENT_BLOCK =
            Pattern.compile(Pattern.quote(COMMENT_START) + ".*?" + Pattern.quote(COMMENT_END), Pattern.DOTALL);

    /** A Velocity macro name, bare (no parens), wherever it appears. */
    private static final Pattern MACRO_NAME = Pattern.compile("#show[A-Za-z]+");

    @Test
    void noThemeStylesheetNamesAMacroWithAHashInsideAComment() throws IOException {
        assertTrue(Files.isDirectory(THEMES), "Expected " + THEMES.toAbsolutePath());

        List<String> offenders = new ArrayList<>();
        List<Path> stylesheets;
        try (Stream<Path> files = Files.walk(THEMES)) {
            stylesheets = files.filter(p -> p.toString().endsWith(".css")).sorted().toList();
        }

        for (Path css : stylesheets) {
            String text = Files.readString(css, StandardCharsets.UTF_8);
            Matcher blocks = COMMENT_BLOCK.matcher(text);
            while (blocks.find()) {
                Matcher name = MACRO_NAME.matcher(blocks.group());
                if (name.find()) {
                    offenders.add(css + ": " + name.group());
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "These theme stylesheets are rendered through Velocity, so a bare "
                        + "#showX macro name inside a CSS comment is INVOKED (see this test's "
                        + "javadoc), not printed. Say \"the showX macro\" instead:\n  "
                        + String.join("\n  ", offenders));
    }
}
