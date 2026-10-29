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
package com.wikantik.pressroom.business.shortcodes;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The parser's corner cases in {@link ShortcodeExpander}: escapes written
 * next to real tags, closers that are only text, and tags whose quoted values
 * look like closers. Each case is an author's literal text coming out as the
 * author meant it, because this expander is what runs on every entry before
 * the sanitizer. {@code ShortcodeExpanderTest} has the main matrix; these are
 * the boundary forms it leaves out.
 *
 * <p>Characterisation tests: written against the existing behaviour and
 * expected to pass immediately.
 */
class ShortcodeExpanderEdgeTest {

    private final WeblogEntry entry = new WeblogEntry();

    private static ShortcodeHandler handler(String name, boolean upperCase) {
        return new ShortcodeHandler() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public ShortcodeCard getCard() {
                return ShortcodeCard.snippet(name, "test.label", "[" + name + "]");
            }

            @Override
            public String render(Map<String, String> attributes, String body, ShortcodeContext content) {
                if (upperCase) {
                    String source = body != null ? body : attributes.getOrDefault("word", "");
                    return source.toUpperCase(Locale.ROOT);
                }
                StringBuilder out = new StringBuilder("{");
                attributes.forEach((k, v) -> out.append(k).append('=').append(v).append(';'));
                return out.append('}').toString();
            }
        };
    }

    private final ShortcodeExpander expander =
            new ShortcodeExpander(List.of(handler("upper", true), handler("echo", false)));

    private String expand(String text) {
        return expander.expand(entry, text);
    }

    @Test
    void aSlashRightAfterTheNameClosesAShortcodeWithNoAttributes() {
        assertEquals("a  b", expand("a [upper/] b"));
    }

    @Test
    void anEscapedCloserOfARegisteredNameBecomesLiteralTextAndOthersAreLeftAlone() {
        assertEquals("see [/upper] and A", expand("see [[/upper]] and [upper word=a]"));
        assertEquals("see [[/nope]] and A", expand("see [[/nope]] and [upper word=a]"));
        assertEquals("x [[/upper] y A", expand("x [[/upper] y [upper word=a]"),
                "without the closing pair of brackets it is not an escape");
    }

    @Test
    void aDoubledOpeningBracketWithNoClosingPairIsTextFollowedByARealTag() {
        assertEquals("tail [X", expand("tail [[upper word=x]"));
    }

    @Test
    void anEscapedCloserInsideABodyDoesNotEndIt() {
        assertEquals("A [/UPPER] B", expand("[upper]a [[/upper]] b[/upper]"));
    }

    @Test
    void anEscapedOpenInsideABodyIsLiteralAndDoesNotNest() {
        assertEquals("X [UPPER WORD=Y] Z", expand("[upper]x [[upper word=y]] z[/upper]"));
    }

    @Test
    void nestedAndSequentialPairsMatchLikeHtml() {
        assertEquals("A B C", expand("[upper]a [upper]b[/upper] c[/upper]"));
        assertEquals("X and Y", expand("[upper]x[/upper] and [upper]y[/upper]"));
    }

    @Test
    void aCloserLookAlikeInsideAQuotedAttributeValueDoesNotEndTheBody() {
        assertEquals("A {CAPTION=[/UPPER];} B", expand("[upper]a [echo caption=\"[/upper]\"] b[/upper]"));
    }

    @Test
    void anOpenWithNoCloserIsRenderedWithoutABody() {
        assertEquals("before X after", expand("before [upper word=x] after"));
        assertEquals("start ", expand("start [upper]"),
                "no closer anywhere: the tag renders as an empty self-closing one");
    }

    @Test
    void moreOpensThanClosersStillPairsTheCloserThatWasWritten() {
        String result = expand("[upper]a [upper]b[/upper]");
        assertEquals("A B", result, "the inner open, with no closer left, renders as an empty tag; its text stays");
    }

    @Test
    void textWithNoBracketOrNoHandlersIsReturnedAsIs() {
        assertEquals("plain text", expand("plain text"));
        assertEquals("[upper]x[/upper]", new ShortcodeExpander(List.of()).expand(entry, "[upper]x[/upper]"));
    }
}
