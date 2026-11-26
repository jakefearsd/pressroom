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

import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;

import com.codeborne.selenide.SelenideElement;

/**
 * The browser suite's names for the React admin's pages and media screens: their routes and the few controls
 * the journeys share. The screens keep the ids of the JSPs they replaced ({@code #pageEditForm},
 * {@code #page_bean_*}, {@code #pageSaveButton}, {@code #mediaDropZone}, {@code #uploadButton}); what they do
 * not keep is the full page load after a save, so a journey waits on what the save changes (the editor's
 * status reads "Saved", the route names the page), never on the document being replaced.
 */
public final class PagesMediaApp {

    private static final String APP = "/pressroom-ui/app/weblogs/";

    private PagesMediaApp() {
    }

    public static String pages(String handle) {
        return APP + handle + "/pages";
    }

    public static String newPage(String handle) {
        return pages(handle) + "/new";
    }

    public static String pagesTrash(String handle) {
        return pages(handle) + "/trash";
    }

    public static String media(String handle) {
        return APP + handle + "/media";
    }

    public static String mediaUpload(String handle) {
        return media(handle) + "/upload";
    }

    /** The page editor is up with its writing surface mounted. */
    public static void awaitPageEditor() {
        $("#pageEditForm").should(exist);
        $(Editor.ROOT).shouldBe(visible);
    }

    /** The page list (its table, or the empty invitation) has loaded and rendered. */
    public static void awaitPagesList() {
        $("#pages-list-marker table, #pages-list-marker .empty-state").should(exist);
    }

    /**
     * Saves the open page and waits for it to land: the editor's status reads "Saved" again (it reads "Unsaved
     * changes" or "Not saved" until then; a banner alone cannot tell this save from the last).
     */
    public static void savePage() {
        $("#pageSaveButton").click();
        $("#editorStatusSave").shouldHave(exactText("Saved"));
        $("#messages").should(exist);
    }

    /** The open confirmation dialog (the React screens' replacement for a native confirm). */
    public static SelenideElement dialog() {
        return $("[role='dialog']");
    }

    /** Confirms the open dialog with its destructive (second) button. */
    public static void confirmDialog() {
        dialog().shouldBe(visible).$(".modal-footer .btn:last-child").click();
    }
}
