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
package org.apache.roller.weblogger.business.comments;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec criterion 16: a comment body is text. Whatever a reader types renders
 * as text with no executable markup; only {@code http(s)} URLs become links,
 * and blank lines become paragraphs.
 *
 * <p>The expected strings are the sanitizer's actual output, which is the
 * last step: it writes {@code <br>} as {@code <br />} and {@code =} as
 * {@code &#61;}, both of which a browser reads back unchanged.
 */
class CommentBodyRendererTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "<script>alert(1)</script>", "<img src=x onerror=alert(1)>", "javascript:alert(1)",
        "<a href=\"javascript:alert(1)\">x</a>", "data:text/html;base64,PHNjcmlwdD4=",
        "&lt;script&gt;alert(1)&lt;/script&gt;", "\u202Egnp.exe", "a\u200Bb<b>c</b>"})
    void hostileBodiesRenderAsText(String body) {
        String html = CommentBodyRenderer.toHtml(body);
        String lower = html.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("<script"), html);
        assertFalse(lower.contains("onerror="), html);
        assertFalse(lower.contains("href=\"javascript:"), html);
        assertFalse(lower.contains("href=\"data:"), html);
        assertFalse(lower.contains("<img"), html);
        assertFalse(lower.contains("<b>"), html);
        assertTrue(html.startsWith("<p>") && html.endsWith("</p>"), html);
    }

    @Test
    void markupIsShownAsTheCharactersTheReaderTyped() {
        assertEquals("<p>&lt;b&gt;bold&lt;/b&gt; &amp; &lt;i&gt;</p>",
                CommentBodyRenderer.toHtml("<b>bold</b> & <i>"));
    }

    @Test
    void anEntityTrickIsShownLiterallyNotDecoded() {
        assertEquals("<p>&amp;lt;script&amp;gt;</p>", CommentBodyRenderer.toHtml("&lt;script&gt;"));
    }

    @Test
    void rtlAndZeroWidthCharactersSurviveAsText() {
        assertEquals("<p>\u202Egnp.exe</p>", CommentBodyRenderer.toHtml("\u202Egnp.exe"));
        assertEquals("<p>a\u200Bb</p>", CommentBodyRenderer.toHtml("a\u200Bb"));
    }

    @Test
    void httpUrlsBecomeNofollowLinks() {
        assertEquals("<p>see <a href=\"https://ex.com/a?b&#61;1&amp;c&#61;2\" rel=\"nofollow ugc noopener\">"
                        + "https://ex.com/a?b&#61;1&amp;c&#61;2</a></p>",
                CommentBodyRenderer.toHtml("see https://ex.com/a?b=1&c=2"));
    }

    @Test
    void plainHttpIsLinkedToo() {
        assertEquals("<p><a href=\"http://ex.com/\" rel=\"nofollow ugc noopener\">http://ex.com/</a></p>",
                CommentBodyRenderer.toHtml("http://ex.com/"));
    }

    @Test
    void trailingPunctuationStaysOutOfTheLink() {
        assertEquals("<p>(see <a href=\"https://ex.com/a\" rel=\"nofollow ugc noopener\">https://ex.com/a</a>).</p>",
                CommentBodyRenderer.toHtml("(see https://ex.com/a)."));
        assertEquals("<p>Really <a href=\"https://ex.com\" rel=\"nofollow ugc noopener\">https://ex.com</a>?!</p>",
                CommentBodyRenderer.toHtml("Really https://ex.com?!"));
    }

    @Test
    void aUrlEndsAtAnAngleBracketOrQuote() {
        assertEquals("<p>&lt;<a href=\"https://ex.com/x\" rel=\"nofollow ugc noopener\">https://ex.com/x</a>&gt;"
                        + " &#34;<a href=\"https://ex.com/y\" rel=\"nofollow ugc noopener\">https://ex.com/y</a>&#34;</p>",
                CommentBodyRenderer.toHtml("<https://ex.com/x> \"https://ex.com/y\""));
    }

    @Test
    void aUrlEndsAtASingleQuoteAndTheQuoteIsText() {
        assertEquals("<p>it&#39;s <a href=\"https://ex.com/a\" rel=\"nofollow ugc noopener\">https://ex.com/a</a>&#39;b</p>",
                CommentBodyRenderer.toHtml("it's https://ex.com/a'b"));
    }

    /**
     * D2: a URL ends at any Unicode space or format character, not only ASCII
     * whitespace: an ideographic space (U+3000), a no-break space (U+00A0), a
     * zero-width space (U+200B) or a bidi control (U+202E) ends the link, and the
     * character stays text after it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\u3000", "\u00A0", "\u2003", "\u200B", "\u202E", "\u2028", "\uFEFF"})
    void aUnicodeSpaceOrFormatCharacterEndsAUrl(String separator) {
        String html = CommentBodyRenderer.toHtml("see https://ex.com/a" + separator + "next");
        assertTrue(html.contains("<a href=\"https://ex.com/a\" rel=\"nofollow ugc noopener\">https://ex.com/a</a>"),
                html);
        assertFalse(html.contains("next\""), "the link ran past the separator: " + html);
    }

    /**
     * D4: a closing parenthesis belongs to the URL when it balances one in it, as
     * in a Wikipedia link; an unbalanced one is the sentence's. (The sanitizer
     * writes the href's parentheses percent-encoded, the same address.)
     */
    @Test
    void aBalancedClosingParenthesisStaysInTheUrl() {
        assertEquals("<p>see <a href=\"https://en.wikipedia.org/wiki/Fish_%28food%29\" rel=\"nofollow ugc noopener\">"
                        + "https://en.wikipedia.org/wiki/Fish_(food)</a></p>",
                CommentBodyRenderer.toHtml("see https://en.wikipedia.org/wiki/Fish_(food)"));
        assertEquals("<p>(see <a href=\"https://en.wikipedia.org/wiki/Fish_%28food%29\" rel=\"nofollow ugc noopener\">"
                        + "https://en.wikipedia.org/wiki/Fish_(food)</a>).</p>",
                CommentBodyRenderer.toHtml("(see https://en.wikipedia.org/wiki/Fish_(food))."));
        assertEquals("<p>(<a href=\"https://ex.com/a\" rel=\"nofollow ugc noopener\">https://ex.com/a</a>))</p>",
                CommentBodyRenderer.toHtml("(https://ex.com/a))"));
    }

    @Test
    void aBareSchemeIsNotALink() {
        assertEquals("<p>https://</p>", CommentBodyRenderer.toHtml("https://"));
        assertEquals("<p>https://.</p>", CommentBodyRenderer.toHtml("https://."));
    }

    @Test
    void otherSchemesStayText() {
        assertEquals("<p>ftp://ex.com mailto:a&#64;ex.com javascript:alert(1)</p>",
                CommentBodyRenderer.toHtml("ftp://ex.com mailto:a@ex.com javascript:alert(1)"));
    }

    @Test
    void blankLinesMakeParagraphsAndNewlinesBreaks() {
        assertEquals("<p>a<br />b</p><p>c</p>", CommentBodyRenderer.toHtml("a\nb\n\nc"));
    }

    @Test
    void windowsLineEndingsAndWhitespaceOnlyLinesAreParagraphBreaksToo() {
        assertEquals("<p>a<br />b</p><p>c</p>", CommentBodyRenderer.toHtml("a\r\nb\r\n  \r\n\r\nc\r\n"));
    }

    @Test
    void aParagraphOfUnicodeSpacesIsNoParagraph() {
        assertEquals("<p>a</p><p>b</p>", CommentBodyRenderer.toHtml("a\n\n\u2003\n\nb"));
    }

    @Test
    void nothingRendersAsNothing() {
        assertEquals("", CommentBodyRenderer.toHtml(null));
        assertEquals("", CommentBodyRenderer.toHtml(""));
        assertEquals("", CommentBodyRenderer.toHtml(" \n\n \n"));
    }
}
