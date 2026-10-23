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
package org.apache.roller.it;

import com.codeborne.selenide.WebDriverRunner;
import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.Editor;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.disappear;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole system from an empty account outward: an administrator creates two
 * users, each user makes their own weblog, writes a post, edits it, and deletes
 * it -- and neither can touch the other's.
 *
 * <p>Every other browser test in this suite drives one seeded administrator on
 * one seeded weblog, which means the multi-user paths -- account creation,
 * signing in as somebody who did not exist when the server started, weblog
 * creation, and the isolation between two real owners -- were never executed
 * end to end. That is exactly where this codebase has repeatedly turned out to
 * be weakest: the entry, media and template controllers each shipped resolving
 * client-supplied ids without checking who owned them, and the last of those
 * was found by hand this week rather than by a test.
 *
 * <p>The isolation assertions here are deliberately blunt: they ask whether one
 * owner's entry id, handed to the other owner's session, does anything at all.
 */
@ResourceLock(value = RollerIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class MultiUserJourneyIT extends RollerIT {

    private static final String CREATE_USER = "/roller-ui/admin/createUser.rol";
    private static final String CREATE_WEBLOG = "/roller-ui/createWeblog.rol";

    /** Rendered on the edit page only once the entry is actually published. */
    private static final String PERMALINK = "#entry_bean_permalink";

    private static final String PASSWORD = "it-user-password";

    @Test
    void twoUsersEachOwnTheirBlogAndCannotReachTheOthers() {
        // Alphanumeric only: the create-user form rejects anything else, which
        // the seeded it_admin sidesteps by being inserted straight into the
        // database rather than going through the form.
        String suffix = Long.toString(System.nanoTime(), 36);
        String alice = "alice" + suffix;
        String bob = "bob" + suffix;
        String aliceBlog = "aliceblog" + suffix;
        String bobBlog = "bobblog" + suffix;

        // --- the administrator creates two accounts -------------------------
        loginAsAdmin();
        createUser(alice, "Alice " + suffix);
        createUser(bob, "Bob " + suffix);
        logout();

        // --- Alice sets up her weblog and publishes ------------------------
        // signInAs, not loginAs: nothing in this class is testing the login
        // SCREEN, only that these two accounts own separate blogs and cannot
        // reach each other's. The fast path still posts the same credentials to
        // the same j_security_check endpoint, so "the account the admin just
        // created can actually sign in" is still proved here; it just skips the
        // three page loads the form costs, four times over. UserAdminIT keeps
        // the form login for a non-admin account, where the screen IS the
        // subject.
        signInAs(alice, PASSWORD);
        createWeblog(aliceBlog, "Alice's Blog " + suffix);
        String aliceEntryId = publishEntry(aliceBlog, "Alice first post " + suffix,
                "Written by Alice " + suffix);

        // edit it
        String editedBody = "Rewritten by Alice " + suffix;
        editEntryBody(aliceBlog, aliceEntryId, editedBody);
        assertTrue(entryBody(aliceBlog, aliceEntryId).contains(editedBody),
                "Alice's edit did not stick");
        logout();

        // --- Bob does the same on his own weblog ---------------------------
        signInAs(bob, PASSWORD);
        createWeblog(bobBlog, "Bob's Blog " + suffix);
        String bobEntryId = publishEntry(bobBlog, "Bob first post " + suffix,
                "Written by Bob " + suffix);

        // --- Bob cannot reach Alice's entry --------------------------------
        // Through his own weblog's editor, with her id: the controller resolves
        // ids globally, so only an ownership check stands between these two.
        openPath("/roller-ui/authoring/entryEdit.rol?weblog=" + bobBlog
                + "&bean.id=" + aliceEntryId);
        BrowserHealth.current().settle();
        String leaked = $$("#entry").isEmpty() ? "" : Editor.getText();
        assertFalse(leaked != null && leaked.contains("Rewritten by Alice"),
                "Bob opened Alice's entry in his own editor; it holds: " + leaked);

        // And he cannot delete it either. The refusal is a 4xx, which is the
        // correct answer here and not a broken page.
        BrowserHealth.current().expectRefusal("entryRemoveViaList!remove.rol");
        deleteEntry(bobBlog, aliceEntryId);
        logout();

        signInAs(alice, PASSWORD);
        assertTrue(entryBody(aliceBlog, aliceEntryId).contains(editedBody),
                "Bob's delete removed Alice's entry");

        // --- Alice deletes her own post, which must work -------------------
        deleteEntry(aliceBlog, aliceEntryId);
        openPath("/roller-ui/authoring/entries.rol?weblog=" + aliceBlog);
        // The row's own checkbox, not the page text: the delete's flash
        // message quotes the entry title, so scanning the source for the
        // title finds the confirmation that it is gone.
        $("input[name='selectedEntries'][value='" + aliceEntryId + "']").should(disappear);
        logout();

        // --- Bob's post is untouched by all of that ------------------------
        signInAs(bob, PASSWORD);
        assertTrue(entryBody(bobBlog, bobEntryId).contains("Written by Bob"),
                "Bob's entry did not survive Alice's activity");
        logout();
    }

    /**
     * Task B10: {@code BaseController.populateCommonModel} adds
     * {@code userWeblogs} only when the signed-in user holds two or more
     * weblogs, and {@code bannerStatus.jsp} renders {@code #weblogSwitcher}
     * only then. This is the one end-to-end proof neither half is enough on
     * its own: a one-weblog session must render no switcher at all, and
     * picking a weblog out of the switcher's menu must land on the SAME rail
     * screen ({@code entries.rol}) for the newly chosen weblog rather than,
     * say, the main menu.
     */
    @Test
    void theSwitcherAppearsOnlyWithTwoWeblogsAndKeepsTheScreen() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String user = "carol" + suffix;
        String blogA = "carolbloga" + suffix;
        String blogB = "carolblogb" + suffix;

        loginAsAdmin();
        createUser(user, "Carol " + suffix);
        logout();

        signInAs(user, PASSWORD);
        createWeblog(blogA, "Carol's Blog A " + suffix);

        // --- one weblog: no switcher at all ---------------------------------
        openPath("/roller-ui/authoring/entries.rol?weblog=" + blogA);
        BrowserHealth.current().settle();
        $("#weblogSwitcher").shouldNot(exist);

        // --- a second weblog makes the switcher appear ----------------------
        createWeblog(blogB, "Carol's Blog B " + suffix);

        openPath("/roller-ui/authoring/entries.rol?weblog=" + blogA);
        BrowserHealth.current().settle();
        $("#weblogSwitcher").should(exist).click();
        clickAndAwaitNewPage($(".weblog-switcher .dropdown-item[href*='weblog=" + blogB + "']").should(visible));

        String url = WebDriverRunner.url();
        assertTrue(url.contains("entries.rol"),
                "choosing another weblog from the switcher must keep the same rail screen: " + url);
        assertTrue(url.contains("weblog=" + blogB),
                "choosing Blog B from the switcher did not land on its entries list: " + url);

        logout();
    }

    // ---------------------------------------------------------------- helpers

    /** Creates an enabled, non-administrator account through the admin UI. */
    private void createUser(String userName, String screenName) {
        openPath(CREATE_USER);
        $("input[name='bean.userName']").should(visible).setValue(userName);
        $("input[name='bean.screenName']").setValue(screenName);
        $("input[name='bean.fullName']").setValue(screenName);
        // "Set a password now" is offered beside the emailed link only while
        // mail is configured; the password field is there either way.
        if ($("#bean_passwordModeNow").exists()) {
            $("#bean_passwordModeNow").click();
        }
        $("input[name='bean.password']").setValue(PASSWORD);
        // example.com, not .invalid: the form now validates the address.
        $("input[name='bean.emailAddress']").setValue(userName + "@example.com");
        // Enabled is ticked by default now (spec 2026-10-13, section 4).
        $("input[name='bean.enabled']").shouldBe(checked);
        $("#save_button").click();

        // The success page is the user list; a validation failure re-renders
        // the form with the username still in it.
        $("#messages").should(exist);
        assertTrue($$("input[name='bean.userName']").isEmpty()
                        || !userName.equals($("input[name='bean.userName']").getValue()),
                "creating " + userName + " came back with the form still filled in, "
                        + "which means it failed validation");
    }

    /** Creates a weblog owned by whoever is signed in. */
    private void createWeblog(String handle, String name) {
        openPath(CREATE_WEBLOG);
        $("#name").should(visible).setValue(name);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");

        // Read back before submitting: the form runs jQuery validation on
        // submit, and a field that silently failed to take turns into a
        // client-side block that looks nothing like a typing problem.
        $("#name").shouldHave(value(name));
        $("#handle").shouldHave(value(handle));
        $("#emailAddress").shouldHave(value(handle + "@example.invalid"));
        $("button[type='submit']").click();

        // The save redirects to the menu with a flash message. Waiting on that
        // (a Selenide condition, so a failure captures the page) is the write
        // landing; the entry-list check below is then about permission.
        $("#messages").should(exist);

        // Reaching the new weblog's own entry list is the first evidence the
        // weblog exists AND that its creator has permission on it.
        openPath("/roller-ui/authoring/entries.rol?weblog=" + handle);
        $("#entries-list-marker, table.rollertable").should(exist);
        BrowserHealth.current().settle();
    }

    /** Publishes an entry on the given weblog and returns its id. */
    private String publishEntry(String weblogHandle, String title, String body) {
        openPath("/roller-ui/authoring/entryAdd.rol?weblog=" + weblogHandle);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText(body);
        $("button[formaction$='entryAdd!publish.rol']").click();

        // Wait on the write landing, not the page moving: the permalink block
        // renders only once the entry is actually published.
        $(PERMALINK).should(exist);
        String id = $("input[name='bean.id']").getValue();
        assertNotNull(id, "the editor must expose the published entry's id");
        return id;
    }

    private void editEntryBody(String weblogHandle, String entryId, String body) {
        openEditor(weblogHandle, entryId);
        Editor.setText(body);
        // Not a wait on #entry: the editor the click came from has one too.
        clickAndAwaitNewPage($("button[formaction$='entryEdit!saveDraft.rol']"));
        $("#entry").should(exist);
    }

    /** The body currently stored for an entry, as its own editor reports it. */
    private String entryBody(String weblogHandle, String entryId) {
        openEditor(weblogHandle, entryId);
        String text = Editor.getText();
        return text == null ? "" : text;
    }

    private void openEditor(String weblogHandle, String entryId) {
        openPath("/roller-ui/authoring/entryEdit.rol?weblog=" + weblogHandle
                + "&bean.id=" + entryId);
        $("#entry").should(exist);
        Editor.root();
    }

    /**
     * Deletes through the entry list's own confirmation modal, which is how a
     * user actually does it.
     */
    private void deleteEntry(String weblogHandle, String entryId) {
        openPath("/roller-ui/authoring/entries.rol?weblog=" + weblogHandle);
        BrowserHealth.current().settle();
        executeJavaScript("showDeleteModal(arguments[0], 'x');", entryId);
        $("#delete-entry-modal").shouldBe(visible);
        // Wait for the POST's answer to replace this page. Bob's caller signs out
        // next, and a POST still unsent at that point lands on a dead session: it
        // is refused for its CSRF token instead of by the ownership check, and its
        // 403 overwrites Alice's freshly installed session cookie.
        clickAndAwaitNewPage($("#delete-entry-modal button[type='submit']"));
    }
}
