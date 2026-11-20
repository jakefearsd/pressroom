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

import com.wikantik.pressroom.business.RevisionKeepLimitException;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.User;
import org.slf4j.Logger;

/**
 * The keep/restore flow shared by the entry and page editors: find the
 * revision (ownership-checked), act on it through the manager, flush, flash,
 * and report whether the revision existed so the controller can pick the
 * redirect.
 *
 * <p>It is bound, through its {@link Revisions} adapter, to one owner the
 * controller has already resolved through {@code lookupChangeableEntry} or
 * {@code lookupPage}; it never takes an owner id, so it cannot bypass the
 * authorization path.
 *
 * @param <R> the revision type
 */
final class RevisionActions<R> {

    /** What one content type contributes to the flow. */
    interface Revisions<R> {
        /** The revision with this id if it belongs to the bound owner, else null. */
        R find(String revisionId);

        /** Restores, then flushes. */
        void restore(R revision, User editor) throws WebloggerException;

        /** Sets the flag, then flushes. */
        void setKept(R revision, boolean kept) throws WebloggerException;

        /** Side effects after a successful restore and flush (reindex, cache). */
        void afterRestore() throws WebloggerException;

        /** Message key flashed after a restore. */
        String restoredKey();

        /** Message key flashed when the revision is not found. */
        String notFoundKey();
    }

    /** The controller's flash helpers, bound to one request. */
    interface Flash {
        void message(String key);

        void error(String key);
    }

    private final Revisions<R> revisions;
    private final Logger log;
    private final Flash flash;

    RevisionActions(Revisions<R> revisions, Logger log, Flash flash) {
        this.revisions = revisions;
        this.log = log;
        this.flash = flash;
    }

    /** @return false (after flashing not-found) when the revision does not exist */
    boolean restore(String revisionId, User editor) {
        R revision = revisions.find(revisionId);
        if (revision == null) {
            flash.error(revisions.notFoundKey());
            return false;
        }
        try {
            // The manager's restore, not a plain save: a restore is never
            // coalesced, so what it replaces always becomes a revision.
            revisions.restore(revision, editor);
            revisions.afterRestore();
            flash.message(revisions.restoredKey());
        } catch (Exception e) {
            log.error("Error restoring revision {}", revisionId, e);
            flash.error("generic.error.check.logs");
        }
        return true;
    }

    /** @return false (after flashing not-found) when the revision does not exist */
    boolean keep(String revisionId, boolean kept) {
        R revision = revisions.find(revisionId);
        if (revision == null) {
            flash.error(revisions.notFoundKey());
            return false;
        }
        try {
            revisions.setKept(revision, kept);
            flash.message(kept ? "weblogEdit.revisionKept" : "weblogEdit.revisionUnkept");
        } catch (RevisionKeepLimitException e) {
            flash.error("weblogEdit.revisionKeepLimit");
        } catch (Exception e) {
            log.error("Error keeping revision {}", revisionId, e);
            flash.error("generic.error.check.logs");
        }
        return true;
    }
}
