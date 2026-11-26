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
import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Keys;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static com.codeborne.selenide.Condition.disappear;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the loop Pressroom exists for: write an entry, publish it, read it as a
 * visitor, edit it, and delete it.
 *
 * <p>{@link RouteSweepIT} proves the admin pages load and render their own
 * content. It does not submit a single form, so it cannot tell you whether
 * publishing works — and it never touches the public rendering path a reader
 * actually sees. Everything below is the other half.
 *
 * <p>Each test is one continuous journey rather than several small ones because
 * {@code BrowserHealthExtension} gives every test its own browser and login;
 * state cannot be carried between test methods, and a half-journey proves
 * little. Every page visited is still checked for console errors and broken
 * assets by the inherited extension.
 *
 * <p>Entries are named with a unique suffix and never cleaned up. The
 * integration database is created from the migration chain and thrown away with
 * the container, so leftovers cost nothing and deleting them would hide
 * ordering bugs.
 */
class AuthoringJourneyIT extends PressroomIT {

    /** Rendered on the edit page only once the entry is actually published. */
    private static final String PERMALINK = AdminApp.PERMALINK;

    /**
     * The publish rail's status pill: Draft / Published / Pending / Scheduled,
     * plus Unsaved before the first save (the shared StatusPill component).
     */
    private static final String STATUS_PILL = AdminApp.STATUS_PILL;

    private HttpClient http;

    @BeforeEach
    void logIn() {
        loginAsAdmin();
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Test
    void anEntryCanBeWrittenPublishedReadEditedAndDeleted() {
        String suffix = uniqueSuffix();
        String title = "IT Journey " + suffix;
        String body = "Body of the journey entry " + suffix + ".";

        // --- write and publish -------------------------------------------------
        openPath(AdminApp.newEntry(WEBLOG_HANDLE));
        writeEntry(title, body);
        clickPublish();

        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink, "a published entry must expose its permalink on the edit page");
        assertTrue(permalink.contains("/" + WEBLOG_HANDLE + "/entry/"),
                "expected a weblog permalink of the form /" + WEBLOG_HANDLE + "/entry/<anchor>, got: "
                        + permalink);

        // --- read it as an anonymous visitor -----------------------------------
        // Fetched without any session, so a post that only renders for its
        // author would not slip through.
        readAnonymously(permalink, title, body);

        // --- edit ---------------------------------------------------------------
        String editedTitle = title + " (edited)";
        AdminApp.awaitEditor();
        AdminApp.titleField().setValue(editedTitle);
        clickPublish();

        $(PERMALINK).should(exist);
        assertEquals(permalink, $(PERMALINK).getAttribute("href"),
                "editing the title must not move the entry: the anchor is the permalink, and "
                        + "changing it would break every existing link to the post");

        readAnonymously(permalink, editedTitle, body);

        // --- delete -------------------------------------------------------------
        String entryId = AdminApp.savedEntryId();
        assertTrue(entryId != null && !entryId.isBlank(), "the editor must be open on the saved entry");
        deleteEntry(editedTitle);

        assertEquals(404, statusOf(permalink),
                "a deleted entry must stop resolving for readers, not linger in the cache or "
                        + "render an empty shell");
    }

    @Test
    void aDraftIsNeverVisibleToReaders() {
        String suffix = uniqueSuffix();
        String title = "IT Draft " + suffix;
        String body = "This draft must never reach a reader " + suffix + ".";

        openPath(AdminApp.newEntry(WEBLOG_HANDLE));
        writeEntry(title, body);
        AdminApp.saveDraftButton().click();

        // A draft has no permalink link on the edit page, but the URL it *would*
        // occupy is predictable from the title, and that is exactly what a reader
        // could guess.
        String entryId = AdminApp.savedEntryId();
        assertTrue(entryId != null && !entryId.isBlank(),
                "saving a draft must persist it and return its id");

        String guessedPermalink = baseUrl() + "/" + WEBLOG_HANDLE + "/entry/"
                + title.toLowerCase().replace(' ', '_');

        assertEquals(404, statusOf(guessedPermalink),
                "an unpublished draft must not be readable at its future permalink");

        // And it must not appear on the weblog's front page either.
        String frontPage = getAnonymously(baseUrl() + "/" + WEBLOG_HANDLE + "/");
        assertTrue(!frontPage.contains(title),
                "an unpublished draft must not be listed on the public weblog home page");
    }

    /**
     * Ctrl/Cmd+Enter publishes even with the caret inside the editor.
     *
     * <p>This is the one arrangement the page's document-level keydown handler
     * cannot rescue. CodeMirror 6's {@code defaultKeymap} binds {@code
     * Mod-Enter} to {@code insertBlankLine}, and a binding that runs
     * {@code preventDefault} without {@code stopPropagation} still reaches
     * document -- where the handler deliberately bails on an already-handled
     * event, because that bail is what stops Ctrl+S firing twice. So the
     * shortcut has to be bound INSIDE the editor at higher precedence than the
     * default keymap, and only a real keypress through a real editor can tell
     * you whether it was.
     *
     * <p>Typed through the editor's own surface rather than {@code
     * pressroomSetEntryText}, for the same reason: the seam function would prove
     * nothing about which keymap wins.
     */
    @Test
    void ctrlEnterInsideTheEditorPublishes() {
        String suffix = uniqueSuffix();
        String title = "IT CtrlEnter " + suffix;

        openPath(AdminApp.newEntry(WEBLOG_HANDLE));
        AdminApp.awaitEditor();
        AdminApp.titleField().setValue(title);
        Editor.type("Published from the keyboard " + suffix + ".");

        $(Editor.CONTENT).sendKeys(Keys.chord(Keys.CONTROL, Keys.ENTER));

        $(PERMALINK).should(exist);
        $(STATUS_PILL).shouldHave(text("Published"));
    }

    /**
     * Ctrl/Cmd+S inside the editor saves a draft, once.
     *
     * <p>Passes against the pre-fix code as well, and says so deliberately: it
     * is here to pin the OTHER half of the arrangement above. Binding
     * {@code Mod-s} inside the editor is only safe because the editor's
     * {@code preventDefault} makes the document-level handler stand down; get
     * that wrong and both fire, which historically meant "two saves, or a save
     * and a publish" per keystroke. A save that published is exactly what the
     * status assertion below refuses.
     */
    @Test
    void ctrlSInsideTheEditorSavesADraftWithoutPublishingIt() {
        String suffix = uniqueSuffix();
        String title = "IT CtrlS " + suffix;

        openPath(AdminApp.newEntry(WEBLOG_HANDLE));
        AdminApp.awaitEditor();
        AdminApp.titleField().setValue(title);
        Editor.type("Saved from the keyboard " + suffix + ".");

        $(Editor.CONTENT).sendKeys(Keys.chord(Keys.CONTROL, "s"));

        // One save landed: the new-entry route became the saved entry's, carrying an id.
        String entryId = AdminApp.savedEntryId();
        assertTrue(entryId != null && !entryId.isBlank(),
                "Ctrl+S must save the draft and return its id");

        // And it is still a DRAFT. A second handler firing the publish button
        // would show Published here, with a permalink beside it.
        $(STATUS_PILL).shouldHave(text("Draft"));
        $(PERMALINK).shouldNot(exist);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Fills the entry form.
     *
     * <p>The body goes in through {@code pressroomSetEntryText}, the page's own
     * seam, rather than by typing into the editor's surface.
     */
    private void writeEntry(String title, String body) {
        AdminApp.write(title, body);
    }

    /**
     * Presses the rail's primary button and waits for the save to land. On an entry that is already
     * published the permalink the callers wait on is already on the page, so the wait is on the success
     * banner, which the click clears and the answer brings back.
     */
    private void clickPublish() {
        AdminApp.publishButton().click();
        AdminApp.awaitSavedBanner();
        BrowserHealth.current().settle();
    }

    /**
     * Fetches the permalink with no session at all and asserts the entry is there.
     *
     * <p>Deliberately a plain HTTP GET rather than the browser. Clearing the
     * browser's cookies mid-session makes the container fall back to URL
     * rewriting, and Tomcat 10 then rejects its own {@code ;jsessionid=} links
     * with 400 - a real behaviour, but one that has nothing to do with whether a
     * reader can read the post. A cookie-less GET is what a first-time visitor
     * (or a crawler) actually sends.
     */
    private void readAnonymously(String permalink, String expectedTitle, String expectedBody) {
        String page = getAnonymously(permalink);

        assertTrue(page.contains(expectedTitle),
                "the published entry's title must appear on its public permalink page. "
                        + "Fetched " + permalink + " and got:\n" + truncate(page));
        assertTrue(page.contains(expectedBody),
                "the published entry's body must appear on its public permalink page. "
                        + "Fetched " + permalink + " and got:\n" + truncate(page));
    }

    /** Deletes the entry through the entry list's row button and its confirmation dialog. */
    private void deleteEntry(String title) {
        openPath(AdminApp.entries(WEBLOG_HANDLE));
        AdminApp.row(title).$("button.entry-delete-btn").shouldBe(visible).click();
        AdminApp.confirmDialog();
        AdminApp.row(title).should(disappear);
    }

    private String pathOf(String absoluteUrl) {
        String base = baseUrl();
        return absoluteUrl.startsWith(base) ? absoluteUrl.substring(base.length()) : absoluteUrl;
    }

    /** Status of an anonymous GET - the public visibility of a URL, not the author's view. */
    private int statusOf(String url) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .GET();
            HttpResponse<Void> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (Exception e) {
            throw new IllegalStateException("Could not GET " + url, e);
        }
    }

    /** Distinguishes entries across runs against a database that is not reset between tests. */
    private String uniqueSuffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String truncate(String text) {
        return text.length() <= 600 ? text : text.substring(0, 600) + "…";
    }
}
