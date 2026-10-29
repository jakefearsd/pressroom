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
 * refused. This covers the parts that only exist in the browser: the list row
 * posts with a CSRF token (Spring Security rejects it outright without one, so
 * a missing {@code sec:csrfInput} would 403 rather than fail quietly), and the
 * redirect lands the author in the editor on the copy rather than the original
 * -- the failure that would have them unknowingly editing the published post.
 */
class DuplicateEntryIT extends PressroomIT {

    private static final String ENTRY_ADD = "/roller-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String ENTRIES = "/roller-ui/authoring/entries.rol?weblog=" + WEBLOG_HANDLE;

    /** Rendered on the edit page only once the entry is actually published. */
    private static final String PERMALINK = "#entry_bean_permalink";

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

        openPath(ENTRIES);
        duplicateRowFor(originalId);

        // --- we are in the editor, on the copy ------------------------------
        $("#entry").should(exist);
        String copyId = $("input[name='bean.id']").getValue();
        assertNotNull(copyId, "the editor must be open on a saved entry");
        assertNotEquals(originalId, copyId,
                "the redirect landed on the original, so the author would edit the published post");

        $("[name='bean.title']").shouldHave(value("Copy of " + title));
        String copyText = Editor.getText();
        assertTrue(copyText != null && copyText.contains(body),
                "the copy did not carry the original's text; it holds: " + copyText);

        // A draft has no permalink block on the edit page -- that is the
        // page's own signal that this copy is not published.
        assertTrue($$(PERMALINK).isEmpty() || !$(PERMALINK).is(visible),
                "the copy is published; a duplicate must start life as a draft");
    }

    /** Publishes an entry and returns its id. */
    private String publishEntry(String title, String body) {
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText(body);
        $("button[formaction$='entryAdd!publish.rol']").click();

        // Wait on the write landing, not on the page moving: the permalink
        // block only renders once the entry is actually published.
        $(PERMALINK).should(exist);
        String id = $("input[name='bean.id']").getValue();
        assertNotNull(id, "the editor must expose the saved entry's id");
        return id;
    }

    /**
     * Presses the duplicate button of the list row for this entry id.
     *
     * <p>The button carries the id as its own name/value rather than a hidden
     * field, because the whole table is one form (the bulk actions need it that
     * way) and only the clicked submit button's value is sent.
     */
    private void duplicateRowFor(String entryId) {
        $("button[name='duplicateId'][value='" + entryId + "']").should(visible).click();
    }
}
