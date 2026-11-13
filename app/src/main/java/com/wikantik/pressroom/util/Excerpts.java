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
package com.wikantik.pressroom.util;

import org.apache.commons.lang3.StringUtils;
import org.owasp.html.HtmlSanitizer;

import java.text.BreakIterator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Plain-text excerpts of rendered entry HTML, for the line of prose an entry
 * list shows under each title.
 *
 * <p>The input is always rendered, sanitized markup (an entry's transformed
 * text or summary), read through the OWASP lexer rather than pattern-matched:
 * truncating markup can cut inside a tag, and stripping it with a regular
 * expression joins a heading onto the sentence after it. What comes back is
 * text, not escaped text -- entities are decoded -- so a template emits it
 * through {@code $utils.escapeHTML}, exactly once.
 */
public final class Excerpts {

    /** The longest opening returned; a sentence that would run past it is left out, or cut at a word. */
    static final int MAX_CHARS = 300;

    /** Sentences an opening is made of, unless they are too short to say anything. */
    static final int SENTENCES = 2;

    /** Below this, another sentence is taken: "Hi. I'm back." is not yet a summary. */
    static final int MIN_CHARS = 100;

    private static final String ELLIPSIS = "…";

    /** Shorter than this, a description found in the post proves nothing about where it was cut. */
    static final int CUT_MIN_CHARS = 20;

    /** What may follow a whole sentence of the post: its own end, or a closing quote or bracket. */
    private static final String SENTENCE_END = ".!?…\"'\u201D\u2019)";

    /**
     * Containers whose paragraphs are someone else's words, or an aside to the
     * writer's own: a quotation, a list, a table, a figure, a callout. A post
     * that opens with one still opens, for a reader, with the paragraph after it.
     */
    private static final Set<String> ASIDES = Set.of(
            "aside", "blockquote", "details", "dl", "figure", "li", "nav", "table");

    /** Text that is not prose at all, and is left out even when a post has no paragraphs. */
    private static final Set<String> SKIPPED = Set.of(
            "button", "figcaption", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "script", "style");

    /** Elements that sit inside a run of text, so open and close insert no space. */
    private static final Set<String> INLINE = Set.of(
            "a", "abbr", "b", "bdi", "bdo", "cite", "code", "data", "del", "dfn", "em", "i", "ins",
            "kbd", "mark", "q", "s", "samp", "small", "span", "strong", "sub", "sup", "time", "u", "var");

    /** Elements that never close. */
    private static final Set<String> VOID = Set.of(
            "area", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr");

    private Excerpts() {
    }

    /**
     * The opening of a post: its first couple of sentences of the writer's
     * own paragraphs -- never a heading, a code block, a caption or a
     * quotation -- as plain text, at most {@link #MAX_CHARS} long. A post with
     * no such paragraph falls back to whatever other text it has.
     *
     * @return the opening, or null when the markup holds no text at all
     */
    public static String opening(String html, Locale locale) {
        if (StringUtils.isBlank(html)) {
            return null;
        }
        Collector collected = collect(html);
        List<String> units = new ArrayList<>();
        for (String paragraph : collected.paragraphs) {
            String text = squash(paragraph);
            if (!text.isEmpty()) {
                units.add(text);
            }
        }
        if (units.isEmpty()) {
            String rest = squash(String.join("", collected.rest));
            if (!rest.isEmpty()) {
                units.add(rest);
            }
        }
        Opening opening = new Opening();
        BreakIterator sentences = BreakIterator.getSentenceInstance(locale == null ? Locale.ROOT : locale);
        for (String unit : units) {
            sentences.setText(unit);
            int start = sentences.first();
            for (int end = sentences.next(); end != BreakIterator.DONE; start = end, end = sentences.next()) {
                if (!opening.offer(unit.substring(start, end).trim())) {
                    return opening.text();
                }
            }
        }
        return opening.text();
    }

    /**
     * Text sized for a field that holds at most {@code max} characters, a
     * meta description say: whole if it fits, else the whole sentences that
     * fit, else its first sentence cut at a word, with an ellipsis.
     *
     * @return the fitted text, or null when there is no text
     */
    public static String fit(String text, int max) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        String whole = squash(text);
        if (whole.length() <= max) {
            return whole;
        }
        BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
        sentences.setText(whole);
        int fits = 0;
        for (int end = sentences.next(); end != BreakIterator.DONE; end = sentences.next()) {
            if (whole.substring(0, end).stripTrailing().length() > max) {
                break;
            }
            fits = end;
        }
        return fits > 0 ? whole.substring(0, fits).strip() : cut(whole, max);
    }

    /**
     * Every word of the markup in reading order, as plain text: an author's
     * own summary, shown whole.
     *
     * @return the text, or null when there is none
     */
    public static String plainText(String html) {
        if (StringUtils.isBlank(html)) {
            return null;
        }
        String text = squash(String.join("", collect(html).all));
        return text.isEmpty() ? null : text;
    }

    /**
     * Whether {@code description} is the post's own text cut off mid-sentence
     * with a full stop stuck on ("...to bring hygienic,."): a run of the post's
     * words that ends with ". " where the post itself carries on. Shown as an
     * excerpt or a search snippet it reads as a broken sentence. A whole
     * sentence of the post, an honest ellipsis, or words the post does not
     * contain are not a cut. Whitespace is compared squashed.
     *
     * @param text the post's text, as plain text or as Markdown source; a run
     *             with inline markup in it simply does not match
     */
    public static boolean isCutFrom(String description, String text) {
        if (StringUtils.isAnyBlank(description, text)) {
            return false;
        }
        String squashed = squash(description);
        if (!squashed.endsWith(".") || squashed.endsWith("..")) {
            return false;
        }
        String run = squashed.substring(0, squashed.length() - 1);
        if (run.length() < CUT_MIN_CHARS) {
            return false;
        }
        String body = squash(text);
        int at = body.indexOf(run);
        int after = at + run.length();
        return at >= 0 && after < body.length() && SENTENCE_END.indexOf(body.charAt(after)) < 0;
    }

    /**
     * Whether {@code summary} is the post's own text cut off part-way, with
     * nothing to say so ("...for a navigable harb"): a run of the post's words
     * that stops short of a sentence end where the post itself carries on.
     * Shown as an excerpt it reads as a broken sentence, so the caller passes
     * it over. A whole sentence of the post, an honest ellipsis, or words the
     * post does not contain are not a clip. Whitespace is compared squashed.
     *
     * @param text the post's text as plain text; a run with inline markup in
     *             it simply does not match
     */
    public static boolean isClippedFrom(String summary, String text) {
        if (StringUtils.isAnyBlank(summary, text)) {
            return false;
        }
        String run = squash(summary);
        if (run.length() < CUT_MIN_CHARS || SENTENCE_END.indexOf(run.charAt(run.length() - 1)) >= 0) {
            return false;
        }
        String body = squash(text);
        int at = body.indexOf(run);
        return at >= 0 && at + run.length() < body.length();
    }

    private static Collector collect(String html) {
        Collector collector = new Collector();
        HtmlSanitizer.sanitize(html, collector);
        return collector;
    }

    /**
     * Every run of whitespace as one space, trimmed. A loop, not a regular
     * expression: a front page squashes every listed post's whole text, and
     * {@code [\s\u00A0]+} was the hottest code in its render (profiling,
     * 2026-11-06). {@link #isSpace} is exactly that class.
     */
    static String squash(CharSequence text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inRun = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isSpace(c)) {
                inRun = true;
            } else {
                if (inRun) {
                    out.append(' ');
                    inRun = false;
                }
                out.append(c);
            }
        }
        if (inRun) {
            out.append(' ');
        }
        return out.toString().trim();
    }

    /** Java's {@code \s} (the six ASCII spaces, not {@link Character#isWhitespace}) or a no-break space. */
    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r' || c == '\u00A0';
    }

    /** Sentences accumulated until the opening says enough, or the next one would overrun it. */
    private static final class Opening {
        private String out = "";
        private int sentences;

        /** @return false once the opening is complete and no more sentences are wanted */
        boolean offer(String sentence) {
            if (sentences >= SENTENCES && out.length() >= MIN_CHARS) {
                return false;
            }
            if (sentence.isEmpty()) {
                return true;
            }
            int separator = out.isEmpty() ? 0 : 1;
            if (out.length() + separator + sentence.length() > MAX_CHARS) {
                // Too long to take whole. An opening that already says enough
                // ends at its last full stop; one that does not -- nothing yet,
                // or a short first sentence -- runs into this one, cut at a word.
                if (out.length() < MIN_CHARS) {
                    out = join(out, cut(sentence, MAX_CHARS - out.length() - separator));
                }
                return false;
            }
            out = join(out, sentence);
            sentences++;
            return true;
        }

        String text() {
            return out.isEmpty() ? null : out;
        }

        private static String join(String head, String tail) {
            return head.isEmpty() ? tail : head + " " + tail;
        }
    }

    /** The sentence cut at the last word that fits in {@code room}, ellipsis included. */
    private static String cut(String sentence, int room) {
        int limit = room - ELLIPSIS.length();
        int at = sentence.lastIndexOf(' ', limit);
        if (at <= 0) {
            // No word to cut at: cut at the limit, but never between the
            // two halves of a character such as an emoji.
            at = Character.isHighSurrogate(sentence.charAt(limit - 1)) ? limit - 1 : limit;
        }
        String head = sentence.substring(0, at);
        return head.stripTrailing() + ELLIPSIS;
    }

    /** One open element, and what it switched on, so its close can switch it off again. */
    private record Frame(String name, boolean aside, boolean skipped, boolean paragraph) {
    }

    /**
     * Collects, from the lexer's events, every word ({@code all}), the words
     * outside non-prose elements ({@code rest}), and the text of each of the
     * writer's own paragraphs.
     */
    private static final class Collector implements HtmlSanitizer.Policy {
        final List<String> all = new ArrayList<>();
        final List<String> rest = new ArrayList<>();
        final List<String> paragraphs = new ArrayList<>();
        private final Deque<Frame> open = new ArrayDeque<>();
        private int asideDepth;
        private int skippedDepth;
        private List<String> paragraph;

        @Override
        public void openDocument() {
            // nothing to set up
        }

        @Override
        public void closeDocument() {
            while (!open.isEmpty()) {
                close(open.pop());
            }
        }

        @Override
        public void openTag(String elementName, List<String> attrs) {
            if (VOID.contains(elementName)) {
                if ("br".equals(elementName)) {
                    space();
                }
                return;
            }
            // A div with a class is shortcode or callout output -- a gallery,
            // a map, a code block's toolbar, a GitHub-style alert -- not prose.
            boolean classed = "div".equals(elementName) && hasClass(attrs);
            boolean aside = ASIDES.contains(elementName) || classed;
            boolean skipped = SKIPPED.contains(elementName) || classed;
            boolean starts = "p".equals(elementName) && asideDepth == 0 && paragraph == null;
            open.push(new Frame(elementName, aside, skipped, starts));
            if (aside) {
                asideDepth++;
            }
            if (skipped) {
                skippedDepth++;
            }
            if (starts) {
                paragraph = new ArrayList<>();
            }
            if (!INLINE.contains(elementName)) {
                space();
            }
        }

        @Override
        public void closeTag(String elementName) {
            if (VOID.contains(elementName) || open.stream().noneMatch(f -> f.name().equals(elementName))) {
                return;
            }
            Frame frame;
            do {
                frame = open.pop();
                close(frame);
            } while (!frame.name().equals(elementName));
            if (!INLINE.contains(elementName)) {
                space();
            }
        }

        @Override
        public void text(String textChunk) {
            all.add(textChunk);
            if (skippedDepth == 0) {
                rest.add(textChunk);
                if (paragraph != null) {
                    paragraph.add(textChunk);
                }
            }
        }

        /** The lexer hands attributes over as a flat name, value, name, value list. */
        private static boolean hasClass(List<String> attrs) {
            for (int i = 0; i < attrs.size(); i += 2) {
                if ("class".equals(attrs.get(i))) {
                    return true;
                }
            }
            return false;
        }

        private void close(Frame frame) {
            if (frame.aside()) {
                asideDepth--;
            }
            if (frame.skipped()) {
                skippedDepth--;
            }
            if (frame.paragraph()) {
                paragraphs.add(String.join("", paragraph));
                paragraph = null;
            }
        }

        private void space() {
            all.add(" ");
            rest.add(" ");
            if (paragraph != null) {
                paragraph.add(" ");
            }
        }
    }
}
