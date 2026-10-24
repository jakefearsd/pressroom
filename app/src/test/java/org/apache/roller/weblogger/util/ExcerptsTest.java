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
package org.apache.roller.weblogger.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line of prose an entry list shows under a title when the author wrote
 * no summary: the opening sentences of the post's own paragraphs, as plain
 * text. The input is always rendered, sanitized HTML (an entry's transformed
 * text), so these cases are written the way commonmark and the shortcodes
 * emit it.
 */
class ExcerptsTest {

    @Test
    void theOpeningIsTheFirstTwoSentences() {
        String html = "<p>Every search took half a second, and nobody had noticed for a year. "
                + "The profiler found it in the first minute. "
                + "This third sentence is the rest of the post.</p>\n";

        assertEquals("Every search took half a second, and nobody had noticed for a year. "
                + "The profiler found it in the first minute.", Excerpts.opening(html, Locale.US));
    }

    @Test
    void headingsCodeFiguresAndAsidesAreNotTheOpening() {
        // A post that starts with a heading, a code block, an image with a
        // caption, a callout and a quotation still opens with its first
        // paragraph of the writer's own prose.
        String html = "<h2 id=\"intro\">Introduction</h2>\n"
                + "<pre><code>SELECT 1;</code></pre>\n"
                + "<figure class=\"roller-figure\"><img src=\"a.jpg\" alt=\"A\"><figcaption>A caption.</figcaption></figure>\n"
                + "<div class=\"markdown-alert markdown-alert-note\"><p class=\"markdown-alert-title\">Note</p>"
                + "<p>An aside.</p></div>\n"
                + "<blockquote><p>Somebody else's words.</p></blockquote>\n"
                + "<ul><li><p>A list item.</p></li></ul>\n"
                + "<p>The real opening is here, and it is long enough on its own to stand as the summary. "
                + "It has a second sentence too.</p>\n";

        assertEquals("The real opening is here, and it is long enough on its own to stand as the summary. "
                + "It has a second sentence too.", Excerpts.opening(html, Locale.US));
    }

    @Test
    void shortSentencesTakeAThirdSoTheRowIsNotABareFragment() {
        String html = "<p>Hi. I'm back. This post explains what changed in the storage layer between the spring "
                + "release and this one. And a fourth sentence that is not needed.</p>";

        assertEquals("Hi. I'm back. This post explains what changed in the storage layer between the spring "
                + "release and this one.", Excerpts.opening(html, Locale.US));
    }

    @Test
    void theOpeningRunsOnIntoTheNextParagraph() {
        String html = "<p>One paragraph, one sentence, and not much else in it.</p>\n"
                + "<p>The second paragraph supplies the next sentence. Not this one.</p>\n";

        assertEquals("One paragraph, one sentence, and not much else in it. "
                + "The second paragraph supplies the next sentence.", Excerpts.opening(html, Locale.US));
    }

    @Test
    void aSentenceThatWouldOverrunTheLimitIsLeftOut() {
        String longSentence = "Then" + " word".repeat(70) + ".";
        String html = "<p>A first sentence of a good length, which says enough on its own to stand as the "
                + "summary of the post. " + longSentence + "</p>";

        assertEquals("A first sentence of a good length, which says enough on its own to stand as the "
                + "summary of the post.", Excerpts.opening(html, Locale.US));
    }

    @Test
    void aShortOpeningRunsIntoTheNextSentenceCutAtAWord() {
        // "In April 2026, Wikantik completed its fork." alone said almost
        // nothing; the long sentence after it is where the post begins.
        String text = "In April 2026, Wikantik completed its fork. "
                + "The first release resolved the runtime migration" + " and the framework".repeat(40) + ".";

        String opening = Excerpts.opening("<p>" + text + "</p>", Locale.US);

        assertTrue(opening.startsWith("In April 2026, Wikantik completed its fork. The first release resolved"),
                opening);
        assertTrue(opening.length() <= Excerpts.MAX_CHARS, opening);
        assertTrue(opening.endsWith("…") && text.startsWith(opening.substring(0, opening.length() - 1) + " "),
                "cut after a whole word: " + opening);
    }

    @Test
    void anOverlongFirstSentenceIsCutAtAWordWithAnEllipsis() {
        String html = "<p>" + "abcdefghi ".repeat(60).trim() + ".</p>";

        String opening = Excerpts.opening(html, Locale.US);

        assertTrue(opening.length() <= Excerpts.MAX_CHARS, opening);
        assertTrue(opening.endsWith("abcdefghi…"), "cut after a whole word: " + opening);
    }

    @Test
    void entitiesComeBackAsTextAndWhitespaceCollapses() {
        // Plain text, not markup: the template escapes it, exactly once.
        String html = "<p>Don&#39;t  &amp;\n won&rsquo;t<br>stop &lt;here&gt;.</p>";

        assertEquals("Don't & won’t stop <here>.", Excerpts.opening(html, Locale.US));
    }

    @Test
    void inlineMarkupKeepsItsWordsTogether() {
        String html = "<p>The <em>first</em> run used <code>Thread.sleep</code> and a "
                + "<a href=\"https://example.com\">polling loop</a>.</p>";

        assertEquals("The first run used Thread.sleep and a polling loop.", Excerpts.opening(html, Locale.US));
    }

    @Test
    void aPostWithNoParagraphsFallsBackToItsOtherText() {
        String html = "<h2>Packing list</h2><ul><li>Boots</li><li>Rain shell</li></ul><pre><code>x</code></pre>";

        assertEquals("Boots Rain shell", Excerpts.opening(html, Locale.US));
    }

    @Test
    void nothingToSayIsNull() {
        assertNull(Excerpts.opening(null, Locale.US));
        assertNull(Excerpts.opening("", Locale.US));
        assertNull(Excerpts.opening("<p><img src=\"a.jpg\" alt=\"\"></p>", Locale.US));
    }

    @Test
    void plainTextIsEveryWordOfTheMarkupInOrder() {
        // An author's own summary is shown whole: plain text, never cut.
        String html = "<p>Just a <strong>taste</strong> of it.</p>\n<h2>Then</h2><p>And more&hellip;</p>";

        assertEquals("Just a taste of it. Then And more…", Excerpts.plainText(html));
        assertNull(Excerpts.plainText("  "));
        assertNull(Excerpts.plainText(null));
    }
}
