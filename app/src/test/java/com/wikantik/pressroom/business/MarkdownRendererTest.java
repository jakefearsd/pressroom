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
package com.wikantik.pressroom.business;

import java.util.List;
import java.util.Locale;
import com.wikantik.pressroom.util.HTMLSanitizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The markdown conversion itself: the extension set that is on, the raw-HTML
 * passthrough the shortcodes depend on, and the explicit note that commonmark
 * is not the security boundary.
 */
class MarkdownRendererTest {


    @Test
    void blankInputComesBackUnchanged() {
        assertNull(MarkdownRenderer.render(null));
        assertEquals("", MarkdownRenderer.render(""));
        assertEquals("   ", MarkdownRenderer.render("   "));
    }

    @Test
    void rendersTheBasics() {
        assertEquals("<h1 id=\"iceland\">Iceland</h1>\n", MarkdownRenderer.render("# Iceland"));
        assertEquals("<p>A <strong>long</strong> drive.</p>\n",
                MarkdownRenderer.render("A **long** drive."));
        assertTrue(MarkdownRenderer.render("- one\n- two").contains("<li>one</li>"));
    }

    @Test
    void theThreeExtensionsAreOn() {
        assertTrue(MarkdownRenderer.render("| a | b |\n| - | - |\n| 1 | 2 |").contains("<table>"),
                "tables extension");
        assertTrue(MarkdownRenderer.render("~~cancelled~~").contains("<del>"),
                "strikethrough extension");
        assertTrue(MarkdownRenderer.render("See https://example.com for details")
                        .contains("<a href=\"https://example.com\">"),
                "autolink extension");
    }

    /**
     * GitHub's alert syntax is how an author (or an agent) writes a callout
     * without inline colors, which a dark theme turns illegible: the theme
     * styles the class instead.
     */
    @Test
    void aGitHubAlertBecomesACalloutTheThemeStyles() {
        for (String type : List.of("NOTE", "TIP", "IMPORTANT", "WARNING", "CAUTION")) {
            String html = HTMLSanitizer.sanitize(MarkdownRenderer.render("> [!" + type + "]\n> Mind the gap."));

            String lower = type.toLowerCase(Locale.ROOT);
            assertTrue(html.contains("class=\"markdown-alert markdown-alert-" + lower + "\""), html);
            assertTrue(html.contains("Mind the gap."), html);
            assertFalse(html.contains("[!" + type + "]"), "no marker may reach a reader: " + html);
            assertFalse(html.contains("<blockquote"), html);
        }
    }

    /** A heading carries an id, so a section can be linked to: entry permalink plus #storage-tier. */
    @Test
    void headingsCarryAnIdThatSurvivesTheSanitizer() {
        String html = HTMLSanitizer.sanitize(MarkdownRenderer.render("## Storage tier\n\ntext\n\n## Storage tier\n"));

        assertTrue(html.contains("<h2 id=\"storage-tier\">Storage tier</h2>"), html);
        assertTrue(html.contains("<h2 id=\"storage-tier-1\">Storage tier</h2>"), "a repeated heading: " + html);
    }

    /**
     * The reader's code-block script highlights and labels by this class.
     * Characterisation: it passed before that script existed, pinning what
     * the script relies on.
     */
    @Test
    void aFencedBlockKeepsItsLanguageThroughTheSanitizer() {
        String html = HTMLSanitizer.sanitize(MarkdownRenderer.render("```java\nint x = 1;\n```\n"));

        assertTrue(html.contains("<pre><code class=\"language-java\">"), html);
    }

    @Test
    void rawHtmlPassesThroughUntouched() {
        // Authors paste embed snippets, and shortcodes expand into HTML right
        // after this step. Escaping here would show both as source text.
        String html = MarkdownRenderer.render("Before\n\n<div class=\"embed\">kept</div>\n\nAfter");
        assertTrue(html.contains("<div class=\"embed\">kept</div>"),
                "raw block HTML must survive: " + html);
        assertFalse(html.contains("&lt;div"), "and must not be escaped: " + html);
    }

    @Test
    void markdownIsNotTheSecurityBoundary() {
        // Documented expectation, pinned so nobody "fixes" it by turning on
        // escaping and assuming that made things safe: commonmark emits the
        // script tag, and HTMLSanitizer -- which runs after -- removes it.
        // WeblogEntryMarkdownRenderingTest proves the removal end to end.
        assertTrue(MarkdownRenderer.render("<script>alert(1)</script>").contains("<script>"),
                "commonmark passes raw HTML through by design; the sanitizer is the boundary");
    }
}
