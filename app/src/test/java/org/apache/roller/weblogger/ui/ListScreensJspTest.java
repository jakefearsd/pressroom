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
package org.apache.roller.weblogger.ui;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scans for the three authoring list screens -- Entries, Submissions
 * and Comments -- and the Entries filter sidebar, from the 2026-10
 * fit-and-finish audit. Each test names the defect it pins: a modal that
 * promised "This cannot be undone" over a move to the Trash, a count built
 * by concatenating a number onto an English fragment, titles escaped twice,
 * a modal header doing a body's work, Yes/No answers, an internal id shown to
 * authors, an empty state that called a filtered list an empty weblog, and
 * pagers with no accessible name.
 */
class ListScreensJspTest {

    private static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps/editor");
    private static final Path ENTRIES = JSPS.resolve("Entries.jsp");
    private static final Path SUBMISSIONS = JSPS.resolve("Submissions.jsp");
    private static final Path COMMENTS = JSPS.resolve("Comments.jsp");
    private static final Path SIDEBAR = JSPS.resolve("EntriesSidebar.jsp");
    private static final Path BUNDLE = Path.of("src/main/resources/ApplicationResources.properties");

    /** The JSP without its {@code <%-- --%>} comments, which describe the old shapes by name. */
    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replaceAll("(?s)<%--.*?--%>", "");
    }

    private static Properties bundle() throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(BUNDLE, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    /** The element whose id is {@code id}, from its start tag to the end of that tag's line block. */
    private static String modal(String src, String id) {
        int start = src.indexOf("id=\"" + id + "\"");
        assertTrue(start >= 0, "no #" + id);
        int end = src.indexOf("class=\"modal-footer\"", start);
        assertTrue(end > start, "#" + id + " has no .modal-footer");
        int footerEnd = src.indexOf("</div>", end);
        return src.substring(start, footerEnd);
    }

    // ------------------------------------------------------------ the bugs

    /** Bulk delete moves entries to the Trash; the warning said they were gone for good. */
    @Test
    void theBulkDeleteWarningDoesNotClaimTheEntriesAreGoneForGood() throws IOException {
        String warning = bundle().getProperty("weblogEntryQuery.bulkDeleteWarning");
        assertNotNull(warning, "weblogEntryQuery.bulkDeleteWarning is missing");
        assertFalse(warning.toLowerCase(java.util.Locale.ROOT).contains("cannot be undone"),
                "the bulk-delete modal says the move to the Trash cannot be undone: " + warning);
        assertTrue(warning.contains("Trash"), "the bulk-delete warning does not mention the Trash: " + warning);
    }

    /**
     * "3 entries selected. that match filter criteria." -- a number glued onto
     * an English fragment inside a JS string. The count is one bundle message
     * with its placeholder, carried in a data-template attribute (no JS-string
     * escaping to forget), on both screens that had the pattern.
     */
    @Test
    void theBulkDeleteCountIsOneMessageNotAConcatenation() throws IOException {
        for (Path jsp : List.of(ENTRIES, SUBMISSIONS, COMMENTS)) {
            String src = read(jsp);
            assertFalse(src.contains("selectedCount"), jsp + " still builds the count from a *.selectedCount fragment");
            assertFalse(src.contains("+ \" \" +"), jsp + " still concatenates the count text in JavaScript");
            Matcher count = Pattern.compile("<p id=\"[A-Za-z]+DeleteCount\" class=\"data\" "
                    + "data-template=\"<spring:message code='selection.count'/>\"></p>").matcher(src);
            assertTrue(count.find(), jsp + " has no modal count line carrying the selection.count template");
        }
        Properties props = bundle();
        assertFalse(props.containsKey("weblogEntryQuery.selectedCount"), "the garbled fragment key is still shipped");
        assertFalse(props.containsKey("submissions.selectedCount"), "the fragment key is still shipped");
    }

    /**
     * An empty moderation tab says what would be there: one body sentence per
     * tab under the shared "No comments here." title (admin/visual 37).
     */
    @Test
    void eachEmptyCommentsTabSaysWhatWouldBeThere() throws IOException {
        String comments = read(COMMENTS);
        int empty = comments.indexOf("<div class=\"empty-state\">");
        assertTrue(empty >= 0, "Comments.jsp has no empty state");
        String state = comments.substring(empty, comments.indexOf("</div>", empty));
        Properties props = bundle();
        for (String tab : new String[] {"PENDING", "PUBLISHED", "HIDDEN"}) {
            String key = "comments.none." + tab.toLowerCase(java.util.Locale.ROOT);
            assertTrue(state.contains("<c:when test=\"${status == '" + tab + "'}\"><spring:message code=\"" + key + "\"/>"),
                    "the empty " + tab + " tab has no sentence of its own");
            assertTrue(props.containsKey(key), key + " is not in the bundle");
        }
        assertTrue(state.contains("<p class=\"empty-state-body\">"), "the sentence is not the empty state's body line");
    }

    /**
     * An entry title is stored escaped, so fn:escapeXml(post.title) printed
     * "Tom &amp;amp; Jerry" into the checkbox, duplicate and delete labels and
     * the delete modal. Every label now comes from the controller's plain-text
     * entryTitles map, escaped once.
     */
    @Test
    void noEntryTitleIsEscapedASecondTime() throws IOException {
        String entries = read(ENTRIES);
        assertFalse(entries.contains("fn:escapeXml(post.title)"), "Entries.jsp escapes the stored title again");
        assertFalse(entries.contains("${post.displayTitle}"),
                "Entries.jsp prints displayTitle bare; an untitled entry's fallback is raw body text");
        assertTrue(entries.contains("data-entry-title=\"${fn:escapeXml(entryTitles[post.id])}\""),
                "the delete button's title is not the plain-text label escaped once");
        assertTrue(entries.contains("<a class=\"entry-title\" href=\"${editUrl}\">${fn:escapeXml(entryTitles[post.id])}</a>"),
                "the visible title is not the plain-text label escaped once");

        String comments = read(COMMENTS);
        assertFalse(comments.contains("${c.entry.displayTitle}"),
                "Comments.jsp prints the entry's displayTitle bare");
        assertTrue(comments.contains("${fn:escapeXml(entryTitles[c.entry.id])}"),
                "Comments.jsp does not label the entry column from entryTitles");
    }

    // ------------------------------------------------------------ the modals

    /**
     * The header is the title alone, a {@code <p class="modal-title">}; a div
     * wrapping an h3 and the sentence rendered the whole question in the
     * caps-label role. Answers name the action next to a plain Cancel.
     */
    @Test
    void everyListModalHasAPlainTitleAndNamedAnswers() throws IOException {
        List<String[]> modals = List.of(
                new String[] {"Entries.jsp", "delete-entry-modal"},
                new String[] {"Entries.jsp", "bulk-delete-modal"},
                new String[] {"Submissions.jsp", "submissions-delete-modal"},
                new String[] {"Comments.jsp", "comments-delete-modal"});
        for (String[] m : modals) {
            String block = modal(read(JSPS.resolve(m[0])), m[1]);
            String where = m[0] + " #" + m[1];
            assertTrue(Pattern.compile("<p id=\"" + m[1] + "-title\" class=\"modal-title\">").matcher(block).find(),
                    where + " has no <p class=\"modal-title\"> header");
            assertFalse(block.contains("<h3"), where + " still puts a heading inside its header");
            assertFalse(block.contains("generic.yes"), where + " still answers Yes");
            assertFalse(block.contains("generic.no\""), where + " still answers No");
            assertTrue(block.contains("code=\"generic.cancel\""), where + " has no Cancel");
        }
    }

    /** The single-entry modal showed an internal UUID in a two-column grid. */
    @Test
    void theSingleDeleteModalShowsTheTitleAndNoInternalId() throws IOException {
        String block = modal(read(ENTRIES), "delete-entry-modal");
        assertFalse(block.contains("postIdLabel"), "the delete modal still shows the entry id");
        assertFalse(block.contains("weblogEntryRemove.entryId"), "the delete modal still labels an entry id");
        assertFalse(block.contains("col-sm-3"), "the delete modal still lays the title out as a form grid");
        assertTrue(block.contains("id=\"postTitleLabel\""), "the delete modal no longer names the entry");
        assertTrue(block.contains("weblogEntryRemove.moveToTrash"), "the confirm button does not say Move to Trash");
        assertFalse(read(ENTRIES).contains("#postIdLabel"), "the script still fills the removed id row");
    }

    /**
     * Comments: one row's delete asked "Delete the selected comments?", and
     * the bulk delete was a bare data-confirm where Entries and Submissions
     * show a count. Single is a data-confirm about this comment; bulk is the
     * modal.
     */
    @Test
    void commentsConfirmOneRowAsOneAndTheSelectionWithACount() throws IOException {
        String src = read(COMMENTS);
        Matcher bulk = Pattern.compile("<button\\b[^>]*id=\"commentsDeleteSelected\"[^>]*>").matcher(src);
        assertTrue(bulk.find(), "the selection bar has no #commentsDeleteSelected");
        assertTrue(bulk.group().contains("type=\"button\""), "bulk delete submits directly: " + bulk.group());
        assertFalse(bulk.group().contains("data-confirm"), "bulk delete is still a bare data-confirm");

        Matcher row = Pattern.compile("<button\\b[^>]*name=\"ids\"[^>]*comments!delete\\.rol[^>]*>").matcher(src);
        assertTrue(row.find(), "no per-row delete button");
        assertTrue(row.group().contains("data-confirm=\"${fn:escapeXml(deleteOneConfirm)}\""),
                "a row's delete does not confirm about that one comment: " + row.group());
        assertTrue(src.contains("code=\"comments.deleteOne.confirm\""), "comments.deleteOne.confirm is not used");
        assertFalse(src.contains("comments.delete.confirm\""), "the plural confirm is still used for one row");
    }

    /** A Bootstrap gray badge, untokened, no dark mode: the author marker is a quiet pill. */
    @Test
    void theAuthorMarkerIsNotABootstrapBadge() throws IOException {
        String src = read(COMMENTS);
        assertFalse(src.contains("badge"), "Comments.jsp still uses a Bootstrap badge");
        assertEquals(2, countOccurrences(src, "<span class=\"status-pill\"><spring:message code=\"comments.author\"/></span>"),
                "both Author markers should be quiet status pills");
    }

    // ------------------------------------------------------------ empty state

    /** A filter that matches nothing is not an empty weblog. */
    @Test
    void aFilteredEmptyListSaysNothingMatchedAndOffersToClearTheFilters() throws IOException {
        String src = read(ENTRIES);
        assertTrue(src.contains("<c:when test=\"${filtered}\">"), "Entries.jsp has no filtered empty state");
        int filtered = src.indexOf("<c:when test=\"${filtered}\">");
        String branch = src.substring(filtered, src.indexOf("</c:when>", filtered));
        assertTrue(branch.contains("empty.entries.filtered.title"), "the filtered state has no title");
        assertTrue(branch.contains("empty.entries.filtered.action"), "the filtered state offers no way out");
        assertFalse(branch.contains("entryAdd.rol"), "the filtered state still invites a first post");
        assertTrue(src.contains("code=\"empty.entries.title\""), "the empty-weblog invitation is gone");
    }

    // ------------------------------------------------------------ pagers

    /** A nav landmark with no name reads as "navigation" twice on Entries. */
    @Test
    void everyPagerIsALabelledNavAndNoneHardCodesThePageSize() throws IOException {
        for (Path jsp : List.of(ENTRIES, SUBMISSIONS, COMMENTS)) {
            String src = read(jsp);
            Matcher bare = Pattern.compile("<nav>").matcher(src);
            assertFalse(bare.find(), jsp + " has a pager <nav> with no aria-label");
            assertTrue(src.contains("<nav aria-label=\"<spring:message code='pager.label'/>\">"),
                    jsp + " has no labelled pager");
            assertFalse(src.contains("* 30"), jsp + " hard-codes the page size");
        }
        assertTrue(read(ENTRIES).contains("<span aria-hidden=\"true\">&larr;</span> <spring:message code=\"pager.newer\"/>"),
                "the top pager's arrow still runs into its label");
        assertFalse(read(ENTRIES).contains("&larr;</span><spring:message"),
                "a pager arrow still runs into its label");
    }

    // ------------------------------------------------------------ sidebar

    /** Category names and the date fields are author input reflected into markup. */
    @Test
    void theSidebarEscapesCategoryNamesAndDates() throws IOException {
        String src = read(SIDEBAR);
        assertTrue(src.contains("<option value=\"${fn:escapeXml(opt.name)}\""), "category option value is raw");
        assertTrue(src.contains(">${fn:escapeXml(opt.name)}</option>"), "category option text is raw");
        assertFalse(src.contains("${opt.name}"), "a category name is still printed raw");
        assertFalse(src.contains("value=\"${bean.startDateString}\""), "the start date is reflected raw");
        assertFalse(src.contains("value=\"${bean.endDateString}\""), "the end date is reflected raw");
    }

    // ------------------------------------------------------------ visual audit

    /**
     * Visual 24: Category, Tags and Search rendered at normal weight while the
     * two dates were bold -- only the dates carried .form-label -- and the
     * casing was mixed. One label role, one group per field, sentence case.
     */
    @Test
    void everySidebarFieldHasTheSameLabelInItsOwnGroup() throws IOException {
        String src = read(SIDEBAR);
        Matcher labels = Pattern.compile("<label\\b[^>]*>").matcher(src);
        int count = 0;
        while (labels.find()) {
            count++;
            assertTrue(labels.group().contains("class=\"form-label\""), "a sidebar label is not a .form-label: "
                    + labels.group());
        }
        assertEquals(6, count, "category, tags, text, two dates and sort each have one label");
        assertFalse(src.contains("<br/>"), "the sidebar still spaces fields with <br/>");

        Properties props = bundle();
        assertEquals("Start date", props.getProperty("weblogEntryQuery.label.startDate"));
        assertEquals("End date", props.getProperty("weblogEntryQuery.label.endDate"));
        assertEquals("Search text", props.getProperty("weblogEntryQuery.label.text"));
    }

    /** Visual 14: at 1280 the Published and Updated cells wrapped to three lines. */
    @Test
    void theEntryTimestampsDoNotWrap() throws IOException {
        String src = read(ENTRIES);
        assertTrue(src.contains("<td class=\"data text-nowrap\"><rc:date value=\"${post.pubTime}\"/></td>"),
                "the Published cell can wrap");
        assertTrue(src.contains("<td class=\"data text-nowrap\"><rc:date value=\"${post.updateTime}\"/></td>"),
                "the Updated cell can wrap");
    }

    /**
     * Visual 41: the Hide-versus-Delete help showed on the Hidden tab, which
     * offers no Hide; and the entry title took the width the comment needed.
     */
    @Test
    void commentsHelpShowsOnlyWhereHideIsOfferedAndTheCommentColumnGetsTheRoom() throws IOException {
        String src = read(COMMENTS);
        assertTrue(Pattern.compile("<c:if test=\"\\$\\{status != 'HIDDEN'}\">\\s*"
                        + "<p class=\"form-text\"><spring:message code=\"comments.hideVsDelete\"/></p>").matcher(src).find(),
                "the hide-versus-delete help is not limited to the tabs that offer Hide");
        assertTrue(src.contains("<th scope=\"col\" class=\"rollertable\" width=\"20%\"><spring:message code=\"comments.column.entry\"/></th>"),
                "the Entry column is not capped, so it squeezes the comment");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
