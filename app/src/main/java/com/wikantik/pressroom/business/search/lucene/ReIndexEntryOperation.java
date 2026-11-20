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
/* Created on Jul 16, 2003 */
package com.wikantik.pressroom.business.search.lucene;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.index.IndexWriter;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.WeblogEntry;

/**
 * An operation that adds a new log entry into the index.
 * 
 * @author Mindaugas Idzelis (min@idzelis.com)
 */
public class ReIndexEntryOperation extends WriteToIndexOperation {

    // ~ Static fields/initializers
    // =============================================

    private static final Logger log = LoggerFactory.getLogger(
            ReIndexEntryOperation.class);

    // ~ Instance fields
    // ========================================================

    private WeblogEntry data;
    private String entryId;
    private final Weblogger pressroom;

    // ~ Constructors
    // ===========================================================

    /**
     * Adds a web log entry into the index.
     */
    public ReIndexEntryOperation(Weblogger pressroom, LuceneIndexManager mgr,
            WeblogEntry data) {
        super(mgr, pressroom);
        this.pressroom = pressroom;
        this.data = data;
    }

    // ~ Methods
    // ================================================================

    @Override
    protected boolean prepare() {
        if (pressroom == null) {
            log.error("Weblogger unavailable; cannot re-index weblog entry");
            return false;
        }
        // since this operation can be run on a separate thread we must treat
        // the weblog object passed in as a detached object which is prone to
        // lazy initialization problems, so requery for the object now.
        // The id is captured first because a genuinely deleted entry
        // re-fetches as null, and the delete-by-id still needs the id.
        entryId = this.data.getId();
        try {
            WeblogEntryManager wMgr = pressroom.getWeblogEntryManager();
            this.data = wMgr.getWeblogEntry(entryId);
        } catch (WebloggerException ex) {
            log.error("Error getting weblogentry object", ex);
            return false;
        }
        return true;
    }

    @Override
    protected void write(IndexWriter writer) throws IOException {
        // Delete Doc -- always, whether or not it comes back below. A
        // re-fetch that returns null (entry deleted out from under this
        // queued job) or a non-PUBLISHED entry (e.g. trashed since this job
        // was queued) must not leave a stale document behind.
        writer.deleteDocuments(IndexUtil.document(FieldConstants.KIND_ENTRY, entryId));

        // Add Doc -- only a published entry belongs in the search index; by
        // the time this runs on its own thread the entry may have moved on,
        // so the check is enforced here rather than trusted from the caller.
        if (data != null && data.isPublished()) {
            writer.addDocument(IndexDocuments.entry(manager, data));
        }
    }
}
