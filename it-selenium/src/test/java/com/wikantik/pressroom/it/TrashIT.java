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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Condition.disappear;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.confirm;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The Trash screen end to end: an entry deleted from the Entries screen
 * lands in Trash, its permalink 404s while it sits there, restoring it
 * brings it back as a DRAFT (never straight back to PUBLISHED), and
 * deleting it forever actually removes it from the trash list.
 *
 * <p>The restore assertion is the one worth calling out.
 * {@code TrashController#restore} calls {@code restoreWeblogEntry}, which
 * sets the entry's status to {@code DRAFT} unconditionally -- an author gets
 * a chance to review before a restored post goes live again. It would be an
 * easy, "helpful" future change to instead restore an entry straight back to
 * PUBLISHED if it was published when it was trashed; this test would catch
 * that the moment it shipped, because the restored entry's permalink must
 * still 404 (a DRAFT permalink is not found, exactly like
 * {@code PageServletRenderingTest#draftEntryPermalinkIsNotFound}/
 * {@code trashedEntryPermalinkIsNotFound} prove at the unit level) and its
 * editor must report {@code bean.status == DRAFT}, not {@code PUBLISHED}.
 *
 * <p>Delete-forever needs the entry back in the trash a second time: restore
 * takes it out of Trash (it is a normal draft again), so this deletes it from
 * the Entries screen once more before exercising the delete-forever button.
 */
class TrashIT extends PressroomIT {

    private static final String ENTRY_ADD = "/pressroom-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String ENTRIES_LIST = "/pressroom-ui/authoring/entries.rol?weblog=" + WEBLOG_HANDLE;
    private static final String TRASH = "/pressroom-ui/authoring/trash.rol?weblog=" + WEBLOG_HANDLE;

    private static final String PERMALINK = "#entry_bean_permalink";

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void deletingRestoringAndDeletingForeverGoThroughTrashCorrectly() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String title = "IT Trash " + suffix;

        String entryId = publishEntry(title);
        String permalink = permalinkOf(entryId);
        assertEquals(200, statusOf(permalink), "the entry must be live before it is trashed");

        // --- delete from the Entries screen puts it in Trash ------------------
        deleteFromEntriesList(entryId);

        openTrash();
        $("#trash-list-marker").shouldHave(text(title));
        $("button[name='restoreId'][value='" + entryId + "']").should(exist);

        // --- a trashed entry's permalink 404s, not a rendered draft -----------
        assertEquals(404, statusOf(permalink),
                "a trashed entry's permalink must 404, not serve a draft or a stale copy");

        // --- restore brings it back as a DRAFT, not published -----------------
        $("button[name='restoreId'][value='" + entryId + "']").click();
        BrowserHealth.current().settle();
        $("#messages").should(exist);
        $("button[name='restoreId'][value='" + entryId + "']").should(disappear);

        assertEquals(404, statusOf(permalink),
                "a restored entry must come back as a draft -- its permalink must still 404, "
                        + "not be live again as if it had been republished");

        openPath("/pressroom-ui/authoring/entryEdit.rol?weblog=" + WEBLOG_HANDLE + "&bean.id=" + entryId);
        $("#entry").should(exist);
        $("input[name='bean.status']").shouldHave(value("DRAFT"));

        // --- delete forever, from the trash, actually removes it --------------
        // restore took it out of Trash; put it back so the delete-forever
        // control has something to act on.
        deleteFromEntriesList(entryId);

        openTrash();
        $("button[name='deleteId'][value='" + entryId + "']").should(exist).click();
        confirm();
        BrowserHealth.current().settle();
        $("#messages").should(exist);
        $("button[name='deleteId'][value='" + entryId + "']").should(disappear);
        $("button[name='restoreId'][value='" + entryId + "']").should(disappear);

        logout();
    }

    // ---------------------------------------------------------------- fixture

    /** Publishes one entry immediately and returns its id. */
    private String publishEntry(String title) {
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText("Body of " + title);
        $("button[formaction$='entryAdd!publish.rol']").click();

        $(PERMALINK).should(exist);
        String id = $("input[name='bean.id']").getValue();
        assertNotNull(id, "the editor must expose the saved entry's id");
        return id;
    }

    /** The live permalink for an entry, read from its editor page. */
    private String permalinkOf(String entryId) {
        openPath("/pressroom-ui/authoring/entryEdit.rol?weblog=" + WEBLOG_HANDLE + "&bean.id=" + entryId);
        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink, "a published entry's editor must expose its permalink");
        return permalink;
    }

    /**
     * Deletes an entry from the Entries screen through the real delete modal
     * -- this is a soft delete (trash), not a hard one; see
     * {@code EntryRemoveController#entryRemoveViaListRemove}.
     */
    private void deleteFromEntriesList(String entryId) {
        openPath(ENTRIES_LIST);
        executeJavaScript("showDeleteModal(arguments[0], 'x');", entryId);
        $("#delete-entry-modal").shouldBe(visible);
        $("#delete-entry-modal button[type='submit']").click();
        $("input[name='selectedEntries'][value='" + entryId + "']").should(disappear);
    }

    private void openTrash() {
        openPath(TRASH);
    }

    /** Status of an anonymous GET, without following redirects. */
    private static int statusOf(String url) {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder().uri(URI.create(url))
                            .timeout(Duration.ofSeconds(20))
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (Exception e) {
            throw new IllegalStateException("Could not GET " + url, e);
        }
    }
}
