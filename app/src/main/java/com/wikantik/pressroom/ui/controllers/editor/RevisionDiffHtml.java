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

package com.wikantik.pressroom.ui.controllers.editor;

import java.util.List;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.wikantik.pressroom.util.HTMLSanitizer;
import com.wikantik.pressroom.util.TextDiff;

/**
 * The HTML fragment an editor's revision modal shows: one labelled diff per
 * field that changed between a revision and the item as it stands, or a line
 * saying nothing did. Shared by the entry and page editors.
 *
 * <p>A revision holds the author's raw Markdown, which may contain any
 * amount of HTML. It is shown as source, so every character of it is escaped
 * and none of it rendered.
 */
final class RevisionDiffHtml {

    /** One field's label with its text in the revision and as it stands now. */
    record Section(String label, String before, String after) {
    }

    private RevisionDiffHtml() {
    }

    /**
     * The fragment: a labelled diff for each section whose text changed. With
     * every section unchanged (the revision matches the item exactly) it
     * carries {@code sameMessage} instead, so the modal never opens blank.
     */
    @SuppressFBWarnings(
            value = "POTENTIAL_XML_INJECTION",
            justification = "The message comes from the bundle and goes through HTMLSanitizer.htmlEncodeTag "
                    + "into element content: with < and > encoded no tag can open there.")
    static String of(String sameMessage, Section... sections) {
        StringBuilder html = new StringBuilder(1024).append("<div class=\"revision-diff\">");
        int empty = html.length();
        for (Section section : sections) {
            append(html, section);
        }
        if (html.length() == empty) {
            html.append("<p class=\"revision-diff-same\">").append(HTMLSanitizer.htmlEncodeTag(sameMessage))
                    .append("</p>");
        }
        return html.append("</div>").toString();
    }

    /** One field's diff under its label; a field that did not change adds nothing. */
    @SuppressFBWarnings(
            value = "POTENTIAL_XML_INJECTION",
            justification = "The label and every diff line go through HTMLSanitizer.htmlEncodeTag into "
                    + "element content (p, span inside pre): with < and > encoded no tag can open there. "
                    + "The diff is shown only to the item's editors.")
    private static void append(StringBuilder html, Section section) {
        List<TextDiff.Line> lines = TextDiff.diff(section.before(), section.after());
        if (lines.isEmpty()) {
            return;
        }
        // A caps label, not a heading: the modal's own title is a <p>, so an
        // <h5> here would be a heading with nothing above it.
        html.append("<p class=\"rail-group-label\">").append(HTMLSanitizer.htmlEncodeTag(section.label()))
                .append("</p><pre>");
        for (TextDiff.Line line : lines) {
            String cssClass = switch (line.kind()) {
                case ADDED -> "diff-added";
                case REMOVED -> "diff-removed";
                case SAME -> "diff-same";
            };
            String marker = switch (line.kind()) {
                case ADDED -> "+ ";
                case REMOVED -> "- ";
                case SAME -> "  ";
            };
            html.append("<span class=\"").append(cssClass).append("\">").append(marker)
                    .append(HTMLSanitizer.htmlEncodeTag(line.text())).append("</span>\n");
        }
        html.append("</pre>");
    }
}
