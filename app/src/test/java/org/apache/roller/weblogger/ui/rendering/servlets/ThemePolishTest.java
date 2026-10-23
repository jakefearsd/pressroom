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
package org.apache.roller.weblogger.ui.rendering.servlets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-theme source scan for the finish items that are invisible per-page and
 * only show up when you line all four themes up beside each other: a favicon
 * link, a {@code <title>} that says which view you are on, a machine-readable
 * date, and something to read when a list is empty.
 *
 * <p>Same shape and rationale as {@link ThemeCspCoverageTest} and
 * {@link ThemeAccessibilityTest} -- a render assertion proves one page in one
 * theme; only a scan proves the other eleven templates were not forgotten.
 */
class ThemePolishTest {

    private static final Path THEMES = Paths.get("src/main/webapp/themes");

    /** A {@code <time>} element carrying a datetime, class attribute or not. */
    private static final Pattern TIME_ELEMENT = Pattern.compile("<time[^>]*\\bdatetime=");

    /** Templates that own a {@code <head>} of their own (frontpage delegates). */
    private static List<Path> headTemplates() throws IOException {
        List<Path> found = new ArrayList<>();
        for (Path p : themeFiles(".vm")) {
            if (read(p).contains("<head>")) {
                found.add(p);
            }
        }
        return found;
    }

    private static List<Path> themeFiles(String suffix) throws IOException {
        assertTrue(Files.isDirectory(THEMES), "Expected " + THEMES.toAbsolutePath());
        List<Path> found = new ArrayList<>();
        try (Stream<Path> themeDirs = Files.list(THEMES)) {
            for (Path theme : themeDirs.filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.list(theme)) {
                    files.filter(f -> f.toString().endsWith(suffix)).forEach(found::add);
                }
            }
        }
        return found;
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String name(Path p) {
        return p.getParent().getFileName() + "/" + p.getFileName();
    }

    /**
     * A theme's {@code _preview} shell, which is a frame body rather than a
     * page and is exempt from the favicon rule below.
     *
     * <p>Both halves of that rule's reasoning are about a *tab*: the icon a
     * reader sees, and the {@code /favicon.ico} a browser asks for when none
     * is declared. Neither applies to a document that only ever loads inside
     * the entry editor's preview iframe -- no browser paints a frame's icon,
     * and none probes for one either. Declaring it anyway would be markup
     * shipped on every preview render for something nothing can display.
     *
     * <p>Deliberately not keyed on the filename alone: a template earns the
     * exemption only by actually being a shell (carrying the article the
     * editor fills), so a future {@code preview.vm} that grows into a real
     * page is held to the rule like every other template.
     */
    private static boolean isPreviewShell(Path template, String src) {
        return "preview.vm".equals(template.getFileName().toString())
                && src.contains("id=\"previewArticle\"");
    }

    /**
     * Only frontpage linked a favicon, so every weblog on the other three
     * themes served the browser's default document icon -- and, on each first
     * visit, a 404 for {@code /favicon.ico} it never asked for.
     *
     * <p>The one exemption is the editor's preview shell, which is never a
     * tab -- see {@link #isPreviewShell}.
     */
    @Test
    void everyThemeHeadLinksTheSiteFavicon() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path template : headTemplates()) {
            String src = read(template);
            if (isPreviewShell(template, src)) {
                continue;
            }
            if (!src.contains("rel=\"icon\"")) {
                offenders.add(name(template) + ": no favicon link");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    /**
     * A category view, a tag view and the home page all rendered the same
     * {@code <title>} -- just the weblog's name -- so a reader with six tabs
     * open, or a bookmark, could not tell them apart. The branch belongs in
     * the theme, not in a model: it is the only place that knows the
     * separator and the ordering the rest of the theme's titles use.
     */
    @Test
    void everyThemesEntryListTitleDistinguishesCategoryAndTagViews() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path template : themeFiles(".vm")) {
            if (!"weblog.vm".equals(template.getFileName().toString())
                    || "frontpage".equals(template.getParent().getFileName().toString())) {
                continue;
            }
            String src = read(template);
            if (!src.contains("$model.weblogCategory.name") || !src.contains("$model.tags")) {
                offenders.add(name(template)
                        + ": <title> does not branch on the category/tag view");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    /**
     * A date archive renders through weblog.vm too, and said nothing about
     * which month or day it lists. Each article theme names it, in the tab and
     * on the page, from {@code $model.weblogDateLabel}.
     */
    @Test
    void everyThemesEntryListNamesADateArchive() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path template : themeFiles(".vm")) {
            if (!"weblog.vm".equals(template.getFileName().toString())
                    || "frontpage".equals(template.getParent().getFileName().toString())) {
                continue;
            }
            String src = read(template);
            String title = src.substring(src.indexOf("<head>"), src.indexOf("</head>"));
            String main = src.substring(src.indexOf("</head>"));
            if (!title.contains("$model.weblogDateLabel")) {
                offenders.add(name(template) + ": <title> does not name the date archive");
            }
            if (!main.contains("-view-name\">$utils.escapeHTML($model.weblogDateLabel)<")) {
                offenders.add(name(template) + ": no heading names the date archive");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    /**
     * The search pager macro emits its own labelled {@code <nav>}; the themes
     * wrapped the call in a second one, so the page carried a navigation
     * landmark holding the status line and another navigation landmark.
     */
    @Test
    void theSearchPagerIsNotNestedInsideAnotherNav() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path template : themeFiles(".vm")) {
            String src = read(template);
            int call = src.indexOf("#showNextPrevSearchControl(");
            if (call < 0) {
                continue;
            }
            String before = src.substring(0, call);
            if (before.lastIndexOf("<nav") > before.lastIndexOf("</nav>")) {
                offenders.add(name(template) + ": the search pager call sits inside a <nav>");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    /**
     * {@code search-pager} (previous left, more right) and {@code search-hits}
     * are the shared macros' classes; a theme that does not style them shows
     * two bare links run together and a hit line in body type.
     */
    @Test
    void everyThemeWithSearchResultsStylesTheSearchPagerAndHitLine() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path css : themeFiles("-custom.css")) {
            String src = read(css);
            for (String selector : new String[]{".search-pager", ".search-hits"}) {
                if (!src.contains(selector)) {
                    offenders.add(name(css) + ": no " + selector + " rule");
                }
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    /** A search results page whose title omits the query is the same problem. */
    @Test
    void everySearchResultsTitleNamesTheQuery() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path template : themeFiles(".vm")) {
            if (!"searchresults.vm".equals(template.getFileName().toString())) {
                continue;
            }
            String src = read(template);
            String title = src.substring(src.indexOf("<title>"), src.indexOf("</title>"));
            if (!title.contains("$model.term")) {
                offenders.add(name(template) + ": <title> does not carry the query");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    /**
     * Every rendered date was display text only, so nothing downstream -- a
     * reader mode, a scraper, an assistive technology reading "MMM d" out of
     * context -- could recover the actual day. {@code <time datetime>} carries
     * the ISO value beside the human one.
     */
    @Test
    void everyRenderedDateIsWrappedInAMachineReadableTime() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path template : themeFiles(".vm")) {
            String src = read(template);
            if (!src.contains("$utils.formatDate(")) {
                continue;
            }
            if (!TIME_ELEMENT.matcher(src).find()) {
                offenders.add(name(template) + ": renders a date with no <time datetime>");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    /**
     * A weblog with nothing published, a category nobody has filed under, a
     * tag page reached from a stale link: all three rendered an empty column
     * with no explanation. Signature move 2 of the design system --
     * "empty states are invitations, not shrugs" -- applies to the public
     * themes too, not just the admin UI.
     */
    @Test
    void everyThemesEntryListHasSomethingToSayWhenItIsEmpty() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path template : themeFiles(".vm")) {
            if (!"weblog.vm".equals(template.getFileName().toString())
                    || "frontpage".equals(template.getParent().getFileName().toString())) {
                continue;
            }
            if (!read(template).contains("-list-empty\"")) {
                offenders.add(name(template) + ": no zero-entry branch");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    /**
     * {@code #showNextPrevSearchControl}'s "showing 1-10 of 42" line was an
     * {@code <h3>}: a heading that names no section, sitting under the site
     * name's h1 in every theme's search page. It is a status line.
     *
     * <p>Scanned rather than rendered because the macro only runs when
     * {@code $model.hits > 0}, and no unit fixture can produce a hit --
     * indexing is asynchronous, so every search test in the suite searches an
     * empty index.
     */
    @Test
    void theSearchHitCountIsAStatusLineNotAHeading() throws IOException {
        String macros = read(Paths.get("src/main/webapp/WEB-INF/velocity/weblog.vm"));
        int control = macros.indexOf("#macro(showNextPrevSearchControl");
        assertTrue(control >= 0, "the search pager macro must exist");
        String body = macros.substring(control, macros.indexOf("\n#end", control));
        assertTrue(body.contains("role=\"status\""),
                "the hit count must be announced as a status:\n" + body);
        assertTrue(!body.contains("<h3>"),
                "the hit count must not be a heading:\n" + body);
    }

    /**
     * Every entry carried tags, every tag had a working archive page, and no
     * bundled theme ever linked to one -- {@code #showEntryTags} was defined
     * in weblog.vm and documented as custom-theme API, but no shipped theme
     * called it, so a reader had no route from an entry to the rest of that
     * subject and the tag archives were unreachable from the site itself.
     *
     * <p>Scoped to the permalink branch of each theme's {@code _day.vm}: the
     * entry list is a scanning surface where a tag row competes with the
     * titles, while the reading view is where a reader who just finished the
     * piece is looking for more of the same.
     */
    @Test
    void everyThemesPermalinkLinksTheEntrysTags() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path template : themeFiles(".vm")) {
            if (!"_day.vm".equals(template.getFileName().toString())) {
                continue;
            }
            String src = read(template);
            int permalink = src.indexOf("#if($model.permalink)");
            if (permalink < 0) {
                offenders.add(name(template) + ": no permalink branch to carry the tags");
                continue;
            }
            int elseAt = src.indexOf("#else", permalink);
            String branch = elseAt < 0 ? src.substring(permalink) : src.substring(permalink, elseAt);
            if (!branch.contains("#showEntryTags")) {
                offenders.add(name(template)
                        + ": the permalink branch does not call #showEntryTags");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }
}
