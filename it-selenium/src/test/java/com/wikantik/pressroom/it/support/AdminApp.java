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
package com.wikantik.pressroom.it.support;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.codeborne.selenide.WebDriverRunner;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;

/**
 * The one place the browser suite names the React admin's entry screens: their routes and the handful of
 * controls every journey drives (publish, save draft, the entry's id, a list row, a confirmation dialog).
 * The screens keep the ids of the JSP pages they replaced ({@code #entry}, {@code #entry_bean_title},
 * {@code #entry_bean_permalink}, {@code #messages}); what they do not keep, such as the hidden
 * {@code bean.id} field and the per-row form buttons, is read from the URL and the row instead.
 *
 * <p>The admin is a single page: a save is not a navigation, so a journey waits on what the save changes
 * (the permalink appears, the status pill changes, the route names the new entry), never on the page being
 * replaced.
 */
public final class AdminApp {

    private static final String APP = "/pressroom-ui/app";
    private static final Pattern SAVED_ENTRY = Pattern.compile("/entries/(?!new(?:[/?#]|$))([^/?#]+)");

    /** The publish rail's status pill: Draft, Published, Pending, Scheduled, or Unsaved before a first save. */
    public static final String STATUS_PILL = ".editor-statusrow .status-pill";
    /** The permalink the editor shows only once the entry is published. */
    public static final String PERMALINK = "#entry_bean_permalink";
    public static final String TITLE = "#entry_bean_title";

    private AdminApp() { }

    // ---------------------------------------------------------------- routes

    public static String weblog(String handle, String tail) {
        return APP + "/weblogs/" + handle + tail;
    }

    public static String newEntry(String handle) {
        return weblog(handle, "/entries/new");
    }

    public static String entries(String handle) {
        return weblog(handle, "/entries");
    }

    public static String entry(String handle, String id) {
        return weblog(handle, "/entries/" + id);
    }

    public static String trash(String handle) {
        return weblog(handle, "/trash");
    }

    public static String comments(String handle) {
        return weblog(handle, "/comments");
    }

    public static String categories(String handle) {
        return weblog(handle, "/categories");
    }

    // ---------------------------------------------------------------- the editor

    /** The editor is up: its form, with the writing surface mounted. */
    public static void awaitEditor() {
        $("#entry").should(exist);
        $(Editor.ROOT).shouldBe(visible);
    }

    public static SelenideElement titleField() {
        return $(TITLE);
    }

    /** Publish, Update or Submit for review: the rail's primary button. */
    public static SelenideElement publishButton() {
        return $(".editor-btnrow .btn-primary");
    }

    /** Save draft or Unpublish: the rail's secondary button. */
    public static SelenideElement saveDraftButton() {
        return $(".editor-btnrow .btn-secondary");
    }

    /** Fills the title and the text of the open editor. */
    public static void write(String title, String body) {
        awaitEditor();
        titleField().setValue(title);
        Editor.setText(body);
    }

    /**
     * The id of the entry the editor is open on, read from the route. A new entry has none until its first
     * save replaces {@code /entries/new} with {@code /entries/<id>}, so this waits for that.
     */
    public static String savedEntryId() {
        String[] found = new String[1];
        Selenide.Wait().withTimeout(Duration.ofSeconds(10))
                .withMessage("the editor never moved to a saved entry's route; it is at " + WebDriverRunner.url())
                .until(driver -> {
                    Matcher match = SAVED_ENTRY.matcher(driver.getCurrentUrl());
                    if (match.find()) {
                        found[0] = match.group(1);
                        return true;
                    }
                    return false;
                });
        return found[0];
    }

    /** Waits for a save to land: the success banner is up. */
    public static void awaitSavedBanner() {
        $("#messages").should(exist);
    }

    /** Waits for the editor's status pill to read this. */
    public static void awaitStatus(String label) {
        $(STATUS_PILL).shouldHave(text(label));
    }

    // ---------------------------------------------------------------- lists

    /** The list or trash row whose title cell holds this text. */
    public static SelenideElement row(String title) {
        return $$("table.pressroomtable tbody tr").findBy(text(title));
    }

    /** The confirmation dialog the React screens open in place of a native confirm. */
    public static SelenideElement dialog() {
        return $("[role='dialog']");
    }

    /** Confirms the open dialog (its destructive button, the second of two). */
    public static void confirmDialog() {
        dialog().shouldBe(visible).$(".modal-footer .btn:last-child").click();
    }
}
