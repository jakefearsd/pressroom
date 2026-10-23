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
        assertTrue(entryEditor.contains("<button type=\"button\" class=\"editor-drawer collapsed\""),
                "the summary drawer's toggle is a button, not an href=\"#\" anchor");
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
     * The entry and page titles are set in IBM Plex Serif, which the admin
     * tokens never loaded, so it fell back to Georgia. The editor's own
     * stylesheet (loaded only on the two editor screens) loads the one weight
     * the title uses; WebjarReferenceTest checks the url resolves.
     */
    @Test
    void theEditorTitleFaceIsLoaded() throws IOException {
        String css = read(Paths.get("src/main/webapp/roller-ui/styles/roller-editor.css"));
        int face = css.indexOf("font-family: \"IBM Plex Serif\"");
        assertTrue(face >= 0 && css.lastIndexOf("@font-face", face) >= 0, "no @font-face for IBM Plex Serif");
        assertTrue(css.contains("IBMPlexSerif-SemiBold.woff2"), "the title is weight 600");
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
