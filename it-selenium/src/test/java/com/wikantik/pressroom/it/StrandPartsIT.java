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

import java.util.List;

import com.wikantik.pressroom.it.support.AdminApp;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.CollectionCondition.exactTexts;
import static com.codeborne.selenide.Condition.checked;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.hidden;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page editor's strand Parts panel in a real browser: the switch shows
 * the panel, the "Add posts" control stamps rows, the row buttons move and
 * remove them, and the order the rows are in when the form is saved is the
 * order a reader sees and the editor shows again.
 *
 * <p>pressroom-strand-parts.js is otherwise covered only by source scans; the
 * server side (merging, refusals) is PageEditControllerStrandTest's. Buttons
 * only: drag-and-drop is a shortcut for them and is not driven here.
 *
 * <p>Owns a weblog of its own on {@code journal}, so the candidate list holds
 * exactly the three posts written here; it creates a weblog, so it holds
 * {@code GLOBAL_CONFIG} read, as {@code PageIT} does. Everything it makes
 * carries a per-run nonce and goes with the run's database container.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class StrandPartsIT extends PressroomIT {

    private static final String ROWS = "#page_strand_parts .strand-parts-list .strand-part";

    @Test
    void thePartsPanelAddsMovesAndRemovesRowsAndTheSavedOrderIsTheReadersOrder() {
        loginAsAdmin();
        String handle = createWeblog();
        String n = nonce();
        String alpha = "Alpha " + n;
        String beta = "Beta " + n;
        String gamma = "Gamma " + n;
        for (String title : List.of(alpha, beta, gamma)) {
            publishEntryOn(handle, title);
        }

        // --- a new page, ticked as a strand: the panel appears ---------------
        openPath("/pressroom-ui/authoring/pageEdit.rol?weblog=" + handle);
        $("#pageEditForm").should(exist);
        $("#page_strand_parts").shouldBe(hidden);
        $("#page_bean_slug").setValue("tale");
        $("#page_bean_title").setValue("The Tale");
        $("#page_bean_status").selectOptionByValue("PUBLISHED");
        $("#page_bean_strand").click();
        $("#page_strand_parts").shouldBe(visible);

        // --- add three posts with the picker ---------------------------------
        for (String title : List.of(alpha, beta, gamma)) {
            $("#strandAddSelect").selectOption(title);
        }
        $$(ROWS + " .strand-part-title").shouldHave(exactTexts(alpha, beta, gamma));
        assertFalse($("#strandAddSelect").getText().contains(alpha), "a chosen post leaves the picker");

        // --- move Gamma up one, then remove Alpha ----------------------------
        $$(ROWS).findBy(text(gamma)).$(".strand-part-up").click();
        $$(ROWS + " .strand-part-title").shouldHave(exactTexts(alpha, gamma, beta));
        $$(ROWS).findBy(text(alpha)).$(".strand-part-remove").click();
        $$(ROWS + " .strand-part-title").shouldHave(exactTexts(gamma, beta));
        $("#strandAddSelect").$$("option").findBy(text(alpha)).should(exist);

        clickAndAwaitNewPage($("#pageSaveButton"));
        $("#messages").should(exist);
        String pageId = $("input[name='bean.id']").getValue();
        assertTrue(pageId != null && !pageId.isEmpty(), "the save created the page");

        // --- a reader sees the saved order -----------------------------------
        String body = getAnonymously(baseUrl() + "/" + handle + "/tale");
        int list = body.indexOf("<ol class=\"strand-parts\"");
        assertTrue(list > 0, "the strand page lists its parts: " + body);
        String parts = body.substring(list, body.indexOf("</ol>", list));
        assertTrue(parts.indexOf(gamma) >= 0 && parts.indexOf(gamma) < parts.indexOf(beta),
                "Gamma, then Beta: " + parts);
        assertFalse(parts.contains(alpha), "the removed post is not a part: " + parts);
        assertEquals(2, parts.split("<li class=\"strand-part\"", -1).length - 1, parts);

        // --- the editor shows the rows again ---------------------------------
        openPath("/pressroom-ui/authoring/pageEdit.rol?weblog=" + handle + "&id=" + pageId);
        $("#page_bean_strand").shouldBe(checked);
        $("#page_strand_parts").shouldBe(visible);
        $$(ROWS + " .strand-part-title").shouldHave(exactTexts(gamma, beta));
    }

    /** A weblog of this test's own on {@code journal}, owned by the admin. */
    private String createWeblog() {
        String handle = "strandit" + nonce();
        openPath("/pressroom-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Strands " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("journal");
        clickAndAwaitNewPage($("button[type='submit']"));
        $("#messages").should(exist);
        return handle;
    }

    private void publishEntryOn(String handle, String title) {
        openPath(AdminApp.newEntry(handle));
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText("Body of " + title + ".");
        AdminApp.publishButton().click();
        $("#entry_bean_permalink").should(exist);
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
