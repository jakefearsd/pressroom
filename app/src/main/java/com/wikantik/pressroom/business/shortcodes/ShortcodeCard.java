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
package com.wikantik.pressroom.business.shortcodes;

/**
 * What the entry editor offers an author for one shortcode: a label and the
 * text to drop at the cursor.
 *
 * <p>The card belongs to the {@link ShortcodeHandler} rather than to the
 * editor page, so the editor's insert menu is generated from the same registry
 * that renders entries. A sixth shortcode therefore cannot ship with a working
 * renderer and no way for an author to discover it -- the compiler asks for
 * the card when the handler is written.
 *
 * @param labelKey         message-bundle key for the menu label. Written as a
 *                         literal at each call site, never assembled from
 *                         {@link #name}: {@code MessageKeyTest} scans source
 *                         text for key references, and a computed key reads to
 *                         it as an unused bundle entry.
 * @param snippet          the exact text inserted at the cursor, in the
 *                         shortcode syntax {@code ShortcodeExpander} parses.
 *                         Empty when {@link #usesMediaChooser} is true. Must
 *                         stay free of {@code <} and {@code &}: it travels to
 *                         the browser inside an HTML attribute, and the plain
 *                         form is what keeps that unambiguous.
 * @param usesMediaChooser whether picking this card opens the media chooser
 *                         instead of inserting {@link #snippet} -- true for
 *                         shortcodes keyed to a specific uploaded file, which
 *                         an author cannot be expected to name by hand.
 */
public record ShortcodeCard(String name, String labelKey, String snippet,
                            boolean usesMediaChooser) {

    /** A card whose snippet is inserted literally at the cursor. */
    public static ShortcodeCard snippet(String name, String labelKey, String snippet) {
        return new ShortcodeCard(name, labelKey, snippet, false);
    }

    /** A card that opens the media chooser, which inserts the shortcode itself. */
    public static ShortcodeCard mediaChooser(String name, String labelKey) {
        return new ShortcodeCard(name, labelKey, "", true);
    }
}
