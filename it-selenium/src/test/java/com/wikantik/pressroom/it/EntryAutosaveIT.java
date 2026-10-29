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
 * <p>{@code roller-draft.js} (unit-tested for its JSP wiring already) is
 * exercised here the way an author actually meets it: type something,
 * abandon the page, come back. Four cases: a snapshot is offered back, a
 * properly saved draft offers nothing (the content-comparison rule, not a
 * timestamp), Discard removes the offer for good, and the page editor
 * recovers the same way the entry editor does.
 */
class EntryAutosaveIT extends PressroomIT {

    private static final String ENTRY_ADD = "/pressroom-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String PAGE_ADD = "/pressroom-ui/authoring/pageEdit.rol?weblog=" + WEBLOG_HANDLE;

    private static final String DRAFT_BAR = "#draftRecoveryBar";
    private static final String DRAFT_RESTORE = ".draft-bar-restore";
    private static final String DRAFT_DISCARD = ".draft-bar-discard";

    private static final String DRAFT_PREFIX = "roller.draft.v1:";

    /**
     * The debounce inside roller-draft.js is 2 seconds; this is how long a
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
        openPath("/pressroom-ui/menu.rol");

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

        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue("IT Autosave " + suffix);
        Editor.setText(body);

        waitForDraftSnapshot(entryDraftKey("entryAdd", "new"));
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

        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue("IT Autosave Saved " + suffix);
        Editor.setText(body);

        String newDraftKey = entryDraftKey("entryAdd", "new");
        waitForDraftSnapshot(newDraftKey);

        // A real save: the submit handler flushes the pending snapshot and
        // the page lands on entryEdit!firstSave with a real bean.id.
        $("button[formaction$='entryAdd!saveDraft.rol']").click();
        $("input[name='bean.id']").should(exist);

        // This is the stale-detection rule: the entryAdd:new snapshot is
        // still sitting in storage (nothing has cleared it yet), but its
        // content now matches exactly what the server just rendered. If the
        // comparison ever regressed from content to a timestamp, this
        // snapshot -- saved seconds ago -- would look "recent" and get
        // offered anyway.
        reloadWithoutLeaveWarning();

        $("#entry").should(exist);
        $(DRAFT_BAR).shouldNotBe(visible);
        assertTrue(!storageHasKey(newDraftKey),
                "a snapshot matching the saved content must not survive in storage");
    }

    @Test
    void publishingAnExistingEntryOffersNothingAfterwards() {
        // The case that exercises differs()' DROP branch -- the wave's headline
        // invariant ("a snapshot matching what the server just rendered is one
        // whose save went through") -- against the PRIMARY key. Every other
        // case here either offers a bar or is satisfied by the staleKeys
        // branch, so without this one differs() could be rewritten to
        // `return true` and the whole suite would stay green.
        //
        // It is also a status CHANGE on purpose. doEntryEditSave mutates
        // bean.status and forwards, so the page rendered right after a
        // successful Post carries PUBLISHED while the snapshot taken at submit
        // holds DRAFT. That difference used to raise a phantom "unsaved
        // changes" bar over a save that had just succeeded, and kept raising it
        // on every later visit -- an offer of stale text the author could act
        // on. bean.status is excluded from the entry snapshot for this reason.
        String suffix = nonce();
        String body = "A paragraph that gets published " + suffix;

        String entryId = saveDraftFromAddForm("IT Autosave Publish " + suffix, body);

        openPath("/pressroom-ui/authoring/entryEdit.rol?weblog=" + WEBLOG_HANDLE
                + "&bean.id=" + entryId);
        $("#entry").should(exist);
        Editor.setText(body + " (revised)");

        String editKey = entryDraftKey("entryEdit", entryId);
        waitForDraftSnapshot(editKey);

        $("button[formaction$='entryEdit!publish.rol']").click();

        // Wait for the PUBLISHED page, not merely for an #entry -- the page we
        // are leaving has one of those too, so `should(exist)` was satisfied
        // instantly by the OLD document and the reload below then raced the
        // publish POST still in flight ("aborted by navigation: Not attached to
        // an active page"). The hidden status input is the right thing to wait
        // on twice over: it is only PUBLISHED once the save has round-tripped,
        // and the status change is the very thing this test is about.
        $("input[name='bean.status'][value='PUBLISHED']").should(exist);

        reloadWithoutLeaveWarning();
        $("#entry").should(exist);
        $(DRAFT_BAR).shouldNotBe(visible);
        assertTrue(!storageHasKey(editKey),
                "a snapshot matching the just-published content must not survive; "
                        + "a status change is not an unsaved edit");
    }

    @Test
    void discardingRemovesTheOfferForGood() {
        String suffix = nonce();
        String body = "A paragraph nobody wants recovered " + suffix;

        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue("IT Autosave Discard " + suffix);
        Editor.setText(body);

        waitForDraftSnapshot(entryDraftKey("entryAdd", "new"));
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
     * changed, and reads "Draft saved locally" once {@code roller-draft.js}
     * actually writes a snapshot -- the {@code roller-draft:saved} event this
     * wave added to that module, consumed here rather than in a unit test
     * because the event only exists once a real debounce has elapsed.
     */
    @Test
    void typingUpdatesTheWordCountAndSaveStatus() {
        String suffix = nonce();

        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("#editorStatusSave").shouldHave(text("Saved"));

        $("[name='bean.title']").setValue("Status Line " + suffix);
        Editor.setText("one two three four five");

        $("#editorStatusWords").shouldHave(text("5 words"));
        $("#editorStatusSave").shouldHave(text("Unsaved changes"));

        waitForDraftSnapshot(entryDraftKey("entryAdd", "new"));
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

    /** Creates an entry through the add form and returns its id. */
    private String saveDraftFromAddForm(String title, String body) {
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText(body);
        $("button[formaction$='entryAdd!saveDraft.rol']").click();
        $("input[name='bean.id']").should(exist);
        return $("input[name='bean.id']").getValue();
    }

    /**
     * Reloads the current page after silencing the editor's own
     * {@code beforeunload} leave-warning.
     *
     * <p>Typing anything installs that handler, and an unhandled Chrome
     * beforeunload dialog blocks the whole WebDriver session -- every
     * command after it fails, not just this one navigation. Clearing it
     * here tests draft recovery rather than the leave-warning, which has no
     * coverage today and gains none here.
     */
    private void reloadWithoutLeaveWarning() {
        executeJavaScript(
                "window.onbeforeunload = null;"
                + "if (window.jQuery) { jQuery(window).off('beforeunload'); }");
        Selenide.refresh();
        BrowserHealth.current().settle();
    }

    /** Waits, on the actual condition, for the debounced snapshot to land in storage. */
    private void waitForDraftSnapshot(String key) {
        Selenide.Wait().withTimeout(SNAPSHOT_TIMEOUT)
                .withMessage(() -> "no draft snapshot was ever written for key " + key)
                .until(d -> storageHasKey(key));
    }

    /** Whether a storage key currently holds anything. */
    private boolean storageHasKey(String key) {
        return Boolean.TRUE.equals(executeJavaScript(
                "return window.localStorage.getItem(arguments[0]) !== null;", key));
    }

    private void clearStoredDrafts() {
        executeJavaScript(
                "var prefix = arguments[0];"
                + "var doomed = [];"
                + "for (var i = 0; i < window.localStorage.length; i++) {"
                + "  var k = window.localStorage.key(i);"
                + "  if (k && k.indexOf(prefix) === 0) { doomed.push(k); }"
                + "}"
                + "for (var j = 0; j < doomed.length; j++) { window.localStorage.removeItem(doomed[j]); }",
                DRAFT_PREFIX);
    }

    private String entryDraftKey(String action, String id) {
        return DRAFT_PREFIX + contextPath + ":" + WEBLOG_HANDLE + ":" + action + ":" + id;
    }

    private String pageDraftKey(String id) {
        return DRAFT_PREFIX + contextPath + ":" + WEBLOG_HANDLE + ":pageEdit:" + id;
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
