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
import org.apache.lucene.index.Term;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.WeblogEntry;

/**
 * An operation that removes the weblog from the index.
 * 
 * @author Mindaugas Idzelis (min@idzelis.com)
 */
public class RemoveEntryOperation extends WriteToIndexOperation {

    // ~ Static fields/initializers
    // =============================================

    private static final Logger log = LoggerFactory.getLogger(
            RemoveEntryOperation.class);

    // ~ Instance fields
    // ========================================================

    private WeblogEntry data;
    private Weblogger pressroom;

    // ~ Constructors
    // ===========================================================

    public RemoveEntryOperation(Weblogger pressroom, LuceneIndexManager mgr,
            WeblogEntry data) {
        super(mgr);
        this.pressroom = pressroom;
        this.data = data;
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

        // since this operation can be run on a separate thread we must treat
        // the weblog object passed in as a detached object which is proned to
        // lazy initialization problems, so requery for the object now
        try {
            WeblogEntryManager wMgr = pressroom.getWeblogEntryManager();
            this.data = wMgr.getWeblogEntry(this.data.getId());
        } catch (WebloggerException ex) {
            log.error("Error getting weblogentry object", ex);
            return;
        }

        IndexWriter writer = beginWriting();
        try {
            if (writer != null) {
                Term term = new Term(FieldConstants.ID, data.getId());
                writer.deleteDocuments(term);
            }
        } catch (IOException e) {
            log.error("Error deleting doc from index", e);
        } finally {
            endWriting();
        }
    }

}
