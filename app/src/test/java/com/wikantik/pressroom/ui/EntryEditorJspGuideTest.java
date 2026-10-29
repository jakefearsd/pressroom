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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scan for the in-app writing guide (Task A8).
 *
 * <p>The guide is a Bootstrap offcanvas rather than a modal so it can sit
 * alongside the editor rather than block it; the browser side of "does it
 * actually open and does it list every shortcode the Insert menu offers" is
 * {@code ShortcodeCardIT.theGuideListsEveryRegisteredShortcode} instead --
 * that needs a real browser to click the help button and read the Insert
 * menu's own DOM. What a source scan CAN pin cheaply is that the guide exists
 * at all, that its shortcode table is generated from the registry rather than
 * hand-typed (so a sixth shortcode cannot silently go undocumented), and that
 * the keyboard table still names the three shortcuts the editor actually
 * binds.
 */
class EntryEditorJspGuideTest {

    /**
     * The guide's markup and the function that opens it now live in the two
     * shared editor includes rather than in {@code EntryEditor.jsp}: the page
     * editor runs the same surface, so a guide homed on the entry screen
     * would have been the fifth thing the page editor silently went without.
     * Same assertions, repointed at the file that owns each half.
     */
    private static final Path ENTRY_EDITOR =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/EditorSurface.jsp");
    private static final Path EDITOR_SCRIPT =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/EditorScript.jsp");

    private static String read(Path jsp) throws IOException {
        return Files.readString(jsp, StandardCharsets.UTF_8);
    }

    @Test
    void theGuideIsAnOffcanvasWithTheExpectedId() throws IOException {
        String jsp = read(ENTRY_EDITOR);
        assertTrue(jsp.contains("id=\"editorGuide\""),
                "EditorSurface.jsp must render the guide offcanvas with id=\"editorGuide\"");
        assertTrue(jsp.contains("class=\"offcanvas"),
                "the guide must be a Bootstrap offcanvas, not a modal");
    }

    @Test
    void theShortcodeTableIsGeneratedFromTheRegistryNotHandTyped() throws IOException {
        // The Insert menu ALSO iterates shortcodeCards (earlier in this
        // file), so the assertion has to be scoped to the
        // guide itself -- otherwise this test passes whether or not the guide
        // exists at all, which is exactly the failure a source scan must not
        // have.
        String jsp = read(ENTRY_EDITOR);
        int guideStart = jsp.indexOf("id=\"editorGuide\"");
        assertTrue(guideStart > 0, "the guide must exist");
        String guide = jsp.substring(guideStart);
        assertTrue(guide.contains("c:forEach items=\"${shortcodeCards}\""),
                "the guide's shortcode table must iterate the shortcodeCards model "
                        + "attribute -- a hand-typed table silently stops matching the "
                        + "Insert menu the moment a new shortcode registers");
    }

    @Test
    void theShortcodeTableRowsCarryTheirDataShortcodeAttribute() throws IOException {
        // ShortcodeCardIT compares this attribute's values against the Insert
        // menu's own [data-shortcode] set, so it has to actually be there.
        String jsp = read(ENTRY_EDITOR);
        int guideStart = jsp.indexOf("id=\"editorGuide\"");
        assertTrue(guideStart > 0, "the guide must exist");
        String guide = jsp.substring(guideStart);
        assertTrue(guide.contains("data-shortcode=\"<c:out value='${card.name}'/>\""),
                "each shortcode row must carry data-shortcode so the guide and the "
                        + "Insert menu can be compared for completeness");
    }

    @Test
    void theKeyboardTableNamesTheThreeEditorLevelShortcuts() throws IOException {
        // Mod-S / Mod-Enter / Mod-/ are bound INSIDE the editor (hostKeymap in
        // editor.js), above CodeMirror's own defaultKeymap, precisely because
        // the editor's own keymap otherwise claims Mod-Enter and Mod-/. A guide
        // that forgot to list them would send an author to the wrong keys.
        String jsp = read(ENTRY_EDITOR);
        int guideStart = jsp.indexOf("id=\"editorGuide\"");
        assertTrue(guideStart > 0, "the guide must exist");
        String guide = jsp.substring(guideStart);
        assertTrue(guide.contains("<kbd class=\"editor-mod\">Ctrl</kbd>+<kbd>S</kbd>"),
                "the keyboard table must name Ctrl+S");
        assertTrue(guide.contains("<kbd class=\"editor-mod\">Ctrl</kbd>+<kbd>Enter</kbd>"),
                "the keyboard table must name Ctrl+Enter");
        assertTrue(guide.contains("<kbd class=\"editor-mod\">Ctrl</kbd>+<kbd>/</kbd>"),
                "the keyboard table must name Ctrl+/");
    }

    /**
     * The guide is shared by both editors, but "Ctrl+Enter" does not mean the
     * same thing on both screens: it publishes on the entry editor and saves
     * on the page editor (which has no separate publish action -- the status
     * select decides that). A hardcoded {@code weblogEdit.post} label was
     * therefore wrong on the page editor's guide. Each host sets
     * {@code editorPrimaryActionKey} as a request attribute before including
     * {@code EditorSurface.jsp}; the surface renders whatever key it was
     * given rather than naming one itself. See
     * {@code PageEditJspTest.bothScreensSetTheirOwnPrimaryActionKeyForTheGuide}
     * for the host side of this.
     */
    @Test
    void theCtrlEnterRowReadsTheHostSuppliedPrimaryActionKey() throws IOException {
        String jsp = read(ENTRY_EDITOR);
        int guideStart = jsp.indexOf("id=\"editorGuide\"");
        assertTrue(guideStart > 0, "the guide must exist");
        String guide = jsp.substring(guideStart);
        assertTrue(guide.contains("<kbd class=\"editor-mod\">Ctrl</kbd>+<kbd>Enter</kbd></td>"
                        + "<td><spring:message code=\"${editorPrimaryActionKey}\"/></td>"),
                "the guide's Ctrl+Enter row must render editorPrimaryActionKey -- a "
                        + "hardcoded weblogEdit.post mislabels the page editor's Save");
    }

    @Test
    void theHelpCommandOpensTheGuide() throws IOException {
        String jsp = read(EDITOR_SCRIPT);
        assertTrue(jsp.contains("window.rollerOpenGuide"),
                "commands.help() must call window.rollerOpenGuide(), and something "
                        + "on this page must define it");
        assertTrue(jsp.contains("bootstrap.Offcanvas.getOrCreateInstance("
                        + "document.getElementById('editorGuide')).show()"),
                "rollerOpenGuide must show the editorGuide offcanvas");
    }
}
