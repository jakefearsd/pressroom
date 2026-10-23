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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules the bundled themes keep in common, checked by reading their sources.
 *
 * <p>The fit-and-finish pass for 0.3.0 found the three article themes
 * (journal, travel, portfolio) and the frontpage each doing the same job in
 * different ways: journal had rebuilt its pagers, the other three still
 * printed "&laquo; Previous page | Main | Next page &raquo;"; one theme showed
 * an author the Edit link and one did not; the search page dropped the footer
 * everywhere and the category nav in some places. Each rule here is the one
 * decision taken for all of them, so a later edit to one theme cannot quietly
 * diverge again.
 *
 * <p>A source scan rather than a render: several rules concern pages no unit
 * fixture can reach (a pager with hits, a frontpage directory with more than
 * one page of weblogs). Behaviour that a fixture CAN reach is rendered in
 * {@code ThemeChromeRenderingTest}.
 */
class ThemeChromeConsistencyTest {

    private static final Path THEMES = Paths.get("src/main/webapp/themes");

    private static final List<String> ARTICLE_THEMES = List.of("journal", "travel", "portfolio");

    /** The pages of an article theme that carry the full reader chrome. */
    private static final List<String> CHROME_PAGES =
            List.of("weblog.vm", "permalink.vm", "searchresults.vm", "page.vm");

    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);

    private static List<Path> themeTemplates() throws IOException {
        assertTrue(Files.isDirectory(THEMES), "Expected " + THEMES.toAbsolutePath());
        List<Path> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(THEMES)) {
            files.filter(f -> f.toString().endsWith(".vm")).sorted().forEach(found::add);
        }
        assertFalse(found.isEmpty(), "no theme templates found; every scan would pass vacuously");
        return found;
    }

    private static Path template(String theme, String file) {
        return THEMES.resolve(theme).resolve(file);
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String name(Path p) {
        return p.getParent().getFileName() + "/" + p.getFileName();
    }

    private static String between(String src, String open, String close) {
        int start = src.indexOf(open);
        if (start < 0) {
            return "";
        }
        int end = src.indexOf(close, start);
        return end < 0 ? "" : src.substring(start, end + close.length());
    }

    // ------------------------------------------------------------- <title>

    /**
     * One separator in every tab title, and the same one the admin layouts
     * use between a page and its weblog (tiles-tabbedpage.jsp's
     * {@code &#8212;}). The themes had used " : ", which reads as a label
     * and a value rather than as a page and the site it belongs to.
     */
    @Test
    void everyTitleSeparatesThePageFromTheSiteWithAnEmDash() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : themeTemplates()) {
            Matcher title = TITLE.matcher(read(p));
            while (title.find()) {
                if (title.group(1).contains(" : ")) {
                    offenders.add(name(p) + ": " + title.group(1).trim());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "titles still separated by \" : \":\n  "
                + String.join("\n  ", offenders));
    }

    /**
     * The entry editor's search preview shows the meta title ALONE as the
     * result's headline (EntryEdit.jsp, updateSeoSnippet), so that is what an
     * author who fills it in is writing: the whole title. Appending
     * " : weblog" to it made the page's real title longer than the one the
     * author was shown, and usually repeated the weblog name they had already
     * put in it.
     */
    @Test
    void aMetaTitleIsTheWholeTitleOnEveryEntryAndPage() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String theme : ARTICLE_THEMES) {
            for (String file : List.of("permalink.vm", "page.vm")) {
                Path p = template(theme, file);
                String head = between(read(p), "<head>", "</head>");
                if (!head.contains(".metaTitle))")) {
                    offenders.add(name(p) + ": <title> does not branch on the meta title");
                }
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    // -------------------------------------------------------------- pagers

    /**
     * The macro prints "&laquo; Previous page | Main | Next page &raquo;", where
     * "Previous" means newer, and on a permalink cuts each neighbour's title to
     * 15-20 characters. Journal replaced it with labelled Newer/Older steps;
     * every theme now does the same, and the frontpage's own
     * "&lt; Previous | Next &gt;" strip goes with it.
     */
    @Test
    void noThemePrintsTheLegacyPagerStrip() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : themeTemplates()) {
            String src = read(p);
            if (src.contains("#showNextPrevEntriesControl(")) {
                offenders.add(name(p) + ": calls the showNextPrevEntriesControl macro");
            }
            if (src.contains("&lt; $") || src.contains("Name &gt;")) {
                offenders.add(name(p) + ": prints a \"< Previous | Next >\" strip");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    /**
     * A {@code <nav>} with nothing in it is still a landmark: a screen reader
     * lists an empty "More entries" region on every one-page weblog. Every
     * pager nav therefore sits directly inside the condition that it has a
     * link to show.
     */
    @Test
    void everyPagerLandmarkRendersOnlyWhenItHasALink() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : themeTemplates()) {
            String[] lines = read(p).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].contains("<nav") || !lines[i].contains("aria-label=\"More ")) {
                    continue;
                }
                int previous = i - 1;
                while (previous >= 0 && (lines[previous].isBlank()
                        || lines[previous].trim().startsWith("##"))) {
                    previous--;
                }
                if (previous < 0 || !lines[previous].trim().startsWith("#if")) {
                    offenders.add(name(p) + ":" + (i + 1) + ": " + lines[i].trim());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "pager landmarks emitted unconditionally:\n  "
                + String.join("\n  ", offenders));
    }

    /**
     * "Older on the left" only holds if the older link comes first in the
     * markup: journal's list pager emitted Newer first, so on page one the
     * empty placeholder took the left column and Older landed on the right,
     * and on a middle page Older wrapped to a second row.
     */
    @Test
    void everyStepPagerEmitsTheBackwardStepFirst() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path p : themeTemplates()) {
            String src = read(p);
            int from = 0;
            while (true) {
                int steps = src.indexOf("-steps\"", from);
                if (steps < 0) {
                    break;
                }
                int end = src.indexOf("</nav>", steps);
                String nav = src.substring(steps, end < 0 ? src.length() : end);
                int back = nav.indexOf("-step-back\"");
                int fwd = nav.indexOf("-step-fwd\"");
                if (back < 0 || fwd < 0 || fwd < back) {
                    offenders.add(name(p) + ": the forward step precedes the backward one");
                }
                from = steps + 1;
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    // ------------------------------------------------------------- chrome

    /**
     * The search page was the only page in each article theme with no footer,
     * so its search box, subscribe block and business card vanished exactly
     * where a reader who had not found what they wanted would look for them.
     */
    @Test
    void everyArticlePageCarriesTheSameFooter() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String theme : ARTICLE_THEMES) {
            String footer = between(read(template(theme, "weblog.vm")), "<footer", "</footer>");
            assertFalse(footer.isEmpty(), theme + "/weblog.vm has no footer to compare against");
            for (String file : CHROME_PAGES) {
                if (!read(template(theme, file)).contains(footer)) {
                    offenders.add(theme + "/" + file + ": footer differs from weblog.vm's");
                }
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    /**
     * One header per theme. Portfolio's permalink and every theme's search
     * page dropped the category nav, and journal's permalink dropped the
     * tagline while its static pages kept it. The rule now: the category nav
     * on every page, the tagline on the entry list only (weblog.vm -- home,
     * category and tag views), where it introduces the weblog. And on the
     * reading pages (permalink, static page) the entry's or page's own title
     * is the h1, so a "##" heading in the body is an h2 beneath it rather
     * than its equal; the site name there is a paragraph.
     */
    @Test
    void everyArticlePageCarriesTheSameHeader() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String theme : ARTICLE_THEMES) {
            String home = between(read(template(theme, "weblog.vm")), "<header", "</header>");
            assertTrue(home.contains("#showWeblogCategoryLinksList()"),
                    theme + "/weblog.vm's header lost the category nav");
            assertTrue(home.contains("-tagline\""), theme + "/weblog.vm's header lost the tagline");
            String list = home.replaceAll("(?s)\\s*#if\\(\\$utils.isNotEmpty\\(\\$model.weblog.tagline\\)\\).*?#end", "");
            String reading = list.replaceAll("<h1 (class=\"[a-z]+-(?:site|name)\">.*?)</h1>", "<p $1</p>");
            assertFalse(reading.equals(list), theme + ": the site name is not an h1 in weblog.vm");
            for (String file : CHROME_PAGES) {
                if ("weblog.vm".equals(file)) {
                    continue;
                }
                // The reading pages' own title is their h1, so there the site
                // name steps down to a paragraph; the lists keep it as the h1.
                String expected = "searchresults.vm".equals(file) ? list : reading;
                String header = between(read(template(theme, file)), "<header", "</header>");
                if (!header.equals(expected)) {
                    offenders.add(theme + "/" + file + ": header differs from weblog.vm's "
                            + "(minus the tagline):\n" + header);
                }
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    /**
     * An author reading their own post gets a way into the editor. Travel and
     * portfolio offered one and journal did not. The page cache is keyed per
     * user (WeblogPageCache#generateKey), so the link never reaches a reader.
     */
    @Test
    void everyPermalinkOffersItsAuthorAnEditLink() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String theme : ARTICLE_THEMES) {
            String src = read(template(theme, "_day.vm"));
            int permalink = src.indexOf("#if($model.permalink)");
            int list = src.indexOf("\n#else", permalink);
            String branch = permalink < 0 || list < 0 ? "" : src.substring(permalink, list);
            if (!branch.contains("#if($utils.isUserAuthorizedToAuthor($entry.website))")
                    || !branch.contains("$url.editEntry($entry.anchor)")) {
                offenders.add(theme + "/_day.vm: no author-only Edit link on the permalink");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    /**
     * The page's only h1 is the site name; an entry title on a list was an
     * h3 in journal and portfolio, skipping a level (travel's was already h2).
     */
    @Test
    void noEntryHeadingSkipsALevelUnderTheSiteName() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String theme : ARTICLE_THEMES) {
            if (read(template(theme, "_day.vm")).contains("<h3")) {
                offenders.add(theme + "/_day.vm: entry heading is an h3 under the page's h1");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    /**
     * A category or tag view said which view it was only in the tab title, so
     * a reader who followed a tag link landed on a list that looked exactly
     * like the home page. Tag views reach weblog.vm through the default
     * template fallback for /tags/&lt;tag&gt;, which no unit fixture could
     * render when this was written -- hence the scan.
     */
    @Test
    void everyEntryListNamesTheCategoryOrTagItShows() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String theme : ARTICLE_THEMES) {
            String main = between(read(template(theme, "weblog.vm")), "<main", "</main>");
            if (!main.contains("-view-name\">$utils.escapeHTML($model.weblogCategory.name)<")) {
                offenders.add(theme + "/weblog.vm: no visible category heading");
            }
            if (!main.contains("-view-name\">$utils.escapeHTML($tagTitle)<")) {
                offenders.add(theme + "/weblog.vm: no visible tag heading");
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }

    // ---------------------------------------------------------------- i18n

    /**
     * Theme chrome renders in the weblog's locale where a message key for the
     * word already exists: the macros beside it do, and {@code <html lang>}
     * follows the weblog. The theme's own voice -- empty states, prompts such
     * as "Get new guides by email" -- stays English by decision; these are
     * the words the bundles already translate.
     */
    @Test
    void chromeWordsWithAMessageKeyAreNotHardCoded() throws IOException {
        List<String> words = List.of(">Newer<", ">Older<", ">Edit<", "aria-label=\"Search\"",
                ">Login<", ">Logout<", ">Main Menu<");
        List<String> offenders = new ArrayList<>();
        for (Path p : themeTemplates()) {
            String src = read(p);
            for (String word : words) {
                if (src.contains(word)) {
                    offenders.add(name(p) + ": " + word);
                }
            }
        }
        assertTrue(offenders.isEmpty(), String.join("\n  ", offenders));
    }
}
