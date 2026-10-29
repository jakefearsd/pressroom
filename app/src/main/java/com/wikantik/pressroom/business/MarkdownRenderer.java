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

import java.util.List;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.alerts.AlertsExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Turns an entry's markdown into HTML.
 *
 * <p>Every entry is markdown. There is no per-entry format flag and no second
 * format to fall back to: {@code WeblogEntry.render()} calls this
 * unconditionally, and the column that once expressed an alternative was
 * dropped in V009 so the invariant cannot drift back into a convention.
 *
 * <p>Three deliberate choices, each of which a future change could quietly
 * undo:
 *
 * <ul>
 * <li><b>Raw HTML passes through</b> ({@code escapeHtml=false}, commonmark's
 *     default). Authors paste embed snippets and hand-written markup into
 *     posts, and shortcodes expand into HTML immediately after this step, so
 *     escaping here would leave both visible as source text. This is NOT a
 *     security decision: {@code HTMLSanitizer} still runs last on the combined
 *     output, and it -- not commonmark -- is the XSS boundary.</li>
 * <li><b>It runs before shortcode expansion.</b> The shortcodes emit
 *     {@code <figure>}/{@code <div>} blocks; feeding those through a markdown
 *     parser afterwards would reflow and mangle them. See
 *     {@code WeblogEntry.render}.</li>
 * <li><b>Extensions are fixed, not configurable.</b> Tables, strikethrough and
 *     autolink are what a travel or photography post actually uses, and GitHub
 *     alerts and heading ids are what a technical one does; making the
 *     set per-weblog would mean the same source rendering differently in two
 *     places, which is exactly the drift the shortcode parsers were designed
 *     to avoid.</li>
 * </ul>
 *
 * <p>The parser and renderer are immutable and thread-safe once built
 * (commonmark documents this), so both are shared statics.
 */
public final class MarkdownRenderer {


    /** Package-visible so {@link ExternalImages} parses entry text exactly as it renders. */
    static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            AutolinkExtension.create(),
            // > [!NOTE] and its four siblings: a callout the theme styles, so
            // an author never needs inline colors that one theme cannot read.
            AlertsExtension.create(),
            // An id on every heading, so a section can be linked to.
            HeadingAnchorExtension.create());

    private static final Parser PARSER = Parser.builder()
            .extensions(EXTENSIONS)
            .build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .build();

    private MarkdownRenderer() {
    }


    /**
     * Render markdown to HTML. A null or blank input returns it unchanged
     * rather than an empty document, so callers can pass an entry's summary
     * without a null guard of their own.
     */
    public static String render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }
        return RENDERER.render(PARSER.parse(markdown));
    }
}
