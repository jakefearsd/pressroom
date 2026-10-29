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
package com.wikantik.pressroom.ui.rendering.velocity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-scan over the two Velocity error templates.
 *
 * <p>These templates are what a <em>reader</em> sees when a theme blows up
 * mid-render: {@code error-page.vm} replaces the whole page and
 * {@code error-parse.vm} is spliced in where a failed {@code #parse} would
 * have gone -- on a public weblog, to anyone (as a 500 now, never cached:
 * see RenderErrorStatusTest). They used to
 * print the exception's message, its {@code getClass().getName()} and the
 * template source that threw, which is precisely the detail
 * {@code roller-ui/errors/error.jsp} refuses to show for the same class of
 * failure ("No exception detail is rendered here on purpose ... the real
 * detail goes to the server log"). Nothing about the server-side logging
 * changes; only the response body does.
 *
 * <p>A source scan rather than a render: reaching these templates needs a
 * genuinely broken theme in the resource loader, and the property under test
 * is textual ("this reference is not in the file") rather than behavioural.
 */
class VelocityErrorTemplateTest {

    private static final Path ERROR_PAGE =
            Path.of("src/main/webapp/WEB-INF/velocity/templates/error-page.vm");
    private static final Path ERROR_PARSE =
            Path.of("src/main/webapp/WEB-INF/velocity/templates/error-parse.vm");

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), path + " must exist");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * The three references that leaked internals. {@code $exception} covers
     * both {@code $exception.getMessage()} and the {@code $!exception
     * .getClass().getName()} form; {@code $exceptionSource} named the
     * template that threw.
     */
    private static void assertNoExceptionDetail(Path path) throws IOException {
        String source = read(path);
        assertFalse(source.contains("$exception"),
                path + " must not render the exception to the reader:\n" + source);
        assertFalse(source.contains("$!exception"),
                path + " must not render the exception to the reader:\n" + source);
        assertFalse(source.contains("$exceptionSource"),
                path + " must not name the failing template to the reader:\n" + source);
        assertFalse(source.contains("getClass().getName()"),
                path + " must not render the exception class to the reader:\n" + source);
    }

    @Test
    void theFullPageErrorTemplateShowsNoExceptionDetail() throws IOException {
        assertNoExceptionDetail(ERROR_PAGE);
    }

    /**
     * The site name is admin-typed text; every other template escapes it, and
     * renderException puts {@code $utils} in the error context for exactly
     * this.
     */
    @Test
    void theSiteNameIsEscapedOnTheErrorPages() throws IOException {
        for (Path path : new Path[]{ERROR_PAGE, ERROR_PARSE}) {
            String source = read(path).replace("$utils.escapeHTML($config.siteName)", "");
            assertFalse(source.contains("$config.siteName"),
                    path + " prints the site name unescaped:\n" + source);
        }
    }

    @Test
    void theParseErrorTemplateShowsNoExceptionDetail() throws IOException {
        assertNoExceptionDetail(ERROR_PARSE);
    }

    /**
     * Both templates keep the two neutral, localized strings
     * ({@code errorPage.title}/{@code errorPage.message}) that say an
     * unexpected error happened and was logged -- the same register
     * {@code error.jsp} uses. Dropping them would orphan two live message
     * keys as well as leaving the reader with a blank box.
     */
    @Test
    void bothTemplatesKeepTheNeutralLocalizedWording() throws IOException {
        for (Path path : new Path[]{ERROR_PAGE, ERROR_PARSE}) {
            String source = read(path);
            assertTrue(source.contains("$text.get(\"errorPage.title\")"),
                    path + " must keep the neutral title:\n" + source);
            assertTrue(source.contains("$text.get(\"errorPage.message\")"),
                    path + " must keep the neutral message:\n" + source);
        }
    }

    /**
     * {@code error-page.vm}'s {@code <style} block was never closed -- the
     * missing {@code >} makes the parser swallow the {@code </head>} that
     * follows as part of the tag. Cosmetic on an error page, but it is the
     * kind of thing that makes the page render differently in different
     * browsers exactly when someone is trying to read it.
     */
    @Test
    void theFullPageErrorTemplateClosesItsStyleTag() throws IOException {
        String source = read(ERROR_PAGE);
        assertTrue(source.contains("</style>"),
                "the <style> block must be closed properly:\n" + source);
        assertFalse(source.contains("</style\n"),
                "the </style tag is missing its '>':\n" + source);
    }

    /**
     * A reader saw a red table banner reading "Unexpected Exception", with
     * no dark mode, and a red block spliced into a themed page. The page is
     * now error.jsp's calm card in the quiet palette, light and dark; the
     * spliced block is one neutral line in the theme's own colours.
     */
    @Test
    void theErrorTemplatesAreCalmAndFollowTheColourScheme() throws IOException {
        String page = read(ERROR_PAGE);
        assertTrue(page.contains("@media (prefers-color-scheme: dark)"), "the error page has a dark palette");
        assertFalse(page.contains("<table"), "no table banner:\n" + page);
        String parse = read(ERROR_PARSE);
        assertTrue(parse.contains("role=\"alert\""), "the spliced line is announced:\n" + parse);
        for (String source : new String[] {page, parse}) {
            for (String red : new String[] {"#f00", "#c00", "#fcc", "#CC0000", "red;"}) {
                assertFalse(source.contains(red), "no red: " + red + "\n" + source);
            }
            assertFalse(source.contains("<span") && source.contains("<ul"), "no list inside a span");
        }
    }

    /** error.jsp carries the viewport meta; this page is read on phones too. */
    @Test
    void theFullPageErrorTemplateIsResponsive() throws IOException {
        assertTrue(read(ERROR_PAGE)
                        .contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"),
                "error-page.vm must carry error.jsp's viewport meta");
    }
}
