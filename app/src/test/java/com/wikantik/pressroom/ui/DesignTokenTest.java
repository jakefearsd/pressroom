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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code roller-tokens.css} -- the "Quiet Instrument" design
 * tokens (see {@code docs/design/design-system.md}) -- stays in sync with
 * its spec and stays wired into the page.
 *
 * <p>Four failure modes this guards, modeled on {@link MessageKeyTest}'s
 * webapp-source scanning (the last one added after a file-scoped check let an
 * entire off-spec palette sit unnoticed in the directory it was policing):
 * <ul>
 *   <li>a color slips in that is not one of the spec's 21 hex values --
 *       the whole point of a token file is that every color traces back to
 *       the spec, not to whatever a later edit typed in;</li>
 *   <li>the light and dark palettes drift out of parity -- a token defined
 *       under {@code :root} but never overridden under
 *       {@code prefers-color-scheme: dark} (or vice versa) is the classic
 *       unreadable-page bug: half the UI keeps its light-mode color on a
 *       dark background;</li>
 *   <li>{@code head.jsp} stops referencing the stylesheet, silently
 *       reverting every admin page to unstyled tokens;</li>
 *   <li>any OTHER stylesheet in the same directory carries its own palette --
 *       the failure that hid {@code atom.xsl}/{@code rss.xsl}'s red/tan
 *       scheme, which escaped only because the check above names one file.</li>
 * </ul>
 */
public class DesignTokenTest {

    private static final Path TOKENS_CSS =
            Paths.get("src/main/webapp/pressroom-ui/styles/roller-tokens.css");

    /**
     * The stylesheets that load alongside {@code roller-tokens.css} and may
     * therefore declare NO color of their own. Both are admin-UI files served
     * from the same {@code <head>} as the token file, so every color they
     * need is already available as a {@code var(--token)}.
     */
    private static final List<Path> TOKENS_ONLY_STYLESHEETS = List.of(
            Paths.get("src/main/webapp/pressroom-ui/styles/roller.css"),
            Paths.get("src/main/webapp/pressroom-ui/styles/roller-editor.css"));

    /**
     * The CodeMirror bundle's own source, under Maven (frontend-maven-plugin
     * builds it into {@code roller-editor.js}, see CLAUDE.md's "Frontend
     * build"). {@code markdown-theme.js} styles the editor entirely through
     * {@code var(--token)} strings handed to CodeMirror's own theming API
     * (see its header comment), so it is tokens-only in the same sense the
     * two stylesheets above are -- this is that same audit for the one place
     * a color could otherwise be typed as a JS string literal instead of a
     * CSS one.
     */
    private static final Path FRONTEND_SRC = Paths.get("frontend/src");

    /** Strips a JS line comment to end-of-line. None of this source has a
     * {@code //} inside a string literal (verified by inspection), so this
     * does not need to be string-aware. */
    private static final Pattern JS_LINE_COMMENT = Pattern.compile("//[^\\n]*");

    private static final Path HEAD_JSP =
            Paths.get("src/main/webapp/WEB-INF/jsps/tiles/head.jsp");

    /**
     * The spec's 21 hex values (docs/design/design-system.md): 11 light
     * tokens + 11 dark tokens, minus the 1 value (--focus) shared by both.
     */
    private static final Set<String> SPEC_HEX_VALUES = Set.of(
            // light
            "#F7F9F9", "#FFFFFF", "#17262A", "#5A6E72", "#DCE4E4",
            "#0F6E68", "#E3F0EE", "#2F7D4F", "#9A6A1F", "#A33B2E", "#2AA198",
            // dark (excluding #2AA198, already listed as --focus above)
            "#131C1C", "#1A2626", "#DCE7E5", "#8FA5A2", "#2A3838",
            "#4FB3AA", "#1E3230", "#6FBF8F", "#D0A45C", "#D0705F"
    );

    private static final Pattern HEX_LITERAL = Pattern.compile("#[0-9A-Fa-f]{6}\\b");

    /**
     * Strips CSS block comments before the roller.css audit runs -- unlike
     * roller-tokens.css, an admin stylesheet's own comments legitimately
     * document hex values it does not itself declare (a third-party
     * stylesheet's hardcoded caret color, say), so those must not trip the
     * literal check below.
     */
    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private static final Pattern COLOR_TOKEN_DECL =
            Pattern.compile("(--[a-z-]+)\\s*:\\s*(#[0-9A-Fa-f]{6})\\s*;");

    /**
     * Matches specifically {@code @media (prefers-color-scheme: dark) { :root {
     * ... } }} and captures only the {@code :root} body. Anchored to
     * {@code :root} right after the media query (not just "the next {"), and
     * the body capture is reluctant ({@code .*?}) so it stops at the first
     * {@code }} -- i.e. :root's own close -- rather than greedily running to
     * the last {@code }\s*}} in the file. A second, unrelated @media block
     * added later (e.g. a print query) cannot widen or shift this match.
     */
    private static final Pattern DARK_MEDIA_BLOCK = Pattern.compile(
            "@media\\s*\\(prefers-color-scheme:\\s*dark\\)\\s*\\{\\s*:root\\s*\\{(.*?)\\}\\s*\\}",
            Pattern.DOTALL);

    @Test
    public void everyHexLiteralIsFromTheSpec() throws IOException {
        String css = readTokensCss();
        Matcher matcher = HEX_LITERAL.matcher(css);
        Set<String> found = new TreeSet<>();
        Set<String> unexpected = new TreeSet<>();
        while (matcher.find()) {
            String hex = matcher.group().toUpperCase(java.util.Locale.ROOT);
            found.add(hex);
            if (!SPEC_HEX_VALUES.contains(hex)) {
                unexpected.add(hex);
            }
        }

        assertTrue(found.size() > 0,
                "Found no hex color literals in " + TOKENS_CSS.toAbsolutePath()
                        + " -- the test is not looking where it thinks it is.");

        assertTrue(unexpected.isEmpty(),
                TOKENS_CSS + " uses hex color(s) that are not in the \"Quiet Instrument\" "
                        + "spec's 21-value set (docs/design/design-system.md):\n  "
                        + String.join("\n  ", unexpected));
    }

    /**
     * roller.css and roller-editor.css are deliberately tokens-only -- design-system.md's whole
     * point is that a color is chosen once, in roller-tokens.css's 21-value
     * spec, and every other stylesheet references it through
     * {@code var(--token)} rather than picking its own literal. The
     * background-shorthand bug this test guards against (a {@code
     * background:} rule silently wiping Bootstrap's {@code
     * background-image} caret) shipped as a hex/literal-shaped mistake, so
     * this audits the same failure class the roller-tokens.css test above
     * does -- a bare {@code #RRGGBB} outside a comment -- but expects ZERO
     * matches rather than checking against the spec set, since neither file
     * is supposed to declare any hex at all. Named CSS color keywords
     * are deliberately out of scope: this locks down the specific regression
     * class the design wave hit, not a general-purpose CSS color linter.
     * (The named colors this exemption was written for are all gone as of the
     * 2026-08-10 consistency pass -- {@code color-mix()} now shades toward
     * {@code var(--ink)} so hover polarity follows the theme, and the
     * collapse chevron takes {@code var(--ink-soft)} -- but the exemption
     * stands, because a keyword is a different failure mode from a hex.)
     */
    @Test
    public void tokensOnlyStylesheetsHaveNoHexColorLiteralsOutsideComments() throws IOException {
        for (Path stylesheet : TOKENS_ONLY_STYLESHEETS) {
            assertTrue(Files.exists(stylesheet), "Expected to find " + stylesheet.toAbsolutePath());
            String css = Files.readString(stylesheet, StandardCharsets.UTF_8);
            String withoutComments = CSS_COMMENT.matcher(css).replaceAll(" ");

            Matcher matcher = HEX_LITERAL.matcher(withoutComments);
            Set<String> found = new TreeSet<>();
            while (matcher.find()) {
                found.add(matcher.group());
            }

            assertTrue(found.isEmpty(),
                    stylesheet + " must stay tokens-only -- found hex color literal(s) outside "
                            + "comments (should be var(--token) references instead): "
                            + String.join(", ", found));
        }
    }

    /**
     * The JS twin of {@link #tokensOnlyStylesheetsHaveNoHexColorLiteralsOutsideComments()}:
     * every {@code .js} file under {@code app/frontend/src/} must stay
     * tokens-only too. A hex literal there is exactly as much an off-spec
     * color as one in {@code roller-editor.css} -- CodeMirror's theming API
     * takes plain strings, so nothing stops a future edit from typing a
     * literal {@code '#17262A'} where {@code 'var(--ink)'} belongs, and this
     * directory sat unaudited while the CSS half was already covered.
     */
    @Test
    public void frontendSourceHasNoHexColorLiteralsOutsideComments() throws IOException {
        assertTrue(Files.isDirectory(FRONTEND_SRC), "Expected to find " + FRONTEND_SRC.toAbsolutePath());

        List<Path> jsFiles;
        try (Stream<Path> files = Files.list(FRONTEND_SRC)) {
            jsFiles = files.filter(f -> f.getFileName().toString().endsWith(".js")).sorted().toList();
        }
        assertFalse(jsFiles.isEmpty(), "Found no .js files under " + FRONTEND_SRC.toAbsolutePath());

        for (Path js : jsFiles) {
            String source = Files.readString(js, StandardCharsets.UTF_8);
            String withoutComments = CSS_COMMENT.matcher(source).replaceAll(" ");
            withoutComments = JS_LINE_COMMENT.matcher(withoutComments).replaceAll(" ");

            Matcher matcher = HEX_LITERAL.matcher(withoutComments);
            Set<String> found = new TreeSet<>();
            while (matcher.find()) {
                found.add(matcher.group());
            }

            assertTrue(found.isEmpty(),
                    js + " must stay tokens-only -- found hex color literal(s) outside comments "
                            + "(should be var(--token) references instead): " + String.join(", ", found));
        }
    }

    /**
     * Every OTHER stylesheet in the directory, not just the one named above.
     *
     * <p>{@link #tokensOnlyStylesheetsHaveNoHexColorLiteralsOutsideComments()}
     * names its files explicitly, which is exactly how {@code atom.xsl} and
     * {@code rss.xsl} kept an entire off-spec red/tan palette
     * ({@code #ad3537}, {@code #c6ab74}, {@code #f00}) sitting in the policed
     * directory, on a public surface, with no dark mode -- they were never
     * named, so they were never checked. A file-scoped linter only guards the
     * files someone remembered to list; this one guards the directory, so a new
     * stylesheet is covered the day it lands rather than the day someone audits
     * it.
     *
     * <p>The assertion here is membership, not absence, and the distinction
     * matters. {@code roller.css} may hold NO hex at all because
     * {@code roller-tokens.css} loads alongside it and it can say
     * {@code var(--accent)}. The feed stylesheets cannot: an XSL transform emits
     * a standalone document with one inline {@code <style>} and no companion
     * file to import, so it has to declare the palette it then references --
     * the same job {@code roller-tokens.css} does, in a file that has to carry
     * it itself. Demanding zero literals there would be demanding they render
     * uncolored. So the rule is the one that actually expresses the intent:
     * every color a stylesheet names must be one of the spec's 21 values.
     * That still fails the red/tan palette this test was written for, and it
     * fails a 3-digit {@code #f00} too, while letting a self-contained document
     * define the tokens it needs.
     *
     * <p>{@code roller-tokens.css} itself is skipped -- it is the palette of
     * record, and {@link #everyHexLiteralIsFromTheSpec()} already guards it.
     */
    @Test
    public void everyStylesheetInTheDirectoryUsesOnlySpecColors() throws IOException {
        Path dir = TOKENS_CSS.getParent();
        assertTrue(Files.isDirectory(dir), "Expected a styles directory at " + dir.toAbsolutePath());

        // 3- and 6-digit forms both: the feed stylesheets used #f00, which a
        // 6-digit-only pattern reads straight past.
        Pattern hex = Pattern.compile("#(?:[0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})\\b");
        // XSL carries its CSS inside XML, so strip XML comments as well.
        Pattern xmlComment = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

        Set<String> offenders = new TreeSet<>();
        List<Path> checked = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (file.equals(TOKENS_CSS) || Files.isDirectory(file)) {
                    continue;
                }
                if (!name.endsWith(".css") && !name.endsWith(".xsl")) {
                    continue;
                }
                checked.add(file);
                String body = Files.readString(file, StandardCharsets.UTF_8);
                body = CSS_COMMENT.matcher(body).replaceAll(" ");
                body = xmlComment.matcher(body).replaceAll(" ");
                Matcher m = hex.matcher(body);
                while (m.find()) {
                    String value = m.group().toUpperCase(java.util.Locale.ROOT);
                    if (!SPEC_HEX_VALUES.contains(value)) {
                        offenders.add(name + ": " + m.group());
                    }
                }
            }
        }

        assertFalse(checked.isEmpty(), "Found no stylesheets to check under " + dir.toAbsolutePath());
        assertTrue(offenders.isEmpty(),
                "Every color in " + dir + " must be one of the \"Quiet Instrument\" spec's 21 "
                        + "values (docs/design/design-system.md). Off-spec color(s): "
                        + String.join(", ", offenders));
    }

    @Test
    public void lightAndDarkPalettesDefineTheSameTokenNames() throws IOException {
        String css = readTokensCss();

        Matcher darkBlockMatcher = DARK_MEDIA_BLOCK.matcher(css);
        assertTrue(darkBlockMatcher.find(),
                TOKENS_CSS + " has no @media (prefers-color-scheme: dark) block");
        String darkBlock = darkBlockMatcher.group(1);
        String lightBlock = css.substring(0, darkBlockMatcher.start());

        Set<String> lightTokens = colorTokenNames(lightBlock);
        Set<String> darkTokens = colorTokenNames(darkBlock);

        assertFalse(lightTokens.isEmpty(), "Found no color custom properties before the dark media block");
        assertFalse(darkTokens.isEmpty(), "Found no color custom properties inside the dark media block");

        assertEquals(lightTokens, darkTokens,
                "Light and dark palettes in " + TOKENS_CSS + " define different token names -- "
                        + "every color token must be overridden in both, or a page renders half "
                        + "light-mode colors on a dark background. Light-only: "
                        + diff(lightTokens, darkTokens) + "; dark-only: " + diff(darkTokens, lightTokens));
    }

    @Test
    public void headJspReferencesTokensStylesheet() throws IOException {
        assertTrue(Files.exists(HEAD_JSP), "Expected to find " + HEAD_JSP.toAbsolutePath());
        String content = Files.readString(HEAD_JSP, StandardCharsets.UTF_8);

        assertTrue(content.contains("roller-tokens.css"),
                HEAD_JSP + " no longer references roller-tokens.css");

        int bootstrapIndex = content.indexOf("bootstrap.min.css");
        int tokensIndex = content.indexOf("roller-tokens.css");
        int rollerCssIndex = content.indexOf("pressroom-ui/styles/roller.css");

        assertTrue(bootstrapIndex >= 0, HEAD_JSP + " no longer links bootstrap.min.css");
        assertTrue(rollerCssIndex >= 0, HEAD_JSP + " no longer links roller.css");

        assertTrue(bootstrapIndex < tokensIndex,
                "roller-tokens.css must be linked after bootstrap.min.css so its custom "
                        + "properties are available to override, but it appears first in " + HEAD_JSP);
        assertTrue(tokensIndex < rollerCssIndex,
                "roller-tokens.css must be linked before roller.css so roller.css can override "
                        + "the tokens, but it appears after in " + HEAD_JSP);
    }

    private static Set<String> colorTokenNames(String block) {
        Matcher matcher = COLOR_TOKEN_DECL.matcher(block);
        Set<String> names = new LinkedHashSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> result = new TreeSet<>(a);
        result.removeAll(b);
        return result;
    }

    private String readTokensCss() throws IOException {
        assertTrue(Files.exists(TOKENS_CSS), "Expected to find " + TOKENS_CSS.toAbsolutePath());
        return Files.readString(TOKENS_CSS, StandardCharsets.UTF_8);
    }
}
