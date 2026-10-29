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
package com.wikantik.pressroom.business.shortcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code [q]..[/q][a]..[/a]} question/answer pairs into ordered
 * {@link Qa} records.
 *
 * <p><b>This parser is the single source of truth for FAQ content.</b> Two
 * emitters call it and must never re-implement it: the {@code [faq]}
 * shortcode ({@link FaqShortcode}, which renders the pairs as a
 * {@code <dl class="faq">}) and the JSON-LD head emission (Wave 3 T4, which
 * renders the same pairs as {@code FAQPage.mainEntity}). Because
 * {@code #showSeoHead} runs before entry content is rendered, the head side
 * re-parses the <em>raw</em> {@code entry.getText()} with {@link #parse};
 * sharing this parser is what keeps the FAQ on the page and the structured
 * data in the head from drifting.
 *
 * <p>{@code [q]} and {@code [a]} are deliberately NOT registered shortcodes:
 * the expander passes unknown names through unchanged, so the tags survive
 * inside a {@code [faq]} body for this parser to read.
 *
 * <p>The pair grammar is strict: a body is one or more
 * {@code [q]question[/q][a]answer[/a]} pairs, in that order, with only
 * whitespace between and around them, and no blank questions or answers.
 * Anything else is malformed and {@link #parsePairs} returns an empty list
 * (never null) -- the shortcode then leaves the author's text visible (the
 * expander's failure signal) instead of rendering half an FAQ, and the
 * JSON-LD side emits nothing for that block. Question and answer text is
 * returned raw (trimmed): it is entry HTML, and each emitter escapes or
 * sanitizes for its own context.
 */
public final class FaqBlocks {

    /** A {@code [faq]...[/faq]} block in raw entry text; body-less forms have no pairs. */
    private static final Pattern FAQ_BLOCK = Pattern.compile(
            "\\[faq\\](.*?)\\[/faq\\]", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * One question/answer pair, in entry-source order. Both sides are raw
     * entry HTML, trimmed but unescaped -- every emitter escapes for its own
     * context (the sanitizer gates the rendered {@code <dl>}, JSON escaping
     * gates the {@code FAQPage} emission).
     */
    public record Qa(String question, String answer) {
    }

    private FaqBlocks() {
        // static use only
    }

    /**
     * Every pair from every <em>well-formed</em> {@code [faq]...[/faq]}
     * block in raw entry text, in source order; an empty list (never null)
     * when there are none. A malformed block contributes nothing -- exactly
     * mirroring the page, where that block renders as literal text instead
     * of a {@code <dl>}. This is the Wave 3 T4 entry point for
     * {@code FAQPage.mainEntity}.
     */
    public static List<Qa> parse(String rawEntryText) {
        if (rawEntryText == null
                || !rawEntryText.toLowerCase(Locale.ROOT).contains("[faq]")) {
            return List.of();
        }
        List<Qa> pairs = new ArrayList<>();
        Matcher matcher = FAQ_BLOCK.matcher(rawEntryText);
        while (matcher.find()) {
            if (matcher.start() > 0 && rawEntryText.charAt(matcher.start() - 1) == '[') {
                // [[faq]]: an escaped mention of the syntax, not a block
                continue;
            }
            // parsePairs() never returns null (an empty list means
            // "malformed, or nothing to parse"), so nothing to guard here.
            pairs.addAll(parsePairs(matcher.group(1)));
        }
        return pairs;
    }

    /**
     * The ordered pairs of one {@code [faq]} body, or an empty list (never
     * null) when the body does not match the strict pair grammar (including
     * a body with no pairs at all). This is the {@link FaqShortcode} entry
     * point; a well-formed body always yields at least one pair, so
     * "malformed" and "well-formed but empty" are indistinguishable here on
     * purpose -- both mean "nothing to render" to every caller.
     */
    public static List<Qa> parsePairs(String faqBody) {
        if (faqBody == null) {
            return List.of();
        }
        List<Qa> pairs = new ArrayList<>();
        int pos = 0;
        int end = faqBody.length();
        while (true) {
            pos = skipWhitespace(faqBody, pos);
            if (pos >= end) {
                break;
            }
            String question = section(faqBody, pos, "[q]", "[/q]");
            if (question == null) {
                return List.of();
            }
            pos = skipWhitespace(faqBody, afterSection(faqBody, pos, "[/q]"));
            String answer = section(faqBody, pos, "[a]", "[/a]");
            if (answer == null) {
                return List.of();
            }
            pos = afterSection(faqBody, pos, "[/a]");
            if (question.isBlank() || answer.isBlank()) {
                return List.of();
            }
            pairs.add(new Qa(question.trim(), answer.trim()));
        }
        return pairs;
    }

    /**
     * The text between {@code open} at exactly {@code pos} and the next
     * {@code close}, or null when the grammar is broken there.
     */
    private static String section(String text, int pos, String open, String close) {
        if (!text.regionMatches(true, pos, open, 0, open.length())) {
            return null;
        }
        int bodyStart = pos + open.length();
        int closeAt = indexOfIgnoreCase(text, close, bodyStart);
        if (closeAt < 0) {
            return null;
        }
        return text.substring(bodyStart, closeAt);
    }

    /** The index just past the next {@code close} tag at or after {@code pos}. */
    private static int afterSection(String text, int pos, String close) {
        return indexOfIgnoreCase(text, close, pos) + close.length();
    }

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        for (int i = Math.max(from, 0); i <= text.length() - needle.length(); i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String text, int pos) {
        int i = pos;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }
}
