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

import static com.codeborne.selenide.Condition.disappear;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Bulk actions on the entry list, in a real browser.
 *
 * <p>These are the parts the controller tests cannot reach: the selection
 * actually reaches the server (the checkboxes are inside the form that carries
 * the CSRF token), the right endpoint is chosen by which button was pressed
 * rather than by the form's declared action, and the delete confirmation is a
 * modal that does not block the page the way {@code window.confirm} would.
 */
class BulkEntryActionsIT extends PressroomIT {

    private static final String ENTRY_ADD = "/roller-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String ENTRIES = "/roller-ui/authoring/entries.rol?weblog=" + WEBLOG_HANDLE;

    private static final String PERMALINK = "#entry_bean_permalink";

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void publishingASelectedDraftMakesItPublic() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String draftId = saveDraft("IT Bulk publish " + suffix, "Body " + suffix);

        openPath(ENTRIES);
        select(draftId);
        // The bar only shows once a row is checked -- select() above is what
        // makes it visible.
        $(".selection-bar[data-selection-bar='entriesBulkForm']").shouldBe(visible);
        $(".selection-bar button[formaction$='entries!bulkPublish.rol']").click();

        // The list is the write's own report: a published entry loses the
        // draft row styling and gains a link to its permalink.
        $("#messages").should(exist);
        openEditorFor(draftId);
        $(PERMALINK).should(exist);
    }

    @Test
    void deletingASelectedEntryAsksFirstAndThenRemovesIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String draftId = saveDraft("IT Bulk delete " + suffix, "Body " + suffix);

        openPath(ENTRIES);
        select(draftId);
        $("#bulkDeleteButton").click();

        // A modal, not a native dialog: the page stays responsive.
        $("#bulk-delete-modal").shouldBe(visible);
        $("#bulkDeleteConfirm").click();

        $("#messages").should(exist);
        // The row is gone, which is the only evidence that matters.
        $("input[name='selectedEntries'][value='" + draftId + "']").should(disappear);
    }

    /** Saves a draft through the editor and returns its id. */
    private String saveDraft(String title, String body) {
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText(body);
        $("button[formaction$='entryAdd!saveDraft.rol']").click();

        // Wait on the save landing rather than on the page moving: the id
        // field is only populated once the entry exists.
        $("input[name='bean.id']").should(exist);
        String id = $("input[name='bean.id']").getValue();
        assertNotNull(id, "the editor must expose the saved entry's id");
        return id;
    }

    private void select(String entryId) {
        $("input[name='selectedEntries'][value='" + entryId + "']").should(exist).click();
    }

    private void openEditorFor(String entryId) {
        openPath("/roller-ui/authoring/entryEdit.rol?weblog=" + WEBLOG_HANDLE + "&bean.id=" + entryId);
        $("#entry").should(exist);
    }
}
