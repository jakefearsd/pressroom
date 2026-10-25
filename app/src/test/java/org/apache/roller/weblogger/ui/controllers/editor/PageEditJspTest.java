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
package org.apache.roller.weblogger.ui.controllers.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scans over the page editor's markup, and over the two includes that
 * make it the <em>same</em> editor the entry screen runs.
 *
 * <p>The page editor was a Bootstrap grid form with its own minimal editor
 * mount, its own copy of the upload script, and no toolbar, mode control,
 * preview, guide or status line. Everything an author does to a page is
 * therefore either identical to the entry editor or a defect -- so the
 * markup and the script are single-homed in
 * {@code EditorSurface.jsp}/{@code EditorScript.jsp} and both screens include
 * them. A copy is what these scans exist to prevent: nothing about a second
 * copy of the editor fails loudly, it simply drifts.
 *
 * <p>Each scan reads the JSP because none of this can fail at compile time,
 * the same reasoning {@code EditorAutosaveWiringTest} and the
 * {@code _showInNav} marker check in {@link PageEditControllerTest} give.
 */
class PageEditJspTest {

    private static final Path PAGE_EDIT =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/PageEdit.jsp");
    private static final Path ENTRY_EDITOR =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/EntryEditor.jsp");
    private static final Path EDITOR_SURFACE =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/EditorSurface.jsp");
    private static final Path EDITOR_SCRIPT =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/EditorScript.jsp");
    private static final Path PAGES =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/Pages.jsp");

    private static String read(Path jsp) throws IOException {
        return Files.readString(jsp, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------ the writing-surface layout

    @Test
    void thePageEditorIsLaidOutAsAWritingSurfaceWithARail() throws IOException {
        String jsp = read(PAGE_EDIT);
        for (String required : new String[] {
                "class=\"editor-grid\"", "class=\"editor-main\"", "class=\"editor-rail\"",
                "editor-form" }) {
            assertTrue(jsp.contains(required),
                    "PageEdit.jsp must carry " + required + " -- the rail layout is the "
                            + "whole point of the screen, and roller.css styles nothing "
                            + "without these hooks");
        }
        assertTrue(jsp.contains("class=\"editor-title\""),
                "the title is the page's one piece of layout hierarchy, not a "
                        + "form-control in a Bootstrap row");
    }

    @Test
    void thePageEditorWarnsBeforeTheSessionExpires() throws IOException {
        // Copied from EntryEdit.jsp: an author can spend an hour on a page as
        // easily as on an entry, and a save that lands on the login screen
        // loses the lot.
        String jsp = read(PAGE_EDIT);
        assertTrue(jsp.contains("id=\"sessionExpiryBar\""),
                "PageEdit.jsp must render the session-expiry bar");
        assertTrue(jsp.contains("data-timeout=\"${pageContext.session.maxInactiveInterval}\""),
                "the bar's arithmetic is local, against the container's own "
                        + "maxInactiveInterval -- without the attribute the script "
                        + "returns early and the bar never appears");
    }

    @Test
    void theSlugSitsOnThePermalinkLineUnderTheWeblogsOwnRoot() throws IOException {
        // A page is served at /<handle>/<slug> -- a bare single segment. The
        // old form showed "<root>page/<slug>", which is the CUSTOM-template
        // route, not a page's, so the copy control handed out a URL that 404s.
        String jsp = read(PAGE_EDIT);
        assertTrue(jsp.contains("class=\"editor-permalink\""),
                "the slug belongs on the permalink line, not in a labelled row");
        assertTrue(jsp.contains("class=\"editor-slug-prefix\"") && jsp.contains("class=\"editor-slug\""),
                "the permalink line is a prefix span plus the slug input");
        assertFalse(jsp.contains("}page/"),
                "a WeblogPage is served at /<handle>/<slug>, so the displayed URL "
                        + "must not carry the CUSTOM-template 'page/' segment");
    }

    @Test
    void theFrontDoorPageSaysItIsServedAtTheRootNotAtItsSlug() throws IOException {
        String jsp = read(PAGE_EDIT);
        assertTrue(jsp.contains("<c:if test=\"${frontDoorPage}\">"),
                "the front-door page gets a note, keyed on the controller's flag");
        assertTrue(jsp.contains("pageEdit.frontDoorNote")
                        && jsp.contains("fn:escapeXml(urls.staticPage(actionWeblog, bean.slug))"),
                "the note names the page's real URL (the root), escaped");
    }

    @Test
    void theSiteSpaceIsNamedBySiteNotByItsInternalHandle() throws IOException {
        for (Path jsp : new Path[] {PAGE_EDIT,
                Paths.get("src/main/webapp/WEB-INF/jsps/editor/Pages.jsp")}) {
            String markup = read(jsp);
            assertTrue(markup.contains("actionWeblog.siteSpace") && markup.contains("weblogPagesForm.subtitle.site"),
                    jsp + ": the subtitle must not show the internal handle site-space");
        }
    }

    @Test
    void theRailCarriesPublishNavigationAndTheQuietDeleteLink() throws IOException {
        String jsp = read(PAGE_EDIT);
        assertTrue(jsp.contains("id=\"pageSaveButton\""),
                "the rail's Publish box owns the Save button");
        assertTrue(jsp.contains("id=\"page_bean_status\"") && jsp.contains("id=\"page_bean_navOrder\""),
                "status and nav order keep their ids -- RedirectIT, ContactFormIT and "
                        + "VirtualHostIT all drive them by id");
        assertTrue(jsp.contains("class=\"delete-link\"") && jsp.contains("id=\"deletePageButton\""),
                "delete is a quiet text link in the rail, not a red button");
    }

    /**
     * The editor and the list screen must hand an author the SAME URL for a
     * page, and it must be the one a {@code WeblogPage} is actually served at:
     * {@code /<handle>/<slug>}, a bare single segment.
     *
     * <p>Task A9 fixed the editor's own permalink line, which had been showing
     * {@code /<handle>/page/<slug>} -- the CUSTOM-<em>template</em> route, which
     * for a page 404s -- and recorded the list screen as still wrong. Task B11
     * then fixed a genuine but different bug on that same line (the
     * author-controlled slug was unescaped in the href) without changing the
     * URL's shape, so both defects were live at once and the fix for the second
     * made the first look attended to.
     *
     * <p>Asserted as an equality between the two files rather than as two
     * independent needles: what went wrong here is precisely that one screen
     * was corrected and the other was not.
     */
    @Test
    void thePagesListLinksAPageWhereTheEditorSaysItLives() throws IOException {
        String pages = read(PAGES);
        assertFalse(pages.contains("}page/"),
                "a WeblogPage is served at /<handle>/<slug>; the 'page/' segment is the "
                        + "CUSTOM-template route and hands an author a 404 from their own "
                        + "admin screen");
        assertTrue(pages.contains(
                        "href=\"${fn:escapeXml(urls.staticPage(actionWeblog, p.slug))}\""),
                "the list's permalink must come from the one strategy rule, escaped -- the "
                        + "same construction PageEdit.jsp's own links use");
        assertFalse(pages.contains("weblogAbsolute(actionWeblog)}${"),
                "a page link is never the weblog root glued to a slug: the site space "
                        + "and its front-door page have a different shape");
        assertTrue(read(PAGE_EDIT).contains("${fn:escapeXml(urls.staticPage(actionWeblog, bean.slug))}"),
                "sanity: the editor's own address line must still be built the same way, "
                        + "or this test is comparing one screen against nothing");
    }

    /**
     * Task M1 (task B7's one modal shape, applied to the page editor). The
     * delete confirmation titled itself with an {@code h3} carrying no
     * {@code .modal-title} at all -- so it was neither the caps-label role
     * every other admin modal wears nor a heading anything else on the page
     * relates to -- and its footer put the destructive button first, which is
     * the order Bootstrap renders and therefore the order a reader sees.
     */
    @Test
    void theDeleteConfirmationTakesTheOneModalShape() throws IOException {
        String jsp = read(PAGE_EDIT);
        assertTrue(jsp.contains("<p id=\"delete-page-modal-title\" class=\"modal-title\">"),
                "the delete modal's title must be a <p class=\"modal-title\">");
        int footer = jsp.indexOf("class=\"modal-footer\"");
        assertTrue(footer >= 0, "the delete modal must still have a footer");
        int dismiss = jsp.indexOf("data-bs-dismiss", footer);
        int action = jsp.indexOf("btn-danger", footer);
        assertTrue(dismiss >= 0 && action > dismiss,
                "cancel first, destructive last -- Bootstrap packs a .modal-footer "
                        + "left-to-right in DOM order");
    }

    /**
     * Spring's field-marker convention, and the one name that works. See
     * {@code PageEditControllerTest}'s own binder tests and
     * {@code BaseController#initBeanBinder}: {@code _bean.showInNav} is
     * silently discarded and nav can then be turned on but never off.
     */
    @Test
    void theShowInNavMarkerIsStillNamedShowInNav() throws IOException {
        String jsp = read(PAGE_EDIT);
        assertTrue(jsp.contains("name=\"_showInNav\""),
                "the marker must be named exactly _showInNav");
        assertFalse(jsp.contains("name=\"_bean.showInNav\""),
                "a marker named _bean.showInNav never reaches checkFieldMarkers "
                        + "and the box silently stays checked forever");
    }

    // ---------------------------------------------------------------- escaping

    @Test
    void theTitleAndSlugAreEscapedWhereTheyAreRendered() throws IOException {
        String jsp = read(PAGE_EDIT);
        assertTrue(jsp.contains(">${fn:escapeXml(bean.title)}</textarea>"),
                "a page title is stored RAW (PageBean.copyTo does no escaping), so "
                        + "the edit form is the boundary -- see CLAUDE.md, Themes");
        assertTrue(jsp.contains("value=\"${fn:escapeXml(bean.slug)}\""),
                "the slug is author input too and rides in an attribute value");
    }

    // ------------------------------------------------------- one editor, two screens

    @Test
    void bothEditorsIncludeTheOneEditorSurface() throws IOException {
        assertTrue(Files.isRegularFile(EDITOR_SURFACE),
                "the editor surface must live in one file: " + EDITOR_SURFACE);
        for (Path jsp : new Path[] { ENTRY_EDITOR, PAGE_EDIT }) {
            assertTrue(read(jsp).contains("page=\"/WEB-INF/jsps/editor/EditorSurface.jsp\""),
                    jsp.getFileName() + " must jsp:include the shared editor surface "
                            + "rather than carrying its own copy of the toolbar, mode "
                            + "control, panes, guide and status line");
        }
    }

    @Test
    void bothEditorsIncludeTheOneEditorScript() throws IOException {
        assertTrue(Files.isRegularFile(EDITOR_SCRIPT),
                "the editor's script must live in one file: " + EDITOR_SCRIPT);
        for (Path jsp : new Path[] { ENTRY_EDITOR, PAGE_EDIT }) {
            assertTrue(read(jsp).contains("page=\"/WEB-INF/jsps/editor/EditorScript.jsp\""),
                    jsp.getFileName() + " must jsp:include the shared editor script");
        }
    }

    /**
     * The two screens post different fields ({@code bean.text} /
     * {@code bean.content}), so the surface takes the textarea's name and
     * value through request-scoped attributes.
     *
     * <p>The escaping therefore happens at the {@code c:set}, not at the
     * textarea, and that placement is deliberate: {@code EditorJspEscapingTest}
     * scans for raw {@code ${bean.text}} / {@code ${bean.content}} anywhere in
     * any JSP, so a future screen that hands the surface a raw value fails
     * that ratchet. Escaping a second time inside the include would render
     * {@code &amp;lt;} to the author instead.
     */
    @Test
    void theEditorFieldValueIsEscapedExactlyOnce() throws IOException {
        assertTrue(read(ENTRY_EDITOR).contains(
                        "var=\"editorFieldValue\" scope=\"request\" value=\"${fn:escapeXml(bean.text)}\""),
                "EntryEditor.jsp must hand the surface an escaped bean.text");
        assertTrue(read(PAGE_EDIT).contains(
                        "var=\"editorFieldValue\" scope=\"request\" value=\"${fn:escapeXml(bean.content)}\""),
                "PageEdit.jsp must hand the surface an escaped bean.content");

        String surface = read(EDITOR_SURFACE);
        assertTrue(surface.contains(">${editorFieldValue}</textarea>"),
                "the surface emits the already-escaped value as-is");
        assertFalse(surface.contains("fn:escapeXml(editorFieldValue)"),
                "escaping it again shows the author &amp;lt; where they typed <");
    }

    /**
     * The preview endpoint and the id field differ per screen, so the script
     * reads both off the surface rather than hardcoding the entry editor's.
     */
    @Test
    void theSurfaceCarriesThePreviewEndpointItsScreenPostsTo() throws IOException {
        String surface = read(EDITOR_SURFACE);
        assertTrue(surface.contains("data-preview-url=") && surface.contains("data-id-field="),
                "EditorSurface.jsp must publish the preview endpoint and the id "
                        + "field name for EditorScript.jsp to read");
        assertTrue(read(EDITOR_SCRIPT).contains("dataset.previewUrl"),
                "EditorScript.jsp must read the endpoint off the surface rather "
                        + "than naming entryEdit!preview.rol -- the page editor posts "
                        + "to pageEdit!preview.rol");

        assertTrue(read(PAGE_EDIT).contains("pageEdit!preview.rol"),
                "PageEdit.jsp must point the surface at the page preview endpoint");
        assertTrue(read(ENTRY_EDITOR).contains("entryEdit!preview.rol"),
                "EntryEditor.jsp must point the surface at the entry preview endpoint");
    }

    /**
     * The shared guide's Ctrl+Enter row means "Post" on the entry editor and
     * "Save" on the page editor (the page editor has no separate publish
     * action). Each host hands the surface the message key for its own
     * primary action, the same way it hands over the preview endpoint and id
     * field above -- see {@code EntryEditorJspGuideTest
     * .theCtrlEnterRowReadsTheHostSuppliedPrimaryActionKey} for the surface
     * side.
     */
    @Test
    void bothScreensSetTheirOwnPrimaryActionKeyForTheGuide() throws IOException {
        assertTrue(read(ENTRY_EDITOR).contains(
                        "var=\"editorPrimaryActionKey\" scope=\"request\" value=\"weblogEdit.post\""),
                "EntryEditor.jsp must tell the shared guide its primary action is Post");
        assertTrue(read(PAGE_EDIT).contains(
                        "var=\"editorPrimaryActionKey\" scope=\"request\" value=\"generic.save\""),
                "PageEdit.jsp must tell the shared guide its primary action is Save, "
                        + "not the entry editor's Post");
    }

    /**
     * A resolved upload replaces its own placeholder by document range, not by
     * rewriting the whole buffer: {@code rollerSetEntryText} moves the caret to
     * the end, so a second file dropped in one go took the author's cursor with
     * it.
     */
    @Test
    void aResolvedUploadEditsItsOwnRangeRatherThanTheWholeDocument() throws IOException {
        String script = read(EDITOR_SCRIPT);
        assertTrue(script.contains("rollerReplaceInEditor"),
                "the upload must resolve its placeholder through a targeted range edit");
        assertTrue(script.contains("view.dispatch("),
                "a targeted edit is a CodeMirror transaction, not a setValue");
    }
}
