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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the couplings between the editors and {@code roller-draft.js}.
 *
 * <p>None of these can fail at compile time and none of them fail loudly in a
 * browser either: a missing {@code <script>} tag, a bar whose class the module
 * cannot find, or a storage key that forgot the weblog handle all produce a
 * page that looks completely normal and silently never recovers anything. That
 * is the same class of defect as the {@code _showInNav} field-marker name (see
 * {@code PageEditControllerTest}), and it gets the same treatment: read the
 * JSP and assert on it.
 */
class EditorAutosaveWiringTest {

    private static final Path ENTRY_EDIT =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/EntryEdit.jsp");
    private static final Path ENTRY_EDITOR =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/EntryEditor.jsp");
    private static final Path PAGE_EDIT =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/PageEdit.jsp");

    private static String read(Path jsp) throws IOException {
        return Files.readString(jsp, StandardCharsets.UTF_8);
    }

    @Test
    void theEntryEditorLoadsTheDraftScript() throws IOException {
        assertTrue(read(ENTRY_EDIT).contains("/theme/scripts/roller-draft.js"),
                "EntryEdit.jsp must load roller-draft.js; without it the editor "
                        + "looks entirely normal and never recovers anything");
    }

    @Test
    void theEntryEditorRendersTheRecoveryBarTheModuleLooksFor() throws IOException {
        String jsp = read(ENTRY_EDIT);
        assertTrue(jsp.contains("id=\"draftRecoveryBar\""),
                "EntryEdit.jsp must render the recovery bar");
        for (String required : new String[] {
                "draft-bar-text", "draft-bar-restore", "draft-bar-discard",
                "data-template=", "data-restored=" }) {
            assertTrue(jsp.contains(required),
                    "roller-draft.js querySelector()s '" + required
                            + "' and silently declines to offer anything without it");
        }
    }

    @Test
    void theRecoveryBarsButtonsCannotSubmitTheForm() throws IOException {
        // The bar sits inside #entry, where a <button> with no type defaults
        // to submit -- clicking Discard would post the entry.
        String bar = read(ENTRY_EDIT);
        int start = bar.indexOf("id=\"draftRecoveryBar\"");
        assertTrue(start > 0, "EntryEdit.jsp must render the recovery bar");
        String markup = bar.substring(start, bar.indexOf("</div>", start));
        assertTrue(markup.contains("type=\"button\" class=\"draft-bar-restore\""),
                "Restore must be type=\"button\"; inside #entry it would submit otherwise");
        assertTrue(markup.contains("type=\"button\" class=\"draft-bar-discard\""),
                "Discard must be type=\"button\"; inside #entry it would submit otherwise");
    }

    @Test
    void theEntryEditorsDraftKeyIsScopedToTheWeblogTheActionAndTheEntry() throws IOException {
        String jsp = read(ENTRY_EDIT);
        assertTrue(jsp.contains("roller.draft.v1:"),
                "the storage key must carry the versioned prefix roller-draft.js sweeps on");
        assertTrue(jsp.contains("${pageContext.request.contextPath}")
                        && jsp.contains("${actionWeblog.handle}")
                        && jsp.contains("${actionName}"),
                "a key missing the context path, handle or action lets two editors "
                        + "share one snapshot and hand an author someone else's text");
    }

    @Test
    void theDraftKeysAreSetInRequestScopeSoTheIncludedEditorCanSeeThem() throws IOException {
        // EntryEditor.jsp arrives via jsp:include and reads ${draftKey}. A
        // page-scoped <c:set> resolves to the empty string there, which
        // produces a valid-looking key that every editor in the install shares.
        String jsp = read(ENTRY_EDIT);
        assertTrue(jsp.contains("var=\"draftKey\" scope=\"request\""),
                "draftKey must be set in request scope");
        assertTrue(jsp.contains("var=\"draftNewKey\" scope=\"request\""),
                "draftNewKey must be set in request scope");
    }

    @Test
    void theEntryEditorInstallsTheModuleWithBothEditorSeams() throws IOException {
        String jsp = read(ENTRY_EDITOR);
        assertTrue(jsp.contains("rollerDraft.install("),
                "EntryEditor.jsp must install the module");
        assertTrue(jsp.contains("getText: rollerGetEntryText"),
                "the module reads the editor through rollerGetEntryText");
        assertTrue(jsp.contains("setText: rollerSetEntryText"),
                "Restore writes back through rollerSetEntryText");
    }

    @Test
    void theLeaveWarningIsBoundOnceRatherThanPerKeystroke() throws IOException {
        // The original registered $(window).on("beforeunload", ...) and
        // $("#entry").on('submit', ...) INSIDE the editor's change callback,
        // so a thousand-word entry left a thousand submit handlers on the form
        // it was about to post.
        String jsp = codeOnly(read(ENTRY_EDITOR));
        assertTrue(jsp.contains("beforeunload"), "the leave-warning must still exist");

        String body = firstEditorChangeCallbackBody(jsp);
        assertFalse(body.contains("beforeunload"),
                "beforeunload must be bound once, outside the change callback -- "
                        + "the callback body is:\n" + body);
        assertFalse(body.contains("'submit'"),
                "a submit handler bound inside the change callback accumulates one "
                        + "per keystroke on the form about to be posted -- "
                        + "the callback body is:\n" + body);
    }

    @Test
    void thePageEditorIsWiredTheSameWay() throws IOException {
        String jsp = read(PAGE_EDIT);
        assertTrue(jsp.contains("/theme/scripts/roller-draft.js"),
                "PageEdit.jsp must load roller-draft.js");
        assertTrue(jsp.contains("id=\"draftRecoveryBar\""),
                "PageEdit.jsp must render the recovery bar");
        assertTrue(jsp.contains("rollerDraft.install("),
                "PageEdit.jsp must install the module");
        assertTrue(jsp.contains("getText: rollerGetEntryText")
                        && jsp.contains("setText: rollerSetEntryText"),
                "PageEdit.jsp has its own copy of the editor seam and must pass both halves");

        // The bar's internals are hand-duplicated from EntryEdit.jsp rather
        // than shared, so they need the same assertions the entry side gets.
        // A typo in any one of these makes offer() return early: no bar, no
        // error, no failing test without this.
        for (String required : new String[] {
                "draft-bar-text", "draft-bar-restore", "draft-bar-discard",
                "data-template=", "data-restored=" }) {
            assertTrue(jsp.contains(required),
                    "roller-draft.js querySelector()s '" + required
                            + "' and silently declines to offer anything without it");
        }
    }

    @Test
    void thePageEditorsDraftKeyIsScopedToTheWeblogAndThePage() throws IOException {
        String jsp = read(PAGE_EDIT);
        assertTrue(jsp.contains("roller.draft.v1:"),
                "the storage key must carry the versioned prefix roller-draft.js sweeps on");
        assertTrue(jsp.contains("${actionWeblog.handle}"),
                "a key without the handle lets two weblogs' page editors share a snapshot");
        assertTrue(jsp.contains(":pageEdit:"),
                "the page editor's key must not collide with the entry editor's");
        assertTrue(jsp.contains("${pageContext.request.contextPath}"),
                "a key without the context path lets two Roller installs on one "
                        + "origin share storage");
        assertTrue(jsp.contains("${empty bean.id ? 'new' : bean.id}"),
                "one action serves both add and edit here, so the id is the ONLY "
                        + "thing separating one page's draft from another's -- without "
                        + "the ternary every page in the weblog shares a single slot");
    }

    @Test
    void thePageEditorsLeaveWarningIsAlsoBoundOnce() throws IOException {
        // PageEdit.jsp carried its own copy of the per-keystroke handler leak
        // and got its own copy of the fix. Without this, a future edit
        // reverting the page editor alone would pass every other test here.
        String jsp = codeOnly(read(PAGE_EDIT));
        assertTrue(jsp.contains("beforeunload"), "the leave-warning must still exist");

        String body = firstEditorChangeCallbackBody(jsp);
        assertFalse(body.contains("beforeunload"),
                "beforeunload must be bound once, outside the change callback -- "
                        + "the callback body is:\n" + body);
        assertFalse(body.contains("'submit'"),
                "a submit handler bound inside the change callback accumulates one "
                        + "per keystroke on the form about to be posted -- "
                        + "the callback body is:\n" + body);
    }

    @Test
    void bothEditorsUnbindTheLeaveWarningByNamespace() throws IOException {
        // An un-namespaced unbind removes EVERY beforeunload handler on the
        // page, so a later feature that binds one would be silently disarmed
        // on submit.
        for (Path jsp : new Path[] { ENTRY_EDITOR, PAGE_EDIT }) {
            String code = codeOnly(read(jsp));
            assertTrue(code.contains("beforeunload.rollerLeaveWarning"),
                    jsp + " must namespace its leave-warning binding");
            assertFalse(code.contains("off(\"beforeunload\")"),
                    jsp + " must not unbind beforeunload wholesale");
        }
    }

    @Test
    void theStorageKeyPrefixAgreesBetweenTheModuleAndBothEditors() throws IOException {
        // The JSPs build the key; the module's own PREFIX is used only by
        // sweep() and the storage probe. Bump one without the other and
        // nothing breaks except housekeeping, which silently stops reaping
        // the keys the JSPs keep writing -- forever, in every author's
        // browser.
        String module = read(Paths.get("src/main/webapp/theme/scripts/roller-draft.js"));
        Matcher prefix = Pattern.compile("var PREFIX = '([^']+)'").matcher(module);
        assertTrue(prefix.find(), "roller-draft.js must declare a PREFIX");

        for (Path jsp : new Path[] { ENTRY_EDIT, PAGE_EDIT }) {
            assertTrue(read(jsp).contains(prefix.group(1)),
                    jsp + " builds a storage key that does not start with the module's "
                            + "own PREFIX (" + prefix.group(1) + "), so sweep() will never "
                            + "reap what this page writes");
        }
    }

    @Test
    void eachEditorExcludesItsOwnTextareaFromTheFieldSweep() throws IOException {
        // The editor's content arrives through getText/setText and is stored
        // once as snapshot.text. Capturing the textarea again doubles every
        // snapshot and gives differs() a second, possibly-stale copy of the
        // same content to compare independently of the first. The two editors
        // name that textarea differently, which is exactly why the module
        // cannot hardcode it.
        assertTrue(read(ENTRY_EDITOR).contains("'bean.text'"),
                "EntryEditor.jsp must exclude its own bean.text textarea");
        assertTrue(read(PAGE_EDIT).contains("'bean.content'"),
                "PageEdit.jsp's textarea is bean.content, not bean.text");
    }

    @Test
    void onlyTheEntryEditorExcludesStatusFromTheComparison() throws IOException {
        // Entries: doEntryEditSave mutates the bean and FORWARDS, so the page
        // rendered right after a successful Post carries PUBLISHED while the
        // snapshot taken at submit holds DRAFT. Comparing it raises a phantom
        // "unsaved changes" bar over a save that just succeeded -- and keeps
        // raising it on every later visit, eventually offering days-old text
        // over something newer.
        assertTrue(codeOnly(read(ENTRY_EDITOR)).contains("'bean.status'"),
                "EntryEditor.jsp must exclude bean.status from the snapshot");

        // Pages: it is a visible <select> the author sets, and PageBean.copyTo
        // writes it straight through. Excluding it would drop a real choice.
        assertFalse(codeOnly(read(PAGE_EDIT)).contains("'bean.status'"),
                "PageEdit.jsp must NOT exclude bean.status -- it is author input there");
    }

    @Test
    void theRecoveryBarIsAnnouncedToScreenReaders() throws IOException {
        for (Path jsp : new Path[] { ENTRY_EDIT, PAGE_EDIT }) {
            String jspText = read(jsp);
            int start = jspText.indexOf("id=\"draftRecoveryBar\"");
            assertTrue(start > 0, jsp + " must render the recovery bar");
            String bar = jspText.substring(start, jspText.indexOf('>', start));
            assertTrue(bar.contains("aria-live"),
                    jsp + "'s recovery bar appears without warning; it needs aria-live "
                            + "so it is not silent for a screen-reader user");
        }
    }

    /**
     * The JSP with its comments removed.
     *
     * <p>Every assertion in this class searches JSP source for a string, and
     * the first version of the leave-warning test taught the lesson twice:
     * a rule that matches raw source is really a rule about the file's
     * <em>prose</em>, and the author ends up rewording an English sentence to
     * satisfy a JavaScript assertion. The second time it happened, the comment
     * explaining why an unbind must be namespaced was itself what failed the
     * namespacing check. Strip the commentary; assert on the code.
     */
    private static String codeOnly(String jsp) {
        return jsp.replaceAll("(?s)<%--.*?--%>", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }

    @Test
    void thePageEditorInstallsAgainstItsOwnFormNotTheEntryForm() throws IOException {
        // PageEdit.jsp's form is #pageEditForm. Copying EntryEditor.jsp's
        // install() verbatim would pass document.getElementById('entry'),
        // which is null here -- install() returns silently and the page has
        // autosave that never fires.
        String jsp = read(PAGE_EDIT);
        assertTrue(jsp.contains("form: document.getElementById('pageEditForm')"),
                "PageEdit.jsp must install against #pageEditForm");
    }

    /**
     * The source of the callback that sets the leave-warning's dirty flag.
     *
     * <p>Brace-matched rather than "the text up to the next {@code });}", and
     * asserted on for what the body <em>contains</em> rather than where a
     * keyword first appears in the file. The first version of this test did the
     * latter, and the result was a test that a JSP <em>comment</em> mentioning
     * beforeunload could break — it made prose fail the build while a genuinely
     * misplaced binding a few lines further down would have passed.
     */
    private static String firstEditorChangeCallbackBody(String jsp) {
        // Anchored on the dirty-flag assignment rather than on "the first
        // change registration" in the file. Both JSPs register a SECOND
        // change listener inside install()'s onEditorChange, so an ordinal
        // anchor means simply moving the install() block above the
        // leave-warning block makes these assertions inspect a one-line
        // callback and pass vacuously -- with the per-keystroke leak fully
        // reintroduced.
        //
        // The registration is a push onto the page's own listener array:
        // PressroomEditor.create takes ONE onChange, so the page fans it out.
        // That is what this anchors on now; the editor's own API is
        // deliberately not named here, since swapping the editor again must
        // not mean rewriting this test.
        int flag = jsp.indexOf("Dirty = true;");
        assertTrue(flag > 0, "the leave-warning must still track a dirty flag");
        int start = jsp.lastIndexOf("rollerEditorChangeListeners.push(", flag);
        assertTrue(start > 0, "the dirty flag must be set from an editor change listener");
        int open = jsp.indexOf('{', jsp.indexOf("function", start));
        assertTrue(open > 0, "the change handler must have a function body");

        int depth = 0;
        for (int i = open; i < jsp.length(); i++) {
            char c = jsp.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return jsp.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError("unbalanced braces in the change handler");
    }
}
