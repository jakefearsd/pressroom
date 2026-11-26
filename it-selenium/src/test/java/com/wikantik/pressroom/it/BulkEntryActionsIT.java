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

import com.codeborne.selenide.Selectors;
import com.wikantik.pressroom.it.support.AdminApp;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Condition.disappear;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;

/**
 * Bulk actions on the entry list, in a real browser.
 *
 * <p>These are the parts the unit tests cannot reach: the selection actually
 * reaches the API (one call per entry, with the session's CSRF token), the
 * right action runs for the button pressed, and the delete confirmation is a
 * modal that does not block the page the way {@code window.confirm} would.
 */
class BulkEntryActionsIT extends PressroomIT {

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void publishingASelectedDraftMakesItPublic() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String title = "IT Bulk publish " + suffix;
        String draftId = saveDraft(title, "Body " + suffix);

        openPath(AdminApp.entries(WEBLOG_HANDLE));
        select(title);
        // The bar only shows once a row is checked -- select() above is what makes it visible.
        $(".selection-bar[data-selection-bar='entriesBulkForm']").shouldBe(visible);
        $(".selection-bar").$(Selectors.byText("Publish selected")).click();

        // The list reports the write, and the entry now carries its published status.
        $("#messages").should(exist);
        openPath(AdminApp.entry(WEBLOG_HANDLE, draftId));
        AdminApp.awaitEditor();
        $(AdminApp.PERMALINK).should(exist);
    }

    @Test
    void deletingASelectedEntryAsksFirstAndThenRemovesIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String title = "IT Bulk delete " + suffix;
        saveDraft(title, "Body " + suffix);

        openPath(AdminApp.entries(WEBLOG_HANDLE));
        select(title);
        $("#bulkDeleteButton").click();

        // A modal, not a native dialog: the page stays responsive.
        AdminApp.dialog().shouldBe(visible);
        AdminApp.confirmDialog();

        $("#messages").should(exist);
        // The row is gone, which is the only evidence that matters.
        AdminApp.row(title).should(disappear);
    }

    /** Saves a draft through the editor and returns its id. */
    private String saveDraft(String title, String body) {
        openPath(AdminApp.newEntry(WEBLOG_HANDLE));
        AdminApp.write(title, body);
        AdminApp.saveDraftButton().click();
        // The first save replaces /entries/new with the new entry's route.
        return AdminApp.savedEntryId();
    }

    private void select(String title) {
        AdminApp.row(title).should(exist).$("input.entry-select").click();
    }
}
