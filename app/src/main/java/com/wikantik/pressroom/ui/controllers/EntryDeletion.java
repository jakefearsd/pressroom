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
 * The deletion seam for a {@code WeblogEntry}, shared by {@link
 * BaseController} and the automation API so the API need not inherit
 * {@code BaseController}'s JSP-only state.
 *
 * <p>Taking the entry out of the search index and expiring its rendered pages
 * is not done here: the entry manager publishes an {@code EntryChanged} after
 * the commit and its listeners do both, for every path that trashes, restores,
 * saves or removes an entry. The caller flushes.
 */
public final class EntryDeletion {

    private EntryDeletion() {
    }

    /** Moves an entry to the trash; callers never invoke {@code trashWeblogEntry} directly. */
    public static void trashEntry(Weblogger weblogger, WeblogEntry entry) throws WebloggerException {
        weblogger.getWeblogEntryManager().trashWeblogEntry(entry);
    }

    /** Permanently deletes an entry -- the "delete forever" action on an already-trashed one. */
    public static void deleteEntryForever(Weblogger weblogger, WeblogEntry entry) throws WebloggerException {
        weblogger.getWeblogEntryManager().removeWeblogEntry(entry);
    }
}
