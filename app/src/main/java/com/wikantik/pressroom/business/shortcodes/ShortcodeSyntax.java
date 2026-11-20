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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * The one tag grammar the expander, {@link MapPins} and {@link FaqBlocks}
 * share: what an opening tag looks like, how its attributes read, and how to
 * list the child tags (such as {@code [pin]}) of a body.
 */
final class ShortcodeSyntax {

    /**
     * An opening (or self-closing) shortcode tag: {@code [name attrs]},
     * {@code [name attrs /]}, or the escaped {@code [[name attrs]}] prefix
     * (group 1 captures the doubled bracket). The attribute section is
     * quote-aware: a {@code ]} inside a complete double- or single-quoted
     * value stays part of the value ({@code caption="Paris [2023]"}) rather
     * than ending the tag, while an unquoted (or unterminated-quote)
     * {@code ]} still ends it -- which is what keeps an unterminated
     * {@code [name} from swallowing the rest of the text. Group 4 catches
     * the no-attribute self-close ({@code [name/]}); a trailing {@code /}
     * after attributes lands inside group 3 and is handled by the callers.
     *
     * <p>Group 3 is written as runs of plain characters between quoted values,
     * all possessive, rather than as one alternation repeated per character.
     * {@code java.util.regex} recurses once per iteration of a repeated group,
     * so the per-character form overflowed a request thread's stack on a
     * couple of thousand characters of unclosed {@code [word ...} prose. The
     * two forms match the same text: a quoted value has exactly one possible
     * end, and nothing after group 3 can match inside it, so the possessive
     * quantifiers give up no match the greedy ones could have found.
     */
    static final Pattern OPEN_TAG = openTagPattern();

    /**
     * {@link #OPEN_TAG}, compiled in a method of its own only so that the
     * REDOS review below is attached to this one pattern rather than to the
     * whole class: a suppression cannot sit on a static initializer.
     */
    @SuppressFBWarnings(
            value = "REDOS",
            justification = "Every repetition is possessive and the alternatives start on different "
                    + "characters (a plain run excludes both quotes), so nothing backtracks for input to "
                    + "multiply; ShortcodeExpanderTest feeds it 100,000 characters of unclosed prose "
                    + "and 20,000 quoted values.")
    private static Pattern openTagPattern() {
        return Pattern.compile("\\[(\\[?)([a-zA-Z][\\w-]*)"
                + "((?:\\s[^\\]\"']*+(?:(?:\"[^\"]*+\"|'[^']*+')[^\\]\"']*+)*+)?)(/?)\\]");
    }

    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\\]]+))");

    private ShortcodeSyntax() {
        // static use only
    }

    /** Whether a tag is written self-closing: {@code /]}, with or without attributes. */
    static boolean isSelfClosing(String attributeText, String slashGroup) {
        return !slashGroup.isEmpty() || attributeText.stripTrailing().endsWith("/");
    }

    /** The attributes of a tag's attribute section, keys lower-cased, in source order. */
    static Map<String, String> parseAttributes(String attributeText) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (attributeText == null || attributeText.isBlank()) {
            return attributes;
        }
        Matcher matcher = ATTRIBUTE.matcher(attributeText);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3)
                    : matcher.group(4);
            attributes.put(matcher.group(1).toLowerCase(Locale.ROOT), value);
        }
        return attributes;
    }

    /**
     * The attributes of every {@code [name ...]} tag in {@code body}, in
     * source order. For child tags that are deliberately not registered
     * shortcodes (such as {@code [pin]}). The escaped {@code [[name ...]]}
     * form, other names, and tags that never close are not children.
     */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Compares an author's tag name case-insensitively with the child tag asked for; "
                    + "it decides only which of the author's own tags a reader collects.")
    static List<Map<String, String>> childTags(String body, String name) {
        List<Map<String, String>> tags = new ArrayList<>();
        Matcher matcher = OPEN_TAG.matcher(body);
        while (matcher.find()) {
            if (!matcher.group(1).isEmpty() || !matcher.group(2).equalsIgnoreCase(name)) {
                continue;
            }
            String section = matcher.group(3).stripTrailing();
            if (section.endsWith("/")) {
                // a self-close slash is never part of a bare value
                section = section.substring(0, section.length() - 1);
            }
            tags.add(parseAttributes(section));
        }
        return tags;
    }
}
