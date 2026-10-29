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

import java.util.HashSet;
import java.util.Set;

import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.codeborne.selenide.SelenideElement;

import static com.codeborne.selenide.CollectionCondition.exactTexts;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's insert menu, in a real browser.
 *
 * <p>{@code ShortcodeCardTest} proves the cards are well-formed and that the
 * JSP generates the menu from the registry rather than hard-coding it. What it
 * cannot prove is the half that only exists once a browser runs the page: that
 * the Bootstrap dropdown opens, that the click handler is bound, and that the
 * snippet survives the trip through the HTML data attribute into the editor
 * with its quotes and newlines intact -- the failure mode that would leave an
 * author with {@code [gallery dir=&quot;default&quot;]} in their post.
 */
class ShortcodeCardIT extends PressroomIT {

    private static final String ENTRY_ADD = "/roller-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void theInsertMenuOffersEveryShippedShortcode() {
        openEditor();
        $("#shortcodeInsertButton").shouldBe(visible).click();

        // The order is the registry's registration order, which is what an
        // author sees; asserting it exactly means a reordering is a decision
        // rather than an accident.
        $$("#shortcodeInsertMenu .shortcode-card").shouldHave(exactTexts(
                "Image…", "Photo gallery", "Map with pins",
                "Call to action", "Questions and answers", "Video", "Contact form",
                "Subscribe form"));

        // The menu now hangs off a button INSIDE the toolbar, so the toolbar's
        // own overflow decides whether the menu is a menu or a 22px-tall
        // letterbox. `exactTexts` above reads textContent and passes either
        // way; only asking whether the LAST item is actually on screen tells
        // you the dropdown escaped its container.
        $$("#shortcodeInsertMenu .shortcode-card").last().shouldBe(visible);
    }

    @Test
    void insertingAGalleryPutsItsSnippetInTheEditor() {
        openEditor();
        $("#shortcodeInsertButton").shouldBe(visible).click();
        $("#shortcodeInsertMenu .shortcode-card[data-shortcode='gallery']")
                .shouldBe(visible).click();

        String text = Editor.getText();
        assertTrue(text != null && text.contains("[gallery dir=\"default\" row=\"320\"]"),
                "The gallery snippet did not reach the editor intact; it holds: " + text);
    }

    /**
     * The [faq] snippet is the multi-line one: if newlines were lost crossing
     * the data attribute, its [q]/[a] pairs would collapse onto one line and
     * the block would still parse -- so this asserts the shape, not just the
     * presence.
     */
    @Test
    void aMultiLineSnippetKeepsItsLineBreaks() {
        openEditor();
        $("#shortcodeInsertButton").shouldBe(visible).click();
        $("#shortcodeInsertMenu .shortcode-card[data-shortcode='faq']")
                .shouldBe(visible).click();

        String text = Editor.getText();
        assertTrue(text != null && text.contains("[faq]\n[q]"),
                "The FAQ snippet lost its line breaks; it holds: " + text);
    }

    /**
     * The other half of {@code EntryEditorJspGuideTest}: that class pins the
     * guide's shortcode table to a source-level fact (it iterates
     * {@code shortcodeCards} rather than being hand-typed), which cannot see
     * whether the rendered result actually agrees with what the Insert menu
     * -- generated from the same model attribute, but rendered separately --
     * shows in a real browser. Comparing rendered sets closes that gap: a
     * shortcode present in one but not the other would mean either an
     * undocumented shortcode or a guide entry describing something no longer
     * registered.
     */
    @Test
    void theGuideListsEveryRegisteredShortcode() {
        openEditor();
        $("#shortcodeInsertButton").shouldBe(visible).click();
        Set<String> menuShortcodes = dataShortcodesOf("#shortcodeInsertMenu .shortcode-card");

        $("#editorToolbar button[data-cmd='help']").shouldBe(visible).click();
        $("#editorGuide").shouldBe(visible);
        Set<String> guideShortcodes = dataShortcodesOf("#editorGuide [data-shortcode]");

        assertEquals(menuShortcodes, guideShortcodes,
                "the guide must list exactly the shortcodes the Insert menu offers -- "
                        + "neither more (a stale row describing something no longer "
                        + "registered) nor fewer (a shortcode that shipped with no "
                        + "documentation)");
    }

    private static Set<String> dataShortcodesOf(String cssSelector) {
        Set<String> names = new HashSet<>();
        for (SelenideElement el : $$(cssSelector)) {
            names.add(el.getAttribute("data-shortcode"));
        }
        return names;
    }

    private void openEditor() {
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        Editor.root();
    }
}
