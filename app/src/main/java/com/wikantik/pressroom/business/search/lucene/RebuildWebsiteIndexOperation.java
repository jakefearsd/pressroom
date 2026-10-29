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
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import com.wikantik.pressroom.util.PressroomConstants;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;

/**
 * An index operation that rebuilds a given users index (or all indexes).
 * 
 * @author Mindaugas Idzelis (min@idzelis.com)
 */
public class RebuildWebsiteIndexOperation extends WriteToIndexOperation {

    // ~ Static fields/initializers
    // =============================================

    private static final Logger log = LoggerFactory.getLogger(
            RebuildWebsiteIndexOperation.class);

    // ~ Instance fields
    // ========================================================

    private Weblog website;
    private Weblogger roller;

    // ~ Constructors
    // ===========================================================

    /**
     * Create a new operation that will recreate an index.
     * 
     * @param website
     *            The website to rebuild the index for, or null for all users.
     */
    public RebuildWebsiteIndexOperation(Weblogger roller, LuceneIndexManager mgr,
            Weblog website) {
        super(mgr);
        this.roller = roller;
        this.website = website;
    }

    // ~ Methods
    // ================================================================

    // The IndexWriter returned by beginWriting() is the base class's own
    // `writer` field; endWriting() in the finally below closes that same
    // field. PMD's CloseResource can't see that a close happening inside a
    // different method (via the field alias) satisfies this local variable.
    @SuppressWarnings("PMD.CloseResource")
    @Override
    protected void doRun() {

        Date start = new Date();

        // since this operation can be run on a separate thread we must treat
        // the weblog object passed in as a detached object which is proned to
        // lazy initialization problems, so requery for the object now
        if (this.website != null) {
            log.debug("Reindexining weblog {}", website.getHandle());
            try {
                this.website = roller.getWeblogManager().getWeblog(
                        this.website.getId());
            } catch (WebloggerException ex) {
                log.error("Error getting website object", ex);
                return;
            }
        } else {
            log.debug("Reindexining entire site");
        }

        IndexWriter writer = beginWriting();
        boolean succeeded = false;

        try {
            if (writer != null) {

                // Fetch the entries to re-add. This runs only once the
                // writer is open -- and so only once WriteToIndexOperation's
                // write lock is held -- so a concurrent AddEntryOperation for
                // a newly published entry cannot slip in between this fetch
                // and the delete below and be silently dropped by the
                // re-add. If the query fails, the catch below rolls back the
                // delete too: a rebuild that deletes first and only then
                // discovers the query failed would otherwise leave the
                // weblog with no search results until the next successful
                // rebuild.
                WeblogEntryManager weblogManager = roller.getWeblogEntryManager();
                WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
                wesc.setWeblog(website);
                wesc.setStatus(PubStatus.PUBLISHED);
                List<WeblogEntry> entries = weblogManager.getWeblogEntries(wesc);

                log.debug("Entries to index: {}", entries.size());

                // Delete Doc
                Term tWebsite = null;
                if (website != null) {
                    tWebsite = IndexUtil.getTerm(FieldConstants.WEBSITE_HANDLE,
                            website.getHandle());
                }
                if (tWebsite != null) {
                    writer.deleteDocuments(tWebsite);
                } else {
                    Term all = IndexUtil.getTerm(FieldConstants.CONSTANT,
                            FieldConstants.CONSTANT_V);
                    writer.deleteDocuments(all);
                }

                // Add Doc
                for (WeblogEntry entry : entries) {
                    writer.addDocument(getDocument(entry));
                    log.debug("Indexed entry {}: {}", entry.getPubTime(), entry.getAnchor());
                }
            }
            succeeded = true;
        } catch (Exception e) {
            log.error("ERROR adding/deleting doc to index; rolling back rebuild", e);
        } finally {
            // A rebuild is all-or-nothing: on any failure after the writer is
            // open, roll back rather than let endWriting()'s close() commit a
            // partial delete/add. IndexWriter.rollback() itself closes the
            // writer, and closing an already-closed writer is a documented
            // no-op, so endWriting() below still safely releases the
            // analyzer and (via the write lock in WriteToIndexOperation) the
            // index is never left half-written.
            if (writer != null && !succeeded) {
                try {
                    writer.rollback();
                } catch (IOException ioe) {
                    log.error("ERROR rolling back index writer", ioe);
                }
            }
            endWriting();
            if (roller != null) {
                roller.release();
            }
        }

        Date end = new Date();
        double length = (end.getTime() - start.getTime()) / (double) PressroomConstants.SEC_IN_MS;

        if (website == null) {
            log.info("Completed rebuilding index for all users in '{}' secs", length);
        } else {
            log.info("Completed rebuilding index for website handle: '{}' in '{}' seconds",
                    website.getHandle(), length);
        }
    }
}
