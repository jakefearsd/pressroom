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
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scan for Task A10's rail cleanup: the newsletter and entry-revisions
 * boxes stop being Bootstrap {@code .card}s (a different shape from every
 * other rail box) and become {@code .editor-box}/{@code .rail-group-label},
 * the same shape Publish/Organize/the SEO drawer already use, and the delete
 * link moves to sit after them so the rail reads as one continuous column
 * rather than "boxes, then two cards, then a link squeezed back inside the
 * boxes".
 */
class EntryEditJspTest {

    private static final Path ENTRY_EDIT = Paths.get("src/main/webapp/WEB-INF/jsps/editor/EntryEdit.jsp");

    private static final String RAIL_START = "<div class=\"editor-rail\">";
    private static final String RAIL_END = "<%-- /editor-grid --%>";

    private static String read() throws IOException {
        return Files.readString(ENTRY_EDIT, StandardCharsets.UTF_8);
    }

    /**
     * The rail's visual column spans from the rail div through the newsletter
     * and revisions boxes (placed after the {@code #entry} form closes, so
     * their own {@code <form>}s do not nest inside it -- see the comment
     * above {@code .editor-grid} in EntryEdit.jsp) up to the grid's own
     * closing marker. That whole span is what a reader sees as "the rail",
     * and none of it may fall back to a Bootstrap card.
     */
    private static String railRegion(String jsp) {
        int start = jsp.indexOf(RAIL_START);
        assertTrue(start >= 0, "EntryEdit.jsp must have a <div class=\"editor-rail\">");
        int end = jsp.indexOf(RAIL_END, start);
        assertTrue(end >= 0, "EntryEdit.jsp must close the editor-grid after the rail");
        return jsp.substring(start, end);
    }

    @Test
    void theRailNeverFallsBackToABootstrapCard() throws IOException {
        String rail = railRegion(read());
        assertTrue(!rail.contains("class=\"card"),
                "the rail (Publish/Organize/SEO/newsletter/revisions/delete) must be one "
                        + "shape -- .editor-box/.rail-group-label -- never a Bootstrap .card:\n" + rail);
    }

    @Test
    void theDeleteLinkIsTheLastThingInTheRail() throws IOException {
        String rail = railRegion(read());
        int deleteAt = rail.indexOf("id=\"deleteEntryButton\"");
        assertTrue(deleteAt >= 0, "deleteEntryButton must still exist in the rail region");

        int newsletterAt = rail.indexOf("id=\"newsletterCard\"");
        int revisionsAt = rail.indexOf("id=\"entryRevisionsCard\"");
        if (newsletterAt >= 0) {
            assertTrue(deleteAt > newsletterAt, "delete must come after the newsletter box");
        }
        if (revisionsAt >= 0) {
            assertTrue(deleteAt > revisionsAt, "delete must come after the revisions box");
        }
    }

    @Test
    void theNewsletterAndRevisionsBoxesKeepTheirPinnedIds() throws IOException {
        // These ids are driven directly by ITs/unit tests and must never be
        // renamed -- see the brief. This test exists so a future restyle
        // that reaches for these boxes trips over an assertion here rather
        // than a browser test three files away.
        String jsp = read();
        for (String id : new String[] {
                "id=\"newsletterCard\"", "id=\"newsletterSentAt\"", "id=\"newsletterNoList\"",
                "id=\"sendNewsletterButton\"", "id=\"entryRevisionsTable\"", "id=\"deleteEntryButton\""
        }) {
            assertTrue(jsp.contains(id), "expected " + id + " to still be present in EntryEdit.jsp");
        }
    }

    /**
     * Task M1: the publish rail states status through the one shared pill
     * component, not through a Bootstrap badge in stock semantic colours.
     *
     * <p>{@code JspConsistencyTest.statusIsAlwaysAStatusPill} bans the badge
     * classes tree-wide; this pins the other half, which that scan cannot see
     * -- that the rail actually <em>includes</em> {@code StatusPill.jsp}
     * rather than hand-rolling a fifth spelling of the same five states. The
     * sixth variant, {@code UNSAVED}, exists for exactly this caller: an entry
     * being composed has no {@code PubStatus} at all, and the pill's vocabulary
     * covers "not yet saved" so the rail does not need a branch outside it.
     */
    @Test
    void theRailStatesStatusThroughTheSharedPill() throws IOException {
        String rail = railRegion(read());
        assertTrue(rail.contains("/WEB-INF/jsps/editor/StatusPill.jsp"),
                "the publish rail must render status through the shared StatusPill.jsp "
                        + "include, the same component Entries.jsp and Pages.jsp use");
        assertTrue(rail.contains("value=\"UNSAVED\""),
                "an entry with no status yet must render the pill's UNSAVED variant, "
                        + "not a branch of its own");
        for (String badge : new String[] {"badge bg-success", "badge bg-info",
                "badge bg-warning", "badge bg-danger", "badge bg-primary"}) {
            assertTrue(!rail.contains(badge),
                    "the rail still carries a Bootstrap " + badge + " -- use the status pill");
        }
    }

    /**
     * Task M1 (task B2's three buckets, applied to the editor): Publish is the
     * screen's one recommended action and wears {@code btn-primary}; every
     * other control on it is {@code btn-secondary} or {@code btn-danger}.
     * {@code btn-success} is not a fourth bucket, and a bare {@code class="btn"}
     * -- which renders as unstyled grey with no bucket at all -- is not a way
     * of opting out of the question.
     */
    @Test
    void theRailsButtonsAreTheThreeBuckets() throws IOException {
        String jsp = read();
        assertTrue(!jsp.contains("btn-success"),
                "btn-success is not a bucket: Publish is the screen's primary, "
                        + "Save draft is secondary");
        assertTrue(!jsp.matches("(?s).*class=\"btn\"[^>]*>.*"),
                "a bare class=\"btn\" carries no bucket -- pick btn-primary, "
                        + "btn-secondary or btn-danger");
        assertTrue(jsp.contains("class=\"btn btn-primary\" formaction="),
                "the publish/submit-for-review button is the screen's one primary");
        assertTrue(jsp.contains("class=\"btn btn-secondary\" formaction="),
                "Save draft is a secondary action beside it");
    }

    /**
     * A revision's note (an API update's revisionNote, spec 2026-10-24
     * section 5) is shown in the revisions table, escaped, on a row of its own
     * under its revision, and only when there is one: the rail is narrow, and
     * a 255-character note in a cell beside the date would stand the row on end.
     */
    @Test
    void aRevisionsNoteIsShownEscapedUnderItsRevision() throws IOException {
        String jsp = read();
        int table = jsp.indexOf("id=\"entryRevisionsTable\"");
        assertTrue(table >= 0, "the revisions table is missing");
        String rows = jsp.substring(table, jsp.indexOf("</table>", table));

        assertTrue(rows.contains("<c:if test=\"${not empty revision.note}\">"), rows);
        assertTrue(rows.contains("<td colspan=\"4\" class=\"revision-note\">${fn:escapeXml(revision.note)}</td>"),
                rows);
        assertTrue(rows.indexOf("revision-restore-button") < rows.indexOf("revision-note"),
                "the note follows its revision's row");
    }

    /**
     * Task M1 (task B5's date vocabulary, applied to the editor): every
     * timestamp this screen renders goes through {@code <rc:date>}.
     *
     * <p>The three sites disagreed with each other and with the entry's own
     * pubtime field. The rail's {@code .editor-when} used {@code fmt:formatDate}
     * (the JVM's zone -- the server's, which is nobody's clock), the revisions
     * table used {@code weblogEntryQuery.date.toStringFormat} (the request
     * locale's zone, and a display pattern eight translators could disagree
     * about), and the newsletter line handed a raw {@code Date} straight to
     * {@code MessageFormat}. {@code <rc:date>} renders all three in the
     * weblog's zone -- the zone {@code bean.pubTimeLocal} has always meant --
     * inside a {@code <time>} whose {@code datetime} is a machine-readable
     * UTC instant.
     *
     * <p>The newsletter line captures the tag's output into a variable rather
     * than nesting the tag: {@code <spring:message>} takes its arguments as an
     * attribute, and dropping the attribute entirely would make the call site
     * read as zero-argument to {@code MessagePlaceholderContractTest} against a
     * message that declares one.
     */
    @Test
    void everyTimestampInTheEditorGoesThroughTheDateTag() throws IOException {
        String jsp = read();
        assertTrue(!jsp.contains("fmt:formatDate"),
                "fmt:formatDate renders in the server's timezone and emits no "
                        + "machine-readable datetime -- use <rc:date>");
        assertTrue(!jsp.contains("weblogEntryQuery.date.toStringFormat"),
                "a date pattern is not a translatable string -- use <rc:date>");
        assertTrue(jsp.contains("<rc:date value=\"${entry.updateTime}\"/>"),
                "the rail's .editor-when must render the update time with <rc:date>");
        assertTrue(jsp.contains("<rc:date value=\"${revision.created}\"/>"),
                "each revision row's timestamp must render with <rc:date>");
        assertTrue(jsp.contains("<rc:date value=\"${entry.newsletterSentAt}\"/>"),
                "newsletter.sentAt's argument must be built with <rc:date>");
    }

    /**
     * Task M1 (task B7's one modal shape, applied to the editor). Three
     * modals live on this screen -- send-as-newsletter, revision diff and
     * delete-entry -- and each broke the shape a different way: the first two
     * titled themselves with an {@code h4} (a document heading competing with
     * the page's own outline, and a level skip under the layout's {@code h2}),
     * and the delete modal wrapped an {@code h3} plus a sentence inside a
     * {@code div.modal-title}, which is the class doing the work of a header
     * AND a body at once.
     *
     * <p>The footer order is asserted per-modal rather than tree-wide because
     * the tree-wide rule only bites once a button carries a bucket:
     * {@code JspConsistencyTest} looks for a {@code btn-danger}/
     * {@code btn-primary} appearing before the dismiss control, so a footer
     * whose action button had no variant class at all passed it while reading
     * back-to-front on screen.
     */
    @Test
    void theEditorModalsTakeTheOneModalShape() throws IOException {
        String jsp = read();
        assertTrue(!jsp.contains("<h4") && !jsp.contains("<h5"),
                "a modal title is a caps-label role, not a heading -- and h4/h5 under "
                        + "the layout's own h2 page title is a level skip besides");
        for (String id : new String[] {"newsletter-confirm-modal-title",
                "revision-diff-modal-title", "delete-entry-modal-title"}) {
            assertTrue(jsp.contains("<p id=\"" + id + "\" class=\"modal-title\">"),
                    "modal title " + id + " must be a <p class=\"modal-title\">, "
                            + "the shape every other admin modal uses");
        }
        for (String modal : new String[] {"newsletter-confirm-modal", "delete-entry-modal"}) {
            int at = jsp.indexOf("id=\"" + modal + "\"");
            assertTrue(at >= 0, modal + " must still exist -- browser tests open it by id");
            int footer = jsp.indexOf("class=\"modal-footer\"", at);
            assertTrue(footer >= 0, modal + " has no footer");
            int dismiss = jsp.indexOf("data-bs-dismiss", footer);
            int action = jsp.indexOf("type=\"submit\"", footer);
            assertTrue(dismiss >= 0 && action > dismiss,
                    modal + ": Bootstrap packs a .modal-footer left-to-right in DOM "
                            + "order, so cancel goes first and the destructive/primary "
                            + "action last");
        }
    }

    @Test
    void theNewsletterNoListMessageIsFollowedByItsOwnLinkText() throws IOException {
        String jsp = read();
        int noListAt = jsp.indexOf("code=\"newsletter.noList\"");
        assertTrue(noListAt >= 0, "expected a <spring:message code=\"newsletter.noList\"/>");

        // The next few hundred characters (well inside the same <p>) must
        // carry a SEPARATE <a> whose own text is the new link key, not a
        // reuse of a generic key like tabbedmenu.website.settings -- that is
        // the whole point of Task A10's decision: the sentence and the link
        // are two distinct pieces of copy.
        int searchLimit = Math.min(jsp.length(), noListAt + 500);
        String window = jsp.substring(noListAt, searchLimit);
        int linkAt = window.indexOf("code=\"newsletter.noList.link\"");
        assertTrue(linkAt >= 0,
                "expected a <spring:message code=\"newsletter.noList.link\"/> shortly after "
                        + "newsletter.noList, inside its own <a>:\n" + window);

        int anchorAt = window.lastIndexOf("<a ", linkAt);
        assertTrue(anchorAt >= 0 && anchorAt < linkAt,
                "the newsletter.noList.link message must render inside an <a> element");
    }

    /** The Comments closed checkbox binds the bean beside noindex, and the counts line links to moderation. */
    @Test
    void theEditorOffersCommentsClosedAndTheCountsLine() throws IOException {
        String jsp = read();
        assertTrue(jsp.contains("name=\"bean.commentsClosed\""), "Comments closed checkbox");
        assertTrue(jsp.contains("code=\"weblogEdit.commentsClosed\""));
        assertTrue(jsp.contains("code=\"weblogEdit.commentCounts\""), "counts line");
        assertTrue(jsp.contains("/roller-ui/authoring/comments.rol"), "links to the moderation page");
        assertTrue(jsp.contains("actionWeblog.commentsEnabled"), "shown only when comments are on");
    }

    /**
     * Category names are rejected at entry if they carry HTML characters, but
     * the option is escaped anyway: that check lives in another controller,
     * and the API is a second way in.
     */
    @Test
    void theCategoryOptionNameIsEscaped() throws IOException {
        String jsp = read();
        assertFalse(jsp.contains(">${opt.name}</option>"), "a bare category name in the Organize select");
        assertTrue(jsp.contains(">${fn:escapeXml(opt.name)}</option>"));
    }

    /**
     * The SEO drawer sits in the 252px rail. Bootstrap's horizontal grid gave
     * its labels a ~60px column beside a ~170px field above 576px; the rail's
     * other boxes put the label above a full-width field, and so does this.
     */
    @Test
    void theSeoDrawerPutsLabelsAboveFields() throws IOException {
        String jsp = read();
        int start = jsp.indexOf("id=\"collapseSeo\"");
        int end = jsp.indexOf("<sec:csrfInput/>", start);
        assertTrue(start >= 0 && end > start, "the SEO drawer must exist");
        String drawer = jsp.substring(start, end);
        assertFalse(drawer.contains("col-sm-"), "no horizontal-form grid in the rail");
        assertFalse(drawer.contains("offset-sm-"), "no horizontal-form grid in the rail");
        assertFalse(drawer.contains("col-form-label"), "labels take the rail's editor-field-label role");
    }

    /**
     * Delete moves the entry to the trash, so the dialog says that and its
     * action button names it. The entry's internal id is no business of the
     * author's, a two-line confirm needs no large dialog, and every other
     * modal on the page has a close control.
     */
    @Test
    void theDeleteDialogSaysTrashAndOffersCancel() throws IOException {
        String jsp = read();
        int start = jsp.indexOf("id=\"delete-entry-modal\"");
        assertTrue(start >= 0, "the delete modal must exist");
        String modal = jsp.substring(start, jsp.indexOf("<%-- ====", start));
        assertFalse(modal.contains("modal-lg"), "a confirm is a default-size dialog");
        assertTrue(modal.contains("class=\"btn-close\""), "the dialog needs a close control");
        assertFalse(modal.contains("weblogEntryRemove.entryId"), "the internal id is not shown to the author");
        assertFalse(modal.contains("generic.yes") || modal.contains("generic.no"), "Yes/No do not say what happens");
        assertTrue(modal.contains("code=\"generic.cancel\""));
        assertTrue(modal.contains("code=\"weblogEntryRemove.moveToTrash\""));
        assertTrue(modal.contains("<p id=\"delete-entry-modal-title\" class=\"modal-title\">"));
        int body = modal.indexOf("class=\"modal-body\"");
        assertTrue(body >= 0 && modal.indexOf("weblogEntryRemove.areYouSure", body) > body,
                "the sentence is in the body, not the title");
        assertEquals(1, modal.split("type=\"submit\"", -1).length - 1,
                "exactly one submit: the ITs click #delete-entry-modal button[type='submit']");
        assertFalse(jsp.contains("postIdLabel"), "nothing writes the removed id row");
    }

    /**
     * A title is stored HTML-escaped, so {@code fn:escapeXml(entry.title)}
     * escaped it twice: "Tom &amp;amp; Jerry" in the attribute, and the
     * modal (filled through .text()) showed "Tom &amp; Jerry". The bean holds
     * the unescaped title (EntryBean.copyFrom), so escaping that is once.
     */
    @Test
    void theDeleteControlEscapesTheTitleExactlyOnce() throws IOException {
        String jsp = read();
        assertFalse(jsp.contains("fn:escapeXml(entry.title)"), "the stored title is already escaped");
        assertTrue(jsp.contains("data-entry-title=\"${fn:escapeXml(bean.title)}\""));
    }

    @Test
    void theNewsletterDialogDismissesWithCancel() throws IOException {
        String jsp = read();
        int start = jsp.indexOf("id=\"newsletter-confirm-modal\"");
        assertTrue(start >= 0, "the newsletter modal must exist");
        String modal = jsp.substring(start, jsp.indexOf("</form>", start));
        assertFalse(modal.contains("generic.no"));
        assertTrue(modal.contains("code=\"generic.cancel\""));
    }

    /**
     * The JSP config trims whitespace-only template text, so the space
     * between the no-list sentence and its link vanished ("first.Open weblog
     * settings"). An explicit space survives trimming.
     */
    @Test
    void theNewsletterNoListSentenceKeepsItsSpaceBeforeTheLink() throws IOException {
        assertTrue(read().contains("<spring:message code=\"newsletter.noList\"/>&#32;"));
    }

    /**
     * On a published entry "Publish" beside "Save draft" did not say that
     * Save draft takes the entry down. The buttons say Update and Unpublish
     * there, and Ctrl+S -- "save" -- follows Update rather than unpublishing.
     */
    @Test
    void aPublishedEntryOffersUpdateAndUnpublish() throws IOException {
        String jsp = read();
        assertTrue(jsp.contains("<spring:message code=\"weblogEdit.update\"/>"), "Update on a live entry");
        assertTrue(jsp.contains("<spring:message code=\"weblogEdit.unpublish\"/>"), "Unpublish on a live entry");
        int update = jsp.indexOf("<spring:message code=\"weblogEdit.update\"/>");
        String updateButton = jsp.substring(jsp.lastIndexOf("<button", update), update);
        assertTrue(updateButton.contains("data-save-shortcut"), "Ctrl+S must click Update on a live entry: " + updateButton);
        String editor = Files.readString(Paths.get("src/main/webapp/WEB-INF/jsps/editor/EntryEditor.jsp"),
                StandardCharsets.UTF_8);
        assertTrue(editor.contains("#entry button[data-save-shortcut]"), "Ctrl+S clicks the marked button");
    }
}
