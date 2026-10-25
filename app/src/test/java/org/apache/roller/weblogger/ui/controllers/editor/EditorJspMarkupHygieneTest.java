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
package org.apache.roller.weblogger.ui.controllers.editor;

import java.io.IOException;
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
 * Markup-hygiene ratchets over the editor JSP tree.
 *
 * <p>Each scan below pins a defect class the 2026-08-20 sweep found and
 * repaired, so the repair cannot quietly rot back in. All three collect every
 * violation before asserting once, for the reason
 * {@link EditorJspScriptBindingTest} states: an assertion inside the loop can
 * only ever name one offender per run.
 */
class EditorJspMarkupHygieneTest {

    private static final Path EDITOR_JSP_DIR =
            Path.of("src/main/webapp/WEB-INF/jsps/editor");

    private static List<Path> editorJsps() throws IOException {
        try (Stream<Path> files = Files.list(EDITOR_JSP_DIR)) {
            return files.filter(p -> p.toString().endsWith(".jsp")).sorted().toList();
        }
    }

    /**
     * A positive {@code tabindex} takes the element out of DOM order and puts
     * it ahead of every untabbed control on the page, which is almost never
     * what the author meant: MediaFileEdit's alt-text field, added later
     * without a number, tabbed AFTER the Cancel button. DOM order is correct
     * on these forms, so the right value is no value at all ({@code -1} to
     * remove something from the order, as the modals do, is still fine).
     */
    @Test
    void noControlOverridesTabOrder() throws Exception {
        List<String> violations = new ArrayList<>();
        Pattern positive = Pattern.compile("tabindex=\"[1-9]");
        for (Path jsp : editorJsps()) {
            Matcher m = positive.matcher(Files.readString(jsp));
            while (m.find()) {
                violations.add(jsp.getFileName() + ": positive tabindex overrides DOM order");
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /** Presentational attributes HTML dropped: {@code border}, {@code align},
     *  and {@code <hr size noshade>}. They do nothing in a Bootstrap page and
     *  read as markup nobody has looked at since. */
    @Test
    void noDeprecatedPresentationalAttributes() throws Exception {
        List<String> violations = new ArrayList<>();
        Pattern deprecated = Pattern.compile("\\b(border|align)=\"|<hr[^>]*\\b(size|noshade)=");
        for (Path jsp : editorJsps()) {
            Matcher m = deprecated.matcher(Files.readString(jsp));
            while (m.find()) {
                violations.add(jsp.getFileName() + ": deprecated presentational attribute "
                        + m.group());
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /**
     * An {@code <a href="#">} that runs script is a button wearing a link's
     * clothes: it lands in the "links" list of a screen reader, it offers a
     * meaningless "open in new tab", and it moves the page to the top of the
     * document if the handler ever fails to cancel the default. Task 3
     * converted the author-text-interpolating ones; this closes the set.
     */
    @Test
    void noAnchorActsAsAButton() throws Exception {
        List<String> violations = new ArrayList<>();
        Pattern anchor = Pattern.compile("<a\\b[^>]*href=\"#\"[^>]*>", Pattern.CASE_INSENSITIVE);
        for (Path jsp : editorJsps()) {
            Matcher m = anchor.matcher(Files.readString(jsp));
            while (m.find()) {
                String tag = m.group();
                // data-bs-toggle anchors are Bootstrap's own collapse/dropdown
                // widgets: the framework gives them button semantics and
                // keyboard handling, and href="#" is what its docs prescribe.
                if (tag.contains("data-bs-toggle")) {
                    continue;
                }
                if (tag.toLowerCase(java.util.Locale.ROOT).contains("onclick")) {
                    violations.add(jsp.getFileName()
                            + ": anchor with href=\"#\" runs script; use a <button>");
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /**
     * Hardcoded English the sweep of 2026-08-20 routed through the bundle.
     * Scoped deliberately narrowly -- these are the exact literals that were
     * fixed, not a general "no English in a JSP" rule, which would drown in
     * class names and false positives. A tripwire, not a policy.
     *
     * <p>MessageKeyTest owns the general JSP arm (every {@code code=} resolves);
     * it cannot see a string that never became a key in the first place, which
     * is exactly the gap this covers.
     */
    @Test
    void noHardcodedEnglishAtTheSitesTheSweepFixed() throws Exception {
        List<String> banned = List.of(
                "aria-label=\"Close\"",
                ">Close</button>",
                "Newer",
                "Older",
                "Are you sure you want to leave?",
                "Show full message",
                "Link changed, not launching page",
                "\" file, \"",
                "\" files, \"",
                "\" total\"",
                ">Thumbnail<",
                ">URL<",
                "alt=\"Copy to clipboard\"",
                "alt=\"thumbnail\"");
        List<String> violations = new ArrayList<>();
        for (Path jsp : editorJsps()) {
            String src = Files.readString(jsp);
            for (String literal : banned) {
                if (src.contains(literal)) {
                    violations.add(jsp.getFileName() + ": hardcoded English " + literal);
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /**
     * The copy/view affordances Task 18 added, pinned by their markup: each
     * is a one-line addition that a later edit could drop without any test
     * noticing, and every one of them exists because the thing an author
     * actually wanted off the screen was unreachable text.
     */
    @Test
    void theCopyAndViewAffordancesArePresent() throws Exception {
        List<String> missing = new ArrayList<>();
        record Expected(String jsp, String needle, String what) { }
        List<Expected> expected = List.of(
                new Expected("MediaFileAddSuccess.jsp", "data-clipboard-target=\"#clip_image_",
                        "the just-uploaded URL must be copyable"),
                new Expected("MediaFileEdit.jsp", "clip_shortcode",
                        "the canonical [image] embed string must be shown and copyable"),
                // The address moved onto the writing surface when the page
                // editor took the entry editor's layout: the weblog's own
                // root as a prefix span, the slug as the field beside it, and
                // the copy control on a published page. Same affordance, two
                // halves; the old single anchor (#page_permalink) also showed
                // a "/page/<slug>" URL that a WeblogPage is not served at.
                new Expected("PageEdit.jsp", "class=\"editor-slug-prefix\"",
                        "a page must show its own URL, rooted at the weblog"),
                new Expected("PageEdit.jsp", "data-clipboard-text=",
                        "a published page's URL must be copyable"),
                // The bare single segment a WeblogPage is served at -- NOT
                // "/page/<slug>", which is the CUSTOM-template route. This
                // needle pinned the wrong shape until task M1; see
                // PageEditJspTest.thePagesListLinksAPageWhereTheEditorSaysItLives
                // for why the two screens are compared against each other.
                new Expected("Pages.jsp", "${fn:escapeXml(urls.staticPage(actionWeblog, p.slug))}",
                        "a published page's slug must link to the live page, with the "
                                + "author-controlled slug escaped in the href (a page slug is "
                                + "restricted only against '/' and reserved names, not against "
                                + "quote/angle-bracket characters)"),
                new Expected("ThemeEdit.jsp", "themeEditor.viewYourBlog",
                        "a persistent view-the-blog link, not only the Preview button"),
                new Expected("WeblogConfig.jsp", "id=\"weblogAbsoluteUrl\"",
                        "the settings page must say what URL the weblog has"));
        for (Expected e : expected) {
            String src = Files.readString(EDITOR_JSP_DIR.resolve(e.jsp()));
            if (!src.contains(e.needle())) {
                missing.add(e.jsp() + ": " + e.what() + " (looked for " + e.needle() + ")");
            }
        }
        assertTrue(missing.isEmpty(), String.join("\n", missing));
    }

    /**
     * {@code navigator.clipboard} is undefined on a non-HTTPS origin -- every
     * plain-http deployment and every dev server -- so an unguarded call left
     * the copy control silently dead there, a TypeError in the console and
     * nothing on screen.
     */
    @Test
    void theClipboardApiCallIsGuarded() throws Exception {
        String src = Files.readString(EDITOR_JSP_DIR.resolve("EntryEdit.jsp"));
        assertTrue(src.contains("if (navigator.clipboard)"),
                "EntryEdit.jsp calls navigator.clipboard without a feature check");
    }

    /**
     * Status was carried by the row tint alone -- {@code .draftentry},
     * {@code .pendingentry}, {@code .scheduledentry} -- which is colour-only
     * information a screen reader never receives and a colour-blind reader
     * cannot separate. The row tints are gone (task B1: one shared
     * {@code StatusPill.jsp} include renders status as text everywhere), but
     * the status-carries-text property and the GET filter chips are both one
     * block of markup that a later edit could drop without any other test
     * noticing.
     */
    @Test
    void theEntriesListStatesStatusInTextAndOffersItAsAFilter() throws Exception {
        String src = Files.readString(EDITOR_JSP_DIR.resolve("Entries.jsp"));
        List<String> missing = new ArrayList<>();
        if (!src.contains("/WEB-INF/jsps/editor/StatusPill.jsp")) {
            missing.add("Entries.jsp: no status-pill include (colour-only rows)");
        }
        if (!src.contains("entries-status-chips")) {
            missing.add("Entries.jsp: no status filter chips");
        }
        assertTrue(missing.isEmpty(), String.join("\n", missing));
    }
}
