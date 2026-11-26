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

import com.wikantik.pressroom.it.support.AdminApp;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.CollectionCondition.sizeGreaterThanOrEqual;
import static com.codeborne.selenide.Condition.disappear;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
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

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void anEditLeavesARevisionThatCanBeComparedAndRestored() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String firstBody = "The original paragraph " + suffix;
        String secondBody = "The rewritten paragraph " + suffix;

        saveDraft("IT Revisions " + suffix, firstBody);

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
        // Close the comparison first: its backdrop would take the click meant for the card.
        $("#revision-diff-modal .btn-close").click();
        $("#revision-diff-modal").should(disappear);
        $(".revision-restore-button").click();
        AdminApp.awaitSavedBanner();
        com.codeborne.selenide.Selenide.Wait().withTimeout(java.time.Duration.ofSeconds(10))
                .withMessage("restore did not bring the original text back")
                .until(driver -> {
                    String restored = Editor.getText();
                    return restored != null && restored.contains(firstBody);
                });
    }

    /** Saves a draft through the editor and returns its id. */
    private String saveDraft(String title, String body) {
        openPath(AdminApp.newEntry(WEBLOG_HANDLE));
        AdminApp.write(title, body);
        AdminApp.saveDraftButton().click();
        return AdminApp.savedEntryId();
    }

    /** Rewrites the body of the entry currently open in the editor and saves. */
    private void editBodyTo(String body) {
        Editor.setText(body);
        AdminApp.saveDraftButton().click();
        // The click clears the banner and the save's answer brings it back.
        AdminApp.awaitSavedBanner();
    }
}
