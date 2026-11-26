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
import java.time.Duration;

import com.wikantik.pressroom.it.support.AdminApp;
import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.codeborne.selenide.Selenide;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local draft recovery, end to end, in a real browser.
 *
 * <p>The React editor's draft keeper (and, for the page editor, {@code pressroom-draft.js}) is
 * exercised here the way an author actually meets it: type something,
 * abandon the page, come back. Four cases: a snapshot is offered back, a
 * properly saved draft offers nothing (the content-comparison rule, not a
 * timestamp), Discard removes the offer for good, and the page editor
 * recovers the same way the entry editor does.
 */
class EntryAutosaveIT extends PressroomIT {

    private static final String PAGE_ADD = "/pressroom-ui/authoring/pageEdit.rol?weblog=" + WEBLOG_HANDLE;

    private static final String DRAFT_BAR = "#draftRecoveryBar";
    private static final String DRAFT_RESTORE = ".draft-bar-restore";
    private static final String DRAFT_DISCARD = ".draft-bar-discard";

    /** The page editor's (JSP) keys: this prefix, the context path, the user id. */
    private static final String DRAFT_PREFIX = "pressroom.draft.v1:";

    /** The React entry editor's keys: this prefix, the user name, then {@code <handle>:<entry id or new>}. */
    private static final String APP_DRAFT_PREFIX = "pressroom.appdraft.v1:";

    /**
     * The debounce inside pressroom-draft.js is 2 seconds; this is how long a
     * wait for the resulting localStorage write is allowed to take on a
     * loaded CI runner before the test gives up and reports "no snapshot was
     * ever written" rather than a mysteriously missing recovery bar.
     */
    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(10);

    private String contextPath;

    @BeforeEach
    void logInAndClearDrafts() {
        loginAsAdmin();
        contextPath = URI.create(baseUrl()).getPath();

        // Navigate before touching storage, and do not remove this: signing in
        // no longer loads a page (PressroomIT.signInAs installs a session cookie
        // over CDP and stops there), so without an explicit open the browser is
        // still on about:blank -- which has no origin, and every localStorage
        // call below it throws WebDriverException. This class is the only one in
        // the suite that reads or writes storage directly, so it is the only one
        // that has to say so.
        openPath("/pressroom-ui/app/");

        // localStorage survives across tests that share a browser session
        // (and, more importantly, this suite runs its whole class in one
        // JVM against a shared instance), so a snapshot left behind by one
        // test -- or by a previous run against the same profile -- can be
        // offered to the next test and make it pass or fail for the wrong
        // reason. Clear this module's own keys, so every test here starts from
        // an empty draft store.
        clearStoredDrafts();
    }

    @Test
    void typingThenReloadingOffersTheTextBack() {
        String suffix = nonce();
        String body = "A paragraph nobody saved yet " + suffix;

        openPath(AdminApp.newEntry(WEBLOG_HANDLE));
        AdminApp.write("IT Autosave " + suffix, body);

        waitForEntryDraftSnapshot(entryDraftKey("new"));
        reloadWithoutLeaveWarning();

        $("#entry").should(exist);
        $(DRAFT_BAR).shouldBe(visible);
        $(DRAFT_RESTORE).click();

        String recovered = Editor.getText();
        assertTrue(recovered != null && recovered.contains(body),
                "restore did not bring the unsaved text back; the editor holds: " + recovered);
    }

    @Test
    void savingThenReloadingOffersNothing() {
        String suffix = nonce();
        String body = "A paragraph that gets saved properly " + suffix;

        openPath(AdminApp.newEntry(WEBLOG_HANDLE));
        AdminApp.write("IT Autosave Saved " + suffix, body);

        String newDraftKey = entryDraftKey("new");
        waitForEntryDraftSnapshot(newDraftKey);

        // A real save: the route moves to the saved entry and the keeper clears what it held.
        AdminApp.saveDraftButton().click();
        AdminApp.savedEntryId();

        // The new-entry snapshot must not survive a save, and a reload of the saved entry offers nothing:
        // what the server holds is what the editor shows. (If the rule regressed from "a save clears the
        // draft" to a timestamp, this snapshot -- written seconds ago -- would be offered anyway.)
        reloadWithoutLeaveWarning();

        $("#entry").should(exist);
        $(DRAFT_BAR).shouldNotBe(visible);
        assertTrue(!entryStorageHasKey(newDraftKey),
                "a snapshot matching the saved content must not survive in storage");
    }

    @Test
    void publishingAnExistingEntryOffersNothingAfterwards() {
        // A save of an existing entry clears its draft: the editor shows what the server holds, so a
        // snapshot of it is no unsaved edit. It is also a status CHANGE on purpose: a draft that became
        // published must not raise a phantom "unsaved changes" bar over a save that just succeeded.
        String suffix = nonce();
        String body = "A paragraph that gets published " + suffix;

        String entryId = saveDraftFromAddForm("IT Autosave Publish " + suffix, body);

        openPath(AdminApp.entry(WEBLOG_HANDLE, entryId));
        AdminApp.awaitEditor();
        Editor.setText(body + " (revised)");

        String editKey = entryDraftKey(entryId);
        waitForEntryDraftSnapshot(editKey);

        AdminApp.publishButton().click();

        // Wait for the PUBLISHED status: it is only Published once the save has round-tripped, and the
        // reload below must not race the publish call still in flight.
        AdminApp.awaitStatus("Published");

        reloadWithoutLeaveWarning();
        $("#entry").should(exist);
        $(DRAFT_BAR).shouldNotBe(visible);
        assertTrue(!entryStorageHasKey(editKey),
                "a snapshot matching the just-published content must not survive; "
                        + "a status change is not an unsaved edit");
    }

    @Test
    void discardingRemovesTheOfferForGood() {
        String suffix = nonce();
        String body = "A paragraph nobody wants recovered " + suffix;

        openPath(AdminApp.newEntry(WEBLOG_HANDLE));
        AdminApp.write("IT Autosave Discard " + suffix, body);

        waitForEntryDraftSnapshot(entryDraftKey("new"));
        reloadWithoutLeaveWarning();

        $("#entry").should(exist);
        $(DRAFT_BAR).shouldBe(visible);
        $(DRAFT_DISCARD).click();
        $(DRAFT_BAR).shouldNotBe(visible);

        reloadWithoutLeaveWarning();
        $("#entry").should(exist);
        $(DRAFT_BAR).shouldNotBe(visible);
    }

    /**
     * The status line (Task A8), end to end: word count follows typing, the
     * save-state span reads "Unsaved changes" as soon as something has
     * changed, and reads "Draft saved locally" once {@code pressroom-draft.js}
     * actually writes a snapshot -- the {@code pressroom-draft:saved} event this
     * wave added to that module, consumed here rather than in a unit test
     * because the event only exists once a real debounce has elapsed.
     */
    @Test
    void typingUpdatesTheWordCountAndSaveStatus() {
        String suffix = nonce();

        openPath(AdminApp.newEntry(WEBLOG_HANDLE));
        AdminApp.awaitEditor();
        $("#editorStatusSave").shouldHave(text("Saved"));

        AdminApp.titleField().setValue("Status Line " + suffix);
        Editor.setText("one two three four five");

        $("#editorStatusWords").shouldHave(text("5 words"));
        $("#editorStatusSave").shouldHave(text("Unsaved changes"));

        waitForEntryDraftSnapshot(entryDraftKey("new"));
        $("#editorStatusSave").shouldHave(text("Draft saved locally"));
    }

    @Test
    void thePageEditorRecoversTheSameWay() {
        String suffix = nonce();
        String body = "A page paragraph nobody saved yet " + suffix;

        openPath(PAGE_ADD);
        $("#pageEditForm").should(exist);
        Editor.setText(body);

        waitForDraftSnapshot(pageDraftKey("new"));
        reloadWithoutLeaveWarning();

        $("#pageEditForm").should(exist);
        $(DRAFT_BAR).shouldBe(visible);
        $(DRAFT_RESTORE).click();

        String recovered = Editor.getText();
        assertTrue(recovered != null && recovered.contains(body),
                "restore did not bring the unsaved page text back; the editor holds: " + recovered);
    }

    // ---------------------------------------------------------------- helpers

    /** Creates an entry through the new-entry editor and returns its id. */
    private String saveDraftFromAddForm(String title, String body) {
        openPath(AdminApp.newEntry(WEBLOG_HANDLE));
        AdminApp.write(title, body);
        AdminApp.saveDraftButton().click();
        return AdminApp.savedEntryId();
    }

    /**
     * Reloads the current page after silencing the editor's own
     * {@code beforeunload} leave-warning.
     *
     * <p>Typing anything installs that handler, and an unhandled Chrome
     * beforeunload dialog blocks the whole WebDriver session -- every
     * command after it fails, not just this one navigation. A capturing listener of our own, added after
     * the editor's, runs first at the window and stops the rest, which tests draft recovery rather than
     * the leave-warning (which Vitest covers).
     */
    private void reloadWithoutLeaveWarning() {
        executeJavaScript(
                "window.onbeforeunload = null;"
                + "window.addEventListener('beforeunload', function (e) { e.stopImmediatePropagation(); }, true);"
                + "if (window.jQuery) { jQuery(window).off('beforeunload'); }");
        Selenide.refresh();
        BrowserHealth.current().settle();
    }

    /** Waits for the React editor's debounced snapshot of {@code <handle>:<id or new>} to land in storage. */
    private void waitForEntryDraftSnapshot(String key) {
        Selenide.Wait().withTimeout(SNAPSHOT_TIMEOUT)
                .withMessage(() -> "no draft snapshot was ever written for key " + key)
                .until(d -> entryStorageHasKey(key));
    }

    /** Whether the React editor's draft {@code <handle>:<id or new>} is in storage, under any user name. */
    private boolean entryStorageHasKey(String key) {
        return Boolean.TRUE.equals(executeJavaScript(
                "var head = arguments[0], tail = ':' + arguments[1];"
                + "for (var i = 0; i < window.localStorage.length; i++) {"
                + "  var k = window.localStorage.key(i);"
                + "  if (k && k.indexOf(head) === 0 && k.slice(-tail.length) === tail"
                + "      && window.localStorage.getItem(k) !== null) { return true; }"
                + "}"
                + "return false;",
                APP_DRAFT_PREFIX, key));
    }

    /** Waits, on the actual condition, for the debounced snapshot to land in storage (the page editor's). */
    private void waitForDraftSnapshot(String key) {
        Selenide.Wait().withTimeout(SNAPSHOT_TIMEOUT)
                .withMessage(() -> "no draft snapshot was ever written for key " + key)
                .until(d -> storageHasKey(key));
    }

    /**
     * Whether the draft named by {@code key} (its part after the user id, see
     * {@link #entryDraftKey}) is in storage under some signed-in user's id.
     * The id is a database UUID this harness does not know; that the key
     * carries a non-empty one is checked here rather than assumed.
     */
    private boolean storageHasKey(String key) {
        return Boolean.TRUE.equals(executeJavaScript(
                "var head = arguments[0], tail = ':' + arguments[1];"
                + "for (var i = 0; i < window.localStorage.length; i++) {"
                + "  var k = window.localStorage.key(i);"
                + "  if (!k || k.indexOf(head) !== 0 || k.length <= head.length + tail.length"
                + "      || k.slice(-tail.length) !== tail) { continue; }"
                + "  var user = k.substring(head.length, k.length - tail.length);"
                + "  if (user.indexOf(':') < 0) { return window.localStorage.getItem(k) !== null; }"
                + "}"
                + "return false;",
                DRAFT_PREFIX + contextPath + ":", key));
    }

    private void clearStoredDrafts() {
        executeJavaScript(
                "var prefixes = [arguments[0], arguments[1]];"
                + "var doomed = [];"
                + "for (var i = 0; i < window.localStorage.length; i++) {"
                + "  var k = window.localStorage.key(i);"
                + "  if (k && (k.indexOf(prefixes[0]) === 0 || k.indexOf(prefixes[1]) === 0)) { doomed.push(k); }"
                + "}"
                + "for (var j = 0; j < doomed.length; j++) { window.localStorage.removeItem(doomed[j]); }",
                DRAFT_PREFIX, APP_DRAFT_PREFIX);
    }

    /** The part of a React entry draft key after {@code pressroom.appdraft.v1:<user name>:}. */
    private String entryDraftKey(String id) {
        return WEBLOG_HANDLE + ":" + id;
    }

    private String pageDraftKey(String id) {
        return WEBLOG_HANDLE + ":pageEdit:" + id;
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
