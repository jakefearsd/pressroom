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
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.WeblogEntry;

/**
 * An operation that adds a new log entry into the index.
 * @author Mindaugas Idzelis  (min@idzelis.com)
 */
public class AddEntryOperation extends WriteToIndexOperation {
    
    //~ Static fields/initializers =============================================
    
    private static final Logger log =
            LoggerFactory.getLogger(AddEntryOperation.class);
    
    //~ Instance fields ========================================================
    
    private WeblogEntry data;
    private Weblogger roller;
    
    //~ Constructors ===========================================================
    
    /**
     * Adds a web log entry into the index.
     */
    public AddEntryOperation(Weblogger roller, LuceneIndexManager mgr, WeblogEntry data) {
        super(mgr);
        this.roller = roller;
        this.data = data;
    }
    
    //~ Methods ================================================================
    
    // The IndexWriter returned by beginWriting() is the base class's own
    // `writer` field; endWriting() in the finally below closes that same
    // field. PMD's CloseResource can't see that a close happening inside a
    // different method (via the field alias) satisfies this local variable.
    @SuppressWarnings("PMD.CloseResource")
    @Override
    protected void doRun() {
        // roller is dereferenced unconditionally below (getWeblogEntryManager,
        // then release() in the finally); the guard belongs here, before
        // either happens, not after -- there is nothing this operation can do
        // without it.
        if (roller == null) {
            log.error("Weblogger unavailable; cannot index weblog entry");
            return;
        }

        // since this operation can be run on a separate thread we must treat
        // the weblog object passed in as a detached object which is proned to
        // lazy initialization problems, so requery for the object now --
        // before the writer opens, as ReIndexEntryOperation does: returning
        // here with it open leaked the writer and the directory's
        // write.lock, and every later write then failed to open its own
        try {
            WeblogEntryManager wMgr = roller.getWeblogEntryManager();
            this.data = wMgr.getWeblogEntry(this.data.getId());
        } catch (WebloggerException ex) {
            log.error("Error getting weblogentry object", ex);
            return;
        }

        IndexWriter writer = beginWriting();
        try {
            // Only a published entry belongs in the search index -- the
            // same guard ReIndexEntryOperation applies on its own add path.
            // This class has no production caller today (only tests reach
            // addEntryIndexOperation directly), but the interface exists for
            // a future one, and re-fetching by id on a background thread
            // means whatever the scheduling caller checked can already be
            // stale by the time this runs: an entry trashed or unpublished
            // between scheduling and execution must not be added.
            if (writer != null && data != null && data.isPublished()) {
                writer.addDocument(getDocument(data));
            }
        } catch (IOException e) {
            log.error("Problems adding doc to index", e);
        } finally {
            roller.release();
            endWriting();
        }
    }
}
