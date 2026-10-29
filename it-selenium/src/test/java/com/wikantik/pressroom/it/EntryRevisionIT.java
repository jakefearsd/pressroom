/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.it;

import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.CollectionCondition.sizeGreaterThanOrEqual;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entry revisions end to end: edit, compare, restore.
 *
 * <p>The business tests prove what is recorded and pruned. This proves the
 * author can actually reach it -- the card appears only once there is
 * something in it, the diff renders through the modal, and restore puts the
 * old text back into the editor rather than merely claiming to.
 */
class EntryRevisionIT extends PressroomIT {

    private static final String ENTRY_ADD = "/pressroom-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void anEditLeavesARevisionThatCanBeComparedAndRestored() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String firstBody = "The original paragraph " + suffix;
        String secondBody = "The rewritten paragraph " + suffix;

        String entryId = saveDraft("IT Revisions " + suffix, firstBody);

        // No revisions yet: the first save displaced nothing.
        assertTrue($$("#entryRevisionsCard").isEmpty(),
                "a freshly created entry must not offer revisions of itself");

        editBodyTo(secondBody);

        // --- the card is there, with the one revision ------------------------
        $("#entryRevisionsCard").should(visible);
        $$("#entryRevisionsTable tr").shouldHave(sizeGreaterThanOrEqual(1));

        // --- compare shows what changed --------------------------------------
        $(".revision-diff-button").click();
        $("#revision-diff-modal").shouldBe(visible);
        $("#revisionDiffBody").shouldHave(text(firstBody));
        $("#revisionDiffBody").shouldHave(text(secondBody));
        // --- restore puts the original text back ------------------------------
        // Reload rather than closing the modal and clicking underneath it.
        // Dismissing a Bootstrap modal is an animation, and waiting for an
        // animation is how a browser test earns an intermittent failure on a
        // loaded runner; a fresh page has no modal to get out of the way.
        openEditorFor(entryId);
        // Not a wait on #entry: the editor the click came from has one too, and
        // reading it would report the text the restore is meant to replace.
        clickAndAwaitNewPage($(".revision-restore-button"));
        $("#entry").should(exist);
        String restored = Editor.getText();
        assertTrue(restored != null && restored.contains(firstBody),
                "restore did not bring the original text back; the editor holds: " + restored);
    }

    /** Saves a draft through the editor and returns its id. */
    private String saveDraft(String title, String body) {
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText(body);
        $("button[formaction$='entryAdd!saveDraft.rol']").click();

        $("input[name='bean.id']").should(exist);
        String id = $("input[name='bean.id']").getValue();
        assertNotNull(id, "the editor must expose the saved entry's id");
        return id;
    }

    /** Opens the editor on a saved entry, with no modal in the way. */
    private void openEditorFor(String entryId) {
        openPath("/pressroom-ui/authoring/entryEdit.rol?weblog=" + WEBLOG_HANDLE
                + "&bean.id=" + entryId);
        $("#entry").should(exist);
    }

    /** Rewrites the body of the entry currently open in the editor and saves. */
    private void editBodyTo(String body) {
        Editor.setText(body);
        clickAndAwaitNewPage($("button[formaction$='entryEdit!saveDraft.rol']"));
        $("#entry").should(exist);
    }
}
