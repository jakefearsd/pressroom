/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.business.search.lucene;

import java.io.IOException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.search.uhighlight.WholeBreakIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The passage of a post a results page shows under its title, with the
 * reader's words marked.
 *
 * <p>The result is emitted unescaped by a template, so it is an XSS boundary:
 * the stored text is plain text that may legitimately hold {@code <}, {@code >},
 * {@code &} and quotes, every character of it is escaped here, and the only
 * markup the result ever contains is {@code <mark>} and {@code </mark>}.
 */
public final class Snippets {

    private static final Logger log = LoggerFactory.getLogger(Snippets.class);

    /**
     * How much of a stored body is analysed for its passage (final review,
     * C4); a match beyond it has no passage and the hit shows its excerpt.
     */
    static final int MAX_ANALYSED_CHARS = 50_000;

    private Snippets() {
    }

    /**
     * @param plainContent the post's text as a reader reads it (not markup)
     * @param query        the parsed search words
     * @param analyzer     the analyzer the index was built with, so a stemmed form is marked
     * @param maxChars     the length aimed for; a passage is cut at word boundaries, a little over at most
     * @return escaped text with {@code <mark>} around matched words, or "" when nothing matches
     */
    public static String html(String plainContent, Query query, Analyzer analyzer, int maxChars) {
        if (plainContent == null || plainContent.isBlank() || query == null) {
            return "";
        }
        String analysed = analysedPart(plainContent);
        try {
            UnifiedHighlighter highlighter = UnifiedHighlighter.builderWithoutSearcher(analyzer)
                    .withBreakIterator(WholeBreakIterator::new)
                    .withFormatter(new WindowFormatter(maxChars))
                    .withMaxNoHighlightPassages(0)
                    // the words are parsed across several fields; the passage is cut from the body
                    .withFieldMatcher(field -> true)
                    .withMaxLength(Math.max(analysed.length(), 1))
                    .build();
            // null when nothing in the text matches (no no-match passages are asked for)
            Object out = highlighter.highlightWithoutSearcher(FieldConstants.CONTENT, query, analysed, 1);
            return out == null ? "" : out.toString();
        } catch (IOException | RuntimeException e) {
            // a display path: no passage is shown, the page still renders
            log.warn("Could not build a search snippet", e);
            return "";
        }
    }

    /**
     * The start of the text the highlighter reads, at most
     * {@link #MAX_ANALYSED_CHARS} characters, never ending inside a surrogate
     * pair: a request analyses each hit's body, so this bounds its work.
     */
    private static String analysedPart(String text) {
        if (text.length() <= MAX_ANALYSED_CHARS) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(MAX_ANALYSED_CHARS - 1))
                ? MAX_ANALYSED_CHARS - 1 : MAX_ANALYSED_CHARS;
        return text.substring(0, end);
    }

    /** HTML-escapes plain text: {@code & < > " '}, and nothing else. */
    public static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        escapeInto(out, text, 0, text.length());
        return out.toString();
    }

    private static void escapeInto(StringBuilder out, String text, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
    }

    /**
     * Receives the whole text as one passage with every match in it, and
     * cuts a window of about {@code maxChars} around the first match at word
     * boundaries. Escapes every character and wraps the matches in
     * {@code <mark>}.
     */
    private static final class WindowFormatter extends PassageFormatter {
        private final int maxChars;

        WindowFormatter(int maxChars) {
            this.maxChars = maxChars;
        }

        @Override
        public Object format(Passage[] passages, String content) {
            if (passages.length == 0 || passages[0].getNumMatches() == 0) {
                return "";
            }
            Passage passage = passages[0];
            int firstStart = passage.getMatchStarts()[0];
            int firstEnd = passage.getMatchEnds()[0];

            int from = Math.max(0, firstStart - maxChars / 4);
            // from a word's start, not its middle
            while (from > 0 && from < firstStart && !Character.isWhitespace(content.charAt(from - 1))) {
                from++;
            }
            int to = Math.min(content.length(), Math.max(from + maxChars, firstEnd));
            // to a word's end, not its middle
            while (to < content.length() && to > firstEnd && !Character.isWhitespace(content.charAt(to))) {
                to--;
            }
            return render(passage, content, from, to);
        }

        private static String render(Passage passage, String content, int from, int to) {
            StringBuilder out = new StringBuilder();
            int pos = from;
            for (int i = 0; i < passage.getNumMatches(); i++) {
                int start = passage.getMatchStarts()[i];
                int stop = passage.getMatchEnds()[i];
                if (start < pos || stop > to) {
                    continue;
                }
                escapeInto(out, content, pos, start);
                out.append("<mark>");
                escapeInto(out, content, start, stop);
                out.append("</mark>");
                pos = stop;
            }
            escapeInto(out, content, pos, to);
            return out.toString().strip();
        }
    }
}
