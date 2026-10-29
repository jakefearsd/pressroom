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

import com.codeborne.selenide.SelenideElement;

import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.executeJavaScript;

/**
 * The one place the browser suite names the Markdown editor's DOM. Every
 * read and write goes through the page's own seam functions
 * (rollerGetEntryText / rollerSetEntryText), never the editor's internals,
 * so replacing the editor again is a change to this class only.
 *
 * <p>It exists because the previous swap was not like that: 25 IT classes
 * each declared their own copy of the old editor's root selector, so
 * retiring that editor meant editing 25 files with no business knowing what
 * the editor is made of.
 */
public final class Editor {

    /** The editor's root element; visible once the bundle has mounted. */
    public static final String ROOT = ".roller-editor .cm-editor";
    public static final String CONTENT = ".roller-editor .cm-content";
    public static final String TOOLBAR = "#editorToolbar";
    public static final String MODE_SPLIT = "#editorMode button[data-mode='split']";
    public static final String MODE_PREVIEW = "#editorMode button[data-mode='preview']";
    public static final String MODE_WRITE = "#editorMode button[data-mode='write']";
    public static final String PREVIEW_PANE = "#editorPreviewPane";
    public static final String PREVIEW_FRAME = "#editorPreviewPane iframe";

    /** The toolbar control that opens the media chooser. */
    public static final String MEDIA_INSERT = "#editorToolbar button[data-cmd='image']";

    private Editor() { }

    public static SelenideElement root() {
        return $(ROOT).shouldBe(visible);
    }

    public static void setText(String markdown) {
        root();
        executeJavaScript("rollerSetEntryText(arguments[0]);", markdown);
    }

    public static String getText() {
        root();
        return executeJavaScript("return rollerGetEntryText();");
    }

    /** Types through the keyboard, for tests that need real input events. */
    public static void type(String text) {
        $(CONTENT).shouldBe(visible).click();
        $(CONTENT).sendKeys(text);
    }
}
