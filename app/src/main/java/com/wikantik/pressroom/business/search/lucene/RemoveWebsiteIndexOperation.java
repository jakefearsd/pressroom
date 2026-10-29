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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import com.wikantik.pressroom.util.PressroomConstants;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;

/**
 * An index operation that rebuilds a given users index (or all indexes).
 * 
 * @author Mindaugas Idzelis (min@idzelis.com)
 */
public class RemoveWebsiteIndexOperation extends WriteToIndexOperation {

    // ~ Static fields/initializers
    // =============================================

    private static final Logger log = LoggerFactory.getLogger(
            RemoveWebsiteIndexOperation.class);

    // ~ Instance fields
    // ========================================================

    private final Weblog website;
    private Weblogger pressroom;

    // ~ Constructors
    // ===========================================================

    /**
     * Create a new operation that will recreate an index.
     * 
     * @param website
     *            The website to rebuild the index for, or null for all sites.
     */
    public RemoveWebsiteIndexOperation(Weblogger pressroom, LuceneIndexManager mgr,
            Weblog website) {
        super(mgr);
        this.pressroom = pressroom;
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

        // The handle as it was given: a weblog being deleted is removed from
        // the index after its delete commits, when it can no longer be
        // re-queried. A weblog that still exists is re-queried, since this
        // operation can run on a separate thread and the object passed in is
        // detached.
        String handle = website.getHandle();
        String name = website.getName();
        try {
            Weblog current = pressroom.getWeblogManager().getWeblog(website.getId());
            if (current != null) {
                handle = current.getHandle();
                name = current.getName();
            }
        } catch (WebloggerException ex) {
            log.error("Error getting website object", ex);
            return;
        }

        IndexWriter writer = beginWriting();
        try {
            if (writer != null) {
                Term tHandle = IndexUtil.getTerm(FieldConstants.WEBSITE_HANDLE,
                        handle);

                if (tHandle != null) {
                    writer.deleteDocuments(tHandle);
                }
            }
        } catch (IOException e) {
            log.info("Problems deleting doc from index", e);
        } finally {
            endWriting();
        }

        Date end = new Date();
        double length = (end.getTime() - start.getTime()) / (double) PressroomConstants.SEC_IN_MS;

        log.info("Completed deleting indices for website '{}' in '{}' seconds", name, length);
    }
}
