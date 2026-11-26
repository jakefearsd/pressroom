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

package com.wikantik.pressroom.ui.controllers;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.WeblogEntry;

/**
 * Copying an entry into a new draft, shared by the entries list's duplicate action and the automation API so
 * the two cannot drift. The copy is a draft with a fresh id and no anchor of its own (see {@link
 * WeblogEntry#copyAsDraft}), so nothing about the original changes and nothing new appears on the blog.
 *
 * <p>The caller has already vetted the original (it belongs to the action weblog and is not trashed: a forged id
 * naming a trashed entry must not duplicate it back into a live draft, which is what restore is for) and
 * supplies the localised title.
 */
public final class EntryDuplication {

    private EntryDuplication() {
    }

    /** Saves and flushes a draft copy of {@code original} titled {@code title}, and returns it. */
    public static WeblogEntry duplicate(Weblogger weblogger, WeblogEntry original, String title)
            throws WebloggerException {
        WeblogEntry copy = original.copyAsDraft(title);
        weblogger.getWeblogEntryManager().saveWeblogEntry(copy);
        weblogger.flush();
        return copy;
    }
}
