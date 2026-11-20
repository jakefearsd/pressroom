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
package com.wikantik.pressroom.business.search.lucene;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.WeblogPage;

/**
 * Brings one page's document up to date: deleted, then added back when the
 * page is PUBLISHED and not {@code noindex}.
 *
 * <p>Runs on another thread, after the save that asked for it committed, so
 * the page is re-read by id here: a page deleted, unpublished or set noindex
 * since the job was queued ends up out of the index, whatever the caller saw.
 */
public class ReIndexPageOperation extends WriteToIndexOperation {

    private static final Logger log = LoggerFactory.getLogger(ReIndexPageOperation.class);

    private final Weblogger pressroom;
    private final String pageId;
    private WeblogPage page;

    public ReIndexPageOperation(Weblogger pressroom, LuceneIndexManager mgr, String pageId) {
        super(mgr, pressroom);
        this.pressroom = pressroom;
        this.pageId = pageId;
    }

    @Override
    protected boolean prepare() {
        try {
            page = pressroom.getWeblogPageManager().getPage(pageId);
            return true;
        } catch (WebloggerException ex) {
            // nothing is known about the page: its document stays as it is
            log.error("Could not read page {} to reindex it", pageId, ex);
            return false;
        }
    }

    @Override
    protected void write(IndexWriter writer) throws IOException {
        writer.deleteDocuments(IndexUtil.document(FieldConstants.KIND_PAGE, pageId));
        Document doc = IndexDocuments.isIndexable(page) ? documentOrNull(page) : null;
        if (doc != null) {
            writer.addDocument(doc);
        }
    }

    /** The page's document, or null (logged) when it cannot be built: the page is then left out, as in a rebuild. */
    private Document documentOrNull(WeblogPage page) {
        try {
            return IndexDocuments.page(manager, page);
        } catch (RuntimeException e) {
            log.warn("Could not index page {}; it is left out of the index", pageId, e);
            return null;
        }
    }
}
