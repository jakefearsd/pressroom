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

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPageRevision;
import com.wikantik.pressroom.ui.controllers.WeblogOwnership;

/** The page editor's revisions, bound to a page already resolved through lookupPage. */
final class PageRevisions implements RevisionActions.Revisions<WeblogPageRevision> {

    private final Weblogger weblogger;
    private final WeblogPage page;

    PageRevisions(Weblogger weblogger, WeblogPage page) {
        this.weblogger = weblogger;
        this.page = page;
    }

    @Override
    public WeblogPageRevision find(String revisionId) {
        return WeblogOwnership.pageRevision(weblogger, revisionId, page);
    }

    @Override
    public void restore(WeblogPageRevision revision, User editor) throws WebloggerException {
        weblogger.getWeblogPageManager().restoreRevision(page, revision, editor);
        weblogger.flush();
    }

    @Override
    public void setKept(WeblogPageRevision revision, boolean kept) throws WebloggerException {
        weblogger.getWeblogPageManager().setRevisionKept(revision, kept);
        weblogger.flush();
    }

    @Override
    public void afterRestore() throws WebloggerException {
        // Pages have no index or cache step here: the manager expires the render.
    }

    @Override
    public String restoredKey() {
        return "pageEdit.revisionRestored";
    }

    @Override
    public String notFoundKey() {
        return "pageEdit.notFound";
    }
}
