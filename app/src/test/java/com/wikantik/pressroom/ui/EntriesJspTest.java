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
package com.wikantik.pressroom.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scans for the Entries list screen (task B5).
 *
 * <p>The title is the row's primary target and it goes to the <em>editor</em>.
 * That is the whole change: this list is the authoring surface, and a click on
 * a post's name there means "open this to work on it", not "show me what a
 * reader sees". The published page is still one click away, as a quiet
 * secondary link -- so the reading affordance is not lost, it is demoted. A
 * pencil column existing purely to reach the editor was then a column spent
 * on something the title already did.
 */
class EntriesJspTest {

    private static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps");
    private static final Path ENTRIES = JSPS.resolve("editor/Entries.jsp");
    private static final Path SIDEBAR = JSPS.resolve("editor/EntriesSidebar.jsp");
    private static final Path PRESSROOM_JS = Path.of("src/main/webapp/theme/scripts/pressroom.js");
    private static final Path PRESSROOM_CSS = Path.of("src/main/webapp/pressroom-ui/styles/pressroom.css");

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** The title cell, from its opening tag to the next {@code </td>}. */
    private static String titleCell(String entriesJsp) {
        int start = entriesJsp.indexOf("<td class=\"entry-cell\">");
        assertTrue(start >= 0, "Entries.jsp has no <td class=\"entry-cell\"> title cell");
        int end = entriesJsp.indexOf("</td>", start);
        assertTrue(end > start, "the title cell is never closed");
        return entriesJsp.substring(start, end);
    }

    @Test
    void theTitleIsTheFirstLinkInItsCellAndItOpensTheEditor() throws IOException {
        String jsp = read(ENTRIES);
        String cell = titleCell(jsp);

        Matcher anchors = Pattern.compile("<a\\b[^>]*>", Pattern.DOTALL).matcher(cell);
        assertTrue(anchors.find(), "the title cell contains no <a> at all");
        String first = anchors.group();
        assertTrue(first.contains("class=\"entry-title\""),
                "the first link in the title cell is not the title itself: " + first);
        assertTrue(first.contains("${editUrl}"),
                "the title link does not href the editUrl: " + first);
        assertTrue(jsp.contains("<c:url var=\"editUrl\" value=\"/pressroom-ui/authoring/entryEdit.rol\">"),
                "editUrl is not built from entryEdit.rol");
    }

    /**
     * The published page keeps a way in, as the quiet secondary link the title
     * used to be -- and only for rows that actually have a public url.
     */
    @Test
    void aPublishedRowStillOffersAQuietViewLink() throws IOException {
        String cell = titleCell(read(ENTRIES));

        assertTrue(cell.contains("${post.status.name() == 'PUBLISHED'}"),
                "the View link is not gated on the row being published");
        assertTrue(cell.contains("class=\"quiet-link\""), "the View link is not a .quiet-link");
        assertTrue(cell.contains("urls.entry(post)"), "the View link does not point at the entry's url");
        assertTrue(cell.contains("code=\"generic.view\""), "the View link has no generic.view label");
    }

    /** The anchor, in the mono data face, under the title. */
    @Test
    void theTitleCellCarriesTheEntryAnchorAsMetadata() throws IOException {
        String cell = titleCell(read(ENTRIES));

        assertTrue(cell.contains("class=\"entry-meta\""), "the title cell has no .entry-meta line");
        assertTrue(cell.contains("post.anchor"), "the .entry-meta line does not show the entry's anchor");
    }

    /** The pencil column existed only to reach the editor; the title does that now. */
    @Test
    void thePencilColumnIsGone() throws IOException {
        String jsp = read(ENTRIES);

        assertFalse(jsp.contains("bi-pencil-square"), "Entries.jsp still renders the pencil edit icon");
        // Two assertions, not one disjunction: a <spring:message> code can be
        // written with either quote style, and a combined assertFalse reports
        // the same message whichever one is present -- so the failure would
        // not say which spelling to go and look for.
        assertFalse(jsp.contains("code='generic.edit'"),
                "Entries.jsp still labels an edit control (single-quoted code)");
        assertFalse(jsp.contains("code=\"generic.edit\""),
                "Entries.jsp still labels an edit control (double-quoted code)");
    }

    /**
     * One date vocabulary. {@code fmt:formatDate} renders in the JVM's default
     * zone and {@code weblogEntryQuery.date.toStringFormat} in the request
     * locale's -- neither is the weblog's clock, and neither emits a
     * machine-readable {@code datetime}. Both are replaced by
     * {@code <rc:date>}. EntryEdit.jsp is deliberately absent: Package A owns
     * it, and post-merge task M1 converts its {@code .editor-when} spans.
     */
    @Test
    void everyAdminTimestampGoesThroughTheDateTag() throws IOException {
        List<Path> dateBearing = List.of(
                JSPS.resolve("editor/Entries.jsp"),
                JSPS.resolve("editor/Trash.jsp"),
                JSPS.resolve("editor/Submissions.jsp"),
                JSPS.resolve("editor/Pages.jsp"),
                JSPS.resolve("editor/StatusPill.jsp"));

        for (Path jsp : dateBearing) {
            String src = read(jsp);
            assertFalse(src.contains("weblogEntryQuery.date.toStringFormat"),
                    jsp + " still formats a date through the message bundle; use <rc:date>");
            assertFalse(src.contains("fmt:formatDate"),
                    jsp + " still uses fmt:formatDate; use <rc:date>");
        }

        assertTrue(read(ENTRIES).contains("<rc:date value=\"${post.pubTime}\"/>"),
                "Entries.jsp does not render pubTime through <rc:date>");
        assertTrue(read(JSPS.resolve("editor/StatusPill.jsp")).contains("<rc:date value=\"${pillWhen}\"/>"),
                "StatusPill.jsp does not render its timestamp through <rc:date>");
    }

    /** The tag has to be declared somewhere for {@code rc:} to resolve to it. */
    @Test
    void theDateTagIsDeclaredInTheConfigTld() throws IOException {
        String tld = read(Path.of("src/main/webapp/WEB-INF/rollerConfig.tld"));

        assertTrue(tld.contains("<name>date</name>"), "rollerConfig.tld declares no <tag> named date");
        assertTrue(tld.contains("com.wikantik.pressroom.ui.tags.DateTag"),
                "rollerConfig.tld does not point the date tag at DateTag");
        assertTrue(tld.indexOf("<tag>") < tld.indexOf("<function>"),
                "the TLD schema requires every <tag> before every <function>");
    }

    /**
     * The chips are the one status control on this screen, so the sidebar's
     * radio set is gone -- two controls for one filter is how a page ends up
     * showing DRAFT while the sidebar claims ALL. The status the chips chose
     * still has to survive a sidebar submit, which is what the hidden input
     * is for.
     */
    @Test
    void theSidebarHasNoSecondStatusControlButStillCarriesTheChosenStatus() throws IOException {
        String sidebar = read(SIDEBAR);

        assertFalse(sidebar.contains("type=\"radio\""),
                "EntriesSidebar.jsp still offers radios; the chips are the one status control");
        assertTrue(sidebar.contains("<input type=\"hidden\" name=\"bean.status\""),
                "EntriesSidebar.jsp drops the chip's status filter on submit");
    }

    /**
     * Folded in from B5's round-1 fix (task-B5-report.md, "Held back"):
     * {@code EntriesBean.status} defaults to {@code "ALL"} and
     * {@code EntriesController.execute} does
     * {@code "ALL".equals(status) ? null : PubStatus.valueOf(status)} -- so an
     * UNGUARDED hidden {@code bean.status} input posts {@code bean.status=}
     * (empty) whenever the incoming request had a blank status, overriding
     * the "ALL" default and reaching {@code PubStatus.valueOf("")}, an
     * uncaught {@code IllegalArgumentException} (a 500, not a degraded list).
     * {@code Entries.jsp}'s own bulk form already guards the same field with
     * {@code <c:if test="${not empty bean.status}">}; the sidebar's copy must
     * carry the identical guard.
     */
    @Test
    void theHiddenStatusFieldIsGuardedAgainstAnEmptyValue() throws IOException {
        String sidebar = read(SIDEBAR);

        Matcher guarded = Pattern.compile(
                "<c:if\\s+test=\"\\$\\{not empty bean\\.status}\">\\s*"
                        + "<input type=\"hidden\" name=\"bean\\.status\"").matcher(sidebar);
        assertTrue(guarded.find(),
                "EntriesSidebar.jsp posts bean.status unconditionally -- an empty incoming status "
                        + "reaches PubStatus.valueOf(\"\") and 500s; wrap the hidden input in "
                        + "<c:if test=\"${not empty bean.status}\"> the way Entries.jsp's bulk form does");
    }

    /**
     * Sort is a select that submits on change, and the submit is wired by a
     * delegated listener rather than an inline {@code onchange} -- same
     * reasoning as {@code data-confirm} (see pressroom.js): behaviour belongs in
     * the script, not in an attribute a translated string can break.
     */
    @Test
    void sortIsASelectThatSubmitsWithoutInlineJavascript() throws IOException {
        String sidebar = read(SIDEBAR);

        Matcher select = Pattern.compile("<select\\b[^>]*name=\"bean\\.sortBy\"[^>]*>").matcher(sidebar);
        assertTrue(select.find(), "EntriesSidebar.jsp does not offer sort as a <select name=\"bean.sortBy\">");
        assertTrue(select.group().contains("data-submit-on-change"),
                "the sort select does not submit its form on change: " + select.group());
        assertFalse(sidebar.contains("onchange="), "EntriesSidebar.jsp uses an inline onchange handler");

        assertTrue(read(PRESSROOM_JS).contains("data-submit-on-change"),
                "pressroom.js has no delegated handler for data-submit-on-change");
    }

    @Test
    void theListTypographyIsInTheStylesheet() throws IOException {
        String css = read(PRESSROOM_CSS);

        for (String selector : List.of(".entry-title", ".entry-meta", ".quiet-link", "time.data")) {
            assertTrue(css.contains(selector), "pressroom.css lacks " + selector);
        }
    }
}
