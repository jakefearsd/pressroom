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

import java.time.Duration;

import com.codeborne.selenide.SelenideElement;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.switchTo;

/**
 * The editor's preview pane, driven the way an author drives it.
 *
 * <p>This exists because the preview shipped broken and nothing noticed: the
 * endpoint's {@code @RequestParam}s were unnamed, the build does not pass
 * {@code -parameters}, and so every real request threw while the unit tests --
 * which call the controller method directly, with arguments already bound --
 * carried on passing. {@code ControllerMetadataTest} now fails on an unnamed
 * parameter; this fails if the preview stops rendering for any other reason.
 *
 * <p>It also pins the thing that makes a server-rendered preview worth having:
 * shortcodes expand here exactly as they will on the published page, which no
 * in-browser Markdown library could do.
 *
 * <p>Both tests read the rendered fragment <em>inside the iframe</em>. The
 * preview is not a bare HTML dump into a div any more: it is the weblog's own
 * theme shell, framed, fed over {@code postMessage}. Reading it from the
 * parent document would find nothing at all, which is the point -- the iframe
 * is the feature.
 */
class MarkdownPreviewIT extends PressroomIT {

    private static final String ENTRY_ADD = "/pressroom-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;

    /** The mode control's Preview radio, and the frame it reveals. */
    private static final String PREVIEW_BUTTON = Editor.MODE_PREVIEW;
    private static final String PREVIEW_FRAME = Editor.PREVIEW_FRAME;

    /**
     * The shell renders through the theme, so the article carries the theme's
     * own prose class -- the fixture weblog is on journal.
     */
    private static final String PREVIEW_ARTICLE = "#previewArticle.qj-prose";

    /** The push is debounced by 400ms and then round-trips to the server. */
    private static final Duration RENDER = Duration.ofSeconds(5);

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    /**
     * Opens the add-entry screen with the editor mounted and the write pane
     * showing.
     *
     * <p>The mode control persists in {@code localStorage} and the browser
     * lives for the whole class, so the previous test's Preview click is
     * still in force when the next one opens the page -- and in preview mode
     * {@code .editor-write} is {@code display:none}, where
     * {@code Editor.setText} would wait forever for a pane that is never
     * shown. Waiting for the editor to exist first is what makes the reset
     * click land on a page whose handlers are bound.
     */
    private void openEditorInWriteMode() {
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $(Editor.ROOT).should(exist);
        $(Editor.MODE_WRITE).click();
        $(Editor.ROOT).shouldBe(visible);
    }

    @Test
    void thePreviewRendersMarkdownThroughTheServer() {
        String suffix = Long.toString(System.nanoTime(), 36);

        openEditorInWriteMode();
        $("[name='bean.title']").setValue("IT Preview " + suffix);
        Editor.setText("## Heading " + suffix + "\n\nA **bold** word.\n");

        $(PREVIEW_BUTTON).should(visible).click();

        switchTo().frame($(PREVIEW_FRAME).shouldBe(visible));
        // Markdown became HTML, which only the server does.
        $(PREVIEW_ARTICLE).shouldHave(text("Heading " + suffix), RENDER);
        $(PREVIEW_ARTICLE + " h2").should(exist);
        $(PREVIEW_ARTICLE + " strong").shouldHave(text("bold"));
        switchTo().defaultContent();
    }

    /**
     * Split mode is the theme, live: the frame carries the weblog's own
     * stylesheet, and typing in the left pane reaches the right one without
     * anybody clicking anything.
     */
    @Test
    void theSplitPaneIsTheThemeAndFollowsTyping() {
        openEditorInWriteMode();
        Editor.setText("## Field notes\n\nSome *emphasis*.");

        $(Editor.MODE_SPLIT).should(visible).click();
        SelenideElement frame = $(PREVIEW_FRAME).shouldBe(visible);
        switchTo().frame(frame);
        $("link[rel='stylesheet'][href*='journal']").should(exist);
        $(PREVIEW_ARTICLE + " h2").shouldHave(text("Field notes"), RENDER);
        switchTo().defaultContent();

        Editor.type("\n\nA new paragraph.");
        switchTo().frame($(PREVIEW_FRAME));
        $(PREVIEW_ARTICLE + " p").shouldHave(text("A new paragraph."), RENDER);
        switchTo().defaultContent();
    }
}
