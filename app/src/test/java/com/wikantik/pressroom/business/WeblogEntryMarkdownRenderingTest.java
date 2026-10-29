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
package com.wikantik.pressroom.business;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The markdown step where it actually lives: inside {@code WeblogEntry}'s
 * render funnel, ahead of shortcode expansion and the sanitizer.
 *
 * <p>Two properties matter more than the conversion itself. An entry that did
 * not opt in must render byte for byte as it did before markdown existed --
 * every entry in every existing blog is such an entry. And the ordering must
 * be shortcodes-then-markdown: running markdown first would escape the quotes
 * in a shortcode's attributes to &amp;quot;, which the expander's grammar does
 * not match, silently breaking every shortcode in every markdown entry.
 */
class WeblogEntryMarkdownRenderingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("mdrenderuser");
        weblog = TestUtils.setupWeblog("mdrenderblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    /** The tier's renderer -- the entity no longer renders itself. */
    private static EntryRenderer renderer() {
        return TestUtils.weblogger().getEntryRenderer();
    }

    private WeblogEntry entry(String text) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(
                "md-" + Long.toString(System.nanoTime(), 36), weblog, user);
        WeblogEntry managed = TestUtils.weblogger()
                .getWeblogEntryManager().getWeblogEntry(entry.getId());
        managed.setText(text);
        return managed;
    }

    // --------------------------------------------------------- the invariant

    @Test
    void everyEntryIsMarkdown() throws Exception {
        // No flag, no opt-in, no second format: markdown is what an entry is.
        String rendered = renderer().transformedText(entry("## Day one\n\nA **long** drive.\n"));

        assertTrue(rendered.contains("<h2 id=\"day-one\">Day one</h2>"), rendered);
        assertTrue(rendered.contains("<strong>long</strong>"), rendered);
        assertFalse(rendered.contains("## Day one"),
                "no raw markdown may reach a reader: " + rendered);
    }

    @Test
    void htmlAnAuthorPastesStillSurvives() throws Exception {
        // Markdown being mandatory does not mean HTML is impossible: raw HTML
        // passes through the parser untouched, which is what lets an author
        // paste an embed snippet and what lets the shortcodes emit markup.
        String rendered = renderer().transformedText(entry("Before\n\n<div class=\"embed\">kept</div>\n\nAfter"));

        assertTrue(rendered.contains("<div class=\"embed\">kept</div>"), rendered);
        assertFalse(rendered.contains("&lt;div"), rendered);
    }

    // ---------------------------------------------------------- the ordering

    @Test
    void shortcodesExpandBeforeMarkdownRuns() throws Exception {
        // Top risk 1 of the wave, and the order is the reverse of the
        // intuitive one. Markdown-first would escape the quotes in
        // [image id="..."] to &quot;, which the expander's attribute grammar
        // does not match -- every attribute-carrying shortcode would silently
        // stop working in markdown entries, which is the whole feature.
        // Expanding first is safe because commonmark passes the emitted raw
        // HTML through verbatim, in block and inline positions alike.
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "md-order-probe.jpg");
        String imageId = image.getId();
        TestUtils.endSession(true);
        WeblogEntry entry = entry("> Notes:\n>\n> - first\n> - second\n\n[image id=\""
                        + imageId + "\"]\n");
        String rendered = renderer().transformedText(entry);

        assertTrue(rendered.contains("<blockquote>"), "markdown ran: " + rendered);
        assertTrue(rendered.contains("<li>first</li>"), "and produced the list: " + rendered);
        assertTrue(rendered.contains("<figure class=\"shortcode-image\""),
                "the shortcode must have expanded, which only happens if the expander "
                        + "saw real quotes rather than &quot;: " + rendered);
        assertFalse(rendered.contains("[image id="),
                "and no shortcode source may survive: " + rendered);
    }

    @Test
    void markdownEntriesAreStillSanitized() throws Exception {
        // commonmark passes raw HTML through by design, so the sanitizer is
        // the boundary -- and it runs last, after markdown and shortcodes.
        WeblogEntry entry = entry("Careful now\n\n<script>alert(1)</script>\n");
        String rendered = renderer().transformedText(entry);
        assertFalse(rendered.contains("<script"),
                "the sanitizer must strip script from markdown output too: " + rendered);
    }

    @Test
    void theSummaryTakesTheSamePath() throws Exception {
        WeblogEntry entry = entry("unused");
        entry.setSummary("A **bold** summary.");
        assertTrue(renderer().transformedSummary(entry).contains("<strong>bold</strong>"),
                "transformedSummary must honour the flag too: "
                        + renderer().transformedSummary(entry));
    }

}
