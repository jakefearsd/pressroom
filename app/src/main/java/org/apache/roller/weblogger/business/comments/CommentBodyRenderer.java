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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.roller.weblogger.util.HTMLSanitizer;

/**
 * Turns a comment body, which is plain text as the reader typed it, into the
 * HTML the entry page shows. It runs at render time only; the stored body is
 * never changed.
 *
 * <p>The steps, in order:
 * <ol>
 *   <li>Every character the reader typed is HTML-escaped, so markup, entity
 *       tricks and {@code javascript:} text all show as the characters they
 *       are.</li>
 *   <li>{@code http(s)} URLs become {@code <a rel="nofollow ugc noopener">}
 *       links whose href and text are the escaped URL. A URL runs up to
 *       any Unicode whitespace or space separator (an ideographic or
 *       no-break space included), a format character (zero-width and bidi
 *       controls), an angle bracket or a quote. Trailing {@code .,;:!?} is
 *       left out, and so is a trailing {@code )} unless it balances a
 *       {@code (} in the URL: "(see https://x.com/a)." links
 *       {@code https://x.com/a}, and a Wikipedia URL ending in
 *       {@code _(food)} keeps its parenthesis. The URL is found in the raw text and the
 *       pieces are escaped as they are emitted, so nothing the reader typed
 *       reaches the output unescaped and an escaped {@code &lt;} can never
 *       be read as part of a URL.</li>
 *   <li>A blank line starts a new paragraph; a single newline is a
 *       {@code <br>}.</li>
 *   <li>The result goes through {@link HTMLSanitizer#sanitize}, the content
 *       boundary every rendered body passes, so even a defect in the steps
 *       above cannot emit executable markup.</li>
 * </ol>
 */
public final class CommentBodyRenderer {

    /** Up to whitespace, a Unicode space separator, a format character, an angle bracket or a quote. */
    private static final Pattern URL = Pattern.compile("https?://[^\\s\\p{Z}\\p{Cf}<>\"']+",
            Pattern.CASE_INSENSITIVE);

    /** A line holding nothing but whitespace (or nothing) separates paragraphs. */
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[ \\t\\x0B\\f]*\\n\\s*");

    private static final String TRAILING_PUNCTUATION = ".,;:!?";

    private static final String REL = "nofollow ugc noopener";

    private CommentBodyRenderer() {
    }

    /**
     * The body as safe HTML: escaped, linked, broken into paragraphs and
     * sanitized. A null or blank body renders as the empty string.
     */
    public static String toHtml(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return "";
        }
        String text = rawBody.replace("\r\n", "\n").replace('\r', '\n').strip();
        StringBuilder html = new StringBuilder(text.length() * 2);
        for (String paragraph : PARAGRAPH_BREAK.split(text)) {
            if (paragraph.isBlank()) {
                continue;
            }
            html.append("<p>");
            String[] lines = paragraph.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    html.append("<br>");
                }
                appendLinked(html, lines[i]);
            }
            html.append("</p>");
        }
        return HTMLSanitizer.sanitize(html.toString());
    }

    /** One line of text, escaped, with its http(s) URLs as links. */
    private static void appendLinked(StringBuilder html, String line) {
        Matcher m = URL.matcher(line);
        int done = 0;
        while (m.find()) {
            String url = withoutTrailingPunctuation(m.group());
            if (url.indexOf("://") + 3 >= url.length()) {
                continue; // a bare scheme ("https://") is text, not a link
            }
            html.append(escape(line.substring(done, m.start())));
            String escaped = escape(url);
            html.append("<a href=\"").append(escaped).append("\" rel=\"").append(REL).append("\">")
                    .append(escaped).append("</a>");
            done = m.start() + url.length();
        }
        html.append(escape(line.substring(done)));
    }

    /**
     * The URL without the sentence's punctuation after it: trailing
     * {@code .,;:!?}, and a trailing {@code )} that has no {@code (} to close
     * inside the URL.
     */
    private static String withoutTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0) {
            char last = url.charAt(end - 1);
            boolean sentence = TRAILING_PUNCTUATION.indexOf(last) >= 0
                    || (last == ')' && count(url, end, ')') > count(url, end, '('));
            if (!sentence) {
                break;
            }
            end--;
        }
        return url.substring(0, end);
    }

    private static int count(String text, int end, char c) {
        int n = 0;
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /**
     * The four characters that could open a tag or an entity in text, or end
     * the double-quoted href; nothing else. (An apostrophe needs no escape
     * here: every attribute is double-quoted, and the sanitizer writes it as
     * {@code &#39;} anyway.)
     */
    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
