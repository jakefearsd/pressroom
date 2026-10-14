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

import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Administering other people's accounts.
 *
 * <p>{@code modifyUser.rol} is one of the routes the sweep skips for want of a
 * seeded id, so nothing exercised it: an administrator editing somebody's
 * details, and -- the part that matters -- disabling an account. A disable that
 * does not actually stop the person signing in is the kind of failure nobody
 * notices until it matters, because the checkbox stays ticked either way.
 *
 * <p>Every test here creates its own account, so nothing depends on what ran
 * before it and nothing it does can lock the suite out of the seeded admin.
 */
class UserAdminIT extends RollerIT {

    private static final String CREATE_USER = "/roller-ui/admin/createUser.rol";
    private static final String USER_ADMIN = "/roller-ui/admin/userAdmin.rol";

    private static final String PASSWORD = "it-user-password";

    @Test
    void anAdminCanEditAnotherUsersDetails() {
        String user = "edituser" + nonce();

        loginAsAdmin();
        createUser(user);

        openUserForEditing(user);
        $("input[name='bean.screenName']").setValue("Renamed Screen Name");
        $("input[name='bean.fullName']").setValue("Renamed Full Name");
        saveUser();

        openUserForEditing(user);
        $("input[name='bean.screenName']").shouldHave(value("Renamed Screen Name"));
        $("input[name='bean.fullName']").shouldHave(value("Renamed Full Name"));
        logout();
    }

    /**
     * The username is the account's identity and is deliberately read-only once
     * the account exists; too much hangs off it (permission rows, entry
     * authorship) for a rename to be a text field.
     */
    @Test
    void theUsernameCannotBeEditedOnAnExistingAccount() {
        String user = "fixeduser" + nonce();

        loginAsAdmin();
        createUser(user);
        openUserForEditing(user);

        assertTrue($("input[name='bean.userName']").getAttribute("readonly") != null,
                "the username field must be read-only when editing an existing account");
        logout();
    }

    /**
     * Disabling an account has to stop the person signing in. This checks the
     * whole loop -- enabled, disabled, enabled again -- because a disable that
     * cannot be undone is its own kind of broken.
     */
    @Test
    void disablingAnAccountStopsThatUserSigningIn() {
        String user = "disableduser" + nonce();

        loginAsAdmin();
        createUser(user);
        logout();

        // Sanity: the account works before it is disabled, or the rest of this
        // test proves nothing.
        loginAs(user, PASSWORD);
        logout();

        loginAsAdmin();
        setEnabled(user, false);
        logout();

        assertTrue(loginIsRefused(user),
                "a disabled account was still able to sign in");

        loginAsAdmin();
        setEnabled(user, true);
        logout();

        loginAs(user, PASSWORD);
        logout();
    }

    // ---------------------------------------------------------------- helpers

    /** Creates an enabled, non-administrator account through the admin UI. */
    private void createUser(String userName) {
        openPath(CREATE_USER);
        $("input[name='bean.userName']").should(visible).setValue(userName);
        $("input[name='bean.screenName']").setValue("Screen " + userName);
        $("input[name='bean.fullName']").setValue("Full " + userName);
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

        $("#messages").should(exist);
    }

    /**
     * Finds a user in Admin → Users and opens their page: filter by name,
     * then follow the row's link, the way an administrator gets there.
     */
    private void openUserForEditing(String userName) {
        openPath(USER_ADMIN + "?q=" + userName);
        clickAndAwaitNewPage($("a[data-username='" + userName + "']"));

        $("input[name='bean.userName']").should(visible).shouldHave(value(userName));
    }

    private void saveUser() {
        $("#save_button").shouldBe(visible).click();
        $("#messages").should(exist);
    }

    /** Ticks or unticks the enabled box for a user and saves. */
    private void setEnabled(String userName, boolean enabled) {
        openUserForEditing(userName);
        if ($("input[name='bean.enabled']").isSelected() != enabled) {
            $("input[name='bean.enabled']").click();
        }
        saveUser();

        openUserForEditing(userName);
        if (enabled) {
            $("input[name='bean.enabled']").shouldBe(checked);
        } else {
            $("input[name='bean.enabled']").shouldNotBe(checked);
        }
    }

    /**
     * Attempts a sign-in and reports whether it was refused.
     *
     * <p>Cannot use {@link RollerIT#loginAs}, which asserts success. Judged on
     * reaching the editor rather than on the page's wording, so a change of
     * error message cannot turn this green.
     */
    private boolean loginIsRefused(String userName) {
        openPath("/roller-ui/login.rol");
        $("#j_username").setValue(userName);
        $("#j_password").setValue(PASSWORD);
        // Wait for the sign-in's answer before asking for the menu: a menu
        // request that overtakes the POST is anonymous, and would report any
        // account -- disabled or not -- as refused.
        clickAndAwaitNewPage($("#login"));

        openPath("/roller-ui/menu.rol");
        BrowserHealth.current().settle();
        return $$("a[href$='/roller-ui/logout.rol']").isEmpty();
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
