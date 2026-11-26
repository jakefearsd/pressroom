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
package com.wikantik.pressroom.ui.controllers.editor;

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
}
