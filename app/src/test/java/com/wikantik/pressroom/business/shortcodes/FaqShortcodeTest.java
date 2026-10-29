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

import java.util.Map;

import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in {@code [faq]} shortcode: strict {@code [q]..[/q][a]..[/a]}
 * pairs rendered as a {@code <dl class="faq">}, null (leave the shortcode
 * as written) for anything malformed. The heavy grammar matrix lives in
 * {@link FaqBlocksTest}; this covers the handler's own contract.
 */
class FaqShortcodeTest {

    private final FaqShortcode shortcode = new FaqShortcode();

    private String render(String body) {
        return shortcode.render(Map.of(), body, new WeblogEntry());
    }

    @Test
    void rendersPairsAsADefinitionList() {
        String html = render(
                "[q]When is the best month?[/q][a]June, by far.[/a]\n"
                        + "[q]Do I need a car?[/q][a]Yes.[/a]");

        assertEquals("<dl class=\"faq\">\n"
                + "<dt>When is the best month?</dt>\n"
                + "<dd>June, by far.</dd>\n"
                + "<dt>Do I need a car?</dt>\n"
                + "<dd>Yes.</dd>\n"
                + "</dl>", html);
    }

    @Test
    void answerHtmlIsEmittedVerbatimForTheDownstreamSanitizer() {
        // answers are entry HTML like any other body text; the entry-content
        // sanitizer gates them after expansion, exactly as it does for the
        // [image] shortcode's body caption
        String html = render(
                "[q]Where to book?[/q][a]Use <a href=\"https://example.com\">this "
                        + "site</a>.[/a]");
        assertTrue(html.contains("<dd>Use <a href=\"https://example.com\">this "
                + "site</a>.</dd>"), html);
    }

    @Test
    void aScriptPayloadPassesThroughToTheSanitizerWhichStripsIt() {
        // the handler's job is structure, not sanitizing; the round-trip is
        // pinned by HTMLSanitizerTest.FaqMarkup and the rendering test
        String html = render("[q]q?[/q][a]\"><script>alert(1)</script>[/a]");
        assertTrue(html.contains("<dd>\"><script>alert(1)</script></dd>"), html);
        assertFalse(com.wikantik.pressroom.util.HTMLSanitizer.sanitize(html)
                .contains("<script"), "the downstream sanitizer must strip it");
    }

    @Test
    void aMalformedBodyIsLeftAsWritten() {
        assertNull(render("[q]question without an answer[/q]"));
        assertNull(render("[a]answer first[/a][q]q[/q]"));
        assertNull(render("[q]q[/q] stray prose [a]a[/a]"));
        assertNull(render("just prose"));
    }

    @Test
    void aMissingOrBlankBodyIsLeftAsWritten() {
        assertNull(render(null), "self-closing [faq] has no body");
        assertNull(render(""));
        assertNull(render("   \n  "));
    }

    // ---------------------------------------------------------- via expander

    @Test
    void theDefaultExpanderShipsWithTheFaqShortcodeRegistered() {
        String rendered = BuiltInExpanders.withMocks().expand(new WeblogEntry(),
                "intro [faq][q]Q1[/q][a]A1[/a][/faq] outro");

        assertEquals("intro <dl class=\"faq\">\n<dt>Q1</dt>\n<dd>A1</dd>\n</dl> outro",
                rendered);
    }

    @Test
    void aMalformedFaqLeavesTheShortcodeTextVisibleThroughTheExpander() {
        String rendered = BuiltInExpanders.withMocks().expand(new WeblogEntry(),
                "[faq][q]dangling question[/q][/faq]");

        assertEquals("[faq][q]dangling question[/q][/faq]", rendered,
                "null from the handler is the SPI's visible-failure signal");
    }
}
