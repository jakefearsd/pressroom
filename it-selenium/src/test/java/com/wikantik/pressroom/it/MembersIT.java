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
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.disabled;
import static com.codeborne.selenide.Condition.enabled;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeAsyncJavaScript;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Members screen ({@code /weblogs/{handle}/members}), which grants weblog permissions, driven as the
 * people it governs.
 *
 * <p>The rules, read from {@code MembersApi}/{@code Memberships}: the page and its API need the weblog's
 * {@code ADMIN} level (anyone else gets the not-found treatment, the API a 403 or 404); a level is one of
 * Admin, Author ({@code POST}) or Limited ({@code EDIT_DRAFT}); nobody lowers or removes their own role;
 * a weblog keeps one admin; an unknown account is refused. A member below ADMIN is answered 403 by the API
 * (the weblog is theirs to see), a stranger 404.
 *
 * <p>One weblog and four accounts, built once by the first test (a {@code @BeforeAll} has no browser).
 * Each test uses accounts of its own, so none depends on another's leftovers. The weblog is removed in
 * {@code @AfterAll}; accounts cannot be deleted, so they carry the run's nonce. Holds
 * {@link PressroomIT#GLOBAL_CONFIG} for READ: granting needs {@code groupblogging.enabled}, which
 * {@code GlobalConfigMatrixIT} turns off.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class MembersIT extends PressroomIT {

    private static final String PASSWORD = "it-user-password";

    private boolean fixtureStarted;
    private boolean fixtureBuilt;
    private String handle;
    private String bob;
    private String carol;
    private String alice;
    private String dave;

    @BeforeEach
    void buildTheFixtureOnce() {
        if (fixtureStarted) {
            assertTrue(fixtureBuilt, "the class fixture failed in an earlier test; see that failure");
            return;
        }
        fixtureStarted = true;
        String nonce = Long.toString(System.nanoTime(), 36);
        handle = "membersit" + nonce;
        bob = "bob" + nonce;
        carol = "carol" + nonce;
        alice = "alice" + nonce;
        dave = "dave" + nonce;
        loginAsAdmin();
        for (String user : new String[] {bob, carol, alice, dave}) {
            createUser(user);
        }
        createWeblogOnTheReactScreen(handle, "Members IT " + nonce, "journal");
        logout();
        fixtureBuilt = true;
    }

    @AfterAll
    void removeTheWeblog() {
        inBrowserAfterAll(() -> {
            if (handle == null) {
                return;
            }
            loginAsAdmin();
            openPath("/pressroom-ui/app/weblogs/" + handle + "/remove");
            $("button.btn-danger").should(visible).click();
            $("h2.mm_weblog_name").should(exist);
            $$("h2.mm_weblog_name .data").findBy(com.codeborne.selenide.Condition.exactText(handle))
                    .shouldNot(exist);
            logout();
        });
    }

    /**
     * Add a member at Author, change them to Limited, remove them; after each step the person themselves,
     * signed in, sees the matching effect: an Author publishes, a Limited member's publish is only a
     * submission for review, and a removed member is refused the weblog altogether.
     */
    @Test
    void grantingChangingAndRemovingAMemberTakesEffectForThatPerson() {
        loginAsAdmin();
        openMembers();
        $(radio(bob, "POST")).shouldNot(exist);

        // --- grant at Author: the row, the flash, and it persists across a reload
        addMember(bob, "POST");
        $("#messages").shouldHave(text("Permissions updated for " + bob));
        $(radio(bob, "POST")).shouldBe(checked);
        openMembers();
        $(radio(bob, "POST")).shouldBe(checked);
        $(radio(bob, "ADMIN")).shouldNotBe(checked);
        logout();

        // --- the Author reaches the weblog and publishes (not a submission for review)
        signInAs(bob, PASSWORD);
        openPath(AdminApp.entries(handle));
        $("#entries-list-marker").should(exist);
        openPath(AdminApp.newEntry(handle));
        AdminApp.write("Author post " + bob, "By an author");
        AdminApp.publishButton().shouldHave(text("Publish"));
        AdminApp.publishButton().click();
        $(AdminApp.PERMALINK).should(exist);
        logout();

        // --- demote to Limited
        loginAsAdmin();
        openMembers();
        $(radio(bob, "EDIT_DRAFT")).click();
        $("#memberPermissionsForm button.btn-primary").click();
        $("#messages").shouldHave(text("Members changed: 1."));
        openMembers();
        $(radio(bob, "EDIT_DRAFT")).shouldBe(checked);
        logout();

        // --- the Limited member can write, but only submit for review
        signInAs(bob, PASSWORD);
        openPath(AdminApp.newEntry(handle));
        AdminApp.write("Limited post " + bob, "By a limited member");
        AdminApp.publishButton().shouldHave(text("Submit for review"));
        AdminApp.publishButton().click();
        AdminApp.awaitStatus("Pending");
        assertTrue($$(AdminApp.PERMALINK).isEmpty(), "a Limited member's submission went live");
        logout();

        // --- remove: the radio, a confirmation, and the member is gone
        loginAsAdmin();
        openMembers();
        $(radio(bob, "REMOVE")).click();
        $("#memberPermissionsForm button.btn-primary").click();
        AdminApp.dialog().shouldBe(visible);
        AdminApp.confirmDialog();
        $("#messages").shouldHave(text("Members removed: 1."));
        $(radio(bob, "POST")).shouldNot(exist);
        openMembers();
        $(radio(bob, "POST")).shouldNot(exist);
        logout();

        // --- the removed member is refused the weblog: no list, and the API answers 404
        signInAs(bob, PASSWORD);
        BrowserHealth.current().expectRefusal("/weblogs/" + handle + "/entries");
        BrowserHealth.current().expectRefusal("/weblogs/" + handle);
        BrowserHealth.current().expectRefusal("/weblogs/" + handle + "/categories");
        openPath(AdminApp.entries(handle));
        BrowserHealth.current().settle();
        $("#entries-list-marker").shouldHave(text("No such weblog."));
        assertTrue($$("table.pressroomtable").isEmpty(), "a removed member still sees the weblog's entries");
        assertEquals(404, apiStatus("GET", "/api/v1/weblogs/" + handle + "/entries"),
                "a removed member still reads the weblog's entries");
        logout();
    }

    /**
     * An Author is not a weblog admin: the Members screen shows them nothing to manage and the API answers
     * 403 to reading, granting and removing -- while the same account still reaches the weblog's entries.
     */
    @Test
    void anAuthorCannotManageMembers() {
        loginAsAdmin();
        openMembers();
        addMember(carol, "POST");
        $("#messages").shouldHave(text("Permissions updated for " + carol));
        logout();

        signInAs(carol, PASSWORD);
        BrowserHealth.current().expectRefusal("/weblogs/" + handle + "/members");
        openPath(AdminApp.entries(handle));
        $("#entries-list-marker").should(exist);
        openMembers();
        BrowserHealth.current().settle();
        $("#memberPermissionsForm").shouldNot(exist);
        $("button[type='submit'].btn-secondary").shouldNot(exist);

        String members = "/api/v1/weblogs/" + handle + "/members";
        // A member below ADMIN is told so (403: the weblog is theirs to see); only a stranger gets 404.
        BrowserHealth.current().expectRefusal(members);
        BrowserHealth.current().expectRefusal(members + "/" + carol);
        assertEquals(403, apiStatus("GET", members), "an Author read the member list");
        assertEquals(403, apiStatus("DELETE", members + "/" + carol), "an Author removed a member");
        assertEquals(403, apiStatus("POST", members, "{\"userName\":\"" + dave + "\",\"permission\":\"ADMIN\"}"),
                "an Author granted a role");
        logout();

        // Nothing changed: dave was not added, carol still holds Author.
        loginAsAdmin();
        openMembers();
        $(radio(carol, "POST")).shouldBe(checked);
        $(radio(dave, "ADMIN")).shouldNot(exist);
        logout();
    }

    /**
     * A member who is an admin of the weblog manages its members, cannot lower or remove their own role,
     * is told when the account they name does not exist, and loses the screen when another admin lowers them.
     */
    @Test
    void anAdminMemberManagesMembersButNotTheirOwnRole() {
        loginAsAdmin();
        openMembers();
        addMember(alice, "ADMIN");
        $("#messages").shouldHave(text("Permissions updated for " + alice));
        logout();

        signInAs(alice, PASSWORD);
        openMembers();
        $("#memberPermissionsForm").should(exist);
        // Their own row: only Admin is open; lowering or removing oneself is locked.
        $(radio(alice, "ADMIN")).shouldBe(checked);
        $(radio(alice, "POST")).shouldBe(disabled);
        $(radio(alice, "EDIT_DRAFT")).shouldBe(disabled);
        $(radio(alice, "REMOVE")).shouldBe(disabled);
        // Someone else's row is open.
        $(radio(ADMIN_USERNAME, "POST")).shouldBe(enabled);

        // An account that does not exist is refused in place.
        addMember("nosuchuser" + dave, "POST");
        $("#errors").shouldHave(text("User not found."));
        $(radio("nosuchuser" + dave, "POST")).shouldNot(exist);

        // An existing one is added by an admin member, not only by the site admin.
        addMember(dave, "EDIT_DRAFT");
        $("#messages").shouldHave(text("Permissions updated for " + dave));
        $(radio(dave, "EDIT_DRAFT")).shouldBe(checked);
        logout();

        // The site admin lowers alice; she then loses the screen.
        loginAsAdmin();
        openMembers();
        $(radio(alice, "POST")).click();
        $("#memberPermissionsForm button.btn-primary").click();
        $("#messages").shouldHave(text("Members changed: 1."));
        logout();

        signInAs(alice, PASSWORD);
        BrowserHealth.current().expectRefusal("/weblogs/" + handle + "/members");
        openMembers();
        BrowserHealth.current().settle();
        $("#memberPermissionsForm").shouldNot(exist);
        logout();
    }

    // ---------------------------------------------------------------- helpers

    private static String radio(String user, String level) {
        return "input[name='perm-" + user + "'][value='" + level + "']";
    }

    private void openMembers() {
        openPath("/pressroom-ui/app/weblogs/" + handle + "/members");
    }

    /** Uses the add-member form (the row above the table) to grant {@code level}. */
    private void addMember(String user, String level) {
        $("#memberPermissionsForm").should(exist);
        $("#grantUserName").setValue(user);
        $("#grantPermission").selectOptionByValue(level);
        $$("button[type='submit']").findBy(text("Add member")).click();
    }

    /** Creates an enabled, non-administrator account through the admin UI. */
    private void createUser(String userName) {
        openPath("/pressroom-ui/app/admin/users/new");
        $("input[name='bean.userName']").should(visible).setValue(userName);
        BrowserHealth.current().settle();
        $("input[name='bean.screenName']").setValue("Screen " + userName);
        $("input[name='bean.fullName']").setValue("Full " + userName);
        if ($("#bean_passwordModeNow").exists()) {
            $("#bean_passwordModeNow").click();
        }
        $("input[name='bean.password']").setValue(PASSWORD);
        $("input[name='bean.emailAddress']").setValue(userName + "@example.com");
        clickAndAwaitSavedMessage($("#save_button"));
    }

    private int apiStatus(String method, String path) {
        return apiStatus(method, path, null);
    }

    /** The status of an API call made from the signed-in page, with its CSRF token (session-only writes). */
    private int apiStatus(String method, String path, String body) {
        Object status = executeAsyncJavaScript(
                "var done = arguments[arguments.length - 1];"
                        + "var boot = JSON.parse(document.getElementById('pressroom-bootstrap').textContent);"
                        + "var headers = {'Accept': 'application/json'};"
                        + "headers[boot.csrf.headerName] = boot.csrf.token;"
                        + "var init = { method: arguments[0], headers: headers, credentials: 'same-origin' };"
                        + "if (arguments[2]) { headers['Content-Type'] = 'application/json'; init.body = arguments[2]; }"
                        + "fetch(boot.contextPath + arguments[1], init).then(function (r) { done(r.status); });",
                method, path, body);
        return ((Number) status).intValue();
    }
}
