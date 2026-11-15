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

    public ReIndexPageOperation(Weblogger pressroom, LuceneIndexManager mgr, String pageId) {
        super(mgr);
        this.pressroom = pressroom;
        this.pageId = pageId;
    }

    // The IndexWriter returned by beginWriting() is the base class's own
    // `writer` field; endWriting() in the finally below closes that same
    // field. PMD's CloseResource can't see that a close happening inside a
    // different method (via the field alias) satisfies this local variable.
    @SuppressWarnings("PMD.CloseResource")
    @Override
    protected void doRun() {
        try {
            WeblogPage page;
            try {
                page = pressroom.getWeblogPageManager().getPage(pageId);
            } catch (WebloggerException ex) {
                // nothing is known about the page: its document stays as it is
                log.error("Could not read page {} to reindex it", pageId, ex);
                return;
            }
            IndexWriter writer = beginWriting();
            try {
                if (writer != null) {
                    writer.deleteDocuments(IndexUtil.document(FieldConstants.KIND_PAGE, pageId));
                    if (isIndexable(page)) {
                        writer.addDocument(getDocument(page));
                    }
                }
            } catch (IOException e) {
                log.error("Problems reindexing page {}", pageId, e);
            } finally {
                endWriting();
            }
        } finally {
            pressroom.release();
        }
    }
}
