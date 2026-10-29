package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a preview warns an author about: markup that renders, but badly on
 * some theme or for some reader. The input is what the sanitizer emits,
 * which is what a preview has in hand.
 */
class PreviewLintTest {

    @Test
    void cleanMarkdownOutputHasNothingToSay() {
        assertEquals(List.of(), PreviewLint.warnings(
                "<h2 id=\"a\">A</h2><p>text with <code>x</code></p>"
                        + "<div class=\"markdown-alert markdown-alert-note\"><p class=\"markdown-alert-title\">Note</p></div>"
                        + "<pre><code class=\"language-java\">int x;</code></pre>"));
        assertEquals(List.of(), PreviewLint.warnings(""));
    }

    /**
     * The first agent post's callout: a pale background with no text color,
     * so the dark theme's near-white text sat on it at about 1.1:1.
     */
    @Test
    void anInlineBackgroundIsNamedWithTheAlertToUseInstead() {
        List<String> warnings = PreviewLint.warnings(
                "<div style=\"background-color:#f0f7ff;border-left:4px solid #0066cc\"><p>Insight</p></div>"
                        + "<p>fine</p><figcaption style=\"font-size:0.9em;color:#666\">cap</figcaption>");

        assertEquals(1, warnings.size(), warnings.toString());
        String warning = warnings.get(0);
        assertTrue(warning.startsWith("2 elements set a text color or background inline"), warning);
        assertTrue(warning.contains("<div style=\"background-color:#f0f7ff;border-left:4px solid #0066cc\">"),
                "names the first: " + warning);
        assertTrue(warning.contains("> [!NOTE]"), warning);
    }

    @Test
    void stylesThatDoNotColorAreFine() {
        assertEquals(List.of(), PreviewLint.warnings(
                "<img style=\"max-width:100%;height:auto;border-radius:8px\" />"
                        + "<p style=\"margin:6px 0 0 0;border-left:4px solid #0066cc\">x</p>"
                        + "<span data-xstyle=\"color:red\">an attribute that only ends in style</span>"));
    }

    @Test
    void theShorthandBackgroundCountsAndOneElementIsSingular() {
        List<String> warnings = PreviewLint.warnings("<span style=\"font-weight:bold;background:#fff\">x</span>");

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("1 element sets a text color or background inline"), warnings.get(0));
    }

    @Test
    void aCodeBlockThatNamesNoLanguageIsCounted() {
        List<String> warnings = PreviewLint.warnings(
                "<pre><code>plain</code></pre><pre>raw html pre</pre>"
                        + "<pre><code class=\"language-sql\">select 1</code></pre>");

        assertEquals(List.of("2 code blocks name no language, so readers see them without highlighting. "
                + "Name it after the opening fence, for example ```java."), warnings);
    }

    @Test
    void bothProblemsAreBothReported() {
        assertEquals(2, PreviewLint.warnings("<p style=\"color:red\">x</p><pre><code>y</code></pre>").size());
    }
}
