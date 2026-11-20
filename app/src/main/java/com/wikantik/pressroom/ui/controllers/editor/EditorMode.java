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
package com.wikantik.pressroom.ui.controllers.editor;

/**
 * The two ways the entry editor saves: a new entry, or an existing one. What
 * differs between them lives here so the save path does not branch on the
 * action's name.
 */
enum EditorMode {
    ADD("entryAdd", "weblogEdit.title.newEntry", false, true),
    EDIT("entryEdit", "weblogEdit.title.editEntry", true, false);

    private final String actionName;
    private final String titleKey;
    private final boolean rendersAfterSave;
    private final boolean clearsStatusOnFailure;

    EditorMode(String actionName, String titleKey, boolean rendersAfterSave, boolean clearsStatusOnFailure) {
        this.actionName = actionName;
        this.titleKey = titleKey;
        this.rendersAfterSave = rendersAfterSave;
        this.clearsStatusOnFailure = clearsStatusOnFailure;
    }

    /** The Screen action name (menu highlight, route marker). */
    String actionName() {
        return actionName;
    }

    String titleKey() {
        return titleKey;
    }

    /** True: redisplay the editor after a save; false: redirect to the first-save landing page. */
    boolean rendersAfterSave() {
        return rendersAfterSave;
    }

    /** A refused first save must not redisplay the status the refused save chose. */
    boolean clearsStatusOnFailure() {
        return clearsStatusOnFailure;
    }
}
