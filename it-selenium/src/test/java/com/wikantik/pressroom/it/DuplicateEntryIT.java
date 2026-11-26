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

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Duplicating a post from the entry list, in a real browser.
 *
 * <p>The unit tests cover what a copy carries and that a foreign id is
 * refused. This covers the parts that only exist in the browser: the list's
 * duplicate call goes through the API with the session's CSRF token (it is
 * refused outright without one), and the author lands in the editor on the
 * copy rather than the original -- the failure that would have them
 * unknowingly editing the published post.
 */
class DuplicateEntryIT extends PressroomIT {

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void duplicatingFromTheListOpensADraftCopyInTheEditor() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String title = "IT Duplicate " + suffix;
        String body = "Body written once, copied twice " + suffix;

        String originalId = publishEntry(title, body);

        openPath(AdminApp.entries(WEBLOG_HANDLE));
        AdminApp.row(title).$("button[aria-label^='Duplicate']").shouldBe(visible).click();

        // --- we are in the editor, on the copy ------------------------------
        AdminApp.awaitEditor();
        AdminApp.titleField().shouldHave(value("Copy of " + title));
        String copyId = AdminApp.savedEntryId();
        assertNotNull(copyId, "the editor must be open on a saved entry");
        assertNotEquals(originalId, copyId,
                "the redirect landed on the original, so the author would edit the published post");

        String copyText = Editor.getText();
        assertTrue(copyText != null && copyText.contains(body),
                "the copy did not carry the original's text; it holds: " + copyText);

        // A draft has no permalink block on the edit page -- that is the
        // page's own signal that this copy is not published.
        assertTrue($$(AdminApp.PERMALINK).isEmpty() || !$(AdminApp.PERMALINK).is(visible),
                "the copy is published; a duplicate must start life as a draft");
        AdminApp.awaitStatus("Draft");
    }

    /** Publishes an entry and returns its id. */
    private String publishEntry(String title, String body) {
        openPath(AdminApp.newEntry(WEBLOG_HANDLE));
        AdminApp.write(title, body);
        AdminApp.publishButton().click();

        // Wait on the write landing: the permalink block only renders once the entry is published.
        $(AdminApp.PERMALINK).should(exist);
        return AdminApp.savedEntryId();
    }
}
