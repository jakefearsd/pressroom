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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.util.cache.CacheManager;

/**
 * The single deletion seam for a {@code WeblogEntry}, extracted from {@link
 * BaseController} (Task 10) the same way {@link EntryFieldRules} and {@link
 * WeblogOwnership} were extracted (Tasks 6/7) -- so the automation API can
 * call it directly without inheriting {@code BaseController}'s JSP-only
 * state ({@code MessageSource}, {@code Model}, flash-message helpers).
 *
 * <p>Both {@code BaseController} and the API's write controller delegate
 * here rather than each reimplementing the index/cache steps; see the
 * methods' javadoc for why those steps are not optional now that a trashed
 * or permanently-deleted entry's row does not simply vanish the way it used
 * to before soft delete existed.
 */
public final class EntryDeletion {

    private static final Logger log = LoggerFactory.getLogger(EntryDeletion.class);

    private EntryDeletion() {
    }

    /**
     * Moves an entry to the trash, taking it out of everything that indexes
     * or caches it. This is the authoring UI's (and now the API's) single
     * deletion seam -- callers should never invoke {@code
     * WeblogEntryManager#trashWeblogEntry} directly.
     *
     * <p>The order matters and the steps are not optional, and staying
     * TRASHED rather than being removed from the database does not make them
     * unnecessary -- the opposite: the search index holds documents keyed by
     * entry id, so a TRASHED entry left in it is still findable by site
     * search and still links to a page that now 404s, exactly the failure a
     * genuine delete would produce. {@code CacheManager.invalidate} then
     * clears the rendered pages that contained it.
     *
     * <p>Indexing failures are logged and swallowed rather than aborting the
     * trash: an index that has fallen behind is repairable from the admin
     * screen, whereas a half-trashed entry is not.
     */
    public static void trashEntryWithIndex(Weblogger weblogger, WeblogEntry entry) throws WebloggerException {
        deIndexAndInvalidate(weblogger, entry);
        weblogger.getWeblogEntryManager().trashWeblogEntry(entry);
    }

    /**
     * Permanently deletes an entry -- the "delete forever" action on an
     * already-trashed entry -- taking it out of everything that indexes or
     * caches it first.
     *
     * <p>Same index/cache steps as {@link #trashEntryWithIndex} and for the
     * same reason (see its javadoc); the only difference is the final call,
     * which here is the actual, irreversible {@code
     * WeblogEntryManager#removeWeblogEntry} rather than a trash. A trashed
     * entry is normally already out of the index -- it was de-indexed on the
     * way into the trash -- but this re-runs the same dance rather than
     * assuming that, since nothing prevents a caller from reaching this on an
     * entry that never went through the trash path.
     */
    public static void deleteEntryForeverWithIndex(Weblogger weblogger, WeblogEntry entry) throws WebloggerException {
        deIndexAndInvalidate(weblogger, entry);
        weblogger.getWeblogEntryManager().removeWeblogEntry(entry);
    }

    /**
     * The shared index/cache step {@link #trashEntryWithIndex} and
     * {@link #deleteEntryForeverWithIndex} both need before their differing
     * final step: take the entry out of the search index, then invalidate
     * the render cache.
     *
     * <p>This used to flip the entry's in-memory status to DRAFT and hand it
     * to a re-index operation on the theory that a re-index would teach the
     * index to drop it. That never worked: the re-index runs on a background
     * thread and re-fetches the entry from the database by id before doing
     * anything, so the caller's in-memory flip was discarded before the job
     * ever ran, and the entry went right back into the index moments after
     * this method returned -- a TRASHED entry, findable again by site
     * search, linking to a page that 404s. The honest operation here is
     * "remove this document from the index", not "re-index it as a draft and
     * hope", so this calls {@code IndexManager#removeEntryIndexOperation}
     * directly and unconditionally. It runs synchronously (in the
     * foreground, not scheduled on a background thread) and is safe to call
     * on an entry that was never published -- deleting a document that was
     * never indexed is a no-op.
     */
    private static void deIndexAndInvalidate(Weblogger weblogger, WeblogEntry entry) {
        try {
            weblogger.getIndexManager().removeEntryIndexOperation(entry);
        } catch (WebloggerException ex) {
            log.warn("Trouble removing entry from the search index for {}", entry.getId(), ex);
        }

        CacheManager.invalidate(entry);
    }
}
