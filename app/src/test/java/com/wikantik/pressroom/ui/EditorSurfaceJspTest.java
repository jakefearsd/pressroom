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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scan for the shared writing surface's fit-and-finish pass: the
 * shortcuts it advertises, the text it inserts, what the image pickers do with
 * a file they cannot use, and the names assistive technology reads.
 *
 * <p>The key bindings themselves are {@code app/frontend/test/keys.test.mjs};
 * this pins the host half -- that the page actually hands the editor the
 * commands those bindings call.
 */
class EditorSurfaceJspTest {

    private static final Path SURFACE =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/EditorSurface.jsp");
    private static final Path SCRIPT =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/EditorScript.jsp");
    private static final Path ENTRY_EDITOR =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/EntryEditor.jsp");

    private static String read(Path jsp) throws IOException {
        return Files.readString(jsp, StandardCharsets.UTF_8);
    }

    private static String guide() throws IOException {
        String jsp = read(SURFACE);
        int at = jsp.indexOf("id=\"editorGuide\"");
        assertTrue(at > 0, "the guide must exist");
        return jsp.substring(at);
    }

    private static String function(String js, String name) {
        int at = js.indexOf("function " + name + "(");
        assertTrue(at >= 0, name + " must exist");
        int end = js.indexOf("\n    }\n", at);
        return js.substring(at, end);
    }

    @Test
    void theAdvertisedFormattingShortcutsReachTheEditor() throws IOException {
        String js = read(SCRIPT);
        assertTrue(js.contains("onBold: commands.bold"), "Ctrl+B must run the toolbar's bold");
        assertTrue(js.contains("onItalic: commands.italic"), "Ctrl+I must run the toolbar's italic");
        assertTrue(js.contains("onLink: commands.link"), "Ctrl+K must run the toolbar's link");
    }

    @Test
    void theGuideListsTheLinkShortcutAndHowToLeaveTheEditor() throws IOException {
        String guide = guide();
        assertTrue(guide.contains("<kbd>K</kbd>"), "the guide must list Ctrl+K");
        assertTrue(guide.contains("editor.guide.leaveEditor"),
                "Tab indents inside the editor, so the guide must say Esc then Tab leaves it");
    }

    /**
     * The modifier is shown as Ctrl and swapped for the Command key on a Mac,
     * where CodeMirror's "Mod" binds Cmd.
     */
    @Test
    void theModifierKeyIsMarkedSoAMacShowsCommand() throws IOException {
        String guide = guide();
        assertFalse(guide.contains("<kbd>Ctrl</kbd>"),
                "every Ctrl in the guide carries the editor-mod class the Mac swap reads");
        assertTrue(guide.contains("<kbd class=\"editor-mod\">Ctrl</kbd>"));
        assertTrue(read(SCRIPT).contains(".editor-mod"), "the script must swap the label on a Mac");
    }

    @Test
    void theGuideShowsTheHeadingTheToolbarInsertsAndTranslatesItsExamples() throws IOException {
        String guide = guide();
        assertTrue(guide.contains("<code>## <spring:message code=\"editor.heading\"/></code>"),
                "the toolbar's heading button inserts ##, so the guide shows ##");
        for (String literal : new String[] {"<code>- item</code>", "<code>1. item</code>",
                "<code>&gt; quote</code>"}) {
            assertFalse(guide.contains(literal), "untranslated guide example " + literal);
        }
        String js = read(SCRIPT);
        assertFalse(js.contains("'`', '`', 'code'"), "the code fallback must come from the bundle");
        assertFalse(js.contains("'[uploading '"), "the upload placeholder must come from the bundle");
    }

    /**
     * Entries are Markdown. A non-image file used to go in as a raw
     * {@code <a href>} with its name and URL unescaped, so a quote in the
     * name broke the markup.
     */
    @Test
    void aChosenNonImageFileIsInsertedAsAMarkdownLink() throws IOException {
        String select = function(read(SCRIPT), "onSelectMediaFile");
        assertFalse(select.contains("<a href="), "no raw HTML link into a Markdown editor");
        assertFalse(select.contains("?t=true"), "the id-less <img> fallback is dead: the chooser always passes an id");
        assertTrue(select.contains("rollerMarkdownLink("), select);
    }

    /**
     * The featured/social pickers take an image. Choosing anything else used
     * to close the chooser and silently do nothing.
     */
    @Test
    void aPickerRefusesANonImageAndStaysOpen() throws IOException {
        assertTrue(read(SURFACE).contains("id=\"mediaChooserRefusal\""));
        String select = function(read(SCRIPT), "onSelectMediaFile");
        int refuse = select.indexOf("mediaChooserRefusal");
        int close = select.indexOf("closeMediaFileLightbox()");
        assertTrue(refuse >= 0 && close > refuse,
                "a picker's non-image refusal must happen before, and instead of, closing: " + select);
    }

    /**
     * The refusal above is the backstop; the chooser a picker opens lists
     * images only in the first place. Insert-media still lists every file,
     * since a non-image goes into the text as a link.
     */
    @Test
    void aPickerOpensTheChooserOnImagesOnly() throws IOException {
        String open = function(read(SCRIPT), "onClickMediaFileInsert");
        assertTrue(open.contains("if (pickerTarget) {") && open.contains("imagesOnly=true"),
                "only a picker asks the chooser for images only: " + open);
    }

    /**
     * An upload refused as a whole (uploads off, no files) answers with a
     * top-level detail and no results; the editor showed its generic
     * "upload failed" instead of saying why.
     */
    @Test
    void aWholeUploadRefusalShowsTheServersDetail() throws IOException {
        String upload = function(read(SCRIPT), "rollerUploadImages");
        assertTrue(upload.contains("(res.body && res.body.detail)"),
                "a refusal with no results must show the body's own detail: " + upload);
    }

    /**
     * A long title was cut off mid-word in a single-line input, on both
     * editors. It is a one-row textarea that wraps and grows; the script
     * keeps it one logical line (Enter moves on, a pasted break is a space).
     */
    @Test
    void aLongTitleWrapsInsteadOfBeingCutOff() throws IOException {
        for (Path screen : new Path[]{
                Paths.get("src/main/webapp/WEB-INF/jsps/editor/EntryEdit.jsp"),
                Paths.get("src/main/webapp/WEB-INF/jsps/editor/PageEdit.jsp")}) {
            String jsp = read(screen);
            int at = jsp.indexOf("class=\"editor-title\"");
            assertTrue(at > 0, screen + ": the title field must exist");
            String tag = jsp.substring(jsp.lastIndexOf('<', at), jsp.indexOf('>', at) + 1);
            assertTrue(tag.startsWith("<textarea") && tag.contains("name=\"bean.title\"")
                            && tag.contains("rows=\"1\""),
                    screen + ": the title is a one-row textarea: " + tag);
            assertTrue(jsp.contains("rows=\"1\"") && jsp.contains(">${fn:escapeXml(bean.title)}</textarea>"),
                    screen + ": the title's text is the escaped textarea body, with no stray whitespace");
            assertFalse(jsp.contains("input[name='bean.title']"),
                    screen + ": a script still looks for the title as an input");
        }
        String fit = function(read(SCRIPT), "rollerFitTitle");
        assertTrue(fit.contains("scrollHeight"), "the title grows to its text: " + fit);
        String js = read(SCRIPT);
        assertTrue(js.contains("e.key === 'Enter' && !e.isComposing"),
                "Enter leaves the title (but not mid-IME composition)");
        assertTrue(read(Paths.get("src/main/webapp/pressroom-ui/styles/roller-editor.css"))
                        .contains("textarea.editor-title"),
                "the editor stylesheet takes the textarea's resize handle and scrollbar away");
    }

    @Test
    void theWritingSurfaceAndTheSummaryHaveNames() throws IOException {
        String surface = read(SURFACE);
        int textarea = surface.indexOf("id=\"edit_content\"");
        assertTrue(textarea >= 0, "the editor's textarea must exist");
        String tag = surface.substring(surface.lastIndexOf("<textarea", textarea), surface.indexOf("</textarea>", textarea));
        assertTrue(tag.contains("aria-label=\"<spring:message code='editor.textLabel'/>\""),
                "the editor's textarea carries the name editor.js hands the contenteditable: " + tag);
        String entryEditor = read(ENTRY_EDITOR);
        assertTrue(entryEditor.contains("id=\"edit_summary\"")
                        && entryEditor.contains("aria-label=\"<spring:message code='weblogEdit.summary'/>\""),
                "the summary textarea has no visible label of its own");
        assertTrue(entryEditor.contains("<button type=\"button\" class=\"editor-drawer${empty bean.summary ? ' collapsed' : ''}\""),
                "the summary drawer's toggle is a button, not an href=\"#\" anchor");
    }

    /**
     * The summary is the line a list of entries shows under the title, and
     * the drawer says so: a tip tied to the field with aria-describedby,
     * naming the fallback. And an entry that has a summary opens with the
     * drawer open, so a written summary is never out of sight.
     */
    @Test
    void theSummaryDrawerSaysWhatItIsForAndOpensWhenThereIsOne() throws IOException {
        String entryEditor = read(ENTRY_EDITOR);
        int textarea = entryEditor.indexOf("id=\"edit_summary\"");
        String tag = entryEditor.substring(entryEditor.lastIndexOf("<textarea", textarea),
                entryEditor.indexOf(">", textarea));
        assertTrue(tag.contains("aria-describedby=\"edit_summary_tip\""), "the tip names the field: " + tag);
        assertTrue(tag.contains("rows=\"3\""), "a sentence or two, not a second editor: " + tag);
        assertTrue(entryEditor.contains("<div id=\"edit_summary_tip\" class=\"form-text\">"
                + "<spring:message code=\"weblogEdit.summary.tip\"/></div>"), entryEditor);
        assertTrue(entryEditor.contains("<div id=\"collapseSummaryEditor\" class=\"collapse${empty bean.summary ? '' : ' show'}\">"),
                "the drawer opens on an entry that has a summary");
        assertTrue(entryEditor.contains("aria-expanded=\"${empty bean.summary ? 'false' : 'true'}\""),
                "and its toggle says so");
    }

    /**
     * Upload refusals go to a live region of their own; the word count beside
     * them changes on every keystroke and must not be announced each time.
     */
    @Test
    void uploadRefusalsAreAnnounced() throws IOException {
        assertTrue(read(SURFACE).contains("<span class=\"editor-status-alerts\" id=\"editorStatusAlerts\" role=\"status\">"));
        assertTrue(function(read(SCRIPT), "rollerStatusError").contains("editorStatusAlerts"));
    }

    /**
     * The entry and page titles are set in the UI face (roller.css's
     * .editor-title: Plex Sans at 600 -- emphasis is weight, never size).
     * They were Plex Serif once, and the editor stylesheet still loaded a
     * serif face nothing used any more.
     */
    @Test
    void theEditorTitleIsInTheUiFaceAndNoUnusedFaceShips() throws IOException {
        String editorCss = read(Paths.get("src/main/webapp/pressroom-ui/styles/roller-editor.css"));
        String pressroomCss = read(Paths.get("src/main/webapp/pressroom-ui/styles/roller.css"));
        int title = pressroomCss.indexOf(".editor-title {");
        assertTrue(title >= 0 && pressroomCss.substring(title, pressroomCss.indexOf('}', title))
                .contains("font-family: var(--font-ui);"), "the editor title is not in the UI face");
        assertFalse(editorCss.contains("IBM Plex Serif"), "roller-editor.css loads a face no rule uses");
    }

    /**
     * A new entry has never been saved; the status line said "Saved" while
     * the rail's pill said "Not saved".
     */
    @Test
    void aNeverSavedEntryDoesNotClaimToBeSaved() throws IOException {
        String surface = read(SURFACE);
        assertTrue(surface.contains("<c:when test=\"${editorNotYetSaved}\"><spring:message code=\"weblogEdit.unsaved\"/></c:when>"),
                "the initial save state must honour editorNotYetSaved");
        assertTrue(read(ENTRY_EDITOR).contains(
                        "<c:set var=\"editorNotYetSaved\" scope=\"request\" value=\"${empty bean.id}\"/>"),
                "the entry editor must say when its entry has no row yet");
    }

    /** The guide's two-key row keeps its space under JSP whitespace trimming. */
    @Test
    void theEscThenTabRowKeepsItsSpace() throws IOException {
        assertTrue(guide().contains("<kbd>Esc</kbd>&#32;<kbd>Tab</kbd>"));
    }
}
