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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The content-asset macro (callouts, code blocks, inline code, tables,
 * heading anchors) on every template that renders entry or page content, the
 * editor's preview shells included -- the twin of
 * {@code MapAssetsRenderingTest}.
 *
 * <p>Missing one call site is a silent failure of the worst kind for this
 * macro: the content still renders, legibly enough that nobody notices, but
 * the callout loses its colours, the code block its toolbar, and a dark
 * theme's code goes back to whatever the browser default makes of it.
 *
 * <p>The macro is one {@code <style>} block (which must work with no script
 * at all) and one classic bootstrap script that loads
 * {@code pressroom-content.js} only when the page has a code block or a heading
 * id inside content to enhance -- the same lazy bargain the map assets make
 * with Leaflet.
 */
class ContentAssetsRenderingTest {

    private static final String HANDLE = "contentassetblog";

    /** The bundle the bootstrap injects; context-relative, from $url.site. */
    private static final String BUNDLE = "/pressroom-ui/scripts/pressroom-content.js";

    private static final String INITIALISER = "function pressroomInitContent(";

    /** One rule from the style block, standing for the whole of it. */
    private static final String CALLOUT_RULE = ".markdown-alert-note {";

    private static final String PREVIEW_SERVLET = "/pressroom-ui/authoring/preview";

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("contentassetuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ------------------------------------------------------------ call sites

    @Test
    void everyJournalTemplateThatRendersContentShipsTheAssetsOnce() throws Exception {
        assertEveryContentTemplate("journal");
    }

    @Test
    void everyPortfolioTemplateThatRendersContentShipsTheAssetsOnce() throws Exception {
        switchTheme("portfolio");
        assertEveryContentTemplate("portfolio");
    }

    @Test
    void everyTravelTemplateThatRendersContentShipsTheAssetsOnce() throws Exception {
        switchTheme("travel");
        assertEveryContentTemplate("travel");
    }

    /**
     * frontpage's header is the head of every page it renders, its own
     * static pages (page.vm) included; a custom theme with no _page reaches
     * the shared fallback page template, and previews go through the shared
     * preview shell -- all of them carry the macro once.
     */
    @Test
    void frontpageAndTheSharedFallbackTemplatesShipTheAssetsOnce() throws Exception {
        switchTheme("frontpage");
        assertAssetsExactlyOnce(render("/" + HANDLE), "frontpage _header.vm");

        savePage("about", "About", "Some **text**.");
        String themed = render("/" + HANDLE + "/about");
        assertTrue(themed.contains("<article class=\"fd-page pressroom-content\">"),
                "frontpage's own _page must put its article in the content scope:\n" + themed);
        assertAssetsExactlyOnce(themed, "frontpage page.vm");

        switchTheme(com.wikantik.pressroom.pojos.WeblogTheme.CUSTOM);
        String page = render("/" + HANDLE + "/about");
        assertTrue(page.contains("<article class=\"pressroom-content\">"),
                "the fallback page's article must be a content container, or its "
                        + "headings get no anchors:\n" + page);
        assertAssetsExactlyOnce(page, "the shared templates/weblog/page.vm");

        assertAssetsExactlyOnce(previewShell(), "the shared templates/weblog/preview.vm");
    }

    /**
     * A template added later that calls the map assets but forgets this macro
     * would pass every rendering test above, which enumerate today's
     * templates. This scans the sources instead: every head that ships one
     * shortcode-asset macro ships this one too.
     */
    @Test
    void everyTemplateWithShortcodeAssetsAlsoCallsTheContentAssets() throws IOException {
        List<Path> roots = List.of(Path.of("src/main/webapp/themes"),
                Path.of("src/main/webapp/WEB-INF/velocity/templates"));
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path vm : files.filter(p -> p.toString().endsWith(".vm")).toList()) {
                    String source = Files.readString(vm);
                    if (source.contains("#showMapAssets()")) {
                        checked++;
                        if (StringUtils.countMatches(source, "#showContentAssets()") != 1) {
                            missing.add(vm.toString());
                        }
                    }
                }
            }
        }
        assertEquals(18, checked,
                "the map assets are called from 18 heads (5 per article theme, frontpage's "
                        + "header, the two shared fallbacks); a change in that count needs a "
                        + "look at whether the new head renders content");
        assertTrue(missing.isEmpty(), "heads without exactly one content-asset call: " + missing);
    }

    // ------------------------------------------------------------ the seam

    /**
     * The other half of the contract: what the renderer emits is what the
     * macro styles and the bundle looks for, inside a container the macro
     * names as content. If either side renamed a class, callouts and code
     * blocks would go plain in silence.
     */
    @Test
    void markdownContentReachesTheReaderInAContainerTheMacroNames() throws Exception {
        switchTheme("portfolio");
        entryWithText("content-seam", "## Storage tier\n\n> [!NOTE]\n> Mind the gap.\n\n"
                + "```java\nint x = 1;\n```\n\nUse `x` here.\n");
        String body = render("/" + HANDLE + "/entry/content-seam");

        assertTrue(body.contains("<div class=\"pf-entry-content\">"), body);
        assertTrue(body.contains("<h2 id=\"storage-tier\">Storage tier</h2>"), body);
        assertTrue(body.contains("class=\"markdown-alert markdown-alert-note\""), body);
        assertTrue(body.contains("<p class=\"markdown-alert-title\">"), body);
        assertTrue(body.contains("<pre><code class=\"language-java\">"), body);
        assertTrue(body.contains("<code>x</code>"), body);
        assertTrue(body.contains(".pf-entry-content"),
                "the macro must name portfolio's content container in its scope:\n" + body);
        assertAssetsExactlyOnce(body, "portfolio permalink with code and a callout");
    }

    // ---------------------------------------------------------- the bootstrap

    /**
     * The bundle URL is unversioned by name, and a cached bundle from before
     * the PressroomContent rename defines the wrong global, so the URL carries
     * the application version as a query string: a release changes it and
     * every browser and CDN fetches the new bundle.
     */
    @Test
    void theBundleUrlCarriesTheApplicationVersion() throws Exception {
        String version = com.wikantik.pressroom.business.Colophon.of(
                null, null, java.time.Clock.systemDefaultZone()).version();
        assertFalse(version == null || version.isEmpty(), "the packaged version is the expected one");

        String body = render("/" + HANDLE);

        assertTrue(body.contains(BUNDLE + "?v=" + version + "';"),
                "the bundle URL must carry ?v=<application version>:\n" + body);
    }

    /**
     * Lazy, re-runnable and localised. The bundle is only injected after the
     * presence check finds an unprocessed code block or content heading; the
     * initialiser chains onto the preview seam so a swapped-in preview
     * fragment is enhanced too; and the words on the controls are the
     * weblog's, from the message bundle, escaped into attributes.
     */
    @Test
    void theBootstrapIsLazyRerunnableAndLocalised() throws Exception {
        String body = render("/" + HANDLE);

        assertTrue(body.contains("script.src = '/pressroom" + BUNDLE + "?v="),
                "the bundle must load under the context path, and only from the "
                        + "bootstrap's injected script element:\n" + body);
        assertFalse(body.contains("<script src=\"/pressroom" + BUNDLE),
                "the bundle must never be a static script tag -- it is lazy:\n" + body);
        assertTrue(body.contains("pre:not([data-pressroom-code]) > code"),
                "the presence check must skip blocks already enhanced:\n" + body);
        assertTrue(body.contains(":is(h2, h3, h4)[id]:not([data-pressroom-anchor])"),
                "the presence check must skip headings already anchored:\n" + body);
        assertTrue(body.contains("pressroomInitContent(document)"),
                "the public page must still enhance once on load:\n" + body);
        assertTrue(body.contains("pressroomInitContent(root);"),
                "the initialiser must chain onto the preview seam:\n" + body);
        assertTrue(body.contains("data-copy=\"Copy\""), body);
        assertTrue(body.contains("data-copied=\"Copied\""), body);
        assertTrue(body.contains("data-copy-failed=\"Copy failed\""), body);
        assertTrue(body.contains("data-anchor=\"Link to this section\""), body);
        assertNoLeaks(body, "journal weblog.vm");
    }

    // --------------------------------------------------------------- the CSS

    /**
     * Every palette value is a {@code light-dark()} pair, so the one style
     * block suits a dark theme and a light one without per-theme rules. A
     * browser that does not know {@code light-dark()} drops the whole
     * declaration, so each one must be preceded, in the same rule, by a
     * declaration of the same property that stays legible on any canvas
     * (an inherited text colour, a translucent tint).
     */
    @Test
    void everyLightDarkValueHasAFallbackDeclarationBeforeIt() throws Exception {
        String css = contentStyleBlock(render("/" + HANDLE));
        Matcher rule = Pattern.compile("\\{([^{}]*)\\}").matcher(css);
        int pairs = 0;
        List<String> unguarded = new ArrayList<>();
        while (rule.find()) {
            String previousProperty = null;
            for (String declaration : rule.group(1).split(";")) {
                String trimmed = declaration.trim();
                int colon = trimmed.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String property = trimmed.substring(0, colon).trim();
                if (trimmed.contains("light-dark(")) {
                    pairs++;
                    if (!property.equals(previousProperty)) {
                        unguarded.add(trimmed);
                    }
                }
                previousProperty = property;
            }
        }
        // 5 callouts x (border, tint, title) + 9 token groups + 2 x 2 for the diff lines
        assertTrue(pairs >= 28, "expected the callout, token and diff palettes, saw " + pairs);
        assertTrue(unguarded.isEmpty(), "light-dark() with no fallback before it: " + unguarded);
    }

    /**
     * A background is never set without the text colour that goes with it:
     * the tints are translucent mixes of the inherited text colour (so they
     * follow whatever canvas the theme paints), and the two opaque
     * backgrounds -- diff additions and deletions -- set their own text
     * colour from the same light-dark scheme in the same rule.
     */
    @Test
    void everyOpaqueBackgroundCarriesItsOwnTextColour() throws Exception {
        String css = contentStyleBlock(render("/" + HANDLE));
        Matcher rule = Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(css);
        List<String> unpaired = new ArrayList<>();
        while (rule.find()) {
            String declarations = rule.group(2);
            for (String declaration : declarations.split(";")) {
                String trimmed = declaration.trim();
                if (trimmed.startsWith("background") && trimmed.contains("light-dark(")
                        && !trimmed.contains("color-mix(")
                        && !Pattern.compile("(^|[;\\s])color:\\s*light-dark\\(").matcher(declarations).find()) {
                    unpaired.add(rule.group(1).trim() + " { " + trimmed + " }");
                }
            }
        }
        assertTrue(unpaired.isEmpty(), "opaque backgrounds without a matching text colour: " + unpaired);
    }

    @Test
    void theStylesAreScopedToContentAndKeepThemeRulesInCharge() throws Exception {
        String css = contentStyleBlock(render("/" + HANDLE));
        String scope = ".qj-prose, .tg-entry-content, .pf-entry-content, .pressroom-preview-prose, "
                + ".pressroom-content";
        assertTrue(css.contains(":where(" + scope + ") pre {"),
                "code blocks are styled inside content only, at zero added specificity:\n" + css);
        assertTrue(css.contains(":where(" + scope + ") :not(pre) > code {"),
                "inline code becomes a chip inside content only:\n" + css);
        assertTrue(css.contains(":where(" + scope + ") table {"), css);
        for (String type : List.of("note", "tip", "important", "warning", "caution")) {
            assertTrue(css.contains(".markdown-alert-" + type + " {"), type + ":\n" + css);
        }
        assertTrue(css.contains(".pressroom-code-lines {") && css.contains("user-select: none;"),
                "line numbers must never be selectable:\n" + css);
    }

    /**
     * Authors (and agents) write boxes as inline styles: a pale background and
     * no text colour of their own, written for the dark ink of a light page.
     * In a dark scheme the box inherited the theme's light ink, light on
     * light, which phones in dark mode could not read (2026-11-07: 115 such
     * boxes across the journal blogs). Such a box keeps a dark ink in every
     * scheme, and so do the links, quotes and captions in it, which themes
     * colour themselves. A box that names its own text colour, or whose
     * background is dark, is left alone.
     *
     * <p>The markup goes through the real pipeline, so the selectors are
     * checked against the style attribute the sanitizer actually emits.
     */
    @Test
    void anAuthorsLightBoxKeepsADarkInkInEveryScheme() throws Exception {
        entryWithText("light-box", """
                <div style="Background-Color: #F0F7FF; border-left: 4px solid #0066cc; padding: 14px 18px">
                <p>Plan ahead: <a href="https://example.com/">book here</a>.</p></div>
                <div style="background:#f8f9fa;border:1px solid #e9ecef"><p>Grey box.</p></div>
                <div style="background-color:#f0f7ff;color:#1a202c"><p>Already inked.</p></div>
                <div style="background:#161618;color:#4fb3aa"><p>Dark chip.</p></div>
                """);
        String body = render("/" + HANDLE + "/entry/light-box");
        String css = contentStyleBlock(body);

        String box = selectorOf(css, "color: #1a202c !important;");
        assertTrue(matches(box, "", styleBefore(body, "Plan ahead")), box + " vs " + styleBefore(body, "Plan ahead"));
        assertTrue(matches(box, "", styleBefore(body, "Grey box.")), box + " vs " + styleBefore(body, "Grey box."));
        assertFalse(matches(box, "", styleBefore(body, "Already inked.")), "the author's own colour stands");
        assertFalse(matches(box, "", styleBefore(body, "Dark chip.")), "a dark background keeps its own ink");

        String link = selectorOf(css, "color: #0a3d91 !important;");
        assertTrue(link.endsWith(" a:not([style^=\"color:\" i], [style*=\";color:\" i])"), link);
        assertTrue(matches(link, styleBefore(body, "Plan ahead"), ""), link);
        assertFalse(matches(link, styleBefore(body, "Plan ahead"), "color:#0066cc"), "a link's own colour stands");
        String quiet = selectorOf(css, "color: #3d4451 !important;");
        assertTrue(quiet.contains("blockquote") && quiet.contains("figcaption"), quiet);
        assertTrue(matches(quiet, styleBefore(body, "Grey box."), ""), quiet);

        // WCAG: AAA for text, AA at least for the rest, on the palest and the
        // darkest backgrounds the selector counts as light.
        for (String background : List.of("#ffffff", "#f0f7ff", "#f8f9fa", "#c0c0c0")) {
            assertTrue(contrast("#1a202c", background) >= 7, "ink on " + background);
            assertTrue(contrast("#0a3d91", background) >= 4.5, "link on " + background);
            assertTrue(contrast("#3d4451", background) >= 4.5, "quote on " + background);
        }
    }

    /**
     * The other half of the same mistake: a translucent background shows the
     * page through it, so a text colour the author chose for one canvas fails
     * on the other (light ink over an 8% tint, written for a dark page, is
     * light on white in a light scheme). Text over a translucent background
     * follows the page's ink: the box's own colour, and a colour its children
     * name (the live boxes colour their paragraph too), unless the child paints
     * a background of its own, like a dark code chip.
     */
    @Test
    void anAuthorsTranslucentBoxTakesThePagesInk() throws Exception {
        entryWithText("tint-box", """
                <div style="background-color: rgba(79, 179, 170, 0.08); border-left: 4px solid #4fb3aa; color: #e8e8e6">
                <strong>Tinted note.</strong><p style="margin: 6px 0 0 0; color: #e8e8e6">Child ink.</p>
                <code style="background:#161618;color:#4fb3aa">chip</code></div>
                <div style="background-color:rgba(0,0,0,0.05)"><p>Plain tint.</p></div>
                """);
        String body = render("/" + HANDLE + "/entry/tint-box");
        String css = contentStyleBlock(body);

        String tint = selectorOf(css, "color: inherit !important;");
        String box = styleBefore(body, "Tinted note.");
        assertTrue(matches(tint, "", box), tint + " vs " + box);
        assertFalse(matches(tint, "", styleBefore(body, "Plain tint.")), "no colour of its own: it already follows the page");
        assertTrue(matches(tint, box, styleBefore(body, "Child ink.")), "a child's colour follows the page too");
        assertFalse(matches(tint, box, styleBefore(body, "chip")), "a child with its own background keeps its ink");
    }

    // ---------------------------------------------------------------- helpers

    /** The selector of the one rule in the style block that carries {@code declaration}. */
    private static String selectorOf(String css, String declaration) {
        Matcher rule = Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(css);
        List<String> found = new ArrayList<>();
        while (rule.find()) {
            if (rule.group(2).contains(declaration)) {
                found.add(rule.group(1).trim());
            }
        }
        assertEquals(1, found.size(), "one rule must declare " + declaration + ": " + found);
        return found.get(0);
    }

    /** The style attribute of the last element opened before {@code text}, as the reader receives it. */
    private static String styleBefore(String body, String text) {
        int at = body.indexOf(text);
        assertTrue(at > 0, "no " + text + " in the page");
        int open = body.lastIndexOf("style=\"", at);
        assertTrue(open > 0, "no inline style before " + text);
        int start = open + "style=\"".length();
        return body.substring(start, body.indexOf('"', start)).replace("&#39;", "'");
    }

    private static final Pattern STYLE_TEST = Pattern.compile("\\[style([*^])=\"([^\"]*)\" i]");

    /**
     * Whether a browser would apply the rule to an element with
     * {@code subjectStyle} inside a box with {@code boxStyle}, as far as the
     * {@code [style]} tests go. Each comma-separated selector is one
     * alternative; in each, the compound after the last descendant space is
     * tested against the subject and everything before it against the box.
     * Every {@code :is(...)} group of {@code [style]} tests needs one that
     * matches, no {@code :not(...)} group may match. Case-insensitive, as
     * the {@code i} flag says.
     */
    private static boolean matches(String selector, String boxStyle, String subjectStyle) {
        assertTrue(STYLE_TEST.matcher(selector).find(), "no [style] tests in " + selector);
        for (String alternative : splitTopLevel(selector, ',')) {
            String trimmed = alternative.trim();
            List<String> parts = splitTopLevel(trimmed, ' ');
            String subject = parts.get(parts.size() - 1);
            String ancestors = String.join(" ", parts.subList(0, parts.size() - 1));
            if (satisfies(ancestors, boxStyle) && satisfies(subject, subjectStyle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean satisfies(String compound, String style) {
        String lower = style.toLowerCase(Locale.ROOT);
        Matcher group = Pattern.compile(":(is|not)\\((\\[style[^)]*)\\)").matcher(compound);
        while (group.find()) {
            boolean any = false;
            Matcher test = STYLE_TEST.matcher(group.group(2));
            while (test.find()) {
                String needle = test.group(2).toLowerCase(Locale.ROOT);
                any |= "^".equals(test.group(1)) ? lower.startsWith(needle) : lower.contains(needle);
            }
            if ("is".equals(group.group(1)) != any) {
                return false;
            }
        }
        Matcher bare = Pattern.compile("(^|[^(,\\s])(\\[style\\*=\"[^\"]*\" i])").matcher(compound);
        while (bare.find()) {
            Matcher test = STYLE_TEST.matcher(bare.group(2));
            if (test.find() && !lower.contains(test.group(2).toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /** {@code text} split at {@code separator} outside parentheses and quotes. */
    private static List<String> splitTopLevel(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == separator) {
                if (i > start) {
                    parts.add(text.substring(start, i));
                }
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** WCAG 2 contrast ratio of two #rrggbb colours. */
    private static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        double[] weights = {0.2126, 0.7152, 0.0722};
        double sum = 0;
        for (int i = 0; i < 3; i++) {
            double c = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
            sum += weights[i] * (c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        return sum;
    }


    private void assertEveryContentTemplate(String theme) throws Exception {
        assertAssetsExactlyOnce(render("/" + HANDLE), theme + " weblog.vm");
        entryWithText(theme + "-entry", "Plain text.");
        assertAssetsExactlyOnce(render("/" + HANDLE + "/entry/" + theme + "-entry"),
                theme + " permalink.vm");
        assertAssetsExactlyOnce(search(), theme + " searchresults.vm");
        savePage(theme + "-page", "A page", "Some **text**.");
        assertAssetsExactlyOnce(render("/" + HANDLE + "/" + theme + "-page"),
                theme + " page.vm");
        assertAssetsExactlyOnce(previewShell(), theme + " preview.vm");
    }

    private static void assertAssetsExactlyOnce(String body, String where) {
        assertOnce(body, BUNDLE, where, "the bundle URL");
        assertOnce(body, INITIALISER, where, "the initialiser");
        assertOnce(body, CALLOUT_RULE, where, "the content style block");
        assertNoLeaks(body, where);
    }

    private static void assertNoLeaks(String body, String where) {
        assertFalse(body.contains("#showContentAssets"), where + " leaked the macro call:\n" + body);
        assertFalse(body.contains("$pressroomContent"), where + " leaked a macro variable:\n" + body);
        assertFalse(body.contains("$text."), where + " leaked a message reference:\n" + body);
        assertFalse(body.contains("$utils."), where + " leaked a utility reference:\n" + body);
        assertFalse(body.contains("$url."), where + " leaked a url reference:\n" + body);
    }

    private static void assertOnce(String body, String needle, String where, String what) {
        assertEquals(1, StringUtils.countMatches(body, needle),
                where + " must carry " + what + " exactly once:\n" + body);
    }

    /** The macro's one style block: the one that styles the callouts. */
    private static String contentStyleBlock(String body) {
        int rule = body.indexOf(CALLOUT_RULE);
        assertTrue(rule > 0, "no content style block:\n" + body);
        int start = body.lastIndexOf("<style>", rule);
        int end = body.indexOf("</style>", rule);
        return body.substring(start + "<style>".length(), end);
    }

    private String render(String pathInfo) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "page must render for " + pathInfo);
        return response.getContentAsString();
    }

    private String search() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/search", "/" + HANDLE);
        request.setParameter("q", "anything");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);
        assertEquals(200, response.getStatus());
        return response.getContentAsString();
    }

    private String previewShell() throws Exception {
        MockHttpServletRequest request =
                RenderingTestSupport.anonymousGet(PREVIEW_SERVLET, "/" + HANDLE + "/");
        request.setParameter("shell", "true");
        MockHttpServletResponse response;
        RenderingTestSupport.signInAs(user.getUserName());
        try {
            response = RenderingTestSupport.execute(RenderingTestSupport.previewServlet(), request);
        } finally {
            RenderingTestSupport.signOut();
        }
        assertEquals(200, response.getStatus(), "the preview shell must render");
        return response.getContentAsString();
    }

    private void switchTheme(String themeName) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(themeName);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private void entryWithText(String anchor, String text) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, weblog, user);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setText(text);
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
    }

    private void savePage(String slug, String title, String content) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent(content);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }
}
