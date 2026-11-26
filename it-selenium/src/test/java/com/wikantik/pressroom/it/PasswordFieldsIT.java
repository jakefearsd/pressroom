/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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

import com.codeborne.selenide.SelenideElement;
import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Keys;

import java.util.List;

import static com.codeborne.selenide.CollectionCondition.size;
import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The show/hide button on password fields (theme/scripts/pressroom-password.js,
 * docs/dev/admin-ui.md "Password fields"): it lets someone see what
 * a password manager actually filled in.
 *
 * <p>The reset-link page cannot be reached with a valid token from this suite
 * (tokens are stored hashed and only ever mailed), so its markup is pinned by
 * {@code AdminJspHygieneTest} and its behaviour rests on the same script proven
 * here on the other pages.
 */
class PasswordFieldsIT extends PressroomIT {

    private static final String LOGIN_PATH = "/pressroom-ui/login.rol";
    private static final String SHOW = "Show password";
    private static final String HIDE = "Hide password";
    private static final String MATCH = "Passwords match";
    private static final String MISMATCH = "Passwords don't match";

    private static SelenideElement revealFor(String id) {
        $$("button[aria-controls='" + id + "']").shouldHave(size(1));
        return $("button[aria-controls='" + id + "']");
    }

    @Test
    void revealShowsWhatTheFieldHolds() {
        openPath(LOGIN_PATH);
        $("#j_password").setValue("not-the-real-one");
        SelenideElement button = revealFor("j_password");
        button.shouldHave(exactText(SHOW), attribute("aria-pressed", "false"));
        $("#j_password").shouldHave(attribute("type", "password"));

        button.click();
        $("#j_password").shouldHave(attribute("type", "text"), value("not-the-real-one"));
        button.shouldHave(exactText(HIDE), attribute("aria-pressed", "true"));

        button.click();
        $("#j_password").shouldHave(attribute("type", "password"));
        button.shouldHave(exactText(SHOW), attribute("aria-pressed", "false"));

        BrowserHealth.current().settle();
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    /**
     * Submitted by pressing Enter, as people do. A later listener cancels the
     * submission and records the field's type as the browser would send it.
     */
    @Test
    void aSubmittedFormNeverCarriesARevealedField() {
        openPath(LOGIN_PATH);
        $("#j_username").setValue("nobody");
        $("#j_password").setValue("whatever");
        revealFor("j_password").click();
        $("#j_password").shouldHave(attribute("type", "text"));

        executeJavaScript("window.addEventListener('submit', function (e) {"
                + " e.preventDefault();"
                + " document.body.setAttribute('data-type-at-submit',"
                + "   document.getElementById('j_password').type); });");
        $("#j_password").sendKeys(Keys.ENTER);

        $("body").shouldHave(attribute("data-type-at-submit", "password"));
        revealFor("j_password").shouldHave(exactText(SHOW), attribute("aria-pressed", "false"));
    }

    /**
     * Back/forward cache: a page left with the password showing would come
     * back showing it. pagehide is what the browser fires on the way out.
     */
    @Test
    void leavingThePageHidesARevealedPassword() {
        openPath(LOGIN_PATH);
        $("#j_password").setValue("whatever");
        revealFor("j_password").click();
        $("#j_password").shouldHave(attribute("type", "text"));

        executeJavaScript("window.dispatchEvent(new PageTransitionEvent('pagehide', {persisted: true}))");

        $("#j_password").shouldHave(attribute("type", "password"));
        revealFor("j_password").shouldHave(exactText(SHOW), attribute("aria-pressed", "false"));
    }

    /** The field's box and the button's, as [top, bottom, left, width] in CSS pixels. */
    private static List<Double> box(String selector) {
        List<Object> raw = executeJavaScript("var r = document.querySelector(arguments[0]).getBoundingClientRect();"
                + " return [r.top, r.bottom, r.left, r.width];", selector);
        return raw.stream().map(n -> ((Number) n).doubleValue()).toList();
    }

    /**
     * The sign-in form's old styling gave input[type=password] a bottom margin
     * and square top corners, so inside the input group the button hung below
     * the field and the field jumped when it became type=text. The button's
     * width must not change with its label either, or the field resizes.
     */
    @Test
    void revealNeitherMovesNorResizesTheField() {
        openPath(LOGIN_PATH);
        SelenideElement button = revealFor("j_password");
        String reveal = "button[aria-controls='j_password']";
        List<Double> field = box("#j_password");
        List<Double> beside = box(reveal);
        assertEquals(field.get(0), beside.get(0), 0.5, "button top vs field top");
        assertEquals(field.get(1), beside.get(1), 0.5, "button bottom vs field bottom");

        button.click();
        $("#j_password").shouldHave(attribute("type", "text"));
        assertEquals(field, box("#j_password"), "the field moved or resized when revealed");
        assertEquals(beside, box(reveal), "the button moved or resized when its label changed");
    }

    /**
     * With a remembered username the sign-in page puts the cursor in the
     * password box. The button must be added without moving the input, or the
     * focus is lost. (This passed before the script existed: it is a guard on
     * the script, not a test of it.)
     */
    @Test
    void rememberedUsernameStillFocusesThePassword() {
        openPath(LOGIN_PATH);
        executeJavaScript("document.cookie = 'username=' + arguments[0] + '; path=/'", ADMIN_USERNAME);
        openPath(LOGIN_PATH);
        revealFor("j_password");
        assertEquals("j_password", executeJavaScript("return document.activeElement.id"));
    }

    @Test
    void userEditorRevealsTheNewPassword() {
        loginAsAdmin();
        openPath("/pressroom-ui/app/admin/users/new");
        $("#bean_password").setValue("for-someone-else");
        revealFor("bean_password").click();
        $("#bean_password").shouldHave(attribute("type", "text"), value("for-someone-else"));
    }

    @Test
    void profileRevealsAllThreeFields() {
        loginAsAdmin();
        openPath("/pressroom-ui/app/profile");
        for (String id : new String[] {"currentPassword", "passwordText", "passwordConfirm"}) {
            revealFor(id).click();
            $("#" + id).shouldHave(attribute("type", "text"));
        }
    }

    /**
     * The live match line, proven on the profile page. The reset-link page
     * runs the same script on the same markup (see the class javadoc).
     */
    @Test
    void profileSaysWhetherTheNewPasswordsMatch() {
        loginAsAdmin();
        openPath("/pressroom-ui/app/profile");
        $$(".password-match").shouldHave(size(1));
        SelenideElement line = $("#passwordConfirm-match");
        line.shouldHave(attribute("aria-live", "polite"), exactText(""));

        $("#passwordText").setValue("correct-horse-1");
        $("#passwordConfirm").setValue("correct-horse");
        line.shouldHave(exactText(MISMATCH));

        String mismatchColour = line.getCssValue("color");

        $("#passwordConfirm").setValue("correct-horse-1");
        line.shouldHave(exactText(MATCH));
        // The two states must look different, not only read differently.
        assertNotEquals(mismatchColour, line.getCssValue("color"), "match and mismatch share a colour");

        $("#passwordConfirm").setValue("");
        line.shouldHave(exactText(""));

        $("#passwordConfirm").setValue("correct-horse-1");
        $("#passwordText").setValue("other");
        line.shouldHave(exactText(MISMATCH));
    }
}
